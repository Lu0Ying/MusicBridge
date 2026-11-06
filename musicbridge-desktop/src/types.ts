export interface BackendLaunchConfig {
  cwd: string;
  command: string;
  args: string[];
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

export type RuntimeState = 'STARTING' | 'UP' | 'DOWN' | 'STOPPING';

export interface RuntimeStatus {
  state: RuntimeState;
  managedByApp: boolean;
  pid: number | null;
  checkedAt: string | null;
  health: HealthResult | null;
  message: string;
}

export interface MusicBridgeApi {
  getConfig: () => Promise<AppConfig>;
  updateApiBaseUrl: (value: string) => Promise<AppConfig>;
  getStatus: () => Promise<RuntimeStatus>;
  refreshStatus: () => Promise<RuntimeStatus>;
  startBackend: () => Promise<RuntimeStatus>;
  stopBackend: () => Promise<RuntimeStatus>;
  captureScreenshot: () => Promise<string | null>;
  getEvidenceDir: () => Promise<string>;
  onStatus: (callback: (status: RuntimeStatus) => void) => () => void;
  versions: {
    electron: string;
    node: string;
    chrome: string;
  };
}
