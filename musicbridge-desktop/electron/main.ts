import { app, BrowserWindow, ipcMain, safeStorage } from 'electron';
import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { ConfigStore } from './config';
import { BackendSupervisor, type RuntimeStatus } from './backend';
import { AuthManager, type AuthPublicStatus } from './auth';

const appRoot = resolve(__dirname, '..');
const projectRoot = resolve(appRoot, '..');
const evidenceDir = join(projectRoot, 'evidence', 'phase1.2');
mkdirSync(evidenceDir, { recursive: true });

const mainLogFile = join(evidenceDir, `desktop-main-${stamp()}.log`);

function stamp(): string {
  return new Date().toISOString().replace(/[:.]/g, '-');
}

function log(message: string): void {
  const line = `[${new Date().toISOString()}] ${message}\n`;
  appendFileSync(mainLogFile, line);
  console.log(line.trim());
}

let mainWindow: BrowserWindow | null = null;
let supervisor: BackendSupervisor | null = null;
let authManager: AuthManager | null = null;
let expiryTimer: NodeJS.Timeout | null = null;
let firstUpCaptured = false;

function broadcast(status: RuntimeStatus): void {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('backend:status', status);
  }
  if (status.state === 'UP' && !firstUpCaptured) {
    firstUpCaptured = true;
    setTimeout(() => void capture('window-ready'), 1000);
  }
}

/** 认证状态广播只携带公开字段，token 始终留在主进程 */
function broadcastAuth(status: AuthPublicStatus): void {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('auth:status', status);
  }
}

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1180,
    height: 780,
    minWidth: 900,
    minHeight: 620,
    title: 'MusicBridge',
    backgroundColor: '#ffffff',
    webPreferences: {
      preload: join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  mainWindow.removeMenu();

  mainWindow.webContents.on('did-fail-load', (_event, code, description, url) => {
    log(`页面加载失败 code=${code} description=${description} url=${url}`);
  });
  mainWindow.webContents.on('did-finish-load', () => {
    log('渲染页面加载完成');
    setTimeout(() => void capture('window-startup'), 1200);
  });

  const devUrl = process.env.ELECTRON_RENDERER_URL;
  if (devUrl) {
    log(`加载开发服务器 ${devUrl}`);
    void mainWindow.loadURL(devUrl);
  } else {
    log('加载本地构建产物 dist/index.html');
    void mainWindow.loadFile(join(appRoot, 'dist', 'index.html'));
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

async function capture(tag: string): Promise<string | null> {
  if (!mainWindow || mainWindow.isDestroyed()) {
    return null;
  }
  try {
    const image = await mainWindow.webContents.capturePage();
    const file = join(evidenceDir, `${tag}-${stamp()}.png`);
    writeFileSync(file, image.toPNG());
    log(`窗口截图已保存 ${file}`);
    return file;
  } catch (error) {
    log(`窗口截图失败 ${error instanceof Error ? error.message : String(error)}`);
    return null;
  }
}

function registerIpc(
  store: ConfigStore,
  supervisorRef: BackendSupervisor,
  authRef: AuthManager
): void {
  ipcMain.handle('config:get', () => store.get());

  ipcMain.handle('config:update-api-base-url', async (_event, raw: unknown) => {
    if (typeof raw !== 'string') {
      throw new Error('API Base URL 必须是字符串');
    }
    const config = store.setApiBaseUrl(raw);
    log(`API Base URL 已更新为 ${config.apiBaseUrl}`);
    await supervisorRef.refreshNow();
    return config;
  });

  ipcMain.handle('status:get', () => supervisorRef.getStatus());
  ipcMain.handle('status:refresh', () => supervisorRef.refreshNow());
  ipcMain.handle('backend:start', () => supervisorRef.startBackend());
  ipcMain.handle('backend:stop', () => supervisorRef.stopBackend());
  ipcMain.handle('evidence:capture', () => capture('window-manual'));
  ipcMain.handle('evidence:get-dir', () => evidenceDir);

  // 认证 IPC 只返回公开状态与错误，Renderer 永远拿不到令牌
  ipcMain.handle('auth:login', (_event, username: unknown, password: unknown) => {
    if (typeof username !== 'string' || typeof password !== 'string') {
      throw new Error('用户名和密码必须是字符串');
    }
    return authRef.login(username.trim(), password);
  });
  ipcMain.handle('auth:logout', () => authRef.logout());
  ipcMain.handle('auth:status', () => authRef.getStatus());

  // 业务请求按方法 + 路径精确白名单转发，令牌由主进程附加
  ipcMain.handle('api:request', (_event, method: unknown, path: unknown) => {
    if (typeof method !== 'string' || typeof path !== 'string') {
      throw new Error('method 与 path 必须是字符串');
    }
    return authRef.request(method.toUpperCase(), path);
  });
}

app.whenReady().then(() => {
  const store = new ConfigStore(appRoot, app.getPath('userData'));
  log(`Electron ${process.versions.electron} / Node ${process.versions.node} / Chrome ${process.versions.chrome}`);
  log(`本地覆盖配置文件 ${store.getOverrideFile()}`);
  log(`safeStorage 加密可用：${safeStorage.isEncryptionAvailable()}`);

  authManager = new AuthManager(store, app.getPath('userData'), broadcastAuth, log);
  authManager.restore();

  supervisor = new BackendSupervisor(store, appRoot, evidenceDir, broadcast, log);
  registerIpc(store, supervisor, authManager);
  createWindow();
  supervisor.start();

  // 每分钟检查一次到期，过期或进入 24 小时提醒由公开状态展示
  expiryTimer = setInterval(() => authManager?.checkExpiry(), 60 * 1000);
});

app.on('window-all-closed', () => {
  if (expiryTimer) {
    clearInterval(expiryTimer);
    expiryTimer = null;
  }
  supervisor?.shutdown();
  app.quit();
});

app.on('before-quit', () => {
  if (expiryTimer) {
    clearInterval(expiryTimer);
    expiryTimer = null;
  }
  supervisor?.shutdown();
});
