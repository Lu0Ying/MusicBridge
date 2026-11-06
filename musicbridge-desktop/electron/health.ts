import type { AppConfig } from './config';

export interface ProbeFailure {
  kind: string;
  message: string;
}

export interface ProbeResult {
  ok: boolean;
  path: string;
  url: string;
  statusCode: number | null;
  durationMs: number;
  checkedAt: string;
  /** 仅保留后端返回的公开状态字段，不透传原始响应体 */
  detail: string | null;
  failure: ProbeFailure | null;
}

function classifyError(error: unknown): ProbeFailure {
  if (error instanceof Error) {
    const name = error.name;
    if (name === 'AbortError' || name === 'TimeoutError') {
      return { kind: 'TIMEOUT', message: '请求超时' };
    }
    const cause = (error as Error & { cause?: { code?: string; message?: string } }).cause;
    const code = cause?.code ?? '';
    if (code === 'ECONNREFUSED') {
      return { kind: 'CONNECTION_REFUSED', message: '连接被拒绝：端口未监听，后端可能尚未启动' };
    }
    if (code === 'ENOTFOUND') {
      return { kind: 'HOST_NOT_FOUND', message: '无法解析 API Base URL 中的主机' };
    }
    if (code === 'ECONNRESET') {
      return { kind: 'CONNECTION_RESET', message: '连接被重置' };
    }
    return { kind: 'REQUEST_FAILED', message: error.message };
  }
  return { kind: 'UNKNOWN', message: String(error) };
}

/** 从就绪响应中提取公开状态字段，避免把内部错误细节带到界面 */
function extractDetail(text: string): string | null {
  try {
    const parsed = JSON.parse(text) as Record<string, unknown>;
    const parts: string[] = [];
    if (typeof parsed.status === 'string') {
      parts.push(`status=${parsed.status}`);
    }
    if (typeof parsed.code === 'string') {
      parts.push(`code=${parsed.code}`);
    }
    if (parsed.checks && typeof parsed.checks === 'object') {
      const checks = parsed.checks as Record<string, unknown>;
      for (const [name, value] of Object.entries(checks)) {
        if (typeof value === 'string') {
          parts.push(`${name}=${value}`);
        } else if (value && typeof value === 'object' && typeof (value as { status?: unknown }).status === 'string') {
          parts.push(`${name}=${(value as { status: string }).status}`);
        }
      }
    }
    return parts.length > 0 ? parts.join(', ') : null;
  } catch {
    return null;
  }
}

/**
 * 对单个后端探测端点发起真实请求。
 * /health 用于进程存活，/ready 用于业务就绪，两者都不携带认证令牌。
 */
export async function runProbe(config: AppConfig, path: string): Promise<ProbeResult> {
  const url = `${config.apiBaseUrl}${path}`;
  const startedAt = Date.now();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), config.requestTimeoutMs);

  try {
    const response = await fetch(url, {
      method: 'GET',
      signal: controller.signal,
      headers: { Accept: 'application/json' }
    });
    const text = await response.text();
    return {
      ok: response.ok,
      path,
      url,
      statusCode: response.status,
      durationMs: Date.now() - startedAt,
      checkedAt: new Date().toISOString(),
      detail: extractDetail(text),
      failure: response.ok
        ? null
        : {
            kind: `HTTP_${response.status}`,
            message:
              response.status === 503
                ? '业务未就绪（HTTP 503）'
                : response.status === 404
                  ? `探测端点不存在（HTTP 404）`
                  : response.status === 401
                    ? '探测被认证拦截（HTTP 401）'
                    : `后端返回 HTTP ${response.status}`
          }
    };
  } catch (error) {
    return {
      ok: false,
      path,
      url,
      statusCode: null,
      durationMs: Date.now() - startedAt,
      checkedAt: new Date().toISOString(),
      detail: null,
      failure: classifyError(error)
    };
  } finally {
    clearTimeout(timer);
  }
}
