const state = {
  metricsTimer: null,
  globalHeaders: {
    consoleToken: "",
    authorization: "",
    apiKey: "",
    userId: "",
    consumer: "",
  },
  lastFailedRequest: null,
  auditLogs: [],
  unlocked: false,
  currentRole: "unknown",
  modelsAll: [],
};

const ACCESS_SESSION_KEY = "gateway-console-unlocked";

function nowText() {
  return new Date().toLocaleString();
}

function logAudit(action, detail = "", ok = true) {
  const item = {
    ts: Date.now(),
    action,
    detail,
    ok,
  };
  state.auditLogs.unshift(item);
  if (state.auditLogs.length > 300) {
    state.auditLogs.length = 300;
  }
  renderAuditLogs();
}

function renderAuditLogs() {
  const listEl = document.getElementById("audit-list");
  const statusEl = document.getElementById("audit-status");
  if (!listEl || !statusEl) return;
  if (!state.auditLogs.length) {
    listEl.innerHTML = "";
    setStatus(statusEl, "暂无日志", "");
    return;
  }
  const fragment = document.createDocumentFragment();
  state.auditLogs.forEach((entry) => {
    const item = document.createElement("article");
    item.className = "audit-item";
    item.innerHTML = `
      <div class="meta">${new Date(entry.ts).toLocaleString()} · ${entry.ok ? "成功" : "失败"} · ${entry.action}</div>
      <div class="text">${entry.detail || "-"}</div>
    `;
    fragment.appendChild(item);
  });
  listEl.innerHTML = "";
  listEl.appendChild(fragment);
  setStatus(statusEl, `共 ${state.auditLogs.length} 条`, "ok");
}

function initAccessGate() {
  const overlay = document.getElementById("access-gate");
  const unlocked = sessionStorage.getItem(ACCESS_SESSION_KEY) === "1";
  state.unlocked = unlocked;
  overlay.hidden = unlocked;
  setStatus(document.getElementById("access-status"), unlocked ? "已登录" : "未登录", unlocked ? "ok" : "warn");
}

async function unlockAccessGate() {
  const usernameEl = document.getElementById("access-username");
  const passwordEl = document.getElementById("access-password");
  const statusEl = document.getElementById("access-status");
  const username = usernameEl.value.trim();
  const password = passwordEl.value.trim();
  if (!username || !password) {
    setStatus(statusEl, "请输入用户名与密码", "error");
    return;
  }

  try {
    const result = await requestJson("/v1/security/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
    if (!result.success) {
      setStatus(statusEl, result.message || "登录失败", "error");
      logAudit("GATE_UNLOCK", result.message || "登录失败", false);
      return;
    }

    state.globalHeaders.consoleToken = result.token || "";
    state.currentRole = result.role || "unknown";
    document.getElementById("global-console-token").value = result.token || "";
    updateRoleStatus();
    saveGlobalHeaders();

    sessionStorage.setItem(ACCESS_SESSION_KEY, "1");
    state.unlocked = true;
    document.getElementById("access-gate").hidden = true;
    setStatus(statusEl, `已登录（${state.currentRole}）`, "ok");
    logAudit("GATE_UNLOCK", `登录成功，用户=${username} 角色=${state.currentRole}`, true);
  } catch (error) {
    setStatus(statusEl, `登录失败：${error.message}`, "error");
    logAudit("GATE_UNLOCK", `登录失败：${error.message}`, false);
  }
}

function bindAccessGate() {
  document.getElementById("access-unlock").addEventListener("click", unlockAccessGate);
  document.getElementById("access-username").addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      unlockAccessGate();
    }
  });
  document.getElementById("access-password").addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      unlockAccessGate();
    }
  });
}

function buildHeaders(extra = {}) {
  const headers = {
    ...(extra || {}),
  };
  if (state.globalHeaders.consoleToken) headers["X-Console-Token"] = state.globalHeaders.consoleToken;
  if (state.globalHeaders.authorization) headers.Authorization = state.globalHeaders.authorization;
  if (state.globalHeaders.apiKey) headers["x-api-key"] = state.globalHeaders.apiKey;
  if (state.globalHeaders.userId) headers.userId = state.globalHeaders.userId;
  if (state.globalHeaders.consumer) headers["X-Consumer"] = state.globalHeaders.consumer;
  return headers;
}

function setStatus(el, text, type = "") {
  el.textContent = text;
  el.classList.remove("ok", "warn", "error");
  if (type) el.classList.add(type);
}

function setOutput(text, isJson = false) {
  const output = document.getElementById("chat-output");
  output.classList.remove("empty");
  output.textContent = isJson ? JSON.stringify(text, null, 2) : text;
}

function appendOutput(text) {
  const output = document.getElementById("chat-output");
  output.classList.remove("empty");
  output.textContent += text;
}

function clearOutput() {
  const output = document.getElementById("chat-output");
  output.classList.add("empty");
  output.textContent = "等待请求...";
}

function switchView(view) {
  document.querySelectorAll(".nav-btn").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.view === view);
  });
  document.querySelectorAll(".panel").forEach((panel) => {
    panel.classList.toggle("active", panel.id === `view-${view}`);
  });
}

async function requestJson(url, init = {}) {
  const mergedHeaders = buildHeaders(init.headers || {});
  const response = await fetch(url, {
    headers: {
      ...(init.body ? { "Content-Type": "application/json" } : {}),
      ...mergedHeaders,
    },
    ...init,
  });

  if (response.status === 401 || response.status === 403) {
    sessionStorage.removeItem(ACCESS_SESSION_KEY);
    state.unlocked = false;
    document.getElementById("access-gate").hidden = false;
    updateRoleStatus();
  }

  if (!response.ok) {
    const errText = await response.text();
    state.lastFailedRequest = {
      url,
      init,
      mode: "json",
    };
    showGlobalError(`HTTP ${response.status}\n${errText}`);
    logAudit("HTTP_REQUEST", `${url} -> ${response.status}\n${errText}`, false);
    throw new Error(`HTTP ${response.status}: ${errText}`);
  }

  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    logAudit("HTTP_REQUEST", `${url} -> 200`, true);
    return response.json();
  }
  logAudit("HTTP_REQUEST", `${url} -> 200`, true);
  return response.text();
}

function showGlobalError(text) {
  const toast = document.getElementById("global-error-toast");
  const textEl = document.getElementById("global-error-text");
  textEl.textContent = text;
  toast.hidden = false;
}

function hideGlobalError() {
  const toast = document.getElementById("global-error-toast");
  toast.hidden = true;
}

function saveGlobalHeaders() {
  state.globalHeaders = {
    consoleToken: document.getElementById("global-console-token").value.trim(),
    authorization: document.getElementById("global-auth").value.trim(),
    apiKey: document.getElementById("global-api-key").value.trim(),
    userId: document.getElementById("global-userid").value.trim(),
    consumer: document.getElementById("global-consumer").value.trim(),
  };
  localStorage.setItem("gateway-console-global-headers", JSON.stringify(state.globalHeaders));
  setStatus(document.getElementById("global-status"), "已保存", "ok");
  updateRoleStatus();
  logAudit("GLOBAL_HEADERS_SAVE", "已更新全局请求头", true);
}

function loadGlobalHeaders() {
  try {
    const raw = localStorage.getItem("gateway-console-global-headers");
    if (!raw) return;
    const parsed = JSON.parse(raw);
    state.globalHeaders = {
      consoleToken: parsed.consoleToken || "",
      authorization: parsed.authorization || "",
      apiKey: parsed.apiKey || "",
      userId: parsed.userId || "",
      consumer: parsed.consumer || "",
    };
    document.getElementById("global-console-token").value = state.globalHeaders.consoleToken;
    document.getElementById("global-auth").value = state.globalHeaders.authorization;
    document.getElementById("global-api-key").value = state.globalHeaders.apiKey;
    document.getElementById("global-userid").value = state.globalHeaders.userId;
    document.getElementById("global-consumer").value = state.globalHeaders.consumer;
    updateRoleStatus();
    setStatus(document.getElementById("global-status"), "已加载", "ok");
  } catch {
    setStatus(document.getElementById("global-status"), "加载失败", "error");
  }
}

function clearGlobalHeaders() {
  state.globalHeaders = { consoleToken: "", authorization: "", apiKey: "", userId: "", consumer: "" };
  state.currentRole = "unknown";
  document.getElementById("global-console-token").value = "";
  document.getElementById("global-auth").value = "";
  document.getElementById("global-api-key").value = "";
  document.getElementById("global-userid").value = "";
  document.getElementById("global-consumer").value = "";
  localStorage.removeItem("gateway-console-global-headers");
  setStatus(document.getElementById("global-status"), "已清空", "warn");
  updateRoleStatus();
  logAudit("GLOBAL_HEADERS_CLEAR", "已清空全局请求头", true);
}

async function logoutConsole() {
  const statusEl = document.getElementById("global-status");
  if (!state.globalHeaders.consoleToken) {
    setStatus(statusEl, "尚未登录", "warn");
    return;
  }
  try {
    await requestJson("/v1/security/logout", { method: "POST" });
    sessionStorage.removeItem(ACCESS_SESSION_KEY);
    state.unlocked = false;
    document.getElementById("access-gate").hidden = false;
    clearGlobalHeaders();
    setStatus(statusEl, "已退出登录", "ok");
    logAudit("LOGOUT", "已退出登录", true);
  } catch (error) {
    setStatus(statusEl, `退出失败：${error.message}`, "error");
  }
}

function updateRoleStatus() {
  const roleEl = document.getElementById("global-role");
  if (!roleEl) return;
  if (!state.globalHeaders.consoleToken) {
    roleEl.textContent = "角色：未登录";
    roleEl.classList.remove("ok");
    return;
  }
  roleEl.textContent = `角色：${state.currentRole || "unknown"}`;
  roleEl.classList.add("ok");
}

async function retryLastFailedRequest() {
  if (!state.lastFailedRequest) {
    setStatus(document.getElementById("global-status"), "无可重试请求", "warn");
    return;
  }
  const { url, init, mode } = state.lastFailedRequest;
  try {
    if (mode === "json") {
      await requestJson(url, init);
    } else {
      await fetch(url, init);
    }
    hideGlobalError();
    setStatus(document.getElementById("global-status"), "重试成功", "ok");
  } catch (error) {
    showGlobalError(`重试失败\n${error.message}`);
  }
}

function bindGlobalUi() {
  document.getElementById("global-save").addEventListener("click", saveGlobalHeaders);
  document.getElementById("global-clear").addEventListener("click", clearGlobalHeaders);
  document.getElementById("global-logout").addEventListener("click", logoutConsole);
  document.getElementById("global-error-close").addEventListener("click", hideGlobalError);
  document.getElementById("global-error-retry").addEventListener("click", retryLastFailedRequest);
}

function bindAuditActions() {
  document.getElementById("audit-clear").addEventListener("click", () => {
    state.auditLogs = [];
    renderAuditLogs();
    logAudit("AUDIT_CLEAR", "已清空审计日志", true);
  });

  document.getElementById("audit-export").addEventListener("click", () => {
    const blob = new Blob([JSON.stringify(state.auditLogs, null, 2)], { type: "application/json" });
    const href = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = href;
    link.download = `gateway-audit-${Date.now()}.json`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(href);
    logAudit("AUDIT_EXPORT", "导出审计日志 JSON", true);
  });

  document.getElementById("audit-server-load").addEventListener("click", loadServerAuditLogs);
  document.getElementById("audit-server-clear").addEventListener("click", clearServerAuditLogs);
}

async function loadServerAuditLogs() {
  const statusEl = document.getElementById("audit-status");
  setStatus(statusEl, "加载服务端审计中...", "warn");
  try {
    const data = await requestJson("/v1/audit/logs?limit=200");
    const items = Array.isArray(data.items) ? data.items : [];
    items.reverse().forEach((entry) => {
      state.auditLogs.unshift({
        ts: entry.timestamp ? new Date(entry.timestamp).getTime() : Date.now(),
        action: `[server] ${entry.action || "-"}`,
        detail: `${entry.target || ""} | ${entry.detail || ""}`,
        ok: entry.success !== false,
      });
    });
    if (state.auditLogs.length > 300) {
      state.auditLogs = state.auditLogs.slice(0, 300);
    }
    renderAuditLogs();
    setStatus(statusEl, `已加载服务端日志 ${items.length} 条`, "ok");
  } catch (error) {
    setStatus(statusEl, `加载失败：${error.message}`, "error");
  }
}

async function clearServerAuditLogs() {
  const statusEl = document.getElementById("audit-status");
  setStatus(statusEl, "清空服务端审计中...", "warn");
  try {
    await requestJson("/v1/audit/logs/clear", { method: "POST" });
    setStatus(statusEl, "服务端审计已清空", "ok");
    logAudit("AUDIT_SERVER_CLEAR", "服务端审计已清空", true);
  } catch (error) {
    setStatus(statusEl, `清空失败：${error.message}`, "error");
  }
}

function parseMessages(raw) {
  const parsed = JSON.parse(raw);
  if (!Array.isArray(parsed) || parsed.length === 0) {
    throw new Error("messages 必须是非空数组");
  }
  return parsed;
}

async function submitChat(event) {
  event.preventDefault();
  const statusEl = document.getElementById("chat-status");
  const errEl = document.getElementById("chat-form-error");
  errEl.textContent = "";

  const model = document.getElementById("chat-model").value.trim();
  const stream = document.getElementById("chat-stream").checked;
  const temperatureText = document.getElementById("chat-temperature").value.trim();
  const maxTokensText = document.getElementById("chat-max-tokens").value.trim();
  const messagesText = document.getElementById("chat-messages").value;

  if (!model) {
    errEl.textContent = "model 不能为空";
    return;
  }

  let messages;
  try {
    messages = parseMessages(messagesText);
  } catch (error) {
    errEl.textContent = `messages JSON 非法：${error.message}`;
    return;
  }

  const body = {
    model,
    messages,
    stream,
  };

  if (temperatureText) body.temperature = Number(temperatureText);
  if (maxTokensText) body.max_tokens = Number(maxTokensText);

  setStatus(statusEl, "请求中...", "warn");
  setOutput("请求中，请稍候...");

  try {
    if (stream) {
      const response = await fetch("/v1/chat/completions", {
        method: "POST",
        headers: buildHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify(body),
      });

      if (!response.ok) {
        const errText = await response.text();
        state.lastFailedRequest = {
          url: "/v1/chat/completions",
          init: {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
          },
          mode: "raw",
        };
        showGlobalError(`HTTP ${response.status}\n${errText}`);
        logAudit("CHAT_STREAM", `stream 请求失败 ${response.status}\n${errText}`, false);
        throw new Error(`HTTP ${response.status}: ${errText}`);
      }
      if (!response.body) {
        throw new Error("浏览器不支持流式读取");
      }

      setOutput("");
      const reader = response.body.getReader();
      const decoder = new TextDecoder("utf-8");

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        appendOutput(decoder.decode(value, { stream: true }));
      }
      appendOutput("\n\n[stream end]");
      setStatus(statusEl, "流式响应完成", "ok");
      logAudit("CHAT_STREAM", "stream 请求完成", true);
    } else {
      const result = await requestJson("/v1/chat/completions", {
        method: "POST",
        body: JSON.stringify(body),
      });
      setOutput(result, typeof result !== "string");
      setStatus(statusEl, "请求成功", "ok");
      logAudit("CHAT_REQUEST", "non-stream 请求成功", true);
    }
  } catch (error) {
    setOutput(String(error));
    setStatus(statusEl, "请求失败", "error");
  }
}

function bindChatActions() {
  const form = document.getElementById("chat-form");
  form.addEventListener("submit", submitChat);

  document.getElementById("chat-fill-example").addEventListener("click", () => {
    document.getElementById("chat-model").value = "gpt-4o-mini-compatible";
    document.getElementById("chat-temperature").value = "0.7";
    document.getElementById("chat-max-tokens").value = "256";
    document.getElementById("chat-stream").checked = false;
    document.getElementById("chat-messages").value = JSON.stringify([
      { role: "system", content: "你是一个 API 网关助手" },
      { role: "user", content: "请输出 3 条 API 网关监控建议" },
    ], null, 2);
  });

  document.getElementById("chat-clear").addEventListener("click", () => {
    clearOutput();
    setStatus(document.getElementById("chat-status"), "已清空", "");
  });

  document.getElementById("chat-copy").addEventListener("click", async () => {
    const text = document.getElementById("chat-output").textContent;
    try {
      await navigator.clipboard.writeText(text);
      setStatus(document.getElementById("chat-status"), "已复制到剪贴板", "ok");
    } catch {
      setStatus(document.getElementById("chat-status"), "复制失败，请手动复制", "error");
    }
  });
}

async function fetchProviderModels(event) {
  event.preventDefault();
  const statusEl = document.getElementById("models-status");
  const listEl = document.getElementById("models-list");
  const countEl = document.getElementById("models-count");

  const baseUrl = document.getElementById("models-base-url").value.trim();
  const apiKey = document.getElementById("models-api-key").value.trim();

  if (!baseUrl || !apiKey) {
    setStatus(statusEl, "请填写 API Base URL 与 API Key", "error");
    return;
  }

  listEl.innerHTML = "";
  countEl.textContent = "0 项";
  setStatus(statusEl, "查询中...", "warn");

  try {
    const data = await requestJson("/v1/providers/models", {
      method: "POST",
      body: JSON.stringify({ baseUrl, apiKey }),
    });

    const models = Array.isArray(data.models) ? data.models : [];
    state.modelsAll = models;
    renderModelList(models);
    setStatus(statusEl, models.length ? "模型加载成功" : (data.message || "查询完成，但无模型"), models.length ? "ok" : "warn");
  } catch (error) {
    setStatus(statusEl, `查询失败：${error.message}`, "error");
  }
}

function modelGroupName(modelId = "") {
  const lower = modelId.toLowerCase();
  if (lower.includes("gpt")) return "OpenAI GPT";
  if (lower.includes("claude")) return "Anthropic Claude";
  if (lower.includes("qwen")) return "Qwen";
  if (lower.includes("deepseek")) return "DeepSeek";
  return "Other";
}

function renderModelList(models) {
  const listEl = document.getElementById("models-list");
  const countEl = document.getElementById("models-count");
  listEl.innerHTML = "";
  if (!models.length) {
    listEl.innerHTML = "<div class=\"list-item\"><div>未返回模型</div></div>";
    countEl.textContent = "0 项";
    return;
  }

  const grouped = {};
  models.forEach((modelId) => {
    const key = modelGroupName(modelId);
    grouped[key] = grouped[key] || [];
    grouped[key].push(modelId);
  });

  Object.keys(grouped)
    .sort()
    .forEach((groupName) => {
      const groupTitle = document.createElement("div");
      groupTitle.className = "meta";
      groupTitle.textContent = `${groupName}（${grouped[groupName].length}）`;
      listEl.appendChild(groupTitle);

      grouped[groupName].forEach((modelId) => {
        const item = document.createElement("article");
        item.className = "list-item";
        item.innerHTML = `<div>${modelId}</div><button class=\"btn\" type=\"button\">使用</button>`;
        item.querySelector("button").addEventListener("click", () => {
          document.getElementById("chat-model").value = modelId;
          switchView("chat");
        });
        listEl.appendChild(item);
      });
    });

  countEl.textContent = `${models.length} 项`;
}

function filterModelsBySearch() {
  const keyword = document.getElementById("models-search").value.trim().toLowerCase();
  if (!keyword) {
    renderModelList(state.modelsAll || []);
    return;
  }
  const filtered = (state.modelsAll || []).filter((item) => item.toLowerCase().includes(keyword));
  renderModelList(filtered);
}

function bindModelsActions() {
  document.getElementById("models-form").addEventListener("submit", fetchProviderModels);
  document.getElementById("models-search").addEventListener("input", filterModelsBySearch);
}

async function refreshMetrics() {
  const model = document.getElementById("metrics-model").value.trim();
  const statusEl = document.getElementById("metrics-status");
  if (!model) {
    setStatus(statusEl, "请填写模型名", "error");
    return;
  }

  setStatus(statusEl, "查询中...", "warn");
  try {
    const data = await requestJson(`/v1/metrics/models/${encodeURIComponent(model)}`);
    document.getElementById("m-callCount").textContent = data.callCount ?? "--";
    document.getElementById("m-successRate").textContent =
      data.successRate == null ? "--" : `${(Number(data.successRate) * 100).toFixed(2)}%`;
    document.getElementById("m-p95").textContent =
      data.p95LatencyMillis == null ? "--" : `${data.p95LatencyMillis} ms`;
    document.getElementById("m-cost").textContent =
      data.totalCost == null ? "--" : `$${Number(data.totalCost).toFixed(4)}`;
    setStatus(statusEl, "查询成功", "ok");
  } catch (error) {
    setStatus(statusEl, `查询失败：${error.message}`, "error");
  }
}

function bindMetricsActions() {
  document.getElementById("metrics-refresh").addEventListener("click", refreshMetrics);

  document.getElementById("metrics-auto").addEventListener("change", (event) => {
    if (state.metricsTimer) {
      clearInterval(state.metricsTimer);
      state.metricsTimer = null;
    }

    if (event.target.checked) {
      refreshMetrics();
      state.metricsTimer = setInterval(refreshMetrics, 10000);
    }
  });
}

function fillRateLimitForm(data) {
  document.getElementById("ratelimit-enabled").checked = Boolean(data.enabled);
  document.getElementById("ratelimit-minute-quota").value = data.defaultTokenQuotaPerMinute ?? "";
  document.getElementById("ratelimit-day-quota").value = data.defaultTokenQuotaPerDay ?? "";
  document.getElementById("ratelimit-min-reserve").value = data.minTokenReserve ?? "";
  document.getElementById("ratelimit-dimensions").value = Array.isArray(data.keyDimensions)
    ? data.keyDimensions.join(",")
    : "";
}

async function loadRateLimitConfig() {
  const statusEl = document.getElementById("ratelimit-status");
  setStatus(statusEl, "加载中...", "warn");
  try {
    const data = await requestJson("/v1/rate-limit/config");
    fillRateLimitForm(data);
    setStatus(statusEl, "配置已加载", "ok");
  } catch (error) {
    setStatus(statusEl, `加载失败：${error.message}`, "error");
  }
}

async function submitRateLimitConfig(event) {
  event.preventDefault();
  const statusEl = document.getElementById("ratelimit-status");
  setStatus(statusEl, "保存中...", "warn");

  const body = {
    enabled: document.getElementById("ratelimit-enabled").checked,
    defaultTokenQuotaPerMinute: Number(document.getElementById("ratelimit-minute-quota").value || 0),
    defaultTokenQuotaPerDay: Number(document.getElementById("ratelimit-day-quota").value || 0),
    minTokenReserve: Number(document.getElementById("ratelimit-min-reserve").value || 0),
    keyDimensions: document.getElementById("ratelimit-dimensions").value
      .split(",")
      .map((item) => item.trim())
      .filter(Boolean),
  };

  try {
    const data = await requestJson("/v1/rate-limit/config", {
      method: "POST",
      body: JSON.stringify(body),
    });
    fillRateLimitForm(data);
    setStatus(statusEl, "保存成功", "ok");
  } catch (error) {
    setStatus(statusEl, `保存失败：${error.message}`, "error");
  }
}

function rateLimitQueryHeaders() {
  const headers = {};
  const userId = document.getElementById("ratelimit-userid").value.trim();
  const consumer = document.getElementById("ratelimit-consumer").value.trim();
  const forwarded = document.getElementById("ratelimit-forwarded").value.trim();
  const realIp = document.getElementById("ratelimit-realip").value.trim();
  if (userId) headers.userId = userId;
  if (consumer) headers["X-Consumer"] = consumer;
  if (forwarded) headers["X-Forwarded-For"] = forwarded;
  if (realIp) headers["X-Real-IP"] = realIp;
  return headers;
}

async function queryRateLimitUsage() {
  const statusEl = document.getElementById("ratelimit-usage-status");
  const provider = document.getElementById("ratelimit-provider").value.trim();
  const model = document.getElementById("ratelimit-model").value.trim();
  if (!provider || !model) {
    setStatus(statusEl, "请填写 provider 和 model", "error");
    return;
  }

  setStatus(statusEl, "查询中...", "warn");
  try {
    const data = await requestJson(`/v1/rate-limit/usage?provider=${encodeURIComponent(provider)}&model=${encodeURIComponent(model)}`, {
      headers: buildHeaders(rateLimitQueryHeaders()),
    });
    document.getElementById("rl-minute-used").textContent = data.minuteUsed ?? "--";
    document.getElementById("rl-minute-remaining").textContent = data.minuteRemaining ?? "--";
    document.getElementById("rl-day-used").textContent = data.dayUsed ?? "--";
    document.getElementById("rl-day-remaining").textContent = data.dayRemaining ?? "--";
    document.getElementById("ratelimit-quota-key").value = data.quotaKey || "";
    setStatus(statusEl, "查询成功", "ok");
  } catch (error) {
    setStatus(statusEl, `查询失败：${error.message}`, "error");
  }
}

function bindRateLimitActions() {
  document.getElementById("ratelimit-load").addEventListener("click", loadRateLimitConfig);
  document.getElementById("ratelimit-form").addEventListener("submit", submitRateLimitConfig);
  document.getElementById("ratelimit-usage").addEventListener("click", queryRateLimitUsage);
}

function fillRoutingForm(data) {
  document.getElementById("routing-default-provider").value = data.defaultProvider || "";
  document.getElementById("routing-fallback-enabled").checked = Boolean(data.fallbackEnabled);
  document.getElementById("routing-priority").value = Array.isArray(data.providerPriority)
    ? data.providerPriority.join(",")
    : "";
  document.getElementById("routing-baseurl").value = JSON.stringify(data.providerBaseUrl || {}, null, 2);
  document.getElementById("routing-alias").value = JSON.stringify(data.modelAlias || {}, null, 2);
}

async function loadRoutingConfig() {
  const statusEl = document.getElementById("routing-status");
  setStatus(statusEl, "加载中...", "warn");
  try {
    const data = await requestJson("/v1/routing/config");
    fillRoutingForm(data);
    setStatus(statusEl, "配置已加载", "ok");
  } catch (error) {
    setStatus(statusEl, `加载失败：${error.message}`, "error");
  }
}

async function submitRoutingConfig(event) {
  event.preventDefault();
  const statusEl = document.getElementById("routing-status");
  setStatus(statusEl, "保存中...", "warn");
  try {
    const providerBaseUrl = JSON.parse(document.getElementById("routing-baseurl").value || "{}");
    const modelAlias = JSON.parse(document.getElementById("routing-alias").value || "{}");
    const body = {
      defaultProvider: document.getElementById("routing-default-provider").value.trim(),
      fallbackEnabled: document.getElementById("routing-fallback-enabled").checked,
      providerPriority: document.getElementById("routing-priority").value
        .split(",")
        .map((item) => item.trim())
        .filter(Boolean),
      providerBaseUrl,
      modelAlias,
    };
    const data = await requestJson("/v1/routing/config", {
      method: "POST",
      body: JSON.stringify(body),
    });
    fillRoutingForm(data);
    setStatus(statusEl, "保存成功", "ok");
  } catch (error) {
    setStatus(statusEl, `保存失败：${error.message}`, "error");
  }
}

async function previewRouting() {
  const statusEl = document.getElementById("routing-preview-status");
  const model = document.getElementById("routing-preview-model").value.trim();
  if (!model) {
    setStatus(statusEl, "请填写预览模型", "error");
    return;
  }
  const provider = document.getElementById("routing-preview-provider").value.trim();
  const consumer = document.getElementById("routing-preview-consumer").value.trim();
  const headers = {};
  if (provider) headers["X-AI-Provider"] = provider;
  if (consumer) headers["X-Consumer"] = consumer;

  setStatus(statusEl, "预览中...", "warn");
  try {
    const data = await requestJson(`/v1/routing/preview?model=${encodeURIComponent(model)}`, { headers });
    document.getElementById("routing-preview-result").value = JSON.stringify(data, null, 2);
    setStatus(statusEl, "预览成功", "ok");
  } catch (error) {
    setStatus(statusEl, `预览失败：${error.message}`, "error");
  }
}

async function simulateRoutingAb() {
  const statusEl = document.getElementById("routing-sim-status");
  const model = document.getElementById("routing-preview-model").value.trim();
  const samples = Number(document.getElementById("routing-sim-samples").value || 200);
  if (!model) {
    setStatus(statusEl, "请填写预览模型", "error");
    return;
  }
  setStatus(statusEl, "仿真中...", "warn");
  try {
    const data = await requestJson(`/v1/routing/simulate?model=${encodeURIComponent(model)}&samples=${encodeURIComponent(samples)}`);
    document.getElementById("routing-sim-result").value = JSON.stringify(data, null, 2);
    setStatus(statusEl, "仿真完成", "ok");
  } catch (error) {
    setStatus(statusEl, `仿真失败：${error.message}`, "error");
  }
}

function formatJsonEditor(id, statusId) {
  const textarea = document.getElementById(id);
  const statusEl = document.getElementById(statusId);
  try {
    const parsed = JSON.parse(textarea.value || "{}");
    textarea.value = JSON.stringify(parsed, null, 2);
    if (statusEl) {
      setStatus(statusEl, "JSON 格式化成功", "ok");
    }
  } catch (error) {
    if (statusEl) {
      setStatus(statusEl, `JSON 格式错误：${error.message}`, "error");
    }
  }
}

function bindRoutingActions() {
  document.getElementById("routing-load").addEventListener("click", loadRoutingConfig);
  document.getElementById("routing-form").addEventListener("submit", submitRoutingConfig);
  document.getElementById("routing-preview").addEventListener("click", previewRouting);
  document.getElementById("routing-simulate").addEventListener("click", simulateRoutingAb);
  document.getElementById("routing-baseurl").addEventListener("blur", () => formatJsonEditor("routing-baseurl", "routing-status"));
  document.getElementById("routing-alias").addEventListener("blur", () => formatJsonEditor("routing-alias", "routing-status"));
}

function fillCacheConfig(data) {
  document.getElementById("cache-enabled").checked = Boolean(data.enabled);
  document.getElementById("cache-semantic-enabled").checked = Boolean(data.semanticCacheEnabled);
  document.getElementById("cache-ttl-seconds").value = data.ttlSeconds ?? 120;
}

async function loadCacheConfig() {
  const statusEl = document.getElementById("cache-status");
  setStatus(statusEl, "加载中...", "warn");
  try {
    const data = await requestJson("/v1/cache/config");
    fillCacheConfig(data);
    setStatus(statusEl, "缓存配置已加载", "ok");
  } catch (error) {
    setStatus(statusEl, `加载失败：${error.message}`, "error");
  }
}

async function submitCacheConfig(event) {
  event.preventDefault();
  const statusEl = document.getElementById("cache-status");
  setStatus(statusEl, "保存中...", "warn");
  const body = {
    enabled: document.getElementById("cache-enabled").checked,
    semanticCacheEnabled: document.getElementById("cache-semantic-enabled").checked,
    ttlSeconds: Number(document.getElementById("cache-ttl-seconds").value || 120),
  };
  try {
    const data = await requestJson("/v1/cache/config", {
      method: "POST",
      body: JSON.stringify(body),
    });
    fillCacheConfig(data);
    setStatus(statusEl, "缓存配置已保存", "ok");
  } catch (error) {
    setStatus(statusEl, `保存失败：${error.message}`, "error");
  }
}

function fillCacheStats(data) {
  document.getElementById("cache-hit").textContent = data.hit ?? 0;
  document.getElementById("cache-miss").textContent = data.miss ?? 0;
  document.getElementById("cache-sem-hit").textContent = data.semanticHit ?? 0;
  const rate = data.hitRate == null ? 0 : Number(data.hitRate) * 100;
  document.getElementById("cache-hit-rate").textContent = `${rate.toFixed(2)}%`;
}

async function refreshCacheStats() {
  const statusEl = document.getElementById("cache-stats-status");
  setStatus(statusEl, "查询中...", "warn");
  try {
    const data = await requestJson("/v1/cache/stats");
    fillCacheStats(data);
    setStatus(statusEl, "统计已更新", "ok");
  } catch (error) {
    setStatus(statusEl, `查询失败：${error.message}`, "error");
  }
}

async function resetCacheStats() {
  const statusEl = document.getElementById("cache-stats-status");
  setStatus(statusEl, "重置中...", "warn");
  try {
    const data = await requestJson("/v1/cache/stats/reset", { method: "POST" });
    fillCacheStats(data);
    setStatus(statusEl, "统计已重置", "ok");
  } catch (error) {
    setStatus(statusEl, `重置失败：${error.message}`, "error");
  }
}

async function refreshCacheTrend() {
  const statusEl = document.getElementById("cache-stats-status");
  const minutes = Number(document.getElementById("cache-trend-minutes").value || 60);
  setStatus(statusEl, "趋势查询中...", "warn");
  try {
    const data = await requestJson(`/v1/cache/stats/trend?minutes=${encodeURIComponent(minutes)}`);
    document.getElementById("cache-trend-result").value = JSON.stringify(data, null, 2);
    setStatus(statusEl, "趋势已更新", "ok");
  } catch (error) {
    setStatus(statusEl, `趋势查询失败：${error.message}`, "error");
  }
}

function fillAbConfig(data) {
  document.getElementById("ab-enabled").checked = Boolean(data.abEnabled);
  document.getElementById("ab-provider").value = data.abProvider || "";
  document.getElementById("ab-percentage").value = data.abPercentage ?? 0;
}

async function loadAbConfig() {
  const statusEl = document.getElementById("ab-status");
  setStatus(statusEl, "加载中...", "warn");
  try {
    const data = await requestJson("/v1/routing/config");
    fillAbConfig(data);
    setStatus(statusEl, "灰度配置已加载", "ok");
  } catch (error) {
    setStatus(statusEl, `加载失败：${error.message}`, "error");
  }
}

async function submitAbConfig(event) {
  event.preventDefault();
  const statusEl = document.getElementById("ab-status");
  setStatus(statusEl, "保存中...", "warn");
  const body = {
    abEnabled: document.getElementById("ab-enabled").checked,
    abProvider: document.getElementById("ab-provider").value.trim(),
    abPercentage: Number(document.getElementById("ab-percentage").value || 0),
  };
  try {
    await requestJson("/v1/routing/config", {
      method: "POST",
      body: JSON.stringify(body),
    });
    await loadAbConfig();
    setStatus(statusEl, "灰度配置已保存", "ok");
  } catch (error) {
    setStatus(statusEl, `保存失败：${error.message}`, "error");
  }
}

async function previewAbRouting() {
  const statusEl = document.getElementById("ab-preview-status");
  const model = document.getElementById("ab-preview-model").value.trim();
  if (!model) {
    setStatus(statusEl, "请填写模型名", "error");
    return;
  }
  const userId = document.getElementById("ab-preview-userid").value.trim();
  const headers = {};
  if (userId) headers.userId = userId;
  setStatus(statusEl, "预览中...", "warn");
  try {
    const data = await requestJson(`/v1/routing/preview?model=${encodeURIComponent(model)}`, { headers });
    document.getElementById("ab-preview-result").value = JSON.stringify(data, null, 2);
    setStatus(statusEl, "预览成功", "ok");
  } catch (error) {
    setStatus(statusEl, `预览失败：${error.message}`, "error");
  }
}

function bindCacheAbActions() {
  document.getElementById("cache-load").addEventListener("click", loadCacheConfig);
  document.getElementById("cache-form").addEventListener("submit", submitCacheConfig);
  document.getElementById("cache-stats-refresh").addEventListener("click", refreshCacheStats);
  document.getElementById("cache-stats-reset").addEventListener("click", resetCacheStats);
  document.getElementById("cache-trend-refresh").addEventListener("click", refreshCacheTrend);

  document.getElementById("ab-load").addEventListener("click", loadAbConfig);
  document.getElementById("ab-form").addEventListener("submit", submitAbConfig);
  document.getElementById("ab-preview").addEventListener("click", previewAbRouting);
}

function canWrite() {
  return state.currentRole === "admin";
}

function ensureWriteAccess(statusEl, actionText) {
  if (canWrite()) {
    return true;
  }
  if (statusEl) {
    setStatus(statusEl, `当前角色(${state.currentRole || "unknown"})无权限执行：${actionText}`, "error");
  }
  logAudit("RBAC_BLOCK", `${actionText} 被拒绝`, false);
  return false;
}

async function loadPlugins() {
  const listEl = document.getElementById("plugin-list");
  const statusEl = document.getElementById("plugins-status");
  setStatus(statusEl, "加载中...", "warn");

  try {
    const data = await requestJson("/v1/plugins/config");
    const map = data.pluginEnabledMap || {};
    listEl.innerHTML = "";

    const entries = Object.entries(map);
    if (!entries.length) {
      listEl.innerHTML = "<div class=\"list-item\"><div>暂无插件配置</div></div>";
      setStatus(statusEl, "无插件项", "warn");
      return;
    }

    entries.forEach(([name, enabled]) => {
      const item = document.createElement("article");
      item.className = "list-item";
      item.innerHTML = `
        <div>
          <div>${name}</div>
          <div class="meta">状态：${enabled ? "已启用" : "已禁用"}</div>
        </div>
        <button class="btn ${enabled ? "" : "primary"}" data-plugin-name="${name}" data-plugin-enabled="${enabled}">
          ${enabled ? "禁用" : "启用"}
        </button>
      `;

      item.querySelector("button").addEventListener("click", () => togglePlugin(name, !enabled));
      listEl.appendChild(item);
    });
    setStatus(statusEl, "配置已加载", "ok");
  } catch (error) {
    setStatus(statusEl, `加载失败：${error.message}`, "error");
  }
}

async function togglePlugin(name, enabled) {
  const statusEl = document.getElementById("plugins-status");
  setStatus(statusEl, `正在${enabled ? "启用" : "禁用"} ${name}...`, "warn");

  try {
    await requestJson(`/v1/plugins/toggle?name=${encodeURIComponent(name)}&enabled=${enabled}`, {
      method: "POST",
      headers: {},
    });
    await loadPlugins();
    setStatus(statusEl, `${name} 已${enabled ? "启用" : "禁用"}`, "ok");
  } catch (error) {
    setStatus(statusEl, `操作失败：${error.message}`, "error");
  }
}

function bindPluginActions() {
  document.getElementById("plugin-reload").addEventListener("click", loadPlugins);
}

function fillSafetyForm(data) {
  document.getElementById("safety-enabled").checked = Boolean(data.enabled);
  document.getElementById("safety-input").value = data.inputStrategy || "";
  document.getElementById("safety-output").value = data.outputStrategy || "";
  document.getElementById("safety-mask").value = data.redactMask || "***";
  const words = Array.isArray(data.blockedWords) ? data.blockedWords : [];
  document.getElementById("safety-words").value = words.join("\n");
  const injection = Array.isArray(data.promptInjectionPatterns) ? data.promptInjectionPatterns : [];
  document.getElementById("safety-injection").value = injection.join("\n");
  const pii = Array.isArray(data.piiPatterns) ? data.piiPatterns : [];
  document.getElementById("safety-pii").value = pii.join("\n");
}

async function loadSafety() {
  const statusEl = document.getElementById("safety-status");
  setStatus(statusEl, "加载中...", "warn");
  try {
    const data = await requestJson("/v1/safety/config");
    fillSafetyForm(data);
    setStatus(statusEl, "配置已加载", "ok");
  } catch (error) {
    setStatus(statusEl, `加载失败：${error.message}`, "error");
  }
}

async function submitSafety(event) {
  event.preventDefault();
  const statusEl = document.getElementById("safety-status");
  setStatus(statusEl, "保存中...", "warn");

  const enabled = document.getElementById("safety-enabled").checked;
  const inputStrategy = document.getElementById("safety-input").value.trim();
  const outputStrategy = document.getElementById("safety-output").value.trim();
  const redactMask = document.getElementById("safety-mask").value.trim() || "***";
  const blockedWords = document
    .getElementById("safety-words")
    .value
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
  const promptInjectionPatterns = document
    .getElementById("safety-injection")
    .value
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
  const piiPatterns = document
    .getElementById("safety-pii")
    .value
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);

  try {
    const data = await requestJson("/v1/safety/config", {
      method: "POST",
      body: JSON.stringify({
        enabled,
        inputStrategy,
        outputStrategy,
        redactMask,
        blockedWords,
        promptInjectionPatterns,
        piiPatterns,
      }),
    });
    fillSafetyForm(data);
    setStatus(statusEl, "保存成功", "ok");
  } catch (error) {
    setStatus(statusEl, `保存失败：${error.message}`, "error");
  }
}

function bindSafetyActions() {
  document.getElementById("safety-load").addEventListener("click", loadSafety);
  document.getElementById("safety-form").addEventListener("submit", submitSafety);
  document.getElementById("safety-sandbox-check").addEventListener("click", safetySandboxCheck);
}

async function safetySandboxCheck() {
  const statusEl = document.getElementById("safety-status");
  const text = document.getElementById("safety-sandbox-text").value;
  if (!text.trim()) {
    setStatus(statusEl, "请填写沙箱文本", "error");
    return;
  }
  setStatus(statusEl, "检测中...", "warn");
  try {
    const data = await requestJson("/v1/safety/sandbox/check", {
      method: "POST",
      body: JSON.stringify({ content: text }),
    });
    document.getElementById("safety-sandbox-result").value = JSON.stringify(data, null, 2);
    setStatus(statusEl, "检测完成", data.matched ? "warn" : "ok");
  } catch (error) {
    setStatus(statusEl, `检测失败：${error.message}`, "error");
  }
}

function formatDate(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function initBillingDefaultDate() {
  const end = new Date();
  const start = new Date(Date.now() - 6 * 24 * 60 * 60 * 1000);
  document.getElementById("billing-start").value = formatDate(start);
  document.getElementById("billing-end").value = formatDate(end);
}

async function exportBilling(event) {
  event.preventDefault();
  const statusEl = document.getElementById("billing-status");
  const startDate = document.getElementById("billing-start").value;
  const endDate = document.getElementById("billing-end").value;

  if (!startDate || !endDate) {
    setStatus(statusEl, "请完整填写日期范围", "error");
    return;
  }

  if (startDate > endDate) {
    setStatus(statusEl, "开始日期不能晚于结束日期", "error");
    return;
  }

  const url = `/v1/billing/export?startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}`;
  setStatus(statusEl, "导出中...", "warn");

  try {
    const response = await fetch(url, { headers: buildHeaders() });
    if (!response.ok) {
      const errText = await response.text();
      showGlobalError(`HTTP ${response.status}\n${errText}`);
      logAudit("BILLING_EXPORT", `导出失败 ${response.status}\n${errText}`, false);
      throw new Error(`HTTP ${response.status}`);
    }
    const blob = await response.blob();
    const link = document.createElement("a");
    const href = URL.createObjectURL(blob);
    link.href = href;
    link.download = `ai-billing-${startDate}-${endDate}.csv`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(href);
    setStatus(statusEl, "导出成功", "ok");
    logAudit("BILLING_EXPORT", `${startDate} ~ ${endDate} 导出成功`, true);
  } catch (error) {
    setStatus(statusEl, `导出失败：${error.message}`, "error");
  }
}

function bindBillingActions() {
  initBillingDefaultDate();
  document.getElementById("billing-form").addEventListener("submit", exportBilling);
}

function bindNavigation() {
  document.querySelectorAll(".nav-btn").forEach((btn) => {
    btn.addEventListener("click", () => switchView(btn.dataset.view));
  });
}

function init() {
  bindAccessGate();
  initAccessGate();
  bindGlobalUi();
  bindAuditActions();
  loadGlobalHeaders();
  updateRoleStatus();
  bindNavigation();
  bindChatActions();
  bindModelsActions();
  bindMetricsActions();
  bindRateLimitActions();
  bindRoutingActions();
  bindCacheAbActions();
  bindPluginActions();
  bindSafetyActions();
  bindBillingActions();
  clearOutput();
  loadRateLimitConfig();
  loadRoutingConfig();
  loadCacheConfig();
  loadAbConfig();
  refreshCacheStats();
  loadPlugins();
  loadSafety();
  renderAuditLogs();
  logAudit("CONSOLE_INIT", `控制台初始化 ${nowText()}`, true);
}

init();
