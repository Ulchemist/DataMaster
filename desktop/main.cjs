const { app, BrowserWindow, Menu, dialog, ipcMain, screen, shell } = require("electron");
const { spawn } = require("node:child_process");
const http = require("node:http");
const net = require("node:net");
const path = require("node:path");
const fs = require("node:fs");
const crypto = require("node:crypto");

app.setName("DataMaster");
if (!app.isPackaged) {
  app.setPath("userData", path.join(app.getPath("appData"), "DataMaster"));
}

const DEVELOPMENT_LOG_FILE = path.join(app.getPath("userData"), "desktop-runtime.log");

function writeDevelopmentLog(message) {
  if (app.isPackaged) return;
  try {
    fs.mkdirSync(path.dirname(DEVELOPMENT_LOG_FILE), { recursive: true });
    fs.appendFileSync(DEVELOPMENT_LOG_FILE, `${new Date().toISOString()} ${message}\n`);
  } catch {
    // Diagnostics must never block application startup.
  }
}

const ONLINE_APP_URL = "https://datamaster-analysis.odozidahe433.chatgpt.site";
const WINDOW_STATE_FILE = "window-state.json";
const DEFAULT_WINDOW_BOUNDS = Object.freeze({ width: 1520, height: 960 });
const MIN_WINDOW_BOUNDS = Object.freeze({ width: 1080, height: 720 });
const MENU_COMMANDS = new Set([
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
const OPEN_FILE_FILTERS = Object.freeze({
  data: [
    { name: "经营数据", extensions: ["xlsx", "xls", "csv"] },
  ],
  document: [
    { name: "Office 文档", extensions: ["xlsx", "xls", "csv", "docx", "pdf"] },
  ],
  all: [
    { name: "DataMaster 支持的文件", extensions: ["xlsx", "xls", "csv", "docx", "pdf"] },
  ],
});
const SAVE_FILE_TYPES = Object.freeze({
  xlsx: { label: "Excel 工作簿", extension: "xlsx" },
  docx: { label: "Word 文档", extension: "docx" },
  csv: { label: "CSV 文件", extension: "csv" },
  json: { label: "DataMaster 项目", extension: "json" },
  pdf: { label: "PDF 文档", extension: "pdf" },
});

let mainWindow = null;
let backendProcess = null;
let backendOrigin = null;
let backendDesktopToken = null;
let windowStateTimer = null;
let quitting = false;
const authorizedFileSelections = new Map();
const FILE_SELECTION_TTL_MS = 15 * 60 * 1000;
const MAX_SELECTED_FILE_BYTES = 500 * 1024 * 1024;
const DATA_FILE_EXTENSIONS = new Set([".xlsx", ".xls", ".csv"]);

function selectedFileMimeType(filePath) {
  const extension = path.extname(filePath).toLowerCase();
  if (extension === ".csv") return "text/csv";
  if (extension === ".xls") return "application/vnd.ms-excel";
  return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
}

function pruneAuthorizedFileSelections(now = Date.now()) {
  for (const [token, selection] of authorizedFileSelections) {
    if (selection.expiresAt <= now) authorizedFileSelections.delete(token);
  }
}

function multipartFileHeader(boundary, selection) {
  const quotedName = selection.name.replace(/[\r\n"]/g, "-");
  const encodedName = encodeURIComponent(selection.name);
  return Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="files"; filename="${quotedName}"; filename*=UTF-8''${encodedName}\r\nContent-Type: ${selection.type}\r\n\r\n`, "utf8");
}

function multipartMappingPart(boundary, mapping) {
  if (!mapping || !Object.values(mapping).some((value) => typeof value === "string" && value.trim())) return null;
  return Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="mapping"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n${JSON.stringify(mapping)}\r\n`, "utf8");
}

function writeRequestChunk(request, chunk) {
  if (request.write(chunk)) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      request.off("drain", onDrain);
      request.off("error", onError);
    };
    const onDrain = () => { cleanup(); resolve(); };
    const onError = (error) => { cleanup(); reject(error); };
    request.once("drain", onDrain);
    request.once("error", onError);
  });
}

async function streamSelectedFilesToBackend(event, selections, mapping, append) {
  const boundary = `----DataMaster-${crypto.randomBytes(18).toString("hex")}`;
  const fileHeaders = selections.map((selection) => multipartFileHeader(boundary, selection));
  const mappingPart = multipartMappingPart(boundary, mapping);
  const closing = Buffer.from(`--${boundary}--\r\n`, "utf8");
  const separator = Buffer.from("\r\n", "utf8");
  const totalFileBytes = selections.reduce((sum, selection) => sum + selection.size, 0);
  const contentLength = fileHeaders.reduce((sum, header, index) => sum + header.length + selections[index].size + separator.length, 0)
    + (mappingPart?.length || 0) + closing.length;
  const endpoint = append ? "/api/workspace/sources" : "/api/analysis/upload";
  const target = new URL(endpoint, backendOrigin);
  let uploadedBytes = 0;
  let lastProgressAt = 0;

  return new Promise((resolve, reject) => {
    const request = http.request(target, {
      method: "POST",
      headers: {
        "Content-Type": `multipart/form-data; boundary=${boundary}`,
        "Content-Length": String(contentLength),
        "X-DataMaster-Token": backendDesktopToken,
      },
    });
    const timer = setTimeout(() => request.destroy(new Error("本地大文件分析超过 15 分钟，请检查文件或重试")), 15 * 60 * 1000);
    request.once("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    request.once("response", (response) => {
      const chunks = [];
      let responseBytes = 0;
      response.on("data", (chunk) => {
        responseBytes += chunk.length;
        if (responseBytes <= 16 * 1024 * 1024) chunks.push(chunk);
      });
      response.once("end", () => {
        clearTimeout(timer);
        if (responseBytes > 16 * 1024 * 1024) {
          reject(new Error("本地分析服务返回内容异常过大"));
          return;
        }
        const text = Buffer.concat(chunks).toString("utf8");
        if ((response.statusCode || 500) < 200 || (response.statusCode || 500) >= 300) {
          let message = text;
          try {
            const body = JSON.parse(text);
            message = body.message || body.error || text;
          } catch {
            // Plain-text server errors are already useful to the user.
          }
          reject(new Error(message || `本地分析服务返回 ${response.statusCode}`));
          return;
        }
        try {
          resolve(text ? JSON.parse(text) : {});
        } catch {
          reject(new Error("本地分析服务返回了无法解析的结果"));
        }
      });
    });

    void (async () => {
      try {
        for (let index = 0; index < selections.length; index += 1) {
          const selection = selections[index];
          await writeRequestChunk(request, fileHeaders[index]);
          for await (const chunk of fs.createReadStream(selection.path, { highWaterMark: 1024 * 1024 })) {
            await writeRequestChunk(request, chunk);
            uploadedBytes += chunk.length;
            const now = Date.now();
            if (now - lastProgressAt >= 120 || uploadedBytes === totalFileBytes) {
              lastProgressAt = now;
              if (!event.sender.isDestroyed()) event.sender.send("datamaster:files:upload-progress", {
                completedFiles: index,
                currentFile: selection.name,
                fileIndex: index + 1,
                totalFiles: selections.length,
                uploadedBytes,
                totalBytes: totalFileBytes,
              });
            }
          }
          await writeRequestChunk(request, separator);
        }
        if (mappingPart) await writeRequestChunk(request, mappingPart);
        request.end(closing);
      } catch (error) {
        request.destroy(error);
      }
    })();
  });
}

async function authorizeSelectedFile(filePath) {
  const resolvedPath = await fs.promises.realpath(filePath);
  const extension = path.extname(resolvedPath).toLowerCase();
  if (!DATA_FILE_EXTENSIONS.has(extension)) throw new Error(`${path.basename(filePath)} 不是支持的经营数据格式`);
  const stat = await fs.promises.stat(resolvedPath);
  if (!stat.isFile()) throw new Error(`${path.basename(filePath)} 不是普通文件`);
  if (stat.size === 0) throw new Error(`${path.basename(filePath)} 是空文件`);
  if (stat.size > MAX_SELECTED_FILE_BYTES) throw new Error(`${path.basename(filePath)} 超过 500 MB 上限`);
  const token = crypto.randomBytes(24).toString("base64url");
  const selection = {
    path: resolvedPath,
    name: path.basename(resolvedPath),
    size: stat.size,
    lastModified: stat.mtimeMs,
    type: selectedFileMimeType(resolvedPath),
    expiresAt: Date.now() + FILE_SELECTION_TTL_MS,
  };
  authorizedFileSelections.set(token, selection);
  return { token, name: selection.name, size: selection.size, lastModified: selection.lastModified, type: selection.type };
}

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.on("error", reject);
    server.listen({ host: "127.0.0.1", port: 0 }, () => {
      const address = server.address();
      server.close(() => resolve(address.port));
    });
  });
}

function runtimePaths() {
  if (app.isPackaged) {
    const java = path.join(process.resourcesPath, "runtime", "bin", process.platform === "win32" ? "java.exe" : "java");
    const jar = path.join(process.resourcesPath, "backend", "datamaster.jar");
    return { java, jar };
  }

  const bundledDevelopmentJava = path.resolve(__dirname, "runtime", "bin", process.platform === "win32" ? "java.exe" : "java");
  const javaHome = process.env.JAVA_HOME;
  const java = fs.existsSync(bundledDevelopmentJava)
    ? bundledDevelopmentJava
    : javaHome
    ? path.join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java")
    : (process.platform === "win32" ? "java.exe" : "java");
  return { java, jar: path.resolve(__dirname, "../backend/target/datamaster-demo-0.1.0.jar") };
}

function waitForHealth(port, timeoutMs = 45000) {
  const started = Date.now();
  return new Promise((resolve, reject) => {
    const check = () => {
      if (Date.now() - started > timeoutMs) {
        reject(new Error("本地分析服务启动超时"));
        return;
      }
      const request = http.get({ hostname: "127.0.0.1", port, path: "/api/health", timeout: 1200 }, (response) => {
        response.resume();
        if (response.statusCode === 200) resolve();
        else setTimeout(check, 350);
      });
      request.on("timeout", () => request.destroy());
      request.on("error", () => setTimeout(check, 350));
    };
    check();
  });
}

async function startBackend() {
  const port = await freePort();
  const desktopToken = crypto.randomBytes(32).toString("base64url");
  const { java, jar } = runtimePaths();
  writeDevelopmentLog(`starting backend java=${java} jar=${jar} port=${port}`);
  if (app.isPackaged && (!fs.existsSync(java) || !fs.existsSync(jar))) {
    throw new Error("应用资源不完整，请重新安装 DataMaster");
  }

  backendProcess = spawn(java, [
    "-Djava.awt.headless=true",
    "-jar",
    jar,
    `--server.port=${port}`,
    "--server.address=127.0.0.1",
    `--datamaster.desktop-token=${desktopToken}`,
  ], {
    windowsHide: true,
    stdio: app.isPackaged ? "ignore" : "inherit",
    env: { ...process.env, DATAMASTER_DESKTOP: "1" },
  });

  backendProcess.once("exit", (code) => {
    writeDevelopmentLog(`backend exited code=${code ?? "unknown"}`);
    backendProcess = null;
    if (!quitting && mainWindow) {
      dialog.showErrorBox("DataMaster 服务已停止", `本地分析服务意外退出（代码 ${code ?? "未知"}）。请重新启动应用。`);
      app.quit();
    }
  });
  backendProcess.once("error", (error) => {
    writeDevelopmentLog(`backend spawn error=${error.message}`);
    if (!quitting) dialog.showErrorBox("无法启动 DataMaster", error.message);
  });

  await waitForHealth(port);
  writeDevelopmentLog(`backend ready port=${port}`);
  return { url: `http://127.0.0.1:${port}`, desktopToken };
}

function windowStatePath() {
  return path.join(app.getPath("userData"), WINDOW_STATE_FILE);
}

function isFiniteBounds(bounds) {
  return bounds
    && Number.isFinite(bounds.x)
    && Number.isFinite(bounds.y)
    && Number.isFinite(bounds.width)
    && Number.isFinite(bounds.height)
    && bounds.width >= MIN_WINDOW_BOUNDS.width
    && bounds.height >= MIN_WINDOW_BOUNDS.height;
}

function isVisibleOnAnyDisplay(bounds) {
  return screen.getAllDisplays().some(({ workArea }) => {
    const overlapWidth = Math.min(bounds.x + bounds.width, workArea.x + workArea.width) - Math.max(bounds.x, workArea.x);
    const overlapHeight = Math.min(bounds.y + bounds.height, workArea.y + workArea.height) - Math.max(bounds.y, workArea.y);
    return overlapWidth >= 120 && overlapHeight >= 80;
  });
}

function readWindowState() {
  try {
    const state = JSON.parse(fs.readFileSync(windowStatePath(), "utf8"));
    if (!isFiniteBounds(state.bounds) || !isVisibleOnAnyDisplay(state.bounds)) return null;
    return { bounds: state.bounds, isMaximized: state.isMaximized === true };
  } catch {
    return null;
  }
}

function saveWindowState(window = mainWindow) {
  if (!window || window.isDestroyed()) return;
  if (windowStateTimer) {
    clearTimeout(windowStateTimer);
    windowStateTimer = null;
  }
  const state = {
    bounds: window.getNormalBounds(),
    isMaximized: window.isMaximized(),
  };
  try {
    fs.mkdirSync(app.getPath("userData"), { recursive: true });
    fs.writeFileSync(windowStatePath(), `${JSON.stringify(state, null, 2)}\n`, { mode: 0o600 });
  } catch (error) {
    if (!app.isPackaged) console.warn("无法保存窗口状态：", error.message);
  }
}

function scheduleWindowStateSave() {
  if (windowStateTimer) clearTimeout(windowStateTimer);
  windowStateTimer = setTimeout(() => saveWindowState(), 250);
}

function sendMenuCommand(command) {
  if (!MENU_COMMANDS.has(command) || !mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.webContents.send("datamaster:menu-command", command);
}

function buildApplicationMenu() {
  const isMac = process.platform === "darwin";
  const template = [
    ...(isMac ? [{
      label: app.name,
      submenu: [
        { role: "about", label: "关于 DataMaster" },
        { type: "separator" },
        { label: "设置…", accelerator: "CmdOrCtrl+,", click: () => sendMenuCommand("open-settings") },
        { type: "separator" },
        { role: "services", label: "服务" },
        { type: "separator" },
        { role: "hide", label: "隐藏 DataMaster" },
        { role: "hideOthers", label: "隐藏其他" },
        { role: "unhide", label: "全部显示" },
        { type: "separator" },
        { role: "quit", label: "退出 DataMaster" },
      ],
    }] : []),
    {
      label: "文件",
      submenu: [
        { label: "新建分析", accelerator: "CmdOrCtrl+N", click: () => sendMenuCommand("new-analysis") },
        { label: "导入 Excel / CSV…", accelerator: "CmdOrCtrl+O", click: () => sendMenuCommand("import-data") },
        { type: "separator" },
        { label: "导出 Excel…", accelerator: "CmdOrCtrl+Shift+E", click: () => sendMenuCommand("export-excel") },
        { label: "导出 Word…", accelerator: "CmdOrCtrl+Shift+W", click: () => sendMenuCommand("export-word") },
        { label: "导出 PDF…", accelerator: "CmdOrCtrl+Shift+P", click: () => sendMenuCommand("export-pdf") },
        ...(!isMac ? [
          { type: "separator" },
          { label: "设置…", accelerator: "CmdOrCtrl+,", click: () => sendMenuCommand("open-settings") },
          { type: "separator" },
          { role: "quit", label: "退出" },
        ] : [{ type: "separator" }, { role: "close", label: "关闭窗口" }]),
      ],
    },
    {
      label: "编辑",
      submenu: [
        { role: "undo", label: "撤销" },
        { role: "redo", label: "重做" },
        { type: "separator" },
        { role: "cut", label: "剪切" },
        { role: "copy", label: "复制" },
        { role: "paste", label: "粘贴" },
        { role: "selectAll", label: "全选" },
      ],
    },
    {
      label: "分析",
      submenu: [
        { label: "经营总览", accelerator: "CmdOrCtrl+1", click: () => sendMenuCommand("navigate:overview") },
        { label: "产品分析", accelerator: "CmdOrCtrl+2", click: () => sendMenuCommand("navigate:product") },
        { label: "客户分析", accelerator: "CmdOrCtrl+3", click: () => sendMenuCommand("navigate:customer") },
        { label: "利润与成本", accelerator: "CmdOrCtrl+4", click: () => sendMenuCommand("navigate:profit") },
        { label: "销售组利润", accelerator: "CmdOrCtrl+5", click: () => sendMenuCommand("navigate:salesGroup") },
        { type: "separator" },
        { label: "显示 / 隐藏 AI 助手", accelerator: "CmdOrCtrl+Shift+A", click: () => sendMenuCommand("toggle-ai-assistant") },
      ],
    },
    {
      label: "视图",
      submenu: [
        { role: "resetZoom", label: "实际大小" },
        { role: "zoomIn", label: "放大" },
        { role: "zoomOut", label: "缩小" },
        { type: "separator" },
        { role: "togglefullscreen", label: "切换全屏" },
        ...(!app.isPackaged ? [
          { type: "separator" },
          { role: "reload", label: "重新载入" },
          { role: "toggleDevTools", label: "开发者工具" },
        ] : []),
      ],
    },
    {
      label: "窗口",
      submenu: [
        { role: "minimize", label: "最小化" },
        ...(isMac ? [
          { role: "zoom", label: "缩放" },
          { type: "separator" },
          { role: "front", label: "前置全部窗口" },
        ] : [{ role: "close", label: "关闭" }]),
      ],
    },
    {
      role: "help",
      label: "帮助",
      submenu: [
        { label: "快捷键", click: () => sendMenuCommand("show-shortcuts") },
        { label: "打开 DataMaster 在线版", click: () => shell.openExternal(ONLINE_APP_URL) },
      ],
    },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

function assertTrustedSender(event) {
  const sender = event.sender;
  const frame = event.senderFrame;
  if (!mainWindow || mainWindow.isDestroyed() || sender !== mainWindow.webContents || !frame || frame !== sender.mainFrame) {
    throw new Error("不受信任的桌面请求");
  }
  let frameOrigin;
  try {
    frameOrigin = new URL(frame.url).origin;
  } catch {
    throw new Error("无效的桌面请求来源");
  }
  if (!backendOrigin || frameOrigin !== backendOrigin) throw new Error("桌面请求来源不匹配");
}

function normalizeSuggestedName(value, extension) {
  const fallback = `DataMaster-${new Date().toISOString().slice(0, 10)}.${extension}`;
  if (typeof value !== "string") return fallback;
  let name = path.basename(value).replace(/[\u0000-\u001f<>:"/\\|?*]/g, "-").trim().slice(0, 120);
  if (!name) return fallback;
  if (!name.toLowerCase().endsWith(`.${extension}`)) name = `${name}.${extension}`;
  return name;
}

function registerIpcHandlers() {
  ipcMain.handle("datamaster:files:open", async (event, options = {}) => {
    assertTrustedSender(event);
    const kind = Object.hasOwn(OPEN_FILE_FILTERS, options.kind) ? options.kind : "data";
    const properties = ["openFile"];
    if (options.multiple !== false) properties.push("multiSelections");
    const result = await dialog.showOpenDialog(mainWindow, {
      title: kind === "data" ? "导入经营数据" : "选择文件",
      buttonLabel: "选择",
      properties,
      filters: OPEN_FILE_FILTERS[kind],
    });
    if (result.canceled) return { canceled: true, filePaths: [], files: [] };
    pruneAuthorizedFileSelections();
    const files = kind === "data"
      ? await Promise.all(result.filePaths.map(authorizeSelectedFile))
      : [];
    // Data imports use short-lived opaque tokens; renderer code never receives local paths.
    return {
      canceled: false,
      filePaths: kind === "data" ? [] : result.filePaths,
      files,
    };
  });

  ipcMain.handle("datamaster:files:upload-selection", async (event, request = {}) => {
    assertTrustedSender(event);
    pruneAuthorizedFileSelections();
    const tokens = Array.isArray(request.tokens) ? request.tokens : [];
    if (!tokens.length || tokens.length > 30) throw new Error("请选择 1–30 个经营数据文件");
    const selections = tokens.map((token) => authorizedFileSelections.get(token));
    if (selections.some((selection) => !selection)) throw new Error("部分文件选择已失效，请重新选择文件");
    let totalBytes = 0;
    for (let index = 0; index < selections.length; index += 1) {
      const selection = selections[index];
      const stat = await fs.promises.stat(selection.path);
      if (!stat.isFile() || stat.size !== selection.size || Math.abs(stat.mtimeMs - selection.lastModified) > 1) {
        authorizedFileSelections.delete(tokens[index]);
        throw new Error(`${selection.name} 在选择后发生变化，请重新选择`);
      }
      if (path.extname(selection.path).toLowerCase() === ".xls" && selection.size > 64 * 1024 * 1024) {
        throw new Error(`${selection.name} 是旧版 .xls 且超过 64 MB，请先另存为 .xlsx 或 .csv`);
      }
      totalBytes += selection.size;
    }
    if (totalBytes > MAX_SELECTED_FILE_BYTES) throw new Error("单次导入的文件总大小不能超过 500 MB");
    if (!backendOrigin || !backendDesktopToken) throw new Error("本地分析服务尚未就绪");

    const mapping = request.mapping && typeof request.mapping === "object" ? request.mapping : null;
    try {
      const result = await streamSelectedFilesToBackend(event, selections, mapping, request.append === true);
      tokens.forEach((token) => authorizedFileSelections.delete(token));
      return result;
    } catch (error) {
      if (error?.name === "AbortError") throw new Error("本地大文件分析超过 15 分钟，请检查文件或重试");
      throw error;
    }
  });

  ipcMain.handle("datamaster:files:save", async (event, options = {}) => {
    assertTrustedSender(event);
    const type = Object.hasOwn(SAVE_FILE_TYPES, options.type) ? options.type : "xlsx";
    const fileType = SAVE_FILE_TYPES[type];
    const result = await dialog.showSaveDialog(mainWindow, {
      title: `导出${fileType.label}`,
      buttonLabel: "保存",
      defaultPath: path.join(app.getPath("documents"), normalizeSuggestedName(options.suggestedName, fileType.extension)),
      filters: [{ name: fileType.label, extensions: [fileType.extension] }],
      properties: ["createDirectory", "showOverwriteConfirmation"],
    });
    return {
      canceled: result.canceled,
      filePath: result.canceled ? null : result.filePath,
    };
  });

  ipcMain.handle("datamaster:files:save-export", async (event, options = {}) => {
    assertTrustedSender(event);
    const type = Object.hasOwn(SAVE_FILE_TYPES, options.type) ? options.type : "xlsx";
    const fileType = SAVE_FILE_TYPES[type];
    const rawBytes = options.bytes;
    const bytes = Buffer.isBuffer(rawBytes)
      ? rawBytes
      : ArrayBuffer.isView(rawBytes)
        ? Buffer.from(rawBytes.buffer, rawBytes.byteOffset, rawBytes.byteLength)
        : rawBytes instanceof ArrayBuffer
          ? Buffer.from(rawBytes)
          : null;
    if (!bytes || bytes.length === 0) throw new Error("导出内容为空");
    if (bytes.length > 512 * 1024 * 1024) throw new Error("单个导出文件不能超过 512 MB");

    const result = await dialog.showSaveDialog(mainWindow, {
      title: `导出${fileType.label}`,
      buttonLabel: "保存",
      defaultPath: path.join(app.getPath("documents"), normalizeSuggestedName(options.suggestedName, fileType.extension)),
      filters: [{ name: fileType.label, extensions: [fileType.extension] }],
      properties: ["createDirectory", "showOverwriteConfirmation"],
    });
    if (result.canceled || !result.filePath) return { canceled: true, success: false, filePath: null, bytesWritten: 0 };
    await fs.promises.writeFile(result.filePath, bytes, { mode: 0o600 });
    return { canceled: false, success: true, filePath: result.filePath, bytesWritten: bytes.length };
  });

  ipcMain.handle("datamaster:window:control", (event, action) => {
    assertTrustedSender(event);
    if (!mainWindow || mainWindow.isDestroyed()) return false;
    if (action === "minimize") mainWindow.minimize();
    else if (action === "toggle-maximize") mainWindow.isMaximized() ? mainWindow.unmaximize() : mainWindow.maximize();
    else if (action === "close") mainWindow.close();
    else throw new Error("不支持的窗口操作");
    return true;
  });

  ipcMain.handle("datamaster:app:info", (event) => {
    assertTrustedSender(event);
    return { name: app.name, version: app.getVersion(), isPackaged: app.isPackaged };
  });
}

function isAllowedAppNavigation(target) {
  try {
    return backendOrigin !== null && new URL(target).origin === backendOrigin;
  } catch {
    return false;
  }
}

async function openExternalHttps(target) {
  try {
    const parsed = new URL(target);
    if (parsed.protocol === "https:") await shell.openExternal(parsed.toString());
  } catch {
    // Ignore malformed URLs instead of passing them to the operating system.
  }
}

async function createWindow() {
  const { url, desktopToken } = await startBackend();
  backendOrigin = new URL(url).origin;
  backendDesktopToken = desktopToken;
  const savedState = readWindowState();
  const initialBounds = savedState?.bounds ?? DEFAULT_WINDOW_BOUNDS;
  mainWindow = new BrowserWindow({
    title: "DataMaster · 个人经营分析",
    ...initialBounds,
    minWidth: MIN_WINDOW_BOUNDS.width,
    minHeight: MIN_WINDOW_BOUNDS.height,
    backgroundColor: "#f5f7fb",
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  mainWindow.webContents.session.webRequest.onBeforeSendHeaders(
    { urls: [`${url}/api/*`] },
    (details, callback) => callback({
      requestHeaders: { ...details.requestHeaders, "X-DataMaster-Token": desktopToken },
    }),
  );

  mainWindow.webContents.setWindowOpenHandler(({ url: target }) => {
    void openExternalHttps(target);
    return { action: "deny" };
  });
  mainWindow.webContents.on("will-navigate", (event, target) => {
    if (!isAllowedAppNavigation(target)) {
      event.preventDefault();
      void openExternalHttps(target);
    }
  });
  mainWindow.webContents.on("will-attach-webview", (event) => event.preventDefault());
  mainWindow.once("ready-to-show", () => {
    if (savedState?.isMaximized) mainWindow.maximize();
    mainWindow.show();
  });
  mainWindow.on("resize", scheduleWindowStateSave);
  mainWindow.on("move", scheduleWindowStateSave);
  mainWindow.on("maximize", scheduleWindowStateSave);
  mainWindow.on("unmaximize", scheduleWindowStateSave);
  mainWindow.on("close", () => saveWindowState(mainWindow));
  mainWindow.on("closed", () => { mainWindow = null; });

  buildApplicationMenu();
  await mainWindow.loadURL(url);
  writeDevelopmentLog(`window loaded url=${url}`);
}

function stopBackend() {
  quitting = true;
  if (!backendProcess) return;
  if (process.platform === "win32") {
    spawn("taskkill", ["/pid", String(backendProcess.pid), "/t", "/f"], { windowsHide: true });
  } else {
    backendProcess.kill("SIGTERM");
    const processToKill = backendProcess;
    setTimeout(() => {
      if (processToKill.exitCode === null) processToKill.kill("SIGKILL");
    }, 3000).unref();
  }
}

app.requestSingleInstanceLock() || app.quit();
app.on("second-instance", () => {
  if (mainWindow) {
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.show();
    mainWindow.focus();
  }
});
app.whenReady().then(() => {
  registerIpcHandlers();
  return createWindow();
}).catch((error) => {
  writeDevelopmentLog(`startup failed=${error.stack || error.message}`);
  dialog.showErrorBox("DataMaster 启动失败", error.message);
  app.quit();
});
app.on("before-quit", stopBackend);
app.on("window-all-closed", () => app.quit());
