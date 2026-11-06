import { spawn, type ChildProcess } from 'node:child_process';
import { appendFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { AppConfig } from './config';
import { ConfigStore } from './config';
import { runHealthCheck, type HealthResult } from './health';

export type RuntimeState = 'STARTING' | 'UP' | 'DOWN' | 'STOPPING';

export interface RuntimeStatus {
  state: RuntimeState;
  /** 后端进程是否由本应用拉起 */
  managedByApp: boolean;
  pid: number | null;
  checkedAt: string | null;
  health: HealthResult | null;
  message: string;
}

/**
 * 后端进程与健康状态监管：
 * - 周期性请求健康检查接口，结果推送给渲染进程；
 * - 可由桌面端按本地环境变量文件拉起/停止后端，不改后端代码。
 */
export class BackendSupervisor {
  private child: ChildProcess | null = null;
  private timer: NodeJS.Timeout | null = null;
  private refreshing = false;
  private startupDeadline = 0;
  private status: RuntimeStatus = {
    state: 'DOWN',
    managedByApp: false,
    pid: null,
    checkedAt: null,
    health: null,
    message: '尚未检查后端状态'
  };

  constructor(
    private readonly store: ConfigStore,
    private readonly appRoot: string,
    private readonly evidenceDir: string,
    private readonly onStatus: (status: RuntimeStatus) => void,
    private readonly log: (message: string) => void
  ) {}

  getStatus(): RuntimeStatus {
    return this.status;
  }

  start(): void {
    this.log(`健康检查路径 ${this.store.get().healthPath}，API Base URL ${this.store.get().apiBaseUrl}`);
    void this.refreshThenSchedule();
  }

  async refreshNow(): Promise<RuntimeStatus> {
    await this.refresh();
    return this.status;
  }

  async startBackend(): Promise<RuntimeStatus> {
    if (this.child) {
      return this.status;
    }

    const config = this.store.get();
    const envFile = this.store.resolveEnvFile(config);
    if (!existsSync(envFile)) {
      this.updateStatus({
        ...this.status,
        managedByApp: false,
        pid: null,
        message: `未找到本地启动环境变量文件 ${config.backend.envFile}，请按页面指引手动启动后端`
      });
      return this.status;
    }

    const env = this.readLocalEnv(envFile);
    const cwd = this.store.resolveBackendCwd(config);
    this.log(`启动后端：${config.backend.command} ${config.backend.args.join(' ')}（cwd=${cwd}）`);

    const runLog = join(this.evidenceDir, `backend-run-${stamp()}.log`);
    const child = spawn(config.backend.command, config.backend.args, {
      cwd,
      env: { ...process.env, ...env },
      shell: true,
      windowsHide: true
    });

    this.child = child;
    this.startupDeadline = Date.now() + config.backend.startupTimeoutMs;

    child.stdout?.on('data', (chunk: Buffer) => appendFileSync(runLog, chunk.toString()));
    child.stderr?.on('data', (chunk: Buffer) => appendFileSync(runLog, chunk.toString()));

    child.on('error', (error) => {
      this.log(`后端进程启动失败：${error.message}`);
      appendFileSync(runLog, `\n[spawn error] ${error.message}\n`);
    });

    child.on('exit', (code, signal) => {
      this.log(`后端进程退出：code=${code ?? 'null'} signal=${signal ?? 'null'}`);
      appendFileSync(runLog, `\n[exit] code=${code ?? 'null'} signal=${signal ?? 'null'}\n`);
      this.child = null;
      this.updateStatus({
        ...this.status,
        managedByApp: false,
        pid: null,
        state: this.status.health?.ok ? 'UP' : 'DOWN',
        message: this.status.health?.ok
          ? '后端已连接（外部进程）'
          : `后端进程已退出（code=${code ?? 'null'}）`
      });
      void this.refreshThenSchedule();
    });

    this.updateStatus({
      ...this.status,
      state: 'STARTING',
      managedByApp: true,
      pid: child.pid ?? null,
      message: '后端进程启动中，等待健康检查通过'
    });
    void this.refreshThenSchedule();
    return this.status;
  }

  async stopBackend(): Promise<RuntimeStatus> {
    if (!this.child) {
      return this.status;
    }
    const pid = this.child.pid;
    this.updateStatus({
      ...this.status,
      state: 'STOPPING',
      message: '正在停止后端进程'
    });
    this.log(`停止后端进程：pid=${pid ?? 'unknown'}`);
    if (pid) {
      spawn('taskkill', ['/PID', String(pid), '/T', '/F'], { shell: true, windowsHide: true });
    } else {
      this.child.kill();
    }
    return this.status;
  }

  /** 应用退出时清理由本应用拉起的后端进程 */
  shutdown(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    const pid = this.child?.pid;
    this.child = null;
    if (pid) {
      spawn('taskkill', ['/PID', String(pid), '/T', '/F'], { shell: true, windowsHide: true });
    }
  }

  private async refreshThenSchedule(): Promise<void> {
    await this.refresh();
    this.scheduleNext();
  }

  private scheduleNext(): void {
    if (this.timer) {
      clearTimeout(this.timer);
    }
    const interval = this.status.state === 'STARTING' ? 1000 : this.store.get().healthRefreshMs;
    this.timer = setTimeout(() => void this.refreshThenSchedule(), interval);
  }

  private async refresh(): Promise<void> {
    if (this.refreshing) {
      return;
    }
    this.refreshing = true;
    try {
      const config = this.store.get();
      const health = await runHealthCheck(config);
      const state = this.resolveState(health);
      const message = this.describeState(state, health);
      this.updateStatus({
        state,
        managedByApp: this.child !== null,
        pid: this.child?.pid ?? null,
        checkedAt: health.checkedAt,
        health,
        message
      });
      this.persistHealth(config, health);
    } catch (error) {
      this.log(`健康检查异常：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      this.refreshing = false;
    }
  }

  private resolveState(health: HealthResult): RuntimeState {
    if (this.child) {
      if (health.ok) {
        return 'UP';
      }
      return Date.now() > this.startupDeadline ? 'DOWN' : 'STARTING';
    }
    return health.ok ? 'UP' : 'DOWN';
  }

  private describeState(state: RuntimeState, health: HealthResult): string {
    if (state === 'UP') {
      return this.child ? '后端运行中（本应用启动）' : '后端已连接（外部启动）';
    }
    if (state === 'STARTING') {
      return '后端进程启动中，等待健康检查通过';
    }
    if (this.child) {
      return '启动超时仍未通过健康检查，请查看证据目录下的后端运行日志';
    }
    return health.failure ? `未连接：${health.failure.message}` : '未连接';
  }

  private updateStatus(next: RuntimeStatus): void {
    const prev = this.status;
    this.status = next;
    if (prev.state !== next.state || prev.message !== next.message) {
      appendFileSync(
        join(this.evidenceDir, 'runtime.log'),
        `[${new Date().toISOString()}] ${prev.state} -> ${next.state} | ${next.message}\n`
      );
    }
    this.onStatus(next);
  }

  private persistHealth(config: AppConfig, health: HealthResult): void {
    appendFileSync(join(this.evidenceDir, 'health-history.jsonl'), `${JSON.stringify(health)}\n`);
    // 最近一次结果单独落盘并覆盖，便于验收直接查阅
    writeFileSync(
      join(this.evidenceDir, 'health-check.json'),
      `${JSON.stringify({ config: { apiBaseUrl: config.apiBaseUrl, healthPath: config.healthPath }, health }, null, 2)}\n`
    );
  }

  private readLocalEnv(file: string): Record<string, string> {
    try {
      const parsed = JSON.parse(readFileSync(file, 'utf-8')) as Record<string, unknown>;
      const env: Record<string, string> = {};
      for (const [key, value] of Object.entries(parsed)) {
        if (key === 'MYSQL_PASSWORD' && typeof value === 'string') {
          env[key] = value;
        } else if (typeof value === 'string' || typeof value === 'number') {
          env[key] = String(value);
        }
      }
      return env;
    } catch (error) {
      this.log(`读取本地环境变量文件失败：${error instanceof Error ? error.message : String(error)}`);
      return {};
    }
  }
}

function stamp(): string {
  return new Date().toISOString().replace(/[:.]/g, '-');
}
