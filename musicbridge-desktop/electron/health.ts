import type { AppConfig } from './config';

export interface HealthFailure {
  kind: string;
  message: string;
}

export interface HealthResult {
  ok: boolean;
  url: string;
  statusCode: number | null;
  durationMs: number;
  checkedAt: string;
  bodyPreview: string | null;
  openapi: string | null;
  failure: HealthFailure | null;
}

function classifyError(error: unknown): HealthFailure {
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

/**
 * 后端健康检查：直接请求后端现有免认证接口。
 * 当前后端没有 /actuator/health，默认使用 /v3/api-docs 作为存活探测。
 */
export async function runHealthCheck(config: AppConfig): Promise<HealthResult> {
  const url = `${config.apiBaseUrl}${config.healthPath}`;
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
    let openapi: string | null = null;
    try {
      const parsed = JSON.parse(text) as { openapi?: unknown };
      openapi = typeof parsed.openapi === 'string' ? parsed.openapi : null;
    } catch {
      openapi = null;
    }
    return {
      ok: response.ok,
      url,
      statusCode: response.status,
      durationMs: Date.now() - startedAt,
      checkedAt: new Date().toISOString(),
      bodyPreview: text.slice(0, 300),
      openapi,
      failure: response.ok
        ? null
        : { kind: `HTTP_${response.status}`, message: `后端返回 HTTP ${response.status}` }
    };
  } catch (error) {
    return {
      ok: false,
      url,
      statusCode: null,
      durationMs: Date.now() - startedAt,
      checkedAt: new Date().toISOString(),
      bodyPreview: null,
      openapi: null,
      failure: classifyError(error)
    };
  } finally {
    clearTimeout(timer);
  }
}
