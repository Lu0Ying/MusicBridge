import { useEffect, useState } from 'react';
import type {
  AppConfig,
  AuthPublicStatus,
  ClipRecord,
  ClipSelection,
  ConsentRecord,
  ProbeResult,
  RuntimeStatus
} from './types';

const STATE_LABEL: Record<RuntimeStatus['state'], string> = {
  STARTING: '启动中',
  UP: '已就绪',
  NOT_READY: '存活但未就绪',
  DOWN: '后端未启动',
  STOPPING: '停止中'
};

function formatTime(value: string | null): string {
  if (!value) {
    return '尚未检查';
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}

function formatRemaining(ms: number | null): string {
  if (ms === null) {
    return '—';
  }
  if (ms <= 0) {
    return '已过期';
  }
  const days = Math.floor(ms / 86400000);
  const hours = Math.floor((ms % 86400000) / 3600000);
  const minutes = Math.floor((ms % 3600000) / 60000);
  if (days > 0) {
    return `${days} 天 ${hours} 小时`;
  }
  if (hours > 0) {
    return `${hours} 小时 ${minutes} 分钟`;
  }
  return `${minutes} 分钟`;
}

function describeProbe(probe: ProbeResult | null): string {
  if (!probe) {
    return '—';
  }
  return probe.ok ? `HTTP ${probe.statusCode ?? '—'}（通过）` : `HTTP ${probe.statusCode ?? '无响应'}（未通过）`;
}

function formatBytes(bytes: number): string {
  if (bytes <= 0) {
    return '0 字节';
  }
  return `${bytes} 字节（${(bytes / 1048576).toFixed(2)} MB）`;
}

export default function App(): JSX.Element {
  const [config, setConfig] = useState<AppConfig | null>(null);
  const [status, setStatus] = useState<RuntimeStatus | null>(null);
  const [auth, setAuth] = useState<AuthPublicStatus | null>(null);
  const [input, setInput] = useState('');
  const [username, setUsername] = useState('local');
  const [password, setPassword] = useState('');
  const [notice, setNotice] = useState('');
  const [loginError, setLoginError] = useState('');
  const [verifyResult, setVerifyResult] = useState('');
  const [busy, setBusy] = useState('');
  const [evidenceDir, setEvidenceDir] = useState('');
  const [evidenceFile, setEvidenceFile] = useState('');
  const [clipSelection, setClipSelection] = useState<ClipSelection | null>(null);
  const [consent, setConsent] = useState<ConsentRecord | null>(null);
  const [retentionDays, setRetentionDays] = useState('1');
  const [clipBusy, setClipBusy] = useState('');
  const [clipError, setClipError] = useState('');
  const [clipResult, setClipResult] = useState<ClipRecord | null>(null);

  useEffect(() => {
    let alive = true;
    const unsubscribeStatus = window.musicbridge.onStatus((next) => {
      if (alive) {
        setStatus(next);
      }
    });
    const unsubscribeAuth = window.musicbridge.onAuthStatus((next) => {
      if (alive) {
        setAuth(next);
      }
    });

    Promise.all([
      window.musicbridge.getConfig(),
      window.musicbridge.getStatus(),
      window.musicbridge.getEvidenceDir(),
      window.musicbridge.getAuthStatus(),
      window.musicbridge.getClipSelection()
    ])
      .then(([loadedConfig, loadedStatus, dir, authStatus, clipInfo]) => {
        if (!alive) {
          return;
        }
        setConfig(loadedConfig);
        setInput(loadedConfig.apiBaseUrl);
        setStatus(loadedStatus);
        setEvidenceDir(dir);
        setAuth(authStatus);
        setClipSelection(clipInfo);
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
      unsubscribeStatus();
      unsubscribeAuth();
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

  async function handleLogin(): Promise<void> {
    setBusy('login');
    setLoginError('');
    setVerifyResult('');
    try {
      const outcome = await window.musicbridge.login(username, password);
      setAuth(outcome.status);
      if (outcome.error) {
        setLoginError(`${outcome.error.kind} · ${outcome.error.message}`);
      } else {
        setPassword('');
        setNotice(`登录成功：userId=${outcome.status.userId ?? '未知'}`);
      }
    } catch (error) {
      setLoginError(`登录请求异常：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setBusy('');
    }
  }

  async function handleLogout(): Promise<void> {
    setBusy('logout');
    setLoginError('');
    setVerifyResult('');
    setConsent(null);
    setClipResult(null);
    setClipError('');
    try {
      const outcome = await window.musicbridge.logout();
      setAuth(outcome.status);
      setNotice(
        outcome.error
          ? `注销完成（本地令牌已清除）：${outcome.error.message}`
          : '已注销，本地令牌已清除'
      );
    } catch (error) {
      setNotice(`注销失败：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setBusy('');
    }
  }

  async function handleVerify(): Promise<void> {
    setBusy('verify');
    setNotice('');
    try {
      const result = await window.musicbridge.requestApi('GET', '/api/v1/auth/me');
      setVerifyResult(
        result.ok
          ? `通过 · HTTP ${result.statusCode} · ${result.durationMs} ms · ${JSON.stringify(result.data)}`
          : `失败 · HTTP ${result.statusCode ?? '无响应'} · ${result.error?.kind ?? ''} ${
              result.error?.message ?? ''
            }`
      );
      setAuth(await window.musicbridge.getAuthStatus());
    } catch (error) {
      setVerifyResult(`请求异常：${error instanceof Error ? error.message : String(error)}`);
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

  async function handleSelectClip(): Promise<void> {
    setClipBusy('select');
    setClipError('');
    setClipResult(null);
    try {
      const selection = await window.musicbridge.selectClipFile();
      if (selection) {
        setClipSelection(selection);
      }
    } catch (error) {
      setClipError(`选择文件失败：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setClipBusy('');
    }
  }

  async function handleCreateConsent(): Promise<void> {
    const days = Number(retentionDays);
    if (!Number.isInteger(days) || days < 1 || days > 365) {
      setClipError('保留天数必须是 1–365 的整数');
      return;
    }
    setClipBusy('consent');
    setClipError('');
    setClipResult(null);
    try {
      const outcome = await window.musicbridge.createConsent(days);
      setConsent(outcome.consent);
      if (outcome.error) {
        setClipError(`创建授权失败：${outcome.error.kind} · ${outcome.error.message}`);
      }
      if (outcome.unauthorized) {
        setAuth(await window.musicbridge.getAuthStatus());
      }
    } catch (error) {
      setClipError(`创建授权异常：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setClipBusy('');
    }
  }

  async function handleUploadClip(): Promise<void> {
    if (!consent) {
      return;
    }
    setClipBusy('upload');
    setClipError('');
    setClipResult(null);
    try {
      const outcome = await window.musicbridge.uploadClip(consent.id);
      setClipResult(outcome.clip);
      if (outcome.error) {
        setClipError(
          outcome.endpointMissing
            ? `未上传：${outcome.error.message}`
            : `上传失败：${outcome.error.kind} · ${outcome.error.message}`
        );
      }
      if (outcome.unauthorized) {
        setConsent(null);
        setAuth(await window.musicbridge.getAuthStatus());
      }
    } catch (error) {
      setClipError(`上传异常：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setClipBusy('');
    }
  }

  const health = status?.health ?? null;
  const ready = status?.ready ?? null;
  const state = status?.state ?? 'DOWN';
  const authenticated = auth?.authenticated ?? false;

  return (
    <main className="app">
      <header className="masthead">
        <div>
          <h1>MusicBridge</h1>
          <p className="subtitle">Phase 1.2 认证与就绪检查 · Electron + React + TypeScript</p>
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
            <dt>存活探测路径</dt>
            <dd>{config?.healthPath ?? '加载中…'}</dd>
          </div>
          <div>
            <dt>就绪探测路径</dt>
            <dd>{config?.readyPath ?? '加载中…'}</dd>
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
        </dl>

        <div className="probe">
          <h3>
            <code>{config?.healthPath ?? '/health'}</code> 进程存活
          </h3>
          <dl className="facts">
            <div>
              <dt>结果</dt>
              <dd>{describeProbe(health)}</dd>
            </div>
            <div>
              <dt>耗时</dt>
              <dd>{health ? `${health.durationMs} ms` : '—'}</dd>
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
        </div>

        <div className="probe">
          <h3>
            <code>{config?.readyPath ?? '/ready'}</code> 业务就绪（MySQL）
          </h3>
          {health && !health.ok ? (
            <p className="skipped">未执行：存活检查未通过</p>
          ) : (
            <>
              <dl className="facts">
                <div>
                  <dt>结果</dt>
                  <dd>{describeProbe(ready)}</dd>
                </div>
                <div>
                  <dt>耗时</dt>
                  <dd>{ready ? `${ready.durationMs} ms` : '—'}</dd>
                </div>
                <div>
                  <dt>公开状态字段</dt>
                  <dd>{ready?.detail ?? '—'}</dd>
                </div>
              </dl>
              {ready?.failure ? (
                <p className="failure">
                  失败原因：{ready.failure.kind} · {ready.failure.message}
                  {ready.detail ? ` · ${ready.detail}` : ''}
                </p>
              ) : null}
            </>
          )}
        </div>

        <button type="button" onClick={() => void handleRefresh()} disabled={busy !== ''}>
          {busy === 'refresh' ? '检查中…' : '立即检查'}
        </button>
      </section>

      <section>
        <h2>本机会话</h2>
        {!authenticated ? (
          <>
            <div className="field">
              <label htmlFor="username">用户名</label>
              <input
                id="username"
                value={username}
                spellCheck={false}
                autoComplete="off"
                onChange={(event) => setUsername(event.target.value)}
                disabled={busy === 'login'}
              />
            </div>
            <div className="field">
              <label htmlFor="password">密码</label>
              <input
                id="password"
                type="password"
                value={password}
                spellCheck={false}
                autoComplete="off"
                onChange={(event) => setPassword(event.target.value)}
                disabled={busy === 'login'}
              />
            </div>
            <button
              type="button"
              onClick={() => void handleLogin()}
              disabled={busy !== '' || username.trim() === '' || password === ''}
            >
              {busy === 'login' ? '登录中…' : '登录'}
            </button>
            {loginError ? <p className="failure">登录失败：{loginError}</p> : null}
            <p className="hint">
              登录请求由主进程调用 <code>POST /api/v1/auth/login</code>，令牌只保存在主进程并通过
              <code>safeStorage</code> 加密持久化；密码不会保存。若后端未配置
              <code>MUSICBRIDGE_LOCAL_PASSWORD</code>，登录会返回「用户名或密码不正确」，此时需在启动后端前设置
              <code>MUSICBRIDGE_LOCAL_USERNAME</code> 与 <code>MUSICBRIDGE_LOCAL_PASSWORD</code>。
            </p>
          </>
        ) : (
          <>
            <dl className="facts">
              <div>
                <dt>当前用户</dt>
                <dd>{auth?.userId ?? '—'}</dd>
              </div>
              <div>
                <dt>到期时间</dt>
                <dd>{auth?.expiresAt ? formatTime(auth.expiresAt) : '—'}</dd>
              </div>
              <div>
                <dt>剩余有效</dt>
                <dd>{formatRemaining(auth?.expiresInMs ?? null)}</dd>
              </div>
              <div>
                <dt>令牌持久化</dt>
                <dd>
                  {auth?.authenticated
                    ? auth.persistence === 'encrypted'
                      ? 'safeStorage 加密已持久化（仅主进程可读）'
                      : '内存会话模式（未写入磁盘）'
                    : '未登录'}
                </dd>
              </div>
            </dl>
            {auth?.authenticated && auth.persistence === 'memory' ? (
              <p className="warning">
                {auth.encryptionAvailable
                  ? '内存会话模式：加密会话写入磁盘失败，重启应用后需要重新登录。'
                  : '内存会话模式：当前系统 safeStorage 不可用，令牌仅保存在内存，重启应用后需要重新登录。'}
              </p>
            ) : null}
            {auth?.expiringSoon ? (
              <p className="warning">会话将在 24 小时内到期，请重新登录以继续使用。</p>
            ) : null}
            <p className="hint">会话状态：{auth?.message ?? '—'}</p>
            <div className="actions">
              <button type="button" onClick={() => void handleVerify()} disabled={busy !== ''}>
                {busy === 'verify' ? '验证中…' : '验证会话（GET /api/v1/auth/me）'}
              </button>
              <button type="button" onClick={() => void handleLogout()} disabled={busy !== ''}>
                {busy === 'logout' ? '注销中…' : '注销'}
              </button>
            </div>
            {verifyResult ? <p className="notice">{verifyResult}</p> : null}
            <p className="hint">令牌不进入渲染进程、localStorage、日志或前端配置，业务请求由主进程附加 Bearer 头转发。</p>
          </>
        )}
      </section>

      <section>
        <h2>文件导入（上传前置）</h2>
        <p className="hint">
          仅支持 MP3 / M4A / WAV / FLAC / OGG，单文件实际字节 ≤ 25 MB、时长 ≤ 5 分钟。客户端预检只做提示，
          不代表服务端已接受，也不代表「已可识别」；是否可用完全由服务端判定。
        </p>
        {!authenticated ? <p className="skipped">未登录：请先在上方「本机会话」登录后再创建授权与上传。</p> : null}

        <div className="actions">
          <button type="button" onClick={() => void handleSelectClip()} disabled={busy !== '' || clipBusy !== ''}>
            {clipBusy === 'select' ? '选择中…' : '选择音频文件'}
          </button>
        </div>

        {clipSelection ? (
          <>
            <dl className="facts">
              <div>
                <dt>文件名</dt>
                <dd>{clipSelection.fileName}</dd>
              </div>
              <div>
                <dt>格式 / 扩展名</dt>
                <dd>
                  {clipSelection.format ?? '不支持'} · {clipSelection.extension || '（无）'}
                </dd>
              </div>
              <div>
                <dt>文件大小</dt>
                <dd>{formatBytes(clipSelection.sizeBytes)}（上限 25 MB）</dd>
              </div>
              <div>
                <dt>容器时长（客户端）</dt>
                <dd>
                  {clipSelection.durationMs !== null
                    ? `${clipSelection.durationMs} ms`
                    : '未能读取，将不发送 durationMs'}
                </dd>
              </div>
              <div>
                <dt>客户端预检</dt>
                <dd>{clipSelection.accepted ? '通过（仅提示，最终以服务端判定为准）' : '已拦截，不可上传'}</dd>
              </div>
            </dl>
            {clipSelection.reasons.map((reason) => (
              <p className="failure" key={reason}>
                {reason}
              </p>
            ))}
            {clipSelection.notes.map((note) => (
              <p className="warning" key={note}>
                {note}
              </p>
            ))}
          </>
        ) : (
          <p className="skipped">尚未选择文件</p>
        )}

        <div className="field">
          <label htmlFor="retention-days">保留天数</label>
          <input
            id="retention-days"
            type="number"
            min={1}
            max={365}
            value={retentionDays}
            onChange={(event) => setRetentionDays(event.target.value)}
            disabled={!authenticated || clipBusy !== ''}
          />
          <button
            type="button"
            onClick={() => void handleCreateConsent()}
            disabled={!authenticated || busy !== '' || clipBusy !== ''}
          >
            {clipBusy === 'consent' ? '创建中…' : '创建上传授权（RAW_AUDIO）'}
          </button>
        </div>

        {consent ? (
          <dl className="facts">
            <div>
              <dt>授权 ID</dt>
              <dd>{consent.id}</dd>
            </div>
            <div>
              <dt>scope</dt>
              <dd>{consent.scope}</dd>
            </div>
            <div>
              <dt>授权有效期</dt>
              <dd>{consent.expiresAt ? formatTime(consent.expiresAt) : '未设置到期时间（撤销前有效）'}</dd>
            </div>
            <div>
              <dt>保留天数</dt>
              <dd>{consent.retentionDays} 天</dd>
            </div>
            <div>
              <dt>状态</dt>
              <dd>{consent.active ? '有效' : '不可用'}</dd>
            </div>
          </dl>
        ) : (
          <p className="skipped">尚未创建有效授权，不满足上传前置条件。</p>
        )}

        <div className="actions">
          <button
            type="button"
            onClick={() => void handleUploadClip()}
            disabled={
              !authenticated ||
              busy !== '' ||
              clipBusy !== '' ||
              !consent?.active ||
              !clipSelection?.accepted
            }
          >
            {clipBusy === 'upload' ? '上传中…' : '上传（POST /api/v1/clips）'}
          </button>
        </div>

        {clipResult ? (
          <dl className="facts">
            <div>
              <dt>clipId</dt>
              <dd>{clipResult.clipId}</dd>
            </div>
            <div>
              <dt>格式 / 类型</dt>
              <dd>
                {clipResult.format} · {clipResult.contentType}
              </dd>
            </div>
            <div>
              <dt>大小</dt>
              <dd>{formatBytes(clipResult.sizeBytes)}</dd>
            </div>
            <div>
              <dt>服务端实测时长</dt>
              <dd>{clipResult.durationMs} ms</dd>
            </div>
            <div>
              <dt>到期时间</dt>
              <dd>{clipResult.expiresAt ? formatTime(clipResult.expiresAt) : '—'}</dd>
            </div>
            <div>
              <dt>状态</dt>
              <dd>{clipResult.status}</dd>
            </div>
          </dl>
        ) : null}

        {clipError ? <p className="failure">{clipError}</p> : null}
        <p className="hint">
          文件路径与令牌只保留在主进程；上传由主进程以 multipart/form-data 转发并附加 Bearer 头，
          `durationMs` 为可选的容器时长提示，读取失败时不传。若后端尚未提供该接口，界面会如实提示「未上传」。
        </p>
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
          手动启动指引：在 <code>musicbridge-backend</code> 目录设置 <code>MB_SERVER_PORT</code>、
          <code>MYSQL_USER</code>、<code>MYSQL_PASSWORD</code>、<code>MUSICBRIDGE_LOCAL_USERNAME</code>、
          <code>MUSICBRIDGE_LOCAL_PASSWORD</code> 后执行 <code>.\mvnw.cmd spring-boot:run</code>。应用内启动读取本地未跟踪配置{' '}
          <code>.local/backend.env.json</code>。
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
