const { contextBridge, ipcRenderer } = require("electron");

const ALLOWED_MENU_COMMANDS = new Set([
  "new-analysis",
  "import-data",
  "export-excel",
  "export-word",
  "export-pdf",
  "open-settings",
  "navigate:overview",
  "navigate:product",
  "navigate:customer",
  "navigate:profit",
  "navigate:salesGroup",
  "toggle-ai-assistant",
  "show-shortcuts",
]);
const OPEN_KINDS = new Set(["data", "document", "all"]);
const SAVE_TYPES = new Set(["xlsx", "docx", "csv", "json", "pdf"]);

function openFiles(options = {}) {
  return ipcRenderer.invoke("datamaster:files:open", {
    kind: OPEN_KINDS.has(options.kind) ? options.kind : "data",
    multiple: options.multiple !== false,
  });
}

function uploadSelectedFiles(tokens, options = {}) {
  if (!Array.isArray(tokens) || !tokens.length || tokens.length > 30 || tokens.some((token) => typeof token !== "string" || !/^[A-Za-z0-9_-]{20,80}$/.test(token))) {
    return Promise.reject(new TypeError("无效的文件选择令牌"));
  }
  const mapping = options.mapping && typeof options.mapping === "object"
    ? Object.fromEntries(Object.entries(options.mapping).filter(([key, value]) => typeof key === "string" && typeof value === "string"))
    : undefined;
  return ipcRenderer.invoke("datamaster:files:upload-selection", {
    tokens,
    append: options.append === true,
    mapping,
  });
}

function onUploadProgress(listener) {
  if (typeof listener !== "function") throw new TypeError("上传进度监听器必须是函数");
  const handler = (_event, progress) => {
    if (!progress || typeof progress !== "object") return;
    listener({
      completedFiles: Number(progress.completedFiles || 0),
      currentFile: String(progress.currentFile || ""),
      fileIndex: Number(progress.fileIndex || 0),
      totalFiles: Number(progress.totalFiles || 0),
      uploadedBytes: Number(progress.uploadedBytes || 0),
      totalBytes: Number(progress.totalBytes || 0),
    });
  };
  ipcRenderer.on("datamaster:files:upload-progress", handler);
  return () => ipcRenderer.removeListener("datamaster:files:upload-progress", handler);
}

function saveFile(options = {}) {
  return ipcRenderer.invoke("datamaster:files:save", {
    type: SAVE_TYPES.has(options.type) ? options.type : "xlsx",
    suggestedName: typeof options.suggestedName === "string" ? options.suggestedName : undefined,
  });
}

function saveExport(options = {}) {
  const value = options.bytes;
  const bytes = value instanceof ArrayBuffer
    ? new Uint8Array(value)
    : ArrayBuffer.isView(value)
      ? new Uint8Array(value.buffer, value.byteOffset, value.byteLength)
      : null;
  if (!bytes || bytes.byteLength === 0) return Promise.reject(new TypeError("导出内容不能为空"));
  return ipcRenderer.invoke("datamaster:files:save-export", {
    type: SAVE_TYPES.has(options.type) ? options.type : "xlsx",
    suggestedName: typeof options.suggestedName === "string" ? options.suggestedName : undefined,
    bytes,
  });
}

function onMenuCommand(listener) {
  if (typeof listener !== "function") throw new TypeError("菜单命令监听器必须是函数");
  const handler = (_event, command) => {
    if (ALLOWED_MENU_COMMANDS.has(command)) listener(command);
  };
  ipcRenderer.on("datamaster:menu-command", handler);
  return () => ipcRenderer.removeListener("datamaster:menu-command", handler);
}

const windowControls = Object.freeze({
  minimize: () => ipcRenderer.invoke("datamaster:window:control", "minimize"),
  toggleMaximize: () => ipcRenderer.invoke("datamaster:window:control", "toggle-maximize"),
  close: () => ipcRenderer.invoke("datamaster:window:control", "close"),
});

contextBridge.exposeInMainWorld("dataMasterDesktop", Object.freeze({
  apiVersion: 1,
  platform: process.platform,
  isDesktop: true,
  getAppInfo: () => ipcRenderer.invoke("datamaster:app:info"),
  openFiles,
  uploadSelectedFiles,
  onUploadProgress,
  saveFile,
  saveExport,
  onMenuCommand,
  windowControls,
}));
