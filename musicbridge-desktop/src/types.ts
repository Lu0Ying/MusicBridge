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
  readyPath: string;
  requestTimeoutMs: number;
  healthRefreshMs: number;
  backend: BackendLaunchConfig;
}

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
  detail: string | null;
  failure: ProbeFailure | null;
}

export type RuntimeState = 'STARTING' | 'UP' | 'NOT_READY' | 'DOWN' | 'STOPPING';

export interface RuntimeStatus {
  state: RuntimeState;
  managedByApp: boolean;
  pid: number | null;
  checkedAt: string | null;
  health: ProbeResult | null;
  ready: ProbeResult | null;
  message: string;
}

export interface AuthPublicStatus {
  authenticated: boolean;
  userId: string | null;
  expiresAt: string | null;
  expiresInMs: number | null;
  expiringSoon: boolean;
  encryptionAvailable: boolean;
  persistence: 'encrypted' | 'memory';
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

export type ClipFormat = 'MP3' | 'M4A' | 'WAV' | 'FLAC' | 'OGG';

export interface ClipSelection {
  fileName: string;
  format: ClipFormat | null;
  extension: string;
  sizeBytes: number;
  durationMs: number | null;
  accepted: boolean;
  reasons: string[];
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

export interface ConsentOutcome {
  ok: boolean;
  statusCode: number | null;
  consent: ConsentRecord | null;
  error: AuthError | null;
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
  error: AuthError | null;
  unauthorized: boolean;
  endpointMissing: boolean;
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
  login: (username: string, password: string) => Promise<AuthOutcome>;
  logout: () => Promise<AuthOutcome>;
  getAuthStatus: () => Promise<AuthPublicStatus>;
  requestApi: (method: string, path: string) => Promise<ApiResult>;
  selectClipFile: () => Promise<ClipSelection | null>;
  getClipSelection: () => Promise<ClipSelection | null>;
  createConsent: (retentionDays: number) => Promise<ConsentOutcome>;
  uploadClip: (consentId: string) => Promise<ClipUploadOutcome>;
  onStatus: (callback: (status: RuntimeStatus) => void) => () => void;
  onAuthStatus: (callback: (status: AuthPublicStatus) => void) => () => void;
  versions: {
    electron: string;
    node: string;
    chrome: string;
  };
}
