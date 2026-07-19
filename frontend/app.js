(() => {
  "use strict";

  const API_BASE = window.__DATAMASTER_API_BASE__ || "";
  const IS_DESKTOP = window.dataMasterDesktop?.isDesktop === true;
  const ACCEPTED_EXTENSIONS = ["xlsx", "xls", "csv"];
  const MAX_UPLOAD_BYTES = 500 * 1024 * 1024;
  const MAX_LEGACY_XLS_BYTES = 64 * 1024 * 1024;
  const LARGE_UPLOAD_BYTES = 100 * 1024 * 1024;
  const LARGE_UPLOAD_TIMEOUT_MS = 15 * 60 * 1000;
  const PROFIT_COVERAGE_EXPLANATION = "收入与所选成本同时有效行的绝对销售额 ÷ 全部有效绝对销售额；100% 仅表示成本口径完整，不代表毛利率为 100% 或数据没有其他风险。";
  const VIEW_META = {
    overview: ["经营总览", "从收入、利润与风险开始，快速看清本期经营结果。"],
    product: ["产品分析", "比较产品规模与盈利质量，识别增长引擎和止损对象。"],
    customer: ["客户分析", "理解客户贡献、集中度与服务价值，避免只看收入排行。"],
    profit: ["利润与成本", "拆开成本和费用，测算通往盈亏平衡的具体路径。"],
    salesGroup: ["销售组利润", "按调拨成本核算阿米巴利润，并下钻到销售组的客户与产品。"]
  };
  const WORKSPACE_META = {
    home: ["项目主页", "从最近数据和常用任务开始，不让仪表盘抢走你的注意力。"],
    data: ["数据源管理", "集中管理工作簿、合并结果和数据质量。"],
    report: ["报告中心", "把已核验的结论、图表和行动建议整理成可交付报告。"]
  };
  const MAPPING_FIELDS = [
    { id: "revenue", label: "收入", required: true, description: "用于销售额、占比和规模排序" },
    { id: "cost", label: "成本", required: false, description: "确认后才会计算毛利与毛利率" },
    { id: "expense", label: "期间费用", required: false, description: "确认后才会计算经营利润与扭亏缺口" },
    { id: "product", label: "产品", required: false, description: "用于产品结构与贡献分析" },
    { id: "customer", label: "客户", required: false, description: "用于客户贡献与集中度分析" },
    { id: "quantity", label: "数量", required: false, description: "用于销量、单价和量价拆解" },
    { id: "date", label: "日期", required: false, description: "用于期间识别与趋势分析" }
  ];
  const DIMENSIONS = {
    product: "产品",
    customer: "客户",
    customerAnalysisMajor: "客户分析大类",
    customerAnalysisCategory: "客户分析分类",
    businessMajor: "经营分析大类",
    businessCategory: "经营分析分类",
    productForm: "产品形态（汇报）",
    salesRegion: "销售区域",
    salesDepartment: "销售部门",
    salesGroup: "销售组",
    channel: "渠道"
  };
  // 维度下钻路径：父维度 → 子维度（动态维度浏览器中逐行提供下钻入口）
  const DIMENSION_DRILLS = { customerDetail: "deliveryAddress", department: "salesGroup" };
  const PRODUCT_STRUCTURE_LENSES = [
    { key: "businessMajor", label: "经营分析大类", groupBy: DIMENSIONS.businessMajor },
    { key: "businessCategory", label: "经营分析分类", groupBy: DIMENSIONS.businessCategory },
    { key: "productForm", label: "产品形态（汇报）", groupBy: DIMENSIONS.productForm }
  ];
  const PRODUCT_FILTERS = [
    ["customerAnalysisLargeCategory", "客户分析大类", DIMENSIONS.customerAnalysisMajor],
    ["customerAnalysisCategory", "客户分析分类", DIMENSIONS.customerAnalysisCategory],
    ["businessAnalysisLargeCategory", "经营分析大类", DIMENSIONS.businessMajor],
    ["businessAnalysisCategory", "经营分析分类", DIMENSIONS.businessCategory],
    ["productForm", "产品形态", DIMENSIONS.productForm]
  ];
  const CUSTOMER_FILTERS = [
    ["region", "销售区域", DIMENSIONS.salesRegion],
    ["department", "销售部门", DIMENSIONS.salesDepartment],
    ["salesGroup", "销售组", DIMENSIONS.salesGroup],
    ["channel", "渠道", DIMENSIONS.channel]
  ];
  const SALES_GROUP_FILTERS = CUSTOMER_FILTERS.slice(0, 3);
  const DONUT_COLORS = ["#5b5ee8", "#32a7a0", "#f2a65a", "#e56f68", "#7a8fc7", "#9b73d5", "#91a3ad"];
  const REPORT_SECTIONS = [
    ["overview", "经营结论"],
    ["metrics", "核心指标与趋势"],
    ["portfolio", "产品与客户"],
    ["organization", "销售组织利润"],
    ["actions", "利润改善建议"],
    ["evidence", "证据附录"]
  ];
  const PDF_SCOPE_SECTIONS = {
    overview: ["overview", "trend"],
    product: ["product"],
    customer: ["customer"],
    profit: ["profit", "insights"],
    salesGroup: ["organization"],
    evidence: ["quality"]
  };

  const DEFAULT_PROVIDERS = [
    {
      id: "deepseek",
      name: "DeepSeek",
      baseUrl: "https://api.deepseek.com",
      model: "deepseek-v4-flash",
      models: [
        { id: "deepseek-v4-flash", label: "DeepSeek V4 Flash", description: "兼顾速度与日常经营分析", recommended: true },
        { id: "deepseek-v4-pro", label: "DeepSeek V4 Pro", description: "更强推理，适合复杂经营归因", recommended: false }
      ],
      customModelAllowed: true,
      configured: false
    },
    {
      id: "bailian",
      name: "阿里云百炼",
      baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1",
      model: "qwen3.7-plus",
      models: [
        { id: "qwen3.7-plus", label: "Qwen 3.7 Plus", description: "适合中文经营分析与报告生成", recommended: true },
        { id: "qwen3.7-max", label: "Qwen 3.7 Max", description: "适合复杂推理和长表摘要", recommended: false },
        { id: "qwen3-max", label: "Qwen 3 Max", description: "适合复杂综合分析", recommended: false },
        { id: "qwen-plus", label: "Qwen Plus", description: "成本友好的通用模型", recommended: false },
        { id: "qwen-turbo", label: "Qwen Turbo", description: "低延迟批量摘要", recommended: false }
      ],
      customModelAllowed: true,
      configured: false
    }
  ];

  const state = {
    analysis: null,
    providers: DEFAULT_PROVIDERS.map(cloneData),
    activeProviderId: "deepseek",
    settingsProviderId: "deepseek",
    activeWorkspace: "home",
    activeView: "overview",
    inspectorTab: "summary",
    inspectorEvidenceIds: ["EV-SUM-REV", "EV-SUM-OP"],
    inspectorCollapsed: false,
    sourceNames: [],
    persistedSources: [],
    loading: false,
    insightsLoading: false,
    schemaLoading: false,
    insightRequestToken: 0,
    schemaRequestToken: 0,
    schemaMessage: "",
    forceMappingReview: false,
    deepInsights: [],
    deepInsightMeta: null,
    periodInsights: new Map(),
    evidence: new Map(),
    lastUploadFiles: null,
    mappingDraft: {},
    appliedMapping: {},
    activeBreakdownId: "",
    explorers: new Map(),
    explorerRequestToken: 0,
    productControls: { filters: {}, metric: "revenue", quantityMetric: IS_DESKTOP ? "convertedQuantity" : "", search: "", page: 0, pageSize: 100 },
    customerControls: { filters: {}, selectedCustomer: "", selectedCustomers: [], customerDrillInitialized: false, selectedChannel: "", search: "", lossPage: 0 },
    salesGroupControls: { filters: {}, groupBy: DIMENSIONS.salesGroup, selectedGroup: "", costMetric: "transferCost", drillCustomers: [] },
    dynamicControls: { page: 0, pageSize: 100, search: "", dimensionFilter: "", drill: null },
    drillSuggestions: {},
    drillSuggestionsLoaded: false,
    periodControls: { mode: "cumulative", years: [], months: [], openKey: "", searches: {} },
    openFilterKey: "",
    filterSearches: {},
    aiChats: new Map(),
    chatRequestToken: 0,
    reportSection: "overview",
    pdfScopes: ["overview", "product", "customer", "profit", "salesGroup", "evidence"],
    drawerOpener: null,
    sync: { connected: false, available: true, account: "", lastSyncedAt: "", message: "尚未连接网页账户" }
  };

  const explorerSearchTimers = new Map();
  const explorerAbortControllers = new Map();
  let explorerRenderTimer = 0;
  let workspaceRecoveryPromise = null;
  let workspaceRecoveryError = "";
  let searchCompositionActive = false;
  let lastSearchInputAt = 0;
  let pendingFileInputOptions = {};
  let desktopPickerBusy = false;
  // 只有真实的视图切换（侧栏导航 / 导入 / 恢复工作区）才播放入场动效；
  // 异步下钻、搜索筛选等整页重渲染保持静默，避免动画重复播放。
  let animateNextViewRender = false;
  let specTooltipEl = null;

  const el = {};

  document.addEventListener("DOMContentLoaded", init);

  async function init() {
    cacheElements();
    if (IS_DESKTOP) {
      document.body.classList.add("desktop-app");
      ensureDesktopImportDialog();
      updateDesktopImportCopy();
    }
    bindEvents();
    renderWorkspaceRestoreState();
    const results = await Promise.allSettled([loadProviders(), loadSyncStatus(), restoreCurrentWorkspace()]);
    if (results[2].status === "rejected") {
      renderWorkspaceRestoreError(results[2].reason);
    }
  }

  function renderWorkspaceRestoreState() {
    document.body.classList.add("has-no-data");
    el.pageTitle.textContent = "正在恢复最近工作区";
    el.pageSubtitle.textContent = "读取保存在本机的文件索引、字段口径与分析结果。";
    el.viewContent.setAttribute("aria-busy", "true");
    el.viewContent.innerHTML = `<div class="workspace-restore-state" role="status"><span class="restore-orbit"><i></i><b></b></span><div><span class="eyebrow">LOCAL WORKSPACE</span><h2>正在恢复上次分析</h2><p>只有在没有保存记录时才会进入空白项目。</p></div></div>`;
    [el.downloadXlsx, el.downloadDocx, el.downloadPdf, el.clearDataBtn].filter(Boolean).forEach((button) => { button.disabled = true; });
    document.querySelectorAll(".view-tabs [data-view]").forEach((tab) => { tab.disabled = true; tab.setAttribute("aria-disabled", "true"); });
  }

  function renderWorkspaceRestoreError(error) {
    el.pageTitle.textContent = "无法读取最近工作区";
    el.pageSubtitle.textContent = "本机服务暂时没有返回工作区状态；当前不会误判为空白项目。";
    el.viewContent.setAttribute("aria-busy", "false");
    el.viewContent.innerHTML = `<div class="desktop-empty-state restore-error"><div class="empty-visual" aria-hidden="true"><span></span><i></i><b>!</b></div><span class="eyebrow">WORKSPACE UNAVAILABLE</span><h2>最近数据没有丢失</h2><p>${esc(friendlyError(error))}。请重试读取；只有服务明确返回空工作区后，程序才会显示新建项目。</p><div class="empty-actions"><button class="button button-primary" type="button" data-action="retry-workspace"><svg><use href="#i-refresh"></use></svg><span>重新读取</span></button></div></div>`;
  }

  async function restoreCurrentWorkspace() {
    try {
      const result = await apiFetch("/api/workspace/current", {}, 30000);
      if (!result?.analysis) {
        renderEmptyState();
        return false;
      }
      applyWorkspacePayload(result, { restored: true });
      setStatus("success", "已恢复上次本地工作区。", 2800);
      return true;
    } catch (error) {
      if (error.status === 404) {
        renderEmptyState();
        return false;
      }
      throw error;
    }
  }

  async function retryWorkspaceRestore() {
    renderWorkspaceRestoreState();
    try {
      await restoreCurrentWorkspace();
    } catch (error) {
      renderWorkspaceRestoreError(error);
    }
  }

  function applyWorkspacePayload(result, { restored = false } = {}) {
    if (!result?.analysis) return false;
    const previousAnalysisId = state.analysis?.id || "";
    invalidateAiRequests();
    state.analysis = normalizeAnalysis(result.analysis);
    state.persistedSources = normalizeWorkspaceSources(result.sources || result.workspace?.sources || []);
    state.sourceNames = state.persistedSources.map((source) => source.name);
    state.lastUploadFiles = null;
    state.schemaMessage = "";
    state.forceMappingReview = false;
    state.deepInsights = [];
    state.deepInsightMeta = null;
    resetExplorerState();
    state.aiChats.clear();
    state.appliedMapping = compactMapping(result.mapping || result.fieldMapping || state.appliedMapping || {});
    initializeMappingState(state.analysis, state.appliedMapping);
    document.body.classList.remove("has-no-data");
    state.activeWorkspace = "analysis";
    state.activeView = VIEW_META[result.activeView] ? result.activeView : "overview";
    renderAll();
    if (previousAnalysisId && previousAnalysisId !== state.analysis.id) releaseAnalysis(previousAnalysisId);
    if (restored) toast("已恢复最近工作区", `${formatInteger(state.analysis.rowCount)} 行数据已就绪。`, "success");
    return true;
  }

  function normalizeWorkspaceSources(input) {
    return (Array.isArray(input) ? input : []).map((source, index) => typeof source === "string"
      ? { id: String(index), name: source, size: 0, rows: 0 }
      : {
        id: String(source.id || source.sourceId || source.fileId || index),
        name: String(source.name || source.fileName || source.originalName || `数据源 ${index + 1}`),
        size: numberValue(source.size ?? source.bytes, 0),
        rows: numberValue(source.rows ?? source.rowCount, 0),
        sha256: String(source.sha256 || ""),
        periods: (Array.isArray(source.periods) ? source.periods : source.period ? [source.period] : []).map(String).filter(Boolean),
        periodBasis: String(source.periodBasis || ""),
        seriesKey: String(source.seriesKey || ""),
        needsAiReview: source.needsAiReview === true,
        relationshipNote: String(source.relationshipNote || "")
      });
  }

  function cacheElements() {
    [
      "statusRegion", "dataStatusDot", "dataModeLabel", "generatedAt", "clearDataBtn",
      "openSync", "syncStatusDot", "openSettings", "providerStatusDot", "importBtn", "fileInput",
      "pageTitle", "pageSubtitle", "downloadXlsx", "downloadDocx", "dropZone", "importTitle",
      "importDescription", "sourceSummary", "sourceCount", "viewContent", "settingsDialog",
      "settingsForm", "closeSettings", "providerChoices", "activeProviderHint", "providerBaseUrl",
      "providerModel", "providerModelHelp", "customModelField", "providerCustomModel", "providerApiKey", "toggleApiKey",
      "apiKeyHelp", "providerConnectionState", "testProvider", "saveProvider", "syncDialog", "closeSync",
      "syncStatusCard", "syncToken", "openSyncSite", "connectSync", "pullSync", "pushSync", "disconnectSync",
      "evidenceDrawer", "evidenceDrawerBody", "chartTooltip", "toastStack"
      , "navHome", "navData", "navReport", "inspectorToggle", "inspectorSummaryContent",
      "inspectorEvidenceContent", "inspectorAiContent", "desktopFileStatus", "desktopRowStatus",
      "desktopComputeStatus", "desktopAiStatus", "desktopSyncStatus", "projectNameLabel", "sidebarSourceCount"
      , "downloadPdf", "pdfExportDialog", "pdfExportForm", "closePdfExport", "cancelPdfExport",
      "confirmPdfExport", "pdfExportSummary"
    ].forEach((id) => { el[id] = document.getElementById(id); });
  }

  function updateDesktopImportCopy() {
    if (!IS_DESKTOP) return;
    const hasCurrentProject = savedDesktopSourceCount() > 0;
    const label = hasCurrentProject ? "追加数据" : "导入数据";
    const text = el.importBtn?.querySelector("span");
    if (text) text.textContent = label;
    if (el.importBtn) {
      el.importBtn.title = hasCurrentProject ? "追加 Excel / CSV（保留现有数据）" : "导入 Excel / CSV";
      el.importBtn.setAttribute("aria-label", el.importBtn.title);
    }
    if (el.dropZone) {
      el.dropZone.setAttribute("aria-label", hasCurrentProject
        ? "追加一个或多个 Excel 和 CSV 文件，现有数据不会被删除"
        : "选择或拖放一个或多个 Excel 和 CSV 文件");
    }
  }

  function savedDesktopSourceCount() {
    return state.persistedSources.length;
  }

  function ensureDesktopImportDialog() {
    if (!IS_DESKTOP || document.getElementById("desktopImportDialog")) return;
    const dialog = document.createElement("dialog");
    dialog.id = "desktopImportDialog";
    dialog.className = "modal desktop-import-dialog";
    dialog.setAttribute("aria-labelledby", "desktopImportTitle");
    dialog.innerHTML = `<form method="dialog" class="modal-surface desktop-import-surface">
      <header class="desktop-import-header"><div><span class="page-kicker">LOCAL FILE BATCH</span><h2 id="desktopImportTitle">确认导入文件</h2><p data-import-subtitle>所选文件会进入同一个经营分析项目。</p></div><button class="icon-button" type="submit" value="cancel" aria-label="取消导入"><svg><use href="#i-close"></use></svg></button></header>
      <section class="desktop-import-intent" data-import-intent></section>
      <ul class="desktop-import-file-list" data-import-files></ul>
      <footer class="desktop-import-actions"><span data-import-total></span><div><button class="button button-secondary" type="submit" value="cancel">取消</button><button class="button button-primary" type="submit" value="confirm" data-import-confirm>追加并分析</button></div></footer>
    </form>`;
    dialog.addEventListener("click", (event) => {
      if (event.target === dialog) dialog.close("cancel");
    });
    document.body.appendChild(dialog);
  }

  function confirmDesktopImportSelection(files, options = {}) {
    const dialog = document.getElementById("desktopImportDialog");
    if (!dialog) return Promise.resolve(true);
    const existingCount = savedDesktopSourceCount();
    const replacing = options.replace === true && existingCount > 0;
    const appending = !replacing && existingCount > 0;
    const action = replacing ? "新建并替换" : appending ? "追加并分析" : "导入并分析";
    dialog.querySelector("[data-import-subtitle]").textContent = replacing
      ? "这是显式的新建项目操作；只有确认后才会替换当前本地项目。"
      : appending
        ? `本次选择 ${files.length} 份文件；当前 ${existingCount} 份文件会完整保留，内容完全相同的文件将自动跳过。`
        : `本次选择 ${files.length} 份文件；系统会合并分析，并自动跳过批次内内容完全相同的文件。`;
    const intent = dialog.querySelector("[data-import-intent]");
    intent.className = `desktop-import-intent ${replacing ? "replace" : "append"}`;
    intent.innerHTML = `<span><svg><use href="#${replacing ? "i-alert" : "i-check"}"></use></svg></span><div><strong>${replacing ? "替换当前项目" : appending ? "追加到当前项目" : "创建当前项目"}</strong><p>${replacing ? `现有 ${existingCount} 份数据源会在新项目保存成功后被替换。` : appending ? "旧文件、已确认字段口径和本地设置不会被清空。" : "所有选中文件会一次提交，按日期字段和期间信息建立月份关系。"}</p></div>`;
    dialog.querySelector("[data-import-files]").innerHTML = files.map((file, index) => `<li><span class="file-type">${esc((file.name.split(".").pop() || "DATA").toUpperCase())}</span><div><strong title="${attr(file.name)}">${esc(file.name)}</strong><small>${esc(formatBytes(file.size))}</small></div><b>${String(index + 1).padStart(2, "0")}</b></li>`).join("");
    dialog.querySelector("[data-import-total]").textContent = `${files.length} 份 · ${formatBytes(files.reduce((sum, file) => sum + file.size, 0))}`;
    const confirm = dialog.querySelector("[data-import-confirm]");
    confirm.textContent = action;
    confirm.classList.toggle("danger", replacing);
    dialog.returnValue = "cancel";
    showModal(dialog);
    return new Promise((resolve) => dialog.addEventListener("close", () => resolve(dialog.returnValue === "confirm"), { once: true }));
  }

  async function requestFileImport(options = {}) {
    if (state.loading || desktopPickerBusy) return;
    const desktop = window.dataMasterDesktop;
    if (!IS_DESKTOP || !desktop?.openFiles || !desktop?.uploadSelectedFiles) {
      pendingFileInputOptions = options;
      el.fileInput.click();
      return;
    }
    desktopPickerBusy = true;
    try {
      const selection = await desktop.openFiles({ kind: "data", multiple: true });
      const descriptors = Array.isArray(selection?.files) ? selection.files : [];
      if (selection?.canceled) return;
      const validation = validateFiles(descriptors);
      if (!validation.ok) {
        setStatus("error", validation.message);
        toast("无法导入", validation.message, "error");
        return;
      }
      if (!(await confirmDesktopImportSelection(descriptors, options))) return;
      await uploadDesktopSelection(descriptors, { ...options, replaceConfirmed: options.replace === true });
    } catch (error) {
      setLoading(false);
      setStatus("error", `导入文件失败：${friendlyError(error)}`);
      toast("导入文件失败", friendlyError(error), "error");
    } finally {
      desktopPickerBusy = false;
    }
  }

  async function confirmAndUploadDroppedFiles(files) {
    if (!files.length) return;
    if (!IS_DESKTOP) {
      await uploadFiles(files);
      return;
    }
    const validation = validateFiles(files);
    if (!validation.ok) {
      setStatus("error", validation.message);
      toast("无法导入", validation.message, "error");
      return;
    }
    if (await confirmDesktopImportSelection(files)) await uploadFiles(files);
  }

  function bindEvents() {
    el.importBtn.addEventListener("click", () => requestFileImport());
    el.dropZone.addEventListener("click", () => requestFileImport());
    el.dropZone.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        requestFileImport();
      }
    });
    el.fileInput.addEventListener("change", () => {
      const options = pendingFileInputOptions;
      pendingFileInputOptions = {};
      if (el.fileInput.files.length) uploadFiles(Array.from(el.fileInput.files), options);
      el.fileInput.value = "";
    });
    ["dragenter", "dragover"].forEach((name) => el.dropZone.addEventListener(name, (event) => {
      event.preventDefault();
      if (!state.loading) el.dropZone.classList.add("dragover");
    }));
    ["dragleave", "drop"].forEach((name) => el.dropZone.addEventListener(name, (event) => {
      event.preventDefault();
      el.dropZone.classList.remove("dragover");
    }));
    el.dropZone.addEventListener("drop", (event) => {
      const files = Array.from(event.dataTransfer?.files || []);
      if (files.length) void confirmAndUploadDroppedFiles(files);
    });
    el.clearDataBtn?.addEventListener("click", resetAnalysis);
    el.downloadXlsx.addEventListener("click", () => downloadResult("export.xlsx"));
    el.downloadDocx.addEventListener("click", () => downloadResult("report.docx"));
    el.downloadPdf?.addEventListener("click", openPdfExport);

    document.querySelector(".view-tabs")?.addEventListener("click", (event) => {
      const tab = event.target.closest("[data-view]");
      if (tab) switchView(tab.dataset.view, { focus: false });
    });
    document.querySelector(".view-tabs")?.addEventListener("keydown", handleTabKeyboard);

    document.querySelector(".primary-navigation")?.addEventListener("click", (event) => {
      const target = event.target.closest("[data-workspace]");
      if (target) {
        event.preventDefault();
        switchWorkspace(target.dataset.workspace, { focus: false });
      }
    });

    el.inspectorToggle?.addEventListener("click", toggleInspector);
    document.querySelector("[data-toggle-inspector]")?.addEventListener("click", toggleInspector);
    document.querySelector(".desktop-inspector")?.addEventListener("change", (event) => {
      if (event.target.matches("[name='inspector-tab']")) {
        state.inspectorTab = event.target.value;
        renderInspector();
      }
      if (event.target.matches("[data-insight-provider]")) {
        state.activeProviderId = event.target.value;
        renderInspector();
        renderDesktopStatus();
      }
    });
    document.querySelector(".desktop-inspector")?.addEventListener("click", (event) => {
      if (event.target.closest("[data-action='generate-insights']")) generateInsights();
      if (event.target.closest("[data-action='open-settings']")) openSettings();
      if (event.target.closest("[data-action='open-sync']")) openSync();
      if (event.target.closest("[data-action='clear-chat']")) clearAiChat();
      const prompt = event.target.closest("[data-chat-prompt]");
      if (prompt) sendAiChat(prompt.dataset.chatPrompt);
      const suggestion = event.target.closest("[data-ai-suggestion]");
      if (suggestion) applyAiSuggestion(Number(suggestion.dataset.aiSuggestion));
    });
    document.querySelector(".desktop-inspector")?.addEventListener("submit", (event) => {
      if (!event.target.matches("[data-ai-chat-form]")) return;
      event.preventDefault();
      const input = event.target.querySelector("[data-ai-chat-input]");
      sendAiChat(input?.value || "");
    });

    el.viewContent.addEventListener("click", (event) => {
      if (event.target.closest("[data-action='generate-insights']")) generateInsights();
      if (event.target.closest("[data-action='import-data']")) requestFileImport();
      if (event.target.closest("[data-action='retry-workspace']")) retryWorkspaceRestore();
      if (event.target.closest("[data-action='open-settings']")) openSettings();
      if (event.target.closest("[data-action='open-sync']")) openSync();
      if (event.target.closest("[data-action='export-xlsx']")) downloadResult("export.xlsx");
      if (event.target.closest("[data-action='export-docx']")) downloadResult("report.docx");
      if (event.target.closest("[data-action='export-pdf']")) openPdfExport();
      if (event.target.closest("[data-action='confirm-mapping']")) confirmFieldMapping();
      if (event.target.closest("[data-action='suggest-mapping-ai']")) suggestFieldMappingWithAi();
      if (event.target.closest("[data-action='reselect-for-mapping']")) requestFileImport({ preserveMapping: true });
      const periodMode = event.target.closest("[data-period-mode]");
      if (periodMode && IS_DESKTOP) {
        state.periodControls.mode = periodMode.dataset.periodMode;
        applyPeriodControlChange();
      }
      const clearPeriod = event.target.closest("[data-clear-period]");
      if (clearPeriod && IS_DESKTOP) {
        state.periodControls.years = [];
        state.periodControls.months = [];
        state.periodControls.searches = {};
        applyPeriodControlChange();
      }
      const clearFilter = event.target.closest("[data-clear-explorer-filter]");
      if (clearFilter && IS_DESKTOP) {
        const scope = clearFilter.dataset.explorerScope;
        const dimension = clearFilter.dataset.clearExplorerFilter;
        const controls = scope === "product" ? state.productControls : scope === "customer" ? state.customerControls : state.salesGroupControls;
        controls.filters[dimension] = [];
        if (scope === "product") controls.page = 0;
        if (scope === "customer") {
          controls.selectedCustomer = "";
          controls.selectedCustomers = [];
          controls.customerDrillInitialized = false;
          if (dimension === DIMENSIONS.channel) controls.selectedChannel = "";
          controls.lossPage = 0;
        }
        if (scope === "salesGroup" && dimension === DIMENSIONS.salesGroup) controls.selectedGroup = "";
        invalidateExplorerScope(scope);
        renderActiveViewPreservingInteraction();
      }
      const expandableSummary = event.target.closest(".filter-multiselect > summary, .period-multiselect > summary");
      if (expandableSummary && IS_DESKTOP) {
        window.setTimeout(() => {
          const details = expandableSummary.parentElement;
          const key = details?.dataset.filterKey || "";
          if (details?.classList.contains("period-multiselect")) state.periodControls.openKey = details.open ? key : "";
          else state.openFilterKey = details?.open ? key : "";
        }, 0);
      }
      const removeSourceTarget = event.target.closest("[data-remove-source]");
      if (removeSourceTarget) removeSource(Number(removeSourceTarget.dataset.removeSource));
      const persistedSourceTarget = event.target.closest("[data-remove-persisted-source]");
      if (persistedSourceTarget) removePersistedSource(persistedSourceTarget.dataset.removePersistedSource);
      const viewTarget = event.target.closest("[data-open-view]");
      if (viewTarget) switchView(viewTarget.dataset.openView, { focus: false });
      const breakdownTarget = event.target.closest("[data-breakdown-id]");
      if (breakdownTarget) {
        state.activeBreakdownId = breakdownTarget.dataset.breakdownId;
        state.dynamicControls.page = 0;
        state.dynamicControls.search = "";
        state.dynamicControls.drill = null;
        invalidateExplorer("dynamicBreakdown");
        renderActiveView();
        el.viewContent.querySelector(`[data-breakdown-id="${CSS.escape(state.activeBreakdownId)}"]`)?.focus();
      }
      const drillTarget = event.target.closest("[data-drill-dimension]");
      if (drillTarget) {
        const parent = drillTarget.dataset.drillDimension;
        const child = drillChildFor(parent);
        if (child) {
          const parentButton = document.querySelector(`.dimension-switcher [data-breakdown-id="${CSS.escape(parent)}"]`);
          state.dynamicControls.drill = {
            parent,
            parentLabel: parentButton?.dataset.dimensionLabel || parent,
            value: drillTarget.dataset.drillValue || ""
          };
          state.activeBreakdownId = child;
          state.dynamicControls.page = 0;
          state.dynamicControls.search = "";
          invalidateExplorer("dynamicBreakdown");
          renderActiveView();
        }
      }
      const clearDrillTarget = event.target.closest("[data-clear-drill]");
      if (clearDrillTarget) {
        state.dynamicControls.drill = null;
        state.dynamicControls.page = 0;
        invalidateExplorer("dynamicBreakdown");
        renderActiveView();
      }
      const dynamicPage = event.target.closest("[data-dynamic-page]");
      if (dynamicPage) {
        state.dynamicControls.page = Math.max(0, Number(dynamicPage.dataset.dynamicPage) || 0);
        invalidateExplorer("dynamicBreakdown");
        renderActiveView();
        window.setTimeout(() => el.viewContent.querySelector(".dimension-canvas")?.scrollIntoView({ block: "nearest" }), 0);
      }
      const lossPage = event.target.closest("[data-loss-page]");
      if (lossPage) {
        state.customerControls.lossPage = Math.max(0, Number(lossPage.dataset.lossPage) || 0);
        invalidateExplorer("losingCustomers");
        renderActiveView();
      }
      const reportSection = event.target.closest("[data-report-section]");
      if (reportSection) {
        state.reportSection = reportSection.dataset.reportSection;
        renderActiveView();
        window.setTimeout(() => el.viewContent.querySelector(".report-paper")?.focus(), 0);
      }
      const productMetric = event.target.closest("[data-product-metric]");
      if (productMetric) {
        state.productControls.metric = productMetric.dataset.productMetric;
        state.productControls.page = 0;
        invalidateExplorer("productDetail");
        renderActiveView();
      }
      const productPage = event.target.closest("[data-product-page]");
      if (productPage) {
        state.productControls.page = Math.max(0, Number(productPage.dataset.productPage) || 0);
        invalidateExplorer("productDetail");
        renderActiveView();
        window.setTimeout(() => el.viewContent.querySelector(".detail-explorer")?.scrollIntoView({ block: "start" }), 0);
      }
      const customer = event.target.closest("[data-select-customer]");
      if (customer) {
        const selectedCustomer = customer.dataset.selectCustomer;
        state.customerControls.selectedCustomer = selectedCustomer;
        state.customerControls.selectedCustomers = selectedCustomer ? [selectedCustomer] : [];
        state.customerControls.customerDrillInitialized = true;
        invalidateExplorer("customerProducts");
        renderActiveView();
      }
      const removeDrillCustomer = event.target.closest("[data-remove-customer-drill]");
      if (removeDrillCustomer) {
        const selected = state.customerControls.selectedCustomers || [];
        state.customerControls.selectedCustomers = selected.filter((value) => value !== removeDrillCustomer.dataset.removeCustomerDrill);
        state.customerControls.selectedCustomer = state.customerControls.selectedCustomers[0] || "";
        state.customerControls.customerDrillInitialized = true;
        invalidateExplorer("customerProducts");
        renderActiveViewPreservingInteraction();
      }
      const clearDrillCustomers = event.target.closest("[data-clear-customer-drill]");
      if (clearDrillCustomers) {
        state.customerControls.selectedCustomer = "";
        state.customerControls.selectedCustomers = [];
        state.customerControls.customerDrillInitialized = true;
        invalidateExplorer("customerProducts");
        renderActiveViewPreservingInteraction();
      }
      const channel = event.target.closest("[data-select-channel]");
      if (channel) {
        state.customerControls.selectedChannel = channel.dataset.selectChannel;
        invalidateExplorer("channelCustomers");
        renderActiveView();
      }
      const groupBy = event.target.closest("[data-sales-group-by]");
      if (groupBy) {
        state.salesGroupControls.groupBy = groupBy.dataset.salesGroupBy;
        state.salesGroupControls.selectedGroup = "";
        state.salesGroupControls.drillCustomers = [];
        invalidateExplorer("salesGroupSummary");
        renderActiveView();
      }
      const salesGroup = event.target.closest("[data-select-sales-group]");
      if (salesGroup) {
        state.salesGroupControls.selectedGroup = salesGroup.dataset.selectSalesGroup;
        state.salesGroupControls.drillCustomers = [];
        invalidateExplorer("salesGroupCustomers");
        invalidateExplorer("salesGroupProducts");
        renderActiveView();
      }
      const clearSgDrillCustomer = event.target.closest("[data-clear-sg-drill-customer]");
      if (clearSgDrillCustomer) {
        state.salesGroupControls.drillCustomers = [];
        invalidateExplorer("salesGroupProducts");
        renderActiveViewPreservingInteraction();
      }
      const retry = event.target.closest("[data-retry-explorer]");
      if (retry) {
        if (workspaceRecoveryError && state.analysis?.id) {
          recoverWorkspaceAfterSessionExpiry(state.analysis.id);
          return;
        }
        invalidateExplorer(retry.dataset.retryExplorer);
        renderActiveView();
      }
    });
    el.viewContent.addEventListener("change", (event) => {
      if (event.target.matches("[data-insight-provider]")) state.activeProviderId = event.target.value;
      if (IS_DESKTOP && event.target.matches("[data-period-year], [data-period-month]")) {
        const field = event.target.matches("[data-period-year]") ? "years" : "months";
        state.periodControls[field] = toggleSelectedValue(state.periodControls[field], event.target.value, event.target.checked);
        applyPeriodControlChange({ preserveInteraction: true });
        return;
      }
      if (IS_DESKTOP && event.target.matches("[data-explorer-multi]")) {
        const scope = event.target.dataset.explorerScope;
        const dimension = event.target.dataset.explorerMulti;
        const controls = scope === "product" ? state.productControls : scope === "customer" ? state.customerControls : state.salesGroupControls;
        controls.filters[dimension] = toggleSelectedValue(controls.filters[dimension], event.target.value, event.target.checked);
        if (scope === "product") controls.page = 0;
        if (scope === "customer") {
          controls.selectedCustomer = "";
          controls.selectedCustomers = [];
          controls.customerDrillInitialized = false;
          controls.selectedChannel = dimension === DIMENSIONS.channel && controls.filters[dimension].length === 1 ? controls.filters[dimension][0] : "";
          controls.lossPage = 0;
        }
        if (scope === "salesGroup") controls.selectedGroup = dimension === DIMENSIONS.salesGroup && controls.filters[dimension].length === 1 ? controls.filters[dimension][0] : "";
        invalidateExplorerScope(scope);
        ensureActiveExplorers();
        updateOpenFilterSummary(event.target.closest(".filter-multiselect"), controls.filters[dimension]);
        return;
      }
      if (IS_DESKTOP && event.target.matches("[data-customer-drill-multi]")) {
        const controls = state.customerControls;
        if (event.target.checked && !(controls.selectedCustomers || []).includes(event.target.value) && (controls.selectedCustomers || []).length >= 200) {
          event.target.checked = false;
          toast("选择数量过多", "客户产品利润一次最多合并 200 位客户，请先取消部分选择。", "error");
          return;
        }
        controls.selectedCustomers = toggleSelectedValue(controls.selectedCustomers, event.target.value, event.target.checked);
        controls.selectedCustomer = controls.selectedCustomers[0] || "";
        controls.customerDrillInitialized = true;
        invalidateExplorer("customerProducts");
        renderActiveViewPreservingInteraction();
        return;
      }
      if (IS_DESKTOP && event.target.matches("[data-sg-drill-customer]")) {
        const controls = state.salesGroupControls;
        controls.drillCustomers = toggleSelectedValue(controls.drillCustomers || [], event.target.value, event.target.checked);
        invalidateExplorer("salesGroupProducts");
        renderActiveViewPreservingInteraction();
        return;
      }
      if (event.target.matches("[data-mapping-field]")) {
        state.mappingDraft[event.target.dataset.mappingField] = event.target.value;
        updateMappingActionState();
      }
      if (event.target.matches("[data-explorer-filter]")) {
        const scope = event.target.dataset.explorerScope;
        const controls = scope === "product" ? state.productControls : scope === "customer" ? state.customerControls : state.salesGroupControls;
        controls.filters[event.target.dataset.explorerFilter] = event.target.value;
        if (scope === "product") controls.page = 0;
        if (scope === "customer") {
          controls.selectedCustomer = "";
          controls.selectedCustomers = [];
          controls.customerDrillInitialized = false;
          controls.selectedChannel = event.target.dataset.explorerFilter === DIMENSIONS.channel ? event.target.value : "";
          controls.lossPage = 0;
        }
        if (scope === "salesGroup") {
          controls.selectedGroup = event.target.dataset.explorerFilter === DIMENSIONS.salesGroup ? event.target.value : "";
        }
        invalidateExplorerScope(scope);
        renderActiveView();
      }
      if (event.target.matches("[data-quantity-metric]")) {
        state.productControls.quantityMetric = event.target.value;
        state.productControls.page = 0;
        invalidateExplorer("productDetail");
        renderActiveView();
      }
      if (event.target.matches("[data-customer-drill]")) {
        state.customerControls.selectedCustomer = event.target.value;
        state.customerControls.selectedCustomers = event.target.value ? [event.target.value] : [];
        state.customerControls.customerDrillInitialized = true;
        invalidateExplorer("customerProducts");
        renderActiveView();
      }
      if (event.target.matches("[data-cost-metric]")) {
        state.salesGroupControls.costMetric = event.target.value;
        invalidateExplorerScope("salesGroup");
        renderActiveView();
      }
    });
    el.viewContent.addEventListener("input", (event) => {
      const input = event.target.closest("[data-profit-search]");
      if (input) {
        if (!event.isComposing && !searchCompositionActive) scheduleProfitSearch(input.dataset.profitSearch, input.value, input);
        return;
      }
      const dynamicSearch = event.target.closest("[data-dynamic-search]");
      if (dynamicSearch) {
        if (!event.isComposing && !searchCompositionActive) scheduleProfitSearch("dynamic", dynamicSearch.value, dynamicSearch);
        return;
      }
      const dimensionFilter = event.target.closest("[data-dimension-filter]");
      if (dimensionFilter) {
        state.dynamicControls.dimensionFilter = String(dimensionFilter.value || "").slice(0, 80);
        filterDimensionButtons(state.dynamicControls.dimensionFilter);
        return;
      }
      const optionSearch = event.target.closest("[data-filter-option-search]");
      if (optionSearch && IS_DESKTOP) {
        const key = optionSearch.dataset.filterOptionSearch;
        state.filterSearches[key] = String(optionSearch.value || "").slice(0, 80);
        filterMultiselectOptions(optionSearch.closest("details"), optionSearch.value);
        return;
      }
      const periodSearch = event.target.closest("[data-period-option-search]");
      if (periodSearch && IS_DESKTOP) {
        const key = periodSearch.dataset.periodOptionSearch;
        state.periodControls.searches[key] = String(periodSearch.value || "").slice(0, 40);
        filterMultiselectOptions(periodSearch.closest("details"), periodSearch.value);
      }
    });
    document.addEventListener("pointerover", (event) => {
      const badge = event.target.closest?.(".spec-badge");
      if (badge) showSpecTooltip(badge);
    });
    document.addEventListener("pointerout", (event) => {
      if (event.target.closest?.(".spec-badge")) hideSpecTooltip();
    });
    document.addEventListener("scroll", (event) => {
      if (specTooltipEl && event.target !== specTooltipEl) hideSpecTooltip();
    }, true);
    document.addEventListener("pointerdown", () => hideSpecTooltip());
    el.viewContent.addEventListener("compositionstart", (event) => {
      if (!event.target.matches("[data-profit-search], [data-dynamic-search], [data-dimension-filter]")) return;
      searchCompositionActive = true;
      window.clearTimeout(explorerRenderTimer);
    });
    el.viewContent.addEventListener("compositionend", (event) => {
      if (!event.target.matches("[data-profit-search], [data-dynamic-search], [data-dimension-filter]")) return;
      searchCompositionActive = false;
      if (event.target.matches("[data-dimension-filter]")) {
        state.dynamicControls.dimensionFilter = String(event.target.value || "").slice(0, 80);
        filterDimensionButtons(state.dynamicControls.dimensionFilter);
        return;
      }
      const scope = event.target.dataset.profitSearch || "dynamic";
      scheduleProfitSearch(scope, event.target.value, event.target);
    });
    ["dragenter", "dragover"].forEach((name) => el.viewContent.addEventListener(name, (event) => {
      const target = event.target.closest?.(".data-drop-card, .desktop-empty-state");
      if (!target) return;
      event.preventDefault();
      target.classList.add("dragover");
    }));
    ["dragleave", "drop"].forEach((name) => el.viewContent.addEventListener(name, (event) => {
      const target = event.target.closest?.(".data-drop-card, .desktop-empty-state");
      if (!target) return;
      event.preventDefault();
      target.classList.remove("dragover");
      if (name === "drop") {
        const files = Array.from(event.dataTransfer?.files || []);
        if (files.length) void confirmAndUploadDroppedFiles(files);
      }
    }));

    el.openSettings.addEventListener("click", openSettings);
    el.closeSettings.addEventListener("click", closeSettings);
    el.settingsDialog.addEventListener("click", (event) => {
      if (event.target === el.settingsDialog) closeSettings();
    });
    el.providerChoices.addEventListener("click", (event) => {
      const choice = event.target.closest("[data-provider-id]");
      if (choice) selectSettingsProvider(choice.dataset.providerId);
    });
    el.providerModel.addEventListener("change", () => {
      toggleCustomModelField();
      updateModelHelp();
    });
    el.toggleApiKey.addEventListener("click", toggleApiKeyVisibility);
    el.testProvider.addEventListener("click", testSelectedProvider);
    el.saveProvider.addEventListener("click", () => saveSelectedProvider({ notify: true }));

    el.openSync.addEventListener("click", openSync);
    el.closeSync.addEventListener("click", closeSync);
    el.syncDialog.addEventListener("click", (event) => {
      if (event.target === el.syncDialog) closeSync();
    });
    el.connectSync.addEventListener("click", connectSync);
    el.pullSync.addEventListener("click", () => runSyncAction("pull"));
    el.pushSync.addEventListener("click", () => runSyncAction("push"));
    el.disconnectSync.addEventListener("click", disconnectSync);

    el.closePdfExport?.addEventListener("click", closePdfExport);
    el.cancelPdfExport?.addEventListener("click", closePdfExport);
    el.pdfExportDialog?.addEventListener("click", (event) => {
      if (event.target === el.pdfExportDialog) closePdfExport();
      const preset = event.target.closest("[data-pdf-preset]");
      if (preset) applyPdfPreset(preset.dataset.pdfPreset);
    });
    el.pdfExportForm?.addEventListener("change", (event) => {
      if (event.target.matches("[name='pdfScope']")) {
        el.pdfExportDialog?.querySelectorAll("[data-pdf-preset]").forEach((button) => button.classList.remove("active"));
        updatePdfExportSummary();
      }
    });
    el.pdfExportForm?.addEventListener("submit", (event) => {
      event.preventDefault();
      exportPdf();
    });

    document.addEventListener("click", (event) => {
      const evidence = event.target.closest("[data-evidence]");
      if (evidence) {
        event.stopPropagation();
        openEvidence(evidence.dataset.evidence.split(",").filter(Boolean), evidence);
      }
      if (event.target.closest("[data-close-evidence]")) closeEvidence();
    });
    document.addEventListener("keydown", handleGlobalKeyboard);
    bindDesktopBridge();
  }

  function bindDesktopBridge() {
    const desktop = window.dataMasterDesktop;
    if (!desktop?.onMenuCommand) return;
    desktop.onMenuCommand((command) => {
      if (command === "new-analysis") requestFileImport({ replace: true });
      if (command === "import-data") requestFileImport();
      if (command === "export-excel") downloadResult("export.xlsx");
      if (command === "export-word") downloadResult("report.docx");
      if (command === "export-pdf") openPdfExport();
      if (command === "open-settings") openSettings();
      if (command === "show-shortcuts") toast("常用快捷键", "⌘/Ctrl+O 导入 · 1–5 切换分析 · Shift+A AI 助手 · , 设置", "success");
      if (command === "toggle-ai-assistant") {
        state.inspectorTab = "ai";
        setInspectorCollapsed(false);
        selectInspectorTab("ai");
        renderInspector();
      }
      if (command?.startsWith("navigate:")) switchView(command.slice(9), { focus: false });
    });
  }

  function handleTabKeyboard(event) {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    const allTabs = Array.from(document.querySelectorAll(".view-tabs [data-view]"));
    const tabs = allTabs.filter((tab) => !tab.disabled && tab.getAttribute("aria-disabled") !== "true");
    const current = tabs.indexOf(document.activeElement);
    if (current < 0) return;
    event.preventDefault();
    let next = current;
    if (event.key === "ArrowLeft") next = (current - 1 + tabs.length) % tabs.length;
    if (event.key === "ArrowRight") next = (current + 1) % tabs.length;
    if (event.key === "Home") next = 0;
    if (event.key === "End") next = tabs.length - 1;
    switchView(tabs[next].dataset.view, { focus: true });
  }

  function handleGlobalKeyboard(event) {
    const interactiveAction = event.target.closest?.("[data-action]");
    if (interactiveAction && !interactiveAction.matches("button, a, input, select, textarea") && (event.key === "Enter" || event.key === " ")) {
      event.preventDefault();
      interactiveAction.click();
      return;
    }
    const evidence = event.target.closest?.("[data-evidence]");
    if (evidence && !evidence.matches("button") && (event.key === "Enter" || event.key === " ")) {
      event.preventDefault();
      openEvidence(evidence.dataset.evidence.split(",").filter(Boolean), evidence);
      return;
    }
    if (event.key === "Escape" && !el.evidenceDrawer.hidden) {
      event.preventDefault();
      closeEvidence();
      return;
    }
    if (event.key === "Tab" && !el.evidenceDrawer.hidden) trapDrawerFocus(event);
    const editable = event.target.matches?.("input, textarea, select, [contenteditable='true']");
    const commandKey = event.metaKey || event.ctrlKey;
    if (!commandKey || editable) return;
    if (event.key.toLowerCase() === "o") {
      event.preventDefault();
      requestFileImport();
    }
    if (/^[1-5]$/.test(event.key)) {
      event.preventDefault();
      switchView(["overview", "product", "customer", "profit", "salesGroup"][Number(event.key) - 1], { focus: false });
    }
    if (event.key === ",") {
      event.preventDefault();
      openSettings();
    }
    if (event.shiftKey && event.key.toLowerCase() === "a") {
      event.preventDefault();
      state.inspectorTab = "ai";
      setInspectorCollapsed(false);
      renderInspector();
    }
    if (event.shiftKey && event.key.toLowerCase() === "e") {
      event.preventDefault();
      switchWorkspace("report", { focus: false });
    }
  }

  async function apiFetch(path, options = {}, timeoutMs = 30000) {
    const controller = new AbortController();
    const externalSignal = options.signal;
    const abortFromExternalSignal = () => controller.abort();
    if (externalSignal?.aborted) controller.abort();
    else externalSignal?.addEventListener?.("abort", abortFromExternalSignal, { once: true });
    const timer = window.setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(`${API_BASE}${path}`, { ...options, signal: controller.signal });
      if (!response.ok) {
        let message = `请求失败（${response.status}）`;
        try {
          const payload = await response.json();
          message = payload.message || payload.error || message;
        } catch (_) {
          const text = await response.text();
          if (text) message = text.slice(0, 180);
        }
        const error = new Error(message);
        error.status = response.status;
        throw error;
      }
      if (response.status === 204) return null;
      const contentType = response.headers.get("content-type") || "";
      return contentType.includes("json") ? response.json() : response.text();
    } finally {
      window.clearTimeout(timer);
      externalSignal?.removeEventListener?.("abort", abortFromExternalSignal);
    }
  }

  async function resetAnalysis(options = {}) {
    const skipConfirm = Boolean(options?.skipConfirm);
    if (!skipConfirm && state.analysis && !window.confirm("清空当前分析数据并返回空白项目？AI 配置与账户设置会保留。")) return;
    const previousAnalysisId = state.analysis?.id;
    if (previousAnalysisId) {
      setLoading(true, "正在清空本地工作区…");
      try {
        await apiFetch("/api/workspace/current", { method: "DELETE" }, 30000);
      } catch (error) {
        if (error.status !== 404) {
          setLoading(false);
          toast("清空失败", friendlyError(error), "error");
          return;
        }
      }
    }
    state.analysis = null;
    state.sourceNames = [];
    state.persistedSources = [];
    state.lastUploadFiles = null;
    state.mappingDraft = {};
    state.appliedMapping = {};
    state.schemaMessage = "";
    state.forceMappingReview = false;
    invalidateAiRequests();
    state.activeBreakdownId = "";
    resetExplorerState();
    state.aiChats.clear();
    state.reportSection = "overview";
    state.deepInsights = [];
    state.deepInsightMeta = null;
    state.activeWorkspace = "home";
    state.activeView = "overview";
    state.inspectorTab = "summary";
    state.inspectorEvidenceIds = [];
    state.evidence.clear();
    setLoading(false);
    renderEmptyState();
    releaseAnalysis(previousAnalysisId);
    toast("已清空当前数据", "AI 配置和账户设置没有变化。", "success");
  }

  function invalidateAiRequests() {
    state.schemaRequestToken += 1;
    state.insightRequestToken += 1;
    state.chatRequestToken += 1;
    state.schemaLoading = false;
    state.insightsLoading = false;
  }

  function releaseAnalysis(id) {
    if (!id) return;
    apiFetch(`/api/analysis/${encodeURIComponent(id)}`, { method: "DELETE" }, 5000).catch(() => {
      // Session cleanup is best-effort; the backend also keeps a small bounded session cache.
    });
  }

  function removeSource(index) {
    if (!Number.isInteger(index) || index < 0 || !state.lastUploadFiles?.length) return;
    const target = state.lastUploadFiles[index];
    if (!target) return;
    if (!window.confirm(`从当前分析中移除“${target.name}”并重新计算？`)) return;
    const remaining = state.lastUploadFiles.filter((_, itemIndex) => itemIndex !== index);
    if (!remaining.length) {
      resetAnalysis({ skipConfirm: true });
      return;
    }
    uploadFiles(remaining, { preserveMapping: true });
  }

  async function removePersistedSource(sourceId) {
    const source = state.persistedSources.find((item) => item.id === String(sourceId));
    if (!source || state.loading) return;
    if (!window.confirm(`从当前工作区删除“${source.name}”并用剩余数据重新计算？`)) return;
    setLoading(true, `正在删除 ${source.name} 并重新计算…`);
    try {
      const result = await apiFetch(`/api/workspace/sources/${encodeURIComponent(source.id)}`, { method: "DELETE" }, 120000);
      if (!result?.analysis) {
        const remaining = state.persistedSources.filter((item) => item.id !== source.id);
        if (!remaining.length) {
          state.analysis = null;
          state.persistedSources = [];
          state.sourceNames = [];
          renderEmptyState();
          toast("数据源已删除", "当前工作区已无数据。", "success");
          return;
        }
        throw new Error("服务未返回重新计算后的分析结果。");
      }
      applyWorkspacePayload(result);
      toast("数据源已删除", `已使用剩余 ${state.persistedSources.length} 份数据重新计算。`, "success");
    } catch (error) {
      toast("删除数据源失败", friendlyError(error), "error");
    } finally {
      setLoading(false);
    }
  }

  function renderEmptyState() {
    state.analysis = null;
    state.persistedSources = [];
    state.sourceNames = [];
    updateDesktopImportCopy();
    document.body.classList.add("has-no-data");
    state.activeWorkspace = "home";
    el.dataModeLabel.textContent = "等待导入数据";
    el.dataStatusDot.className = "idle";
    el.generatedAt.textContent = "尚未创建分析";
    if (el.projectNameLabel) {
      el.projectNameLabel.textContent = "未创建项目";
      el.projectNameLabel.removeAttribute("title");
    }
    if (el.sidebarSourceCount) el.sidebarSourceCount.textContent = "0 个";
    el.pageTitle.textContent = "项目主页";
    el.pageSubtitle.textContent = "导入你的经营表格，开始建立本地分析项目。";
    el.downloadXlsx.disabled = true;
    el.downloadDocx.disabled = true;
    if (el.downloadPdf) el.downloadPdf.disabled = true;
    el.dropZone.hidden = true;
    if (el.clearDataBtn) el.clearDataBtn.disabled = true;
    document.querySelectorAll(".view-tabs [data-view]").forEach((tab) => {
      tab.setAttribute("aria-selected", "false");
      tab.setAttribute("aria-disabled", "true");
      tab.disabled = true;
      tab.tabIndex = -1;
    });
    document.querySelectorAll("[data-workspace]").forEach((item) => {
      const active = item.dataset.workspace === "home";
      item.classList.toggle("is-active", active);
      item.setAttribute("aria-current", active ? "page" : "false");
      if (item.dataset.workspace === "report") item.disabled = true;
    });
    el.viewContent.setAttribute("aria-labelledby", "nav-home");
    el.viewContent.setAttribute("aria-busy", "false");
    el.viewContent.innerHTML = `<div class="desktop-empty-state"><div class="empty-visual" aria-hidden="true"><span></span><i></i><b>＋</b></div><span class="eyebrow">EMPTY WORKSPACE</span><h2>开始一次新的经营分析</h2><p>当前没有业务数据。导入一个或多个 Excel / CSV，系统会在本机合并、校验并建立分析项目。</p><div class="empty-actions"><button class="button button-primary" type="button" data-action="import-data"><svg><use href="#i-upload"></use></svg><span>导入经营表格</span></button></div><div class="empty-promises"><span><svg><use href="#i-check"></use></svg>支持多文件合并</span><span><svg><use href="#i-check"></use></svg>数据默认只在本机处理</span><span><svg><use href="#i-check"></use></svg>清空不会删除 AI 配置</span></div></div>`;
    if (el.inspectorSummaryContent) el.inspectorSummaryContent.innerHTML = `<div class="inspector-empty"><span class="eyebrow">当前项目</span><h3>尚未导入数据</h3><p>导入后，这里会显示当前选择的指标摘要、风险和快捷操作。</p></div>`;
    if (el.inspectorEvidenceContent) el.inspectorEvidenceContent.innerHTML = `<div class="inspector-empty"><strong>暂无计算证据</strong><p>完成分析后，点击指标或图表即可核对计算口径。</p></div>`;
    if (el.inspectorAiContent) el.inspectorAiContent.innerHTML = `<div class="inspector-empty"><span class="inspector-ai-mark"><svg><use href="#i-spark"></use></svg></span><strong>AI 等待分析上下文</strong><p>先导入数据，AI 才会接收经过确定性计算的经营摘要。</p><button class="button button-secondary" type="button" data-action="open-settings">提前配置模型</button></div>`;
    selectInspectorTab("summary");
    renderDesktopStatus();
  }

  function captureImportState() {
    return {
      analysisId: state.analysis?.id,
      files: state.lastUploadFiles,
      sourceNames: [...state.sourceNames],
      persistedSources: [...state.persistedSources],
      appliedMapping: { ...state.appliedMapping }
    };
  }

  function restoreImportState(previous) {
    state.lastUploadFiles = previous.files;
    state.sourceNames = previous.sourceNames;
    state.persistedSources = previous.persistedSources;
    state.appliedMapping = previous.appliedMapping;
  }

  async function applyImportedResult(result, context) {
    const analysisPayload = result?.analysis || result;
    if (!analysisPayload?.id) throw new Error("本地分析服务没有返回有效的分析结果");
    state.analysis = normalizeAnalysis(analysisPayload);
    state.persistedSources = normalizeWorkspaceSources(result?.sources || result?.workspace?.sources || []);
    if (!state.persistedSources.length) {
      try {
        const workspace = await apiFetch("/api/workspace/current", {}, 30000);
        state.persistedSources = normalizeWorkspaceSources(workspace?.sources || []);
      } catch (_) {
        // The analysis remains usable when persistence metadata is temporarily unavailable.
      }
    }
    state.sourceNames = state.persistedSources.length
      ? state.persistedSources.map((source) => source.name)
      : context.selectedNames;
    state.lastUploadFiles = context.retainFileObjects ? context.files : null;
    state.schemaMessage = "";
    state.forceMappingReview = false;
    state.deepInsights = [];
    state.deepInsightMeta = null;
    resetExplorerState();
    state.aiChats.clear();
    state.appliedMapping = context.requestedMapping
      ? compactMapping(context.requestedMapping)
      : compactMapping(result?.mapping || result?.fieldMapping || {});
    initializeMappingState(state.analysis, state.appliedMapping);
    document.body.classList.remove("has-no-data");
    state.activeView = "overview";
    state.activeWorkspace = "analysis";
    renderAll();
    if (context.previousAnalysisId && context.previousAnalysisId !== state.analysis.id) releaseAnalysis(context.previousAnalysisId);
    const awaitingMapping = mappingConfirmationRequired(state.analysis);
    const totalSources = state.persistedSources.length || state.analysis.sourceFileCount || state.sourceNames.length;
    const addedSources = context.appending ? Math.max(0, totalSources - numberValue(context.previousSourceCount, 0)) : context.fileCount;
    const skippedSources = context.appending ? Math.max(0, context.fileCount - addedSources) : 0;
    const appendStatus = addedSources > 0
      ? `已新增 ${addedSources} 份文件${skippedSources ? `，并跳过 ${skippedSources} 份重复文件` : ""}；当前项目共 ${totalSources} 份数据源、${formatInteger(state.analysis.rowCount)} 行数据。`
      : `所选 ${context.fileCount} 份文件均与当前项目内容重复，没有新增数据源；现有 ${totalSources} 份文件保持不变。`;
    const appendToast = addedSources > 0
      ? `原有数据已保留${skippedSources ? `，另有 ${skippedSources} 份重复文件未写入` : ""}；月份与业务维度已重新聚合。`
      : "系统按文件内容校验重复项，没有重复写入。";
    setStatus(awaitingMapping ? "error" : "success", awaitingMapping
      ? "数据结构已识别，但关键经营口径仍需确认；确认前不会生成利润结论。"
      : context.appending
        ? appendStatus
        : `已合并 ${context.fileCount} 个文件，共识别 ${formatInteger(state.analysis.rowCount)} 行数据。`, awaitingMapping ? 0 : 6000);
    toast(awaitingMapping ? "请确认字段口径" : context.appending ? addedSources > 0 ? "追加分析完成" : "已跳过重复文件" : "分析完成",
      awaitingMapping ? "检测到多个可能的收入、成本或利润字段。" : context.appending ? appendToast : "经营指标、风险和数据质量已更新。",
      awaitingMapping ? "error" : "success");
  }

  async function uploadDesktopSelection(files, options = {}) {
    if (state.loading) return;
    const existingWorkspaceCount = savedDesktopSourceCount();
    const appending = existingWorkspaceCount > 0 && options.replace !== true;
    const requestedMapping = options.mapping || (options.preserveMapping || appending ? state.appliedMapping : null);
    const previous = captureImportState();
    const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
    invalidateAiRequests();
    if (!appending) state.sourceNames = files.map((file) => file.name);
    setLoading(true, appending
      ? `正在把 ${files.length} 份文件（${formatBytes(totalBytes)}）追加到当前项目，请保持程序开启…`
      : `正在流式处理 ${files.length} 份文件（${formatBytes(totalBytes)}），请保持程序开启…`);
    const stopProgress = window.dataMasterDesktop.onUploadProgress?.((progress) => {
      if (!progress.totalBytes) return;
      const percent = Math.min(100, Math.round(progress.uploadedBytes / progress.totalBytes * 100));
      setStatus("loading", percent >= 100
        ? `${progress.totalFiles}/${progress.totalFiles} 文件传输完成，正在合并、校验并重算全部月份…`
        : `正在传输 ${progress.fileIndex}/${progress.totalFiles}：${progress.currentFile} · ${percent}%（${formatBytes(progress.uploadedBytes)} / ${formatBytes(progress.totalBytes)}）`);
    });
    try {
      const result = await window.dataMasterDesktop.uploadSelectedFiles(files.map((file) => file.token), {
        append: appending,
        mapping: requestedMapping ? compactMapping(requestedMapping) : undefined
      });
      await applyImportedResult(result, {
        appending,
        fileCount: files.length,
        selectedNames: files.map((file) => file.name),
        files: null,
        retainFileObjects: false,
        requestedMapping,
        previousAnalysisId: previous.analysisId,
        previousSourceCount: existingWorkspaceCount
      });
    } catch (error) {
      restoreImportState(previous);
      const detail = `本批次 ${files.length} 份文件均未写入：${friendlyError(error)}`;
      setStatus("error", detail, 0, () => requestFileImport(options));
      toast("追加分析失败", friendlyError(error), "error");
    } finally {
      if (typeof stopProgress === "function") stopProgress();
      setLoading(false);
    }
  }

  async function uploadFiles(files, options = {}) {
    const validation = validateFiles(files);
    if (!validation.ok) {
      setStatus("error", validation.message);
      toast("无法导入", validation.message, "error");
      return;
    }
    const existingWorkspaceCount = state.persistedSources.length;
    const desktopAppending = IS_DESKTOP && existingWorkspaceCount > 0 && options.replace !== true;
    const replacingProject = !IS_DESKTOP && existingWorkspaceCount > 0 && !options.mapping && !options.preserveMapping;
    if (replacingProject && !window.confirm(`本次导入会创建新的当前项目，并在保存成功后替换本机已有的 ${existingWorkspaceCount} 份数据源。若要合并多份报表，请在本次选择中一次选全。是否继续？`)) return;
    if (IS_DESKTOP && options.replace === true && existingWorkspaceCount > 0 && !options.replaceConfirmed
      && !window.confirm(`新建分析会在保存成功后替换本机已有的 ${existingWorkspaceCount} 份数据源。是否继续？`)) return;
    if (state.loading) return;
    invalidateAiRequests();
    const previous = captureImportState();
    state.lastUploadFiles = files;
    if (!desktopAppending) state.sourceNames = files.map((file) => file.name);
    const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
    const largeUpload = totalBytes > LARGE_UPLOAD_BYTES;
    setLoading(true, desktopAppending
      ? `正在把 ${files.length} 份文件追加到当前项目（${formatBytes(totalBytes)}）…`
      : largeUpload
        ? `正在以大文件模式处理 ${files.length} 个文件（${formatBytes(totalBytes)}），请保持程序开启…`
        : `正在合并 ${files.length} 个文件并校验字段…`);
    const formData = new FormData();
    files.forEach((file) => formData.append("files", file, file.name));
    const requestedMapping = options.mapping || (options.preserveMapping || desktopAppending ? state.appliedMapping : null);
    if (requestedMapping && Object.values(requestedMapping).some(Boolean)) formData.append("mapping", JSON.stringify(compactMapping(requestedMapping)));
    try {
      const endpoint = desktopAppending ? "/api/workspace/sources" : "/api/analysis/upload";
      const result = await apiFetch(endpoint, { method: "POST", body: formData }, largeUpload ? LARGE_UPLOAD_TIMEOUT_MS : 180000);
      await applyImportedResult(result, {
        appending: desktopAppending,
        fileCount: files.length,
        selectedNames: files.map((file) => file.name),
        files,
        retainFileObjects: !IS_DESKTOP,
        requestedMapping,
        previousAnalysisId: previous.analysisId,
        previousSourceCount: existingWorkspaceCount
      });
    } catch (error) {
      restoreImportState(previous);
      setStatus("error", `导入分析失败：${friendlyError(error)}`, 0, () => uploadFiles(files, options));
      toast("分析失败", friendlyError(error), "error");
    } finally {
      setLoading(false);
    }
  }

  function validateFiles(files) {
    if (!files.length) return { ok: false, message: "请至少选择一个表格文件。" };
    if (files.length > 30) return { ok: false, message: "单次最多合并 30 个文件。" };
    const invalid = files.find((file) => !ACCEPTED_EXTENSIONS.includes((file.name.split(".").pop() || "").toLowerCase()));
    if (invalid) return { ok: false, message: `${invalid.name} 不是支持的 Excel/CSV 格式。` };
    const empty = files.find((file) => file.size === 0);
    if (empty) return { ok: false, message: `${empty.name} 是空文件。` };
    const oversizedLegacy = files.find((file) => (file.name.split(".").pop() || "").toLowerCase() === "xls" && file.size > MAX_LEGACY_XLS_BYTES);
    if (oversizedLegacy) return { ok: false, message: `${oversizedLegacy.name} 是旧版 .xls 且超过 64 MB，请先另存为 .xlsx 或 .csv。` };
    const oversized = files.find((file) => file.size > MAX_UPLOAD_BYTES);
    if (oversized) return { ok: false, message: `${oversized.name} 超过单文件 500 MB 上限。` };
    const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
    if (totalBytes > MAX_UPLOAD_BYTES) return { ok: false, message: "单次导入的文件总大小不能超过 500 MB。" };
    return { ok: true };
  }

  function renderAll() {
    if (!state.analysis) return;
    document.body.classList.remove("has-no-data");
    const mappingBlocked = mappingConfirmationRequired(state.analysis);
    document.querySelectorAll(".view-tabs [data-view]").forEach((tab) => {
      const unavailableForMapping = mappingBlocked && tab.dataset.view !== "overview";
      const unavailableProfitView = tab.dataset.view === "profit" && !state.analysis.capabilities.grossProfitAvailable;
      const unavailable = unavailableForMapping || unavailableProfitView;
      tab.disabled = unavailable;
      tab.setAttribute("aria-disabled", String(unavailable));
      if (!unavailable) tab.removeAttribute("aria-disabled");
      const detail = tab.querySelector("small");
      if (detail && unavailableForMapping) detail.textContent = "先确认关键口径";
      else if (detail) {
        const defaultDetail = { overview: "结果与趋势", product: "结构与量价", customer: "价值与下钻", profit: "拆解与扭亏", salesGroup: "阿米巴与下钻" };
        detail.textContent = tab.dataset.view === "profit" && unavailableProfitView ? "先确认成本口径" : defaultDetail[tab.dataset.view] || detail.textContent;
      }
    });
    document.querySelectorAll("[data-workspace]").forEach((item) => { item.disabled = false; });
    if (el.clearDataBtn) el.clearDataBtn.disabled = false;
    buildEvidenceLedger();
    renderHeader();
    renderImportPanel();
    animateNextViewRender = true;
    renderActiveView();
    renderInspector();
    renderDesktopStatus();
    const exportBlocked = mappingBlocked || !state.analysis.capabilities.hasRevenue;
    el.downloadXlsx.disabled = exportBlocked;
    el.downloadDocx.disabled = exportBlocked;
    if (el.downloadPdf) el.downloadPdf.disabled = exportBlocked;
    const exportHint = exportBlocked ? "先确认收入口径后再导出" : "";
    if (exportHint) {
      el.downloadXlsx.title = exportHint;
      el.downloadDocx.title = exportHint;
      if (el.downloadPdf) el.downloadPdf.title = exportHint;
    } else {
      el.downloadXlsx.title = "导出分析表";
      el.downloadDocx.title = "导出经营报告";
      if (el.downloadPdf) el.downloadPdf.title = "选择范围并导出 PDF";
    }
  }

  function renderHeader() {
    el.dataModeLabel.textContent = "本地工作簿";
    el.dataStatusDot.className = "ready";
    el.generatedAt.textContent = `更新于 ${formatDateTime(state.analysis.generatedAt)}`;
    if (el.projectNameLabel) {
      const projectName = state.sourceNames[0]?.replace(/\.[^.]+$/, "") || "本地经营分析";
      el.projectNameLabel.textContent = projectName;
      el.projectNameLabel.title = projectName;
    }
    if (el.sidebarSourceCount) el.sidebarSourceCount.textContent = `${formatInteger(state.persistedSources.length || state.analysis.sourceFileCount || state.sourceNames.length || 1)} 个`;
    updateDesktopImportCopy();
    renderDesktopStatus();
  }

  function renderImportPanel() {
    const count = state.persistedSources.length || state.analysis.sourceFileCount || state.sourceNames.length;
    el.importTitle.textContent = IS_DESKTOP ? `当前 ${count} 份数据源 · 可继续追加 Excel / CSV` : `已完成 ${count} 个文件的合并分析`;
    el.importDescription.textContent = IS_DESKTOP ? "新文件会加入当前项目并重新分析，旧文件不会被清空；支持一次多选或分批添加。" : state.sourceNames.length ? truncateNames(state.sourceNames) : `${formatInteger(state.analysis.rowCount)} 行有效经营记录`;
    el.sourceCount.textContent = IS_DESKTOP ? `${count} 份 · ${formatInteger(state.analysis.rowCount)} 行` : `${formatInteger(state.analysis.rowCount)} 行数据`;
  }

  function switchView(view, { focus = false } = {}) {
    if (!VIEW_META[view]) return;
    if (view !== "overview" && state.analysis && mappingConfirmationRequired(state.analysis)) {
      toast("关键口径尚未确认", "请先在经营总览或数据源页确认收入等业务字段。", "error");
      return;
    }
    if (view === "profit" && state.analysis && !state.analysis.capabilities.grossProfitAvailable) {
      toast("利润分析尚不可用", "先在字段口径卡中确认成本列；没有费用列时仍只会开放毛利分析。", "error");
      return;
    }
    state.activeWorkspace = "analysis";
    state.activeView = view;
    state.inspectorTab = "summary";
    animateNextViewRender = true;
    renderActiveView();
    selectInspectorTab("summary");
    if (focus) document.getElementById(`tab-${view}`)?.focus();
  }

  function switchWorkspace(workspace, { focus = false } = {}) {
    if (!WORKSPACE_META[workspace]) return;
    if (!state.analysis) {
      if (workspace === "data") requestFileImport();
      return;
    }
    state.activeWorkspace = workspace;
    state.inspectorTab = "summary";
    animateNextViewRender = true;
    renderActiveView();
    selectInspectorTab("summary");
    if (focus) document.querySelector(`[data-workspace="${workspace}"]`)?.focus();
  }

  function renderActiveView() {
    if (!state.analysis) return;
    const allowMotion = animateNextViewRender;
    animateNextViewRender = false;
    document.body.classList.toggle("suppress-view-motion", !allowMotion);
    el.dropZone.hidden = true;
    const isAnalysis = state.activeWorkspace === "analysis";
    if (isAnalysis && state.activeView !== "overview" && mappingConfirmationRequired(state.analysis)) {
      state.activeView = "overview";
    }
    const [title, subtitle] = isAnalysis ? VIEW_META[state.activeView] : WORKSPACE_META[state.activeWorkspace];
    el.pageTitle.textContent = title;
    el.pageSubtitle.textContent = subtitle;
    document.querySelectorAll(".view-tabs [data-view]").forEach((tab) => {
      const active = isAnalysis && tab.dataset.view === state.activeView;
      tab.setAttribute("aria-selected", String(active));
      tab.setAttribute("aria-current", active ? "page" : "false");
      tab.tabIndex = active ? 0 : -1;
    });
    document.querySelectorAll("[data-workspace]").forEach((item) => {
      const active = !isAnalysis && item.dataset.workspace === state.activeWorkspace;
      item.setAttribute("aria-current", active ? "page" : "false");
      item.classList.toggle("is-active", active);
    });
    el.viewContent.setAttribute("aria-labelledby", isAnalysis ? `tab-${state.activeView}` : `nav-${state.activeWorkspace}`);
    el.viewContent.setAttribute("aria-busy", String(state.loading));
    const renderers = { overview: renderOverview, product: renderProduct, customer: renderCustomer, profit: renderProfit, salesGroup: renderSalesGroup };
    const workspaceRenderers = { home: renderProjectHome, data: renderDataWorkspace, report: renderReportWorkspace };
    const page = isAnalysis ? renderers[state.activeView](state.analysis) : workspaceRenderers[state.activeWorkspace](state.analysis);
    const periodBar = IS_DESKTOP && (isAnalysis || state.activeWorkspace === "report") ? renderPeriodControl(state.analysis) : "";
    el.viewContent.innerHTML = isAnalysis
      ? `<div class="analysis-stage">${periodBar}${renderSemanticContract(state.analysis)}${renderMappingConfirmation(state.analysis)}${renderRiskNotice(state.analysis)}${page}</div>`
      : periodBar ? `<div class="analysis-stage report-analysis-stage">${periodBar}${page}</div>` : page;
    const providerSelect = el.viewContent.querySelector("[data-insight-provider]");
    if (providerSelect) providerSelect.value = state.activeProviderId;
    bindChartInteractions();
    renderInspector();
    renderDesktopStatus();
    ensureActiveExplorers();
    fitDonutCenterValues(el.viewContent);
    animateKpiValues(el.viewContent);
  }

  function fitDonutCenterValues(root) {
    if (!root) return;
    const ruler = document.createElement("span");
    ruler.style.cssText = "position:absolute;visibility:hidden;white-space:nowrap;pointer-events:none";
    root.appendChild(ruler);
    root.querySelectorAll(".donut-plot > span > b").forEach((value) => {
      value.style.fontSize = "";
      const cs = getComputedStyle(value);
      ruler.style.fontFamily = cs.fontFamily;
      ruler.style.fontWeight = cs.fontWeight;
      ruler.style.letterSpacing = cs.letterSpacing;
      ruler.textContent = value.textContent;
      const plot = value.closest(".donut-plot");
      const limit = (plot ? plot.clientWidth : 92) - 30;
      let size = Number.parseFloat(cs.fontSize);
      while (Number.isFinite(size) && size > 7.5) {
        ruler.style.fontSize = `${size}px`;
        if (ruler.offsetWidth <= limit) break;
        size -= 0.5;
      }
      value.style.fontSize = `${size}px`;
    });
    ruler.remove();
  }

  function animateKpiValues(root) {
    if (!root || document.body.classList.contains("suppress-view-motion")) return;
    if (window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    root.querySelectorAll(".kpi-value").forEach((node) => {
      const text = node.textContent;
      const match = text.match(/^([^0-9+\-]*)([+-]?\d[\d,]*(?:\.\d+)?)(.*)$/);
      if (!match) return;
      const [, prefix, numberPart, suffix] = match;
      const target = Number.parseFloat(numberPart.replace(/,/g, ""));
      if (!Number.isFinite(target) || target === 0) return;
      const decimals = (numberPart.split(".")[1] || "").length;
      const useGrouping = numberPart.includes(",");
      const format = (value) => prefix + value.toLocaleString("en-US", { minimumFractionDigits: decimals, maximumFractionDigits: decimals, useGrouping }) + suffix;
      const duration = 620;
      const start = performance.now();
      const tick = (now) => {
        const progress = Math.min(1, (now - start) / duration);
        const eased = 1 - Math.pow(1 - progress, 3);
        node.textContent = progress < 1 ? format(target * eased) : text;
        if (progress < 1 && node.isConnected) requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
    });
  }

  function availablePeriodMonths(a = state.analysis) {
    const values = new Set();
    (a?.monthly || []).forEach((item) => {
      const month = normalizeMonthValue(item?.month);
      if (month) values.add(month);
    });
    (state.persistedSources || []).forEach((source) => (source.periods || []).forEach((value) => {
      const month = normalizeMonthValue(value);
      if (month) values.add(month);
    }));
    return [...values].sort();
  }

  function normalizeMonthValue(value) {
    const match = String(value || "").match(/(20\d{2})[^0-9]?([01]?\d)/);
    if (!match) return "";
    const month = Number(match[2]);
    return month >= 1 && month <= 12 ? `${match[1]}-${String(month).padStart(2, "0")}` : "";
  }

  function normalizePeriodSelections(a = state.analysis) {
    if (!IS_DESKTOP) return;
    const months = availablePeriodMonths(a);
    const years = new Set(months.map((month) => month.slice(0, 4)));
    state.periodControls.years = state.periodControls.years.filter((year) => years.has(year));
    state.periodControls.months = state.periodControls.months.filter((month) => months.includes(month));
  }

  function periodRequestPayload(a = state.analysis) {
    if (!IS_DESKTOP) return {};
    normalizePeriodSelections(a);
    return {
      periodMode: state.periodControls.mode,
      years: [...state.periodControls.years],
      months: [...state.periodControls.months]
    };
  }

  function periodQueryString() {
    if (!IS_DESKTOP) return "";
    const period = periodRequestPayload();
    const query = new URLSearchParams({ periodMode: period.periodMode });
    period.years.forEach((value) => query.append("years", value));
    period.months.forEach((value) => query.append("months", value));
    return `?${query.toString()}`;
  }

  function activePeriodMonths(a = state.analysis) {
    const available = availablePeriodMonths(a);
    if (!IS_DESKTOP) return available;
    const selectedMonths = new Set(state.periodControls.months);
    const selectedYears = new Set(state.periodControls.years);
    return available.filter((month) => (!selectedYears.size || selectedYears.has(month.slice(0, 4)))
      && (!selectedMonths.size || selectedMonths.has(month)));
  }

  function periodScopeLabel(a = state.analysis) {
    const months = activePeriodMonths(a);
    const modeLabel = { cumulative: "累计", year: "年度对比", month: "月度对比" }[state.periodControls.mode] || "累计";
    if (!months.length) return `${modeLabel} · 暂无匹配月份`;
    if (months.length === 1) return `${modeLabel} · ${displayMonth(months[0])}`;
    return `${modeLabel} · ${displayMonth(months[0])}—${displayMonth(months.at(-1))}（${months.length} 个月）`;
  }

  function renderPeriodControl(a) {
    if (!IS_DESKTOP) return "";
    normalizePeriodSelections(a);
    const months = availablePeriodMonths(a);
    if (!months.length) return `<section class="period-command empty" aria-label="分析期间"><div><span class="eyebrow">统一分析期间</span><strong>当前数据没有可用月份</strong><small>请先在字段复核中确认日期字段。</small></div></section>`;
    const years = [...new Set(months.map((month) => month.slice(0, 4)))];
    const modeText = {
      cumulative: "合并所选期间，查看累计经营结果",
      year: "按年度比较规模、成本与利润",
      month: "按月份逐期比较并支持多选"
    }[state.periodControls.mode];
    return `<section class="period-command" aria-labelledby="periodCommandTitle">
      <div class="period-command-intro"><span class="eyebrow">统一分析期间</span><strong id="periodCommandTitle">${esc(periodScopeLabel(a))}</strong><small>${esc(modeText)}；本页所有图表、下钻和 AI 上下文使用同一范围。</small></div>
      <div class="period-mode-switch" role="group" aria-label="分析方式">${[["cumulative", "累计"], ["year", "年度"], ["month", "月度"]].map(([value, label]) => `<button type="button" data-period-mode="${value}" aria-pressed="${state.periodControls.mode === value}" class="${state.periodControls.mode === value ? "active" : ""}">${label}</button>`).join("")}</div>
      ${renderPeriodMultiselect("years", "年度（可多选）", years, state.periodControls.years)}
      ${renderPeriodMultiselect("months", "月份（可多选）", months, state.periodControls.months)}
      <button class="period-reset" type="button" data-clear-period ${!state.periodControls.years.length && !state.periodControls.months.length ? "disabled" : ""}>恢复全部期间</button>
      <p class="period-contract-note"><b>月份筛选在这里完成。</b>下方“收入/成本/日期”下拉框只是在多份来源列中确认字段含义，选择带“6月”的字段不等于只分析 6 月。</p>
    </section>`;
  }

  function renderPeriodMultiselect(key, label, options, selectedValues) {
    const selected = new Set(selectedValues);
    const filterKey = `period:${key}`;
    const search = state.periodControls.searches[key] || "";
    const summary = selected.size ? `已选 ${selected.size} 项` : "全部";
    return `<details class="period-multiselect" data-filter-key="${filterKey}" ${state.periodControls.openKey === filterKey ? "open" : ""}><summary><span>${esc(label)}</span><b data-filter-summary>${esc(summary)}</b></summary><div class="multiselect-popover"><label class="multiselect-search"><span class="sr-only">搜索${esc(label)}</span><input type="search" value="${attr(search)}" data-period-option-search="${key}" autocomplete="off" placeholder="搜索${key === "years" ? "年度" : "月份"}"></label><div class="multiselect-options">${options.map((value) => `<label data-option-text="${attr(value)}" ${search && !normalizeFuzzyText(value).includes(normalizeFuzzyText(search)) ? "hidden" : ""}><input type="checkbox" value="${attr(value)}" data-period-${key === "years" ? "year" : "month"} ${selected.has(value) ? "checked" : ""}><span>${esc(key === "years" ? `${value} 年` : displayMonth(value))}</span></label>`).join("")}</div><small>不勾选表示全部；可连续勾选多项。</small></div></details>`;
  }

  function renderSemanticContract(a) {
    const capabilities = a.capabilities;
    const mappingPending = mappingConfirmationRequired(a);
    const fieldSource = a.fieldUnderstandingSource === "AI" ? "AI 已辅助识别" : "本地规则已初判";
    const currentInsight = currentPeriodInsightRecord(a);
    const interpretation = mappingPending
      ? "等待口径确认"
      : currentInsight?.insights?.length ? `${providerName(currentInsight.providerId || a.insightProviderId)} 已返回` : "尚未调用 AI";
    const available = [
      !mappingPending && capabilities.hasRevenue ? "销售分析" : null,
      !mappingPending && capabilities.grossProfitAvailable ? "毛利分析" : null,
      !mappingPending && capabilities.operatingProfitAvailable ? "经营利润" : null,
      !mappingPending && capabilities.hasQuantity ? "量价分析" : null,
      !mappingPending && capabilities.hasDate ? "期间趋势" : null
    ].filter(Boolean);
    return `<section class="semantic-contract ${mappingPending ? "needs-review" : ""}" aria-label="本次分析能力与处理阶段">
      <div class="contract-title"><span class="contract-orbit" aria-hidden="true"><i></i><b></b></span><div><span class="eyebrow">ANALYSIS CONTRACT</span><strong>本次分析口径</strong><small>只开放已被数据支持的能力</small></div></div>
      <ol class="contract-flow">
        <li class="done"><span>01</span><div><strong>结构识别</strong><small>${formatInteger(a.profile.profiledRows || a.rowCount)} 行已画像</small></div></li>
        <li class="${mappingPending ? "current" : "done"}"><span>02</span><div><strong>${a.fieldUnderstandingSource === "AI" ? "AI 字段理解" : "字段理解"}</strong><small>${fieldSource}</small></div></li>
        <li class="${mappingPending ? "waiting" : "done"}"><span>03</span><div><strong>本地确定性计算</strong><small>${mappingPending ? "关键口径确认后重算" : "金额与比率已计算"}</small></div></li>
        <li class="${isAiInsight(a) ? "done" : "waiting"}"><span>04</span><div><strong>AI 经营解读</strong><small>${interpretation}</small></div></li>
      </ol>
      <div class="capability-stack"><span>当前可分析</span><div>${available.length ? available.map((item) => `<b>${esc(item)}</b>`).join("") : `<b class="muted">等待确认收入口径</b>`}</div></div>
    </section>`;
  }

  function renderMappingConfirmation(a) {
    const profile = a.profile;
    const forcedReview = IS_DESKTOP && state.forceMappingReview;
    if (!forcedReview && !profile.mappings.length && !profile.mappingIssues.length) return "";
    const pending = mappingConfirmationRequired(a);
    const reviewableFields = MAPPING_FIELDS.filter((field) => mappingCandidates(a, field.id).length);
    const mappingIssues = profile.mappingIssues.filter(isMappingIssue);
    if (!forcedReview && !pending && !mappingIssues.length && !profile.mappings.some((item) => item.alternatives.length)) return "";
    const filesAvailable = Boolean(state.lastUploadFiles?.length || state.persistedSources.length);
    const issues = mappingIssues.length ? mappingIssues : profile.mappingIssues.filter((item) => item.severity === "BLOCKING");
    return `<section class="mapping-workbench ${pending ? "pending" : "resolved"} ${forcedReview ? "ai-review" : ""}" aria-labelledby="mappingTitle">
      <header class="mapping-head"><div class="mapping-symbol" aria-hidden="true"><svg><use href="#i-table"></use></svg></div><div><span class="eyebrow">${forcedReview ? "AI REQUESTED REVIEW" : a.fieldUnderstandingSource === "AI" ? "AI FIELD UNDERSTANDING" : "FIELD UNDERSTANDING"}</span><h2 id="mappingTitle">${pending ? "先确认业务字段，再生成经营结论" : forcedReview ? "AI 建议你复核本次字段与月份口径" : "复核系统识别的字段口径"}</h2><p>${forcedReview ? "AI 只触发复核流程，没有修改任何金额。请检查收入、成本、数量和日期字段，再由你确认是否重新计算。" : "同一张报表可能同时包含未税金额、含税金额、调后金额，以及多种成本或毛利列。DataMaster 不会静默猜选，也不会把公式错误或缺失列当成 0。"}</p></div><span class="review-state">${pending ? "需要你确认" : forcedReview ? "AI 建议复核" : "可选复核"}</span></header>
      ${issues.length ? `<ul class="mapping-issue-list">${issues.slice(0, 4).map((issue) => `<li class="${issue.severity.toLowerCase()}"><svg><use href="#i-alert"></use></svg><span><strong>${esc(issue.severity === "BLOCKING" ? "阻止利润结论" : "需要复核")}</strong>${esc(issue.message)}</span></li>`).join("")}</ul>` : ""}
      ${IS_DESKTOP ? `<div class="mapping-period-note"><svg><use href="#i-info"></use></svg><span><strong>字段选择 ≠ 月份筛选</strong>这里的每个候选项代表一份来源表中的列。即使列名旁显示“6月”，也只是确认该列的业务含义；实际分析月份统一由页面顶部的“统一分析期间”控制。</span></div>` : ""}
      <div class="mapping-grid">${reviewableFields.map((field) => renderMappingField(a, field)).join("")}</div>
      ${state.schemaMessage ? `<div class="schema-assist-state"><svg><use href="#${a.fieldUnderstandingSource === "AI" ? "i-spark" : "i-info"}"></use></svg><span>${esc(state.schemaMessage)}</span></div>` : ""}
      <footer class="mapping-actions"><div><strong>选择后会怎样？</strong><span>程序使用当前保留的本地文件重新聚合；原始文件不会被修改。AI 辅助识别只发送匿名列画像，不发送原始行或客户名称。</span></div><div class="mapping-action-buttons"><button class="button button-secondary" type="button" data-action="suggest-mapping-ai" ${state.schemaLoading ? "disabled" : ""}><svg><use href="#i-spark"></use></svg><span>${state.schemaLoading ? "AI 正在识别…" : "AI 辅助识别字段"}</span></button>${filesAvailable
        ? `<button class="button button-primary" type="button" data-action="confirm-mapping" ${state.loading ? "disabled" : ""}><svg><use href="#i-check"></use></svg><span>${state.loading ? "正在重新分析…" : pending ? "确认口径并重新分析" : "应用选择并重新分析"}</span></button>`
        : `<button class="button button-secondary" type="button" data-action="reselect-for-mapping"><svg><use href="#i-upload"></use></svg><span>重新选择原文件</span></button>`}</div></footer>
    </section>`;
  }

  function renderMappingField(a, field) {
    const candidates = mappingCandidates(a, field.id);
    const selected = Object.prototype.hasOwnProperty.call(state.mappingDraft, field.id)
      ? state.mappingDraft[field.id]
      : selectedMappingValue(candidates);
    const selectedCandidate = candidates.find((candidate) => candidate.value === selected);
    const confidence = selectedCandidate?.confidence;
    const confidenceText = confidence === null || confidence === undefined ? "待确认" : confidence >= .9 ? "高置信" : confidence >= .7 ? "中等置信" : "低置信";
    return `<label class="mapping-field ${field.required ? "required" : ""}"><span class="mapping-field-label"><strong>${esc(field.label)}</strong><small>${esc(field.description)}</small></span><select data-mapping-field="${attr(field.id)}" aria-label="选择${attr(field.label)}字段">${field.required ? "" : `<option value="">不使用此字段</option>`}${candidates.map((candidate) => `<option value="${attr(candidate.value)}" ${candidate.value === selected ? "selected" : ""}>${esc(candidate.header)}${candidate.location ? ` · ${esc(candidate.location)}` : ""}</option>`).join("")}</select><span class="mapping-confidence ${confidenceText === "低置信" ? "low" : ""}"><i></i>${esc(confidenceText)}${selectedCandidate?.coverage !== null && selectedCandidate?.coverage !== undefined ? ` · ${formatPercent(selectedCandidate.coverage)}` : ""}</span></label>`;
  }

  function renderRiskNotice(a) {
    const issueMessages = a.profile.mappingIssues.filter((issue) => !isMappingIssue(issue)).map((issue) => ({ severity: issue.severity, code: issue.code, message: issue.message }));
    const warnings = (a.quality.warnings || []).map((message) => ({ severity: "WARNING", code: "QUALITY", message }));
    if (a.quality.formulaErrors) warnings.unshift({ severity: "WARNING", code: "FORMULA_ERROR", message: `${formatInteger(a.quality.formulaErrors)} 个候选指标数值或公式结果不可解析；未选口径不会进入当前计算。` });
    if (a.quality.externalWorkbookLinks) warnings.unshift({ severity: "WARNING", code: "EXTERNAL_REFERENCE", message: `工作簿包含 ${formatInteger(a.quality.externalWorkbookLinks)} 个外部链接，当前只能读取文件内缓存值。` });
    const risks = [...issueMessages, ...warnings].filter((item, index, list) => item.message && list.findIndex((candidate) => candidate.message === item.message) === index).slice(0, 4);
    if (!risks.length) return "";
    const critical = risks.some((item) => item.severity === "BLOCKING" || /外部|缓存|公式|error|link/i.test(`${item.code} ${item.message}`));
    return `<details class="data-risk-panel ${critical ? "critical" : ""}" ${critical ? "open" : ""}><summary><span class="risk-icon"><svg><use href="#i-alert"></use></svg></span><div><strong>${critical ? "这份报表存在公式或外链风险" : "数据质量提示"}</strong><small>${critical ? "缓存值可能与源工作簿不同步，请在据此决策前复核。" : "查看不会阻断当前分析的提示项。"}</small></div><span>${formatInteger(risks.length)} 项</span></summary><ul>${risks.map((risk) => `<li><span>${esc(risk.code || "CHECK")}</span><p>${esc(risk.message)}</p></li>`).join("")}</ul><footer>公式错误、空值和不可解析数值不会被自动替换成 0；相关指标会显示为不可用或降低覆盖率。</footer></details>`;
  }

  function drillChildFor(dimensionId) {
    // 下钻路径由后端从数据关系自动发现（drillSuggestions）；未拿到建议前暂用内置映射兜底。
    return state.drillSuggestionsLoaded
      ? (state.drillSuggestions[dimensionId] || null)
      : (DIMENSION_DRILLS[dimensionId] || null);
  }

  function renderDynamicExplorer(a) {
    const dimensions = a.dynamicBreakdowns.filter((item) => item.values.length);
    if (!dimensions.length) return "";
    const active = dimensions.find((item) => item.dimensionId === state.activeBreakdownId) || preferredBreakdown(dimensions);
    state.activeBreakdownId = active.dimensionId;
    const entry = state.explorers.get("dynamicBreakdown");
    const dataMatches = entry?.status === "success" && entry.data?.groupBy === active.dimensionId;
    const feedback = dataMatches ? "" : entry?.status === "error"
      ? renderExplorerFeedback("dynamicBreakdown", { errorTitle: `无法读取${active.dimensionLabel}` })
      : `<div class="explorer-feedback loading" role="status"><span class="spinner"></span><div><strong>正在读取${esc(active.dimensionLabel)}全部分组</strong><p>销售额、毛利和毛利率会在同一口径下计算。</p></div></div>`;
    const data = dataMatches ? entry.data : null;
    const entries = data?.items || [];
    const maxRevenue = Math.max(...entries.map((item) => Math.abs(item.revenue || 0)), 1);
    const start = data ? data.offset + 1 : 0;
    const end = data ? data.offset + entries.length : 0;
    const total = data?.totalGroups || active.totalGroups || active.values.length;
    const currentPage = data ? Math.floor(data.offset / Math.max(data.pageSize, 1)) : state.dynamicControls.page;
    const totalPages = Math.max(1, Math.ceil(total / state.dynamicControls.pageSize));
    const searchQuery = state.dynamicControls.search;
    const drill = state.dynamicControls.drill;
    const drillChild = drillChildFor(active.dimensionId);
    const drillChildLabel = drillChild ? (dimensions.find((item) => item.dimensionId === drillChild)?.dimensionLabel || drillChild) : "";
    const rows = entries.map((item, index) => {
      const revenueShare = item.share ?? (item.revenue !== null && data?.totals?.revenue ? item.revenue / data.totals.revenue : null);
      const drillButton = drillChild ? `<button type="button" class="dimension-drill" data-drill-dimension="${attr(active.dimensionId)}" data-drill-value="${attr(item.name)}" title="下钻到${attr(drillChildLabel)}：${attr(item.name)}" aria-label="下钻到${attr(drillChildLabel)}：${attr(item.name)}">↳</button>` : "";
      return `<li><span class="dimension-rank">${String(start + index).padStart(2, "0")}</span><div class="dimension-name"><div class="dimension-name-text"><strong title="${attr(item.name)}">${esc(item.name)}</strong><span>${formatInteger(item.count || item.records || 0)} 条${revenueShare === null ? "" : ` · 销售占比 ${formatPercent(revenueShare)}`}</span></div>${drillButton}</div><div class="dimension-bar"><i style="width:${Math.max(3, Math.abs(item.revenue || 0) / maxRevenue * 100)}%"></i></div><b>${item.revenue === null ? "—" : esc(formatCurrencyExact(item.revenue))}</b><b class="${item.grossProfit !== null && item.grossProfit < 0 ? "negative-text" : ""}">${item.grossProfit === null ? "—" : esc(formatCurrencyExact(item.grossProfit))}</b><b class="${item.grossMargin !== null && item.grossMargin < 0 ? "negative-text" : ""}">${item.grossMargin === null ? "—" : esc(formatPercent(item.grossMargin))}</b></li>`;
    }).join("");
    const result = feedback || `<div class="dimension-column-head" aria-hidden="true"><span>序号</span><span>${esc(active.dimensionLabel)}</span><span>规模</span><span>销售额</span><span>毛利</span><span>毛利率</span></div><ol>${rows}</ol><footer class="dimension-pagination"><span>${searchQuery ? "匹配" : "共"} ${formatInteger(total)} 项，当前 ${formatInteger(start)}–${formatInteger(end)}</span><div><button type="button" data-dynamic-page="${Math.max(0, currentPage - 1)}" ${currentPage <= 0 ? "disabled" : ""}>上一页</button><strong>${formatInteger(currentPage + 1)} / ${formatInteger(totalPages)}</strong><button type="button" data-dynamic-page="${currentPage + 1}" ${data?.hasMore ? "" : "disabled"}>下一页</button></div></footer>`;
    const dimensionQuery = normalizeFuzzyText(state.dynamicControls.dimensionFilter);
    const visibleDimensionCount = dimensions.filter((dimension) => !dimensionQuery || normalizeFuzzyText(dimension.dimensionLabel).includes(dimensionQuery)).length;
    const dimensionButtons = dimensions.map((dimension) => {
      const hidden = dimensionQuery && !normalizeFuzzyText(dimension.dimensionLabel).includes(dimensionQuery);
      return `<button type="button" data-breakdown-id="${attr(dimension.dimensionId)}" data-dimension-label="${attr(dimension.dimensionLabel)}" aria-pressed="${dimension.dimensionId === active.dimensionId}" ${hidden ? "hidden" : ""}><span>${esc(dimension.dimensionLabel)}</span><small>${formatInteger(dimension.totalGroups || dimension.values.length)} 组</small></button>`;
    }).join("");
    return `<section class="surface dimension-explorer" aria-labelledby="dimensionExplorerTitle"><header class="section-heading"><div><span class="eyebrow">DYNAMIC DIMENSIONS</span><h2 id="dimensionExplorerTitle">从任意业务维度下钻</h2><p>不再截断前八项；每页可查看 100 项，并同时核对销售额、毛利与毛利率。</p></div><span class="section-note">${formatInteger(dimensions.length)} 个可用维度</span></header><div class="dimension-layout"><aside class="dimension-rail"><label class="dimension-rail-search"><span>查找分析维度</span><input type="search" data-dimension-filter value="${attr(state.dynamicControls.dimensionFilter)}" autocomplete="off" placeholder="如客户、产品、渠道"><small data-dimension-filter-count>${dimensionQuery ? `${formatInteger(visibleDimensionCount)} 个匹配维度` : `${formatInteger(dimensions.length)} 个业务维度`}</small></label><nav class="dimension-switcher" aria-label="选择分析维度">${dimensionButtons}<span class="dimension-filter-empty" data-dimension-filter-empty ${visibleDimensionCount ? "hidden" : ""}>没有匹配的分析维度</span></nav><div class="dimension-rail-summary"><strong>全量明细</strong><span>分页呈现全部分组，切换维度不会改写原始数据。</span></div></aside><div class="dimension-canvas"><header class="dimension-canvas-head"><div><strong>${esc(active.dimensionLabel)}</strong><span>完整分页 · 销售与毛利同屏</span></div>${drill ? `<button type="button" class="drill-context-chip" data-clear-drill title="清除下钻条件，返回全部${attr(drill.parentLabel)}"><span>${esc(drill.parentLabel)}：${esc(drill.value)}</span><i aria-hidden="true">×</i></button>` : ""}<label class="inline-explorer-search"><span class="sr-only">在${esc(active.dimensionLabel)}中模糊搜索</span><input type="search" data-dynamic-search value="${attr(searchQuery)}" maxlength="120" autocomplete="off" placeholder="搜索${attr(active.dimensionLabel)}"><i aria-hidden="true"></i></label>${data ? `<small>${formatInteger(start)}–${formatInteger(end)} / ${formatInteger(total)}</small>` : ""}</header>${result}</div></div></section>`;
  }

  function preferredBreakdown(dimensions) {
    const preferred = ["region", "channel", "status", "businessStatus", "product", "customer"];
    return [...dimensions].sort((left, right) => {
      const leftRank = preferred.findIndex((id) => left.dimensionId.toLowerCase().includes(id.toLowerCase()));
      const rightRank = preferred.findIndex((id) => right.dimensionId.toLowerCase().includes(id.toLowerCase()));
      return (leftRank < 0 ? 99 : leftRank) - (rightRank < 0 ? 99 : rightRank);
    })[0];
  }

  function breakdownMetric(a, entries) {
    if (state.activeView === "profit" && a.capabilities.grossProfitAvailable
        && entries.some((item) => item.grossProfitAvailable)) {
      return { label: "毛利润（优先显示拖累项）", value: (item) => item.grossProfitAvailable ? numberValue(item.grossProfit) : 0, total: 0, shareable: false, format: formatCurrency, direction: "asc" };
    }
    if (a.capabilities.hasRevenue && entries.some((item) => item.revenue !== null)) {
      return { label: "收入", value: (item) => numberValue(item.revenue), total: Math.max(numberValue(a.summary.revenue), 1), shareable: true, format: formatCurrency, direction: "desc" };
    }
    if (a.capabilities.hasQuantity && entries.some((item) => item.quantity !== null)) {
      const total = entries.reduce((sum, item) => sum + numberValue(item.quantity), 0);
      return { label: "数量", value: (item) => numberValue(item.quantity), total: Math.max(total, 1), shareable: true, format: (value) => formatInteger(value), direction: "desc" };
    }
    return { label: "记录数", value: (item) => numberValue(item.count || item.quantity || 0), total: 0, shareable: false, format: (value) => `${formatInteger(value)} 条`, direction: "desc" };
  }

  function mappingCandidates(a, fieldId) {
    const mappings = a.profile.mappings;
    const candidates = [];
    const append = (raw, parent = {}) => {
      const object = typeof raw === "string" ? (mappings.find((item) => item.columnId === raw || item.header === raw) || { columnId: raw, header: raw }) : (raw || {});
      const value = String(object.columnId || object.id || object.header || object.column || "").trim();
      if (!value || candidates.some((item) => item.value === value)) return;
      candidates.push({
        value,
        header: String(object.header || object.label || object.name || value),
        location: String(object.columnIndexLabel || object.excelColumn || object.source || parent.source || ""),
        confidence: nullableNumber(object.confidence ?? parent.confidence),
        coverage: nullableNumber(object.coverage ?? object.validCoverage ?? parent.coverage),
        selected: Boolean(object.selected)
      });
    };
    mappings.filter((mapping) => canonicalField(mapping.fieldId) === fieldId).forEach((mapping) => {
      append(mapping);
      mapping.alternatives.forEach((alternative) => append(alternative, mapping));
    });
    return candidates.sort((left, right) => Number(right.selected) - Number(left.selected)
      || numberValue(right.confidence, -1) - numberValue(left.confidence, -1));
  }

  function selectedMappingValue(candidates) {
    return candidates.find((candidate) => candidate.selected)?.value || candidates[0]?.value || "";
  }

  function resolvedMappingValue(candidates, preferred) {
    const requested = String(preferred || "").trim();
    if (!requested) return selectedMappingValue(candidates);
    const normalized = requested.normalize("NFKC").replace(/\s+/g, "").toLowerCase();
    const match = candidates.find((candidate) => {
      if (candidate.value === requested) return true;
      const header = String(candidate.header || "").normalize("NFKC").replace(/\s+/g, "").toLowerCase();
      return header === normalized;
    });
    return match?.value || selectedMappingValue(candidates);
  }

  function initializeMappingState(a, preferred = {}) {
    const draft = {};
    MAPPING_FIELDS.forEach((field) => {
      const candidates = mappingCandidates(a, field.id);
      draft[field.id] = resolvedMappingValue(candidates, preferred[field.id]);
    });
    state.mappingDraft = draft;
    const availableBreakdowns = a.dynamicBreakdowns.map((item) => item.dimensionId);
    if (!availableBreakdowns.includes(state.activeBreakdownId)) state.activeBreakdownId = "";
  }

  function compactMapping(mapping) {
    return Object.fromEntries(Object.entries(mapping || {}).filter(([key, value]) => MAPPING_FIELDS.some((field) => field.id === key) && String(value || "").trim()).map(([key, value]) => [key, String(value).trim()]));
  }

  async function confirmFieldMapping() {
    if (state.loading) return;
    if (!mappingCandidates(state.analysis, "revenue").length) {
      toast("尚未找到收入候选", "请先使用 AI 辅助识别字段，或重新导入包含收入列的报表。", "error");
      return;
    }
    const mapping = compactMapping(state.mappingDraft);
    if (!mapping.revenue && mappingCandidates(state.analysis, "revenue").length) {
      toast("收入字段尚未确认", "请选择本次分析采用的收入列。", "error");
      return;
    }
    if (state.lastUploadFiles?.length) {
      await uploadFiles([...state.lastUploadFiles], { mapping });
      return;
    }
    if (state.persistedSources.length) {
      const requestAnalysisId = state.analysis?.id || "";
      let timedOut = false;
      pauseExplorerRequests();
      setLoading(true, "正在使用本机保存的数据重新分析…");
      try {
        const result = await apiFetch("/api/workspace/current/reanalyze", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(mapping)
        }, LARGE_UPLOAD_TIMEOUT_MS);
        state.appliedMapping = mapping;
        applyWorkspacePayload(result);
        toast("字段口径已应用", "已使用本机保存的数据重新计算，无需再次选择文件。", "success");
      } catch (error) {
        timedOut = error?.name === "AbortError";
        if (timedOut) {
          setStatus("loading", "本机仍可能在完成重新分析，正在自动对账最新结果…");
          toast("重新分析仍在后台完成", "程序会自动接管最终结果，请勿重复提交。", "success");
        } else {
          toast("重新分析失败", friendlyError(error), "error");
        }
      } finally {
        setLoading(false);
      }
      if (timedOut && requestAnalysisId) recoverWorkspaceAfterSessionExpiry(requestAnalysisId);
      else ensureActiveExplorers();
      return;
    }
    toast("需要重新选择原文件", "当前工作区没有可重新分析的本地数据源。", "error");
    requestFileImport({ preserveMapping: true });
  }

  async function suggestFieldMappingWithAi() {
    if (!state.analysis?.id || state.schemaLoading) return;
    const requestAnalysisId = state.analysis.id;
    const requestToken = ++state.schemaRequestToken;
    const provider = state.providers.find((item) => item.id === state.activeProviderId);
    if (!provider?.configured) {
      toast("先配置 AI 平台", "AI 字段理解需要已保存且连接可用的 DeepSeek 或阿里云百炼配置。", "error");
      openSettings();
      return;
    }
    state.schemaLoading = true;
    state.schemaMessage = "AI 正在阅读匿名列画像；原始行、客户名称和文件内容不会发送。";
    renderActiveView();
    try {
      const result = await apiFetch(`/api/analysis/${encodeURIComponent(state.analysis.id)}/schema-suggestions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ providerId: provider.id })
      }, 90000);
      if (state.schemaRequestToken !== requestToken || state.analysis?.id !== requestAnalysisId) return;
      const fieldCandidates = Array.isArray(result?.fieldCandidates) ? result.fieldCandidates : [];
      const supported = new Set(MAPPING_FIELDS.map((field) => field.id));
      const suggestions = [];
      fieldCandidates.forEach((column) => {
        (Array.isArray(column.candidates) ? column.candidates : []).forEach((candidate) => {
          const fieldId = canonicalField(candidate.semanticField);
          if (!supported.has(fieldId) || fieldId === "unknown") return;
          suggestions.push({
            fieldId,
            columnId: String(column.columnId || ""),
            confidence: nullableNumber(candidate.confidence),
            reasonCode: String(candidate.reasonCode || "AI_PROFILE_SUGGESTION")
          });
        });
      });
      if (!suggestions.length) throw new Error("AI 没有返回可用的字段候选，请手动确认或更换模型后重试。");
      mergeAiMappingSuggestions(state.analysis, suggestions, result);
      state.analysis.fieldUnderstandingSource = "AI";
      const limitations = Array.isArray(result?.limitations) ? result.limitations : [];
      state.schemaMessage = `${providerName(result?.providerId || provider.id)} · ${result?.model || provider.model} 已完成字段画像建议；${Array.isArray(result?.requiredConfirmations) && result.requiredConfirmations.length ? "仍有冲突，必须由你确认后重算。" : "建议已填入下方选择框，请确认后重算。"}${limitations.length ? ` 限制：${limitations.map(schemaCodeLabel).join("、")}` : ""}`;
      renderAll();
      toast("AI 字段理解已返回", "建议已填入但尚未应用；请人工确认业务口径。", "success");
    } catch (error) {
      if (state.schemaRequestToken !== requestToken || state.analysis?.id !== requestAnalysisId) return;
      state.schemaMessage = `AI 字段理解失败：${friendlyError(error)}。当前本地候选仍可手动确认。`;
      renderActiveView();
      toast("AI 字段识别失败", friendlyError(error), "error");
    } finally {
      if (state.schemaRequestToken === requestToken && state.analysis?.id === requestAnalysisId) {
        state.schemaLoading = false;
        renderActiveView();
      }
    }
  }

  function mergeAiMappingSuggestions(a, suggestions, result) {
    const knownMappings = a.profile.mappings;
    const byField = new Map();
    suggestions.forEach((suggestion) => {
      if (!suggestion.columnId) return;
      if (!byField.has(suggestion.fieldId)) byField.set(suggestion.fieldId, []);
      byField.get(suggestion.fieldId).push(suggestion);
    });
    byField.forEach((items, fieldId) => {
      items.sort((left, right) => numberValue(right.confidence, 0) - numberValue(left.confidence, 0));
      const alternatives = items.slice(1).map((item) => {
        const known = knownMappings.find((mapping) => mapping.columnId === item.columnId);
        return { columnId: item.columnId, header: known?.header || item.columnId, source: known?.source || "", confidence: item.confidence, reasonCode: item.reasonCode };
      });
      items.forEach((item, index) => {
        let mapping = knownMappings.find((candidate) => canonicalField(candidate.fieldId) === fieldId && candidate.columnId === item.columnId);
        const columnMeta = knownMappings.find((candidate) => candidate.columnId === item.columnId);
        if (!mapping) {
          mapping = {
            columnId: item.columnId,
            fieldId,
            role: fieldId === "product" || fieldId === "customer" || fieldId === "date" ? "DIMENSION" : "METRIC",
            header: columnMeta?.header || item.columnId,
            source: columnMeta?.source || "AI 匿名列画像",
            columnIndex: columnMeta?.columnIndex ?? null,
            confidence: item.confidence,
            selected: index === 0,
            nonBlankCount: columnMeta?.nonBlankCount ?? null,
            validCount: columnMeta?.validCount ?? null,
            rowCount: columnMeta?.rowCount ?? null,
            coverage: columnMeta?.coverage ?? null,
            validCoverage: columnMeta?.validCoverage ?? null,
            alternatives: index === 0 ? alternatives : []
          };
          knownMappings.push(mapping);
        } else {
          mapping.confidence = item.confidence;
          mapping.selected = index === 0;
          if (index === 0) mapping.alternatives = alternatives;
        }
      });
      state.mappingDraft[fieldId] = items[0].columnId;
    });
    const conflicts = Array.isArray(result?.conflicts) ? result.conflicts : [];
    const confirmations = Array.isArray(result?.requiredConfirmations) ? result.requiredConfirmations : [];
    const retained = a.profile.mappingIssues.filter((issue) => !String(issue.code).startsWith("AI_"));
    const aiIssues = conflicts.map((conflict) => ({
      severity: "BLOCKING",
      code: `AI_${conflict.code || "MAPPING_CONFLICT"}`,
      message: `${schemaCodeLabel(conflict.semanticField)}存在多个合理候选：${(conflict.columnIds || []).join("、") || "请人工复核"}。AI 不会替你决定业务口径。`,
      source: "AI 匿名列画像",
      candidates: Array.isArray(conflict.columnIds) ? conflict.columnIds.map(String) : []
    }));
    confirmations.filter((confirmation) => !aiIssues.some((issue) => issue.code.includes(String(confirmation)))).forEach((confirmation) => aiIssues.push({ severity: "BLOCKING", code: `AI_${confirmation}`, message: `${schemaCodeLabel(confirmation)}需要人工确认后才能应用。`, source: "AI 匿名列画像", candidates: [] }));
    if (!aiIssues.length) aiIssues.push({ severity: "WARNING", code: "AI_CONFIRM_DRAFT", message: "AI 建议仅基于匿名列画像，仍需人工确认后才会重新计算。", source: "AI 匿名列画像", candidates: [] });
    a.profile.mappingIssues = [...retained, ...aiIssues];
  }

  function schemaCodeLabel(value) {
    const code = String(value || "");
    const labels = {
      REVENUE: "收入字段", COST: "成本字段", EXPENSE: "费用字段", DATE: "日期字段", QUANTITY: "数量字段", PRODUCT: "产品字段", CUSTOMER: "客户字段",
      PROFILE_ONLY_NO_RAW_VALUES: "仅基于列画像，不读取原始值", LOW_CONFIDENCE_MAPPING: "部分字段置信度较低",
      CONFIRM_REVENUE_FIELD: "收入口径", CONFIRM_COST_FIELD: "成本口径", CONFIRM_EXPENSE_FIELD: "费用口径"
    };
    return labels[code] || code.replaceAll("_", " ").toLowerCase();
  }

  function updateMappingActionState() {
    const button = el.viewContent.querySelector("[data-action='confirm-mapping']");
    if (!button) return;
    button.disabled = state.loading || !mappingCandidates(state.analysis, "revenue").length || !state.mappingDraft.revenue;
  }

  function mappingConfirmationRequired(a) {
    return a.profile.mappingIssues.some((issue) => issue.severity === "BLOCKING") || (a.profile.mappings.length > 0 && !a.capabilities.hasRevenue);
  }

  function analysisCapabilityNames(a) {
    const capabilities = a?.capabilities || {};
    const names = [];
    if (capabilities.hasRevenue) names.push("销售规模");
    if (capabilities.hasProduct) names.push("产品结构");
    if (capabilities.hasCustomer) names.push("客户结构");
    if (capabilities.grossProfitAvailable) names.push("可比毛利");
    if (capabilities.operatingProfitAvailable) names.push("经营利润");
    if (capabilities.hasDate && Array.isArray(a?.monthly) && a.monthly.length > 1) names.push("期间比较");
    if (Array.isArray(a?.dynamicBreakdowns) && a.dynamicBreakdowns.length) names.push("动态维度");
    return names;
  }

  function isMappingIssue(issue) {
    return issue.severity === "BLOCKING" || /MAPPING|AMBIGU|FIELD|METRIC|REQUIRED|口径|字段|候选/i.test(`${issue.code} ${issue.message}`);
  }

  function canonicalField(value) {
    const normalized = String(value || "").replace(/[^a-zA-Z]/g, "").toLowerCase();
    const aliases = {
      revenue: "revenue", sales: "revenue", amount: "revenue",
      cost: "cost", totalcost: "cost", directcost: "cost",
      expense: "expense", expenses: "expense", periodexpense: "expense", periodexpenses: "expense",
      product: "product", productname: "product", sku: "product",
      customer: "customer", customername: "customer", clientsystem: "customer",
      quantity: "quantity", qty: "quantity", volume: "quantity",
      date: "date", businessdate: "date", period: "date"
    };
    return aliases[normalized] || normalized;
  }

  function renderProjectHome(a) {
    const s = a.summary;
    const capabilities = a.capabilities;
    const hasSavedDesktopProject = IS_DESKTOP && savedDesktopSourceCount() > 0;
    const mappingBlocked = mappingConfirmationRequired(a);
    const hasRevenue = capabilities.hasRevenue && !mappingBlocked;
    const hasGross = capabilities.grossProfitAvailable && !mappingBlocked;
    const hasOperating = capabilities.operatingProfitAvailable && !mappingBlocked;
    const projectLabel = state.sourceNames[0]?.replace(/\.[^.]+$/, "") || "本地经营分析";
    return `<div class="desktop-page project-home-page">
      <section class="project-welcome">
        <div><span class="eyebrow">PERSONAL ANALYSIS STUDIO</span><h2>今天从哪项工作开始？</h2><p>打开一个项目、导入新的工作簿，或继续查看当前分析。桌面端只保留与你当前任务有关的内容。</p></div>
        <button class="button button-primary project-primary-action" type="button" data-action="import-data"><svg><use href="#i-upload"></use></svg><span>${hasSavedDesktopProject ? "追加数据源" : "新建经营分析"}</span></button>
      </section>
      <section class="quick-task-grid" aria-label="常用任务">
        <button type="button" class="quick-task" data-action="import-data"><span class="quick-task-icon">＋</span><strong>${hasSavedDesktopProject ? "追加工作簿" : "导入工作簿"}</strong><small>${hasSavedDesktopProject ? "保留本机已有文件并重新聚合" : "合并 Excel、XLS 或 CSV"}</small></button>
        <button type="button" class="quick-task" data-open-view="overview"><span class="quick-task-icon">↗</span><strong>继续当前分析</strong><small>查看收入、利润与风险</small></button>
        <button type="button" class="quick-task" data-action="open-settings"><span class="quick-task-icon">✦</span><strong>配置 AI 模型</strong><small>DeepSeek 或阿里云百炼</small></button>
        <button type="button" class="quick-task" data-action="export-docx" ${hasRevenue ? "" : "disabled"}><span class="quick-task-icon">W</span><strong>${hasRevenue ? "生成经营报告" : "报告口径待确认"}</strong><small>${hasRevenue ? "导出可编辑 Word 文档" : "确认收入字段后开放"}</small></button>
      </section>
      <section class="recent-project-panel">
        <header><div><span class="eyebrow">当前项目</span><h3 title="${attr(projectLabel)}">${esc(projectLabel)}</h3></div><span class="project-state">本地项目</span></header>
        <div class="project-metrics"><div><span>营业收入</span><strong>${hasRevenue ? esc(formatCurrency(s.revenue)) : "待确认"}</strong></div><div><span>${hasOperating ? "经营利润" : hasGross ? "毛利润" : "利润能力"}</span><strong class="${hasOperating && s.operatingProfit < 0 ? "negative-text" : ""}">${hasOperating ? esc(formatCurrency(s.operatingProfit)) : hasGross ? esc(formatCurrency(s.grossProfit)) : "未开放"}</strong></div><div><span>有效记录</span><strong>${formatInteger(a.quality.validRows)} 行</strong></div><div><span>最近计算</span><strong>${esc(formatDateTime(a.generatedAt))}</strong></div></div>
        <footer><span title="${attr(state.sourceNames.join("、"))}">${esc(truncateNames(state.sourceNames))}</span><button type="button" class="text-button" data-open-view="overview">打开项目 →</button></footer>
      </section>
    </div>`;
  }

  function renderDesktopSourceRelation(source) {
    if (!IS_DESKTOP) return "";
    const periods = Array.isArray(source.periods) ? source.periods : [];
    const periodLabel = periods.length ? periods.map((period) => period.replace(/^(\d{4})-(\d{2})$/, "$1年$2月")).join("、") : "期间待识别";
    const note = source.relationshipNote || (source.seriesKey ? `已归入 ${source.seriesKey}` : "将与同口径文件共同参与趋势分析");
    return `<span class="source-relation ${source.needsAiReview ? "review" : "linked"}" title="${attr(note)}"><b>${source.needsAiReview ? "需复核" : periodLabel}</b><em>${source.needsAiReview ? "AI/人工复核月份关系" : esc(note)}</em></span>`;
  }

  function renderDataWorkspace(a) {
    const q = a.quality;
    const score = qualityScore(q);
    const verdict = qualityVerdict(q, a);
    const hasSavedDesktopProject = IS_DESKTOP && savedDesktopSourceCount() > 0;
    const sourceRecords = state.persistedSources.length
      ? state.persistedSources
      : (state.sourceNames.length ? state.sourceNames.map((name, index) => ({ id: String(index), name, size: state.lastUploadFiles?.[index]?.size || 0, rows: 0 })) : Array.from({ length: a.sourceFileCount || 2 }, (_, index) => ({ id: String(index), name: `经营数据${index + 1}.${index ? "csv" : "xlsx"}`, size: 0, rows: 0 })));
    return `<div class="desktop-page data-workspace-page">
      ${renderSemanticContract(a)}
      ${renderMappingConfirmation(a)}
      ${renderRiskNotice(a)}
      <section class="data-drop-card" data-action="import-data" role="button" tabindex="0" aria-label="${hasSavedDesktopProject ? "追加经营数据，保留现有文件" : "导入经营数据"}">
        <span class="data-drop-icon"><svg><use href="#i-upload"></use></svg></span><div><strong>${hasSavedDesktopProject ? "追加 Excel / CSV 到当前项目" : "拖入文件，或选择新的 Excel / CSV"}</strong><p>${hasSavedDesktopProject ? `可一次多选或分批添加；本机已有 ${savedDesktopSourceCount()} 份数据源不会被清空。单次总计最多 500 MB。` : ".xlsx / .csv 单次最多 500 MB；旧版 .xls 最高 64 MB，超过 100 MB 自动进入大文件模式。"}</p></div><span class="button button-primary">${hasSavedDesktopProject ? "追加文件" : "选择文件"}</span>
      </section>
      <div class="data-workspace-grid">
        <section class="surface data-source-list"><div class="section-heading"><div><span class="eyebrow">数据源</span><h2>本次分析文件</h2><p>每份文件都可单独移除，移除后系统会用剩余文件重新计算。</p></div><span class="section-note">${formatInteger(sourceRecords.length)} 份</span></div><ul>${sourceRecords.map((source, index) => `<li><span class="file-type">${esc((source.name.split(".").pop() || "DATA").toUpperCase())}</span><div><strong title="${attr(source.name)}">${esc(source.name)}</strong><small>${source.size ? formatBytes(source.size) + " · " : ""}${source.rows ? formatInteger(source.rows) + " 行 · " : ""}已保存到本机工作区</small>${renderDesktopSourceRelation(source)}</div><span class="source-ready">可用</span>${state.persistedSources.length ? `<button class="source-remove" type="button" data-remove-persisted-source="${attr(source.id)}" aria-label="从工作区删除 ${attr(source.name)}">删除</button>` : state.lastUploadFiles?.[index] ? `<button class="source-remove" type="button" data-remove-source="${index}" aria-label="从分析中移除 ${attr(source.name)}">删除</button>` : ""}</li>`).join("")}</ul></section>
        <section class="surface quality-workbench ${verdict.tone}"><div class="section-heading"><div><span class="eyebrow">数据质量</span><h2>${score} 分 · ${esc(verdict.title)}</h2><p>${esc(verdict.detail)}</p></div>${evidenceTicket(["EV-QUALITY-001"], verdict.tone === "verified" ? "verified" : "warn", "查看口径")}</div><div class="quality-score-line"><i style="width:${score}%"></i></div><div class="quality-facts"><div><span>有效记录</span><strong>${formatInteger(q.validRows)}</strong></div><div><span>缺少日期</span><strong>${formatInteger(q.missingDate)}</strong></div><div><span>缺少产品</span><strong>${formatInteger(q.missingProduct)}</strong></div><div><span>缺少客户</span><strong>${formatInteger(q.missingCustomer)}</strong></div></div><p>${q.warnings?.length ? esc(q.warnings.join("；")) : "字段完整，数值口径已通过基础校验。"}</p></section>
      </div>
      ${renderConclusion("数据源", mappingConfirmationRequired(a) ? "关键字段仍需确认，当前结果不会输出利润判断" : `${formatInteger(q.validRows)} 行数据已进入可复核分析`, mappingConfirmationRequired(a) ? "请选择业务含义最准确的收入、成本和费用列。确认后程序会重新聚合，不会修改原文件。" : `当前已开放${analysisCapabilityNames(a).join("、") || "基础数据质量检查"}；公式、外链和缺失值风险已单独标记。`, ["EV-QUALITY-001"])}
    </div>`;
  }

  function renderReportWorkspace(input) {
    const a = desktopScopedAnalysis(input);
    const s = a.summary;
    const capabilities = a.capabilities;
    const mappingBlocked = mappingConfirmationRequired(a);
    const hasRevenue = capabilities.hasRevenue && !mappingBlocked;
    const hasOperating = capabilities.operatingProfitAvailable && hasRevenue;
    const hasGross = capabilities.grossProfitAvailable && hasRevenue;
    const losing = hasOperating && s.operatingProfit < 0;
    const headline = !hasRevenue
      ? "关键收入口径待确认，报告暂不可生成"
      : hasOperating
      ? (losing ? "收入尚未转化为经营利润" : "经营结果保持正向，需守住利润底线")
      : hasGross ? "当前报告覆盖销售与毛利，经营利润仍待费用口径" : "当前报告仅覆盖销售表现，利润口径尚未建立";
    const lead = !hasRevenue
      ? "当前不会显示营业收入、利润或趋势金额，避免把未确认或部分来源的数据写入报告。"
      : hasOperating
      ? `本期营业收入 ${formatCurrency(s.revenue)}，经营利润 ${formatCurrency(s.operatingProfit)}，经营利润率 ${formatPercent(s.operatingMargin)}。`
      : hasGross ? `本期营业收入 ${formatCurrency(s.revenue)}，毛利润 ${formatCurrency(s.grossProfit)}，毛利率 ${formatPercent(s.grossMargin)}；未识别期间费用，因此不输出经营利润。`
        : `本期营业收入 ${formatCurrency(s.revenue)}；成本字段尚未确认，因此不输出毛利率或利润判断。`;
    const action = !hasRevenue
      ? "回到经营总览或数据源页，确认收入等关键业务字段后再生成报告。"
      : hasOperating
      ? (losing ? "优先拆解亏损业务的直接成本与费用结构，并给改善动作设置可量化目标。" : "将当前经营利润率写入报价与费用预算规则，并持续复核增长业务的履约成本。")
      : "回到数据源页确认成本与费用字段；在口径完整前，只使用销售规模和结构结论。";
    const gatedCapabilities = { ...capabilities, hasRevenue, grossProfitAvailable: hasGross, operatingProfitAvailable: hasOperating };
    const activeSection = REPORT_SECTIONS.some(([id]) => id === state.reportSection) ? state.reportSection : "overview";
    const context = { s, hasRevenue, hasGross, hasOperating, losing, headline, lead, action, gatedCapabilities };
    return `<div class="desktop-page report-workspace-page">
      <aside class="report-outline"><span class="eyebrow">报告目录</span><ol>${REPORT_SECTIONS.map(([id, label], index) => `<li class="${id === activeSection ? "active" : ""}"><button type="button" data-report-section="${attr(id)}" aria-current="${id === activeSection ? "page" : "false"}"><span>${String(index + 1).padStart(2, "0")}</span><strong>${esc(label)}</strong><svg><use href="#i-chevron"></use></svg></button></li>`).join("")}</ol><div class="report-export-stack"><button type="button" class="button button-primary" data-action="export-pdf" ${!hasRevenue ? "disabled" : ""}><span class="file-badge pdf">PDF</span>${hasRevenue ? "选择范围导出" : "口径待确认"}</button><button type="button" class="button button-secondary" data-action="export-docx" ${!hasRevenue ? "disabled" : ""}><span class="file-badge docx">DOCX</span>Word 报告</button><button type="button" class="button button-secondary" data-action="export-xlsx" ${!hasRevenue ? "disabled" : ""}><span class="file-badge xlsx">XLSX</span>分析底稿</button></div></aside>
      <article class="report-paper report-section-preview ${!hasRevenue ? "blocked" : ""}" tabindex="-1" aria-label="${attr(REPORT_SECTIONS.find(([id]) => id === activeSection)?.[1] || "经营报告")}预览"><header><span>DataMaster · 经营分析报告</span><strong>${esc(formatDateTime(a.generatedAt))}</strong></header>${renderReportPreviewSection(a, activeSection, context)}<footer>不可用指标不会按 0 填充；金额由本地确定性计算，AI 仅参与解释与建议。</footer></article>
    </div>`;
  }

  function renderReportPreviewSection(a, section, context) {
    const { s, hasRevenue, hasGross, hasOperating, losing, headline, lead, action, gatedCapabilities } = context;
    if (!hasRevenue) return `<div class="report-blocked-preview"><span>!</span><div><strong>报告正在等待收入口径</strong><p>完成关键字段确认后，所有目录章节和导出能力会自动恢复。</p></div></div>`;
    if (section === "metrics") return `<span class="report-chapter">02 · 核心指标与趋势</span><h2>经营结果如何变化</h2><div class="report-kpi-row"><div><span>收入</span><strong>${esc(formatCurrency(s.revenue))}</strong></div><div><span>毛利率</span><strong>${hasGross ? esc(formatPercent(s.grossMargin)) : "不可用"}</strong></div><div><span>经营利润</span><strong class="${losing ? "negative-text" : ""}">${hasOperating ? esc(formatCurrency(s.operatingProfit)) : "不可用"}</strong></div></div><div class="report-chart-preview">${renderTrendChart(a.monthly, gatedCapabilities)}</div>`;
    if (section === "portfolio") {
      const products = sortedByRevenue(a.products).slice(0, 6);
      const customers = sortedByRevenue(a.customers).slice(0, 6);
      return `<span class="report-chapter">03 · 产品与客户</span><h2>增长来自哪里，风险集中在哪里</h2><div class="report-dual-list"><section><h3>产品销售额 Top 6</h3><ol>${products.map((item, index) => `<li><span>${String(index + 1).padStart(2, "0")}</span><strong>${esc(item.name)}</strong><b>${esc(formatCurrency(item.revenue))}</b></li>`).join("") || "<li>暂无产品维度</li>"}</ol></section><section><h3>客户销售额 Top 6</h3><ol>${customers.map((item, index) => `<li><span>${String(index + 1).padStart(2, "0")}</span><strong>${esc(item.name)}</strong><b>${esc(formatCurrency(item.revenue))}</b></li>`).join("") || "<li>暂无客户维度</li>"}</ol></section></div>`;
    }
    if (section === "organization") {
      const feedback = renderExplorerFeedback("salesGroupSummary", { loadingTitle: "正在准备销售组织利润", emptyTitle: "暂无销售组织数据", emptyText: "报告仍可导出其他章节。" });
      const items = state.explorers.get("salesGroupSummary")?.data?.items || [];
      return `<span class="report-chapter">04 · 销售组织利润</span><h2>阿米巴口径下，利润由哪些销售组贡献</h2><p class="report-lead">默认采用调拨成本，展示销售组销售额、利润和利润率。</p>${feedback || `<div class="data-table-wrap"><table class="data-table"><thead><tr><th>销售组</th><th>销售额</th><th>调拨成本</th><th>利润</th><th>利润率</th></tr></thead><tbody>${items.slice(0, 20).map((item) => { const displayedCost = salesGroupCost(item, "transferCost"); return `<tr><td><strong>${esc(item.name)}</strong></td><td>${esc(formatCurrencyExact(item.revenue))}</td><td>${displayedCost === null ? "—" : esc(formatCurrencyExact(displayedCost))}</td><td class="${item.grossProfit !== null && item.grossProfit < 0 ? "negative-text" : ""}">${item.grossProfit === null ? "—" : esc(formatCurrencyExact(item.grossProfit))}</td><td>${item.grossMargin === null ? "—" : esc(formatPercent(item.grossMargin))}</td></tr>`; }).join("")}</tbody></table></div>`}`;
    }
    if (section === "actions") {
      const insights = contextualInsights("profit");
      return `<span class="report-chapter">05 · 利润改善建议</span><h2>${esc(action)}</h2><div class="report-action-list">${insights.slice(0, 6).map((item, index) => `<article><span>${String(index + 1).padStart(2, "0")}</span><div><h3>${esc(item.title)}</h3><p>${esc(item.action || item.finding)}</p></div></article>`).join("")}</div>`;
    }
    if (section === "evidence") {
      const evidence = [...state.evidence.values()].slice(0, 30);
      return `<span class="report-chapter">06 · 证据附录</span><h2>结论如何回到计算口径</h2><p class="report-lead">共登记 ${formatInteger(state.evidence.size)} 条本地计算证据；下方为报告引用索引。</p><div class="report-evidence-list">${evidence.map((item) => `<article><span>${esc(item.id)}</span><strong>${esc(item.title)}</strong><p>${esc(item.logic)}</p><b>${esc(item.value)}</b></article>`).join("")}</div>`;
    }
    return `<span class="report-chapter">01 · 经营结论</span><h2>${esc(headline)}</h2><p class="report-lead">${esc(lead)}</p><div class="report-kpi-row"><div><span>收入</span><strong>${esc(formatCurrency(s.revenue))}</strong></div><div><span>毛利率</span><strong>${hasGross ? esc(formatPercent(s.grossMargin)) : "不可用"}</strong></div><div><span>经营利润</span><strong class="${losing ? "negative-text" : ""}">${hasOperating ? esc(formatCurrency(s.operatingProfit)) : "不可用"}</strong></div></div><h3>首要行动</h3><p>${esc(action)}</p><div class="report-cover-note"><span>报告覆盖</span><strong>经营 · 产品 · 客户 · 利润 · 销售组织 · 证据</strong><p>使用左侧真实目录切换预览；PDF 可按章节选择范围。</p></div>`;
  }

  function renderInspector() {
    if (!state.analysis) return;
    const a = desktopScopedAnalysis(state.analysis);
    if (el.inspectorSummaryContent) el.inspectorSummaryContent.innerHTML = renderInspectorSummary(a);
    if (el.inspectorEvidenceContent) el.inspectorEvidenceContent.innerHTML = renderInspectorEvidence();
    if (el.inspectorAiContent) {
      el.inspectorAiContent.innerHTML = renderAiChat(a);
      const providerSelect = el.inspectorAiContent.querySelector("[data-insight-provider]");
      if (providerSelect) providerSelect.value = state.activeProviderId;
      const conversation = el.inspectorAiContent.querySelector("[data-chat-messages]");
      if (conversation) conversation.scrollTop = conversation.scrollHeight;
    }
    selectInspectorTab(state.inspectorTab);
    setInspectorCollapsed(state.inspectorCollapsed);
  }

  function aiContextKey() {
    return state.activeWorkspace === "analysis" ? state.activeView : state.activeWorkspace;
  }

  function currentAiChat() {
    const key = aiContextKey();
    if (!state.aiChats.has(key)) state.aiChats.set(key, { messages: [], suggestions: [], loading: false, error: "" });
    return state.aiChats.get(key);
  }

  function renderAiChat(a) {
    const chat = currentAiChat();
    const contextLabel = state.activeWorkspace === "analysis" ? VIEW_META[state.activeView]?.[0] || "当前分析" : WORKSPACE_META[state.activeWorkspace]?.[0] || "当前页面";
    const mappingPending = mappingConfirmationRequired(a);
    const activeProvider = state.providers.find((item) => item.id === state.activeProviderId);
    const providerOptions = state.providers.map((provider) => `<option value="${attr(provider.id)}">${esc(provider.name)}${provider.configured ? " · 已配置" : ""}</option>`).join("");
    const quickPrompts = aiQuickPrompts(aiContextKey());
    const messages = chat.messages.map((message) => `<article class="chat-message ${message.role}"><span>${message.role === "user" ? "你" : "AI"}</span><div>${formatChatText(message.content)}${message.evidence?.length ? `<div class="chat-evidence">${message.evidence.map((item) => `<button type="button" data-evidence="${attr(item.id)}"><span>${esc(item.display || item.id)}</span><small>${esc(item.id)}</small></button>`).join("")}</div>` : ""}</div></article>`).join("");
    const suggestionChips = chat.suggestions.length ? `<div class="chat-adjustments"><span>建议调整（点击后应用）</span>${chat.suggestions.map((suggestion, index) => `<button type="button" data-ai-suggestion="${index}" title="${suggestion.action === "reviewSchema" ? "打开字段口径复核，不会直接修改数据" : "此操作只调整筛选或展示口径，不会改写原始数字"}"><svg><use href="#i-spark"></use></svg>${esc(suggestion.label)}</button>`).join("")}</div>` : "";
    const guardrail = IS_DESKTOP
      ? "AI 可建议筛选、展示口径并发起字段复核；重新分析仍需你确认，原始数据不会被对话改写。"
      : "AI 可建议筛选和展示口径；原始数据、金额计算与成本口径不会被对话改写。";
    return `<div class="ai-chat-shell"><header class="ai-chat-header"><span class="inspector-ai-mark"><svg><use href="#i-spark"></use></svg></span><div><strong>${esc(contextLabel)} · AI 分析搭档</strong><small>${activeProvider?.configured ? `${esc(activeProvider.name)} · ${esc(activeProvider.model)}` : "模型尚未配置"}</small></div>${chat.messages.length ? `<button type="button" data-action="clear-chat">清空</button>` : ""}</header><label class="inspector-field chat-provider"><span>对话平台</span><select data-insight-provider ${mappingPending ? "disabled" : ""}>${providerOptions}</select></label><div class="chat-message-list" data-chat-messages aria-live="polite">${messages || `<div class="chat-welcome"><strong>直接问当前页面</strong><p>我会带上本页筛选、指标与汇总结果。涉及调整时只给出可点击建议，由你确认后应用。</p><div>${quickPrompts.map((prompt) => `<button type="button" data-chat-prompt="${attr(prompt)}">${esc(prompt)}</button>`).join("")}</div></div>`}${chat.loading ? `<article class="chat-message assistant loading"><span>AI</span><div><i></i><i></i><i></i><small>正在结合当前分析…</small></div></article>` : ""}${chat.error ? `<div class="chat-error" role="alert"><strong>这次对话没有完成</strong><p>${esc(chat.error)}</p></div>` : ""}${suggestionChips}</div><form class="ai-chat-form" data-ai-chat-form><label><span class="sr-only">向 AI 询问当前分析</span><textarea data-ai-chat-input rows="3" maxlength="1200" placeholder="例如：只看华东销售组后，哪些产品拖累利润？" ${mappingPending || chat.loading ? "disabled" : ""}></textarea></label><button type="submit" aria-label="发送问题" ${mappingPending || chat.loading ? "disabled" : ""}><svg><use href="#i-send"></use></svg></button></form><p class="chat-guardrail">${esc(guardrail)}</p><details class="legacy-insight-tools"><summary>页面深度解读</summary><button class="button button-secondary" type="button" data-action="generate-insights" ${state.insightsLoading || mappingPending ? "disabled" : ""}>${state.insightsLoading ? `<span class="spinner"></span><span>正在生成</span>` : `<svg><use href="#i-spark"></use></svg><span>${isAiInsight(a) ? "重新生成结构化解读" : "生成结构化解读"}</span>`}</button></details></div>`;
  }

  function aiQuickPrompts(context) {
    let prompts;
    if (context === "product") prompts = ["哪些分类销售额高但利润率低？", "帮我从数量与单价拆解产品变化"];
    else if (context === "customer") prompts = ["哪些客户的产品组合正在亏损？", "按渠道找出最需要关注的客户"];
    else if (context === "profit") prompts = ["当前最可执行的扭亏动作是什么？", "成本和费用哪一项更值得优先控制？"];
    else if (context === "salesGroup") prompts = ["哪个销售组的调拨成本利润最弱？", "帮我下钻亏损销售组的客户和产品"];
    else prompts = ["解释本期收入与利润变化", "建议我下一步下钻哪个维度"];
    return IS_DESKTOP ? [...prompts, "这次分析结果不对，帮我检查字段口径"] : prompts;
  }

  function formatChatText(value) {
    return esc(String(value || "")).replace(/\n/g, "<br>");
  }

  async function sendAiChat(rawMessage) {
    const message = String(rawMessage || "").trim();
    if (!message || !state.analysis || mappingConfirmationRequired(state.analysis)) return;
    const provider = state.providers.find((item) => item.id === state.activeProviderId);
    if (!provider?.configured) {
      toast("先连接 AI 模型", "配置 DeepSeek 或阿里云百炼后即可在页面内对话。", "error");
      openSettings();
      return;
    }
    const key = aiContextKey();
    const chat = currentAiChat();
    if (chat.loading) return;
    const history = chat.messages.slice(-12).map(({ role, content }) => ({ role, content }));
    chat.messages.push({ role: "user", content: message });
    chat.loading = true;
    chat.error = "";
    chat.suggestions = [];
    const token = ++state.chatRequestToken;
    chat.requestToken = token;
    const analysisId = state.analysis.id;
    const context = currentAnalysisContext(key);
    renderInspector();
    try {
      const result = await apiFetch(`/api/analysis/${encodeURIComponent(analysisId)}/chat`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          message,
          view: key,
          providerId: state.activeProviderId,
          filters: context.filters || {},
          history,
          context,
          ...(IS_DESKTOP ? { allowSchemaReview: true } : {})
        })
      }, 90000);
      const stored = state.aiChats.get(key);
      if (state.analysis?.id !== analysisId || stored?.requestToken !== token) return;
      const reply = String(result?.message || result?.answer || result?.reply?.content || result?.reply || result?.content || "").trim();
      if (!reply) throw new Error("AI 没有返回可显示的分析内容。");
      const rawEvidence = Array.isArray(result?.evidence) ? result.evidence : Array.isArray(result?.reply?.evidence) ? result.reply.evidence : [];
      const evidence = rawEvidence.map((item) => ({ id: String(item.id || item.evidenceId || ""), display: String(item.display || item.value || "") })).filter((item) => item.id);
      evidence.forEach((item) => {
        if (!state.evidence.has(item.id)) state.evidence.set(item.id, { id: item.id, title: "AI 对话引用依据", logic: "AI 只引用本地确定性计算证据", value: item.display || "已挂接", status: "verified", source: "本地经营分析" });
      });
      stored.messages.push({ role: "assistant", content: reply, evidence });
      stored.suggestions = normalizeAiSuggestions(result?.suggestedActions || result?.suggestions || result?.actions || result?.reply?.suggestions || []);
      stored.error = "";
    } catch (error) {
      const stored = state.aiChats.get(key);
      if (state.analysis?.id !== analysisId || stored?.requestToken !== token) return;
      stored.error = friendlyError(error);
    } finally {
      const stored = state.aiChats.get(key);
      if (stored?.requestToken === token) stored.loading = false;
      if (aiContextKey() === key) renderInspector();
    }
  }

  function currentAnalysisContext(key = aiContextKey()) {
    const period = periodRequestPayload();
    if (key === "product") return { filters: compactFilters(state.productControls.filters), metric: state.productControls.metric, quantityMetric: state.productControls.quantityMetric, ...period };
    if (key === "customer") {
      const selectedCustomers = [...new Set((state.customerControls.selectedCustomers || []).map(String).filter(Boolean))];
      const filters = compactFilters(state.customerControls.filters);
      if (selectedCustomers.length) filters[DIMENSIONS.customer] = selectedCustomers;
      return { filters, selectedCustomer: selectedCustomers[0] || state.customerControls.selectedCustomer, selectedChannel: state.customerControls.selectedChannel, ...period };
    }
    if (key === "salesGroup") return { filters: compactFilters(state.salesGroupControls.filters), groupBy: state.salesGroupControls.groupBy, selectedGroup: state.salesGroupControls.selectedGroup, costMetric: state.salesGroupControls.costMetric, ...period };
    return { filters: {}, metric: key === "profit" ? "grossProfit" : "revenue", ...period };
  }

  function clearAiChat() {
    state.aiChats.delete(aiContextKey());
    renderInspector();
    window.setTimeout(() => el.inspectorAiContent?.querySelector("[data-ai-chat-input]")?.focus(), 0);
  }

  function normalizeAiSuggestions(input) {
    const allowed = new Set(["setFilter", "setMetric", "setQuantityMetric", "setCostMetric", "openDrilldown"]);
    if (IS_DESKTOP) allowed.add("reviewSchema");
    return (Array.isArray(input) ? input : []).map((suggestion) => {
      const action = String(suggestion.action || suggestion.type || "");
      if (!allowed.has(action)) return null;
      return {
        action,
        scope: String(suggestion.scope || suggestion.view || aiContextKey()),
        field: String(suggestion.field || suggestion.dimension || suggestion.key || ""),
        value: String(suggestion.value ?? suggestion.values?.[0] ?? suggestion.metric ?? suggestion.target ?? ""),
        label: String(suggestion.label || suggestion.title || suggestion.description || aiSuggestionLabel(action, suggestion))
      };
    }).filter(Boolean).slice(0, 5);
  }

  function aiSuggestionLabel(action, suggestion) {
    if (action === "reviewSchema") return "复核字段与月份口径";
    if (action === "setFilter") return `筛选 ${suggestion.field || suggestion.dimension}：${suggestion.value ?? suggestion.values?.[0] ?? "指定值"}`;
    if (action === "setMetric") return `切换指标：${suggestion.value || suggestion.metric}`;
    if (action === "setQuantityMetric") return `数量口径：${suggestion.value || suggestion.metric}`;
    if (action === "setCostMetric") return `成本口径：${suggestion.value || suggestion.metric}`;
    return `打开${suggestion.value || suggestion.target || "下钻分析"}`;
  }

  function applyAiSuggestion(index) {
    const chat = currentAiChat();
    const suggestion = chat.suggestions[index];
    if (!suggestion) return;
    if (IS_DESKTOP && suggestion.action === "reviewSchema") {
      state.forceMappingReview = true;
      state.activeWorkspace = "data";
      state.schemaMessage = "AI 建议复核当前字段与月份关系；尚未修改任何数据，请确认后再重新计算。";
      chat.suggestions.splice(index, 1);
      renderActiveView();
      toast("已打开字段口径复核", "AI 没有直接改动数据；请检查字段，必要时先使用 AI 辅助识别，再由你确认重算。", "success");
      window.setTimeout(() => el.viewContent.querySelector(".mapping-workbench.ai-review")?.scrollIntoView({ block: "start", behavior: "smooth" }), 0);
      return;
    }
    const scope = ["product", "customer", "salesGroup"].includes(suggestion.scope) ? suggestion.scope : aiContextKey();
    let applied = false;
    if (suggestion.action === "setFilter") {
      const definitions = scope === "product" ? PRODUCT_FILTERS : scope === "customer" ? CUSTOMER_FILTERS : SALES_GROUP_FILTERS;
      const definition = definitions.find(([key, , dimension]) => suggestion.field === key || suggestion.field === dimension);
      if (!definition || !suggestion.value) return toast("未应用 AI 建议", "建议中的筛选字段不在当前页面白名单内。", "error");
      const controls = scope === "product" ? state.productControls : scope === "customer" ? state.customerControls : state.salesGroupControls;
      controls.filters[definition[2]] = IS_DESKTOP ? [suggestion.value] : suggestion.value;
      invalidateExplorerScope(scope);
      if (VIEW_META[scope]) switchView(scope, { focus: false });
      else renderActiveView();
      applied = true;
    } else if (suggestion.action === "setMetric") {
      if (!["revenue", "quantity", "unitPrice"].includes(suggestion.value)) return toast("未应用 AI 建议", "AI 返回了不支持的产品指标。", "error");
      state.productControls.metric = suggestion.value;
      switchView("product", { focus: false });
      applied = true;
    } else if (suggestion.action === "setQuantityMetric") {
      if (!["outboundQuantity", "convertedQuantity"].includes(suggestion.value)) return toast("未应用 AI 建议", "AI 返回了不支持的数量口径。", "error");
      state.productControls.quantityMetric = suggestion.value;
      invalidateExplorer("productDetail");
      switchView("product", { focus: false });
      applied = true;
    } else if (suggestion.action === "setCostMetric") {
      if (!["transferCost", "cost"].includes(suggestion.value)) return toast("未应用 AI 建议", "AI 返回了不支持的成本口径。", "error");
      state.salesGroupControls.costMetric = suggestion.value;
      invalidateExplorerScope("salesGroup");
      switchView("salesGroup", { focus: false });
      applied = true;
    } else if (suggestion.action === "openDrilldown") {
      const normalizedDimension = suggestion.field.toLowerCase();
      const dimensionView = ["product", DIMENSIONS.product.toLowerCase()].includes(normalizedDimension)
        ? "product"
        : ["customer", DIMENSIONS.customer.toLowerCase()].includes(normalizedDimension)
          ? "customer"
          : ["salesgroup", "sales_group", DIMENSIONS.salesGroup.toLowerCase()].includes(normalizedDimension) ? "salesGroup" : "";
      const targetView = VIEW_META[suggestion.value] ? suggestion.value : dimensionView;
      if (targetView) {
        switchView(targetView, { focus: false });
        applied = true;
      }
    }
    if (!applied) return toast("未应用 AI 建议", "这个建议无法映射到当前页面的安全操作。", "error");
    chat.suggestions.splice(index, 1);
    toast("已应用 AI 建议", "只调整了页面筛选或展示口径，原始数据未修改。", "success");
    state.inspectorTab = "ai";
    renderInspector();
  }

  function renderInspectorSummary(a) {
    const s = a.summary;
    const mappingBlocked = mappingConfirmationRequired(a);
    const hasRevenue = a.capabilities.hasRevenue && !mappingBlocked;
    const hasOperating = a.capabilities.operatingProfitAvailable && hasRevenue;
    const hasGross = a.capabilities.grossProfitAvailable && hasRevenue;
    let kicker = "当前项目";
    let title = "本地经营分析";
    let description = `${formatInteger(a.quality.validRows)} 行有效记录，数据质量 ${qualityScore(a.quality)} 分。`;
    let evidenceIds = hasRevenue ? ["EV-SUM-REV"] : ["EV-QUALITY-001"];
    if (state.activeWorkspace === "analysis") {
      if (state.activeView === "overview") {
        kicker = "经营结论";
        if (!hasRevenue) {
          title = "收入口径待确认";
          description = "金额、利润与趋势暂不显示；请先完成关键业务字段确认。";
          evidenceIds = ["EV-QUALITY-001"];
        } else if (hasOperating) {
          title = s.operatingProfit < 0 ? `当前仍有 ${formatCurrency(Math.abs(s.operatingProfit))} 亏损缺口` : `本期实现 ${formatCurrency(s.operatingProfit)} 经营利润`;
          const rates = operatingComparableRates(a);
          description = `收入 ${formatCurrency(s.revenue)}，毛利率 ${formatPercent(s.grossMargin)}，可比费用率 ${formatRate(rates.expenseRate)}。`;
          evidenceIds = ["EV-SUM-REV", "EV-SUM-OP"];
        } else if (hasGross) {
          title = `本期毛利润 ${formatCurrency(s.grossProfit)}`;
          description = `毛利率 ${formatPercent(s.grossMargin)}；缺少费用口径，未计算经营利润。`;
          evidenceIds = ["EV-SUM-REV", "EV-SUM-GP"];
        } else {
          title = `本期销售规模 ${formatCurrency(s.revenue)}`;
          description = "成本口径尚未建立，当前只展示销售结构，不判断盈利。";
        }
      } else if (state.activeView === "product") {
        const profitKey = hasOperating ? "operatingProfit" : hasGross ? "grossProfit" : "";
        const lowestProfit = profitKey ? [...a.products]
          .filter((item) => !isProfitAdjustmentProduct(item.name))
          .filter((item) => item[profitKey] !== null && Number.isFinite(item[profitKey]))
          .sort((x, y) => x[profitKey] - y[profitKey])[0] : null;
        const worst = lowestProfit && lowestProfit[profitKey] < 0 ? lowestProfit : null;
        const top = sortedByRevenue(a.products)[0];
        kicker = "产品焦点";
        title = worst ? `${worst.name}需要优先复核` : top ? `${top.name}销售贡献最高` : "等待产品维度";
        description = worst
          ? profitDescription(worst, hasOperating)
          : top
            ? hasGross
              ? `收入 ${formatCurrency(top.revenue)}；${profitDescription(top, hasOperating)}`
              : `收入 ${formatCurrency(top.revenue)}；利润字段不可用，暂不评价盈利质量。`
            : "导入包含产品字段的明细后显示。";
        evidenceIds = (worst || top) ? [productEvidence(a, worst || top)] : evidenceIds;
      } else if (state.activeView === "customer") {
        const scopedCustomerEntry = state.explorers.get("customerSummary");
        const scopedCustomers = scopedCustomerEntry?.status === "success" ? scopedCustomerEntry.data.items : null;
        const top = scopedCustomers ? [...scopedCustomers].sort((x, y) => numberValue(y.revenue) - numberValue(x.revenue))[0] : sortedByRevenue(a.customers)[0];
        const scopedRevenue = scopedCustomers ? numberValue(scopedCustomerEntry.data.totals?.revenue) : numberValue(s.revenue);
        kicker = "客户焦点";
        title = top ? `${top.name}是当前第一大客户` : "等待客户维度";
        description = top ? `当前筛选内收入贡献 ${formatPercent(top.revenue / Math.max(scopedRevenue, 1))}${hasOperating && !scopedCustomers ? `，经营利润率 ${formatPercent(top.operatingMargin)}` : hasGross ? `，毛利率 ${top.grossMargin === null ? "不可用" : formatPercent(top.grossMargin)}` : "；利润口径尚不可用"}。` : "调整客户价值筛选或导入包含客户字段的明细后显示。";
        evidenceIds = top ? [scopedCustomers ? "EV-SUM-GP" : customerEvidence(a, top)] : evidenceIds;
      } else if (state.activeView === "salesGroup") {
        const orgItems = state.explorers.get("salesGroupSummary")?.data?.items || [];
        const selected = state.salesGroupControls.selectedGroup || (state.salesGroupControls.groupBy === DIMENSIONS.salesGroup ? orgItems[0]?.name : "");
        const unit = orgItems.find((item) => item.name === selected) || [...orgItems].sort((x, y) => (y.grossProfit || 0) - (x.grossProfit || 0))[0];
        kicker = "阿米巴利润";
        title = unit ? `${unit.name}利润 ${formatCurrency(unit.grossProfit)}` : "正在核算销售组织利润";
        description = unit ? `销售额 ${formatCurrency(unit.revenue)}，调拨成本口径利润率 ${unit.grossMargin === null ? "不可用" : formatPercent(unit.grossMargin)}。` : "默认按调拨成本，支持区域、部门与销售组筛选。";
        evidenceIds = ["EV-SUM-REV", "EV-SUM-COST", "EV-SUM-GP"];
      } else {
        kicker = hasOperating ? (s.operatingProfit < 0 ? "盈亏平衡" : "利润保护") : "毛利结构";
        title = hasOperating ? (s.operatingProfit < 0 ? `离盈亏平衡还差 ${formatCurrency(Math.abs(s.operatingProfit))}` : `利润安全垫 ${formatCurrency(s.operatingProfit)}`) : `毛利率 ${formatPercent(s.grossMargin)}`;
        if (hasOperating) {
          const rates = operatingComparableRates(a);
          description = `可比成本率 ${formatRate(rates.costRate)}，可比费用率 ${formatRate(rates.expenseRate)}。`;
        } else {
          description = "尚未识别期间费用，因此不显示经营利润或扭亏测算。";
        }
        evidenceIds = hasOperating ? ["EV-SUM-COST", "EV-SUM-EXP", "EV-SUM-OP"] : ["EV-SUM-COST", "EV-SUM-GP"];
      }
    } else if (state.activeWorkspace === "data") {
      const verdict = qualityVerdict(a.quality, a);
      kicker = "数据质量";
      title = `${qualityScore(a.quality)} 分 · ${verdict.title}`;
      description = `${formatInteger(a.quality.validRows)} / ${formatInteger(a.quality.totalRows)} 行已纳入指标。`;
      evidenceIds = ["EV-QUALITY-001"];
    } else if (state.activeWorkspace === "report") {
      kicker = "报告状态";
      title = hasRevenue ? "经营报告已准备好" : "报告正在等待收入口径";
      description = hasRevenue ? "当前报告包含经营结论、关键指标、趋势图、行动建议和证据附录。" : "完成关键字段确认前，不会展示或导出部分金额。";
    }
    const summaryProfitNegative = hasOperating ? s.operatingProfit < 0 : hasGross ? s.grossProfit < 0 : false;
    return `<div class="inspector-callout"><span class="eyebrow">${esc(kicker)}</span><h3>${esc(title)}</h3><p>${esc(description)}</p>${evidenceTicket(evidenceIds, summaryProfitNegative ? "warn" : "verified", "查看计算依据")}</div><dl class="inspector-facts"><div><dt>营业收入</dt><dd>${hasRevenue ? esc(formatCurrency(s.revenue)) : "待确认"}</dd></div><div><dt>${hasOperating ? "经营利润" : hasGross ? "毛利润" : "利润指标"}</dt><dd class="${summaryProfitNegative ? "negative-text" : ""}">${hasOperating ? esc(formatCurrency(s.operatingProfit)) : hasGross ? esc(formatCurrency(s.grossProfit)) : "不可用"}</dd></div>${IS_DESKTOP ? `<div><dt>分析期间</dt><dd title="${attr(periodScopeLabel(a))}">${esc(periodScopeLabel(a))}</dd></div>` : ""}<div><dt>${IS_DESKTOP ? "项目数据源" : "数据范围"}</dt><dd>${formatInteger(a.sourceFileCount || state.sourceNames.length || 1)} 个文件</dd></div><div><dt>最近计算</dt><dd>${esc(formatDateTime(a.generatedAt))}</dd></div></dl><div class="inspector-actions"><button type="button" data-action="open-settings">AI 设置</button><button type="button" data-action="open-sync">账户同步</button></div>`;
  }

  function renderInspectorEvidence() {
    const ids = state.inspectorEvidenceIds?.length ? state.inspectorEvidenceIds : ["EV-SUM-REV", "EV-SUM-OP"];
    const records = ids.map((id) => state.evidence.get(id)).filter(Boolean);
    return `<div class="inspector-ledger"><header><strong>${formatInteger(records.length)} 条已选证据</strong><small>数值由本地确定性计算生成</small></header>${records.map((record) => `<article><span class="record-id">${esc(record.id)}</span><h3>${esc(record.title)}</h3><strong class="record-value">${esc(record.value)}</strong><p>${esc(record.logic)}</p><small>${esc(record.source)}</small></article>`).join("") || `<div class="inspector-empty"><strong>还没有选择证据</strong><p>点击指标、图表或表格中的“证据”即可在这里核对。</p></div>`}</div>`;
  }

  function selectInspectorTab(tab) {
    const control = document.getElementById(`inspector-${tab}`);
    if (control && "checked" in control) control.checked = true;
    document.querySelectorAll("[name='inspector-tab']").forEach((item) =>
      item.setAttribute("aria-selected", String(item === control)));
  }

  function toggleInspector() {
    setInspectorCollapsed(!state.inspectorCollapsed);
  }

  function setInspectorCollapsed(collapsed) {
    state.inspectorCollapsed = Boolean(collapsed);
    document.querySelector(".desktop-grid")?.classList.toggle("inspector-collapsed", state.inspectorCollapsed);
    el.inspectorToggle?.setAttribute("aria-expanded", String(!state.inspectorCollapsed));
    el.inspectorToggle?.setAttribute("aria-pressed", String(state.inspectorCollapsed));
    el.inspectorToggle?.setAttribute("title", state.inspectorCollapsed ? "展开检查器" : "收起检查器");
  }

  function renderDesktopStatus() {
    if (!state.analysis) {
      if (el.desktopFileStatus) el.desktopFileStatus.textContent = "0 个文件";
      if (el.desktopRowStatus) el.desktopRowStatus.textContent = "0 行数据";
      if (el.desktopComputeStatus) el.desktopComputeStatus.textContent = "等待导入";
      const emptyProvider = state.providers.find((item) => item.id === state.activeProviderId);
      if (el.desktopAiStatus) el.desktopAiStatus.textContent = emptyProvider?.configured ? `${emptyProvider.name} 已连接` : "AI 待配置";
      if (el.desktopSyncStatus) el.desktopSyncStatus.textContent = state.sync.connected ? "账户已同步" : "仅保存在本机";
      return;
    }
    const fileCount = state.persistedSources.length || state.analysis.sourceFileCount || state.sourceNames.length || 1;
    if (el.desktopFileStatus) el.desktopFileStatus.textContent = `${formatInteger(fileCount)} 个文件`;
    if (el.desktopRowStatus) el.desktopRowStatus.textContent = `项目共 ${formatInteger(state.analysis.quality.validRows)} 行有效`;
    if (el.desktopComputeStatus) el.desktopComputeStatus.textContent = state.loading ? "正在计算" : "本地计算完成";
    const provider = state.providers.find((item) => item.id === state.activeProviderId);
    if (el.desktopAiStatus) el.desktopAiStatus.textContent = provider?.configured ? `${provider.name} 已连接` : "AI 待配置";
    if (el.desktopSyncStatus) el.desktopSyncStatus.textContent = state.sync.connected ? "账户已同步" : "仅保存在本机";
  }

  function scopedMonthlySeries(a) {
    if (!IS_DESKTOP) return a.monthly || [];
    const selected = new Set(activePeriodMonths(a));
    const filtered = (a.monthly || []).filter((item) => selected.has(normalizeMonthValue(item.month)));
    if (state.periodControls.mode !== "year") return filtered;
    const groups = new Map();
    filtered.forEach((item) => {
      const year = normalizeMonthValue(item.month).slice(0, 4);
      if (!year) return;
      if (!groups.has(year)) groups.set(year, []);
      groups.get(year).push(item);
    });
    return [...groups.entries()].map(([year, items]) => {
      const revenue = sumPeriodMetric(items, "revenue");
      const cost = sumPeriodMetric(items, "cost");
      const grossProfit = sumPeriodMetric(items, "grossProfit") ?? (revenue !== null && cost !== null ? revenue - cost : null);
      const expenses = sumPeriodMetric(items, "expenses");
      const operatingProfit = sumPeriodMetric(items, "operatingProfit") ?? (grossProfit !== null && expenses !== null ? grossProfit - expenses : null);
      return {
        month: `${year}年`,
        revenue,
        cost,
        grossProfit,
        expenses,
        operatingProfit,
        grossMargin: revenue ? grossProfit / revenue : null,
        operatingMargin: revenue ? operatingProfit / revenue : null,
        quantity: sumPeriodMetric(items, "quantity"),
        grossProfitAvailable: grossProfit !== null,
        operatingProfitAvailable: operatingProfit !== null
      };
    });
  }

  function sumPeriodMetric(monthly, key) {
    const values = monthly.map((item) => nullableNumber(item?.[key])).filter((value) => value !== null);
    return values.length ? values.reduce((sum, value) => sum + value, 0) : null;
  }

  function desktopPeriodCapabilities(a, overviewEntry, defaultPeriod) {
    const base = a.capabilities || {};
    if (overviewEntry?.status !== "success") {
      if (defaultPeriod) return base;
      return {
        ...base,
        grossProfitAvailable: false,
        operatingProfitAvailable: false,
        grossProfitRevenueCoverage: null,
        operatingProfitRevenueCoverage: null,
        grossProfitExcludedRows: 0,
        grossProfitExcludedRevenue: 0,
        operatingProfitExcludedRows: 0,
        operatingProfitExcludedRevenue: 0,
        unavailableReasons: ["正在计算所选期间的利润可比口径"]
      };
    }
    const period = overviewEntry.data?.periodSummary || {};
    const totals = overviewEntry.data?.totals || {};
    const records = nullableNumber(period.records) ?? nullableNumber(totals.count) ?? 0;
    const grossComparableRows = nullableNumber(period.grossComparableRows) ?? nullableNumber(totals.comparableRows);
    const operatingComparableRows = nullableNumber(period.operatingComparableRows);
    const grossExcludedRows = nullableNumber(period.grossProfitExcludedRows)
      ?? nullableNumber(totals.excludedProfitRows) ?? 0;
    const operatingExcludedRows = nullableNumber(period.operatingProfitExcludedRows) ?? 0;
    const revenue = nullableNumber(period.revenue) ?? nullableNumber(totals.revenue);
    const grossComparableRevenue = nullableNumber(period.grossComparableRevenue)
      ?? nullableNumber(totals.comparableRevenue);
    const operatingComparableRevenue = nullableNumber(period.operatingComparableRevenue)
      ?? nullableNumber(totals.operatingComparableRevenue);
    const grossExcludedRevenue = nullableNumber(period.grossProfitExcludedRevenue)
      ?? (revenue !== null && grossComparableRevenue !== null ? Math.abs(revenue) - Math.abs(grossComparableRevenue) : 0);
    const operatingExcludedRevenue = nullableNumber(period.operatingProfitExcludedRevenue)
      ?? (revenue !== null && operatingComparableRevenue !== null ? Math.abs(revenue) - Math.abs(operatingComparableRevenue) : 0);
    const hasRevenue = Boolean(period.revenueAvailable ?? (revenue !== null && records > 0));
    const hasCost = Boolean(period.costAvailable ?? nullableNumber(period.cost) !== null);
    const hasExpenses = Boolean(period.expenseAvailable ?? nullableNumber(period.expense) !== null);
    const hasQuantity = Boolean(period.quantityAvailable
      ?? firstDefined(period.quantity, period.salesQuantity, period.convertedQuantity) !== undefined);
    const grossProfitAvailable = Boolean(period.grossProfitAvailable) && hasRevenue && hasCost;
    const operatingProfitAvailable = Boolean(period.operatingProfitAvailable)
      && grossProfitAvailable && hasExpenses;
    const grossRowDenominator = grossComparableRows === null ? null : grossComparableRows + grossExcludedRows;
    const operatingRowDenominator = operatingComparableRows === null
      ? null : operatingComparableRows + operatingExcludedRows;
    return {
      ...base,
      hasRevenue,
      hasCost,
      hasExpenses,
      hasQuantity,
      grossProfitAvailable,
      operatingProfitAvailable,
      grossProfitCoverage: grossRowDenominator ? grossComparableRows / grossRowDenominator : null,
      operatingProfitCoverage: operatingRowDenominator ? operatingComparableRows / operatingRowDenominator : null,
      grossProfitRevenueCoverage: nullableNumber(period.grossProfitCoverage),
      operatingProfitRevenueCoverage: nullableNumber(period.operatingProfitCoverage),
      grossProfitExcludedRows: grossExcludedRows,
      grossProfitExcludedRevenue: Math.max(0, grossExcludedRevenue),
      operatingProfitExcludedRows: operatingExcludedRows,
      operatingProfitExcludedRevenue: Math.max(0, operatingExcludedRevenue),
      unavailableReasons: []
    };
  }

  function desktopScopedAnalysis(a) {
    if (!IS_DESKTOP) return a;
    const monthly = scopedMonthlySeries(a);
    const overviewEntry = state.explorers.get("periodOverview");
    const defaultPeriod = currentPeriodSignature() === defaultPeriodSignature();
    const totals = overviewEntry?.status === "success" ? overviewEntry.data?.totals || {} : {};
    const periodSummary = overviewEntry?.status === "success" ? overviewEntry.data?.periodSummary || {} : {};
    const monthlyRevenue = sumPeriodMetric(monthly, "revenue");
    const monthlyCost = sumPeriodMetric(monthly, "cost");
    const monthlyGrossProfit = sumPeriodMetric(monthly, "grossProfit");
    const monthlyExpenses = sumPeriodMetric(monthly, "expenses");
    const monthlyOperatingProfit = sumPeriodMetric(monthly, "operatingProfit");
    const revenue = nullableNumber(periodSummary.revenue) ?? nullableNumber(totals.revenue) ?? monthlyRevenue ?? a.summary.revenue;
    const cost = nullableNumber(periodSummary.cost) ?? nullableNumber(totals.cost) ?? monthlyCost ?? a.summary.cost;
    const grossProfit = nullableNumber(periodSummary.grossProfit) ?? nullableNumber(totals.grossProfit) ?? monthlyGrossProfit
      ?? (revenue !== null && cost !== null ? revenue - cost : a.summary.grossProfit);
    const expenses = nullableNumber(periodSummary.expense) ?? nullableNumber(totals.expenses) ?? monthlyExpenses ?? a.summary.expenses;
    const operatingProfit = nullableNumber(periodSummary.operatingProfit) ?? nullableNumber(totals.operatingProfit) ?? monthlyOperatingProfit
      ?? (grossProfit !== null && expenses !== null ? grossProfit - expenses : a.summary.operatingProfit);
    const grossComparableRevenue = nullableNumber(periodSummary.grossComparableRevenue)
      ?? nullableNumber(totals.comparableRevenue) ?? revenue;
    const grossComparableCost = nullableNumber(periodSummary.grossComparableCost)
      ?? nullableNumber(totals.comparableCost) ?? cost;
    const operatingComparableRevenue = nullableNumber(periodSummary.operatingComparableRevenue)
      ?? nullableNumber(totals.operatingComparableRevenue) ?? revenue;
    const operatingComparableCost = nullableNumber(periodSummary.operatingComparableCost)
      ?? nullableNumber(totals.operatingComparableCost) ?? cost;
    const operatingComparableExpenses = nullableNumber(periodSummary.operatingComparableExpense)
      ?? nullableNumber(totals.operatingComparableExpense) ?? expenses;
    const grossMargin = nullableNumber(periodSummary.grossMargin)
      ?? (grossComparableRevenue ? grossProfit / grossComparableRevenue : null);
    const operatingMargin = nullableNumber(periodSummary.operatingMargin)
      ?? (operatingComparableRevenue ? operatingProfit / operatingComparableRevenue : null);
    const summary = {
      ...a.summary,
      revenue,
      cost,
      grossProfit,
      grossMargin,
      expenses,
      operatingProfit,
      operatingMargin,
      grossComparableRevenue,
      grossComparableCost,
      operatingComparableRevenue,
      operatingComparableCost,
      operatingComparableExpenses
    };
    const productEntry = state.explorers.get("productPeriodSummary");
    const customerEntry = state.explorers.get("customerPeriodSummary");
    const scopedRecords = nullableNumber(overviewEntry?.data?.periodSummary?.records)
      ?? nullableNumber(overviewEntry?.data?.totals?.count)
      ?? (defaultPeriod ? a.rowCount : null);
    return {
      ...a,
      rowCount: scopedRecords,
      summary,
      capabilities: desktopPeriodCapabilities(a, overviewEntry, defaultPeriod),
      monthly,
      products: productEntry?.status === "success" ? productEntry.data.items : defaultPeriod ? a.products : [],
      customers: customerEntry?.status === "success" ? customerEntry.data.items : defaultPeriod ? a.customers : []
    };
  }

  function renderOverview(input) {
    const a = desktopScopedAnalysis(input);
    const s = a.summary;
    const capabilities = a.capabilities;
    const mappingBlocked = mappingConfirmationRequired(a);
    const hasRevenue = capabilities.hasRevenue && !mappingBlocked;
    const hasGross = capabilities.grossProfitAvailable && hasRevenue;
    const hasOperating = capabilities.operatingProfitAvailable && hasRevenue;
    const losing = hasOperating && s.operatingProfit < 0;
    const trustedMonthly = hasRevenue ? a.monthly : [];
    const latest = trustedMonthly.at(-1);
    const previous = trustedMonthly.at(-2);
    const revenueChange = latest && previous && previous.revenue ? (latest.revenue - previous.revenue) / Math.abs(previous.revenue) : null;
    const topProduct = hasRevenue ? sortedByRevenue(a.products)[0] : null;
    const productProfitCandidates = a.products.filter((item) => !isProfitAdjustmentProduct(item.name));
    const worstProduct = hasOperating ? [...productProfitCandidates].sort((x, y) => x.operatingProfit - y.operatingProfit)[0] : hasGross ? [...productProfitCandidates].sort((x, y) => x.grossProfit - y.grossProfit)[0] : null;
    const topCustomer = hasRevenue ? sortedByRevenue(a.customers)[0] : null;
    let conclusion;
    let explanation;
    let conclusionEvidence;
    if (!hasRevenue) {
      conclusion = "收入口径尚未确认，当前不显示营业收入或利润结论。";
      explanation = "请先确认本次分析采用的收入字段；在此之前，程序不会把缺失值或部分来源的金额显示为 ¥0。";
      conclusionEvidence = ["EV-QUALITY-001"];
    } else if (hasOperating) {
      conclusion = losing ? `收入达到 ${formatCurrency(s.revenue)}，但经营利润仍为 ${formatCurrency(s.operatingProfit)}。` : `本期实现经营利润 ${formatCurrency(s.operatingProfit)}，收入与利润均保持正向。`;
      explanation = losing ? `毛利率为 ${formatPercent(s.grossMargin)}，期间费用 ${formatCurrency(s.expenses)} 是当前盈亏平衡的主要压力。利润按收入、成本和费用同时有效的可比行计算。` : `经营利润率为 ${formatPercent(s.operatingMargin)}。继续观察高收入业务的盈利质量，并复核指标覆盖率。`;
      conclusionEvidence = ["EV-SUM-REV", "EV-SUM-OP"];
    } else if (hasGross) {
      conclusion = `收入 ${formatCurrency(s.revenue)}，可比口径毛利润 ${formatCurrency(s.grossProfit)}。`;
      explanation = `毛利率 ${formatPercent(s.grossMargin)}，按收入与成本同时有效的可比行计算。当前没有可用期间费用，因此不显示经营利润、利润率或扭亏判断。`;
      conclusionEvidence = ["EV-SUM-REV", "EV-SUM-GP"];
    } else {
      conclusion = `当前确认的收入规模为 ${formatCurrency(s.revenue)}，利润结论仍需成本口径。`;
      explanation = "产品、客户、区域和渠道仍可用于销售结构分析；在成本字段确认前，程序不会把缺失成本当作 0，也不会输出 100% 毛利率。";
      conclusionEvidence = ["EV-SUM-REV"];
    }
    const scopedRecordNote = a.rowCount === null
      ? `${periodScopeLabel(a)} · 正在统计记录数`
      : `${formatInteger(a.rowCount)} 行 · ${periodScopeLabel(a)}`;
    const kpis = [kpi("营业收入", hasRevenue ? formatCurrency(s.revenue) : "待确认", revenueChange === null ? scopedRecordNote : `环比 ${formatSignedPercent(revenueChange)} · ${periodScopeLabel(a)}`, hasRevenue ? "EV-SUM-REV" : "EV-QUALITY-001", "indigo", revenueChange)];
    if (hasGross) kpis.push(kpi("可比口径毛利润", formatCurrency(s.grossProfit), `毛利率 ${formatPercent(s.grossMargin)} · ${coverageLabel(capabilities.grossProfitRevenueCoverage)} 可比收入`, "EV-SUM-GP,EV-SUM-GM", "sky", s.grossMargin));
    if (hasOperating) kpis.push(kpi("经营利润", formatCurrency(s.operatingProfit), `利润率 ${formatPercent(s.operatingMargin)} · ${coverageLabel(capabilities.operatingProfitRevenueCoverage)} 可比收入`, "EV-SUM-OP,EV-SUM-OM", losing ? "coral" : "lavender", s.operatingMargin, losing));
    if (capabilities.hasQuantity) kpis.push(kpi("可用分析维度", formatInteger(a.dynamicBreakdowns.length), "支持销量与结构下钻", "EV-QUALITY-001", "lavender"));
    kpis.push(kpi("数据质量", `${qualityScore(a.quality)} 分`, `项目全量 ${formatInteger(a.quality.validRows)} / ${formatInteger(a.quality.totalRows)} 行有效`, "EV-QUALITY-001", "lavender", qualityScore(a.quality) / 100));
    const fallbackEvidence = hasRevenue ? "EV-SUM-REV" : "EV-QUALITY-001";
    const waitingDimensionText = hasRevenue ? "上传包含对应维度的表格后显示。" : "确认收入口径后再生成规模与贡献判断。";
    const drivers = [
      { title: topProduct ? `${topProduct.name}贡献最高收入` : hasRevenue ? "等待产品维度数据" : "产品贡献正在等待收入口径", text: topProduct ? `贡献 ${formatCurrency(topProduct.revenue)}${hasOperating ? `，经营利润率 ${formatPercent(topProduct.operatingMargin)}` : hasGross ? `，毛利率 ${formatPercent(topProduct.grossMargin)}` : "；当前只评价销售规模"}。` : waitingDimensionText, evidence: topProduct ? productEvidence(a, topProduct) : fallbackEvidence, tone: "" },
      { title: worstProduct ? `${worstProduct.name}需要复核盈利质量` : "利润驱动尚不可判断", text: worstProduct ? `${hasOperating ? `经营利润 ${formatCurrency(worstProduct.operatingProfit)}` : `毛利润 ${formatCurrency(worstProduct.grossProfit)}`}，需核对报价与履约口径。` : hasRevenue ? "成本或费用字段未确认，暂不生成利润拖累判断。" : "确认收入字段后，程序才会继续判断利润拖累项。", evidence: worstProduct ? productEvidence(a, worstProduct) : fallbackEvidence, tone: worstProduct && (hasOperating ? worstProduct.operatingProfit : worstProduct.grossProfit) < 0 ? "bad" : "" },
      { title: topCustomer ? `${topCustomer.name}为第一大客户` : hasRevenue ? "等待客户维度数据" : "客户贡献正在等待收入口径", text: topCustomer ? `贡献总收入的 ${formatPercent(topCustomer.revenue / Math.max(s.revenue, 1))}，需持续关注集中度${hasOperating ? "与服务成本" : ""}。` : waitingDimensionText, evidence: topCustomer ? customerEvidence(a, topCustomer) : fallbackEvidence, tone: topCustomer && topCustomer.revenue / Math.max(s.revenue, 1) > .4 ? "warn" : "" }
    ];
    return `<div class="view-stack">
      ${renderKpis(kpis)}
      <div class="section-grid two-one">
        ${renderTrendCard(trustedMonthly, { ...capabilities, hasRevenue, grossProfitAvailable: hasGross, operatingProfitAvailable: hasOperating })}
        <section class="surface driver-card" aria-labelledby="overviewDriverTitle">
          <div class="section-heading"><div><span class="eyebrow">关键驱动因素</span><h2 id="overviewDriverTitle">什么在影响结果</h2></div></div>
          <ol class="driver-list">${drivers.map((item, index) => `<li><span class="driver-rank ${item.tone}">${String(index + 1).padStart(2, "0")}</span><div><strong>${esc(item.title)}</strong><p>${esc(item.text)}</p>${evidenceTicket(item.evidence.split(","), item.tone ? "warn" : "verified")}</div></li>`).join("")}</ol>
        </section>
      </div>
      ${hasRevenue ? renderDynamicExplorer(a) : ""}
      ${renderConclusion("经营总览", conclusion, explanation, conclusionEvidence)}
    </div>`;
  }

  function resetExplorerState() {
    pauseExplorerRequests({ clear: true });
    workspaceRecoveryError = "";
    state.explorers.clear();
    explorerSearchTimers.forEach((timer) => window.clearTimeout(timer));
    explorerSearchTimers.clear();
    window.clearTimeout(explorerRenderTimer);
    searchCompositionActive = false;
    state.productControls = { filters: {}, metric: "revenue", quantityMetric: IS_DESKTOP ? "convertedQuantity" : "", search: "", page: 0, pageSize: 100 };
    state.customerControls = { filters: {}, selectedCustomer: "", selectedCustomers: [], customerDrillInitialized: false, selectedChannel: "", search: "", lossPage: 0 };
    state.salesGroupControls = { filters: {}, groupBy: DIMENSIONS.salesGroup, selectedGroup: "", costMetric: "transferCost", drillCustomers: [] };
    state.dynamicControls = { page: 0, pageSize: 100, search: "", dimensionFilter: "", drill: null };
    state.periodControls = { mode: "cumulative", years: [], months: [], openKey: "", searches: {} };
    initializePeriodInsights();
    state.openFilterKey = "";
    state.filterSearches = {};
  }

  function pauseExplorerRequests({ clear = false } = {}) {
    const pauseToken = ++state.explorerRequestToken;
    explorerAbortControllers.forEach((controller) => controller.abort());
    explorerAbortControllers.clear();
    window.clearTimeout(explorerRenderTimer);
    if (clear) {
      state.explorers.clear();
      return;
    }
    state.explorers.forEach((entry, slot) => {
      if (entry?.status === "loading") {
        // Keep the slot visible but invalidate the aborted promise token. A recovery
        // failure can then turn this placeholder into a stable, retryable error.
        state.explorers.set(slot, { ...entry, status: "paused", token: pauseToken });
      }
    });
  }

  function failPausedExplorerRequests(error) {
    const message = `分析会话恢复失败：${friendlyError(error)}`;
    state.explorers.forEach((entry, slot) => {
      if (entry?.status === "paused") {
        state.explorers.set(slot, { ...entry, status: "error", error: message, data: null });
      }
    });
    requestExplorerRender();
  }

  function periodSignature(payload = periodRequestPayload()) {
    const mode = ["cumulative", "year", "month"].includes(payload?.periodMode) ? payload.periodMode : "cumulative";
    const years = [...new Set((Array.isArray(payload?.years) ? payload.years : []).map(String).filter(Boolean))].sort();
    const months = [...new Set((Array.isArray(payload?.months) ? payload.months : []).map(normalizeMonthValue).filter(Boolean))].sort();
    return `${mode}|years:${years.join(",") || "*"}|months:${months.join(",") || "*"}`;
  }

  function defaultPeriodSignature() {
    return periodSignature({ periodMode: "cumulative", years: [], months: [] });
  }

  function currentPeriodSignature() {
    return IS_DESKTOP ? periodSignature(periodRequestPayload()) : defaultPeriodSignature();
  }

  function initializePeriodInsights() {
    state.periodInsights.clear();
    const a = state.analysis;
    if (!IS_DESKTOP || !a || String(a.insightSource || "").toUpperCase() !== "AI" || !a.insights?.length) return;
    const signature = a.insightPeriodSignature || defaultPeriodSignature();
    state.periodInsights.set(signature, {
      insights: a.insights,
      providerId: a.insightProviderId || "",
      model: a.insightModel || "",
      generatedAt: a.insightsGeneratedAt || "",
      evidenceRecords: []
    });
  }

  function currentPeriodInsightRecord(analysis = state.analysis) {
    if (!analysis) return null;
    if (!IS_DESKTOP) return String(analysis.insightSource || "").toUpperCase() === "AI"
      ? { insights: analysis.insights || [], providerId: analysis.insightProviderId || "", model: analysis.insightModel || "", generatedAt: analysis.insightsGeneratedAt || "", evidenceRecords: [] }
      : null;
    return state.periodInsights.get(currentPeriodSignature()) || null;
  }

  function periodMatchedInsights(analysis = state.analysis) {
    if (!analysis) return [];
    if (!IS_DESKTOP) return analysis.insights || [];
    const aiRecord = currentPeriodInsightRecord(analysis);
    if (aiRecord) return aiRecord.insights || [];
    const sourceIsAi = String(analysis.insightSource || "").toUpperCase() === "AI";
    return !sourceIsAi && currentPeriodSignature() === defaultPeriodSignature() ? analysis.insights || [] : [];
  }

  function toggleSelectedValue(current, value, checked) {
    const selected = new Set(Array.isArray(current) ? current.map(String) : current ? [String(current)] : []);
    if (checked) selected.add(String(value));
    else selected.delete(String(value));
    return [...selected];
  }

  function applyPeriodControlChange({ preserveInteraction = false } = {}) {
    invalidateAiRequests();
    pauseExplorerRequests({ clear: true });
    state.deepInsights = [];
    state.deepInsightMeta = null;
    state.aiChats.clear();
    state.customerControls.lossPage = 0;
    state.dynamicControls.page = 0;
    buildEvidenceLedger();
    if (preserveInteraction) renderActiveViewPreservingInteraction();
    else renderActiveView();
    toast("分析期间已更新", `${periodScopeLabel()}；所有分析页面与 AI 上下文已切换到同一范围。`, "success");
  }

  function updateOpenFilterSummary(details, selectedValues) {
    const summary = details?.querySelector("[data-filter-summary]");
    if (summary) summary.textContent = selectedValues?.length ? `已选 ${selectedValues.length} 项` : "全部";
  }

  function filterMultiselectOptions(details, value) {
    if (!details) return;
    const query = normalizeFuzzyText(value);
    let visible = 0;
    details.querySelectorAll("[data-option-text]").forEach((option) => {
      const matches = !query || normalizeFuzzyText(option.dataset.optionText).includes(query);
      option.hidden = !matches;
      if (matches) visible += 1;
    });
    details.classList.toggle("has-no-match", visible === 0);
  }

  function scheduleProfitSearch(scope, value, input = null) {
    if (!["product", "customer", "dynamic"].includes(scope)) return;
    const controls = scope === "product" ? state.productControls : scope === "customer" ? state.customerControls : state.dynamicControls;
    controls.search = String(value || "").slice(0, 120);
    if (scope === "product") controls.page = 0;
    if (scope === "customer") {
      controls.selectedCustomer = controls.selectedCustomers?.[0] || "";
      controls.lossPage = 0;
    }
    if (scope === "dynamic") controls.page = 0;
    lastSearchInputAt = Date.now();
    window.clearTimeout(explorerRenderTimer);
    const previousTimer = explorerSearchTimers.get(scope);
    if (previousTimer) window.clearTimeout(previousTimer);
    input?.closest(".profit-search, .inline-explorer-search")?.classList.add("search-pending");
    input?.setAttribute("aria-busy", "true");
    explorerSearchTimers.set(scope, window.setTimeout(() => {
      explorerSearchTimers.delete(scope);
      invalidateExplorerSearchScope(scope);
      ensureActiveExplorers();
    }, 420));
  }

  function invalidateExplorerSearchScope(scope) {
    const slots = scope === "product"
      ? ["productDetail"]
      : scope === "customer"
        ? ["customerValue", "losingCustomers", "customerProducts"]
        : ["dynamicBreakdown"];
    slots.forEach(invalidateExplorer);
  }

  function filterDimensionButtons(value) {
    const rail = el.viewContent.querySelector(".dimension-rail");
    if (!rail) return;
    const query = normalizeFuzzyText(value);
    let visible = 0;
    rail.querySelectorAll("[data-breakdown-id]").forEach((button) => {
      const matches = !query || normalizeFuzzyText(button.dataset.dimensionLabel).includes(query);
      button.hidden = !matches;
      if (matches) visible += 1;
    });
    const empty = rail.querySelector("[data-dimension-filter-empty]");
    if (empty) empty.hidden = visible > 0;
    const count = rail.querySelector("[data-dimension-filter-count]");
    if (count) count.textContent = query ? `${formatInteger(visible)} 个匹配维度` : `${formatInteger(rail.querySelectorAll("[data-breakdown-id]").length)} 个业务维度`;
  }

  function normalizeFuzzyText(value) {
    return String(value || "").normalize("NFKC").replace(/\s+/g, "").toLocaleLowerCase("zh-CN");
  }

  function requestExplorerRender() {
    window.clearTimeout(explorerRenderTimer);
    const activeSearch = document.activeElement?.matches?.("[data-profit-search], [data-dynamic-search], [data-dimension-filter], [data-filter-option-search], [data-period-option-search]");
    const quietFor = Date.now() - lastSearchInputAt;
    const delay = searchCompositionActive
      ? 180
      : activeSearch && quietFor < 560
        ? Math.max(80, 560 - quietFor)
        : 48;
    explorerRenderTimer = window.setTimeout(() => {
      if (searchCompositionActive) {
        requestExplorerRender();
        return;
      }
      renderActiveViewPreservingInteraction();
    }, delay);
  }

  function renderActiveViewPreservingInteraction() {
    const canvas = document.querySelector(".workspace-canvas");
    const active = document.activeElement;
    const focusState = active && el.viewContent.contains(active) ? searchableControlState(active) : null;
    const scrollState = Array.from(el.viewContent.querySelectorAll(".dimension-canvas ol, .data-table-wrap, .grouped-profit-list"))
      .map((node, index) => ({ index, top: node.scrollTop, left: node.scrollLeft }));
    const canvasTop = canvas?.scrollTop || 0;
    const canvasLeft = canvas?.scrollLeft || 0;
    renderActiveView();
    restoreActiveViewInteraction({ canvas, canvasTop, canvasLeft, focusState, scrollState });
    window.requestAnimationFrame(() => {
      restoreActiveViewInteraction({ canvas, canvasTop, canvasLeft, focusState, scrollState });
    });
  }

  function searchableControlState(control) {
    let selector = "";
    if (control.dataset.profitSearch) selector = `[data-profit-search="${CSS.escape(control.dataset.profitSearch)}"]`;
    else if (control.matches("[data-dynamic-search]")) selector = "[data-dynamic-search]";
    else if (control.matches("[data-dimension-filter]")) selector = "[data-dimension-filter]";
    else if (control.dataset.filterOptionSearch) selector = `[data-filter-option-search="${CSS.escape(control.dataset.filterOptionSearch)}"]`;
    else if (control.dataset.periodOptionSearch) selector = `[data-period-option-search="${CSS.escape(control.dataset.periodOptionSearch)}"]`;
    if (!selector) return null;
    return {
      selector,
      start: typeof control.selectionStart === "number" ? control.selectionStart : null,
      end: typeof control.selectionEnd === "number" ? control.selectionEnd : null,
      direction: control.selectionDirection || "none",
      scrollLeft: control.scrollLeft || 0
    };
  }

  function restoreActiveViewInteraction({ canvas, canvasTop, canvasLeft, focusState, scrollState }) {
    if (canvas) {
      canvas.scrollTop = canvasTop;
      canvas.scrollLeft = canvasLeft;
    }
    const scrollNodes = el.viewContent.querySelectorAll(".dimension-canvas ol, .data-table-wrap, .grouped-profit-list");
    scrollState.forEach((saved) => {
      const node = scrollNodes[saved.index];
      if (!node) return;
      node.scrollTop = saved.top;
      node.scrollLeft = saved.left;
    });
    if (!focusState) return;
    const control = el.viewContent.querySelector(focusState.selector);
    if (!control) return;
    control.focus({ preventScroll: true });
    control.scrollLeft = focusState.scrollLeft;
    if (focusState.start !== null && typeof control.setSelectionRange === "function") {
      const end = Math.min(focusState.end, control.value.length);
      control.setSelectionRange(Math.min(focusState.start, end), end, focusState.direction);
    }
  }

  function invalidateExplorer(slot) {
    explorerAbortControllers.get(slot)?.abort();
    explorerAbortControllers.delete(slot);
    state.explorers.delete(slot);
  }

  function invalidateExplorerScope(scope) {
    const slots = scope === "product"
      ? ["productDetail"]
      : scope === "customer"
        ? ["customerSummary", "customerValue", "losingCustomers", "customerChannels", "channelCustomers", "customerProducts"]
        : ["salesGroupSummary", "salesDepartmentTransfer", "salesGroupCustomers", "salesGroupProducts"];
    slots.forEach(invalidateExplorer);
  }

  function compactFilters(filters = {}) {
    return Object.fromEntries(Object.entries(filters)
      .filter(([, value]) => value !== "" && value !== null && value !== undefined)
      .map(([key, value]) => [key, Array.isArray(value) ? value.map(String) : [String(value)]]));
  }

  function hasActiveFilters(filters = {}) {
    return Object.values(filters).some((value) => Array.isArray(value) ? value.length > 0 : value !== "" && value !== null && value !== undefined);
  }

  function activeFilterLabel(filters = {}) {
    return Object.values(filters).flatMap((value) => Array.isArray(value) ? value : value ? [value] : []).join(" · ");
  }

  function ensureActiveExplorers() {
    if (!state.analysis || state.loading || workspaceRecoveryPromise
      || mappingConfirmationRequired(state.analysis)) return;
    const periodGroup = state.analysis.dynamicBreakdowns?.find((item) => item.values?.length)?.dimensionId || DIMENSIONS.product;
    if (IS_DESKTOP && ["analysis", "report"].includes(state.activeWorkspace)) {
      loadExplorer("periodOverview", { groupBy: periodGroup, filters: {}, includeFilterOptions: false, limit: 1 });
    }
    if (state.activeWorkspace === "report") {
      loadExplorer("salesGroupSummary", { groupBy: DIMENSIONS.salesGroup, filters: {}, costMetric: "transferCost", limit: 100 });
      if (IS_DESKTOP) {
        loadExplorerAll("productPeriodSummary", { groupBy: DIMENSIONS.product, filters: {} });
        loadExplorerAll("customerPeriodSummary", { groupBy: DIMENSIONS.customer, filters: {} });
      }
      return;
    }
    if (state.activeWorkspace !== "analysis") return;
    if (["overview", "profit"].includes(state.activeView)) {
      if (IS_DESKTOP) {
        loadExplorerAll("productPeriodSummary", { groupBy: DIMENSIONS.product, filters: {} });
        loadExplorerAll("customerPeriodSummary", { groupBy: DIMENSIONS.customer, filters: {} });
      }
      const dimensions = state.analysis.dynamicBreakdowns.filter((item) => item.values.length);
      const active = dimensions.find((item) => item.dimensionId === state.activeBreakdownId) || preferredBreakdown(dimensions);
      if (active) {
        const pageSize = state.dynamicControls.pageSize;
        loadExplorer("dynamicBreakdown", {
          groupBy: active.dimensionId,
          filters: state.dynamicControls.drill ? { [state.dynamicControls.drill.parent]: [state.dynamicControls.drill.value] } : {},
          includeFilterOptions: false,
          search: state.dynamicControls.search,
          offset: state.dynamicControls.page * pageSize,
          limit: pageSize
        });
      }
    }
    if (state.activeView === "product") {
      if (IS_DESKTOP) loadExplorerAll("productPeriodSummary", { groupBy: DIMENSIONS.product, filters: {} });
      PRODUCT_STRUCTURE_LENSES.forEach((lens) => loadExplorer(`productStructure:${lens.key}`, { groupBy: lens.groupBy, filters: {}, includeFilterOptions: false, ...(IS_DESKTOP ? { quantityMetric: "convertedQuantity" } : {}), limit: 200 }));
      loadExplorer("productDetail", { groupBy: DIMENSIONS.product, filters: compactFilters(state.productControls.filters), includeFilterOptions: false, quantityMetric: state.productControls.quantityMetric, sortBy: state.productControls.metric, search: state.productControls.search, ...(IS_DESKTOP ? { offset: state.productControls.page * state.productControls.pageSize, limit: state.productControls.pageSize } : { limit: 500 }) });
      return;
    }
    if (state.activeView === "customer") {
      const baseFilters = compactFilters(state.customerControls.filters);
      loadExplorerAll("customerSummary", { groupBy: DIMENSIONS.customer, filters: baseFilters, costMetric: "cost" });
      loadExplorer("customerValue", { groupBy: DIMENSIONS.customer, filters: baseFilters, includeFilterOptions: false, costMetric: "cost", search: state.customerControls.search, limit: 500 });
      loadExplorer("losingCustomers", { groupBy: DIMENSIONS.customer, filters: baseFilters, includeFilterOptions: false, costMetric: "cost", sortBy: "profit", profitFilter: "loss", search: state.customerControls.search, offset: state.customerControls.lossPage * 100, limit: 100 });
      const channelFilters = { ...baseFilters };
      delete channelFilters[DIMENSIONS.channel];
      loadExplorer("customerChannels", { groupBy: DIMENSIONS.channel, filters: channelFilters, includeFilterOptions: false, limit: 20 });
      const channelEntry = state.explorers.get("customerChannels");
      const selectedChannel = state.customerControls.selectedChannel || channelEntry?.data?.items?.[0]?.name || "";
      if (selectedChannel) loadExplorer("channelCustomers", { groupBy: DIMENSIONS.customer, filters: { ...channelFilters, [DIMENSIONS.channel]: [selectedChannel] }, includeFilterOptions: false, costMetric: "cost", limit: 10 });
      const controls = state.customerControls;
      const customerValueEntry = state.explorers.get("customerValue");
      if (!controls.customerDrillInitialized && !(controls.selectedCustomers || []).length) {
        const initialCustomer = (customerValueEntry?.status === "success" ? customerValueEntry.data?.items?.[0]?.name : "")
          || (!controls.search ? sortedByRevenue(state.analysis.customers)[0]?.name : "")
          || "";
        if (initialCustomer) {
          controls.selectedCustomer = initialCustomer;
          controls.selectedCustomers = [initialCustomer];
          controls.customerDrillInitialized = true;
        }
      }
      const selectedCustomers = [...new Set((controls.selectedCustomers || []).map(String).filter(Boolean))];
      controls.selectedCustomer = selectedCustomers[0] || "";
      if (selectedCustomers.length) loadExplorer("customerProducts", { groupBy: DIMENSIONS.product, filters: { ...baseFilters, [DIMENSIONS.customer]: selectedCustomers }, includeFilterOptions: false, costMetric: "cost", limit: 30 });
      return;
    }
    if (state.activeView === "salesGroup") {
      const controls = state.salesGroupControls;
      const baseFilters = compactFilters(controls.filters);
      loadExplorer("salesGroupSummary", { groupBy: controls.groupBy, filters: baseFilters, costMetric: controls.costMetric, limit: 500 });
      if (IS_DESKTOP) loadExplorer("salesDepartmentTransfer", { groupBy: DIMENSIONS.salesDepartment, filters: baseFilters, includeFilterOptions: false, costMetric: "transferCost", limit: 500 });
      const summaryEntry = state.explorers.get("salesGroupSummary");
      const selectedGroup = controls.selectedGroup || (controls.groupBy === DIMENSIONS.salesGroup ? summaryEntry?.data?.items?.[0]?.name : "") || "";
      if (selectedGroup) {
        const drillFilters = { ...baseFilters, [DIMENSIONS.salesGroup]: [selectedGroup] };
        const drillCustomers = (controls.drillCustomers || []).filter(Boolean);
        const productFilters = drillCustomers.length ? { ...drillFilters, [DIMENSIONS.customer]: drillCustomers } : drillFilters;
        loadExplorer("salesGroupCustomers", { groupBy: DIMENSIONS.customer, filters: drillFilters, includeFilterOptions: false, costMetric: controls.costMetric, limit: 50 });
        loadExplorer("salesGroupProducts", { groupBy: DIMENSIONS.product, filters: productFilters, includeFilterOptions: false, costMetric: controls.costMetric, limit: 50 });
      }
    }
  }

  function loadExplorer(slot, payload) {
    if (!state.analysis?.id) return;
    const scopedPayload = IS_DESKTOP ? { ...payload, ...periodRequestPayload() } : payload;
    const signature = JSON.stringify(scopedPayload);
    const existing = state.explorers.get(slot);
    if (existing?.signature === signature && ["loading", "success", "error"].includes(existing.status)) return;
    const token = ++state.explorerRequestToken;
    const analysisId = state.analysis.id;
    explorerAbortControllers.get(slot)?.abort();
    const requestController = new AbortController();
    explorerAbortControllers.set(slot, requestController);
    state.explorers.set(slot, { status: "loading", signature, token, data: null, error: "" });
    apiFetch(`/api/analysis/${encodeURIComponent(analysisId)}/explore`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(scopedPayload),
      signal: requestController.signal
    }, 90000).then((result) => {
      if (explorerAbortControllers.get(slot) === requestController) explorerAbortControllers.delete(slot);
      const current = state.explorers.get(slot);
      if (state.analysis?.id !== analysisId || current?.token !== token) return;
      if (result?.drillSuggestions && typeof result.drillSuggestions === "object") {
        state.drillSuggestions = result.drillSuggestions;
        state.drillSuggestionsLoaded = true;
      }
      state.explorers.set(slot, { ...current, status: "success", data: normalizeExploreResponse(result), error: "" });
      if (explorerAffectsPeriodEvidence(slot)) buildEvidenceLedger();
      requestExplorerRender();
    }).catch((error) => {
      if (explorerAbortControllers.get(slot) === requestController) explorerAbortControllers.delete(slot);
      const current = state.explorers.get(slot);
      if (state.analysis?.id !== analysisId || current?.token !== token) return;
      if (error.status === 404) {
        recoverWorkspaceAfterSessionExpiry(analysisId);
        return;
      }
      if (slot.startsWith("salesGroup") && scopedPayload.costMetric === "transferCost" && /不可用|可选值/.test(String(error.message || error))) {
        state.salesGroupControls.costMetric = "cost";
        invalidateExplorerScope("salesGroup");
        toast("已切换成本口径", "当前数据没有调拨成本，销售组分析改用已确认成本。", "success");
        renderActiveView();
        return;
      }
      state.explorers.set(slot, { ...current, status: "error", error: friendlyError(error), data: null });
      requestExplorerRender();
    });
  }

  function loadExplorerAll(slot, payload) {
    if (!state.analysis?.id) return;
    const scopedPayload = IS_DESKTOP ? { ...payload, ...periodRequestPayload() } : payload;
    const signature = JSON.stringify({ ...scopedPayload, __all: true });
    const existing = state.explorers.get(slot);
    if (existing?.signature === signature && ["loading", "success", "error"].includes(existing.status)) return;
    const token = ++state.explorerRequestToken;
    const analysisId = state.analysis.id;
    explorerAbortControllers.get(slot)?.abort();
    const requestController = new AbortController();
    explorerAbortControllers.set(slot, requestController);
    state.explorers.set(slot, { status: "loading", signature, token, data: null, error: "" });
    (async () => {
      let offset = 0;
      let aggregate = null;
      for (let page = 0; page < 200; page += 1) {
        const result = await apiFetch(`/api/analysis/${encodeURIComponent(analysisId)}/explore`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ ...scopedPayload, offset, limit: 500,
            includeFilterOptions: offset === 0 ? scopedPayload.includeFilterOptions : false }),
          signal: requestController.signal
        }, 90000);
        const normalized = normalizeExploreResponse(result);
        aggregate = aggregate
          ? { ...aggregate, items: [...aggregate.items, ...normalized.items], totalGroups: normalized.totalGroups }
          : normalized;
        if (!normalized.hasMore || !normalized.items.length) break;
        const nextOffset = normalized.offset + normalized.items.length;
        if (nextOffset <= offset) break;
        offset = nextOffset;
      }
      if (!aggregate) aggregate = normalizeExploreResponse({ items: [] });
      aggregate = { ...aggregate, offset: 0, pageSize: aggregate.items.length, returnedGroups: aggregate.items.length, truncated: false, hasMore: false };
      if (explorerAbortControllers.get(slot) === requestController) explorerAbortControllers.delete(slot);
      const current = state.explorers.get(slot);
      if (state.analysis?.id !== analysisId || current?.token !== token) return;
      state.explorers.set(slot, { ...current, status: "success", data: aggregate, error: "" });
      if (explorerAffectsPeriodEvidence(slot)) buildEvidenceLedger();
      requestExplorerRender();
    })().catch((error) => {
      if (explorerAbortControllers.get(slot) === requestController) explorerAbortControllers.delete(slot);
      const current = state.explorers.get(slot);
      if (state.analysis?.id !== analysisId || current?.token !== token) return;
      if (error.status === 404) {
        recoverWorkspaceAfterSessionExpiry(analysisId);
        return;
      }
      state.explorers.set(slot, { ...current, status: "error", error: friendlyError(error), data: null });
      requestExplorerRender();
    });
  }

  function recoverWorkspaceAfterSessionExpiry(expiredAnalysisId) {
    if (!expiredAnalysisId || state.analysis?.id !== expiredAnalysisId
      || state.loading || workspaceRecoveryPromise) return workspaceRecoveryPromise;
    workspaceRecoveryError = "";
    pauseExplorerRequests();
    setLoading(true, "正在重新连接本机分析会话…");
    workspaceRecoveryPromise = (async () => {
      let recovered = false;
      try {
        const result = await apiFetch("/api/workspace/current", {}, LARGE_UPLOAD_TIMEOUT_MS);
        if (state.analysis?.id !== expiredAnalysisId) return;
        if (!result?.analysis) throw new Error("本机工作区没有可恢复的分析结果");
        const changed = result.analysis.id !== expiredAnalysisId;
        applyWorkspacePayload(result);
        recovered = true;
        toast(changed ? "已接管最新分析" : "分析会话已恢复",
          changed ? "后台重新分析已完成，页面已切换到最新结果。" : "本地数据没有丢失，分析页面已恢复。",
          "success");
      } catch (error) {
        if (state.analysis?.id !== expiredAnalysisId) return;
        workspaceRecoveryError = `分析会话恢复失败：${friendlyError(error)}`;
        failPausedExplorerRequests(error);
        setStatus("error", workspaceRecoveryError, 0,
          () => recoverWorkspaceAfterSessionExpiry(expiredAnalysisId));
        toast("分析会话恢复失败", friendlyError(error), "error");
      } finally {
        workspaceRecoveryPromise = null;
        // A failed restore must stay idle until the explicit retry. Automatically
        // resuming explorers here would repeat 404 -> restore -> 404 indefinitely.
        setLoading(false, "", { resumeExplorers: false });
        if (recovered) ensureActiveExplorers();
      }
    })();
    return workspaceRecoveryPromise;
  }

  function explorerAffectsPeriodEvidence(slot) {
    return IS_DESKTOP && ["periodOverview", "productPeriodSummary", "customerPeriodSummary"].includes(slot);
  }

  function normalizeExploreResponse(raw) {
    const input = raw?.result || raw?.exploration || raw || {};
    const rows = Array.isArray(input) ? input : input.items || input.groups || input.rows || input.breakdown || [];
    const items = (Array.isArray(rows) ? rows : []).map((item, index) => normalizeExploreItem(item, index));
    const periodSummary = input.periodSummary || {};
    return {
      items,
      totals: normalizeExploreItem(input.totals || input.summary || {}, 0),
      periodSummary: {
        ...periodSummary,
        records: nullableNumber(firstDefined(periodSummary.records, input.totals?.records, input.summary?.records)),
        revenue: nullableNumber(periodSummary.revenue),
        cost: nullableNumber(periodSummary.cost),
        expense: nullableNumber(periodSummary.expense),
        grossComparableRevenue: nullableNumber(periodSummary.grossComparableRevenue),
        grossComparableCost: nullableNumber(periodSummary.grossComparableCost),
        grossProfit: nullableNumber(periodSummary.grossProfit),
        grossMargin: nullableRatio(periodSummary.grossMargin),
        grossProfitCoverage: nullableRatio(periodSummary.grossProfitCoverage),
        grossProfitAvailable: Boolean(periodSummary.grossProfitAvailable),
        operatingComparableRevenue: nullableNumber(periodSummary.operatingComparableRevenue),
        operatingComparableCost: nullableNumber(periodSummary.operatingComparableCost),
        operatingComparableExpense: nullableNumber(periodSummary.operatingComparableExpense),
        operatingProfit: nullableNumber(periodSummary.operatingProfit),
        operatingMargin: nullableRatio(periodSummary.operatingMargin),
        operatingProfitCoverage: nullableRatio(periodSummary.operatingProfitCoverage),
        operatingProfitAvailable: Boolean(periodSummary.operatingProfitAvailable),
        quantity: nullableNumber(periodSummary.quantity),
        salesQuantity: nullableNumber(periodSummary.salesQuantity),
        convertedQuantity: nullableNumber(periodSummary.convertedQuantity),
        revenueAvailable: periodSummary.revenueAvailable,
        costAvailable: periodSummary.costAvailable,
        expenseAvailable: periodSummary.expenseAvailable,
        quantityAvailable: periodSummary.quantityAvailable,
        grossComparableRows: nullableNumber(periodSummary.grossComparableRows),
        operatingComparableRows: nullableNumber(periodSummary.operatingComparableRows),
        grossProfitExcludedRows: nullableNumber(periodSummary.grossProfitExcludedRows),
        operatingProfitExcludedRows: nullableNumber(periodSummary.operatingProfitExcludedRows),
        grossProfitExcludedRevenue: nullableNumber(periodSummary.grossProfitExcludedRevenue),
        operatingProfitExcludedRevenue: nullableNumber(periodSummary.operatingProfitExcludedRevenue)
      },
      periodMode: String(input.periodMode || ""),
      appliedYears: Array.isArray(input.appliedYears) ? input.appliedYears.map(String) : [],
      appliedMonths: Array.isArray(input.appliedMonths) ? input.appliedMonths.map(String) : [],
      filterOptions: normalizeExploreFilterOptions(input.filterOptions || input.availableFilters || input.filters || {}),
      dimensions: Array.isArray(input.dimensions || input.availableDimensions) ? (input.dimensions || input.availableDimensions) : [],
      measures: Array.isArray(input.measures || input.availableMeasures) ? (input.measures || input.availableMeasures) : [],
      quantityMetric: String(input.quantityMetric || ""),
      costMetric: String(input.costMetric || ""),
      availableQuantityMetrics: Array.isArray(input.availableQuantityMetrics) ? input.availableQuantityMetrics.map(String) : [],
      availableCostMetrics: Array.isArray(input.availableCostMetrics) ? input.availableCostMetrics.map(String) : [],
      groupBy: String(input.groupBy || input.dimension || ""),
      search: String(input.search || ""),
      profitFilter: String(input.profitFilter || ""),
      offset: numberValue(input.offset, 0),
      pageSize: numberValue(input.pageSize, items.length),
      returnedGroups: numberValue(input.returnedGroups, items.length),
      totalGroups: numberValue(input.totalGroups, items.length),
      truncated: Boolean(input.truncated),
      hasMore: Boolean(input.hasMore)
    };
  }

  function normalizeExploreItem(item, index) {
    const revenue = nullableNumber(firstDefined(item.revenue, item.sales, item.salesAmount, item.amount));
    const cost = nullableNumber(firstDefined(item.cost, item.transferCost, item.allocatedCost, item.standardCost));
    const comparableRevenue = nullableNumber(firstDefined(item.comparableRevenue, item.profitBasisRevenue));
    const comparableCost = nullableNumber(firstDefined(item.comparableCost, item.profitBasisCost));
    const profitCoverage = nullableRatio(firstDefined(item.profitCoverage, item.coverage));
    const reportedProfit = firstDefined(item.grossProfit, item.profit, item.marginAmount);
    const grossProfit = reportedProfit !== undefined
      ? nullableNumber(reportedProfit)
      : item.profitAvailable === false ? null
        : (comparableRevenue !== null && comparableCost !== null ? comparableRevenue - comparableCost
          : isCompleteCoverage(profitCoverage) && revenue !== null && cost !== null ? revenue - cost : null);
    const quantity = nullableNumber(firstDefined(item.quantity, item.selectedQuantity, item.qty, item.volume));
    const outboundQuantity = Object.prototype.hasOwnProperty.call(item, "outboundQuantity")
      ? nullableNumber(item.outboundQuantity)
      : nullableNumber(firstDefined(item.deliveryQuantity, item.shippedQuantity, item.quantity));
    const convertedQuantity = Object.prototype.hasOwnProperty.call(item, "convertedQuantity")
      ? nullableNumber(item.convertedQuantity)
      : nullableNumber(firstDefined(item.convertedUnits, item.pieceQuantity, item.quantity));
    const profitBasis = comparableRevenue !== null ? comparableRevenue : revenue;
    const reportedMargin = nullableRatio(firstDefined(item.grossMargin, item.profitMargin, item.marginRate, item.margin));
    const grossMargin = profitBasis !== null && profitBasis > 0 && grossProfit !== null
      ? (reportedMargin ?? (comparableRevenue !== null || isCompleteCoverage(profitCoverage) ? grossProfit / profitBasis : null))
      : null;
    const unitPriceProvided = Object.prototype.hasOwnProperty.call(item, "unitPrice")
      || Object.prototype.hasOwnProperty.call(item, "averagePrice");
    const unitPrice = unitPriceProvided
      ? nullableNumber(firstDefined(item.unitPrice, item.averagePrice))
      : (revenue !== null && quantity ? revenue / quantity : null);
    const salesQuantity = nullableNumber(firstDefined(item.salesQuantity, item.outboundQuantity, item.deliveryQuantity, item.shippedQuantity, item.quantity));
    const averageCost = nullableNumber(firstDefined(item.averageCost, item.unitCost));
    const unitGrossProfit = nullableNumber(firstDefined(item.unitGrossProfit, item.grossProfitPerUnit, item.unitMargin));
    return {
      ...item,
      name: String(item.name || item.label || item.value || item.group || item.key || `未命名 ${index + 1}`),
      revenue,
      cost,
      comparableRevenue,
      comparableCost,
      transferCost: nullableNumber(firstDefined(item.transferCost, item.allocatedCost, cost)),
      grossProfit,
      grossMargin,
      profitCoverage,
      comparableRows: numberValue(item.comparableRows, 0),
      excludedProfitRows: numberValue(item.excludedProfitRows, 0),
      profitAvailable: Boolean(item.profitAvailable ?? (grossProfit !== null)),
      operatingProfit: nullableNumber(firstDefined(item.operatingProfit, item.netProfit)),
      operatingMargin: nullableRatio(firstDefined(item.operatingMargin, item.netMargin)),
      operatingComparableRevenue: nullableNumber(item.operatingComparableRevenue),
      operatingComparableCost: nullableNumber(item.operatingComparableCost),
      operatingComparableExpense: nullableNumber(item.operatingComparableExpense),
      operatingProfitCoverage: nullableRatio(item.operatingProfitCoverage),
      operatingProfitAvailable: Boolean(item.operatingProfitAvailable),
      quantity,
      salesQuantity,
      outboundQuantity,
      convertedQuantity,
      transferComparableRevenue: nullableNumber(item.transferComparableRevenue),
      transferComparableCost: nullableNumber(item.transferComparableCost),
      transferGrossProfit: nullableNumber(item.transferGrossProfit),
      transferGrossMargin: nullableRatio(item.transferGrossMargin),
      transferProfitCoverage: nullableRatio(item.transferProfitCoverage),
      confirmedCost: nullableNumber(item.confirmedCost),
      confirmedComparableRevenue: nullableNumber(item.confirmedComparableRevenue),
      confirmedComparableCost: nullableNumber(item.confirmedComparableCost),
      confirmedGrossProfit: nullableNumber(item.confirmedGrossProfit),
      confirmedGrossMargin: nullableRatio(item.confirmedGrossMargin),
      confirmedProfitCoverage: nullableRatio(item.confirmedProfitCoverage),
      confirmedProfitAvailable: Boolean(item.confirmedProfitAvailable),
      salesGroups: Array.isArray(item.salesGroups)
        ? [...new Set(item.salesGroups.map(String).filter(Boolean))]
        : item.salesGroup ? [String(item.salesGroup)] : [],
      unitPrice,
      averageCost,
      unitGrossProfit,
      expenses: nullableNumber(firstDefined(item.expenses, item.periodExpenses)),
      productCount: numberValue(firstDefined(item.productCount, item.distinctProductCount, item.distinctCount, item.skuCount), 0),
      customerCount: numberValue(firstDefined(item.customerCount, item.distinctCustomerCount), 0),
      count: numberValue(firstDefined(item.count, item.records, item.rowCount, item.recordCount), 0),
      share: nullableRatio(firstDefined(item.share, item.revenueShare, item.salesShare))
    };
  }

  function normalizeExploreFilterOptions(input) {
    if (Array.isArray(input)) {
      return input.reduce((result, item) => {
        const key = String(item.dimension || item.field || item.name || "");
        if (key) result[key] = (item.values || item.options || []).map((value) => String(value?.value ?? value?.name ?? value));
        return result;
      }, {});
    }
    if (!input || typeof input !== "object") return {};
    return Object.fromEntries(Object.entries(input).map(([key, values]) => [key, (Array.isArray(values) ? values : values?.values || []).map((value) => String(value?.value ?? value?.name ?? value))]));
  }

  function explorerOptions(scope, dimension) {
    const prefix = scope === "product" ? "product" : scope === "customer" ? "customer" : "salesGroup";
    const options = new Set();
    state.explorers.forEach((entry, slot) => {
      if (!slot.startsWith(prefix) || entry.status !== "success") return;
      (entry.data?.filterOptions?.[dimension] || []).forEach((value) => options.add(value));
    });
    const dynamic = state.analysis?.dynamicBreakdowns?.find((item) => item.dimensionLabel === dimension || item.dimensionId === dimension);
    dynamic?.values?.forEach((item) => options.add(item.name));
    return [...options].filter(Boolean).sort((a, b) => a.localeCompare(b, "zh-CN"));
  }

  function renderExplorerFilters(definitions, scope, filters) {
    if (IS_DESKTOP) {
      return `<div class="analysis-filter-grid multi-filter-grid" aria-label="${scope === "product" ? "产品明细" : scope === "customer" ? "客户价值" : "销售组织"}筛选条件">${definitions.map(([key, label, dimension]) => {
        const selected = new Set(Array.isArray(filters[dimension]) ? filters[dimension].map(String) : filters[dimension] ? [String(filters[dimension])] : []);
        const options = explorerOptions(scope, dimension);
        selected.forEach((value) => { if (!options.includes(value)) options.unshift(value); });
        const filterKey = `${scope}:${dimension}`;
        const search = state.filterSearches[filterKey] || "";
        const summary = selected.size ? `已选 ${selected.size} 项` : "全部";
        return `<details class="filter-multiselect" data-filter-key="${attr(filterKey)}" ${state.openFilterKey === filterKey ? "open" : ""}><summary><span>${esc(label)}</span><b data-filter-summary>${esc(summary)}</b></summary><div class="multiselect-popover"><label class="multiselect-search"><span class="sr-only">搜索${esc(label)}</span><input type="search" value="${attr(search)}" data-filter-option-search="${attr(filterKey)}" autocomplete="off" placeholder="搜索选项"></label><div class="multiselect-options">${options.length ? options.map((value) => `<label data-option-text="${attr(value)}" ${search && !normalizeFuzzyText(value).includes(normalizeFuzzyText(search)) ? "hidden" : ""}><input type="checkbox" value="${attr(value)}" data-explorer-scope="${attr(scope)}" data-explorer-multi="${attr(dimension)}" ${selected.has(value) ? "checked" : ""}><span title="${attr(value)}">${esc(value)}</span></label>`).join("") : `<span class="multiselect-empty">等待读取可选值…</span>`}</div><footer><small>支持多选；不勾选表示全部</small><button type="button" data-explorer-scope="${attr(scope)}" data-clear-explorer-filter="${attr(dimension)}" ${selected.size ? "" : "disabled"}>清除</button></footer></div></details>`;
      }).join("")}</div>`;
    }
    return `<div class="analysis-filter-grid" aria-label="${scope === "product" ? "产品明细" : scope === "customer" ? "客户价值" : "销售组织"}筛选条件">${definitions.map(([key, label, dimension]) => {
      const selected = String(filters[dimension] || "");
      const options = explorerOptions(scope, dimension);
      if (selected && !options.includes(selected)) options.unshift(selected);
      return `<label><span>${esc(label)}</span><select data-explorer-scope="${attr(scope)}" data-explorer-filter="${attr(dimension)}"><option value="">全部</option>${options.map((value) => `<option value="${attr(value)}" ${value === selected ? "selected" : ""}>${esc(value)}</option>`).join("")}</select></label>`;
    }).join("")}</div>`;
  }

  function renderExplorerFeedback(slot, options = {}) {
    const entry = state.explorers.get(slot);
    if (workspaceRecoveryError && (!entry || ["loading", "paused", "error"].includes(entry.status))) {
      return `<div class="explorer-feedback error" role="alert"><span>!</span><div><strong>${esc(options.errorTitle || "分析会话需要重新连接")}</strong><p>${esc(workspaceRecoveryError)}</p><button class="text-action" type="button" data-retry-explorer="${attr(slot)}">重新连接</button></div></div>`;
    }
    if (!entry || ["loading", "paused"].includes(entry.status)) return `<div class="explorer-feedback loading" role="status"><span class="spinner"></span><div><strong>${esc(options.loadingTitle || "正在计算当前维度")}</strong><p>正在按已选口径重新聚合数据…</p></div></div>`;
    if (entry.status === "error") return `<div class="explorer-feedback error" role="alert"><span>!</span><div><strong>${esc(options.errorTitle || "暂时无法读取该维度")}</strong><p>${esc(entry.error)}</p><button class="text-action" type="button" data-retry-explorer="${attr(slot)}">重试</button></div></div>`;
    if (!entry.data?.items?.length) return `<div class="explorer-feedback empty"><span>—</span><div><strong>${esc(options.emptyTitle || "当前筛选下暂无数据")}</strong><p>${esc(options.emptyText || "调整筛选条件后再试。")}</p></div></div>`;
    return "";
  }

  function renderProduct(input) {
    const a = desktopScopedAnalysis(input);
    const capabilities = a.capabilities;
    const hasOperating = capabilities.operatingProfitAvailable;
    const hasGross = capabilities.grossProfitAvailable;
    const list = sortedByRevenue(a.products);
    const top = list[0];
    const profitKey = hasOperating ? "operatingProfit" : "grossProfit";
    const marginKey = hasOperating ? "operatingMargin" : "grossMargin";
    const profitReady = hasOperating || hasGross;
    const comparableProducts = profitReady ? a.products.filter((item) => item[profitKey] !== null && Number.isFinite(item[profitKey])) : [];
    const operatingProducts = comparableProducts.filter((item) => !isProfitAdjustmentProduct(item.name));
    const best = comparableProducts
      .filter((item) => !isProfitAdjustmentProduct(item.name))
      .filter((item) => item.revenue > 0 && item[profitKey] > 0 && item[marginKey] !== null && Number.isFinite(item[marginKey]))
      .sort((x, y) => y[marginKey] - x[marginKey])[0] || null;
    const worst = [...operatingProducts].sort((x, y) => x[profitKey] - y[profitKey])[0] || null;
    const concentration = list.slice(0, 3).reduce((sum, item) => sum + item.revenue, 0) / Math.max(a.summary.revenue, 1);
    const losingCount = hasOperating ? operatingProducts.filter((item) => item.operatingProfit < 0).length : hasGross ? operatingProducts.filter((item) => item.grossProfit < 0).length : 0;
    const quantityEntries = PRODUCT_STRUCTURE_LENSES.map((lens) => state.explorers.get(`productStructure:${lens.key}`));
    const convertedQuantity = nullableNumber(quantityEntries.find((entry) => entry?.status === "success" && entry.data?.totals?.convertedQuantity !== null)?.data?.totals?.convertedQuantity);
    const convertedQuantityState = convertedQuantity !== null ? `${formatQuantity(convertedQuantity)} 只` : quantityEntries.some((entry) => entry?.status === "loading" || !entry) ? "计算中" : "不可用";
    const worstProfit = worst ? (hasOperating ? worst.operatingProfit : worst.grossProfit) : null;
    const conclusion = worst && worstProfit < 0
      ? `${worst.name}${hasOperating ? "经营" : "毛"}利润为 ${formatCurrency(worstProfit)}${profitBasisRevenue(worst, marginKey) <= 0 ? `（收入 ${formatCurrency(worst.revenue)}，利润率不适用）` : ""}，是产品组合中的首要复核对象。`
      : hasGross ? `${top?.name || "主力产品"}贡献最高收入，当前可比口径没有明显亏损项。` : `${top?.name || "主力产品"}贡献最高收入；成本口径未确认，暂不评价产品盈利。`;
    const explanation = top
      ? `${top.name}贡献 ${formatCurrency(top.revenue)}；${best ? `${best.name}的${hasOperating ? "经营" : "毛"}利率最高。` : "当前仅能比较销售规模。"}前三产品收入集中度为 ${formatPercent(concentration)}${hasGross ? "；亏损判断已排除“商业折扣”和“直营专柜调整”两项调整记录。" : "。"}`
      : "当前数据没有可用的产品字段。请导入包含产品名称的经营明细。";
    const kpis = IS_DESKTOP ? [
      kpi("产品种类数", formatInteger(a.products.length), hasGross ? `${losingCount} 个可比口径亏损产品` : "去重后的产品名称数量", "EV-SUM-REV", "indigo", null, losingCount > 0),
      kpi("换算只数总和", convertedQuantityState, convertedQuantity === null ? "源表未提供可靠换算只数时不回退" : "单位：只 · 当前期间求和", "EV-QUALITY-001", "sky"),
      kpi("第一大产品", top ? top.name : "—", top ? formatCurrency(top.revenue) : "暂无数据", top ? productEvidence(a, top) : "EV-SUM-REV", "sky")
    ] : [kpi("产品数量", formatInteger(a.products.length), hasGross ? `${losingCount} 个可比口径亏损产品` : "已识别产品维度", "EV-SUM-REV", "indigo", null, losingCount > 0), kpi("第一大产品", top ? top.name : "—", top ? formatCurrency(top.revenue) : "暂无数据", top ? productEvidence(a, top) : "EV-SUM-REV", "sky")];
    if (best) kpis.push(kpi(`最高${hasOperating ? "经营" : "毛"}利率`, formatPercent(hasOperating ? best.operatingMargin : best.grossMargin), best.name, productEvidence(a, best), "lavender", hasOperating ? best.operatingMargin : best.grossMargin));
    kpis.push(kpi("前三收入占比", formatPercent(concentration), concentration > .75 ? "结构较集中" : "结构相对分散", list.length ? list.slice(0, 3).map((item) => productEvidence(a, item)).join(",") : "EV-SUM-REV", concentration > .75 ? "amber" : "lavender", concentration));
    return `<div class="view-stack">
      ${renderKpis(kpis)}
      <div class="section-grid two-one">
        ${renderProductChart(a)}
        <section class="surface product-card" aria-labelledby="productSignalTitle">
          <div class="section-heading"><div><span class="eyebrow">组合信号</span><h2 id="productSignalTitle">规模与质量</h2></div></div>
          <div class="product-summary-panel">
            ${summaryStat("规模引擎", top?.name || "暂无数据", top ? `收入 ${formatCurrency(top.revenue)}` : "等待产品字段")}
            ${summaryStat(hasGross ? "利润引擎" : "利润能力", best?.name || "尚未识别", best ? `${hasOperating ? "经营" : "毛"}利率 ${formatPercent(best[marginKey])}` : hasGross ? "需同时满足正收入与正利润" : "先确认成本口径")}
            ${summaryStat(hasGross ? "优先复核" : "口径状态", worst?.name || "成本待确认", worst ? `${hasOperating ? "经营利润" : "毛利润"} ${formatCurrency(worstProfit)}` : "不会按 0 计算", worstProfit < 0)}
          </div>
        </section>
      </div>
      ${renderBusinessCategoryAnalysis()}
      ${renderProductStructureLenses()}
      ${renderProductDetailExplorer(a)}
      ${renderConclusion("产品分析", conclusion, explanation, worst ? [productEvidence(a, worst), hasOperating ? "EV-SUM-OP" : "EV-SUM-GP"] : ["EV-SUM-REV"])}
    </div>`;
  }

  function isProfitAdjustmentProduct(name) {
    const normalized = String(name || "").normalize("NFKC").replace(/\s+/g, "").toLowerCase();
    if (normalized === "商业折扣") return true;
    return normalized === "直营专柜调整" || (normalized.includes("直营专柜") && normalized.includes("调整"));
  }

  function mentionsProfitAdjustmentProduct(value) {
    const normalized = String(value || "").normalize("NFKC").replace(/\s+/g, "").toLowerCase();
    return normalized.includes("商业折扣") || (normalized.includes("直营专柜") && normalized.includes("调整"));
  }

  function renderBusinessCategoryAnalysis() {
    const slot = "productStructure:businessCategory";
    const feedback = renderExplorerFeedback(slot, { loadingTitle: "正在按经营分析分类聚合", emptyTitle: "没有可用的经营分析分类", emptyText: "请确认源表中包含“经营分析分类”字段。" });
    const items = state.explorers.get(slot)?.data?.items || [];
    if (feedback) return `<section class="surface grouped-profit-card"><div class="section-heading"><div><span class="eyebrow">经营分析分类</span><h2>产品收入与利润率</h2><p>按经营分析分类比较销售规模与盈利质量。</p></div></div>${feedback}</section>`;
    const max = Math.max(...items.map((item) => Math.abs(item.revenue || 0)), 1);
    return `<section class="surface grouped-profit-card" aria-labelledby="businessCategoryProfitTitle"><div class="section-heading"><div><span class="eyebrow">经营分析分类</span><h2 id="businessCategoryProfitTitle">产品收入与利润率</h2><p>横条表示销售额；利润率使用当前已确认的可比成本口径。列表展示全部已返回分类，不再截断前八项。</p></div><span class="section-note">${formatInteger(items.length)} 个分类</span></div><div class="grouped-profit-list">${items.map((item, index) => `<div class="grouped-profit-row"><span class="rank-number">${String(index + 1).padStart(2, "0")}</span><strong title="${attr(item.name)}">${esc(item.name)}</strong><div class="bar-track"><i style="width:${Math.max(3, Math.abs(item.revenue || 0) / max * 100)}%"></i></div><span>${esc(formatCurrency(item.revenue))}</span><b class="${item.grossMargin !== null && item.grossMargin < 0 ? "negative-text" : ""}">${esc(marginLabel(item))}</b></div>`).join("")}</div></section>`;
  }

  function renderProductStructureLenses() {
    const title = IS_DESKTOP ? "换算只数与销售额结构并排核对" : "数量结构与销售额结构并排核对";
    const description = IS_DESKTOP ? "数量侧统一使用“换算只数”求和，单位为只；产品种类数仅作为去重后的结构说明，不再混作销量。" : "每个维度同时展示产品数量占比和销售额占比，避免只看金额忽略组合复杂度。";
    return `<section class="structure-section" aria-labelledby="productStructureTitle"><div class="section-heading structure-heading"><div><span class="eyebrow">产品结构透镜</span><h2 id="productStructureTitle">${esc(title)}</h2><p>${esc(description)}</p></div><span class="section-note">3 个业务口径</span></div><div class="structure-lens-grid">${PRODUCT_STRUCTURE_LENSES.map(renderStructureLensCard).join("")}</div></section>`;
  }

  function renderStructureLensCard(lens) {
    const slot = `productStructure:${lens.key}`;
    const feedback = renderExplorerFeedback(slot, { loadingTitle: `正在读取${lens.label}`, emptyTitle: `没有${lens.label}数据`, emptyText: `请确认表格包含“${lens.label}”字段。` });
    const data = state.explorers.get(slot)?.data;
    const items = data?.items || [];
    const groupCount = data?.totalGroups ?? items.length;
    const distinctProducts = nullableNumber(data?.totals?.productCount);
    return `<article class="surface structure-lens-card"><header><div><span>${esc(lens.label)}</span><strong>产品组合结构</strong></div>${items.length ? `<small>${formatInteger(groupCount)} 类${IS_DESKTOP && distinctProducts !== null ? ` · ${formatInteger(distinctProducts)} 种产品` : ""}</small>` : ""}</header>${feedback || `<div class="paired-donuts">${renderShareDonut(items, IS_DESKTOP ? "convertedQuantity" : "count", IS_DESKTOP ? "换算只数" : "产品数量", data?.totals, data?.truncated)}${renderShareDonut(items, "revenue", "销售额", data?.totals, data?.truncated)}</div>`}</article>`;
  }

  function renderShareDonut(sourceItems, metric, label, totals = {}, truncated = false) {
    const prepared = sourceItems.map((item) => ({ name: item.name, value: metric === "convertedQuantity" ? nullableNumber(item.convertedQuantity) : metric === "count" ? nullableNumber(item.productCount) : nullableNumber(item.revenue) })).filter((item) => item.value !== null && item.value > 0).sort((a, b) => b.value - a.value);
    const returnedTotal = prepared.reduce((sum, item) => sum + item.value, 0);
    const authoritativeTotal = metric === "convertedQuantity" ? nullableNumber(totals?.convertedQuantity) : metric === "count" ? nullableNumber(totals?.productCount) : nullableNumber(totals?.revenue);
    const omitted = truncated && authoritativeTotal !== null ? Math.max(0, authoritativeTotal - returnedTotal) : 0;
    if (omitted > 0) prepared.push({ name: "其余分类", value: omitted });
    const visible = prepared.slice(0, 6);
    if (prepared.length > 6) visible.push({ name: "其他", value: prepared.slice(6).reduce((sum, item) => sum + item.value, 0) });
    const total = visible.reduce((sum, item) => sum + item.value, 0);
    if (!total) return `<div class="mini-donut metric-${attr(metric)} empty"><div class="donut-plot"><span>—</span></div><strong>${esc(label)}</strong><small>${metric === "convertedQuantity" ? "源表没有可靠换算只数字段，不回退为产品种类数" : "暂无可计算数据"}</small></div>`;
    let cursor = 0;
    const segments = visible.map((item, index) => {
      const start = cursor;
      cursor += item.value / total * 100;
      return `${DONUT_COLORS[index % DONUT_COLORS.length]} ${start.toFixed(2)}% ${cursor.toFixed(2)}%`;
    }).join(",");
    const metricValue = (value) => metric === "convertedQuantity" ? `${formatQuantity(value)} 只` : metric === "count" ? `${formatInteger(value)} 个产品` : formatCurrencyExact(value);
    const totalLabel = metric === "convertedQuantity" ? formatQuantity(total) : metric === "count" ? formatInteger(total) : formatCurrency(total);
    return `<div class="mini-donut metric-${attr(metric)}"><div class="donut-plot" style="--donut:conic-gradient(${segments})" role="img" aria-label="${attr(`${label}：${visible.map((item) => `${item.name}，数值 ${metricValue(item.value)}，占比 ${formatPercent(item.value / total)}`).join("；")}`)}"><span title="${attr(metricValue(total))}"><b>${esc(totalLabel)}</b><small>${esc(label)}</small></span></div><ul>${visible.map((item, index) => `<li><i style="background:${DONUT_COLORS[index % DONUT_COLORS.length]}"></i><span title="${attr(item.name)}">${esc(item.name)}</span><b class="legend-value" title="${attr(metricValue(item.value))}">${esc(metric === "convertedQuantity" ? formatQuantity(item.value) : metric === "count" ? formatInteger(item.value) : formatCurrency(item.value))}</b><b class="legend-share">${esc(formatPercent(item.value / total))}</b></li>`).join("")}</ul></div>`;
  }

  function renderProductDetailExplorer(a) {
    const controls = state.productControls;
    const slot = "productDetail";
    const feedback = renderExplorerFeedback(slot, { loadingTitle: "正在生成产品明细", emptyTitle: "当前条件下没有产品", emptyText: "清除部分筛选条件后再试。" });
    const detailData = state.explorers.get(slot)?.data;
    const items = detailData?.items || [];
    const metricLabels = { revenue: "销售额", quantity: "数量", unitPrice: "单价" };
    const quantityMetric = controls.quantityMetric || detailData?.quantityMetric || "quantity";
    const quantityLabel = quantityMetric === "convertedQuantity" ? "换算只数" : quantityMetric === "outboundQuantity" ? "出库数量" : "原始数量";
    const available = detailData?.availableQuantityMetrics || [];
    const quantityReady = available.length > 0;
    const quantityOptions = [["outboundQuantity", "出库数量"], ["convertedQuantity", "换算只数"], ["quantity", "原始数量"]];
    const detailDescription = IS_DESKTOP ? "支持产品名称模糊搜索和分类多选；明细按“销售数量 → 换算只数 → 均价/均成本 → 销售与毛利”固定顺序展示。" : "支持产品名称模糊搜索；筛选条件只作用于产品明细，结构透镜保留全局口径。";
    return `<section class="surface detail-explorer" aria-labelledby="productDetailExplorerTitle"><header class="section-heading"><div><span class="eyebrow">产品明细分析</span><h2 id="productDetailExplorerTitle">筛选业务组合，再看量、价、额</h2><p>${esc(detailDescription)}</p></div><span class="section-note">${feedback ? "准备中" : `${formatInteger(detailData?.totalGroups || items.length)} 个匹配产品`}</span></header><div class="explorer-toolbar"><label class="profit-search"><span>搜索产品利润</span><input type="search" data-profit-search="product" value="${attr(controls.search)}" maxlength="120" autocomplete="off" placeholder="输入产品名称中的任意文字"><small>在当前业务筛选范围内模糊匹配</small></label>${renderExplorerFilters(PRODUCT_FILTERS, "product", controls.filters)}<div class="metric-toolbar"><fieldset><legend>分析指标</legend>${Object.entries(metricLabels).map(([key, label]) => { const disabled = key !== "revenue" && detailData && !quantityReady; return `<button type="button" data-product-metric="${key}" aria-pressed="${controls.metric === key}" class="${controls.metric === key ? "active" : ""}" ${disabled ? "disabled title=\"当前报表没有可用数量字段\"" : ""}>${label}</button>`; }).join("")}</fieldset><label><span>${IS_DESKTOP ? "排行数量口径" : "数量口径"}</span><select data-quantity-metric ${detailData && !quantityReady ? "disabled" : ""}>${quantityOptions.map(([value, label]) => `<option value="${value}" ${quantityMetric === value ? "selected" : ""} ${available.length && !available.includes(value) ? "disabled" : ""}>${label}${available.length && !available.includes(value) ? "（不可用）" : ""}</option>`).join("")}</select></label></div></div>${feedback || renderProductDetailResults(items, { ...controls, quantityMetric }, quantityLabel, a, detailData)}</section>`;
  }

  function renderProductDetailResults(items, controls, quantityLabel, a, data) {
    const rows = items.map((item) => {
      const quantity = item.quantity;
      const value = controls.metric === "quantity" ? quantity : controls.metric === "unitPrice" ? item.unitPrice : item.revenue;
      return { ...item, selectedQuantity: quantity, selectedValue: value };
    }).filter((item) => item.selectedValue !== null).sort((x, y) => y.selectedValue - x.selectedValue);
    const max = Math.max(...rows.map((item) => Math.abs(item.selectedValue || 0)), 1);
    const formatSelected = (value) => controls.metric === "quantity" ? formatQuantity(value) : formatCurrency(value);
    if (!IS_DESKTOP) return `<div class="product-detail-layout"><div class="detail-ranking" aria-label="产品${controls.metric === "revenue" ? "销售额" : controls.metric === "quantity" ? quantityLabel : "单价"}排行">${rows.slice(0, 15).map((item, index) => `<div class="detail-rank-row"><span>${String(index + 1).padStart(2, "0")}</span><strong title="${attr(item.name)}">${esc(item.name)}</strong><div class="bar-track"><i style="width:${Math.max(3, Math.abs(item.selectedValue) / max * 100)}%"></i></div><b>${esc(formatSelected(item.selectedValue))}</b></div>`).join("")}</div><div class="data-table-wrap compact sticky-head-table product-profit-table"><table class="data-table"><thead><tr><th>产品</th><th>销售额</th><th>${esc(quantityLabel)}</th><th>平均单价</th><th>可比成本</th><th>毛利润</th><th>${a.capabilities.grossProfitAvailable ? "毛利率" : "利润状态"}</th><th title="${attr(PROFIT_COVERAGE_EXPLANATION)}">可算覆盖</th></tr></thead><tbody>${rows.slice(0, 500).map((item) => `<tr><td><strong>${esc(item.name)}</strong></td><td>${esc(formatCurrencyExact(item.revenue))}</td><td>${item.selectedQuantity === null ? "—" : esc(formatInteger(item.selectedQuantity))}</td><td>${item.unitPrice !== null ? esc(formatCurrencyExact(item.unitPrice)) : "—"}</td><td>${item.comparableCost === null ? "—" : esc(formatCurrencyExact(item.comparableCost))}</td><td class="${item.grossProfit !== null && item.grossProfit < 0 ? "negative-text" : ""}">${item.grossProfit === null ? "—" : esc(formatCurrencyExact(item.grossProfit))}</td><td class="${item.grossMargin !== null && item.grossMargin < 0 ? "negative-text" : ""}">${esc(marginLabel(item))}</td><td title="${attr(PROFIT_COVERAGE_EXPLANATION)}">${item.profitCoverage === null ? "—" : esc(formatPercent(item.profitCoverage))}</td></tr>`).join("")}</tbody></table></div></div>`;
    const perUnitLabel = controls.quantityMetric === "convertedQuantity" ? "元/只" : "元/源单位";
    const startIndex = numberValue(data?.offset, controls.page * controls.pageSize);
    const total = numberValue(data?.totalGroups, rows.length);
    const currentPage = Math.floor(startIndex / Math.max(numberValue(data?.pageSize, controls.pageSize), 1));
    const totalPages = Math.max(1, Math.ceil(total / controls.pageSize));
    const unitProfitLabel = controls.quantityMetric === "convertedQuantity" ? "单只毛利" : "单位毛利";
    return `<div class="product-detail-layout vertical-ledger"><div class="detail-ranking" aria-label="产品${controls.metric === "revenue" ? "销售额" : controls.metric === "quantity" ? quantityLabel : "单价"}排行">${rows.slice(0, 15).map((item, index) => `<div class="detail-rank-row"><span>${String(startIndex + index + 1).padStart(2, "0")}</span><strong title="${attr(item.name)}">${esc(item.name)}</strong><div class="bar-track"><i style="width:${Math.max(3, Math.abs(item.selectedValue) / max * 100)}%"></i></div><b>${esc(formatSelected(item.selectedValue))}</b></div>`).join("")}</div><div class="responsive-metric-list product-metric-ledger" role="list" aria-label="产品经营明细">${rows.map((item, index) => `<article class="responsive-metric-row" role="listitem"><header><span>${String(startIndex + index + 1).padStart(2, "0")}</span><strong>${esc(item.name)}</strong>${specBadge(item)}</header><dl><div><dt>销售数量 <small>源单位 / 混合单位</small></dt><dd>${item.salesQuantity === null ? "—" : `${esc(formatQuantity(item.salesQuantity))} 源单位`}</dd></div><div><dt>换算只数 <small>统一单位</small></dt><dd>${item.convertedQuantity === null ? "—" : `${esc(formatQuantity(item.convertedQuantity))} 只`}</dd></div><div><dt>平均单价 <small>${perUnitLabel}</small></dt><dd>${item.unitPrice === null ? "—" : esc(formatCurrencyExact(item.unitPrice))}</dd></div><div><dt>平均成本 <small>${perUnitLabel}</small></dt><dd>${item.averageCost === null ? "—" : esc(formatCurrencyExact(item.averageCost))}</dd></div><div><dt>${unitProfitLabel} <small>${perUnitLabel}</small></dt><dd class="${item.unitGrossProfit !== null && item.unitGrossProfit < 0 ? "negative-text" : ""}">${item.unitGrossProfit === null ? "—" : esc(formatCurrencyExact(item.unitGrossProfit))}</dd></div><div><dt>销售额</dt><dd>${esc(formatCurrencyExact(item.revenue))}</dd></div><div><dt>可比成本</dt><dd>${item.comparableCost === null ? "—" : esc(formatCurrencyExact(item.comparableCost))}</dd></div><div><dt>毛利润</dt><dd class="${item.grossProfit !== null && item.grossProfit < 0 ? "negative-text" : ""}">${item.grossProfit === null ? "—" : esc(formatCurrencyExact(item.grossProfit))}</dd></div><div><dt>${a.capabilities.grossProfitAvailable ? "毛利率" : "利润状态"}</dt><dd class="${item.grossMargin !== null && item.grossMargin < 0 ? "negative-text" : ""}">${esc(marginLabel(item))}</dd></div><div><dt title="${attr(PROFIT_COVERAGE_EXPLANATION)}">毛利可算覆盖率</dt><dd title="${attr(PROFIT_COVERAGE_EXPLANATION)}">${item.profitCoverage === null ? "—" : esc(formatPercent(item.profitCoverage))}</dd></div></dl></article>`).join("")}</div></div><footer class="dimension-pagination product-pagination"><span>共 ${formatInteger(total)} 个产品，当前 ${formatInteger(startIndex + 1)}–${formatInteger(startIndex + rows.length)}</span><div><button type="button" data-product-page="${Math.max(0, currentPage - 1)}" ${currentPage <= 0 ? "disabled" : ""}>上一页</button><strong>${formatInteger(currentPage + 1)} / ${formatInteger(totalPages)}</strong><button type="button" data-product-page="${currentPage + 1}" ${data?.hasMore ? "" : "disabled"}>下一页</button></div></footer>`;
  }

  function renderCustomer(input) {
    const a = desktopScopedAnalysis(input);
    const capabilities = a.capabilities;
    const hasGross = capabilities.grossProfitAvailable;
    const summaryEntry = state.explorers.get("customerSummary");
    const scopedReady = summaryEntry?.status === "success";
    const hasBusinessFilters = hasActiveFilters(state.customerControls.filters);
    const list = scopedReady
      ? [...(summaryEntry.data?.items || [])].sort((left, right) => numberValue(right.revenue) - numberValue(left.revenue))
      : hasBusinessFilters ? [] : sortedByRevenue(a.customers);
    const totalRevenue = scopedReady ? numberValue(summaryEntry.data?.totals?.revenue) : numberValue(a.summary.revenue);
    const customerCount = scopedReady ? summaryEntry.data.totalGroups : hasBusinessFilters ? 0 : a.customers.length;
    const top = list[0];
    const comparableCustomers = hasGross ? list.filter((item) => item.grossProfit !== null && Number.isFinite(item.grossProfit)) : [];
    const best = comparableCustomers
      .filter((item) => item.revenue > 0 && item.grossProfit > 0 && item.grossMargin !== null && Number.isFinite(item.grossMargin))
      .sort((x, y) => y.grossMargin - x.grossMargin)[0] || null;
    const worst = [...comparableCustomers].sort((x, y) => x.grossProfit - y.grossProfit)[0] || null;
    const topShare = top ? top.revenue / Math.max(totalRevenue, 1) : 0;
    const top3Share = list.slice(0, 3).reduce((sum, item) => sum + numberValue(item.revenue), 0) / Math.max(totalRevenue, 1);
    const avgRevenue = customerCount ? totalRevenue / customerCount : 0;
    const losingCount = hasGross ? comparableCustomers.filter((item) => item.grossProfit < 0).length : 0;
    const filterContext = hasBusinessFilters ? `当前筛选范围（${activeFilterLabel(state.customerControls.filters)}）` : "全部客户";
    const conclusion = top
      ? `${filterContext}内，${top.name}贡献 ${formatPercent(topShare)} 的收入，客户集中度${topShare > .4 ? "需要关注" : "仍在可控范围"}。`
      : scopedReady ? `${filterContext}内没有可用客户。` : "正在按客户价值筛选重新计算贡献、集中度与盈利质量。";
    const worstProfit = worst?.grossProfit ?? null;
    const explanation = worst && worstProfit < 0
      ? `${filterContext}内，${worst.name}毛利润为 ${formatCurrency(worstProfit)}。高收入不等于高价值，应把履约投入与客户贡献一起复核。`
      : `${filterContext}识别 ${customerCount} 个客户，单客平均收入 ${formatCurrency(avgRevenue)}${hasGross ? "；利润按收入与成本同时有效的可比行计算" : "；利润口径尚未开放"}。`;
    const kpis = [
      kpi("筛选内客户", scopedReady || !hasBusinessFilters ? formatInteger(customerCount) : "计算中", hasGross ? `${losingCount} 个可比口径亏损客户` : "已识别客户维度", "EV-SUM-REV", "indigo", null, losingCount > 0),
      kpi("第一大客户占比", top ? formatPercent(topShare) : scopedReady ? "—" : "计算中", top?.name || filterContext, "EV-SUM-REV", topShare > .4 ? "amber" : "sky", topShare),
      kpi("前三客户占比", list.length ? formatPercent(top3Share) : scopedReady ? "—" : "计算中", top3Share > .7 ? "集中度偏高" : "结构相对分散", "EV-SUM-REV", top3Share > .7 ? "coral" : "lavender", top3Share),
      kpi("筛选内盈利质量", best ? formatPercent(best.grossMargin) : hasGross ? scopedReady ? "暂无正毛利客户" : "计算中" : "不可用", best ? `${best.name}毛利率最高` : filterContext, "EV-SUM-GP", "lavender")
    ];
    return `<div class="view-stack">
      ${renderKpis(kpis)}
      ${renderCustomerFilterPanel()}
      ${renderCustomerChart(a, topShare, list, { scoped: scopedReady || hasBusinessFilters, context: filterContext })}
      <div class="section-grid equal customer-drill-grid">
        ${renderChannelCustomerDrill()}
        ${renderCustomerProductDrill(a)}
      </div>
      ${renderCustomerValueExplorer(a)}
      ${renderLosingCustomerList()}
      ${renderConclusion("客户分析", conclusion, explanation, hasGross ? ["EV-SUM-REV", "EV-SUM-GP"] : ["EV-SUM-REV"])}
    </div>`;
  }

  function renderCustomerFilterPanel() {
    return `<section class="surface filter-command-bar customer-filter-bar" aria-labelledby="customerFilterTitle"><div class="customer-filter-intro"><span class="eyebrow">客户价值筛选</span><h2 id="customerFilterTitle">从组织与渠道切换观察范围</h2><p>顶部贡献、集中度、盈利质量以及下方客户清单都会随筛选重新计算。</p><span class="filter-scope-note">筛选改变分析口径 · 搜索只收窄客户明细</span></div><div class="customer-filter-controls"><label class="profit-search customer-profit-search"><span>搜索客户利润</span><input type="search" data-profit-search="customer" value="${attr(state.customerControls.search)}" maxlength="120" autocomplete="off" placeholder="输入客户名称中的任意文字"><small>模糊匹配客户价值与亏损清单</small></label>${renderExplorerFilters(CUSTOMER_FILTERS, "customer", state.customerControls.filters)}</div></section>`;
  }

  function renderChannelCustomerDrill() {
    const channelEntry = state.explorers.get("customerChannels");
    const channels = channelEntry?.data?.items || [];
    const selected = state.customerControls.selectedChannel || channels[0]?.name || "";
    const feedback = renderExplorerFeedback("channelCustomers", { loadingTitle: "正在下钻渠道客户", emptyTitle: "该渠道暂无客户", emptyText: "选择其他渠道或调整组织筛选。" });
    const items = state.explorers.get("channelCustomers")?.data?.items || [];
    const max = Math.max(...items.map((item) => Math.abs(item.revenue || 0)), 1);
    return `<section class="surface channel-drill" aria-labelledby="channelDrillTitle"><header class="section-heading"><div><span class="eyebrow">客户组合 · 渠道下钻</span><h2 id="channelDrillTitle">渠道前十名客户</h2><p>先选择渠道，再比较该渠道客户的收入与利润质量。</p></div><span class="section-note">Top 10</span></header><div class="channel-switcher" role="listbox" aria-label="选择渠道">${channels.length ? channels.slice(0, 12).map((item) => `<button type="button" role="option" aria-selected="${item.name === selected}" class="${item.name === selected ? "active" : ""}" data-select-channel="${attr(item.name)}"><span>${esc(item.name)}</span><small>${formatCurrency(item.revenue)}</small></button>`).join("") : `<span class="muted-label">渠道数据读取中…</span>`}</div>${feedback || `<ol class="compact-ranking">${items.slice(0, 10).map((item, index) => `<li><span>${String(index + 1).padStart(2, "0")}</span><strong title="${attr(item.name)}">${esc(item.name)}</strong><div class="bar-track"><i style="width:${Math.max(3, Math.abs(item.revenue || 0) / max * 100)}%"></i></div><b>${esc(formatCurrency(item.revenue))}</b><em class="${item.grossMargin !== null && item.grossMargin < 0 ? "negative-text" : ""}">${item.grossMargin === null ? "—" : esc(formatPercent(item.grossMargin))}</em></li>`).join("")}</ol>`}</section>`;
  }

  function renderCustomerProductDrill(a) {
    const customerItems = state.explorers.get("customerValue")?.data?.items || sortedByRevenue(a.customers);
    const controls = state.customerControls;
    const selectedCustomers = [...new Set((controls.selectedCustomers || []).map(String).filter(Boolean))];
    const selected = controls.selectedCustomer || selectedCustomers[0] || customerItems[0]?.name || "";
    const feedback = selectedCustomers.length
      ? renderExplorerFeedback("customerProducts", { loadingTitle: "正在汇总所选客户的产品利润", emptyTitle: "所选客户暂无产品明细", emptyText: "减少选择范围或选择其他客户后再试。" })
      : `<div class="explorer-feedback empty"><span>+</span><div><strong>请选择一个或多个客户</strong><p>可同时选择天虹、盒马等客户，结果会按产品合并汇总。</p></div></div>`;
    const items = state.explorers.get("customerProducts")?.data?.items || [];
    if (!IS_DESKTOP) return `<section class="surface customer-product-drill" aria-labelledby="customerProductTitle"><header class="section-heading"><div><span class="eyebrow">客户 → 产品利润</span><h2 id="customerProductTitle">客户买了什么，利润来自哪里</h2><p>销售额保留完整规模；可比收入与成本只统计两者同时有效的行，确保毛利润可以复核。</p></div></header><label class="drill-select"><span>选择客户</span><select data-customer-drill>${customerItems.map((item) => `<option value="${attr(item.name)}" ${item.name === selected ? "selected" : ""}>${esc(item.name)}</option>`).join("")}</select></label>${feedback || `<div class="data-table-wrap compact"><table class="data-table"><thead><tr><th>产品</th><th>销售额</th><th>可比收入</th><th>可比成本</th><th>毛利润</th><th>毛利率</th><th title="${attr(PROFIT_COVERAGE_EXPLANATION)}">可算覆盖</th></tr></thead><tbody>${items.slice(0, 30).map((item) => `<tr><td><strong>${esc(item.name)}</strong></td><td>${esc(formatCurrencyExact(item.revenue))}</td><td>${item.comparableRevenue === null ? "—" : esc(formatCurrencyExact(item.comparableRevenue))}</td><td>${item.comparableCost === null ? "—" : esc(formatCurrencyExact(item.comparableCost))}</td><td class="${item.grossProfit !== null && item.grossProfit < 0 ? "negative-text" : ""}">${item.grossProfit === null ? "—" : esc(formatCurrencyExact(item.grossProfit))}</td><td class="${item.grossMargin !== null && item.grossMargin < 0 ? "negative-text" : ""}">${item.grossMargin === null ? "—" : esc(formatPercent(item.grossMargin))}</td><td title="${attr(PROFIT_COVERAGE_EXPLANATION)}">${item.profitCoverage === null ? "—" : esc(formatPercent(item.profitCoverage))}</td></tr>`).join("")}</tbody></table></div>`}</section>`;
    const customerSummaryItems = state.explorers.get("customerSummary")?.data?.items || [];
    const optionNames = [...new Set([
      ...selectedCustomers,
      ...customerItems.map((item) => String(item.name || "")),
      ...customerSummaryItems.map((item) => String(item.name || "")),
      ...explorerOptions("customer", DIMENSIONS.customer)
    ].filter(Boolean))].sort((left, right) => left.localeCompare(right, "zh-CN"));
    const filterKey = "customer:drill";
    const optionSearch = state.filterSearches[filterKey] || "";
    const normalizedSearch = normalizeFuzzyText(optionSearch);
    const selectedSet = new Set(selectedCustomers);
    const selectionControl = `<div class="customer-drill-selector"><details class="filter-multiselect customer-drill-multiselect" data-filter-key="${filterKey}" ${state.openFilterKey === filterKey ? "open" : ""}><summary><span>选择客户（可多选）</span><b data-filter-summary>${selectedCustomers.length ? `已选 ${formatInteger(selectedCustomers.length)} 项` : "请选择"}</b></summary><div class="multiselect-popover"><label class="multiselect-search"><span class="sr-only">搜索客户</span><input type="search" value="${attr(optionSearch)}" data-filter-option-search="${filterKey}" autocomplete="off" placeholder="输入客户名称快速查找"></label><div class="multiselect-options">${optionNames.length ? optionNames.map((name) => `<label data-option-text="${attr(name)}" ${normalizedSearch && !normalizeFuzzyText(name).includes(normalizedSearch) ? "hidden" : ""}><input type="checkbox" value="${attr(name)}" data-customer-drill-multi ${selectedSet.has(name) ? "checked" : ""}><span title="${attr(name)}">${esc(name)}</span></label>`).join("") : `<span class="multiselect-empty">等待读取客户选项…</span>`}</div><footer><small>所选客户将合并计算产品利润</small><button type="button" data-clear-customer-drill ${selectedCustomers.length ? "" : "disabled"}>清除</button></footer></div></details><div class="customer-selection-chips" aria-label="已选择客户">${selectedCustomers.length ? selectedCustomers.map((name) => `<button type="button" data-remove-customer-drill="${attr(name)}" title="取消选择 ${attr(name)}"><span>${esc(name)}</span><i aria-hidden="true">×</i></button>`).join("") : `<span>尚未选择客户</span>`}</div></div>`;
    return `<section class="surface customer-product-drill" aria-labelledby="customerProductTitle"><header class="section-heading"><div><span class="eyebrow">客户 → 产品利润</span><h2 id="customerProductTitle">客户买了什么，利润来自哪里</h2><p>可同时选择多个客户并按产品合并；纵向全宽明细无需左右滚动，销售额与可比口径保持同屏复核。</p></div><span class="section-note">${selectedCustomers.length ? `${formatInteger(selectedCustomers.length)} 位客户` : "待选择"}</span></header>${selectionControl}${feedback || `<div class="responsive-metric-list customer-product-ledger" role="list">${items.slice(0, 30).map((item, index) => `<article class="responsive-metric-row" role="listitem"><header><span>${String(index + 1).padStart(2, "0")}</span><strong>${esc(item.name)}</strong>${specBadge(item)}</header><dl><div><dt>销售额</dt><dd>${esc(formatCurrencyExact(item.revenue))}</dd></div><div><dt>可比收入</dt><dd>${item.comparableRevenue === null ? "—" : esc(formatCurrencyExact(item.comparableRevenue))}</dd></div><div><dt>可比成本</dt><dd>${item.comparableCost === null ? "—" : esc(formatCurrencyExact(item.comparableCost))}</dd></div><div><dt>毛利润</dt><dd class="${item.grossProfit !== null && item.grossProfit < 0 ? "negative-text" : ""}">${item.grossProfit === null ? "—" : esc(formatCurrencyExact(item.grossProfit))}</dd></div><div><dt>毛利率</dt><dd class="${item.grossMargin !== null && item.grossMargin < 0 ? "negative-text" : ""}">${item.grossMargin === null ? "—" : esc(formatPercent(item.grossMargin))}</dd></div><div><dt title="${attr(PROFIT_COVERAGE_EXPLANATION)}">毛利可算覆盖率</dt><dd title="${attr(PROFIT_COVERAGE_EXPLANATION)}">${item.profitCoverage === null ? "—" : esc(formatPercent(item.profitCoverage))}</dd></div></dl></article>`).join("")}</div>`}</section>`;
  }

  function renderCustomerValueExplorer(a) {
    const feedback = renderExplorerFeedback("customerValue", { loadingTitle: "正在应用客户价值筛选", emptyTitle: "当前条件下没有客户", emptyText: "清除部分筛选条件后再试。" });
    const data = state.explorers.get("customerValue")?.data;
    const items = data?.items || [];
    const selectedCustomers = new Set(state.customerControls.selectedCustomers || []);
    return `<section class="surface customer-value-explorer" aria-labelledby="customerValueDetailTitle"><header class="section-heading"><div><span class="eyebrow">客户价值明细</span><h2 id="customerValueDetailTitle">收入贡献与产品利润下钻入口</h2><p>占比按当前业务筛选下的全部客户计算；名称搜索只收窄明细，不改写分母。表头在清单内保持可见。</p></div><span class="section-note">${feedback ? "准备中" : `${formatInteger(data?.totalGroups || items.length)} 个匹配客户`}</span></header>${feedback || `<div class="data-table-wrap customer-profit-table sticky-head-table"><table class="data-table selectable-table"><thead><tr><th>客户</th><th>销售额</th><th>销售额占比</th><th>毛利润</th><th>毛利率</th><th title="${attr(PROFIT_COVERAGE_EXPLANATION)}">可算覆盖</th><th>产品数</th></tr></thead><tbody>${items.map((item) => `<tr class="${selectedCustomers.has(item.name) ? "selected" : ""}"><td><button type="button" class="table-link" data-select-customer="${attr(item.name)}">${esc(item.name)}</button></td><td>${esc(formatCurrencyExact(item.revenue))}</td><td>${item.share === null ? "—" : esc(formatPercent(item.share))}</td><td class="${item.grossProfit !== null && item.grossProfit < 0 ? "negative-text" : ""}">${item.grossProfit === null ? "—" : esc(formatCurrencyExact(item.grossProfit))}</td><td class="${item.grossMargin !== null && item.grossMargin < 0 ? "negative-text" : ""}">${item.grossMargin === null ? "—" : esc(formatPercent(item.grossMargin))}</td><td title="${attr(PROFIT_COVERAGE_EXPLANATION)}">${item.profitCoverage === null ? "—" : esc(formatPercent(item.profitCoverage))}</td><td>${formatInteger(item.productCount)}</td></tr>`).join("")}</tbody></table></div>`}</section>`;
  }

  function customerSalesGroups(item) {
    const raw = Array.isArray(item?.salesGroups) ? item.salesGroups : item?.salesGroup ? [item.salesGroup] : [];
    return [...new Set(raw.map(String).map((value) => value.trim()).filter(Boolean))].sort((left, right) => left.localeCompare(right, "zh-CN"));
  }

  function renderCustomerSalesGroups(item) {
    const groups = customerSalesGroups(item);
    if (!groups.length) return "—";
    const full = groups.join("、");
    const short = groups.length > 2 ? `${groups.slice(0, 2).join("、")} 等 ${formatInteger(groups.length)} 组` : full;
    return `<span class="sales-group-summary" title="${attr(full)}">${esc(short)}</span>`;
  }

  function renderLosingCustomerList() {
    const feedback = renderExplorerFeedback("losingCustomers", { loadingTitle: "正在识别亏损客户", emptyTitle: "当前筛选下没有客户", emptyText: "调整筛选条件或搜索关键词后再试。" });
    const data = state.explorers.get("losingCustomers")?.data;
    const losses = (data?.items || []).filter((item) => item.profitAvailable && item.grossProfit !== null && item.grossProfit < 0)
      .sort((left, right) => left.grossProfit - right.grossProfit);
    const currentPage = data ? Math.floor(data.offset / Math.max(data.pageSize, 1)) : state.customerControls.lossPage;
    const totalPages = Math.max(1, Math.ceil((data?.totalGroups || 0) / 100));
    const body = feedback || (losses.length
      ? `<div class="data-table-wrap losing-customer-table sticky-head-table"><table class="data-table"><thead><tr><th>客户</th><th>销售组</th><th>销售额</th><th>可比收入</th><th>可比成本</th><th>毛利润</th><th>毛利率</th><th title="${attr(PROFIT_COVERAGE_EXPLANATION)}">可算覆盖</th><th>产品数</th></tr></thead><tbody>${losses.map((item) => `<tr><td><button type="button" class="table-link" data-select-customer="${attr(item.name)}">${esc(item.name)}</button></td><td>${renderCustomerSalesGroups(item)}</td><td>${esc(formatCurrencyExact(item.revenue))}</td><td>${item.comparableRevenue === null ? "—" : esc(formatCurrencyExact(item.comparableRevenue))}</td><td>${item.comparableCost === null ? "—" : esc(formatCurrencyExact(item.comparableCost))}</td><td class="negative-text">${esc(formatCurrencyExact(item.grossProfit))}</td><td class="negative-text">${item.grossMargin === null ? "—" : esc(formatPercent(item.grossMargin))}</td><td title="${attr(PROFIT_COVERAGE_EXPLANATION)}">${item.profitCoverage === null ? "—" : esc(formatPercent(item.profitCoverage))}</td><td>${formatInteger(item.productCount)}</td></tr>`).join("")}</tbody></table></div><footer class="dimension-pagination"><span>共 ${formatInteger(data.totalGroups)} 个亏损客户，当前 ${formatInteger(data.offset + 1)}–${formatInteger(data.offset + losses.length)}</span><div><button type="button" data-loss-page="${Math.max(0, currentPage - 1)}" ${currentPage <= 0 ? "disabled" : ""}>上一页</button><strong>${formatInteger(currentPage + 1)} / ${formatInteger(totalPages)}</strong><button type="button" data-loss-page="${currentPage + 1}" ${data.hasMore ? "" : "disabled"}>下一页</button></div></footer>`
      : `<div class="explorer-feedback empty"><span>✓</span><div><strong>当前范围没有可比口径亏损客户</strong><p>仅统计收入与成本同时有效、且毛利润小于 0 的客户。</p></div></div>`);
    return `<section class="surface losing-customer-list" aria-labelledby="losingCustomerTitle"><header class="section-heading"><div><span class="eyebrow">亏损客户清单</span><h2 id="losingCustomerTitle">优先核对哪些客户</h2><p>沿用上方组织、渠道与客户名称筛选；销售组按客户涉及的全部组织汇总，表头在清单内保持可见。</p></div><span class="section-note">${feedback ? "准备中" : `${formatInteger(data?.totalGroups || 0)} 个亏损客户`}</span></header>${body}</section>`;
  }

  function renderSalesGroup(a) {
    const controls = state.salesGroupControls;
    const entry = state.explorers.get("salesGroupSummary");
    const items = entry?.data?.items || [];
    const totals = entry?.data?.totals || {};
    const feedback = renderExplorerFeedback("salesGroupSummary", { loadingTitle: "正在按调拨成本核算销售组织利润", emptyTitle: "当前条件下没有销售组织数据", emptyText: "请确认源表包含销售区域、销售部门或销售组字段。" });
    const totalRevenue = totals.revenue ?? items.reduce((sum, item) => sum + (item.revenue || 0), 0);
    const outboundQuantity = nullableNumber(totals.outboundQuantity);
    const convertedQuantity = nullableNumber(totals.convertedQuantity);
    const selectedTotals = salesGroupProfitMetrics(totals, controls.costMetric);
    const profitReady = selectedTotals.profit !== null;
    const effectiveRevenue = profitReady ? selectedTotals.comparableRevenue : null;
    const effectiveProfit = profitReady ? selectedTotals.profit : null;
    const effectiveCost = profitReady ? selectedTotals.comparableCost : null;
    const reportedCost = controls.costMetric === "transferCost" ? nullableNumber(totals.transferCost) : nullableNumber(totals.cost);
    const effectiveMargin = profitReady ? selectedTotals.margin : null;
    const costLabel = controls.costMetric === "transferCost" ? "调拨成本" : "已确认成本";
    const selectedGroup = controls.selectedGroup || (controls.groupBy === DIMENSIONS.salesGroup ? items[0]?.name : "") || "";
    const levelLabel = controls.groupBy;
    const kpis = [
      kpi("出库数量", feedback ? "计算中" : outboundQuantity === null ? "不可用" : formatQuantity(outboundQuantity), `${formatInteger(items.length)} 个${levelLabel}单元`, "EV-QUALITY-001", "indigo"),
      kpi("换算只数", feedback ? "计算中" : convertedQuantity === null ? "不可用" : formatQuantity(convertedQuantity), "独立数量口径", "EV-QUALITY-001", "sky"),
      kpi("销售收入", feedback ? "计算中" : formatCurrency(totalRevenue), `${levelLabel}筛选后合计`, "EV-SUM-REV", "sky"),
      kpi(costLabel, feedback ? "计算中" : reportedCost === null ? "不可用" : formatCurrency(reportedCost), effectiveCost === null ? "毛利可比口径不足" : `其中可比口径 ${formatCurrency(effectiveCost)}`, "EV-SUM-COST", "lavender"),
      kpi("毛利润", feedback ? "计算中" : effectiveProfit === null ? "不可用" : formatCurrency(effectiveProfit), controls.costMetric === "transferCost" ? "可比收入 − 可比调拨成本" : "可比收入 − 可比已确认成本", "EV-SUM-GP", effectiveProfit < 0 ? "coral" : "lavender", effectiveMargin, effectiveProfit < 0),
      kpi("毛利率", feedback ? "计算中" : effectiveMargin === null ? "不可用" : formatPercent(effectiveMargin), effectiveRevenue === null ? "等待成本数据" : `可比收入 ${formatCurrency(effectiveRevenue)}`, "EV-SUM-GP", effectiveMargin < 0 ? "coral" : "lavender", effectiveMargin, effectiveMargin < 0)
    ];
    const conclusion = feedback
      ? "正在根据调拨成本口径生成销售组织利润。"
      : effectiveProfit === null
        ? `${levelLabel}销售额为 ${formatCurrency(totalRevenue)}，但当前没有收入与${controls.costMetric === "transferCost" ? "调拨成本" : "已确认成本"}同时有效的可比行；缺失成本不会按 0 计算。`
        : effectiveProfit < 0
        ? `${levelLabel}合计利润为 ${formatCurrency(effectiveProfit)}，应优先下钻亏损销售组的客户与产品。`
        : `${levelLabel}合计利润为 ${formatCurrency(effectiveProfit)}，可继续比较各销售组的利润贡献。`;
    return `<div class="view-stack sales-group-view">${renderKpis(kpis)}<section class="surface org-command-center" aria-labelledby="orgCommandTitle"><div class="section-heading"><div><span class="eyebrow">阿米巴利润口径</span><h2 id="orgCommandTitle">销售组（产品组）多指标分析</h2><p>销售区域、销售部门、销售组可组合筛选；数量、换算只数、收入、调拨成本、毛利润和毛利率同屏核对。</p></div><label class="cost-metric-select"><span>成本口径</span><select data-cost-metric><option value="transferCost" ${controls.costMetric === "transferCost" ? "selected" : ""}>调拨成本（默认）</option><option value="cost" ${controls.costMetric === "cost" ? "selected" : ""}>已确认成本（对照）</option></select></label></div>${renderExplorerFilters(SALES_GROUP_FILTERS, "salesGroup", controls.filters)}<div class="org-level-switch" role="group" aria-label="组织分析层级">${[DIMENSIONS.salesRegion, DIMENSIONS.salesDepartment, DIMENSIONS.salesGroup].map((dimension) => `<button type="button" data-sales-group-by="${attr(dimension)}" aria-pressed="${controls.groupBy === dimension}" class="${controls.groupBy === dimension ? "active" : ""}">${esc(dimension)}</button>`).join("")}</div></section>${IS_DESKTOP ? renderSalesDepartmentTransferComparison() : ""}${renderSalesGroupSummary(items, feedback, selectedGroup, controls)}<div class="section-grid org-drill-grid">${renderSalesGroupDrill("salesGroupCustomers", "客户情况", "该销售组服务了哪些客户", "customer")}${renderSalesGroupDrill("salesGroupProducts", "产品情况", "该销售组销售了哪些产品", "product")}</div>${renderConclusion("销售组利润分析", conclusion, `当前成本口径为${controls.costMetric === "transferCost" ? "调拨成本" : "已确认成本"}。选择销售组后，客户和产品下钻会保持同一组织与成本口径。`, ["EV-SUM-REV", "EV-SUM-COST", "EV-SUM-GP"])}</div>`;
  }

  function renderSalesDepartmentTransferComparison() {
    const feedback = renderExplorerFeedback("salesDepartmentTransfer", { loadingTitle: "正在按调拨价比较销售部门", emptyTitle: "暂无销售部门调拨价数据", emptyText: "请确认源表包含销售部门与调拨成本字段。" });
    const items = state.explorers.get("salesDepartmentTransfer")?.data?.items || [];
    return `<section class="surface sales-department-comparison"><header class="section-heading"><div><span class="eyebrow">销售部门 · 调拨价</span><h2>部门可比毛利对比</h2><p>毛利只使用收入与调拨成本同时有效的行；低覆盖仍展示可算结果并明确提示，不再显示成原因不明的空值。</p></div><span class="section-note">${feedback ? "准备中" : `${formatInteger(items.length)} 个部门`}</span></header>${feedback || `<div class="responsive-metric-list department-profit-ledger">${items.map((item, index) => {
      const transferReady = item.transferGrossProfit !== null;
      const coverage = item.transferProfitCoverage ?? (item.revenue ? 0 : null);
      const coverageWarning = coverage !== null && coverage < .8;
      return `<article class="responsive-metric-row"><header><span>${String(index + 1).padStart(2, "0")}</span><strong>${esc(item.name)}</strong></header><dl><div><dt>数量</dt><dd>${item.outboundQuantity === null ? "—" : esc(formatQuantity(item.outboundQuantity))}</dd></div><div><dt>换算只数</dt><dd>${item.convertedQuantity === null ? "—" : esc(formatQuantity(item.convertedQuantity))}</dd></div><div><dt>价税合计</dt><dd>${esc(formatCurrencyExact(item.revenue))}</dd></div><div><dt>调拨成本</dt><dd>${item.transferCost === null ? "未识别" : esc(formatCurrencyExact(item.transferCost))}</dd></div><div><dt>毛利</dt><dd class="${item.transferGrossProfit !== null && item.transferGrossProfit < 0 ? "negative-text" : ""}">${transferReady ? esc(formatCurrencyExact(item.transferGrossProfit)) : "不可计算"}</dd></div><div><dt>毛利率</dt><dd class="${item.transferGrossMargin !== null && item.transferGrossMargin < 0 ? "negative-text" : ""}">${item.transferGrossMargin === null ? "不可计算" : esc(formatPercent(item.transferGrossMargin))}</dd></div></dl>${coverageWarning ? `<p class="metric-basis-note negative-text" title="${attr(PROFIT_COVERAGE_EXPLANATION)}">当前覆盖低于 80%，毛利仅代表已匹配调拨成本的收入，请结合覆盖率复核。</p>` : ""}</article>`;
    }).join("")}</div>`}</section>`;
  }

  function renderSalesGroupSummary(items, feedback, selectedGroup, controls) {
    if (feedback) return `<section class="surface org-profit-table"><div class="section-heading"><div><span class="eyebrow">组织利润排行</span><h2>${esc(controls.groupBy)}利润表现</h2></div></div>${feedback}</section>`;
    const sorted = [...items].sort((a, b) => {
      const aMetric = salesGroupProfitMetrics(a, controls.costMetric);
      const bMetric = salesGroupProfitMetrics(b, controls.costMetric);
      return Number(bMetric.profit !== null) - Number(aMetric.profit !== null)
        || numberValue(bMetric.profit, -Infinity) - numberValue(aMetric.profit, -Infinity);
    });
    const costName = controls.costMetric === "transferCost" ? "调拨成本" : "已确认成本";
    const rows = sorted.map((item) => {
      const displayedCost = salesGroupCost(item, controls.costMetric);
      const metric = salesGroupProfitMetrics(item, controls.costMetric);
      const missingTransfer = controls.costMetric === "transferCost" && metric.profit === null;
      const profitCell = metric.profit !== null
        ? esc(formatCurrencyExact(metric.profit))
        : missingTransfer && item.confirmedGrossProfit !== null
          ? `<span class="cell-missing">不可计算</span><small class="cell-sub" title="${attr("该单元没有可用调拨成本，未按 0 计算；此处为已确认成本口径对照")}">对照 ${esc(formatCurrency(item.confirmedGrossProfit))}</small>`
          : `<span class="cell-missing" title="${attr("该单元没有可用调拨成本，未按 0 计算")}">不可计算</span>`;
      return `<tr class="${item.name === selectedGroup ? "selected" : ""}"><td>${controls.groupBy === DIMENSIONS.salesGroup ? `<button class="table-link" type="button" data-select-sales-group="${attr(item.name)}">${esc(item.name)}</button>` : `<strong>${esc(item.name)}</strong>`}</td><td>${item.outboundQuantity === null ? "—" : esc(formatQuantity(item.outboundQuantity))}</td><td>${item.convertedQuantity === null ? "—" : esc(formatQuantity(item.convertedQuantity))}</td><td>${esc(formatCurrencyExact(item.revenue))}</td><td>${displayedCost === null ? "未识别" : esc(formatCurrencyExact(displayedCost))}</td><td class="${metric.profit !== null && metric.profit < 0 ? "negative-text" : ""}">${profitCell}</td><td class="${metric.margin !== null && metric.margin < 0 ? "negative-text" : ""}">${metric.margin === null ? "—" : esc(formatPercent(metric.margin))}</td><td title="${attr(PROFIT_COVERAGE_EXPLANATION)}">${metric.coverage === null ? (item.revenue ? "0.0%" : "—") : esc(formatPercent(metric.coverage))}</td><td>${formatInteger(item.customerCount)}</td><td>${formatInteger(item.productCount)}</td></tr>`;
    }).join("");
    return `<section class="surface org-profit-table" aria-labelledby="orgProfitTableTitle"><header class="section-heading"><div><span class="eyebrow">组织多指标排行</span><h2 id="orgProfitTableTitle">${esc(controls.groupBy)}数量、收入与毛利表现</h2><p>成本列展示筛选范围内的完整${costName}；毛利润按收入与成本同时有效的可比行计算，低覆盖结果仍显示并保留覆盖提示。</p></div><span class="section-note">${formatInteger(sorted.length)} 个单元</span></header><p class="table-hint" title="${attr(PROFIT_COVERAGE_EXPLANATION)}">“可算覆盖”达到 100% 只表示每笔收入都能匹配当前成本口径，不等于毛利率 100%。</p><div class="data-table-wrap sticky-head-table"><table class="data-table selectable-table org-metric-table"><thead><tr><th>${esc(controls.groupBy)}</th><th>出库数量</th><th>换算只数</th><th>收入</th><th>${costName}</th><th>毛利润</th><th>毛利率</th><th title="${attr(PROFIT_COVERAGE_EXPLANATION)}">可算覆盖</th><th>客户数</th><th>产品数</th></tr></thead><tbody>${rows}</tbody></table></div>${controls.groupBy !== DIMENSIONS.salesGroup ? `<p class="table-hint">切换到“销售组”层级后，可点击销售组下钻客户和产品。</p>` : ""}</section>`;
  }

  function salesGroupCost(item, costMetric) {
    return costMetric === "transferCost" ? nullableNumber(item?.transferCost) : nullableNumber(item?.cost);
  }

  function salesGroupProfitMetrics(item, costMetric) {
    if (costMetric === "transferCost") {
      return {
        comparableRevenue: nullableNumber(item?.transferComparableRevenue),
        comparableCost: nullableNumber(item?.transferComparableCost),
        profit: nullableNumber(item?.transferGrossProfit),
        margin: nullableRatio(item?.transferGrossMargin),
        coverage: nullableRatio(item?.transferProfitCoverage)
      };
    }
    return {
      comparableRevenue: nullableNumber(item?.comparableRevenue),
      comparableCost: nullableNumber(item?.comparableCost),
      profit: nullableNumber(item?.grossProfit),
      margin: nullableRatio(item?.grossMargin),
      coverage: nullableRatio(item?.profitCoverage)
    };
  }

  function hideSpecTooltip() {
    if (specTooltipEl) {
      specTooltipEl.remove();
      specTooltipEl = null;
    }
  }

  function showSpecTooltip(target) {
    const text = target?.dataset?.fullSpec || "";
    hideSpecTooltip();
    if (!text) return;
    specTooltipEl = document.createElement("div");
    specTooltipEl.className = "spec-tooltip";
    specTooltipEl.textContent = text;
    document.body.appendChild(specTooltipEl);
    const rect = target.getBoundingClientRect();
    const tipRect = specTooltipEl.getBoundingClientRect();
    const left = Math.max(8, Math.min(rect.left, window.innerWidth - tipRect.width - 12));
    let top = rect.top - tipRect.height - 8;
    if (top < 8) top = rect.bottom + 8;
    specTooltipEl.style.left = `${left}px`;
    specTooltipEl.style.top = `${top}px`;
  }

  function specBadge(item) {
    const spec = item?.spec ? String(item.spec).trim() : "";
    if (!spec) return "";
    const variants = Array.isArray(item.specVariants) ? item.specVariants.filter(Boolean) : [];
    const extra = variants.length > 1 ? `<i>等${variants.length}种</i>` : "";
    const full = variants.length > 1 ? `规格型号（共 ${variants.length} 种，按用量排序）：\n${variants.join("\n")}` : `规格型号：${spec}`;
    return `<small class="spec-badge" data-full-spec="${attr(full)}">${esc(spec)}${extra}</small>`;
  }

  function salesGroupDrillCustomerFilter() {
    const selectedGroup = state.salesGroupControls.selectedGroup || state.explorers.get("salesGroupSummary")?.data?.items?.[0]?.name || "";
    if (!selectedGroup) return "";
    const optionNames = (state.explorers.get("salesGroupCustomers")?.data?.items || []).map((item) => item.name).filter(Boolean);
    const selected = [...new Set((state.salesGroupControls.drillCustomers || []).map(String).filter(Boolean))];
    const selectedSet = new Set(selected);
    const search = state.filterSearches.sgDrillCustomer || "";
    const normalizedSearch = normalizeFuzzyText(search);
    return `<details class="filter-multiselect sg-drill-customer-filter" data-filter-key="sgDrillCustomer" ${state.openFilterKey === "sgDrillCustomer" ? "open" : ""}><summary><span>筛选客户（可多选）</span><b data-filter-summary>${selected.length ? `已选 ${formatInteger(selected.length)} 项` : "全部客户"}</b></summary><div class="multiselect-popover"><label class="multiselect-search"><span class="sr-only">搜索客户</span><input type="search" value="${attr(search)}" data-filter-option-search="sgDrillCustomer" autocomplete="off" placeholder="输入客户名称快速查找"></label><div class="multiselect-options">${optionNames.length ? optionNames.map((name) => `<label data-option-text="${attr(name)}" ${normalizedSearch && !normalizeFuzzyText(name).includes(normalizedSearch) ? "hidden" : ""}><input type="checkbox" value="${attr(name)}" data-sg-drill-customer ${selectedSet.has(name) ? "checked" : ""}><span title="${attr(name)}">${esc(name)}</span></label>`).join("") : `<span class="multiselect-empty">等待读取客户选项…</span>`}</div><footer><small>只统计所选客户购买的产品</small><button type="button" data-clear-sg-drill-customer ${selected.length ? "" : "disabled"}>清除</button></footer></div></details>`;
  }

  function renderSalesGroupDrill(slot, kicker, title, type) {
    const selectedGroup = state.salesGroupControls.selectedGroup || (state.salesGroupControls.groupBy === DIMENSIONS.salesGroup ? state.explorers.get("salesGroupSummary")?.data?.items?.[0]?.name : "") || "";
    if (!selectedGroup) return `<section class="surface org-drill-card"><div class="explorer-feedback empty"><span>↳</span><div><strong>先选择一个销售组</strong><p>切换到销售组层级并点击销售组名称后显示${esc(kicker)}。</p></div></div></section>`;
    const feedback = renderExplorerFeedback(slot, { loadingTitle: `正在读取${selectedGroup}${kicker}`, emptyTitle: `该销售组暂无${kicker}`, emptyText: "选择其他销售组后再试。" });
    const items = state.explorers.get(slot)?.data?.items || [];
    return `<section class="surface org-drill-card"><header class="section-heading"><div><span class="eyebrow">${esc(selectedGroup)} · ${esc(kicker)}</span><h2>${esc(title)}</h2><p>数量、换算只数、收入与完整成本保持同一筛选；毛利按可比行计算，缺失成本不会按 0 处理。</p></div><span class="section-note">Top ${Math.min(items.length, 50)}</span></header>${type === "product" ? salesGroupDrillCustomerFilter() : ""}${feedback || `<div class="data-table-wrap compact sticky-head-table"><table class="data-table org-drill-table"><thead><tr><th>${type === "customer" ? "客户" : "产品"}</th><th>出库数量</th><th>换算只数</th><th>收入</th><th>${state.salesGroupControls.costMetric === "transferCost" ? "调拨成本" : "成本"}</th><th>毛利润</th><th>毛利率</th><th title="${attr(PROFIT_COVERAGE_EXPLANATION)}">可算覆盖</th></tr></thead><tbody>${items.slice(0, 50).map((item) => {
      const displayedCost = salesGroupCost(item, state.salesGroupControls.costMetric);
      const metric = salesGroupProfitMetrics(item, state.salesGroupControls.costMetric);
      return `<tr><td><strong>${esc(item.name)}</strong>${type === "product" ? specBadge(item) : ""}</td><td>${item.outboundQuantity === null ? "—" : esc(formatQuantity(item.outboundQuantity))}</td><td>${item.convertedQuantity === null ? "—" : esc(formatQuantity(item.convertedQuantity))}</td><td>${esc(formatCurrencyExact(item.revenue))}</td><td>${displayedCost === null ? "未识别" : esc(formatCurrencyExact(displayedCost))}</td><td class="${metric.profit !== null && metric.profit < 0 ? "negative-text" : ""}">${metric.profit === null ? "不可计算" : esc(formatCurrencyExact(metric.profit))}</td><td class="${metric.margin !== null && metric.margin < 0 ? "negative-text" : ""}">${metric.margin === null ? "—" : esc(formatPercent(metric.margin))}</td><td title="${attr(PROFIT_COVERAGE_EXPLANATION)}">${metric.coverage === null ? (item.revenue ? "0.0%" : "—") : esc(formatPercent(metric.coverage))}</td></tr>`;
    }).join("")}</tbody></table></div>`}</section>`;
  }

  function renderProfit(input) {
    const a = desktopScopedAnalysis(input);
    const s = a.summary;
    const capabilities = a.capabilities;
    if (!capabilities.grossProfitAvailable) {
      return `<div class="view-stack"><section class="surface capability-empty"><span class="capability-lock">×</span><div><span class="eyebrow">PROFIT CAPABILITY</span><h2>成本口径尚未确认</h2><p>没有可靠成本列时，DataMaster 不会显示毛利率、经营利润或扭亏测算。请到数据源页确认成本字段。</p><button class="button button-primary" type="button" data-open-view="overview">返回经营总览</button></div></section>${renderConclusion("利润与成本", "利润分析暂未开放", "当前仅能进行销售规模与结构分析；缺失成本不会被按 0 处理。", ["EV-SUM-REV"])}</div>`;
    }
    const revenue = s.revenue;
    const grossComparableRevenue = comparableMetric(s.grossComparableRevenue, s.grossProfit, s.grossMargin,
      revenue, capabilities.grossProfitRevenueCoverage);
    const grossComparableCost = nullableNumber(s.grossComparableCost) ?? (grossComparableRevenue !== null ? grossComparableRevenue - s.grossProfit : null);
    const grossCostRate = grossComparableRevenue > 0 && grossComparableCost !== null
      ? grossComparableCost / grossComparableRevenue : null;
    if (!capabilities.operatingProfitAvailable) {
      const conclusion = `本期可比口径毛利润 ${formatCurrency(s.grossProfit)}，毛利率 ${formatPercent(s.grossMargin)}。`;
      const explanation = `利润按收入与成本同时有效的可比行计算，毛利可算收入覆盖率为 ${coverageLabel(capabilities.grossProfitRevenueCoverage)}，排除 ${formatInteger(capabilities.grossProfitExcludedRows)} 行、涉及收入 ${formatCurrency(capabilities.grossProfitExcludedRevenue)}。覆盖率 100% 仅表示当前成本口径完整，不代表毛利率 100% 或数据没有其他风险。由于没有可用期间费用，本页不显示经营利润、费用率或扭亏测算。`;
      const kpis = [
        kpi("可比口径毛利润", formatCurrency(s.grossProfit), `毛利率 ${formatPercent(s.grossMargin)}`, "EV-SUM-GP,EV-SUM-GM", s.grossProfit < 0 ? "coral" : "sky", s.grossMargin, s.grossProfit < 0),
        kpi("可比成本率", grossCostRate === null ? "不可用" : formatPercent(grossCostRate), `可比成本 ${grossComparableCost === null ? "不可用" : formatCurrency(grossComparableCost)}`, "EV-SUM-COST", "lavender", grossCostRate),
        kpi("毛利可算覆盖", coverageLabel(capabilities.grossProfitRevenueCoverage), `成本同时有效 · 排除 ${formatInteger(capabilities.grossProfitExcludedRows)} 行`, "EV-SUM-COST", capabilities.grossProfitRevenueCoverage !== null && capabilities.grossProfitRevenueCoverage < .95 ? "amber" : "lavender", capabilities.grossProfitRevenueCoverage),
        kpi("经营利润", "不可用", "缺少期间费用字段", "EV-SUM-GP", "lavender")
      ];
      return `<div class="view-stack">${renderKpis(kpis)}${renderGrossStructure(a)}<details class="detail-drawer"><summary><span>展开毛利率趋势与低毛利产品</span><small>查看进一步拆解</small></summary><div class="section-grid equal">${renderMarginTrend(a.monthly, "gross")}${renderLossRanking(a, "gross")}</div></details>${renderDynamicExplorer(a)}${renderConclusion("利润与成本分析", conclusion, explanation, ["EV-SUM-COST", "EV-SUM-GP"])}</div>`;
    }
    const operatingComparableRevenue = comparableMetric(s.operatingComparableRevenue, s.operatingProfit,
      s.operatingMargin, revenue, capabilities.operatingProfitRevenueCoverage);
    const operatingComparableCost = comparableComponent(s.operatingComparableCost, s.cost,
      capabilities.operatingProfitRevenueCoverage);
    const operatingComparableExpenses = comparableComponent(s.operatingComparableExpenses, s.expenses,
      capabilities.operatingProfitRevenueCoverage);
    const costRate = operatingComparableRevenue && operatingComparableCost !== null
      ? operatingComparableCost / operatingComparableRevenue : null;
    const expenseRate = operatingComparableRevenue && operatingComparableExpenses !== null
      ? operatingComparableExpenses / operatingComparableRevenue : null;
    const losing = s.operatingProfit < 0;
    const gap = Math.max(0, -s.operatingProfit);
    const costFive = Math.max(0, (operatingComparableCost || 0) * .05);
    const expenseEight = Math.max(0, (operatingComparableExpenses || 0) * .08);
    const potential = costFive + expenseEight;
    const conclusion = losing
      ? `每 100 元可比收入需要承担 ${costRate === null ? "待刷新" : formatNumber(costRate * 100, 1)} 元成本和 ${expenseRate === null ? "待刷新" : formatNumber(expenseRate * 100, 1)} 元费用，最终亏损 ${formatNumber(Math.abs(s.operatingMargin) * 100, 1)} 元。`
      : `每 100 元可比收入最终留下 ${formatNumber(s.operatingMargin * 100, 1)} 元经营利润，成本与费用结构处于正向区间。`;
    const explanation = losing
      ? `当前离盈亏平衡还差 ${formatCurrency(gap)}。若成本压降 5%、费用压降 8%，预计释放 ${formatCurrency(potential)}，可覆盖亏损额的 ${formatPercent(gap ? potential / gap : 1)}。`
      : `毛利率 ${formatPercent(s.grossMargin)}，经营利润率 ${formatPercent(s.operatingMargin)}。建议把现有利润率作为报价和费用预算的底线。`;
    const kpis = [
      kpi("毛利率", formatPercent(s.grossMargin), `毛利润 ${formatCurrency(s.grossProfit)}`, "EV-SUM-GP,EV-SUM-GM", "sky", s.grossMargin),
      kpi("经营利润率", formatPercent(s.operatingMargin), `经营利润 ${formatCurrency(s.operatingProfit)}`, "EV-SUM-OP,EV-SUM-OM", losing ? "coral" : "indigo", s.operatingMargin, losing),
      kpi("可比成本率", costRate === null ? "不可用" : formatPercent(costRate), operatingComparableCost === null ? "旧版口径待重新计算" : `可比成本 ${formatCurrency(operatingComparableCost)} · ${coverageLabel(capabilities.operatingProfitRevenueCoverage)}`, "EV-SUM-COST", "lavender", costRate),
      kpi("可比费用率", expenseRate === null ? "不可用" : formatPercent(expenseRate), operatingComparableExpenses === null ? "旧版口径待重新计算" : `可比费用 ${formatCurrency(operatingComparableExpenses)} · ${coverageLabel(capabilities.operatingProfitRevenueCoverage)}`, "EV-SUM-EXP", expenseRate !== null && expenseRate > .25 ? "amber" : "lavender", expenseRate)
    ];
    return `<div class="view-stack">
      ${renderKpis(kpis)}
      <div class="section-grid two-one">
        ${renderCostStructure(a, operatingComparableRevenue, operatingComparableCost, operatingComparableExpenses)}
        ${renderTurnaround(a, gap, potential, operatingComparableCost, operatingComparableExpenses)}
      </div>
      <details class="detail-drawer"><summary><span>展开利润率趋势与亏损排行</span><small>查看进一步拆解</small></summary><div class="section-grid equal">${renderMarginTrend(a.monthly, "operating")}${renderLossRanking(a, "operating")}</div></details>
      ${renderConclusion("利润与成本分析", conclusion, explanation, ["EV-SUM-COST", "EV-SUM-EXP", "EV-SUM-OP"])}
    </div>`;
  }

  function renderGrossStructure(a) {
    const s = a.summary;
    const revenue = s.revenue;
    const comparableRevenue = comparableMetric(s.grossComparableRevenue, s.grossProfit, s.grossMargin,
      revenue, a.capabilities.grossProfitRevenueCoverage);
    const comparableCost = nullableNumber(s.grossComparableCost) ?? (comparableRevenue !== null ? comparableRevenue - s.grossProfit : null);
    const costPct = comparableRevenue > 0 && comparableCost !== null ? Math.max(0, comparableCost / comparableRevenue * 100) : 0;
    const marginPct = comparableRevenue > 0 ? s.grossProfit / comparableRevenue * 100 : 0;
    const positiveMargin = Math.max(0, marginPct);
    const total = costPct + positiveMargin || 1;
    return `<section class="surface cost-structure gross-only" aria-labelledby="grossStructureTitle"><div class="section-heading"><div><span class="eyebrow">每百元可比收入</span><h2 id="grossStructureTitle">成本与毛利润结构</h2><p>仅使用收入和成本同时有效的行；未识别期间费用。</p></div><span class="section-note">可比收入 ${coverageLabel(a.capabilities.grossProfitRevenueCoverage)}</span></div><div class="cost-stack" role="img" aria-label="每百元可比收入中，成本 ${formatNumber(costPct, 1)} 元，毛利润 ${formatNumber(marginPct, 1)} 元"><i class="cost" style="width:${costPct / total * 100}%"></i>${positiveMargin ? `<i class="profit" style="width:${positiveMargin / total * 100}%"></i>` : `<i class="loss" style="width:5px"></i>`}</div><div class="cost-legend two"><button type="button" data-evidence="EV-SUM-COST"><span><i></i>营业成本</span><strong>${formatNumber(costPct, 1)} 元</strong></button><button type="button" class="${marginPct < 0 ? "loss" : "profit"}" data-evidence="EV-SUM-GP"><span><i></i>${marginPct < 0 ? "毛亏损" : "毛利润"}</span><strong>${formatNumber(Math.abs(marginPct), 1)} 元</strong></button></div></section>`;
  }

  function comparableMetric(explicitValue, profit, margin, fallbackRevenue, coverage) {
    const explicit = nullableNumber(explicitValue);
    if (explicit !== null) return explicit;
    const comparableProfit = nullableNumber(profit);
    const comparableMargin = nullableNumber(margin);
    if (comparableProfit !== null && comparableMargin !== null && Math.abs(comparableMargin) > 0.000001) {
      return comparableProfit / comparableMargin;
    }
    return isCompleteCoverage(coverage) ? nullableNumber(fallbackRevenue) : null;
  }

  function comparableComponent(explicitValue, fullValue, coverage) {
    const explicit = nullableNumber(explicitValue);
    if (explicit !== null) return explicit;
    return isCompleteCoverage(coverage) ? nullableNumber(fullValue) : null;
  }

  function operatingComparableRates(a) {
    const s = a.summary;
    const fallbackRevenue = s.revenue;
    const revenue = comparableMetric(s.operatingComparableRevenue, s.operatingProfit,
      s.operatingMargin, fallbackRevenue, a.capabilities.operatingProfitRevenueCoverage);
    const cost = comparableComponent(s.operatingComparableCost, s.cost,
      a.capabilities.operatingProfitRevenueCoverage);
    const expenses = comparableComponent(s.operatingComparableExpenses, s.expenses,
      a.capabilities.operatingProfitRevenueCoverage);
    return {
      revenue,
      cost,
      expenses,
      costRate: revenue > 0 && cost !== null ? cost / revenue : null,
      expenseRate: revenue > 0 && expenses !== null ? expenses / revenue : null
    };
  }

  function formatRate(value) {
    return value === null || !Number.isFinite(value) ? "不可用" : formatPercent(value);
  }

  function profitDescription(item, hasOperating) {
    const profit = hasOperating ? item.operatingProfit : item.grossProfit;
    const margin = hasOperating ? item.operatingMargin : item.grossMargin;
    const label = hasOperating ? "经营" : "毛";
    const basis = profitBasisRevenue(item, hasOperating ? "operatingMargin" : "grossMargin");
    if (basis === null || basis <= 0 || margin === null || !Number.isFinite(margin)) {
      const basisLabel = basis !== null && basis !== item.revenue ? "可比收入" : "收入";
      return `${label}利润 ${formatCurrency(profit)}，${basisLabel} ${formatCurrency(basis ?? item.revenue)}；负收入、退货或折扣场景不使用利润率比较。`;
    }
    return `${label}利润 ${formatCurrency(profit)}，利润率 ${formatPercent(margin)}。`;
  }

  function profitBasisRevenue(item, marginKey = "grossMargin") {
    const comparable = marginKey === "operatingMargin"
      ? nullableNumber(item?.operatingComparableRevenue)
      : nullableNumber(firstDefined(item?.comparableRevenue, item?.grossComparableRevenue));
    return comparable !== null ? comparable : nullableNumber(item?.revenue);
  }

  function marginLabel(item, marginKey = "grossMargin") {
    const basis = profitBasisRevenue(item, marginKey);
    const margin = nullableNumber(item?.[marginKey]);
    if (basis === null || basis <= 0) return "不适用";
    return margin === null ? "不可用" : formatPercent(margin);
  }

  function isCompleteCoverage(value) {
    const coverage = nullableNumber(value);
    return coverage !== null && coverage >= .99995;
  }

  function renderConclusion(kicker, title, description, evidenceIds) {
    return `<section class="conclusion-card page-summary summary-last" aria-labelledby="viewConclusionTitle">
      <div class="conclusion-main"><span class="eyebrow">本页总结 · ${esc(kicker)}</span><h2 id="viewConclusionTitle">${esc(title)}</h2><p>${esc(description)}</p></div>
      <aside class="conclusion-side"><span>结论依据</span><strong>${esc(evidenceIds.join(" · "))}</strong>${evidenceTicket(evidenceIds, "verified", "查看计算证据")}<small>本地确定性计算 · AI 不参与金额计算</small></aside>
    </section>`;
  }

  function kpi(label, value, meta, evidence, tone = "indigo", signalValue = null, negative = false) {
    return { label, value, meta, evidence, tone, signalValue, negative };
  }

  function renderKpis(items) {
    return `<section class="kpi-grid" aria-label="本页核心指标">${items.map((item) => {
      const signalClass = item.negative ? "bad" : item.signalValue !== null && item.signalValue > 0 ? "good" : "neutral";
      return `<article class="kpi-card ${esc(item.tone)}"><div class="kpi-top"><span>${esc(item.label)}</span><i class="kpi-tone" aria-hidden="true"></i></div><strong class="kpi-value ${item.negative ? "negative" : ""}" title="${attr(item.value)}">${esc(item.value)}</strong><div class="kpi-foot"><span class="signal ${signalClass}">${esc(item.meta)}</span>${evidenceTicket(item.evidence.split(","), item.negative ? "warn" : "verified", "证据")}</div></article>`;
    }).join("")}</section>`;
  }

  function renderTrendCard(monthly, capabilities = state.analysis?.capabilities || {}) {
    const hasRevenue = Boolean(capabilities.hasRevenue);
    const hasGross = capabilities.grossProfitAvailable;
    const hasOperating = capabilities.operatingProfitAvailable;
    const title = !hasRevenue ? "经营趋势暂未开放" : hasOperating ? "收入、成本与经营利润" : hasGross ? "收入、成本与毛利润" : "销售收入趋势";
    const description = !hasRevenue ? "确认收入口径后再绘制趋势，未确认金额不会按 0 展示。" : hasGross ? `柱形显示收入与成本，折线显示${hasOperating ? "经营" : "毛"}利润。` : "当前只有收入口径，未绘制成本或利润系列。";
    const periodTitle = IS_DESKTOP && state.periodControls.mode === "year" ? "年度经营对比" : IS_DESKTOP && state.periodControls.mode === "month" ? "月度经营对比" : "累计范围 · 月度趋势";
    return `<section class="surface trend-card" aria-labelledby="trendTitle"><div class="section-heading"><div><span class="eyebrow">${esc(periodTitle)}</span><h2 id="trendTitle">${esc(title)}</h2><p>${esc(description)} ${IS_DESKTOP ? esc(periodScopeLabel()) : ""}</p></div>${hasRevenue ? `<div class="chart-legend" aria-label="图例"><span><i class="revenue"></i>收入</span>${hasGross ? `<span><i class="cost"></i>成本</span><span><i class="profit"></i>${hasOperating ? "经营利润" : "毛利润"}</span>` : ""}</div>` : ""}</div>${renderTrendChart(monthly, capabilities)}</section>`;
  }

  function renderTrendChart(monthly, capabilities = state.analysis?.capabilities || {}) {
    if (!monthly.length) return `<div class="empty-insights"><div><strong>暂无月度趋势</strong><p>导入包含日期字段的数据后显示。</p></div></div>`;
    const hasGross = Boolean(capabilities.grossProfitAvailable);
    const hasOperating = Boolean(capabilities.operatingProfitAvailable);
    const lineKey = hasOperating ? "operatingProfit" : hasGross ? "grossProfit" : null;
    const lineLabel = hasOperating ? "经营利润" : "毛利润";
    const width = Math.max(1000, monthly.length * 92 + 120);
    const height = 360;
    const margin = { top: 24, right: 24, bottom: 54, left: 72 };
    const plotWidth = width - margin.left - margin.right;
    const plotHeight = height - margin.top - margin.bottom;
    const values = monthly.flatMap((item) => [item.revenue, hasGross ? item.cost : null, lineKey ? item[lineKey] : null].filter((value) => value !== null));
    const min = Math.min(0, ...values) * 1.15;
    const max = Math.max(1, ...values) * 1.08;
    const y = (value) => margin.top + ((max - value) / Math.max(max - min, 1)) * plotHeight;
    const zeroY = y(0);
    const step = plotWidth / monthly.length;
    const barWidth = Math.min(28, step * .22);
    const ticks = Array.from({ length: 5 }, (_, index) => max - ((max - min) / 4) * index);
    const grids = ticks.map((tick) => `<line class="grid-line" x1="${margin.left}" y1="${y(tick)}" x2="${width - margin.right}" y2="${y(tick)}"></line><text class="axis-label" x="${margin.left - 10}" y="${y(tick) + 4}" text-anchor="end">${esc(shortAxis(tick))}</text>`).join("");
    const lineCoords = lineKey ? monthly.filter((item) => item[lineKey] !== null).map((item) => ({ x: margin.left + step * monthly.indexOf(item) + step / 2, y: y(item[lineKey]) })) : [];
    const points = lineCoords.map((point) => `${point.x},${point.y}`).join(" ");
    const areaPath = lineCoords.length > 1 ? `M${lineCoords[0].x},${zeroY} ${lineCoords.map((point) => `L${point.x},${point.y}`).join(" ")} L${lineCoords[lineCoords.length - 1].x},${zeroY} Z` : "";
    const groups = monthly.map((item, index) => {
      const center = margin.left + step * index + step / 2;
      const revenueTop = y(item.revenue);
      const costTop = hasGross ? y(item.cost) : zeroY;
      const id = `EV-MON-${pad(index + 1)}`;
      const lineValue = lineKey ? item[lineKey] : null;
      return `<g tabindex="0" role="button" aria-label="查看 ${attr(displayMonth(item.month))} 经营证据" data-evidence="${id}" data-chart-tip data-month="${attr(displayMonth(item.month))}" data-revenue="${attr(formatCurrencyExact(item.revenue))}" data-cost="${hasGross ? attr(formatCurrencyExact(item.cost)) : "不可用"}" data-profit="${lineValue !== null ? attr(formatCurrencyExact(lineValue)) : "不可用"}"><rect class="chart-focus" x="${center - step / 2 + 3}" y="${margin.top}" width="${Math.max(step - 6, 1)}" height="${plotHeight}" rx="6"></rect><rect class="bar-revenue" x="${hasGross ? center - barWidth - 2 : center - barWidth / 2}" y="${Math.min(revenueTop, zeroY)}" width="${barWidth}" height="${Math.max(Math.abs(zeroY - revenueTop), 1)}" rx="4"></rect>${hasGross ? `<rect class="bar-cost" x="${center + 2}" y="${Math.min(costTop, zeroY)}" width="${barWidth}" height="${Math.max(Math.abs(zeroY - costTop), 1)}" rx="4"></rect>` : ""}${lineValue !== null ? `<circle class="profit-dot" cx="${center}" cy="${y(lineValue)}" r="5" style="--dot-delay:${index * 45}ms"></circle>` : ""}<rect class="chart-hit" x="${center - step / 2}" y="${margin.top}" width="${step}" height="${plotHeight}"></rect><text class="axis-label" x="${center}" y="${height - 18}" text-anchor="middle">${esc(displayMonth(item.month))}</text></g>`;
    }).join("");
    const table = `<details class="data-disclosure"><summary><svg><use href="#i-table"></use></svg>查看月度数据表</summary><div class="data-table-wrap"><table class="data-table"><thead><tr><th>月份</th><th>收入</th>${hasGross ? `<th>成本</th><th>${lineLabel}</th><th>${hasOperating ? "经营利润率" : "毛利率"}</th>` : ""}</tr></thead><tbody>${monthly.map((item) => { const lineValue = lineKey ? item[lineKey] : null; const lineMargin = hasOperating ? item.operatingMargin : item.grossMargin; return `<tr><td><strong>${esc(displayMonth(item.month))}</strong></td><td>${esc(formatCurrencyExact(item.revenue))}</td>${hasGross ? `<td>${esc(formatCurrencyExact(item.cost))}</td><td class="${lineValue < 0 ? "negative-text" : ""}">${esc(formatCurrencyExact(lineValue))}</td><td class="${lineMargin < 0 ? "negative-text" : ""}">${esc(formatPercent(lineMargin))}</td>` : ""}</tr>`; }).join("")}</tbody></table></div></details>`;
    const defs = `<defs><linearGradient id="trendGradRevenue" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#6a62ee"></stop><stop offset="1" stop-color="#4f46e5"></stop></linearGradient><linearGradient id="trendGradCost" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#cdd4e3"></stop><stop offset="1" stop-color="#aeb7cb"></stop></linearGradient><linearGradient id="trendGradProfitArea" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="rgb(189 70 63 / 18%)"></stop><stop offset="1" stop-color="rgb(189 70 63 / 0%)"></stop></linearGradient></defs>`;
    return `<div class="trend-chart-scroll"><svg class="trend-svg" style="aspect-ratio:${width} / ${height};min-width:${Math.max(760, monthly.length * 86 + 120)}px" viewBox="0 0 ${width} ${height}" preserveAspectRatio="xMidYMid meet" role="group" aria-label="月度${hasGross ? `收入、成本和${lineLabel}` : "收入"}趋势图">${defs}${grids}<line class="zero-line" x1="${margin.left}" y1="${zeroY}" x2="${width - margin.right}" y2="${zeroY}"></line>${areaPath ? `<path class="profit-area" d="${areaPath}" fill="url(#trendGradProfitArea)"></path>` : ""}${groups}${lineKey ? `<polyline class="profit-line" pathLength="1000" points="${points}"></polyline>` : ""}</svg></div>${table}`;
  }

  function renderQuality(q) {
    const score = qualityScore(q);
    const verdict = qualityVerdict(q, state.analysis);
    const issues = [["缺日期", q.missingDate], ["缺产品", q.missingProduct], ["缺客户", q.missingCustomer], ["数值异常", q.invalidNumericCells]];
    return `<section class="surface quality-card ${verdict.tone}" aria-labelledby="qualityTitle"><div class="quality-summary"><span class="score-ring" style="--score:${score}" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${score}" aria-label="数据质量 ${score} 分"><strong>${score}</strong></span><div><span class="eyebrow">数据质量</span><h3 id="qualityTitle">${esc(verdict.title)}</h3><p>${formatInteger(q.validRows)} / ${formatInteger(q.totalRows)} 行纳入本次分析 · ${esc(verdict.detail)}</p>${evidenceTicket(["EV-QUALITY-001"], verdict.tone === "verified" ? "verified" : "warn")}</div></div><div class="quality-issues">${issues.map(([label, value]) => `<div class="quality-issue ${value ? "warn" : ""}"><span>${esc(label)}</span><strong>${formatInteger(value)}</strong></div>`).join("")}</div></section>`;
  }

  function renderProductChart(a) {
    const list = sortedByRevenue(a.products).slice(0, 8);
    const hasOperating = a.capabilities.operatingProfitAvailable;
    const hasGross = a.capabilities.grossProfitAvailable;
    if (!list.length) return `<section class="surface product-card"><div class="empty-insights"><div><strong>暂无产品数据</strong><p>导入包含产品字段的表格后显示。</p></div></div></section>`;
    const max = Math.max(...list.map((item) => item.revenue), 1);
    return `<section class="surface product-card" aria-labelledby="productChartTitle"><div class="section-heading"><div><span class="eyebrow">产品收入${hasGross ? "与利润率" : "结构"}</span><h2 id="productChartTitle">${hasGross ? "规模并不等于盈利质量" : "先比较规模，再补全利润口径"}</h2><p>横条显示收入${hasGross ? `，右侧显示${hasOperating ? "经营" : "毛"}利率；负收入项目标为不适用。` : "；成本字段不可用时不显示虚假的利润率。"}</p></div><span class="section-note">前 ${list.length} 项</span></div><div class="product-chart">${list.map((item, index) => { const marginKey = hasOperating ? "operatingMargin" : "grossMargin"; const margin = item[marginKey]; return `<div class="product-bar-row ${hasGross ? "" : "sales-only"}"><div class="product-name"><span class="rank-number">${String(index + 1).padStart(2, "0")}</span><strong title="${attr(item.name)}">${esc(item.name)}</strong></div><div class="bar-track" aria-label="${attr(item.name)}收入 ${attr(formatCurrencyExact(item.revenue))}"><i style="width:${Math.max(4, item.revenue / max * 100)}%"></i></div><span class="bar-value">${esc(formatCurrency(item.revenue))}</span>${hasGross ? `<button type="button" class="margin-pill ${margin < 0 ? "negative" : ""}" data-evidence="${productEvidence(a, item)}" aria-label="查看 ${attr(item.name)} 利润率证据">${esc(marginLabel(item, marginKey))}</button>` : ""}</div>`; }).join("")}</div></section>`;
  }

  function renderProductTable(a) {
    const list = sortedByRevenue(a.products);
    const hasGross = a.capabilities.grossProfitAvailable;
    const hasOperating = a.capabilities.operatingProfitAvailable;
    const header = `<tr><th>产品</th><th>收入</th>${hasGross ? "<th>成本</th><th>毛利润</th><th>毛利率</th>" : ""}${hasOperating ? "<th>经营利润</th><th>经营利润率</th>" : ""}<th>证据</th></tr>`;
    return `<section class="surface section-padding" aria-labelledby="productTableTitle"><div class="section-heading"><div><span class="eyebrow">产品明细</span><h2 id="productTableTitle">${hasGross ? "收入、成本与利润排行" : "销售收入排行"}</h2><p>${hasGross ? "利润只使用收入与成本同时有效的可比行。" : "成本口径未确认，因此没有利润列。"}</p></div><span class="section-note">${formatInteger(list.length)} 个产品</span></div><div class="data-table-wrap"><table class="data-table"><thead>${header}</thead><tbody>${list.length ? list.map((item) => `<tr><td><strong>${esc(item.name)}</strong></td><td>${esc(formatCurrencyExact(item.revenue))}</td>${hasGross ? `<td>${esc(formatCurrencyExact(item.cost))}</td><td class="${item.grossProfit < 0 ? "negative-text" : ""}">${esc(formatCurrencyExact(item.grossProfit))}</td><td class="${item.grossMargin < 0 ? "negative-text" : ""}">${esc(formatPercent(item.grossMargin))}</td>` : ""}${hasOperating ? `<td class="${item.operatingProfit < 0 ? "negative-text" : ""}">${esc(formatCurrencyExact(item.operatingProfit))}</td><td class="${item.operatingMargin < 0 ? "negative-text" : ""}">${esc(formatPercent(item.operatingMargin))}</td>` : ""}<td>${evidenceTicket([productEvidence(a, item)], hasOperating ? (item.operatingProfit < 0 ? "warn" : "verified") : hasGross && item.grossProfit < 0 ? "warn" : "verified", "查看")}</td></tr>`).join("") : `<tr><td colspan="${hasOperating ? 8 : hasGross ? 6 : 3}">暂无产品数据</td></tr>`}</tbody></table></div></section>`;
  }

  function renderCustomerChart(a, topShare, source = a.customers, options = {}) {
    const list = [...source].sort((left, right) => numberValue(right.revenue) - numberValue(left.revenue)).slice(0, 8);
    const hasOperating = a.capabilities.operatingProfitAvailable && !options.scoped;
    const hasGross = a.capabilities.grossProfitAvailable;
    const max = Math.max(...list.map((item) => item.revenue), 1);
    return `<section class="surface customer-card" aria-labelledby="customerChartTitle"><div class="section-heading"><div><span class="eyebrow">客户组合</span><h2 id="customerChartTitle">贡献、集中度${hasGross ? "与盈利质量" : ""}</h2><p>${esc(options.context || "全部客户")} · 圆环、排行与利润率已和客户价值筛选联动。</p></div><span class="section-note">当前范围前 ${list.length} 位</span></div><div class="customer-overview"><div class="concentration-visual"><div><div class="concentration-ring" style="--share:${Math.max(0, Math.min(100, topShare * 100))}" role="img" aria-label="第一大客户收入占比 ${formatPercent(topShare)}"><div><strong>${esc(formatPercent(topShare))}</strong><span>第一大客户占比</span></div></div><p class="concentration-caption">${topShare > .4 ? "集中度偏高，需准备流失预案" : "集中度处于相对可控范围"}</p></div></div><div class="customer-ranking">${list.length ? list.map((item, index) => { const margin = hasOperating ? item.operatingMargin : item.grossMargin; const evidence = options.scoped ? "EV-SUM-GP" : customerEvidence(a, item); return `<div class="customer-rank-row ${hasGross ? "" : "sales-only"}"><div class="product-name"><span class="rank-number">${String(index + 1).padStart(2, "0")}</span><strong title="${attr(item.name)}">${esc(item.name)}</strong></div><div class="bar-track"><i style="width:${Math.max(4, item.revenue / max * 100)}%"></i></div><span class="bar-value">${esc(formatCurrency(item.revenue))}</span>${hasGross ? `<button type="button" class="margin-pill ${margin !== null && margin < 0 ? "negative" : ""}" data-evidence="${evidence}" aria-label="查看 ${attr(item.name)} 利润证据">${margin === null ? "—" : esc(formatPercent(margin))}</button>` : ""}</div>`; }).join("") : `<div class="empty-insights"><div><strong>${options.scoped ? "当前筛选下暂无客户" : "暂无客户数据"}</strong><p>${options.scoped ? "调整客户价值筛选后再试。" : "导入包含客户字段的表格后显示。"}</p></div></div>`}</div></div></section>`;
  }

  function renderCustomerTable(a) {
    const list = sortedByRevenue(a.customers);
    const total = Math.max(a.summary.revenue, 1);
    const hasGross = a.capabilities.grossProfitAvailable;
    const hasOperating = a.capabilities.operatingProfitAvailable;
    return `<section class="surface section-padding" aria-labelledby="customerTableTitle"><div class="section-heading"><div><span class="eyebrow">客户价值明细</span><h2 id="customerTableTitle">${hasGross ? "收入贡献与利润" : "收入贡献与集中度"}</h2><p>${hasGross ? "利润按收入与成本同时有效的可比行计算。" : "成本不可用，当前不输出客户利润判断。"}</p></div><span class="section-note">${formatInteger(list.length)} 个客户维度</span></div><div class="data-table-wrap"><table class="data-table"><thead><tr><th>客户</th><th>收入</th><th>收入占比</th>${hasGross ? "<th>毛利率</th>" : ""}${hasOperating ? "<th>经营利润</th><th>经营利润率</th>" : ""}<th>证据</th></tr></thead><tbody>${list.length ? list.map((item) => `<tr><td><strong>${esc(item.name)}</strong></td><td>${esc(formatCurrencyExact(item.revenue))}</td><td>${esc(formatPercent(item.revenue / total))}</td>${hasGross ? `<td class="${item.grossMargin < 0 ? "negative-text" : ""}">${esc(formatPercent(item.grossMargin))}</td>` : ""}${hasOperating ? `<td class="${item.operatingProfit < 0 ? "negative-text" : ""}">${esc(formatCurrencyExact(item.operatingProfit))}</td><td class="${item.operatingMargin < 0 ? "negative-text" : ""}">${esc(formatPercent(item.operatingMargin))}</td>` : ""}<td>${evidenceTicket([customerEvidence(a, item)], hasOperating && item.operatingProfit < 0 ? "warn" : hasGross && item.grossProfit < 0 ? "warn" : "verified", "查看")}</td></tr>`).join("") : `<tr><td colspan="${hasOperating ? 7 : hasGross ? 5 : 4}">暂无客户数据</td></tr>`}</tbody></table></div></section>`;
  }

  function renderCostStructure(a, comparableRevenue, comparableCost, comparableExpenses) {
    const s = a.summary;
    if (!comparableRevenue || comparableCost === null || comparableExpenses === null) {
      return `<section class="surface cost-structure"><div class="explorer-feedback empty"><span>↻</span><div><strong>可比成本结构待刷新</strong><p>当前是旧版分析快照，重新打开本地工作区后会根据原数据重算，不会用全量成本替代可比成本。</p></div></div></section>`;
    }
    const revenue = Math.max(Math.abs(comparableRevenue), 1);
    const costPct = Math.max(0, comparableCost / revenue * 100);
    const expensePct = Math.max(0, comparableExpenses / revenue * 100);
    const profitPct = Math.max(0, s.operatingProfit / revenue * 100);
    const total = costPct + expensePct + profitPct || 1;
    const loss = s.operatingProfit < 0;
    return `<section class="surface cost-structure" aria-labelledby="costStructureTitle"><div class="section-heading"><div><span class="eyebrow">每百元可比收入结构</span><h2 id="costStructureTitle">可比收入最终流向哪里</h2><p>仅使用收入、成本和费用同时有效的行。</p></div><span class="section-note">可比收入基准 ¥100</span></div><div class="cost-stack" role="img" aria-label="每百元可比收入中，成本 ${formatNumber(costPct, 1)} 元，费用 ${formatNumber(expensePct, 1)} 元，${loss ? "亏损" : "经营利润"} ${formatNumber(Math.abs(s.operatingMargin * 100), 1)} 元"><i class="cost" style="width:${costPct / total * 100}%"></i><i class="expense" style="width:${expensePct / total * 100}%"></i>${profitPct ? `<i class="profit" style="width:${profitPct / total * 100}%"></i>` : ""}${loss ? `<i class="loss" style="width:5px"></i>` : ""}</div><div class="cost-legend"><button type="button" data-evidence="EV-SUM-COST"><span><i></i>可比营业成本</span><strong>${formatNumber(costPct, 1)} 元</strong></button><button type="button" class="expense" data-evidence="EV-SUM-EXP"><span><i></i>可比期间费用</span><strong>${formatNumber(expensePct, 1)} 元</strong></button><button type="button" class="${loss ? "loss" : "profit"}" data-evidence="EV-SUM-OP"><span><i></i>${loss ? "经营亏损" : "经营利润"}</span><strong>${formatNumber(Math.abs(s.operatingMargin * 100), 1)} 元</strong></button></div></section>`;
  }

  function renderTurnaround(a, gap, potential, comparableCost, comparableExpenses) {
    const losing = a.summary.operatingProfit < 0;
    const ready = comparableCost !== null && comparableExpenses !== null;
    return `<aside class="surface turnaround-card" aria-labelledby="turnaroundTitle"><span class="eyebrow">${losing ? "扭亏测算" : "利润保护"}</span><h2 id="turnaroundTitle">${losing ? `离盈亏平衡还差 ${formatCurrency(gap)}` : `本期利润安全垫 ${formatCurrency(a.summary.operatingProfit)}`}</h2><p>${ready ? (losing ? "以下测算只使用当前可比成本和可比费用，不假设收入额外增长。" : "用当前可比利润率作为报价和费用预算的最低参考线。") : "旧版快照缺少可比成本与费用拆分，刷新前不使用全量数据模拟扭亏。"}</p><div class="target-list"><div class="target-row"><span>可比成本压降 5%</span><strong>${ready ? formatCurrency(comparableCost * .05) : "不可用"}</strong></div><div class="target-row"><span>可比费用压降 8%</span><strong>${ready ? formatCurrency(comparableExpenses * .08) : "不可用"}</strong></div><div class="target-row"><span>预计释放</span><strong class="good">${ready ? formatCurrency(potential) : "不可用"}</strong></div><div class="target-row"><span>${losing ? "亏损覆盖率" : "利润增厚比例"}</span><strong class="good">${ready ? formatPercent(losing && gap ? potential / gap : potential / Math.max(a.summary.operatingProfit, 1)) : "不可用"}</strong></div></div>${evidenceTicket(["EV-SUM-COST", "EV-SUM-EXP", "EV-SUM-OP"], losing ? "warn" : "verified", "查看测算依据")}</aside>`;
  }

  function renderMarginTrend(monthly, type = "operating") {
    const label = type === "gross" ? "毛利率" : "经营利润率";
    return `<section class="surface profit-card" aria-labelledby="marginTrendTitle"><div class="section-heading"><div><span class="eyebrow">月度${esc(label)}</span><h2 id="marginTrendTitle">${esc(label)}是否正在改善</h2></div></div><div class="margin-trend">${monthly.length ? monthly.map((item) => { const margin = type === "gross" ? item.grossMargin : item.operatingMargin; if (margin === null) return ""; const position = Math.max(2, Math.min(98, 50 + margin * 200)); return `<div class="margin-month"><span>${esc(displayMonth(item.month))}</span><div class="margin-track" role="img" aria-label="${attr(displayMonth(item.month))}${attr(label)} ${attr(formatPercent(margin))}"><i class="${margin >= 0 ? "positive" : ""}" style="left:${position}%"></i></div><strong class="${margin < 0 ? "negative-text" : ""}">${esc(formatPercent(margin))}</strong></div>`; }).join("") : `<div class="empty-insights"><div><strong>暂无月度数据</strong></div></div>`}</div></section>`;
  }

  function renderLossRanking(a, type = "operating") {
    const profitKey = type === "gross" ? "grossProfit" : "operatingProfit";
    const marginKey = type === "gross" ? "grossMargin" : "operatingMargin";
    const label = type === "gross" ? "毛利润" : "经营利润";
    const losses = a.products.filter((item) => !isProfitAdjustmentProduct(item.name)).sort((x, y) => x[profitKey] - y[profitKey]).slice(0, 4);
    return `<section class="surface driver-card" aria-labelledby="lossRankingTitle"><div class="section-heading"><div><span class="eyebrow">利润缺口</span><h2 id="lossRankingTitle">优先处理哪些产品</h2></div></div><ol class="driver-list">${losses.length ? losses.map((item, index) => `<li><span class="driver-rank ${item[profitKey] < 0 ? "bad" : ""}">${String(index + 1).padStart(2, "0")}</span><div><strong>${esc(item.name)}</strong><p>${esc(profitDescription(item, type !== "gross"))}</p>${evidenceTicket([productEvidence(a, item)], item[profitKey] < 0 ? "warn" : "verified")}</div></li>`).join("") : `<li><span class="driver-rank">01</span><div><strong>暂无产品利润数据</strong><p>导入产品维度后显示。</p></div></li>`}</ol></section>`;
  }

  function renderAiSection(view) {
    const insights = contextualInsights(view);
    const aiGenerated = isAiInsight(state.analysis);
    const providerOptions = state.providers.map((provider) => `<option value="${attr(provider.id)}">${esc(provider.name)}${provider.configured ? " · 已配置" : ""}</option>`).join("");
    return `<section class="surface ai-section" aria-labelledby="aiAdviceTitle"><header class="ai-header"><div class="ai-title"><span class="ai-mark"><svg><use href="#i-spark"></use></svg></span><div><h2 id="aiAdviceTitle">${aiGenerated ? "AI 深度解读" : "本地规则建议（非 AI）"} · ${esc(VIEW_META[view][0])}</h2><p>${aiGenerated ? "基于确定性指标解释原因，每条建议都绑定可复核证据。" : "由固定经营阈值生成，无需 API Key；配置模型后可进一步深度解读。"}</p></div></div><div class="ai-controls"><select class="select-field" data-insight-provider aria-label="选择生成建议的 AI 平台">${providerOptions}</select><button class="button button-primary" type="button" data-action="generate-insights" ${state.insightsLoading ? "disabled" : ""}>${state.insightsLoading ? `<span class="spinner"></span><span>正在分析</span>` : `<svg><use href="#i-spark"></use></svg><span>生成 AI 解读</span>`}</button></div></header><div class="ai-state">${state.insightsLoading ? `<div class="inline-state"><span class="spinner"></span><span>AI 正在核对经营事实并生成本页建议…</span></div>` : ""}</div><div class="ai-insight-grid">${insights.length ? insights.slice(0, 3).map((item, index) => `<article class="insight-card"><div class="insight-top"><span class="insight-type">${index === 0 ? "本页优先行动" : "经营改进"}</span><span class="insight-number">${String(index + 1).padStart(2, "0")}</span></div><h3>${esc(item.title)}</h3>${item.finding ? `<p>${esc(item.finding)}</p>` : ""}${item.action ? `<p class="insight-action"><strong>建议：</strong>${esc(item.action)}</p>` : ""}${evidenceTicket(item.evidence, "verified")}</article>`).join("") : `<div class="empty-insights"><div><strong>等待生成经营建议</strong><p>完成 AI 设置后，可以基于当前指标生成建议。</p></div></div>`}</div><footer class="ai-footer"><p>金额由本地计算；只有标注“AI 深度解读”时才代表模型已成功返回。</p><span>${state.evidence.size} 条确定性证据已登记</span></footer></section>`;
  }

  function contextualInsights(view) {
    const sourceAnalysis = state.analysis;
    if (!sourceAnalysis) return [];
    const a = IS_DESKTOP ? desktopScopedAnalysis(sourceAnalysis) : sourceAnalysis;
    const sourceInsights = periodMatchedInsights(sourceAnalysis);
    const base = view === "product"
      ? sourceInsights.filter((item) => !mentionsProfitAdjustmentProduct(`${item?.title || ""} ${item?.finding || ""} ${item?.action || ""}`))
      : sourceInsights;
    if (isAiInsight(sourceAnalysis) && (view !== "product" || base.length)) return base;
    if (mappingConfirmationRequired(a)) return [{ title: "先确认经营口径", finding: "当前存在多个可能的收入、成本或费用字段。", action: "在字段口径卡中人工确认后重新分析；确认前不要使用利润结论。", evidence: ["EV-QUALITY-001"] }];
    const hasOperating = a.capabilities.operatingProfitAvailable;
    const hasGross = a.capabilities.grossProfitAvailable;
    let primary;
    if (view === "product") {
      const productCandidates = a.products.filter((item) => !isProfitAdjustmentProduct(item.name));
      const target = hasOperating ? [...productCandidates].sort((x, y) => x.operatingProfit - y.operatingProfit)[0] : hasGross ? [...productCandidates].sort((x, y) => x.grossProfit - y.grossProfit)[0] : sortedByRevenue(productCandidates)[0];
      primary = target ? (hasOperating
        ? { title: `先处理 ${target.name} 的利润缺口`, finding: `该产品${profitDescription(target, true)}`, action: target.operatingProfit < 0 ? "复核直接成本、交付工时和报价底线。" : "保持当前利润底线，把销售资源优先投入高利润率产品。", evidence: [productEvidence(a, target), "EV-SUM-OP"] }
        : hasGross ? { title: `复核 ${target.name} 的毛利质量`, finding: `该产品${profitDescription(target, false)}`, action: "结合销量、价格和直接成本进一步下钻；未识别费用前不判断经营利润。", evidence: [productEvidence(a, target), "EV-SUM-GP"] }
          : { title: `关注 ${target.name} 的销售贡献`, finding: `该产品收入 ${formatCurrency(target.revenue)}。`, action: "先确认成本字段，再评价盈利质量。", evidence: [productEvidence(a, target), "EV-SUM-REV"] }) : null;
    } else if (view === "customer") {
      const scopedEntry = state.explorers.get("customerSummary");
      const scopedItems = scopedEntry?.status === "success" ? scopedEntry.data.items : null;
      const top = scopedItems ? [...scopedItems].sort((x, y) => numberValue(y.revenue) - numberValue(x.revenue))[0] : sortedByRevenue(a.customers)[0];
      const totalRevenue = scopedItems ? numberValue(scopedEntry.data.totals?.revenue) : numberValue(a.summary.revenue);
      const marginText = top && hasGross ? (top.grossMargin === null ? "，毛利率不可用" : `，毛利率 ${formatPercent(top.grossMargin)}`) : "";
      primary = top ? { title: `为 ${top.name} 建立集中度预案`, finding: `该客户在当前筛选内贡献 ${formatPercent(top.revenue / Math.max(totalRevenue, 1))} 收入${hasOperating && !scopedItems ? `，经营利润率 ${formatPercent(top.operatingMargin)}` : marginText}。`, action: hasGross ? "把收入贡献与履约投入放在同一张复盘表中，并准备续约与流失情景。" : "当前先管理集中度；确认成本后再判断客户价值。", evidence: [scopedItems ? "EV-SUM-GP" : customerEvidence(a, top), "EV-SUM-REV"] } : null;
    } else if (view === "profit") {
      const s = a.summary;
      const rates = operatingComparableRates(a);
      primary = hasOperating ? { title: s.operatingProfit < 0 ? "先用成本和费用动作覆盖亏损额" : "把当前利润率设为经营底线", finding: `本期经营利润 ${formatCurrency(s.operatingProfit)}，可比成本率 ${formatRate(rates.costRate)}，可比费用率 ${formatRate(rates.expenseRate)}。`, action: s.operatingProfit < 0 ? "分别给营业成本和期间费用设置月度压降目标，每周跟踪实际释放金额。" : "将利润率底线写入报价、促销和费用审批规则。", evidence: ["EV-SUM-COST", "EV-SUM-EXP", "EV-SUM-OP"] }
        : { title: "当前只分析到毛利层", finding: `本期毛利润 ${formatCurrency(s.grossProfit)}，毛利率 ${formatPercent(s.grossMargin)}。`, action: "确认期间费用字段后，再进行经营利润与扭亏测算。", evidence: ["EV-SUM-COST", "EV-SUM-GP"] };
    } else {
      primary = base[0] || (hasOperating ? { title: "把经营结果转成一个本周行动", finding: `本期经营利润 ${formatCurrency(a.summary.operatingProfit)}，利润率 ${formatPercent(a.summary.operatingMargin)}。`, action: a.summary.operatingProfit < 0 ? "先锁定最大的产品或客户利润缺口，再制定可量化的止损动作。" : "保持利润率底线，并复核增长最快业务的成本变化。", evidence: ["EV-SUM-OP", "EV-SUM-OM"] }
        : hasGross ? { title: "毛利结果需要补充费用口径", finding: `本期毛利润 ${formatCurrency(a.summary.grossProfit)}，毛利率 ${formatPercent(a.summary.grossMargin)}。`, action: "在费用字段确认前，只使用毛利层结论。", evidence: ["EV-SUM-GP", "EV-SUM-GM"] }
          : { title: "先完成销售结构复盘", finding: `本期收入 ${formatCurrency(a.summary.revenue)}。`, action: "确认成本字段后再进入利润分析。", evidence: ["EV-SUM-REV"] });
    }
    const result = [];
    if (primary) result.push(primary);
    base.forEach((item) => {
      if (!result.some((existing) => existing.title === item.title)) result.push(item);
    });
    return result;
  }

  function summaryStat(label, value, note, warn = false) {
    return `<div class="summary-stat ${warn ? "negative-text" : ""}"><span>${esc(label)}</span><strong>${esc(value)}</strong><p>${esc(note)}</p></div>`;
  }

  function evidenceTicket(ids, status = "verified", label) {
    const valid = ids.filter(Boolean);
    const text = label || (valid.length === 1 ? valid[0] : `${valid[0]} +${valid.length - 1}`);
    return `<button type="button" class="evidence-ticket ${status === "warn" ? "warn" : ""}" data-evidence="${attr(valid.join(","))}" aria-label="查看证据 ${attr(valid.join("、"))}">${esc(text)}</button>`;
  }

  function buildEvidenceLedger() {
    if (!state.analysis) {
      state.evidence.clear();
      return;
    }
    const sourceAnalysis = state.analysis;
    const a = IS_DESKTOP ? desktopScopedAnalysis(sourceAnalysis) : sourceAnalysis;
    const mappingBlocked = mappingConfirmationRequired(a);
    const hasRevenue = a.capabilities.hasRevenue && !mappingBlocked;
    state.evidence.clear();
    if (hasRevenue) registerEvidence("EV-SUM-REV", "营业收入", "所有有效经营记录的收入求和", formatCurrencyExact(a.summary.revenue));
    if (hasRevenue && a.capabilities.hasCost) registerEvidence("EV-SUM-COST", "已识别成本", "所有可解析成本值求和", formatCurrencyExact(a.summary.cost));
    if (hasRevenue && a.capabilities.grossProfitAvailable) {
      registerEvidence("EV-SUM-GP", "可比口径毛利润", comparableCoverageText(a.capabilities, "gross"), formatCurrencyExact(a.summary.grossProfit));
      registerEvidence("EV-SUM-GM", "可比口径毛利率", comparableCoverageText(a.capabilities, "gross"), formatPercent(a.summary.grossMargin));
    }
    if (hasRevenue && a.capabilities.hasExpenses) registerEvidence("EV-SUM-EXP", "期间费用", "所有可解析期间费用求和", formatCurrencyExact(a.summary.expenses));
    if (hasRevenue && a.capabilities.operatingProfitAvailable) {
      registerEvidence("EV-SUM-OP", "可比口径经营利润", comparableCoverageText(a.capabilities, "operating"), formatCurrencyExact(a.summary.operatingProfit), a.summary.operatingProfit < 0 ? "warn" : "verified");
      registerEvidence("EV-SUM-OM", "可比口径经营利润率", comparableCoverageText(a.capabilities, "operating"), formatPercent(a.summary.operatingMargin), a.summary.operatingMargin < 0 ? "warn" : "verified");
    }
    registerEvidence("EV-QUALITY-001", "数据质量与可比覆盖", `${a.quality.validRows} / ${a.quality.totalRows} 行已读取`, `${qualityScore(a.quality)} 分`, qualityScore(a.quality) < 95 ? "warn" : "verified");
    if (hasRevenue) {
      a.products.forEach((item, index) => registerEvidence(`EV-PRD-${pad(index + 1)}`, item.name, item.grossProfitAvailable ? "产品维度可比收入、成本与毛利汇总" : "产品收入汇总；利润口径不可用", item.grossProfitAvailable ? `${formatCurrencyExact(item.revenue)} / 毛利润 ${formatCurrencyExact(item.grossProfit)}` : formatCurrencyExact(item.revenue), item.grossProfitAvailable && item.grossProfit < 0 ? "warn" : "verified"));
      a.customers.forEach((item, index) => registerEvidence(`EV-CUS-${pad(index + 1)}`, item.name, item.grossProfitAvailable ? "客户维度可比收入、成本与毛利汇总" : "客户收入汇总；利润口径不可用", item.grossProfitAvailable ? `${formatCurrencyExact(item.revenue)} / 毛利润 ${formatCurrencyExact(item.grossProfit)}` : formatCurrencyExact(item.revenue), item.grossProfitAvailable && item.grossProfit < 0 ? "warn" : "verified"));
      a.monthly.forEach((item, index) => registerEvidence(`EV-MON-${pad(index + 1)}`, displayMonth(item.month), item.grossProfitAvailable ? "月度可比收入、成本与毛利汇总" : "月度收入汇总；利润口径不可用", item.grossProfitAvailable ? `${formatCurrencyExact(item.revenue)} / 毛利润 ${formatCurrencyExact(item.grossProfit)}` : formatCurrencyExact(item.revenue), item.grossProfitAvailable && item.grossProfit < 0 ? "warn" : "verified"));
    }
    const generatedByAi = isAiInsight(sourceAnalysis);
    const matchedInsights = periodMatchedInsights(sourceAnalysis);
    if (!mappingBlocked) {
      matchedInsights.forEach((insight) => insight.evidence.forEach((id) => {
        if (!state.evidence.has(id)) registerEvidence(id,
                generatedByAi ? "AI 解读引用依据" : "本地规则判断依据",
                generatedByAi ? "由已验证的 AI 结果引用" : "由固定经营规则引用，不代表 AI 已调用",
                insight.evidenceText || "已挂接");
      }));
      (currentPeriodInsightRecord(sourceAnalysis)?.evidenceRecords || []).forEach((record) => state.evidence.set(record.id, record));
    }
  }

  function registerEvidence(id, title, logic, value, status = "verified") {
    state.evidence.set(id, { id, title, logic, value, status, source: `${state.analysis.sourceFileCount} 个本地工作簿合并结果` });
  }

  function productEvidence(a, item) {
    const index = a.products.indexOf(item);
    return `EV-PRD-${pad(Math.max(0, index) + 1)}`;
  }

  function customerEvidence(a, item) {
    const index = a.customers.indexOf(item);
    return `EV-CUS-${pad(Math.max(0, index) + 1)}`;
  }

  function openEvidence(ids, opener) {
    state.drawerOpener = opener || document.activeElement;
    state.inspectorEvidenceIds = ids;
    state.inspectorTab = "evidence";
    if (document.querySelector(".desktop-inspector") && window.innerWidth >= 1180) {
      setInspectorCollapsed(false);
      renderInspector();
      document.querySelector(".desktop-inspector")?.focus?.();
      return;
    }
    const records = ids.map((id) => state.evidence.get(id) || { id, title: "分析证据", logic: "由分析服务返回的证据标识", value: "已挂接", source: "本地工作簿", status: "verified" });
    el.evidenceDrawerBody.innerHTML = records.map((record) => `<article class="evidence-record"><span class="record-id">${esc(record.id)} · ${record.status === "warn" ? "待复核" : "已核验"}</span><h3>${esc(record.title)}</h3><p>该结论可以回到确定性计算结果复核，AI 不参与数值计算。</p><dl><dt>数据来源</dt><dd>${esc(record.source)}</dd><dt>计算口径</dt><dd>${esc(record.logic)}</dd><dt>证据值</dt><dd>${esc(record.value)}</dd></dl></article>`).join("");
    el.evidenceDrawer.hidden = false;
    document.body.style.overflow = "hidden";
    el.evidenceDrawer.querySelector(".drawer-header [data-close-evidence]")?.focus();
  }

  function closeEvidence() {
    if (el.evidenceDrawer.hidden) return;
    el.evidenceDrawer.hidden = true;
    document.body.style.overflow = "";
    state.drawerOpener?.focus?.();
    state.drawerOpener = null;
  }

  function trapDrawerFocus(event) {
    const focusable = Array.from(el.evidenceDrawer.querySelectorAll("button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex='-1'])")).filter((node) => !node.hidden && node.tabIndex >= 0);
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable.at(-1);
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  }

  function bindChartInteractions() {
    el.viewContent.querySelectorAll("[data-chart-tip]").forEach((target) => {
      const show = (event) => showChartTooltip(target, event);
      const hide = () => { el.chartTooltip.hidden = true; };
      target.addEventListener("pointerenter", show);
      target.addEventListener("pointermove", show);
      target.addEventListener("pointerleave", hide);
      target.addEventListener("focus", show);
      target.addEventListener("blur", hide);
    });
  }

  function showChartTooltip(target, event) {
    el.chartTooltip.innerHTML = `<strong>${esc(target.dataset.month)}</strong><span>收入 ${esc(target.dataset.revenue)}</span><span>成本 ${esc(target.dataset.cost)}</span><span>利润 ${esc(target.dataset.profit)}</span>`;
    el.chartTooltip.hidden = false;
    const rect = target.getBoundingClientRect();
    const x = event.clientX || rect.left + rect.width / 2;
    const y = event.clientY || rect.top;
    el.chartTooltip.style.left = `${Math.max(8, Math.min(x + 12, window.innerWidth - 178))}px`;
    el.chartTooltip.style.top = `${Math.max(8, Math.min(y + 12, window.innerHeight - 126))}px`;
  }

  async function generateInsights() {
    if (!state.analysis) {
      toast("还没有分析数据", "请先导入经营表格。", "error");
      return;
    }
    if (!state.analysis || state.insightsLoading) return;
    const requestAnalysisId = state.analysis.id;
    const requestPeriodSignature = currentPeriodSignature();
    const requestToken = ++state.insightRequestToken;
    const select = el.inspectorAiContent?.querySelector("[data-insight-provider]") || el.viewContent.querySelector("[data-insight-provider]");
    state.activeProviderId = select?.value || state.activeProviderId;
    const provider = state.providers.find((item) => item.id === state.activeProviderId);
    if (!provider?.configured) {
      toast("需要完成 AI 设置", `请先为 ${provider?.name || "所选平台"} 配置 API Key。`, "error");
      openSettings();
      return;
    }
    state.insightsLoading = true;
    renderActiveView();
    try {
      const result = await apiFetch(`/api/analysis/${encodeURIComponent(state.analysis.id)}/deep-insights`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ providerId: state.activeProviderId, ...(IS_DESKTOP ? periodRequestPayload() : {}) })
      }, 90000);
      if (state.insightRequestToken !== requestToken || state.analysis?.id !== requestAnalysisId || currentPeriodSignature() !== requestPeriodSignature) return;
      if (!Array.isArray(result?.insights) || result.insights.length < 2 || !result?.providerId || !result?.model) {
        throw new Error("服务未返回通过证据校验的 AI 深度解读。");
      }
      const evidenceRecords = [];
      const normalized = result.insights.map((item, index) => {
        const references = Array.isArray(item.evidence) ? item.evidence : [];
        references.forEach((reference) => {
          if (!reference?.evidenceId) return;
          evidenceRecords.push({
            id: String(reference.evidenceId),
            title: `${schemaCodeLabel(item.category || "AI")} · ${item.title || `经营发现 ${index + 1}`}`,
            logic: `${item.claimType || "FACT"} · AI 只解释本地聚合证据`,
            value: String(reference.display || "已通过证据白名单校验"),
            source: "本地确定性计算",
            status: "verified"
          });
        });
        return {
          title: String(item.title || `经营发现 ${index + 1}`),
          finding: String(item.finding || ""),
          action: String(item.action || ""),
          evidence: references.map((reference) => String(reference.evidenceId || "")).filter(Boolean),
          evidenceText: references.map((reference) => String(reference.display || "")).filter(Boolean).join("；"),
          claimType: String(item.claimType || "FACT"),
          category: String(item.category || "OPERATIONS")
        };
      });
      const insightRecord = {
        insights: normalized,
        providerId: String(result.providerId),
        model: String(result.model),
        generatedAt: result.generatedAt || new Date().toISOString(),
        evidenceRecords
      };
      if (IS_DESKTOP) state.periodInsights.set(requestPeriodSignature, insightRecord);
      if (!IS_DESKTOP || requestPeriodSignature === defaultPeriodSignature()) {
        state.analysis = {
          ...state.analysis,
          insights: normalized,
          insightSource: "AI",
          insightProviderId: insightRecord.providerId,
          insightModel: insightRecord.model,
          insightsGeneratedAt: insightRecord.generatedAt,
          insightPeriodSignature: requestPeriodSignature
        };
      }
      buildEvidenceLedger();
      toast("AI 解读已更新", `已通过 ${providerName(state.activeProviderId)} · ${insightRecord.model || provider?.model || "当前模型"} 完成。`, "success");
    } catch (error) {
      if (state.insightRequestToken !== requestToken || state.analysis?.id !== requestAnalysisId || currentPeriodSignature() !== requestPeriodSignature) return;
      toast("AI 分析未完成", friendlyError(error), "error");
    } finally {
      if (state.insightRequestToken === requestToken && state.analysis?.id === requestAnalysisId && currentPeriodSignature() === requestPeriodSignature) {
        state.insightsLoading = false;
        renderActiveView();
      }
    }
  }

  async function loadProviders() {
    try {
      const result = await apiFetch("/api/providers", {}, 8000);
      const list = Array.isArray(result) ? result : result?.providers;
      if (Array.isArray(list)) state.providers = mergeProviders(list);
      if (!Array.isArray(result) && state.providers.some((item) => item.id === result?.selectedProvider)) {
        state.activeProviderId = result.selectedProvider;
      }
    } catch (_) {
      state.providers = DEFAULT_PROVIDERS.map(cloneData);
    }
    if (!state.providers.some((item) => item.id === state.activeProviderId && item.configured)) {
      const configured = state.providers.find((item) => item.configured);
      if (configured) state.activeProviderId = configured.id;
    }
    renderProviderStatus();
  }

  function mergeProviders(list) {
    return DEFAULT_PROVIDERS.map((fallback) => {
      const value = list.find((item) => String(item.id).toLowerCase() === fallback.id) || {};
      const models = Array.isArray(value.models) && value.models.length ? value.models.map(normalizeProviderModel) : fallback.models.map(normalizeProviderModel);
      const model = String(value.model || fallback.model);
      const withCurrent = models.some((item) => item.id === model) ? models : [{ id: model, label: model, description: "当前已保存模型", recommended: false }, ...models];
      return { id: fallback.id, name: String(value.name || fallback.name), baseUrl: String(value.baseUrl || fallback.baseUrl), model, models: withCurrent, customModelAllowed: value.customModelAllowed ?? fallback.customModelAllowed ?? true, configured: Boolean(value.configured) };
    });
  }

  function normalizeProviderModel(value) {
    if (typeof value === "string") return { id: value, label: value, description: "", recommended: false };
    const id = String(value?.id || value?.model || value?.name || "");
    return {
      id,
      label: String(value?.label || value?.displayName || id),
      description: String(value?.description || value?.hint || ""),
      recommended: Boolean(value?.recommended)
    };
  }

  function renderProviderStatus() {
    const configured = state.providers.filter((item) => item.configured);
    el.providerStatusDot.classList.toggle("ready", configured.length > 0);
    el.providerStatusDot.classList.toggle("warn", configured.length === 0);
    if (state.analysis) renderActiveView();
    else renderDesktopStatus();
  }

  function openSettings() {
    state.settingsProviderId = state.providers.find((item) => item.configured)?.id || state.activeProviderId || state.providers[0].id;
    renderProviderChoices();
    renderProviderForm();
    showModal(el.settingsDialog);
    window.setTimeout(() => el.providerChoices.querySelector("[aria-checked='true']")?.focus(), 40);
  }

  function closeSettings() {
    closeModal(el.settingsDialog);
    el.openSettings.focus();
  }

  function selectSettingsProvider(id) {
    if (!state.providers.some((item) => item.id === id)) return;
    state.settingsProviderId = id;
    renderProviderChoices();
    renderProviderForm();
    el.providerBaseUrl.focus();
  }

  function renderProviderChoices() {
    el.providerChoices.innerHTML = state.providers.map((provider) => `<button type="button" class="provider-choice ${provider.id === "bailian" ? "bailian" : ""}" role="radio" aria-checked="${provider.id === state.settingsProviderId}" data-provider-id="${attr(provider.id)}"><span class="provider-logo" aria-hidden="true">${provider.id === "bailian" ? "百炼" : "DS"}</span><span><strong>${esc(provider.name)}</strong><small>${provider.configured ? `已连接 · ${esc(provider.model)}` : "尚未配置"}</small></span><i class="provider-state ${provider.configured ? "ready" : ""}" aria-hidden="true"></i></button>`).join("");
  }

  function renderProviderForm() {
    const provider = selectedProvider();
    if (!provider) return;
    el.activeProviderHint.textContent = `${provider.name} · ${provider.configured ? "已保存配置" : "等待配置"}`;
    el.providerBaseUrl.value = provider.baseUrl;
    const models = (provider.models || []).map(normalizeProviderModel);
    const known = models.some((model) => model.id === provider.model);
    const customOption = provider.customModelAllowed === false ? "" : `<option value="__custom__">自定义模型…</option>`;
    el.providerModel.innerHTML = `${models.map((model) => `<option value="${attr(model.id)}">${esc(model.label)}${model.recommended ? " · 推荐" : ""}</option>`).join("")}${customOption}`;
    el.providerModel.value = known ? provider.model : "__custom__";
    el.providerCustomModel.value = known ? "" : provider.model;
    el.providerCustomModel.placeholder = provider.id === "bailian"
      ? "例如：qwen3.7-plus 或你的百炼模型 ID"
      : "例如：deepseek-v4-pro";
    el.providerApiKey.value = "";
    el.providerApiKey.type = "password";
    el.toggleApiKey.setAttribute("aria-pressed", "false");
    el.toggleApiKey.setAttribute("aria-label", "显示 API Key");
    el.toggleApiKey.innerHTML = `<svg><use href="#i-eye"></use></svg>`;
    el.apiKeyHelp.textContent = provider.configured ? "已保存的密钥不会回显；留空则保持原密钥。" : "保存后输入框会自动清空。";
    setConnectionState(provider.configured ? "配置已保存，可以测试连接。" : "尚未测试连接。", provider.configured ? "success" : "");
    toggleCustomModelField();
    updateModelHelp();
  }

  function toggleCustomModelField() {
    const custom = el.providerModel.value === "__custom__";
    el.customModelField.hidden = !custom;
    if (custom && !el.providerCustomModel.value) window.setTimeout(() => el.providerCustomModel.focus(), 0);
  }

  function updateModelHelp() {
    const provider = selectedProvider();
    if (!provider) return;
    if (el.providerModel.value === "__custom__") {
      el.providerModelHelp.textContent = "请输入平台支持的准确模型 ID。";
      return;
    }
    const model = (provider.models || []).map(normalizeProviderModel).find((item) => item.id === el.providerModel.value);
    el.providerModelHelp.textContent = model?.description || "使用平台返回的可用模型。";
  }

  function toggleApiKeyVisibility() {
    const show = el.providerApiKey.type === "password";
    el.providerApiKey.type = show ? "text" : "password";
    el.toggleApiKey.setAttribute("aria-pressed", String(show));
    el.toggleApiKey.setAttribute("aria-label", show ? "隐藏 API Key" : "显示 API Key");
    el.toggleApiKey.innerHTML = `<svg><use href="#${show ? "i-eye-off" : "i-eye"}"></use></svg>`;
    el.providerApiKey.focus();
  }

  async function saveSelectedProvider({ notify = true } = {}) {
    const provider = selectedProvider();
    if (!provider) return false;
    const baseUrl = el.providerBaseUrl.value.trim();
    const model = (el.providerModel.value === "__custom__" ? el.providerCustomModel.value : el.providerModel.value).trim();
    const apiKey = el.providerApiKey.value.trim();
    if (!/^https:\/\//i.test(baseUrl)) {
      setConnectionState("API 地址需要以 https:// 开头。", "error");
      return false;
    }
    if (!model) {
      setConnectionState("请选择模型或填写自定义模型名称。", "error");
      return false;
    }
    setButtonBusy(el.saveProvider, true, "保存中…");
    try {
      const result = await apiFetch(`/api/providers/${encodeURIComponent(provider.id)}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ baseUrl, model, apiKey })
      });
      const responseModels = Array.isArray(result?.models) && result.models.length ? result.models.map(normalizeProviderModel) : (provider.models || []).map(normalizeProviderModel);
      const updatedModel = String(result?.model || model);
      const updatedModels = responseModels.some((item) => item.id === updatedModel) ? responseModels : [{ id: updatedModel, label: updatedModel, description: "当前已保存模型", recommended: false }, ...responseModels];
      const updated = { ...provider, baseUrl: String(result?.baseUrl || baseUrl), model: updatedModel, models: updatedModels, customModelAllowed: result?.customModelAllowed ?? provider.customModelAllowed ?? true, configured: result?.configured ?? Boolean(provider.configured || apiKey) };
      state.providers[state.providers.findIndex((item) => item.id === provider.id)] = updated;
      state.activeProviderId = provider.id;
      el.providerApiKey.value = "";
      renderProviderChoices();
      renderProviderForm();
      renderProviderStatus();
      setConnectionState("配置已加密保存到本机。", "success");
      if (notify) toast(`${provider.name} 已保存`, "API Key 不会在页面中回显。", "success");
      return true;
    } catch (error) {
      setConnectionState(friendlyError(error), "error");
      if (notify) toast("配置保存失败", friendlyError(error), "error");
      return false;
    } finally {
      setButtonBusy(el.saveProvider, false, "保存配置");
    }
  }

  async function testSelectedProvider() {
    const provider = selectedProvider();
    if (!provider) return;
    const saved = await saveSelectedProvider({ notify: false });
    if (!saved) return;
    setButtonBusy(el.testProvider, true, "测试中…");
    setConnectionState("正在连接模型服务…", "");
    try {
      const result = await apiFetch(`/api/providers/${encodeURIComponent(provider.id)}/test`, { method: "POST" }, 45000);
      const success = Boolean(result?.success ?? true);
      setConnectionState(result?.message || (success ? "连接成功，模型可以正常响应。" : "连接未通过。"), success ? "success" : "error");
      toast(success ? "连接成功" : "连接未通过", result?.message || provider.name, success ? "success" : "error");
    } catch (error) {
      setConnectionState(friendlyError(error), "error");
      toast("连接测试失败", friendlyError(error), "error");
    } finally {
      setButtonBusy(el.testProvider, false, "测试连接");
    }
  }

  function selectedProvider() {
    return state.providers.find((item) => item.id === state.settingsProviderId);
  }

  function setConnectionState(message, type = "") {
    el.providerConnectionState.className = `connection-result ${type}`;
    el.providerConnectionState.innerHTML = `<span>${esc(message)}</span>`;
  }

  async function loadSyncStatus() {
    try {
      const result = await apiFetch("/api/sync/status", {}, 8000);
      state.sync = normalizeSyncStatus(result);
    } catch (error) {
      state.sync = { connected: false, available: error.status !== 404, account: "", lastSyncedAt: "", message: error.status === 404 ? "当前本地服务版本尚未启用账户同步" : "暂时无法读取同步状态" };
    }
    renderSyncStatus();
  }

  function normalizeSyncStatus(input) {
    const value = input || {};
    return {
      connected: Boolean(value.connected ?? value.linked ?? value.authenticated),
      available: true,
      account: String(value.account || value.email || value.userName || value.displayName || (value.connected ? "DataMaster 网页账户" : "")),
      siteUrl: String(value.siteUrl || ""),
      lastSyncedAt: String(value.lastSyncedAt || value.lastSyncAt || value.updatedAt || ""),
      message: String(value.message || "")
    };
  }

  function openSync() {
    renderSyncStatus();
    showModal(el.syncDialog);
    window.setTimeout(() => (state.sync.connected ? el.pullSync : el.syncToken).focus(), 40);
  }

  function closeSync() {
    closeModal(el.syncDialog);
    el.openSync.focus();
  }

  function renderSyncStatus() {
    const connected = state.sync.connected;
    el.syncStatusDot.classList.toggle("ready", connected);
    el.syncStatusDot.classList.toggle("warn", !connected);
    if (!el.syncStatusCard) return;
    const title = connected ? `已连接：${state.sync.account}` : state.sync.available ? "尚未连接网页账户" : "账户同步尚未启用";
    const detail = connected
      ? (state.sync.lastSyncedAt ? `上次同步 ${formatDateTime(state.sync.lastSyncedAt)}` : state.sync.message || (state.sync.siteUrl ? `已连接 ${state.sync.siteUrl}` : "连接已建立，可以拉取或推送配置。"))
      : (state.sync.message && state.sync.message !== title ? state.sync.message : "在网页版生成同步令牌，再粘贴到下方建立连接。");
    el.syncStatusCard.className = `sync-status-card ${connected ? "connected" : ""}`;
    el.syncStatusCard.innerHTML = `<span class="sync-mark"><svg><use href="#i-cloud"></use></svg></span><div><strong>${esc(title)}</strong><p>${esc(detail)}</p></div>`;
    if (state.sync.siteUrl) el.openSyncSite.href = state.sync.siteUrl;
    el.syncToken.closest("label").hidden = connected;
    el.connectSync.hidden = connected;
    el.pullSync.disabled = !connected;
    el.pushSync.disabled = !connected;
    el.disconnectSync.disabled = !connected;
    renderDesktopStatus();
  }

  async function connectSync() {
    const token = el.syncToken.value.trim();
    if (!token) {
      toast("需要同步令牌", "请先粘贴网页端生成的同步令牌。", "error");
      el.syncToken.focus();
      return;
    }
    setButtonBusy(el.connectSync, true, "连接中…");
    try {
      const result = await apiFetch("/api/sync/connect", { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ token }) }, 30000);
      state.sync = normalizeSyncStatus(result || { connected: true, message: "连接成功" });
      if (!state.sync.connected) state.sync.connected = true;
      el.syncToken.value = "";
      renderSyncStatus();
      toast("账户已连接", state.sync.account || "现在可以同步偏好和 AI 配置。", "success");
    } catch (error) {
      toast("连接失败", friendlyError(error), "error");
    } finally {
      setButtonBusy(el.connectSync, false, "连接账户");
    }
  }

  async function runSyncAction(action) {
    if (!state.sync.connected) return;
    const button = action === "pull" ? el.pullSync : el.pushSync;
    const actionName = action === "pull" ? "拉取" : "推送";
    setButtonBusy(button, true, `${actionName}中…`);
    try {
      const result = await apiFetch(`/api/sync/${action}`, { method: "POST" }, 45000);
      if (result && typeof result === "object") state.sync = { ...state.sync, ...normalizeSyncStatus({ ...state.sync, ...result }), connected: true };
      else state.sync.lastSyncedAt = new Date().toISOString();
      renderSyncStatus();
      if (action === "pull") await loadProviders();
      toast(`${actionName}完成`, result?.message || "本地与云端配置已更新。", "success");
    } catch (error) {
      toast(`${actionName}失败`, friendlyError(error), "error");
    } finally {
      setButtonBusy(button, false, action === "pull" ? "拉取云端配置" : "推送本地配置");
    }
  }

  async function disconnectSync() {
    setButtonBusy(el.disconnectSync, true, "断开中…");
    try {
      await apiFetch("/api/sync/disconnect", { method: "DELETE" }, 30000);
      state.sync = { connected: false, available: true, account: "", lastSyncedAt: "", message: "已断开网页账户" };
      renderSyncStatus();
      toast("已断开账户", "本地分析和已保存配置不会被删除。", "success");
    } catch (error) {
      toast("断开失败", friendlyError(error), "error");
    } finally {
      setButtonBusy(el.disconnectSync, false, "断开连接");
    }
  }

  function normalizeAnalysis(raw) {
    const input = raw?.analysis || raw || {};
    const rawSummary = input.summary || input.kpis || {};
    const capabilities = normalizeCapabilities(input.capabilities || {}, rawSummary, input);
    const summary = normalizeSummary(rawSummary, capabilities);
    const profile = normalizeProfile(input.profile || {}, input);
    const insightSource = String(input.insightSource || input.insightsSource || "LOCAL_RULES").toUpperCase();
    return {
      id: String(input.id || input.analysisId || `analysis-${Date.now()}`),
      sourceFileCount: numberValue(input.sourceFileCount ?? input.fileCount, 0),
      rowCount: numberValue(input.rowCount ?? input.totalRows, 0),
      summary,
      capabilities,
      profile,
      quality: normalizeQuality(input.quality || {}, input.rowCount),
      products: normalizeBreakdown(input.products || input.productRanking || [], capabilities),
      customers: normalizeBreakdown(input.customers || input.customerRanking || [], capabilities),
      monthly: normalizeMonthly(input.monthly || input.monthlyTrend || [], capabilities),
      dynamicBreakdowns: normalizeDynamicBreakdowns(input.dynamicBreakdowns || input.breakdowns || [], capabilities),
      insights: normalizeInsights(input.insights || [], insightSource === "AI" ? "EV-AI" : "EV-RULE"),
      insightSource,
      fieldUnderstandingSource: String(input.fieldUnderstandingSource || input.mappingSource || profile.mappingSource || "LOCAL_RULES").toUpperCase() === "AI" ? "AI" : "LOCAL_RULES",
      insightProviderId: String(input.insightProviderId || input.providerId || ""),
      insightModel: String(input.insightModel || input.model || ""),
      insightsGeneratedAt: input.insightsGeneratedAt || input.insightGeneratedAt || "",
      insightPeriodSignature: String(input.insightPeriodSignature || input.periodSignature || ""),
      generatedAt: input.generatedAt || new Date().toISOString()
    };
  }

  function isAiInsight(analysis = state.analysis) {
    if (!IS_DESKTOP) return String(analysis?.insightSource || "").toUpperCase() === "AI";
    return Boolean(currentPeriodInsightRecord(analysis)?.insights?.length);
  }

  function normalizeSummary(input, capabilities = null) {
    const revenue = capabilities?.hasRevenue === false ? null : nullableNumber(firstDefined(input.revenue, input.sales));
    const cost = capabilities?.hasCost === false ? null : nullableNumber(firstDefined(input.cost, input.totalCost));
    const expenses = capabilities?.hasExpenses === false ? null : nullableNumber(firstDefined(input.expenses, input.periodExpenses));
    const grossComparableRevenue = nullableNumber(input.grossComparableRevenue);
    const grossComparableCost = nullableNumber(input.grossComparableCost);
    const operatingComparableRevenue = nullableNumber(input.operatingComparableRevenue);
    const operatingComparableCost = nullableNumber(input.operatingComparableCost);
    const operatingComparableExpenses = nullableNumber(input.operatingComparableExpenses);
    const grossProfitInput = nullableNumber(input.grossProfit);
    const operatingProfitInput = nullableNumber(firstDefined(input.operatingProfit, input.profit, input.netProfit));
    const grossProfitFallback = grossComparableRevenue !== null && grossComparableCost !== null
      ? grossComparableRevenue - grossComparableCost
      : isCompleteCoverage(capabilities?.grossProfitRevenueCoverage) && revenue !== null && cost !== null
        ? revenue - cost : null;
    const grossProfit = capabilities?.grossProfitAvailable === false ? null : (grossProfitInput ?? grossProfitFallback);
    const operatingProfitFallback = operatingComparableRevenue !== null && operatingComparableCost !== null && operatingComparableExpenses !== null
      ? operatingComparableRevenue - operatingComparableCost - operatingComparableExpenses
      : isCompleteCoverage(capabilities?.operatingProfitRevenueCoverage) && grossProfit !== null && expenses !== null
        ? grossProfit - expenses : null;
    const operatingProfit = capabilities?.operatingProfitAvailable === false ? null : (operatingProfitInput ?? operatingProfitFallback);
    const grossMarginInput = nullableRatio(input.grossMargin);
    const operatingMarginInput = nullableRatio(firstDefined(input.operatingMargin, input.profitMargin));
    const grossMarginBasis = grossComparableRevenue !== null ? grossComparableRevenue : revenue;
    const operatingMarginBasis = operatingComparableRevenue !== null ? operatingComparableRevenue : revenue;
    const grossMargin = capabilities?.grossProfitAvailable === false || grossMarginBasis === null || grossMarginBasis <= 0
      ? null
      : (grossMarginInput ?? ((grossComparableRevenue !== null || isCompleteCoverage(capabilities?.grossProfitRevenueCoverage)) && grossProfit !== null
        ? grossProfit / grossMarginBasis : null));
    const operatingMargin = capabilities?.operatingProfitAvailable === false || operatingMarginBasis === null || operatingMarginBasis <= 0
      ? null
      : (operatingMarginInput ?? ((operatingComparableRevenue !== null || isCompleteCoverage(capabilities?.operatingProfitRevenueCoverage)) && operatingProfit !== null
        ? operatingProfit / operatingMarginBasis : null));
    return {
      revenue,
      cost,
      grossComparableRevenue,
      grossComparableCost,
      grossProfit,
      expenses,
      operatingComparableRevenue,
      operatingComparableCost,
      operatingComparableExpenses,
      operatingProfit,
      grossMargin,
      operatingMargin
    };
  }

  function normalizeBreakdown(list, capabilities = null) {
    return (Array.isArray(list) ? list : []).map((item, index) => ({
      name: String(item.name || item.label || item.product || item.customer || item.value || `未命名 ${index + 1}`),
      ...normalizeSummary(item, capabilities),
      quantity: nullableNumber(firstDefined(item.quantity, item.qty, item.volume)),
      count: nullableNumber(firstDefined(item.count, item.rowCount, item.records)),
      grossProfitAvailable: item.grossProfitAvailable ?? capabilities?.grossProfitAvailable ?? false,
      operatingProfitAvailable: item.operatingProfitAvailable ?? capabilities?.operatingProfitAvailable ?? false,
      grossProfitCoverage: nullableNumber(item.grossProfitCoverage),
      operatingProfitCoverage: nullableNumber(item.operatingProfitCoverage),
      grossProfitExcludedRows: numberValue(item.grossProfitExcludedRows, 0),
      operatingProfitExcludedRows: numberValue(item.operatingProfitExcludedRows, 0)
    }));
  }

  function normalizeMonthly(list, capabilities = null) {
    return (Array.isArray(list) ? list : []).map((item, index) => ({
      month: String(item.month || item.period || `第 ${index + 1} 期`),
      ...normalizeSummary(item, capabilities),
      quantity: nullableNumber(firstDefined(item.quantity, item.qty)),
      grossProfitAvailable: item.grossProfitAvailable ?? capabilities?.grossProfitAvailable ?? false,
      operatingProfitAvailable: item.operatingProfitAvailable ?? capabilities?.operatingProfitAvailable ?? false,
      grossProfitCoverage: nullableNumber(item.grossProfitCoverage),
      operatingProfitCoverage: nullableNumber(item.operatingProfitCoverage),
      grossProfitExcludedRows: numberValue(item.grossProfitExcludedRows, 0),
      operatingProfitExcludedRows: numberValue(item.operatingProfitExcludedRows, 0)
    }));
  }

  function normalizeCapabilities(input, rawSummary, analysisInput) {
    const explicit = input && Object.keys(input).length > 0;
    const hasRevenue = explicit ? Boolean(input.hasRevenue) : hasAnyKey(rawSummary, ["revenue", "sales"]);
    const hasCost = explicit ? Boolean(input.hasCost) : hasAnyKey(rawSummary, ["cost", "totalCost"]);
    const hasExpenses = explicit ? Boolean(input.hasExpenses) : hasAnyKey(rawSummary, ["expenses", "periodExpenses"]);
    const hasQuantity = explicit ? Boolean(input.hasQuantity) : [...(analysisInput.products || []), ...(analysisInput.monthly || [])].some((item) => hasAnyKey(item, ["quantity", "qty"]));
    const hasDate = explicit ? Boolean(input.hasDate) : Array.isArray(analysisInput.monthly || analysisInput.monthlyTrend) && (analysisInput.monthly || analysisInput.monthlyTrend).length > 0;
    const hasProduct = explicit ? Boolean(input.hasProduct) : Array.isArray(analysisInput.products || analysisInput.productRanking) && (analysisInput.products || analysisInput.productRanking).length > 0;
    const hasCustomer = explicit ? Boolean(input.hasCustomer) : Array.isArray(analysisInput.customers || analysisInput.customerRanking) && (analysisInput.customers || analysisInput.customerRanking).length > 0;
    return {
      hasRevenue,
      hasCost,
      hasExpenses,
      hasQuantity,
      hasDate,
      hasProduct,
      hasCustomer,
      grossProfitAvailable: input.grossProfitAvailable ?? (hasRevenue && hasCost),
      operatingProfitAvailable: input.operatingProfitAvailable ?? (hasRevenue && hasCost && hasExpenses),
      revenueCoverage: nullableNumber(input.revenueCoverage),
      costCoverage: nullableNumber(input.costCoverage),
      expenseCoverage: nullableNumber(input.expenseCoverage),
      grossProfitCoverage: nullableNumber(input.grossProfitCoverage),
      operatingProfitCoverage: nullableNumber(input.operatingProfitCoverage),
      grossProfitRevenueCoverage: nullableNumber(input.grossProfitRevenueCoverage),
      operatingProfitRevenueCoverage: nullableNumber(input.operatingProfitRevenueCoverage),
      grossProfitExcludedRows: numberValue(input.grossProfitExcludedRows, 0),
      grossProfitExcludedRevenue: numberValue(input.grossProfitExcludedRevenue, 0),
      operatingProfitExcludedRows: numberValue(input.operatingProfitExcludedRows, 0),
      operatingProfitExcludedRevenue: numberValue(input.operatingProfitExcludedRevenue, 0),
      unavailableReasons: Array.isArray(input.unavailableReasons) ? input.unavailableReasons.map(String) : []
    };
  }

  function normalizeProfile(input, analysisInput) {
    const rawMappings = input.mappings || analysisInput.fieldMappings || [];
    const rawIssues = input.mappingIssues || analysisInput.mappingIssues || [];
    return {
      mappings: (Array.isArray(rawMappings) ? rawMappings : []).map((mapping) => ({
        columnId: String(mapping.columnId || mapping.id || mapping.header || ""),
        fieldId: String(mapping.fieldId || mapping.semanticField || mapping.target || ""),
        role: String(mapping.role || ""),
        header: String(mapping.header || mapping.label || mapping.columnId || "未命名列"),
        source: String(mapping.source || mapping.sheet || ""),
        columnIndex: nullableNumber(mapping.columnIndex),
        confidence: nullableNumber(mapping.confidence),
        selected: Boolean(mapping.selected),
        nonBlankCount: nullableNumber(mapping.nonBlankCount),
        validCount: nullableNumber(mapping.validCount),
        rowCount: nullableNumber(mapping.rowCount),
        coverage: nullableNumber(mapping.coverage),
        validCoverage: nullableNumber(mapping.validCoverage),
        alternatives: Array.isArray(mapping.alternatives) ? mapping.alternatives : []
      })),
      mappingIssues: (Array.isArray(rawIssues) ? rawIssues : []).map((issue) => ({
        severity: String(issue.severity || issue.level || "WARNING").toUpperCase(),
        code: String(issue.code || issue.type || "MAPPING_REVIEW"),
        message: String(issue.message || issue.description || "字段口径需要复核。"),
        source: String(issue.source || ""),
        candidates: Array.isArray(issue.candidates) ? issue.candidates.map(String) : []
      })),
      availableDimensions: Array.isArray(input.availableDimensions) ? input.availableDimensions.map(String) : [],
      availableMetrics: Array.isArray(input.availableMetrics) ? input.availableMetrics.map(String) : [],
      profiledRows: numberValue(input.profiledRows, analysisInput.rowCount || 0),
      mappingSource: String(input.mappingSource || analysisInput.fieldUnderstandingSource || "LOCAL_RULES")
    };
  }

  function normalizeDynamicBreakdowns(list, capabilities) {
    return (Array.isArray(list) ? list : []).map((dimension, index) => {
      const values = dimension.values || dimension.items || dimension.groups || dimension.breakdown || [];
      return {
        dimensionId: String(dimension.dimensionId || dimension.id || dimension.fieldId || `dimension-${index + 1}`),
        dimensionLabel: String(dimension.dimensionLabel || dimension.label || dimension.name || dimension.dimensionId || `维度 ${index + 1}`),
        values: normalizeBreakdown(values, capabilities),
        totalGroups: numberValue(dimension.totalGroups, Array.isArray(values) ? values.length : 0),
        truncated: Boolean(dimension.truncated)
      };
    });
  }

  function normalizeQuality(input, fallbackRows) {
    const totalRows = numberValue(input.totalRows, fallbackRows || 0);
    return { totalRows, validRows: numberValue(input.validRows, totalRows), missingDate: numberValue(input.missingDate, 0), missingProduct: numberValue(input.missingProduct, 0), missingCustomer: numberValue(input.missingCustomer, 0), invalidNumericCells: numberValue(input.invalidNumericCells, 0), formulaErrors: numberValue(input.profiledNumericErrors ?? input.formulaErrors ?? input.formulaErrorCells, 0), externalWorkbookLinks: numberValue(input.externalWorkbookLinks, 0), cachedFormulaCells: numberValue(input.cachedFormulaCells, 0), warnings: Array.isArray(input.warnings) ? input.warnings.map(String) : input.warnings ? [String(input.warnings)] : [] };
  }

  function normalizeInsights(list, evidencePrefix = "EV-RULE") {
    return (Array.isArray(list) ? list : []).map((item, index) => ({ title: String(item.title || item.finding || `经营发现 ${index + 1}`), finding: String(item.finding || item.description || ""), action: String(item.action || item.recommendation || ""), evidence: normalizeEvidenceInput(item.evidence || item.evidenceIds || item.evidenceId, `${evidencePrefix}-${pad(index + 1)}`), evidenceText: typeof item.evidence === "string" && !/EV-[A-Z0-9-]+/i.test(item.evidence) ? item.evidence : "" }));
  }

  function normalizeEvidenceInput(value, fallback) {
    if (Array.isArray(value)) {
      const ids = value.map((item) => typeof item === "string" ? item : item?.id || item?.evidenceId || item?.label).filter(Boolean).map(String);
      return ids.length ? ids : [fallback];
    }
    if (typeof value === "string" && value.trim()) {
      const ids = value.match(/EV-[A-Z0-9-]+/gi) || [];
      return ids.length ? ids.map((id) => id.toUpperCase()) : [fallback];
    }
    if (value && typeof value === "object") return [String(value.id || value.evidenceId || fallback)];
    return [fallback];
  }

  function setLoading(loading, message = "", { resumeExplorers = true } = {}) {
    state.loading = loading;
    el.importBtn.disabled = loading;
    el.dropZone.classList.toggle("uploading", loading);
    el.viewContent.setAttribute("aria-busy", String(loading));
    if (loading) setStatus("loading", message);
    else if (el.statusRegion.querySelector(".global-status.loading")) setStatus("", "");
    renderDesktopStatus();
    if (!loading && resumeExplorers) window.queueMicrotask(() => ensureActiveExplorers());
  }

  function setStatus(type, message, autoHide = 0, retry) {
    window.clearTimeout(setStatus.timer);
    if (!message) { el.statusRegion.innerHTML = ""; return; }
    const icon = type === "loading" ? `<span class="spinner"></span>` : `<svg><use href="#${type === "error" ? "i-alert" : "i-check"}"></use></svg>`;
    el.statusRegion.innerHTML = `<div class="global-status ${type}">${icon}<span>${esc(message)}</span>${retry ? `<button type="button">重试</button>` : ""}</div>`;
    if (retry) el.statusRegion.querySelector("button").addEventListener("click", retry, { once: true });
    if (autoHide) setStatus.timer = window.setTimeout(() => { el.statusRegion.innerHTML = ""; }, autoHide);
  }

  function toast(title, message, type = "success") {
    const node = document.createElement("div");
    node.className = `toast ${type}`;
    node.setAttribute("role", type === "error" ? "alert" : "status");
    node.innerHTML = `<span class="toast-icon"><svg><use href="#${type === "error" ? "i-alert" : "i-check"}"></use></svg></span><span><strong>${esc(title)}</strong><small>${esc(message)}</small></span><button type="button" aria-label="关闭通知">×</button>`;
    node.querySelector("button").addEventListener("click", () => node.remove());
    el.toastStack.appendChild(node);
    window.setTimeout(() => node.remove(), 5400);
  }

  function setButtonBusy(button, busy, text) {
    button.disabled = busy;
    button.innerHTML = busy ? `<span class="spinner"></span><span>${esc(text)}</span>` : `<span>${esc(text)}</span>`;
  }

  async function downloadResult(filename) {
    if (!state.analysis) {
      toast("没有可导出的结果", "请先完成一次经营分析。", "error");
      return;
    }
    if (mappingConfirmationRequired(state.analysis) || !state.analysis.capabilities.hasRevenue) {
      toast("暂不可导出", "请先确认收入口径，避免把未确认或部分来源的金额写入报告。", "error");
      return;
    }
    if (!state.analysis?.id) return;
    const type = filename.endsWith("xlsx") ? "xlsx" : "docx";
    const suggestedName = `${safeFilename(el.projectNameLabel?.textContent || "DataMaster")}-${type === "xlsx" ? "分析底稿" : "经营分析报告"}.${type}`;
    try {
      if (window.dataMasterDesktop?.saveExport) {
        const response = await fetch(`${API_BASE}/api/analysis/${encodeURIComponent(state.analysis.id)}/${filename}${periodQueryString()}`);
        if (!response.ok) throw new Error(`导出失败（${response.status}）`);
        const result = await window.dataMasterDesktop.saveExport({ type, suggestedName, bytes: new Uint8Array(await response.arrayBuffer()) });
        if (result?.canceled) return;
        if (result?.success === false) throw new Error(result.message || "本地文件写入失败。");
        toast("导出完成", `${type === "xlsx" ? "Excel 分析底稿" : "Word 经营报告"}已保存。`, "success");
        return;
      }
      const anchor = document.createElement("a");
      anchor.href = `${API_BASE}/api/analysis/${encodeURIComponent(state.analysis.id)}/${filename}${periodQueryString()}`;
      anchor.download = suggestedName;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      toast("正在导出", type === "xlsx" ? "Excel 分析表即将下载。" : "Word 经营报告即将下载。", "success");
    } catch (error) {
      toast("导出失败", friendlyError(error), "error");
    }
  }

  function openPdfExport() {
    if (!state.analysis || mappingConfirmationRequired(state.analysis) || !state.analysis.capabilities.hasRevenue) {
      toast("暂不可导出 PDF", "请先导入数据并确认收入口径。", "error");
      return;
    }
    el.pdfExportForm?.querySelectorAll("[name='pdfScope']").forEach((checkbox) => { checkbox.checked = state.pdfScopes.includes(checkbox.value); });
    updatePdfExportSummary();
    showModal(el.pdfExportDialog);
    window.setTimeout(() => el.pdfExportDialog?.querySelector("[data-pdf-preset='all']")?.focus(), 40);
  }

  function closePdfExport() {
    closeModal(el.pdfExportDialog);
    (document.querySelector("[data-action='export-pdf']") || el.downloadPdf)?.focus?.();
  }

  function applyPdfPreset(preset) {
    const current = state.activeWorkspace === "report"
      ? reportSectionToPdfScopes(state.reportSection)
      : state.activeWorkspace === "data" || state.activeWorkspace === "home"
        ? ["overview"]
        : [VIEW_META[state.activeView] ? state.activeView : "overview"];
    el.pdfExportForm?.querySelectorAll("[name='pdfScope']").forEach((checkbox) => {
      checkbox.checked = preset === "all" ? true : preset === "current" ? current.includes(checkbox.value) : false;
    });
    el.pdfExportDialog?.querySelectorAll("[data-pdf-preset]").forEach((button) => button.classList.toggle("active", button.dataset.pdfPreset === preset));
    updatePdfExportSummary();
  }

  function reportSectionToPdfScopes(section) {
    if (section === "portfolio") return ["product", "customer"];
    if (section === "organization") return ["salesGroup"];
    if (section === "actions") return ["profit"];
    if (section === "evidence") return ["evidence"];
    return ["overview"];
  }

  function selectedPdfScopes() {
    return Array.from(el.pdfExportForm?.querySelectorAll("[name='pdfScope']:checked") || []).map((item) => item.value);
  }

  function updatePdfExportSummary() {
    const scopes = selectedPdfScopes();
    if (el.pdfExportSummary) el.pdfExportSummary.textContent = scopes.length ? `将导出 ${scopes.length} 个业务章节 · 详细版 PDF` : "请至少选择一个报告章节";
    if (el.confirmPdfExport) el.confirmPdfExport.disabled = !scopes.length;
    if (scopes.length !== 6) el.pdfExportDialog?.querySelector("[data-pdf-preset='all']")?.classList.remove("active");
  }

  async function exportPdf() {
    const scopes = selectedPdfScopes();
    if (!scopes.length || !state.analysis?.id) return;
    state.pdfScopes = scopes;
    const sections = [...new Set(scopes.flatMap((scope) => PDF_SCOPE_SECTIONS[scope] || []))];
    const button = el.confirmPdfExport;
    setButtonBusy(button, true, "正在生成 PDF…");
    try {
      const response = await fetch(`${API_BASE}/api/analysis/${encodeURIComponent(state.analysis.id)}/report.pdf`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          title: `${el.projectNameLabel?.textContent || "DataMaster"}经营分析报告`,
          sections,
          topN: 20,
          ...(IS_DESKTOP ? periodRequestPayload() : {})
        })
      });
      if (!response.ok) {
        let message = `PDF 生成失败（${response.status}）`;
        const fallbackResponse = response.clone();
        try {
          const payload = await response.json();
          message = payload.message || payload.error || message;
        } catch (_) {
          const text = await fallbackResponse.text();
          if (text) message = text.slice(0, 180);
        }
        throw new Error(message);
      }
      const blob = await response.blob();
      if (!blob.size) throw new Error("服务返回了空的 PDF 文件。");
      const suggestedName = `${safeFilename(el.projectNameLabel?.textContent || "DataMaster")}-经营分析报告.pdf`;
      if (window.dataMasterDesktop?.saveExport) {
        const result = await window.dataMasterDesktop.saveExport({ type: "pdf", suggestedName, bytes: new Uint8Array(await blob.arrayBuffer()) });
        if (result?.canceled) return;
        if (result?.success === false) throw new Error(result.message || "本地 PDF 写入失败。");
      } else {
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement("a");
        anchor.href = url;
        anchor.download = suggestedName;
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      }
      closePdfExport();
      toast("PDF 报告已生成", `已包含 ${scopes.length} 个业务章节。`, "success");
    } catch (error) {
      toast("PDF 导出失败", friendlyError(error), "error");
    } finally {
      button.disabled = false;
      button.innerHTML = `<svg><use href="#i-download"></use></svg><span>生成 PDF</span>`;
      updatePdfExportSummary();
    }
  }

  function safeFilename(value) {
    return String(value || "DataMaster").replace(/[\\/:*?"<>|]+/g, "-").replace(/\s+/g, " ").trim() || "DataMaster";
  }

  function showModal(dialog) {
    if (typeof dialog.showModal === "function") dialog.showModal();
    else dialog.setAttribute("open", "");
  }

  function closeModal(dialog) {
    if (dialog.open && typeof dialog.close === "function") dialog.close();
    else dialog.removeAttribute("open");
  }

  function sortedByRevenue(list) {
    return [...list].sort((a, b) => b.revenue - a.revenue);
  }

  function qualityScore(quality) {
    if (!quality.totalRows) return 0;
    let score = quality.validRows / quality.totalRows * 100;
    const analysis = state.analysis;
    if (analysis?.capabilities?.grossProfitAvailable && analysis.capabilities.grossProfitRevenueCoverage !== null) {
      score = Math.min(score, analysis.capabilities.grossProfitRevenueCoverage * 100);
    } else if (analysis?.capabilities?.hasRevenue && analysis.capabilities.revenueCoverage !== null) {
      score = Math.min(score, analysis.capabilities.revenueCoverage * 100);
    }
    if (analysis?.profile?.mappingIssues?.some((issue) => issue.severity === "BLOCKING")) score = Math.min(score, 70);
    if (quality.invalidNumericCells > 0) score -= Math.min(8, quality.invalidNumericCells / quality.totalRows * 100);
    return Math.max(0, Math.min(100, Math.round(score)));
  }

  function qualityVerdict(quality, analysis = state.analysis) {
    const score = qualityScore(quality);
    if (analysis && mappingConfirmationRequired(analysis)) {
      return { title: "关键口径待确认", detail: "先确认收入等业务字段，再使用金额和利润结论。", tone: "blocking" };
    }
    const formulaRisks = numberValue(quality.formulaErrors, 0);
    const externalLinks = numberValue(quality.externalWorkbookLinks, 0);
    const numericRisks = numberValue(quality.invalidNumericCells, 0);
    if (formulaRisks || externalLinks || numericRisks) {
      return { title: "可分析，但需复核风险", detail: "公式错误、外链或异常数值已隔离标记，不会静默按 0 计算。", tone: "review" };
    }
    if (score >= 95) return { title: "结构与口径可用", detail: "当前能力已通过基础完整性与可比覆盖检查。", tone: "verified" };
    if (score >= 85) return { title: "可分析，建议补齐缺失项", detail: "结果可用于初步判断，关键决策前建议处理提示项。", tone: "review" };
    return { title: "建议先清洗再做决策", detail: "当前完整性或可比覆盖不足，部分结果可能不可用。", tone: "blocking" };
  }

  function coverageLabel(value) {
    return value === null || value === undefined ? "待确认" : formatPercent(value);
  }

  function comparableCoverageText(capabilities, type = "gross") {
    const operating = type === "operating";
    const coverage = operating ? capabilities.operatingProfitRevenueCoverage : capabilities.grossProfitRevenueCoverage;
    const rows = operating ? capabilities.operatingProfitExcludedRows : capabilities.grossProfitExcludedRows;
    const revenue = operating ? capabilities.operatingProfitExcludedRevenue : capabilities.grossProfitExcludedRevenue;
    return `仅按相关指标同时有效的可比行计算；毛利可算收入覆盖率 ${coverageLabel(coverage)}，排除 ${formatInteger(rows)} 行、涉及收入 ${formatCurrency(revenue)}。100% 只表示当前成本口径完整，不代表毛利率 100%`;
  }

  function firstDefined(...values) {
    return values.find((value) => value !== undefined && value !== null);
  }

  function hasAnyKey(input, keys) {
    if (!input || typeof input !== "object") return false;
    return keys.some((key) => Object.prototype.hasOwnProperty.call(input, key)
      && input[key] !== undefined && input[key] !== null && input[key] !== "");
  }

  function nullableNumber(value) {
    if (value === undefined || value === null || value === "") return null;
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
  }

  function nullableRatio(value) {
    const number = nullableNumber(value);
    if (number === null) return null;
    return Math.abs(number) > 1.5 ? number / 100 : number;
  }

  function numberValue(value, fallback = 0) {
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  }

  function ratioValue(value, fallback = 0) {
    const number = Number(value);
    if (!Number.isFinite(number)) return fallback;
    return Math.abs(number) > 1.5 ? number / 100 : number;
  }

  function formatCurrency(value) {
    const number = numberValue(value);
    const sign = number < 0 ? "−" : "";
    const absolute = Math.abs(number);
    if (absolute >= 100000000) return `${sign}¥${(absolute / 100000000).toFixed(2)}亿`;
    if (absolute >= 10000) return `${sign}¥${(absolute / 10000).toFixed(2)}万`;
    return `${sign}¥${absolute.toLocaleString("zh-CN", { maximumFractionDigits: 0 })}`;
  }

  function formatCurrencyExact(value) {
    const number = numberValue(value);
    const sign = number < 0 ? "−" : "";
    return `${sign}¥${Math.abs(number).toLocaleString("zh-CN", { maximumFractionDigits: 2 })}`;
  }

  function formatPercent(value) {
    return `${(numberValue(value) * 100).toFixed(1)}%`;
  }

  function formatSignedPercent(value) {
    const number = numberValue(value);
    return `${number > 0 ? "+" : number < 0 ? "−" : ""}${Math.abs(number * 100).toFixed(1)}%`;
  }

  function formatInteger(value) {
    return Math.round(numberValue(value)).toLocaleString("zh-CN");
  }

  function formatQuantity(value) {
    const number = nullableNumber(value);
    if (number === null) return "—";
    return number.toLocaleString("zh-CN", { maximumFractionDigits: 2 });
  }

  function formatBytes(value) {
    const bytes = Math.max(0, numberValue(value));
    if (bytes >= 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
    if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(bytes >= 100 * 1024 * 1024 ? 0 : 1)} MB`;
    if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${Math.round(bytes)} B`;
  }

  function formatNumber(value, digits = 0) {
    return numberValue(value).toLocaleString("zh-CN", { minimumFractionDigits: digits, maximumFractionDigits: digits });
  }

  function shortAxis(value) {
    const abs = Math.abs(value);
    const sign = value < 0 ? "−" : "";
    if (abs >= 100000000) return `${sign}${(abs / 100000000).toFixed(1)}亿`;
    if (abs >= 10000) return `${sign}${Math.round(abs / 10000)}万`;
    return `${sign}${Math.round(abs)}`;
  }

  function displayMonth(value) {
    const match = String(value).match(/(\d{4})[-/.年](\d{1,2})/);
    return match ? `${Number(match[2])}月` : String(value);
  }

  function formatDateTime(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "刚刚";
    return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(date).replace("/", "-");
  }

  function truncateNames(names) {
    if (!names.length) return "本地工作簿";
    const first = names[0];
    return names.length === 1 ? first : `${first}，以及另外 ${names.length - 1} 个文件`;
  }

  function providerName(id) {
    return state.providers.find((item) => item.id === id)?.name || id;
  }

  function friendlyError(error) {
    if (!error) return "未知错误";
    if (error.name === "AbortError") return "请求超时，请检查本地服务或网络连接。";
    if (/Failed to fetch|NetworkError|fetch/i.test(error.message || "")) return "无法连接本地 DataMaster 服务。";
    return String(error.message || error).slice(0, 180);
  }

  function cloneData(value) {
    return typeof structuredClone === "function" ? structuredClone(value) : JSON.parse(JSON.stringify(value));
  }

  function pad(value) {
    return String(value).padStart(3, "0");
  }

  function esc(value) {
    return String(value ?? "").replace(/[&<>'"]/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" }[char]));
  }

  function attr(value) {
    return esc(value).replace(/`/g, "&#96;");
  }
})();
