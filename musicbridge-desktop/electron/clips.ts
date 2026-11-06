import { BrowserWindow, dialog } from 'electron';
import { closeSync, openSync, readFileSync, readSync, statSync } from 'node:fs';
import { basename, extname } from 'node:path';
import { probeDurationMs } from './audio-duration';
import { ConfigStore } from './config';
import type { AuthManager } from './auth';

export const MAX_CLIP_BYTES = 26214400;
const MAX_DURATION_MS = 300000;
const MIN_DURATION_MS = 500;
const CONSENT_PURPOSE = '本机文件导入：上传原始音频用于识曲';

export type ClipFormat = 'MP3' | 'M4A' | 'WAV' | 'FLAC' | 'OGG';

const FORMAT_BY_EXTENSION: Record<string, ClipFormat> = {
  '.mp3': 'MP3',
  '.m4a': 'M4A',
  '.wav': 'WAV',
  '.flac': 'FLAC',
  '.ogg': 'OGG'
};

const CONTENT_TYPE: Record<ClipFormat, string> = {
  MP3: 'audio/mpeg',
  M4A: 'audio/mp4',
  WAV: 'audio/wav',
  FLAC: 'audio/flac',
  OGG: 'audio/ogg'
};

const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export interface ClipSelection {
  fileName: string;
  format: ClipFormat | null;
  extension: string;
  sizeBytes: number;
  durationMs: number | null;
  accepted: boolean;
  /** 客户端拦截原因（非空时不允许进入上传步骤） */
  reasons: string[];
  /** 仅提示、不拦截的信息 */
  notes: string[];
}

export interface ConsentRecord {
  id: string;
  scope: string;
  purpose: string;
  grantedAt: string | null;
  expiresAt: string | null;
  retentionDays: number;
  active: boolean;
}

export interface ClipError {
  kind: string;
  message: string;
}

export interface ConsentOutcome {
  ok: boolean;
  statusCode: number | null;
  consent: ConsentRecord | null;
  error: ClipError | null;
  unauthorized: boolean;
}

export interface ClipRecord {
  clipId: string;
  consentId: string;
  format: string;
  contentType: string;
  sizeBytes: number;
  durationMs: number;
  fileName: string;
  createdAt: string;
  expiresAt: string;
  retentionDays: number;
  status: string;
}

export interface ClipUploadOutcome {
  ok: boolean;
  statusCode: number | null;
  durationMs: number;
  clip: ClipRecord | null;
  error: ClipError | null;
  unauthorized: boolean;
  /** 后端尚未提供 /api/v1/clips 时为 true，界面不得显示为上传成功 */
  endpointMissing: boolean;
}

interface SniffResult {
  format: ClipFormat | null;
  hint: string;
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function classifyNetworkError(error: unknown): ClipError {
  if (error instanceof Error) {
    if (error.name === 'AbortError' || error.name === 'TimeoutError') {
      return { kind: 'TIMEOUT', message: '请求超时' };
    }
    const code = (error as Error & { cause?: { code?: string } }).cause?.code ?? '';
    if (code === 'ECONNREFUSED') {
      return { kind: 'CONNECTION_REFUSED', message: '连接被拒绝：后端尚未启动或不可达' };
    }
    return { kind: 'REQUEST_FAILED', message: error.message };
  }
  return { kind: 'UNKNOWN', message: String(error) };
}

function readHeader(filePath: string, length = 32): Buffer | null {
  try {
    const fd = openSync(filePath, 'r');
    try {
      const buffer = Buffer.alloc(length);
      const bytes = readSync(fd, buffer, 0, length, 0);
      return buffer.subarray(0, bytes);
    } finally {
      closeSync(fd);
    }
  } catch {
    return null;
  }
}

/** 仅按容器特征做「明显不支持」的客户端拦截，最终以服务端判定为准 */
function sniffFormat(header: Buffer): SniffResult {
  if (header.length >= 3 && header.toString('latin1', 0, 3) === 'ID3') {
    return { format: 'MP3', hint: 'ID3 标记' };
  }
  if (header.length >= 2 && header[0] === 0xff && (header[1] & 0xe0) === 0xe0) {
    const layerBits = (header[1] >> 1) & 0x03;
    if (layerBits === 0) {
      return { format: null, hint: '独立 AAC（ADTS）不在支持范围' };
    }
    return { format: 'MP3', hint: 'MPEG 音频帧头' };
  }
  if (header.length >= 12 && header.toString('latin1', 0, 4) === 'RIFF' && header.toString('latin1', 8, 12) === 'WAVE') {
    return { format: 'WAV', hint: 'RIFF/WAVE 容器' };
  }
  if (header.length >= 4 && header.toString('latin1', 0, 4) === 'fLaC') {
    return { format: 'FLAC', hint: 'fLaC 容器' };
  }
  if (header.length >= 4 && header.toString('latin1', 0, 4) === 'OggS') {
    return { format: 'OGG', hint: 'Ogg 容器' };
  }
  if (header.length >= 8 && header.toString('latin1', 4, 8) === 'ftyp') {
    return { format: 'M4A', hint: 'ISO BMFF（ftyp）' };
  }
  return { format: null, hint: '无法识别的文件头' };
}

/** 客户端预检：文件大小、扩展名、格式白名单与文件头特征一致性（仅提示，不替代服务端判定） */
export function inspectClipFile(filePath: string): ClipSelection {
  const fileName = basename(filePath);
  const extension = extname(filePath).toLowerCase();
  const format = FORMAT_BY_EXTENSION[extension] ?? null;
  const reasons: string[] = [];
  const notes: string[] = [];

  let sizeBytes = 0;
  try {
    sizeBytes = statSync(filePath).size;
  } catch (error) {
    reasons.push(`无法读取文件大小：${error instanceof Error ? error.message : String(error)}`);
  }

  if (!format) {
    reasons.push(`扩展名 ${extension || '（无）'} 不在支持范围（仅 MP3 / M4A / WAV / FLAC / OGG）`);
  }
  if (sizeBytes <= 0) {
    reasons.push('文件为空');
  } else if (sizeBytes > MAX_CLIP_BYTES) {
    reasons.push(`实际字节 ${sizeBytes} 超过 25 MB（${MAX_CLIP_BYTES} 字节）上限`);
  }

  const header = readHeader(filePath);
  const sniffed = header ? sniffFormat(header) : { format: null, hint: '无法读取文件头' };
  if (format) {
    if (!sniffed.format) {
      reasons.push(`文件内容不是可识别的 MP3/M4A/WAV/FLAC/OGG 容器：${sniffed.hint}`);
    } else if (sniffed.format !== format) {
      reasons.push(`文件内容看起来是 ${sniffed.format}，与扩展名 ${extension} 不一致`);
    }
  }

  const durationMs = format && reasons.length === 0 ? probeDurationMs(filePath) : null;
  if (durationMs === null) {
    notes.push('未能从容器读取时长，将不发送 durationMs（以服务端实测为准）');
  } else if (durationMs < MIN_DURATION_MS) {
    notes.push(`容器时长 ${durationMs} ms 不足 500 ms，服务端会拒绝（客户端不拦截）`);
  } else if (durationMs > MAX_DURATION_MS) {
    notes.push(`容器时长 ${durationMs} ms 超过 5 分钟，服务端会拒绝（客户端不拦截）`);
  }

  return {
    fileName,
    format,
    extension,
    sizeBytes,
    durationMs,
    accepted: reasons.length === 0,
    reasons,
    notes
  };
}

export class ClipUploader {
  private selected: { path: string; info: ClipSelection } | null = null;

  constructor(
    private readonly store: ConfigStore,
    private readonly auth: AuthManager,
    private readonly log: (message: string) => void
  ) {}

  async selectFile(parent: BrowserWindow | null): Promise<ClipSelection | null> {
    const options: Electron.OpenDialogOptions = {
      title: '选择音频文件',
      properties: ['openFile'],
      filters: [{ name: '音频文件', extensions: ['mp3', 'm4a', 'wav', 'flac', 'ogg'] }]
    };
    const result = parent
      ? await dialog.showOpenDialog(parent, options)
      : await dialog.showOpenDialog(options);
    if (result.canceled || result.filePaths.length === 0) {
      return null;
    }

    const filePath = result.filePaths[0];
    const info = inspectClipFile(filePath);
    this.selected = info.format ? { path: filePath, info } : null;
    this.log(
      `已选择音频文件 name=${info.fileName} format=${info.format ?? 'UNKNOWN'} size=${info.sizeBytes} precheck=${info.accepted ? 'PASS' : 'BLOCK'} durationMs=${info.durationMs ?? 'N/A'}`
    );
    return info;
  }

  getSelection(): ClipSelection | null {
    return this.selected?.info ?? null;
  }

  async createConsent(retentionDays: number): Promise<ConsentOutcome> {
    if (!Number.isInteger(retentionDays) || retentionDays < 1 || retentionDays > 365) {
      return {
        ok: false,
        statusCode: null,
        consent: null,
        error: { kind: 'VALIDATION_FAILED', message: '保留天数必须是 1–365 的整数' },
        unauthorized: false
      };
    }
    const token = this.auth.getAccessToken();
    if (!token) {
      return {
        ok: false,
        statusCode: 401,
        consent: null,
        error: { kind: 'UNAUTHENTICATED', message: '请先登录' },
        unauthorized: true
      };
    }

    const config = this.store.get();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), config.requestTimeoutMs);
    try {
      const response = await fetch(`${config.apiBaseUrl}/api/v1/consents`, {
        method: 'POST',
        signal: controller.signal,
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
          Accept: 'application/json'
        },
        body: JSON.stringify({ scope: 'RAW_AUDIO', purpose: CONSENT_PURPOSE, retentionDays })
      });
      const text = await response.text();

      if (response.status === 401) {
        this.auth.invalidateSession('登录已失效，请重新登录');
        return {
          ok: false,
          statusCode: 401,
          consent: null,
          error: { kind: 'UNAUTHENTICATED', message: '登录已失效，请重新登录' },
          unauthorized: true
        };
      }
      if (!response.ok) {
        const body = parseJson(text) as { code?: unknown; message?: unknown } | null;
        const kind =
          typeof body?.code === 'string'
            ? body.code
            : response.status === 403
              ? 'FORBIDDEN'
              : response.status === 404
                ? 'NOT_FOUND'
                : `HTTP_${response.status}`;
        const message =
          typeof body?.message === 'string'
            ? body.message
            : `创建上传授权失败（HTTP ${response.status}）`;
        this.log(`创建上传授权失败 HTTP ${response.status} code=${kind}`);
        return {
          ok: false,
          statusCode: response.status,
          consent: null,
          error: { kind, message: `${message}（HTTP ${response.status} / ${kind}）` },
          unauthorized: false
        };
      }

      const body = parseJson(text) as Partial<ConsentRecord> | null;
      if (!body || typeof body.id !== 'string') {
        return {
          ok: false,
          statusCode: response.status,
          consent: null,
          error: { kind: 'INVALID_RESPONSE', message: '授权响应缺少 id' },
          unauthorized: false
        };
      }
      const consent: ConsentRecord = {
        id: body.id,
        scope: typeof body.scope === 'string' ? body.scope : 'RAW_AUDIO',
        purpose: typeof body.purpose === 'string' ? body.purpose : CONSENT_PURPOSE,
        grantedAt: typeof body.grantedAt === 'string' ? body.grantedAt : null,
        expiresAt: typeof body.expiresAt === 'string' ? body.expiresAt : null,
        retentionDays: typeof body.retentionDays === 'number' ? body.retentionDays : retentionDays,
        active: body.active !== false
      };
      this.log(
        `上传授权已创建 id=${consent.id} scope=${consent.scope} retentionDays=${consent.retentionDays} active=${consent.active}`
      );
      return { ok: true, statusCode: response.status, consent, error: null, unauthorized: false };
    } catch (error) {
      const failure = classifyNetworkError(error);
      this.log(`创建上传授权请求失败：${failure.kind} ${failure.message}`);
      return { ok: false, statusCode: null, consent: null, error: failure, unauthorized: false };
    } finally {
      clearTimeout(timer);
    }
  }

  async uploadClip(consentId: string): Promise<ClipUploadOutcome> {
    const startedAt = Date.now();
    const fail = (error: ClipError, statusCode: number | null, unauthorized = false, endpointMissing = false): ClipUploadOutcome => ({
      ok: false,
      statusCode,
      durationMs: Date.now() - startedAt,
      clip: null,
      error,
      unauthorized,
      endpointMissing
    });

    if (!UUID_PATTERN.test(consentId)) {
      return fail({ kind: 'VALIDATION_FAILED', message: '授权 ID 格式非法' }, null);
    }
    if (!this.selected || !this.selected.info.accepted) {
      return fail({ kind: 'PRECHECK_FAILED', message: '请先选择通过客户端预检的音频文件' }, null);
    }
    const token = this.auth.getAccessToken();
    if (!token) {
      return fail({ kind: 'UNAUTHENTICATED', message: '请先登录' }, 401, true);
    }
    const info = this.selected.info;

    // 上传前先向服务端确认授权仍有效，避免把 404 误判成「接口未上线」
    const verify = await this.verifyConsent(token, consentId);
    if (verify.error) {
      return fail(verify.error, verify.statusCode, verify.unauthorized);
    }

    const config = this.store.get();
    const timeoutMs = Math.max(config.requestTimeoutMs, 30000);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const buffer = readFileSync(this.selected.path);
      const form = new FormData();
      // 文本字段显式声明 text/plain：后端用 @RequestPart 读取，缺省 Content-Type 会被判为不支持而返回 415
      const textPart = (value: string): Blob => new Blob([value], { type: 'text/plain' });
      form.append('file', new Blob([buffer], { type: CONTENT_TYPE[info.format as ClipFormat] }), info.fileName);
      form.append('consentId', textPart(consentId));
      form.append('format', textPart(info.format as ClipFormat));
      // 仅当客户端读到的容器时长落在契约允许区间时才作为提示值提交；
      // 后端 durationMs 为数值型 @RequestPart，需以 application/json 提交，text/plain 会被判 415
      if (
        info.durationMs !== null &&
        info.durationMs >= MIN_DURATION_MS &&
        info.durationMs <= MAX_DURATION_MS
      ) {
        form.append('durationMs', new Blob([String(info.durationMs)], { type: 'application/json' }));
      }
      form.append('fileName', textPart(info.fileName));

      const response = await fetch(`${config.apiBaseUrl}/api/v1/clips`, {
        method: 'POST',
        signal: controller.signal,
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
        body: form
      });
      const text = await response.text();
      const body = parseJson(text) as { code?: unknown; message?: unknown } | null;

      if (response.status === 401) {
        this.auth.invalidateSession('登录已失效，请重新登录');
        this.log('上传返回 401，已清除本地会话');
        return fail({ kind: 'UNAUTHENTICATED', message: '登录已失效，请重新登录' }, 401, true);
      }
      if (response.status === 404) {
        this.log('上传返回 404，判断后端尚未提供 /api/v1/clips 接口');
        return fail(
          {
            kind: typeof body?.code === 'string' ? body.code : 'NOT_FOUND',
            message: '后端尚未提供 /api/v1/clips 接口（HTTP 404），未上传任何文件'
          },
          404,
          false,
          true
        );
      }
      if (!response.ok) {
        const error = mapClipError(response.status, body);
        this.log(`上传失败 HTTP ${response.status} code=${error.kind}`);
        return fail(error, response.status);
      }

      const parsed = body as Partial<ClipRecord> | null;
      if (!parsed || typeof parsed.clipId !== 'string') {
        return fail({ kind: 'INVALID_RESPONSE', message: '上传响应缺少 clipId' }, response.status);
      }
      const clip: ClipRecord = {
        clipId: parsed.clipId,
        consentId: typeof parsed.consentId === 'string' ? parsed.consentId : consentId,
        format: typeof parsed.format === 'string' ? parsed.format : (info.format as string),
        contentType: typeof parsed.contentType === 'string' ? parsed.contentType : CONTENT_TYPE[info.format as ClipFormat],
        sizeBytes: typeof parsed.sizeBytes === 'number' ? parsed.sizeBytes : info.sizeBytes,
        durationMs: typeof parsed.durationMs === 'number' ? parsed.durationMs : 0,
        fileName: typeof parsed.fileName === 'string' ? parsed.fileName : info.fileName,
        createdAt: typeof parsed.createdAt === 'string' ? parsed.createdAt : '',
        expiresAt: typeof parsed.expiresAt === 'string' ? parsed.expiresAt : '',
        retentionDays: typeof parsed.retentionDays === 'number' ? parsed.retentionDays : 0,
        status: typeof parsed.status === 'string' ? parsed.status : 'STORED'
      };
      this.log(
        `上传成功 clipId=${clip.clipId} format=${clip.format} size=${clip.sizeBytes} durationMs=${clip.durationMs}`
      );
      return {
        ok: true,
        statusCode: response.status,
        durationMs: Date.now() - startedAt,
        clip,
        error: null,
        unauthorized: false,
        endpointMissing: false
      };
    } catch (error) {
      const failure = classifyNetworkError(error);
      this.log(`上传请求失败：${failure.kind} ${failure.message}`);
      return fail(failure, null);
    } finally {
      clearTimeout(timer);
    }
  }

  private async verifyConsent(
    token: string,
    consentId: string
  ): Promise<{ error: ClipError | null; statusCode: number | null; unauthorized: boolean }> {
    const config = this.store.get();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), config.requestTimeoutMs);
    try {
      const response = await fetch(`${config.apiBaseUrl}/api/v1/consents/${consentId}/verify`, {
        method: 'POST',
        signal: controller.signal,
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' }
      });
      if (response.ok) {
        return { error: null, statusCode: response.status, unauthorized: false };
      }
      const text = await response.text();
      const body = parseJson(text) as { code?: unknown; message?: unknown } | null;
      if (response.status === 401) {
        this.auth.invalidateSession('登录已失效，请重新登录');
        return {
          error: { kind: 'UNAUTHENTICATED', message: '登录已失效，请重新登录' },
          statusCode: 401,
          unauthorized: true
        };
      }
      const kind = typeof body?.code === 'string' ? body.code : `HTTP_${response.status}`;
      const message =
        response.status === 403
          ? `上传授权不可用：${typeof body?.message === 'string' ? body.message : '已撤销或已过期'}（HTTP 403 / ${kind}）`
          : response.status === 404
            ? `上传授权不存在或不属于当前用户（HTTP 404 / ${kind}）`
            : `授权校验失败 HTTP ${response.status} / ${kind}`;
      this.log(`授权校验失败 HTTP ${response.status} code=${kind}`);
      return { error: { kind, message }, statusCode: response.status, unauthorized: false };
    } catch (error) {
      const failure = classifyNetworkError(error);
      this.log(`授权校验请求失败：${failure.kind} ${failure.message}`);
      return { error: failure, statusCode: null, unauthorized: false };
    } finally {
      clearTimeout(timer);
    }
  }
}

/** 按 HTTP 状态与业务 code 逐类映射为可读提示，不吞错误码 */
function mapClipError(status: number, body: { code?: unknown; message?: unknown } | null): ClipError {
  const code = typeof body?.code === 'string' ? body.code : `HTTP_${status}`;
  const serverMessage = typeof body?.message === 'string' ? body.message : '';
  const suffix = `（HTTP ${status} / ${code}）`;

  switch (status) {
    case 400:
      if (code === 'INVALID_REQUEST') {
        return { kind: code, message: `上传表单无法解析或缺少文件字段${suffix}` };
      }
      return {
        kind: code,
        message: `${serverMessage || '请求参数或音频校验未通过'}${suffix}`
      };
    case 403:
      return {
        kind: code,
        message: `${serverMessage || '上传授权已撤销、已过期或保留天数为 0'}，请重新创建授权${suffix}`
      };
    case 413:
      return { kind: code, message: `文件实际字节超过 25 MB${suffix}` };
    case 415:
      return {
        kind: code,
        message: `${serverMessage || '格式不受支持：文件内容与扩展名/格式不一致，或不是可解析的音频容器'}${suffix}`
      };
    default:
      return {
        kind: code,
        message: `${serverMessage || '上传失败'}${suffix}`
      };
  }
}
