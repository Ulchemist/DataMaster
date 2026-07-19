# DataMaster Desktop

Electron 桌面壳会启动只监听 `127.0.0.1` 的 Spring Boot 分析服务，并在独立应用窗口中加载同一套工作台。按正式发布流程构建的安装包将包含精简 Java 17 运行时；当前尚未交付安装包，仓库内现有运行时仅为 macOS arm64，不能直接用于 Windows。

当前桌面壳已经提供原生菜单、快捷键、安全桥接和窗口状态恢复；系统打开/另存为对话框 API 已预留，但当前分析前端仍使用文件输入框和浏览器下载流程。开发阶段要求 Java 17、Maven、Node.js 与 npm，可以直接启动检查，无需先制作安装包：

```bash
cd backend && mvn package
cd ../desktop && npm install
npm start
```

渲染层只能调用白名单桌面能力：

- `dataMasterDesktop.onMenuCommand(listener)`：接收导入、导出、设置、分析页面切换等原生菜单命令。
- `dataMasterDesktop.openFiles({ kind, multiple })`：打开系统文件选择器；经营数据默认启用原生多选，并返回短时有效的安全文件令牌。
- `dataMasterDesktop.uploadSelectedFiles(tokens, { append, mapping })`：由主进程把已授权文件流式提交到本机分析服务，避免大文件字节复制进渲染进程；`append` 为 `true` 时保留当前工作区。
- `dataMasterDesktop.onUploadProgress(listener)`：订阅主进程流式传输的文件、字节和总量进度；返回取消订阅函数。
- `dataMasterDesktop.saveFile({ type, suggestedName })`：打开系统另存为窗口；`type` 支持 `xlsx`、`docx`、`csv`、`json`、`pdf`。
- `dataMasterDesktop.windowControls`：最小化、最大化/恢复、关闭窗口。

IPC 不暴露任意通道，主进程同时校验请求必须来自当前本地分析窗口。

macOS 未签名 arm64 目录构建（不是安装包）：

```bash
cd backend && mvn package
cd ../desktop
chmod +x build-runtime.sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./build-runtime.sh
npm install
npm run build:mac
```

Windows x64 打包配置已存在但尚未验证。必须在 Windows 构建机生成对应的 Java 17 `jlink` 运行时；当前 `build-runtime.sh` 仅适用于 macOS，不能直接用于 Windows。`dist:win`、安装、签名和运行验收均留待打包阶段完成。
