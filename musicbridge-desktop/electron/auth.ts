import { safeStorage } from 'electron';
import { existsSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ConfigStore } from './config';

const SESSION_FILE = 'auth-session.bin';
const EXPIRY_WARNING_MS = 24 * 60 * 60 * 1000;

export interface AuthPublicStatus {
  authenticated: boolean;
  userId: string | null;
  expiresAt: string | null;
  expiresInMs: number | null;
  expiringSoon: boolean;
  encryptionAvailable: boolean;
  message: string;
}

export interface AuthError {
  kind: string;
  message: string;
}

export interface AuthOutcome {
  status: AuthPublicStatus;
  error: AuthError | null;
}

export interface ApiResult {
  ok: boolean;
  statusCode: number | null;
  durationMs: number;
  data: unknown;
  error: AuthError | null;
  unauthorized: boolean;
}

interface SessionState {
  token: string;
  userId: string;
  expiresAt: string;
}

/** 业务请求白名单：按方法与路径精确匹配，响应字段在返回渲染进程前再投影一次 */
const ALLOWED_ROUTES: Array<{
  method: string;
  path: string;
  project: (body: unknown) => unknown;
}> = [
  {
    method: 'GET',
    path: '/api/v1/auth/me',
    project: (body) => {
      const parsed = body as { userId?: unknown; expiresAt?: unknown } | null;
      return {
        userId: typeof parsed?.userId === 'string' ? parsed.userId : null,
        expiresAt: typeof parsed?.expiresAt === 'string' ? parsed.expiresAt : null
      };
    }
  }
];

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function classifyNetworkError(error: unknown): AuthError {
  if (error instanceof Error) {
    if (error.name === 'AbortError' || error.name === 'TimeoutError') {
      return { kind: 'TIMEOUT', message: '请求超时' };
    }
    const code = (error as Error & { cause?: { code?: string } }).cause?.code ?? '';
    if (code === 'ECONNREFUSED') {
      return { kind: 'CONNECTION_REFUSED', message: '连接被拒绝：后端可能尚未启动' };
    }
    return { kind: 'REQUEST_FAILED', message: error.message };
  }
  return { kind: 'UNKNOWN', message: String(error) };
}

/**
 * 主进程会话管理：令牌只在主进程内存与 safeStorage 加密文件中流转。
 * 不持久化密码，不把令牌写入日志、Renderer 或前端配置。
 */
export class AuthManager {
  private token: string | null = null;
  private userId: string | null = null;
  private expiresAt: string | null = null;
  private message = '未登录';
  private encryptionAvailable: boolean;
  private readonly sessionFile: string;

  constructor(
    private readonly store: ConfigStore,
    userDataDir: string,
    private readonly onStatus: (status: AuthPublicStatus) => void,
    private readonly log: (message: string) => void
  ) {
    this.sessionFile = join(userDataDir, SESSION_FILE);
    this.encryptionAvailable = safeStorage.isEncryptionAvailable();
  }

  restore(): void {
    if (!existsSync(this.sessionFile)) {
      return;
    }
    this.encryptionAvailable = safeStorage.isEncryptionAvailable();
    if (!this.encryptionAvailable) {
      this.message = '存在已保存会话，但当前系统 safeStorage 不可用，请重新登录';
      return;
    }
    try {
      const encrypted = Buffer.from(readFileSync(this.sessionFile, 'utf-8').trim(), 'base64');
      const parsed = parseJson(safeStorage.decryptString(encrypted)) as Partial<SessionState> | null;
      if (
        !parsed ||
        typeof parsed.token !== 'string' ||
        typeof parsed.userId !== 'string' ||
        typeof parsed.expiresAt !== 'string'
      ) {
        throw new Error('会话文件字段不完整');
      }
      if (new Date(parsed.expiresAt).getTime() <= Date.now()) {
        this.clearSession('会话已过期，请重新登录');
        return;
      }
      this.token = parsed.token;
      this.userId = parsed.userId;
      this.expiresAt = parsed.expiresAt;
      this.message = '已恢复本机会话';
      this.log(`已恢复本机会话 userId=${parsed.userId}`);
    } catch (error) {
      this.clearSession('无法读取已保存会话，请重新登录');
      this.log(`恢复会话失败：${error instanceof Error ? error.message : String(error)}`);
    }
  }

  getStatus(): AuthPublicStatus {
    this.checkExpiry();
    return this.readStatus();
  }

  checkExpiry(): void {
    if (this.token && this.remainingMs() !== null && (this.remainingMs() as number) <= 0) {
      this.clearSession('会话已过期，请重新登录');
    }
  }

  async login(username: string, password: string): Promise<AuthOutcome> {
    const config = this.store.get();
    const url = `${config.apiBaseUrl}/api/v1/auth/login`;
    const startedAt = Date.now();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), config.requestTimeoutMs);

    try {
      const response = await fetch(url, {
        method: 'POST',
        signal: controller.signal,
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ username, password })
      });
      const text = await response.text();

      if (!response.ok) {
        const body = parseJson(text) as { code?: unknown; message?: unknown } | null;
        const message =
          typeof body?.message === 'string' ? body.message : `登录失败（HTTP ${response.status}）`;
        const kind =
          typeof body?.code === 'string'
            ? body.code
            : response.status === 401
              ? 'UNAUTHENTICATED'
              : `HTTP_${response.status}`;
        this.log(`登录失败：HTTP ${response.status} code=${kind}`);
        return { status: this.readStatus(), error: { kind, message } };
      }

      const body = parseJson(text) as { token?: unknown; expiresAt?: unknown; userId?: unknown } | null;
      if (
        !body ||
        typeof body.token !== 'string' ||
        typeof body.expiresAt !== 'string' ||
        typeof body.userId !== 'string'
      ) {
        this.log('登录失败：响应缺少 token/expiresAt/userId');
        return {
          status: this.readStatus(),
          error: { kind: 'INVALID_RESPONSE', message: '登录响应缺少 token/expiresAt/userId' }
        };
      }
      if (new Date(body.expiresAt).getTime() <= Date.now()) {
        return {
          status: this.readStatus(),
          error: { kind: 'INVALID_RESPONSE', message: '登录响应中的会话已过期' }
        };
      }

      this.token = body.token;
      this.userId = body.userId;
      this.expiresAt = body.expiresAt;
      this.message = '登录成功';
      this.persist();
      this.log(`登录成功 userId=${body.userId}，耗时 ${Date.now() - startedAt}ms，令牌仅保存在主进程`);
      this.onStatus(this.readStatus());
      return { status: this.readStatus(), error: null };
    } catch (error) {
      const failure = classifyNetworkError(error);
      this.log(`登录请求失败：${failure.kind} ${failure.message}`);
      return { status: this.readStatus(), error: failure };
    } finally {
      clearTimeout(timer);
    }
  }

  async logout(): Promise<AuthOutcome> {
    const token = this.token;
    if (!token) {
      return { status: this.readStatus(), error: null };
    }

    const config = this.store.get();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), config.requestTimeoutMs);
    let error: AuthError | null = null;
    try {
      const response = await fetch(`${config.apiBaseUrl}/api/v1/auth/logout`, {
        method: 'POST',
        signal: controller.signal,
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' }
      });
      this.log(`注销请求返回 HTTP ${response.status}`);
      if (!response.ok && response.status !== 401) {
        error = {
          kind: `HTTP_${response.status}`,
          message: `后端注销返回 HTTP ${response.status}，本地令牌已清除`
        };
      }
    } catch (networkError) {
      const failure = classifyNetworkError(networkError);
      error = { ...failure, message: `${failure.message}，本地令牌已清除` };
      this.log(`注销请求失败：${failure.kind} ${failure.message}`);
    } finally {
      clearTimeout(timer);
    }

    this.clearSession('已注销，本地令牌已清除');
    return { status: this.readStatus(), error };
  }

  /** 业务请求统一在获取令牌后由主进程附加 Authorization */
  async request(method: string, path: string): Promise<ApiResult> {
    const route = ALLOWED_ROUTES.find((item) => item.method === method && item.path === path);
    if (!route) {
      return {
        ok: false,
        statusCode: null,
        durationMs: 0,
        data: null,
        error: { kind: 'NOT_ALLOWED', message: `请求不在白名单内：${method} ${path}` },
        unauthorized: false
      };
    }
    if (!this.token) {
      return {
        ok: false,
        statusCode: 401,
        durationMs: 0,
        data: null,
        error: { kind: 'UNAUTHENTICATED', message: '尚未登录' },
        unauthorized: true
      };
    }

    const config = this.store.get();
    const startedAt = Date.now();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), config.requestTimeoutMs);
    try {
      const response = await fetch(`${config.apiBaseUrl}${route.path}`, {
        method: route.method,
        signal: controller.signal,
        headers: { Authorization: `Bearer ${this.token}`, Accept: 'application/json' }
      });
      const text = await response.text();
      const durationMs = Date.now() - startedAt;

      if (response.status === 401) {
        this.clearSession('登录已失效，请重新登录');
        return {
          ok: false,
          statusCode: 401,
          durationMs,
          data: null,
          error: { kind: 'UNAUTHENTICATED', message: '登录已失效，请重新登录' },
          unauthorized: true
        };
      }

      const body = parseJson(text);
      if (!response.ok) {
        const errorBody = body as { code?: unknown; message?: unknown } | null;
        return {
          ok: false,
          statusCode: response.status,
          durationMs,
          data: null,
          error: {
            kind: typeof errorBody?.code === 'string' ? errorBody.code : `HTTP_${response.status}`,
            message:
              typeof errorBody?.message === 'string'
                ? errorBody.message
                : `请求失败（HTTP ${response.status}）`
          },
          unauthorized: false
        };
      }

      return {
        ok: true,
        statusCode: response.status,
        durationMs,
        data: route.project(body),
        error: null,
        unauthorized: false
      };
    } catch (error) {
      return {
        ok: false,
        statusCode: null,
        durationMs: Date.now() - startedAt,
        data: null,
        error: classifyNetworkError(error),
        unauthorized: false
      };
    } finally {
      clearTimeout(timer);
    }
  }

  private readStatus(): AuthPublicStatus {
    const expiresInMs = this.remainingMs();
    return {
      authenticated: this.token !== null,
      userId: this.userId,
      expiresAt: this.expiresAt,
      expiresInMs,
      expiringSoon: expiresInMs !== null && expiresInMs > 0 && expiresInMs <= EXPIRY_WARNING_MS,
      encryptionAvailable: this.encryptionAvailable,
      message: this.message
    };
  }

  private remainingMs(): number | null {
    if (!this.expiresAt) {
      return null;
    }
    return new Date(this.expiresAt).getTime() - Date.now();
  }

  private persist(): void {
    if (!this.token || !this.userId || !this.expiresAt) {
      return;
    }
    if (!this.encryptionAvailable) {
      this.log('safeStorage 不可用，令牌仅保存在内存中');
      return;
    }
    try {
      const payload: SessionState = {
        token: this.token,
        userId: this.userId,
        expiresAt: this.expiresAt
      };
      const encrypted = safeStorage.encryptString(JSON.stringify(payload));
      writeFileSync(this.sessionFile, encrypted.toString('base64'), 'utf-8');
    } catch (error) {
      this.log(`令牌持久化失败：${error instanceof Error ? error.message : String(error)}`);
    }
  }

  private clearSession(reason: string): void {
    this.token = null;
    this.userId = null;
    this.expiresAt = null;
    this.message = reason;
    try {
      if (existsSync(this.sessionFile)) {
        unlinkSync(this.sessionFile);
      }
    } catch (error) {
      this.log(`删除会话文件失败：${error instanceof Error ? error.message : String(error)}`);
    }
    this.onStatus(this.readStatus());
  }
}
