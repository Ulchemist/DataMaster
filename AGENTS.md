# AGENTS.md — DataMaster 工作指南

DataMaster 是个人经营分析工作台：导入多份 Excel/XLS/CSV 后在本机完成合并、质检、经营/产品/客户/利润/销售组分析，生成带证据的建议与 Excel/Word/PDF 报告。当前处于 v0.6 本地分析工作台验收阶段，本地版为准，在线站点（`site/`）与安装包本轮不调整。

## 仓库结构与各端关系

```
frontend/   分析前端（index.html / styles.css / app.js），无框架、无构建步骤
backend/    Spring Boot (Java 17, Maven)，分析引擎 + API + 静态托管前端
desktop/    Electron 壳（main.cjs / preload.cjs），拉起后端 jar 并加载其 URL
site/       独立的在线版（Cloudflare 全栈），与桌面端不是一套代码，勿混改
demo/       演示脚本与示例 CSV（demo/samples/*.csv）
```

关键链路：`desktop` 启动 → 拉起 `backend/target/datamaster-demo-0.1.0.jar`（127.0.0.1 随机端口）→ jar 内静态资源即 `frontend/`（由 `backend/pom.xml` 的 resource 配置在打包时拷入 `BOOT-INF/classes/static/`）。

**改了 `frontend/` 必须 `cd backend && mvn package -DskipTests` 重新打 jar，桌面端才能看到效果。**

## 构建与启动

```bash
# 后端（Java 17+，Maven 在 /opt/homebrew/opt/maven/libexec/bin/mvn）
cd backend && mvn package            # 含测试；-DskipTests 跳过
java -jar target/datamaster-demo-0.1.0.jar   # 默认 127.0.0.1:8765

# 桌面端（开发模式直接用 ../backend/target 下的 jar）
cd desktop && npm install && npm start
```

## 前端架构要点（frontend/）

- **渲染方式**：全部模板字符串拼 HTML → `el.viewContent.innerHTML`（`app.js` 的 `renderActiveView`，约 1330 行）。无虚拟 DOM，重渲染即整页替换。文本插值必须过 `esc()`，属性插值过 `attr()`。
- **状态**：单例 `state`（约 112 行）；视图切换 `switchView` / `switchWorkspace`；工作区 `home|data|report` + 分析视图 `overview|product|customer|profit|salesGroup`。
- **异步下钻（explorer）**：各视图的明细/透镜区块由 `ensureActiveExplorers` → `loadExplorer` 异步拉取，完成后 `requestExplorerRender` 防抖 → `renderActiveViewPreservingInteraction` 整页重渲染并还原焦点/滚动。
- **动效门控（重要，勿破坏）**：入场动画只在真实视图切换时播放。流程：`renderAll`/`switchView`/`switchWorkspace` 置 `animateNextViewRender = true` → `renderActiveView` 消费该标记并切换 `body.suppress-view-motion`；异步/搜索/分页重渲染不授权，`suppress-view-motion` 类常驻直到下一次真实切换。CSS 所有入场动画都挂在 `body:not(.suppress-view-motion)` 下。
- **桌面判定**：`IS_DESKTOP = window.dataMasterDesktop?.isDesktop === true`（preload 注入）。为真才加 `body.desktop-app` 类。**样式表中桌面端专属规则（含末尾的 Lumina 打磨层）大多以 `.desktop-app` 开头——普通浏览器里调试时这些规则不生效**，需注入 mock（见下"QA 工作流"）。
- **样式结构**：`styles.css` 分两层——1–670 行旧 Web 版，671 行起桌面端覆写，文件末尾是 Lumina 打磨层（动效/表面/图表美化，约 2290 行后）。同优先级后者胜出，改样式优先在打磨层追加而非大改旧规则。
- **图表**：纯字符串生成，无图表库。唯一 SVG 是趋势图（`renderTrendChart`，含 defs 渐变/利润面积/pathLength 描线动画）；其余为 div 宽度条（`.bar-track > i`）、conic-gradient 环（`.donut-plot --donut`/`.score-ring --score`/`.concentration-ring --share`）。tooltip 只有趋势图用 `#chartTooltip`（`data-chart-tip`），其他靠 `title`。
- **已知坑**：全局 `svg { stroke: currentColor }` 图标规则会继承到图表 SVG 子元素，热区矩形/文字会被描边——已在打磨层对 `.trend-svg` 各元素显式 `stroke: none` 修复；新增 SVG 元素时注意显式声明 fill/stroke。
- **数字自适应**：`fitDonutCenterValues`（甜甜圈中心值缩放到孔内）与 `animateKpiValues`（KPI 数字滚动）在每次 `renderActiveView` 后执行；后者被 `suppress-view-motion` 抑制。
- **JS 依赖的 id/class 不要改名**：`cacheElements()`（约 267 行）缓存约 60 个 id；另有 `.view-tabs [data-view]`、`[data-workspace]`、`.is-active`、`aria-selected` 等状态选择器被 JS 和 CSS 双向依赖。

## 后端要点（backend/）

- API 在 `api/`（Workspace/Report/Analysis/AiAnalysis/Provider/Sync 等 Controller）；分析引擎在 `service/`；工作区持久化在 `WorkspacePersistenceService`。
- `DesktopTokenFilter`：仅当 `datamaster.desktop-token` 非空（桌面壳启动时注入）才校验 `X-DataMaster-Token`；本地裸跑 jar 默认放行 `/api/*`。
- 上传：`POST /api/workspace/sources`，multipart 字段名 `files`（可多个）。
- 关键配置（application.yml，均可环境变量覆盖）：`DATAMASTER_WORKSPACE_PATH`（默认 `~/.datamaster/workspace`）、`DATAMASTER_IMPORT_MAX_ROWS`、上传上限 500MiB。
- 字段识别：表头按中英文同义词映射到业务维度（如 `换算只数`→convertedQuantity，`出库数量`→outboundQuantity，见 `ExploreService`）。示例 CSV 缺的字段会导致对应 explorer 400（"数量口径 convertedQuantity 不可用"）——这是数据问题不是前端 bug。
- 测试：`mvn test`（`src/test/java`，fixtures 在 `AnalysisFixtures`）。

## 桌面壳要点（desktop/）

- `main.cjs`：找空闲端口 → 拉起 jar（开发用 `desktop/runtime` 内 Java 或系统 Java）→ 注入随机 `desktopToken` → 创建 BrowserWindow 加载后端 URL。窗口状态持久化。
- `preload.cjs`：contextBridge 白名单暴露 `dataMasterDesktop.*`（onMenuCommand/openFiles/uploadSelectedFiles/saveExport 等）；渲染层无 Node 能力，不要试图在 app.js 里用 Node API。
- iCloud 目录可能导致 Electron 签名损坏启动卡死，`prepare-electron.cjs`（prestart）已做 xattr 清理。

## QA / 验证工作流（本机已验证可用）

```bash
# 1) 隔离工作区 + 磁盘前端热改，免重打包快速验证
DATAMASTER_WORKSPACE_PATH=/tmp/dm-ws java -jar backend/target/datamaster-demo-0.1.0.jar \
  --server.port=8765 \
  '--spring.web.resources.static-locations=file:<repo绝对路径>/frontend/'

# 2) 上传示例数据（注意用隔离工作区，别碰用户真实数据 ~/.datamaster）
curl -X POST http://127.0.0.1:8765/api/workspace/sources \
  -F "files=@demo/samples/经营明细_2026-01.csv" -F "files=@demo/samples/经营明细_2026-02.csv"

# 3) 截图验证：用 npx 缓存里的 playwright-core + 系统 Chrome（channel:"chrome"），
#    无需下载浏览器；NODE_PATH 指向 /Users/alchemist/.npm/_npx/e41f203b7505f1fb/node_modules
#    页面里必须 page.addInitScript(() => { window.dataMasterDesktop = { isDesktop: true }; })
#    否则 .desktop-app 样式（含打磨层）不生效，截图会误导。
```

## 提交改动时的约定

- 最小改动：前端无构建、无 lint 配置；改完至少 `node --check frontend/app.js`。
- 后端改动跑 `mvn test`；前端/桌面改动按上面 QA 流程截图自查。
- 不动 `site/`（在线版）、不碰用户真实工作区数据、git 操作需用户明确要求。
- AGENTS.md 需与实际代码保持同步：改了上述流程、结构或约定时一并更新本文件。
