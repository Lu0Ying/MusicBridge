import { contextBridge, ipcRenderer } from 'electron';

const api = {
  getConfig: () => ipcRenderer.invoke('config:get'),
  updateApiBaseUrl: (value: string) => ipcRenderer.invoke('config:update-api-base-url', value),
  getStatus: () => ipcRenderer.invoke('status:get'),
  refreshStatus: () => ipcRenderer.invoke('status:refresh'),
  startBackend: () => ipcRenderer.invoke('backend:start'),
  stopBackend: () => ipcRenderer.invoke('backend:stop'),
  captureScreenshot: () => ipcRenderer.invoke('evidence:capture'),
  getEvidenceDir: () => ipcRenderer.invoke('evidence:get-dir'),
  onStatus: (callback: (status: unknown) => void) => {
    const listener = (_event: Electron.IpcRendererEvent, status: unknown) => callback(status);
    ipcRenderer.on('backend:status', listener);
    return () => ipcRenderer.removeListener('backend:status', listener);
  },
  versions: {
    electron: process.versions.electron,
    node: process.versions.node,
    chrome: process.versions.chrome
  }
};

contextBridge.exposeInMainWorld('musicbridge', api);
