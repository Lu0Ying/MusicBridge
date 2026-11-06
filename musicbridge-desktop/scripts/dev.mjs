import { spawn, spawnSync } from 'node:child_process';
import { createWriteStream, existsSync, mkdirSync } from 'node:fs';
import net from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const appRoot = join(here, '..');
const projectRoot = join(appRoot, '..');
const evidenceDir = join(projectRoot, 'evidence', 'phase1.1');
mkdirSync(evidenceDir, { recursive: true });

const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const logFile = join(evidenceDir, `electron-dev-${stamp}.log`);
const logStream = createWriteStream(logFile, { flags: 'a' });

let logClosed = false;
function write(source, chunk) {
  if (logClosed) {
    return;
  }
  const text = `[${new Date().toISOString()}][${source}] ${chunk}`;
  process.stdout.write(text);
  logStream.write(text);
}

function closeLog() {
  if (!logClosed) {
    logClosed = true;
    logStream.end();
  }
}

write('dev', `启动桌面端开发环境，日志文件：${logFile}\n`);

// 1. 编译 Electron 主进程与 preload
write('dev', '编译主进程与 preload（tsc）...\n');
const tsc = spawnSync('npm run build:electron', { cwd: appRoot, shell: true, encoding: 'utf-8' });
write('tsc', `${tsc.stdout ?? ''}${tsc.stderr ?? ''}`);
if (tsc.status !== 0) {
  write('dev', `主进程编译失败，退出码 ${tsc.status}\n`);
  closeLog();
  process.exit(tsc.status ?? 1);
}

// 2. 启动 Vite 开发服务器
write('dev', '启动 Vite 开发服务器（127.0.0.1:5173）...\n');
const vite = spawn('npm run dev:renderer', { cwd: appRoot, shell: true, env: process.env });
vite.stdout?.on('data', (chunk) => write('vite', chunk.toString()));
vite.stderr?.on('data', (chunk) => write('vite', chunk.toString()));
vite.on('exit', (code) => write('vite', `Vite 进程退出 code=${code ?? 'null'}\n`));

const electronBin = join(appRoot, 'node_modules', '.bin', process.platform === 'win32' ? 'electron.cmd' : 'electron');
if (!existsSync(electronBin)) {
  write('dev', `未找到 Electron 可执行文件：${electronBin}\n`);
  killTree(vite);
  closeLog();
  process.exit(1);
}

let electron = null;
let shuttingDown = false;

function killTree(child) {
  if (!child || !child.pid) {
    return;
  }
  spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], {
    shell: true,
    windowsHide: true
  });
}

function shutdown(exitCode) {
  if (shuttingDown) {
    return;
  }
  shuttingDown = true;
  write('dev', '关闭开发环境，清理子进程...\n');
  killTree(electron);
  killTree(vite);
  setTimeout(() => {
    closeLog();
    process.exit(exitCode);
  }, 300);
}

process.on('SIGINT', () => shutdown(0));
process.on('SIGTERM', () => shutdown(0));

async function waitForPort(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const reachable = await new Promise((resolve) => {
      const socket = net.connect({ host: '127.0.0.1', port });
      socket.once('connect', () => {
        socket.destroy();
        resolve(true);
      });
      socket.once('error', () => {
        socket.destroy();
        resolve(false);
      });
    });
    if (reachable) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`等待 127.0.0.1:${port} 超时（${timeoutMs}ms）`);
}

Promise.resolve()
  .then(async () => {
    await waitForPort(5173, 30000);
    write('dev', 'Vite 已就绪，启动 Electron 窗口...\n');

    // 本机渲染沙箱在 Chromium 启动时触发访问冲突（0xC0000005），关闭 GPU 沙箱后可正常启动；
    // 渲染进程沙箱与 contextIsolation 仍保持开启。
    electron = spawn(electronBin, ['.', '--disable-gpu-sandbox'], {
      cwd: appRoot,
      shell: true,
      env: { ...process.env, ELECTRON_RENDERER_URL: 'http://127.0.0.1:5173' }
    });
    electron.stdout?.on('data', (chunk) => write('electron', chunk.toString()));
    electron.stderr?.on('data', (chunk) => write('electron', chunk.toString()));
    electron.on('exit', (code) => {
      write('dev', `Electron 进程退出 code=${code ?? 'null'}\n`);
      shutdown(code ?? 0);
    });
  })
  .catch((error) => {
    write('dev', `启动失败：${error instanceof Error ? error.message : String(error)}\n`);
    shutdown(1);
  });
