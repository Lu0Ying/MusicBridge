import { useEffect, useState } from 'react';
import type { AppConfig, RuntimeStatus } from './types';

const STATE_LABEL: Record<RuntimeStatus['state'], string> = {
  STARTING: '启动中',
  UP: '已连接',
  DOWN: '未连接',
  STOPPING: '停止中'
};

function formatTime(value: string | null): string {
  if (!value) {
    return '尚未检查';
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}

export default function App(): JSX.Element {
  const [config, setConfig] = useState<AppConfig | null>(null);
  const [status, setStatus] = useState<RuntimeStatus | null>(null);
  const [input, setInput] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState('');
  const [evidenceDir, setEvidenceDir] = useState('');
  const [evidenceFile, setEvidenceFile] = useState('');

  useEffect(() => {
    let alive = true;
    const unsubscribe = window.musicbridge.onStatus((next) => {
      if (alive) {
        setStatus(next);
      }
    });

    Promise.all([
      window.musicbridge.getConfig(),
      window.musicbridge.getStatus(),
      window.musicbridge.getEvidenceDir()
    ])
      .then(([loadedConfig, loadedStatus, dir]) => {
        if (!alive) {
          return;
        }
        setConfig(loadedConfig);
        setInput(loadedConfig.apiBaseUrl);
        setStatus(loadedStatus);
        setEvidenceDir(dir);
      })
      .catch((error: unknown) => {
        if (alive) {
          setNotice(`初始化失败：${error instanceof Error ? error.message : String(error)}`);
        }
      });

    const refresh = window.setInterval(() => {
      window.musicbridge.refreshStatus().catch(() => undefined);
    }, 10000);

    return () => {
      alive = false;
      unsubscribe();
      window.clearInterval(refresh);
    };
  }, []);

  async function handleSave(): Promise<void> {
    setBusy('save');
    setNotice('');
    try {
      const saved = await window.musicbridge.updateApiBaseUrl(input);
      setConfig(saved);
      setInput(saved.apiBaseUrl);
      setStatus(await window.musicbridge.getStatus());
      setNotice('API Base URL 已保存到本机用户配置，并已重新检查。');
    } catch (error) {
      setNotice(`保存失败：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setBusy('');
    }
  }

  async function handleRefresh(): Promise<void> {
    setBusy('refresh');
    setNotice('');
    try {
      setStatus(await window.musicbridge.refreshStatus());
    } catch (error) {
      setNotice(`检查失败：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setBusy('');
    }
  }

  async function handleStart(): Promise<void> {
    setBusy('start');
    setNotice('');
    try {
      setStatus(await window.musicbridge.startBackend());
    } catch (error) {
      setNotice(`启动失败：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setBusy('');
    }
  }

  async function handleStop(): Promise<void> {
    setBusy('stop');
    setNotice('');
    try {
      setStatus(await window.musicbridge.stopBackend());
    } catch (error) {
      setNotice(`停止失败：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setBusy('');
    }
  }

  async function handleCapture(): Promise<void> {
    setBusy('capture');
    setNotice('');
    try {
      const file = await window.musicbridge.captureScreenshot();
      setEvidenceFile(file ? `窗口截图已保存：${file}` : '当前没有可截图的窗口');
    } catch (error) {
      setNotice(`截图失败：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setBusy('');
    }
  }

  const health = status?.health ?? null;
  const state = status?.state ?? 'DOWN';

  return (
    <main className="app">
      <header className="masthead">
        <div>
          <h1>MusicBridge</h1>
          <p className="subtitle">Phase 1.1 最小桌面端 · Electron + React + TypeScript</p>
        </div>
        <div className={`state state-${state.toLowerCase()}`}>{STATE_LABEL[state]}</div>
      </header>

      <section>
        <h2>连接配置</h2>
        <div className="field">
          <label htmlFor="api-base-url">API Base URL</label>
          <input
            id="api-base-url"
            value={input}
            spellCheck={false}
            onChange={(event) => setInput(event.target.value)}
            disabled={!config}
          />
          <button type="button" onClick={() => void handleSave()} disabled={!config || busy !== ''}>
            {busy === 'save' ? '保存中…' : '保存并检查'}
          </button>
        </div>
        <dl className="facts">
          <div>
            <dt>当前生效地址</dt>
            <dd>{config?.apiBaseUrl ?? '加载中…'}</dd>
          </div>
          <div>
            <dt>健康检查路径</dt>
            <dd>{config?.healthPath ?? '加载中…'}</dd>
          </div>
          <div>
            <dt>请求超时 / 自动刷新</dt>
            <dd>{config ? `${config.requestTimeoutMs} ms / ${config.healthRefreshMs} ms` : '加载中…'}</dd>
          </div>
        </dl>
      </section>

      <section>
        <h2>后端连接状态</h2>
        <div className="status-line">
          <span className={`marker marker-${state.toLowerCase()}`} aria-hidden="true" />
          <span className="status-text">{status ? status.message : '正在读取状态…'}</span>
        </div>
        <dl className="facts">
          <div>
            <dt>最近检查时间</dt>
            <dd>{formatTime(status?.checkedAt ?? null)}</dd>
          </div>
          <div>
            <dt>请求耗时</dt>
            <dd>{health ? `${health.durationMs} ms` : '—'}</dd>
          </div>
          <div>
            <dt>HTTP 状态</dt>
            <dd>{health ? (health.statusCode ?? '无响应') : '—'}</dd>
          </div>
          <div>
            <dt>OpenAPI 版本</dt>
            <dd>{health?.openapi ?? '—'}</dd>
          </div>
          <div>
            <dt>请求地址</dt>
            <dd>{health?.url ?? '—'}</dd>
          </div>
        </dl>
        {health?.failure ? (
          <p className="failure">
            失败原因：{health.failure.kind} · {health.failure.message}
          </p>
        ) : null}
        <button type="button" onClick={() => void handleRefresh()} disabled={busy !== ''}>
          {busy === 'refresh' ? '检查中…' : '立即检查'}
        </button>
      </section>

      <section>
        <h2>后端进程</h2>
        <dl className="facts">
          <div>
            <dt>进程归属</dt>
            <dd>{status?.managedByApp ? `本应用启动（PID ${status.pid ?? '未知'}）` : '非本应用启动'}</dd>
          </div>
          <div>
            <dt>进程状态</dt>
            <dd>{STATE_LABEL[state]}</dd>
          </div>
        </dl>
        <div className="actions">
          <button type="button" onClick={() => void handleStart()} disabled={busy !== '' || state === 'STARTING'}>
            {busy === 'start' ? '启动中…' : '启动本机后端'}
          </button>
          <button type="button" onClick={() => void handleStop()} disabled={busy !== '' || !status?.managedByApp}>
            {busy === 'stop' ? '停止中…' : '停止后端'}
          </button>
          <button type="button" onClick={() => void handleCapture()} disabled={busy !== ''}>
            {busy === 'capture' ? '截图中…' : '保存窗口截图'}
          </button>
        </div>
        <p className="hint">
          手动启动指引：在 <code>musicbridge-backend</code> 目录执行 <code>.\mvnw.cmd spring-boot:run</code>，并设置
          <code>MB_SERVER_PORT</code>（配置默认 8080，本次验收使用 18080）、<code>MYSQL_USER</code>、
          <code>MYSQL_PASSWORD</code>。应用内启动读取本地未跟踪配置 <code>.local/backend.env.json</code>。
        </p>
      </section>

      <footer>
        {notice ? <p className="notice">{notice}</p> : null}
        {evidenceFile ? <p className="notice">{evidenceFile}</p> : null}
        <p>
          证据目录：<code>{evidenceDir || '加载中…'}</code>
        </p>
        <p>
          Electron {window.musicbridge.versions.electron} · Node {window.musicbridge.versions.node} · Chromium{' '}
          {window.musicbridge.versions.chrome}
        </p>
      </footer>
    </main>
  );
}
