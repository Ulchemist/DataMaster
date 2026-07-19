const { existsSync } = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

// macOS may attach Finder/FileProvider metadata when node_modules lives in an
// iCloud-backed Documents folder.  Those attributes invalidate Electron's
// nested code signatures and can leave the launcher blocked in dyld before our
// main process writes a log line.  Clear metadata only from the disposable
// development Electron bundle; packaged applications are unaffected.
if (process.platform === "darwin") {
  const electronApp = path.join(__dirname, "node_modules", "electron", "dist", "Electron.app");
  if (existsSync(electronApp)) {
    const result = spawnSync("xattr", ["-cr", electronApp], { stdio: "ignore" });
    if (result.error || result.status !== 0) {
      console.warn("无法清理 Electron 开发运行库的 macOS 扩展属性；若启动停滞，请重新安装依赖。");
    }
  }
}
