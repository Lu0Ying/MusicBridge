import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

export interface BackendLaunchConfig {
  /** 相对桌面端工程根目录的后端工程路径 */
  cwd: string;
  command: string;
  args: string[];
  /** 相对桌面端工程根目录的本地环境变量文件（不入库） */
  envFile: string;
  startupTimeoutMs: number;
}

export interface AppConfig {
  apiBaseUrl: string;
  healthPath: string;
  requestTimeoutMs: number;
  healthRefreshMs: number;
  backend: BackendLaunchConfig;
}

const FALLBACK_CONFIG: AppConfig = {
  apiBaseUrl: 'http://127.0.0.1:18080',
  healthPath: '/v3/api-docs',
  requestTimeoutMs: 8000,
  healthRefreshMs: 5000,
  backend: {
    cwd: '../musicbridge-backend',
    command: 'mvnw.cmd',
    args: ['-q', 'spring-boot:run'],
    envFile: '.local/backend.env.json',
    startupTimeoutMs: 120000
  }
};

export function normalizeApiBaseUrl(raw: string): string {
  const value = raw.trim().replace(/\/+$/, '');
  if (!value) {
    throw new Error('API Base URL 不能为空');
  }
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error('API Base URL 不是合法的 URL');
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error('API Base URL 仅支持 http/https');
  }
  return value;
}

/**
 * 配置优先级：仓库内默认配置 < 用户本地覆盖（userData/app-config.json）。
 * 本地覆盖只保存用户显式修改过的字段，默认值始终以仓库配置为准。
 */
export class ConfigStore {
  private readonly defaultFile: string;
  private readonly overrideFile: string;

  constructor(
    private readonly appRoot: string,
    userDataDir: string
  ) {
    this.defaultFile = join(appRoot, 'config', 'app.config.json');
    this.overrideFile = join(userDataDir, 'app-config.json');
  }

  get(): AppConfig {
    const defaults = this.readDefault();
    const override = this.readOverride();
    return {
      ...defaults,
      ...override,
      backend: { ...defaults.backend, ...(override.backend ?? {}) }
    };
  }

  setApiBaseUrl(raw: string): AppConfig {
    const apiBaseUrl = normalizeApiBaseUrl(raw);
    const current = this.readOverride();
    writeFileSync(this.overrideFile, JSON.stringify({ ...current, apiBaseUrl }, null, 2), 'utf-8');
    return this.get();
  }

  getOverrideFile(): string {
    return this.overrideFile;
  }

  resolveBackendCwd(config: AppConfig): string {
    return resolve(this.appRoot, config.backend.cwd);
  }

  resolveEnvFile(config: AppConfig): string {
    return resolve(this.appRoot, config.backend.envFile);
  }

  private readDefault(): AppConfig {
    if (!existsSync(this.defaultFile)) {
      return FALLBACK_CONFIG;
    }
    try {
      const parsed = JSON.parse(readFileSync(this.defaultFile, 'utf-8')) as Partial<AppConfig>;
      return {
        ...FALLBACK_CONFIG,
        ...parsed,
        backend: { ...FALLBACK_CONFIG.backend, ...(parsed.backend ?? {}) }
      };
    } catch {
      return FALLBACK_CONFIG;
    }
  }

  private readOverride(): Partial<AppConfig> {
    if (!existsSync(this.overrideFile)) {
      return {};
    }
    try {
      return JSON.parse(readFileSync(this.overrideFile, 'utf-8')) as Partial<AppConfig>;
    } catch {
      return {};
    }
  }
}
