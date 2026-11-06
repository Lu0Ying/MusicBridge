import { spawn, type ChildProcess } from 'node:child_process';
import { appendFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { AppConfig } from './config';
import { ConfigStore } from './config';
import { runProbe, type ProbeResult } from './health';

export type RuntimeState = 'STARTING' | 'UP' | 'NOT_READY' | 'DOWN' | 'STOPPING';

export interface RuntimeStatus {
  state: RuntimeState;
  /** 后端进程是否由本应用拉起 */
  managedByApp: boolean;
  pid: number | null;
  checkedAt: string | null;
  /** /health：进程存活探测 */
  health: ProbeResult | null;
  /** /ready：业务就绪探测，/health 失败时跳过 */
  ready: ProbeResult | null;
  message: string;
}

/**
 * 后端进程与状态监管：
 * - 先 /health 判断进程存活，再 /ready 判断业务就绪，结果推送给渲染进程；
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
    ready: null,
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
    const config = this.store.get();
    this.log(`存活探测 ${config.healthPath}，就绪探测 ${config.readyPath}，API Base URL ${config.apiBaseUrl}`);
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
        state: 'DOWN',
        message: `后端进程已退出（code=${code ?? 'null'}）`
      });
      void this.refreshThenSchedule();
    });

    this.updateStatus({
      ...this.status,
      state: 'STARTING',
      managedByApp: true,
      pid: child.pid ?? null,
      message: '后端进程启动中，等待存活与就绪检查'
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
      // 顺序固定：先存活，再就绪；存活失败时不再请求就绪端点
      const health = await runProbe(config, config.healthPath);
      const ready = health.ok ? await runProbe(config, config.readyPath) : null;
      const state = this.resolveState(health, ready);
      const message = this.describeState(state, health, ready);
      this.updateStatus({
        state,
        managedByApp: this.child !== null,
        pid: this.child?.pid ?? null,
        checkedAt: health.checkedAt,
        health,
        ready,
        message
      });
      this.persistProbes(config, health, ready);
    } catch (error) {
      this.log(`后端探测异常：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      this.refreshing = false;
    }
  }

  private resolveState(health: ProbeResult, ready: ProbeResult | null): RuntimeState {
    if (this.child && !health.ok) {
      return Date.now() > this.startupDeadline ? 'DOWN' : 'STARTING';
    }
    if (!health.ok) {
      return 'DOWN';
    }
    return ready?.ok ? 'UP' : 'NOT_READY';
  }

  private describeState(
    state: RuntimeState,
    health: ProbeResult,
    ready: ProbeResult | null
  ): string {
    if (state === 'UP') {
      return this.child ? '后端已就绪（本应用启动）' : '后端已就绪（外部启动）';
    }
    if (state === 'NOT_READY') {
      const reason = ready?.failure?.message ?? '就绪探测未通过';
      const detail = ready?.detail ? `（${ready.detail}）` : '';
      return `后端存活但未就绪：${reason}${detail}`;
    }
    if (state === 'STARTING') {
      return '后端进程启动中，等待存活与就绪检查';
    }
    if (this.child) {
      return '启动超时仍未通过存活检查，请查看证据目录下的后端运行日志';
    }
    if (health.failure) {
      return `后端未启动或不可达：${health.failure.message}`;
    }
    return '后端未启动';
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

  private persistProbes(
    config: AppConfig,
    health: ProbeResult,
    ready: ProbeResult | null
  ): void {
    const record = {
      checkedAt: health.checkedAt,
      config: {
        apiBaseUrl: config.apiBaseUrl,
        healthPath: config.healthPath,
        readyPath: config.readyPath
      },
      health,
      ready
    };
    appendFileSync(join(this.evidenceDir, 'probe-history.jsonl'), `${JSON.stringify(record)}\n`);
    writeFileSync(
      join(this.evidenceDir, 'probe-latest.json'),
      `${JSON.stringify(record, null, 2)}\n`
    );
  }

  private readLocalEnv(file: string): Record<string, string> {
    try {
      const parsed = JSON.parse(readFileSync(file, 'utf-8')) as Record<string, unknown>;
      const env: Record<string, string> = {};
      for (const [key, value] of Object.entries(parsed)) {
        if (typeof value === 'string' || typeof value === 'number') {
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
