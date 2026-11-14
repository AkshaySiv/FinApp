/* Family Wealth — front-end (vanilla JS, no external network calls). */
"use strict";
const APP_VERSION = "1.12";

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const state = {
  token: sessionStorage.getItem("fw_token"),
  meta: null, data: null, summary: null,
  cur: localStorage.getItem("fw_cur") || "AED",
  who: localStorage.getItem("fw_who") || "family",
  view: location.hash.slice(1).split("?")[0] || "dashboard",
  theme: localStorage.getItem("fw_theme") || "dark",
  charts: [],
  assetFilter: "all",
};
document.documentElement.dataset.theme = state.theme;

// ------------------------------------------------------------------ API
async function api(path, opts = {}) {
  const res = await fetch(path, {
    method: opts.method || "GET",
    headers: { "Content-Type": "application/json", "X-Session": state.token || "" },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  if (res.status === 401 && !path.startsWith("/api/unlock")) { lockUI(); throw new Error("locked"); }
  const j = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(j.detail || `Error ${res.status}`);
  return j;
}

function toast(msg, kind = "ok") {
  const t = document.createElement("div");
  t.className = `toast ${kind}`;
  t.textContent = msg;
  $("#toast-root").appendChild(t);
  setTimeout(() => t.remove(), kind === "error" ? 6000 : 3000);
}

// ------------------------------------------------------------------ formatting
const SYM = { AED: "AED ", USD: "$", INR: "₹" };
function fmt(v, cur = state.cur, o = {}) {
  v = Number(v) || 0;
  const neg = v < 0, a = Math.abs(v);
  let s;
  if (o.compact) {
    if (cur === "INR") {
      s = a >= 1e7 ? (a / 1e7).toFixed(2) + " Cr" : a >= 1e5 ? (a / 1e5).toFixed(2) + " L" : a.toLocaleString("en-IN", { maximumFractionDigits: 0 });
    } else {
      s = a >= 1e9 ? (a / 1e9).toFixed(2) + "B" : a >= 1e6 ? (a / 1e6).toFixed(2) + "M" : a >= 1e4 ? (a / 1e3).toFixed(1) + "K" : a.toLocaleString("en-US", { maximumFractionDigits: 0 });
    }
  } else {
    const d = o.dp ?? (a < 100 && a !== 0 ? 2 : 0);
    s = a.toLocaleString(cur === "INR" ? "en-IN" : "en-US", { minimumFractionDigits: d, maximumFractionDigits: d });
  }
  return (neg ? "−" : "") + SYM[cur] + s;
}
const pct = (v, dp = 1) => `${v >= 0 ? "+" : "−"}${Math.abs(v * 100).toFixed(dp)}%`;
const numf = (v, dp = 2) => (Number(v) || 0).toLocaleString("en-US", { maximumFractionDigits: dp });
const signCls = (v) => (v > 0.0001 ? "up" : v < -0.0001 ? "down" : "muted");
const fx = () => state.data?.fx || { USD: 1, AED: 3.6725, INR: 88.5 };
const conv = (amt, from, to = state.cur) => (from === to ? amt : (amt / fx()[from]) * fx()[to]);
const monthName = (d) => new Date(d + "T00:00:00").toLocaleString("en", { month: "short" });
const fmtDate = (d) => (d ? new Date(d + "T00:00:00").toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" }) : "—");
const cssVar = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
const SERIES = () => [1, 2, 3, 4, 5, 6, 7, 8].map((i) => cssVar(`--s${i}`));

function memberName(id) {
  if (id === "joint" || !id) return "Joint";
  return state.data.members.find((m) => m.id === id)?.name || "Joint";
}
function memberColor(id) {
  return state.data.members.find((m) => m.id === id)?.color || cssVar("--text-muted");
}
function ownerChip(id) {
  return `<span class="owner-chip"><i style="background:${id === "joint" || !id ? "linear-gradient(90deg," + state.data.members.map((m) => m.color).join(",") + ")" : memberColor(id)}"></i>${esc(memberName(id))}</span>`;
}
function visibleToWho(owner) {
  if (state.who === "family") return true;
  return owner === state.who || owner === "joint" || !state.data.members.some((m) => m.id === owner);
}

// ------------------------------------------------------------------ auth screens
async function boot() {
  try {
    const st = await (await fetch("/api/status")).json();
    if (!st.exists) return renderSetup();
    if (!st.unlocked || !state.token) return renderUnlock();
    await loadAll();
    renderShell();
  } catch (e) {
    if (e.message !== "locked") $("#root").innerHTML = `<div class="boot">Could not reach the local server. Is it running?</div>`;
  }
}

function lockUI() {
  state.token = null; state.data = null; state.summary = null;
  sessionStorage.removeItem("fw_token");
  destroyCharts();
  closeModal();
  renderUnlock();
}

function authCard(inner) {
  $("#root").innerHTML = `<div class="auth"><div class="auth-card">
    <div class="brand-mark">FW</div>${inner}
    <div class="lock-note">🔒 <span>Data is encrypted on this Mac with AES-256-GCM. The server only listens on 127.0.0.1 — nothing leaves your machine.</span></div>
  </div></div>`;
}

function renderSetup() {
  authCard(`<h1>Create your family vault</h1>
    <p>Set a master password. There is no recovery — if you forget it, the data cannot be decrypted.</p>
    <form id="setup-form">
      <div class="field"><label>Your name</label><input class="ctl" name="m1" value="Akshay" required></div>
      <div class="field"><label>Spouse's name</label><input class="ctl" name="m2" placeholder="e.g. Priya"></div>
      <div class="field"><label>Default currency</label><select class="ctl" name="base"><option>AED</option><option>INR</option><option>USD</option></select></div>
      <div class="field"><label>Master password (min 10 chars)</label><input class="ctl" type="password" name="pw" minlength="10" required autocomplete="new-password"></div>
      <div class="field"><label>Confirm password</label><input class="ctl" type="password" name="pw2" required autocomplete="new-password"></div>
      <div class="err" id="auth-err"></div>
      <button class="btn primary" type="submit">Create encrypted vault</button>
    </form>`);
  $("#setup-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    if (f.get("pw") !== f.get("pw2")) return ($("#auth-err").textContent = "Passwords do not match");
    try {
      $("#auth-err").textContent = "Deriving key…";
      const r = await api("/api/setup", { method: "POST", body: { password: f.get("pw"), members: [f.get("m1"), f.get("m2")], base_currency: f.get("base") } });
      setToken(r.token);
      state.cur = f.get("base"); localStorage.setItem("fw_cur", state.cur);
      await loadAll(); renderShell();
    } catch (err) { $("#auth-err").textContent = err.message; }
  });
}

function renderUnlock() {
  authCard(`<h1>Unlock</h1><p>Enter your master password to decrypt your data.</p>
    <form id="unlock-form">
      <div class="field"><input class="ctl" type="password" name="pw" placeholder="Master password" autofocus required autocomplete="current-password"></div>
      <div class="err" id="auth-err"></div>
      <button class="btn primary" type="submit">Unlock</button>
    </form>`);
  $("#unlock-form input").focus();
  $("#unlock-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    $("#auth-err").textContent = "Decrypting…";
    try {
      const r = await api("/api/unlock", { method: "POST", body: { password: new FormData(e.target).get("pw") } });
      setToken(r.token);
      await loadAll(); renderShell();
    } catch (err) { $("#auth-err").textContent = err.message; }
  });
}

function setToken(t) { state.token = t; sessionStorage.setItem("fw_token", t); }

async function loadAll() {
  if (!state.meta) state.meta = await api("/api/meta");
  state.data = await api("/api/data");
  if (state.who !== "family" && !state.data.members.some((m) => m.id === state.who)) state.who = "family";
  state.summary = await api(`/api/summary?currency=${state.cur}&who=${state.who}`);
}
async function refresh() { await loadAll(); renderView(); updateRefreshBadge(); }

// keep the session alive while the user is active; server auto-locks when idle
let lastPing = 0;
["mousemove", "keydown", "click", "scroll"].forEach((ev) =>
  window.addEventListener(ev, () => {
    if (state.token && Date.now() - lastPing > 60000) { lastPing = Date.now(); api("/api/ping").catch(() => {}); }
  }, { passive: true }));
// background price refreshes happen on the server — pick them up without touching the idle timer
setInterval(async () => {
  if (!state.token || !state.data) return;
  try {
    const r = await api("/api/rev");
    state.lastRefresh = r.last_refresh; state.refreshing = r.refreshing;
    updateRefreshBadge();
    const busy = state.chatBusy || state.view === "advisor" || $("#modal-root").children.length || ["INPUT", "SELECT", "TEXTAREA"].includes(document.activeElement?.tagName);
    if (r.rev !== state.data.rev && !busy) await refresh();
  } catch {}
}, 15000);

function updateRefreshBadge() {
  const b = $("#refresh-btn");
  if (!b) return;
  const lr = state.lastRefresh || state.data?.last_refresh;
  if (state.refreshing) { b.textContent = "↻ Updating…"; b.title = ""; return; }
  b.textContent = lr ? `↻ Prices ${ago(lr.at)}` : "↻ Update prices";
  b.title = lr ? `Last update ${lr.at}: ${lr.updated} updated${lr.failed?.length ? " · not found: " + lr.failed.join(", ") : ""}` : "";
  b.classList.toggle("warn", !!(lr && lr.failed && lr.failed.length));
}

// ------------------------------------------------------------------ shell
const NAV = [
  ["Overview", [["dashboard", "◧", "Dashboard"], ["insights", "◈", "Insights"], ["advisor", "✦", "AI Advisor"], ["family", "◎", "Family"]]],
  ["Wealth", [["assets", "▤", "Assets"], ["stocks-IN", "₹", "Indian Stocks"], ["stocks-US", "$", "US Stocks"], ["stocks-UAE", "د", "UAE Stocks"], ["brokers", "⇆", "Broker Sync"]]],
  ["Planning", [["loans", "⇄", "Loans & EMI"], ["goals", "◆", "Goals"]]],
  ["", [["settings", "⚙", "Settings"]]],
];
const TITLES = { dashboard: "Dashboard", family: "Family", assets: "Assets", "stocks-IN": "Indian Stocks", "stocks-US": "US Stocks", "stocks-UAE": "UAE Stocks", loans: "Loans & EMI", goals: "Goals", settings: "Settings", brokers: "Broker Sync", insights: "Insights", advisor: "AI Advisor" };

function renderShell() {
  $("#root").innerHTML = `<div class="shell">
    <aside class="sidebar">
      <div class="brand"><div class="brand-mark">FW</div>Family Wealth</div>
      ${NAV.map(([sec, items]) => `${sec ? `<div class="nav-section">${sec}</div>` : `<div style="height:14px"></div>`}
        ${items.map(([id, ico, label]) => `<button class="nav-item" data-nav="${id}"><span class="ico">${ico}</span>${label}</button>`).join("")}`).join("")}
      <div class="sidebar-foot"><span class="dot"></span>Encrypted · local only · v${APP_VERSION}</div>
    </aside>
    <div class="main">
      <header class="topbar">
        <h1 id="page-title"></h1><div class="spacer"></div>
        <select class="ctl compact" id="who-select" title="Whose finances">
          <option value="family">👪 Family</option>
          ${state.data.members.map((m) => `<option value="${m.id}">${esc(m.name)}</option>`).join("")}
        </select>
        <div class="seg" id="cur-seg">${state.meta.currencies.map((c) => `<button data-cur="${c}">${c}</button>`).join("")}</div>
        <button class="btn sm" id="refresh-btn" title="Update prices now">↻ Prices</button>
        <button class="btn sm ghost" id="theme-btn" title="Toggle light / dark">${state.theme === "dark" ? "☀︎ Light" : "☾ Dark"}</button>
        <button class="btn sm" id="lock-btn" title="Lock now">🔒 Lock</button>
      </header>
      <section class="content" id="view"></section>
    </div>
  </div>`;
  $("#who-select").value = state.who;
  $("#who-select").addEventListener("change", async (e) => { state.who = e.target.value; localStorage.setItem("fw_who", state.who); await refresh(); });
  $$("#cur-seg button").forEach((b) => b.addEventListener("click", async () => { state.cur = b.dataset.cur; localStorage.setItem("fw_cur", state.cur); await refresh(); }));
  $("#theme-btn").addEventListener("click", () => {
    state.theme = state.theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = state.theme; localStorage.setItem("fw_theme", state.theme); $("#theme-btn").textContent = state.theme === "dark" ? "☀︎ Light" : "☾ Dark"; renderView();
  });
  $("#refresh-btn").addEventListener("click", async () => {
    state.refreshing = true; updateRefreshBadge();
    try {
      const r = await api("/api/refresh", { method: "POST" });
      toast(`Updated ${r.updated} price(s)` + (r.failed?.length ? ` · not found: ${r.failed.join(", ")}` : "") + (r.fx_error ? " · FX fetch failed" : ""), r.failed?.length || r.fx_error ? "error" : "ok");
    } catch (e) { toast(e.message, "error"); }
    state.refreshing = false; await refresh();
  });
  $("#lock-btn").addEventListener("click", async () => { await api("/api/lock", { method: "POST" }).catch(() => {}); lockUI(); });
  $$(".nav-item").forEach((b) => b.addEventListener("click", () => { location.hash = b.dataset.nav; }));
  bindActions($("#view"));
  renderView();
  updateRefreshBadge();
}
window.addEventListener("hashchange", () => { state.view = location.hash.slice(1).split("?")[0] || "dashboard"; if (state.data) renderView(); });

function destroyCharts() { state.charts.forEach((c) => c.destroy()); state.charts = []; }

function flashFromHash() {
  const q = new URLSearchParams(location.hash.split("?")[1] || "");
  if (q.get("ok")) toast(q.get("ok"));
  if (q.get("err")) toast(q.get("err"), "error");
  if (q.get("ok") || q.get("err")) history.replaceState(null, "", "#" + state.view);
}
function brokerBanner() {
  const need = (state.data.connections || []).filter((c) => c.status === "login_required" || (c.status === "new" && !c.logged_in));
  if (!need.length || state.view === "brokers") return "";
  return `<div class="banner">⇆ <span>${need.map((c) => `<b>${c.broker === "zerodha" ? "Zerodha" : "Fyers"}${c.label ? " · " + esc(c.label) : ""}</b>`).join(", ")} ${need.length > 1 ? "need" : "needs"} a login to sync holdings.</span>
    ${need.map((c) => `<button class="btn sm primary" data-login="${c.id}">Log in ${c.broker === "zerodha" ? "Zerodha" : "Fyers"}${need.length > 1 && c.label ? " (" + esc(c.label) + ")" : ""}</button>`).join("")}</div>`;
}

function renderView() {
  destroyCharts();
  flashFromHash();
  const v = TITLES[state.view] ? state.view : "dashboard";
  $("#page-title").textContent = TITLES[v];
  $$(".nav-item").forEach((b) => b.classList.toggle("active", b.dataset.nav === v));
  $$("#cur-seg button").forEach((b) => b.classList.toggle("on", b.dataset.cur === state.cur));
  const view = $("#view");
  if (v.startsWith("stocks-")) { view.innerHTML = brokerBanner() + viewStocks(v.split("-")[1]); afterStocks(v.split("-")[1]); }
  else { view.innerHTML = brokerBanner() + VIEWS[v].render(); VIEWS[v].after?.(); }
}

// ------------------------------------------------------------------ chart helpers
function chartDefaults() {
  if (!window.Chart) return;
  Chart.defaults.font.family = 'system-ui, -apple-system, "Segoe UI", sans-serif';
  Chart.defaults.font.size = 12;
  Chart.defaults.color = cssVar("--text-muted");
  Chart.defaults.borderColor = cssVar("--grid");
  Chart.defaults.plugins.legend.display = false;
  Object.assign(Chart.defaults.plugins.tooltip, {
    backgroundColor: cssVar("--surface-3"), titleColor: cssVar("--text-primary"), bodyColor: cssVar("--text-secondary"),
    borderColor: cssVar("--border"), borderWidth: 1, padding: 10, cornerRadius: 8, boxPadding: 4, usePointStyle: true,
  });
}
function mkChart(id, cfg) {
  const el = document.getElementById(id);
  if (!el || !window.Chart) return;
  chartDefaults();
  state.charts.push(new Chart(el, cfg));
}
function hbar(id, labels, values, color, fmtFn) {
  const total = values.reduce((a, b) => a + b, 0) || 1;
  mkChart(id, {
    type: "bar",
    data: { labels, datasets: [{ data: values, backgroundColor: color, borderRadius: 4, borderSkipped: "start", barThickness: 14 }] },
    options: {
      indexAxis: "y", maintainAspectRatio: false,
      scales: {
        x: { grid: { color: cssVar("--grid") }, border: { display: false }, ticks: { callback: (v) => fmtFn(v, true), maxTicksLimit: 4, maxRotation: 0 } },
        y: { grid: { display: false }, border: { color: cssVar("--axis") }, ticks: { color: cssVar("--text-secondary") } },
      },
      plugins: { tooltip: { callbacks: { label: (c) => ` ${fmtFn(c.raw)} · ${((c.raw / total) * 100).toFixed(1)}%` } } },
    },
  });
}

// ------------------------------------------------------------------ views
const VIEWS = {};

function kpi(label, value, sub = "", cls = "") {
  return `<div class="card ${cls}"><div class="stat-label">${label}</div><div class="stat-value">${value}</div><div class="stat-sub">${sub}</div></div>`;
}
function emptyState(msg, action, label) {
  return `<div class="empty">${msg}<br>${action ? `<button class="btn primary" data-act="${action}">${label}</button>` : ""}</div>`;
}

VIEWS.dashboard = {
  render() {
    const s = state.summary, t = s.totals, cur = state.cur;
    const hist = s.history;
    const prev = hist.length > 1 ? hist[hist.length - 2] : null;
    const first = hist.length > 1 ? hist[0] : null;
    const change = first ? t.net_worth - first.net_worth : null;
    const curTotal = s.by_currency.reduce((a, b) => a + b.value, 0) || 1;
    const memTotal = s.by_member.reduce((a, b) => a + Math.max(b.assets, 0), 0) || 1;
    const S = SERIES();
    return `
    <div class="grid kpis">
      ${kpi("Net worth", fmt(t.net_worth), change != null ? `<span class="${signCls(change)}">${change >= 0 ? "▲" : "▼"} ${fmt(Math.abs(change), cur, { compact: true })}</span> since ${fmtDate(first.date)}` : `${state.who === "family" ? "Family" : esc(memberName(state.who))} · ${cur}`, "hero")}
      ${kpi("Total assets", fmt(t.assets), `Invested ${fmt(t.invested, cur, { compact: true })} · <span class="${signCls(t.gain)}">${fmt(t.gain, cur, { compact: true })} gain</span>`)}
      ${kpi("Liabilities", fmt(t.liabilities), `Debt / assets ${(t.debt_ratio * 100).toFixed(1)}%`)}
      ${kpi("Monthly EMI", fmt(t.monthly_emi), `${s.loans.filter((l) => l.active).length} active loan(s)`)}
      ${kpi("Stocks P&amp;L", `<span class="${signCls(t.stocks_pnl)}">${fmt(t.stocks_pnl)}</span>`, `${pct(t.stocks_pnl_pct)} on ${fmt(t.stocks_value - t.stocks_pnl, cur, { compact: true })} cost`)}
    </div>

    <div class="grid cols-2" style="margin-top:16px">
      <div class="card"><div class="card-h"><h3>Net worth trend</h3><span class="sub">${cur} · one snapshot per day</span></div>
        ${hist.length < 2 ? `<div class="empty" style="padding:90px 0">The trend line builds automatically — a snapshot is saved each day you use the app.</div>` : `<div class="chart-box"><canvas id="ch-hist" aria-label="Net worth over time"></canvas></div>`}
      </div>
      <div class="card"><div class="card-h"><h3>Asset allocation</h3><span class="sub">by class</span></div>
        ${s.by_class.length ? `<div class="chart-box"><canvas id="ch-alloc" aria-label="Asset allocation by class"></canvas></div>` : emptyState("No assets yet.", "add-asset", "Add an asset")}
      </div>
    </div>

    <div class="grid cols-3" style="margin-top:16px">
      ${Object.entries(s.markets).map(([k, m]) => `
        <div class="card market" data-nav-go="stocks-${k}">
          <div class="card-h"><span class="flag">${k}</span><h3>${m.label}</h3><div class="spacer"></div><span class="sub">${m.count} holding${m.count === 1 ? "" : "s"}</span></div>
          <div class="stat-value" style="font-size:22px">${fmt(m.value)}</div>
          <div class="stat-sub"><span class="${signCls(m.pnl)}">${fmt(m.pnl)} (${pct(m.pnl_pct)})</span> unrealised</div>
        </div>`).join("")}
    </div>

    <div class="grid cols-3" style="margin-top:16px">
      <div class="card"><div class="card-h"><h3>Currency exposure</h3><span class="sub">assets held in</span></div>
        <div class="split">${s.by_currency.map((c, i) => c.value > 0 ? `<span style="width:${(c.value / curTotal) * 100}%;background:${S[i]}"></span>` : "").join("")}</div>
        <div class="list">${s.by_currency.map((c, i) => `<div class="list-row"><i style="width:10px;height:10px;border-radius:3px;background:${S[i]}"></i><div class="grow t">${c.currency}</div><div class="sec">${((c.value / curTotal) * 100).toFixed(1)}%</div><div style="width:120px;text-align:right">${fmt(c.value, cur, { compact: true })}</div></div>`).join("")}</div>
      </div>
      <div class="card"><div class="card-h"><h3>Family split</h3><span class="sub">net worth by member · joint split equally</span></div>
        <div class="split">${s.by_member.map((m) => m.assets > 0 ? `<span style="width:${(m.assets / memTotal) * 100}%;background:${m.id ? memberColor(m.id) : S[0]}"></span>` : "").join("")}</div>
        <div class="list">${s.by_member.map((m) => `<div class="list-row"><div class="avatar" style="background:${memberColor(m.id)};width:28px;height:28px;font-size:12px">${esc(m.name[0] || "?")}</div><div class="grow"><div class="t">${esc(m.name)}</div><div class="s">Assets ${fmt(m.assets, cur, { compact: true })} · Debt ${fmt(m.liabilities, cur, { compact: true })}</div></div><div style="font-weight:600">${fmt(m.net, cur, { compact: true })}</div></div>`).join("")}</div>
      </div>
      <div class="card"><div class="card-h"><h3>Upcoming EMIs</h3><div class="spacer"></div><button class="btn sm ghost" data-nav-go="loans">All loans →</button></div>
        ${s.upcoming_emis.length ? `<div class="list">${s.upcoming_emis.slice(0, 5).map((u) => `<div class="list-row"><div class="datepill"><b>${u.date.slice(8)}</b><span>${monthName(u.date)}</span></div><div class="grow"><div class="t">${esc(u.name)}</div><div class="s">${fmt(u.emi, u.currency)}</div></div><div>${fmt(u.emi_conv)}</div></div>`).join("")}</div>` : `<div class="empty">No active loans 🎉</div>`}
      </div>
    </div>

    <div class="card" style="margin-top:16px"><div class="card-h"><h3>Goals</h3><div class="spacer"></div><button class="btn sm ghost" data-nav-go="goals">Manage →</button></div>
      ${s.goals.length ? `<div class="grid cols-2e">${s.goals.slice(0, 6).map(goalRow).join("")}</div>` : emptyState("Set a goal — a house, kids' education, retirement.", "add-goal", "Add a goal")}
    </div>`;
  },
  after() {
    const s = state.summary;
    if (s.history.length >= 2) {
      const ctx = document.getElementById("ch-hist");
      const accent = cssVar("--s1");
      mkChart("ch-hist", {
        type: "line",
        data: { labels: s.history.map((h) => fmtDate(h.date)), datasets: [{ data: s.history.map((h) => h.net_worth), borderColor: accent, borderWidth: 2, pointRadius: 0, pointHoverRadius: 5, pointHoverBorderWidth: 2, pointHoverBorderColor: cssVar("--surface-1"), pointHoverBackgroundColor: accent, tension: 0.25, fill: true,
          backgroundColor: (() => { const g = ctx.getContext("2d").createLinearGradient(0, 0, 0, 260); g.addColorStop(0, accent + "33"); g.addColorStop(1, accent + "00"); return g; })() }] },
        options: {
          maintainAspectRatio: false, interaction: { mode: "index", intersect: false },
          scales: { x: { grid: { display: false }, border: { color: cssVar("--axis") }, ticks: { maxTicksLimit: 7 } },
            y: { grid: { color: cssVar("--grid") }, border: { display: false }, ticks: { callback: (v) => fmt(v, state.cur, { compact: true }), maxTicksLimit: 5 } } },
          plugins: { tooltip: { callbacks: { label: (c) => ` Net worth ${fmt(c.raw)}` } } },
        },
      });
    }
    const bc = [...s.by_class].sort((a, b) => b.value - a.value);
    hbar("ch-alloc", bc.map((x) => x.label), bc.map((x) => x.value), cssVar("--s1"), (v, c) => fmt(v, state.cur, { compact: c }));
  },
};

function goalRow(g) {
  const p = g.progress;
  return `<div><div style="display:flex;justify-content:space-between;margin-bottom:6px"><span style="font-weight:560">${esc(g.name)}</span><span class="sec">${(p * 100).toFixed(0)}%</span></div>
    <div class="bar ${p >= 1 ? "good" : ""}"><span style="width:${Math.max(p * 100, 1)}%"></span></div>
    <div class="stat-sub">${fmt(g.saved, g.currency, { compact: true })} of ${fmt(g.target, g.currency, { compact: true })} · ${g.months_left ? `${g.months_left} mo left · needs ${fmt(g.required_monthly, g.currency, { compact: true })}/mo` : "due"}</div></div>`;
}

// ---------- Family
VIEWS.family = {
  render() {
    const s = state.summary;
    return `<div class="toolbar"><span class="muted">Joint items are split equally between members. Use the selector at the top to view any one member's finances across every page.</span><div class="spacer"></div><button class="btn primary" data-act="add-member">+ Add member</button></div>
    <div class="grid cols-3">${s.by_member.map((m) => `<div class="card">
      <div class="member-tile"><div class="avatar" style="background:${memberColor(m.id)}">${esc(m.name[0] || "?")}</div><div style="flex:1"><div style="font-weight:620;font-size:15px">${esc(m.name)}</div><div class="muted" style="font-size:12px">Net worth</div></div>
        <button class="icon-btn" data-edit="members:${m.id}" title="Edit">✎</button>${state.data.members.length > 1 ? `<button class="icon-btn" data-del="members:${m.id}" title="Remove">✕</button>` : ""}</div>
      <div class="stat-value">${fmt(m.net)}</div>
      <dl class="kv" style="margin-top:14px"><dt>Assets</dt><dd>${fmt(m.assets)}</dd><dt>Liabilities</dt><dd>${fmt(m.liabilities)}</dd></dl>
      <div style="margin-top:14px"><button class="btn sm" data-who="${m.id}">View ${esc(m.name)}'s dashboard →</button></div>
    </div>`).join("")}
    <div class="card"><div class="member-tile"><div class="avatar" style="background:var(--surface-3);color:var(--text-primary)">👪</div><div><div style="font-weight:620;font-size:15px">Family total</div><div class="muted" style="font-size:12px">Everyone combined</div></div></div>
      <div class="stat-value">${fmt(s.by_member.reduce((a, b) => a + b.net, 0))}</div>
      <dl class="kv" style="margin-top:14px"><dt>Assets</dt><dd>${fmt(s.by_member.reduce((a, b) => a + b.assets, 0))}</dd><dt>Liabilities</dt><dd>${fmt(s.by_member.reduce((a, b) => a + b.liabilities, 0))}</dd></dl>
      <div style="margin-top:14px"><button class="btn sm" data-who="family">View family dashboard →</button></div></div>
    </div>
    <div class="card" style="margin-top:16px"><div class="card-h"><h3>Ownership by member</h3><span class="sub">${state.cur}</span></div><div class="chart-box sm"><canvas id="ch-members"></canvas></div><div class="legend" style="margin-top:10px"><span><i style="background:${cssVar("--s1")}"></i>Assets</span><span><i style="background:${cssVar("--s2")}"></i>Liabilities</span></div></div>`;
  },
  after() {
    const s = state.summary;
    mkChart("ch-members", {
      type: "bar",
      data: { labels: s.by_member.map((m) => m.name), datasets: [
        { label: "Assets", data: s.by_member.map((m) => m.assets), backgroundColor: cssVar("--s1"), borderRadius: 4, barThickness: 14 },
        { label: "Liabilities", data: s.by_member.map((m) => m.liabilities), backgroundColor: cssVar("--s2"), borderRadius: 4, barThickness: 14 }] },
      options: { indexAxis: "y", maintainAspectRatio: false,
        scales: { x: { grid: { color: cssVar("--grid") }, border: { display: false }, ticks: { callback: (v) => fmt(v, state.cur, { compact: true }) } }, y: { grid: { display: false }, ticks: { color: cssVar("--text-secondary") } } },
        plugins: { tooltip: { callbacks: { label: (c) => ` ${c.dataset.label}: ${fmt(c.raw)}` } } } },
    });
  },
};

// ---------- Assets
VIEWS.assets = {
  render() {
    const AC = state.meta.asset_classes;
    const rows = state.data.assets.filter((a) => visibleToWho(a.owner) && (state.assetFilter === "all" || a.asset_class === state.assetFilter));
    const used = [...new Set(state.data.assets.map((a) => a.asset_class))];
    let tot = 0, totInv = 0;
    const body = rows.sort((a, b) => conv(b.calc_value || 0, b.currency) - conv(a.calc_value || 0, a.currency)).map((a) => {
      const v = conv(a.calc_value || 0, a.currency), inv = conv(a.calc_cost ?? a.calc_value ?? 0, a.currency);
      tot += v; totInv += inv;
      const g = v - inv;
      return `<tr><td><div style="font-weight:560">${esc(a.name)}</div><div class="muted" style="font-size:12px">${a.source ? `<span class="tag sync">⇆ ${esc(a.source_label || "Broker")}</span>` : esc(a.institution || "")}</div></td>
        <td><span class="tag">${esc(AC[a.asset_class] || a.asset_class)}</span></td><td>${ownerChip(a.owner)}</td>
        <td>${valuationInfo(a)}</td>
        <td class="num">${fmt(a.calc_value, a.currency)}</td><td class="num">${fmt(v)}</td>
        <td class="num ${signCls(g)}">${Math.abs(g) > 0.005 ? fmt(g) : "—"}</td>
        <td class="actions"><button class="icon-btn" data-edit="assets:${a.id}">✎</button><button class="icon-btn" data-del="assets:${a.id}">✕</button></td></tr>`;
    }).join("");
    return `<div class="toolbar">
      <select class="ctl compact" id="asset-filter"><option value="all">All classes</option>${used.map((k) => `<option value="${k}" ${state.assetFilter === k ? "selected" : ""}>${esc(AC[k] || k)}</option>`).join("")}</select>
      <span class="muted">Values update automatically: live prices for gold, silver &amp; crypto, daily NAV for Indian mutual funds, interest accrual for FDs/EPF/PPF, yearly growth for property.</span>
      <div class="spacer"></div><button class="btn primary" data-act="add-asset">+ Add asset</button></div>
      <div class="card">${rows.length ? `<div class="table-wrap"><table><thead><tr><th>Name</th><th>Class</th><th>Owner</th><th>Valuation</th><th class="num">Value (native)</th><th class="num">Value (${state.cur})</th><th class="num">Gain</th><th></th></tr></thead>
        <tbody>${body}</tbody><tfoot><tr><td colspan="5">Total · ${rows.length} item(s)</td><td class="num">${fmt(tot)}</td><td class="num ${signCls(tot - totInv)}">${fmt(tot - totInv)}</td><td></td></tr></tfoot></table></div>`
        : emptyState("No assets here yet.", "add-asset", "+ Add asset")}</div>`;
  },
  after() { $("#asset-filter")?.addEventListener("change", (e) => { state.assetFilter = e.target.value; renderView(); }); },
};

function ago(iso) {
  if (!iso) return "";
  const d = new Date(iso.length <= 10 ? iso + "T00:00:00" : iso);
  const m = Math.round((Date.now() - d) / 60000);
  if (iso.length <= 10) return fmtDate(iso);
  return m < 1 ? "just now" : m < 60 ? `${m}m ago` : m < 1440 ? `${Math.round(m / 60)}h ago` : fmtDate(iso.slice(0, 10));
}
function valuationInfo(a) {
  const m = a.method || "manual";
  const err = a.price_error ? `<div class="down" style="font-size:11.5px" title="${esc(a.price_error)}">⚠ ${esc(a.price_error.slice(0, 40))}</div>` : "";
  if (m === "market" || m === "nav" || m === "broker") {
    const lbl = m === "nav" ? "NAV" : m === "broker" ? "Broker NAV" : "Live";
    const unitTxt = m === "nav" || m === "broker" ? `${numf(a.units, 3)} units` : String(a.symbol).toUpperCase() === "XAU" || String(a.symbol).toUpperCase() === "XAG" ? `${numf(a.units, 2)} g${String(a.symbol).toUpperCase() === "XAU" ? " · " + (a.purity || 24) + "K" : ""}` : `${numf(a.units, 6)}`;
    const price = a.live_price != null ? ` @ ${a.live_ccy} ${numf(a.live_price, a.live_price < 10 ? 4 : 2)}` : "";
    return `<span class="tag live">● ${lbl}</span> <span class="sec" style="font-size:12.5px">${esc(a.symbol || "")}</span><div class="muted" style="font-size:11.5px">${unitTxt}${price}${a.price_updated ? " · " + ago(a.price_updated) : a.live_price == null && !a.price_error ? " · waiting for first fetch" : ""}</div>${err}`;
  }
  if (m === "accrual") return `<span class="tag">↗ ${numf(a.rate)}% accrual</span><div class="muted" style="font-size:11.5px">since ${fmtDate(a.start_date)}${a.maturity_date ? " · matures " + fmtDate(a.maturity_date) : ""}</div>`;
  if (m === "appreciation") return `<span class="tag">${(a.growth_pct || 0) >= 0 ? "↗" : "↘"} ${numf(a.growth_pct)}%/yr</span><div class="muted" style="font-size:11.5px">from ${fmtDate(a.base_date)}</div>`;
  return `<span class="tag">Manual</span><div class="muted" style="font-size:11.5px">${a.as_of ? "as of " + fmtDate(a.as_of) : "updated " + fmtDate((a.updated || "").slice(0, 10))}</div>`;
}

// ---------- Stocks (per market)
function viewStocks(mk) {
  const M = state.meta.markets[mk], ncur = M.currency;
  const rows = state.data.holdings.filter((h) => h.market === mk && visibleToWho(h.owner));
  let tv = 0, tc = 0, day = 0, dayBase = 0;
  const calc = rows.map((h) => {
    const c = h.currency || ncur;
    const v = conv((h.qty || 0) * (h.price || h.avg_cost || 0), c, ncur), cost = conv((h.qty || 0) * (h.avg_cost || 0), c, ncur);
    tv += v; tc += cost;
    if (h.prev_close) { day += conv((h.qty || 0) * (h.price - h.prev_close), c, ncur); dayBase += conv((h.qty || 0) * h.prev_close, c, ncur); }
    return { h, c, v, cost };
  }).sort((a, b) => b.v - a.v);
  const pnl = tv - tc;
  const syncedSyms = new Set(rows.filter((r) => r.source).map((r) => `${r.symbol}|${r.owner}`));
  const body = calc.map(({ h, c, v, cost }) => {
    const dup = !h.source && syncedSyms.has(`${h.symbol}|${h.owner}`);
    const p = v - cost, dp = h.prev_close ? (h.price - h.prev_close) / h.prev_close : null;
    return `<tr><td><div style="font-weight:600">${esc(h.symbol)}${h.source ? ` <span class="tag sync" title="Synced from ${esc(h.source_label || "broker")}">⇆ ${esc((h.source_label || "").split(" · ")[0])}</span>` : ""}</div><div class="muted" style="font-size:12px;max-width:220px;overflow:hidden;text-overflow:ellipsis">${dup ? `<span class="down">⚠ also synced from broker — delete this manual entry</span>` : esc(h.name || "")}</div></td>
      <td><span class="tag">${esc(h.exchange || "")}</span></td><td>${ownerChip(h.owner)}</td>
      <td class="num">${numf(h.qty, 4)}</td><td class="num">${fmt(h.avg_cost, c, { dp: 2 })}</td>
      <td class="num">${h.price ? fmt(h.price, c, { dp: 2 }) : `<span class="muted">no price yet</span>`}${h.price_error ? `<div class="down" style="font-size:11px" title="${esc(h.price_error)}">⚠ ${esc(h.price_error.slice(0, 28))}</div>` : h.price_updated ? `<div class="muted" style="font-size:11px">${ago(h.price_updated)}</div>` : ""}</td>
      <td class="num ${dp == null ? "muted" : signCls(dp)}">${dp == null ? "—" : pct(dp, 2)}</td>
      <td class="num">${fmt(cost, ncur)}</td><td class="num">${fmt(v, ncur)}</td>
      <td class="num ${signCls(p)}">${fmt(p, ncur)}<div style="font-size:11.5px">${cost ? pct(p / cost) : ""}</div></td>
      <td class="num">${tv ? ((v / tv) * 100).toFixed(1) : 0}%</td>
      <td class="actions"><button class="icon-btn" data-edit="holdings:${h.id}">✎</button><button class="icon-btn" data-del="holdings:${h.id}">✕</button></td></tr>`;
  }).join("");
  return `<div class="grid kpis">
      ${kpi("Market value", fmt(tv, ncur), ncur !== state.cur ? `≈ ${fmt(conv(tv, ncur))}` : `${rows.length} holdings`, "hero")}
      ${kpi("Invested", fmt(tc, ncur), ncur !== state.cur ? `≈ ${fmt(conv(tc, ncur))}` : "")}
      ${kpi("Unrealised P&amp;L", `<span class="${signCls(pnl)}">${fmt(pnl, ncur)}</span>`, `<span class="${signCls(pnl)}">${tc ? pct(pnl / tc) : "—"}</span>`)}
      ${kpi("Today", dayBase ? `<span class="${signCls(day)}">${fmt(day, ncur)}</span>` : "—", dayBase ? `<span class="${signCls(day)}">${pct(day / dayBase, 2)}</span>` : "refresh prices to see")}
      ${kpi("Holdings", rows.length, `${[...new Set(rows.map((r) => r.exchange))].filter(Boolean).join(" · ") || M.exchanges.join(" · ")}`)}
    </div>
    <div class="toolbar" style="margin-top:16px"><span class="muted">${mk === "IN" && rows.some((r) => r.source) ? "⇆ Broker-synced stocks are priced by your broker (Zerodha/Fyers); others" : "Prices"} refresh automatically${mk === "IN" && rows.some((r) => r.source) ? "" : " (delayed quotes, Yahoo Finance)"}. ⚠ means a price couldn't be fetched — hover it for the reason.</span><div class="spacer"></div>
      <button class="btn" data-act="refresh-prices" data-market="${mk}">↻ Refresh prices</button><button class="btn primary" data-act="add-holding" data-market="${mk}">+ Add holding</button></div>
    <div class="card">${rows.length ? `<div class="table-wrap"><table><thead><tr><th>Symbol</th><th>Exch.</th><th>Owner</th><th class="num">Qty</th><th class="num">Avg cost</th><th class="num">LTP</th><th class="num">Day</th><th class="num">Invested</th><th class="num">Value</th><th class="num">P&amp;L</th><th class="num">Weight</th><th></th></tr></thead><tbody>${body}</tbody>
        <tfoot><tr><td colspan="7">Total · ${rows.length} holding(s)</td><td class="num">${fmt(tc, ncur)}</td><td class="num">${fmt(tv, ncur)}</td><td class="num ${signCls(pnl)}">${fmt(pnl, ncur)}</td><td class="num">100%</td><td></td></tr></tfoot></table></div>`
        : emptyState(`No ${M.label.toLowerCase()} yet.`, "add-holding", "+ Add holding")}</div>
    ${rows.length ? `<div class="grid cols-2" style="margin-top:16px">
      <div class="card"><div class="card-h"><h3>Allocation</h3><span class="sub">by holding · ${ncur}</span></div><div class="chart-box" style="height:${Math.min(Math.max(calc.length, 3), 10) * 30 + 50}px"><canvas id="ch-stock-alloc" aria-label="Allocation by holding"></canvas></div></div>
      <div class="card"><div class="card-h"><h3>By owner</h3><span class="sub">joint split equally</span></div><div class="list">${ownerSplit(calc, ncur, tv)}</div></div>
    </div>` : ""}
    </div>`;
}
function ownerSplit(calc, ncur, tv) {
  const per = {};
  state.data.members.forEach((m) => (per[m.id] = 0));
  calc.forEach(({ h, v }) => {
    const ids = state.data.members.map((m) => m.id);
    if (ids.includes(h.owner)) per[h.owner] += v; else ids.forEach((i) => (per[i] += v / ids.length));
  });
  return state.data.members.map((m) => `<div class="list-row"><div class="avatar" style="background:${m.color};width:28px;height:28px;font-size:12px">${esc(m.name[0] || "?")}</div><div class="grow"><div class="t">${esc(m.name)}</div><div class="bar" style="margin-top:6px"><span style="width:${tv ? (per[m.id] / tv) * 100 : 0}%;background:${m.color}"></span></div></div><div style="width:130px;text-align:right"><div style="font-weight:600">${fmt(per[m.id], ncur, { compact: true })}</div><div class="s">${tv ? ((per[m.id] / tv) * 100).toFixed(1) : 0}%</div></div></div>`).join("");
}
function afterStocks(mk) {
  const ncur = state.meta.markets[mk].currency;
  const rows = state.data.holdings.filter((h) => h.market === mk && visibleToWho(h.owner))
    .map((h) => ({ l: h.symbol, v: conv((h.qty || 0) * (h.price || h.avg_cost || 0), h.currency || ncur, ncur) })).sort((a, b) => b.v - a.v);
  const top = rows.slice(0, 9);
  if (rows.length > 9) top.push({ l: "Other", v: rows.slice(9).reduce((a, b) => a + b.v, 0) });
  hbar("ch-stock-alloc", top.map((r) => r.l), top.map((r) => r.v), cssVar("--s1"), (v, c) => fmt(v, ncur, { compact: c }));
}

// ---------- Loans
VIEWS.loans = {
  render() {
    const s = state.summary, L = s.loans;
    const remainingInterest = L.reduce((a, l) => a + conv(l.total_interest - l.interest_paid, l.currency), 0);
    const body = L.map((l) => `<tr>
      <td><div style="font-weight:560">${esc(l.name)}</div><div class="muted" style="font-size:12px">${esc(l.type_label)}${l.lender ? " · " + esc(l.lender) : ""}</div></td>
      <td>${ownerChip(l.owner)}</td><td class="num">${fmt(l.principal, l.currency)}</td><td class="num">${numf(l.rate)}%</td>
      <td class="num">${fmt(l.emi, l.currency)}</td><td class="num">${fmt(l.outstanding, l.currency)}<div class="muted" style="font-size:11.5px">≈ ${fmt(conv(l.outstanding, l.currency), state.cur, { compact: true })}</div></td>
      <td style="min-width:130px">${l.emis_total ? `<div class="bar good"><span style="width:${Math.max(l.progress * 100, 1)}%"></span></div><div class="muted" style="font-size:11.5px;margin-top:4px">${l.emis_paid}/${l.emis_total} EMIs · ${(l.progress * 100).toFixed(0)}% repaid</div>` : `<span class="tag">Revolving</span>`}</td>
      <td>${!l.active ? `<span class="tag">Closed</span>` : l.next_due ? fmtDate(l.next_due) : "Monthly"}</td><td class="muted">${fmtDate(l.end_date)}</td>
      <td class="actions">${l.emis_total ? `<button class="btn sm" data-schedule="${l.id}">Schedule</button>` : ""}<button class="icon-btn" data-edit="loans:${l.id}">✎</button><button class="icon-btn" data-del="loans:${l.id}">✕</button></td></tr>`).join("");
    return `<div class="grid kpis">
      ${kpi("Total outstanding", fmt(s.totals.liabilities), `${L.filter((l) => l.active).length} active`, "hero")}
      ${kpi("Monthly EMI outflow", fmt(s.totals.monthly_emi), `${fmt(s.totals.monthly_emi * 12, state.cur, { compact: true })} a year`)}
      ${kpi("Interest still to pay", fmt(remainingInterest), "on current schedules")}
      ${kpi("Next EMI", s.upcoming_emis[0] ? fmtDate(s.upcoming_emis[0].date) : "—", s.upcoming_emis[0] ? `${esc(s.upcoming_emis[0].name)} · ${fmt(s.upcoming_emis[0].emi, s.upcoming_emis[0].currency)}` : "")}
      ${kpi("Debt / assets", `${(s.totals.debt_ratio * 100).toFixed(1)}%`, "lower is healthier")}
    </div>
    <div class="toolbar" style="margin-top:16px"><span class="muted">Outstanding is computed from the amortisation schedule. If your bank statement differs (prepayments, rate resets), enter the actual outstanding when editing.</span><div class="spacer"></div><button class="btn primary" data-act="add-loan">+ Add loan / EMI</button></div>
    <div class="card">${L.length ? `<div class="table-wrap"><table><thead><tr><th>Loan</th><th>Owner</th><th class="num">Principal</th><th class="num">Rate</th><th class="num">EMI</th><th class="num">Outstanding</th><th>Progress</th><th>Next due</th><th>Ends</th><th></th></tr></thead><tbody>${body}</tbody></table></div>` : emptyState("No loans tracked.", "add-loan", "+ Add loan")}</div>
    <div class="card" style="margin-top:16px"><div class="card-h"><h3>EMI calculator</h3><span class="sub">what-if, nothing is saved</span></div>
      <div class="form-grid" style="grid-template-columns:repeat(4,1fr)">
        <div class="field"><label>Loan amount</label><input class="ctl calc" id="c-p" type="number" value="1000000"></div>
        <div class="field"><label>Interest rate (% p.a.)</label><input class="ctl calc" id="c-r" type="number" step="0.05" value="8.5"></div>
        <div class="field"><label>Tenure (years)</label><input class="ctl calc" id="c-n" type="number" value="20"></div>
        <div class="field"><label>Currency</label><select class="ctl calc" id="c-c">${state.meta.currencies.map((c) => `<option ${c === state.cur ? "selected" : ""}>${c}</option>`).join("")}</select></div>
      </div><div class="grid cols-3" style="margin-top:14px" id="calc-out"></div></div>`;
  },
  after() {
    const run = () => {
      const P = +$("#c-p").value || 0, r = (+$("#c-r").value || 0) / 1200, n = Math.round((+$("#c-n").value || 0) * 12), c = $("#c-c").value;
      const emi = n ? (r ? (P * r * (1 + r) ** n) / ((1 + r) ** n - 1) : P / n) : 0;
      $("#calc-out").innerHTML = `<div><div class="stat-label">Monthly EMI</div><div class="stat-value" style="font-size:22px">${fmt(emi, c)}</div></div>
        <div><div class="stat-label">Total interest</div><div class="stat-value" style="font-size:22px">${fmt(emi * n - P, c)}</div></div>
        <div><div class="stat-label">Total payable</div><div class="stat-value" style="font-size:22px">${fmt(emi * n, c)}</div></div>`;
    };
    $$(".calc").forEach((i) => i.addEventListener("input", run));
    run();
  },
};

async function showSchedule(id) {
  const r = await api(`/api/loans/${id}/schedule`);
  const c = r.loan.currency, today = new Date().toISOString().slice(0, 10);
  const years = {};
  r.schedule.forEach((row) => {
    const y = row.date.slice(0, 4);
    years[y] ??= { y, emi: 0, interest: 0, principal: 0, balance: 0 };
    years[y].emi += row.emi; years[y].interest += row.interest; years[y].principal += row.principal; years[y].balance = row.balance;
  });
  openModal(`Amortisation · ${esc(r.loan.name)}`, `
    <dl class="kv" style="grid-template-columns:repeat(4,auto 1fr);margin-bottom:16px">
      <dt>EMI</dt><dd>${fmt(r.status.emi, c)}</dd><dt>Outstanding</dt><dd>${fmt(r.status.outstanding, c)}</dd>
      <dt>Total interest</dt><dd>${fmt(r.status.total_interest, c)}</dd><dt>Ends</dt><dd>${fmtDate(r.status.end_date)}</dd></dl>
    <div class="chart-box sm"><canvas id="ch-amort"></canvas></div>
    <div class="legend" style="margin-top:8px"><span><i style="background:${cssVar("--s1")}"></i>Principal</span><span><i style="background:${cssVar("--s2")}"></i>Interest</span></div>
    <div class="table-wrap" style="margin-top:12px;max-height:340px;overflow:auto"><table><thead><tr><th>#</th><th>Date</th><th class="num">EMI</th><th class="num">Principal</th><th class="num">Interest</th><th class="num">Balance</th></tr></thead><tbody>
    ${r.schedule.map((x) => `<tr style="${x.date <= today ? "opacity:.55" : ""}"><td>${x.n}</td><td>${fmtDate(x.date)}</td><td class="num">${fmt(x.emi, c, { dp: 0 })}</td><td class="num">${fmt(x.principal, c, { dp: 0 })}</td><td class="num">${fmt(x.interest, c, { dp: 0 })}</td><td class="num">${fmt(x.balance, c, { dp: 0 })}</td></tr>`).join("")}
    </tbody></table></div>`, null, true);
  const ys = Object.values(years);
  mkChart("ch-amort", {
    type: "bar",
    data: { labels: ys.map((y) => y.y), datasets: [
      { label: "Principal", data: ys.map((y) => y.principal), backgroundColor: cssVar("--s1"), borderRadius: 2, stack: "a" },
      { label: "Interest", data: ys.map((y) => y.interest), backgroundColor: cssVar("--s2"), borderRadius: 2, stack: "a" }] },
    options: { maintainAspectRatio: false, interaction: { mode: "index", intersect: false },
      datasets: { bar: { borderColor: cssVar("--surface-1"), borderWidth: { top: 1 } } },
      scales: { x: { stacked: true, grid: { display: false }, ticks: { maxTicksLimit: 12 } }, y: { stacked: true, grid: { color: cssVar("--grid") }, border: { display: false }, ticks: { callback: (v) => fmt(v, c, { compact: true }) } } },
      plugins: { tooltip: { callbacks: { label: (x) => ` ${x.dataset.label}: ${fmt(x.raw, c)}` } } } },
  });
}

// ---------- Goals
VIEWS.goals = {
  render() {
    const G = state.summary.goals;
    return `<div class="toolbar"><span class="muted">Progress = "saved so far" + any assets or holdings you link to the goal. Required monthly saving assumes the expected return you set.</span><div class="spacer"></div><button class="btn primary" data-act="add-goal">+ Add goal</button></div>
    ${G.length ? `<div class="grid cols-3">${G.map((g) => `<div class="card">
      <div class="card-h"><h3>${esc(g.name)}</h3>${g.priority ? `<span class="tag">${esc(g.priority)}</span>` : ""}<div class="spacer"></div><button class="icon-btn" data-edit="goals:${g.id}">✎</button><button class="icon-btn" data-del="goals:${g.id}">✕</button></div>
      <div style="display:flex;align-items:baseline;gap:8px"><div class="stat-value" style="margin:0">${(g.progress * 100).toFixed(0)}%</div><span class="muted">of ${fmt(g.target, g.currency, { compact: true })}</span></div>
      <div class="bar ${g.progress >= 1 ? "good" : ""}" style="margin:12px 0"><span style="width:${Math.max(g.progress * 100, 1)}%"></span></div>
      <dl class="kv"><dt>Saved</dt><dd>${fmt(g.saved, g.currency)}</dd><dt>Remaining</dt><dd>${fmt(Math.max(g.target - g.saved, 0), g.currency)}</dd>
        <dt>Target date</dt><dd>${fmtDate(g.target_date)}${g.months_left ? ` · ${g.months_left} mo` : ""}</dd>
        <dt>Needed / month</dt><dd style="font-weight:620">${fmt(g.required_monthly, g.currency)}</dd>
        <dt>Assumed return</dt><dd>${numf(g.expected_return ?? 8)}% p.a.</dd><dt>Owner</dt><dd>${ownerChip(g.owner)}</dd>
        ${g.currency !== state.cur ? `<dt>In ${state.cur}</dt><dd>${fmt(g.saved_conv, state.cur, { compact: true })} / ${fmt(g.target_conv, state.cur, { compact: true })}</dd>` : ""}</dl>
    </div>`).join("")}</div>` : `<div class="card">${emptyState("No goals yet.", "add-goal", "+ Add goal")}</div>`}`;
  },
};

// ---------- Broker sync
const BROKER = {
  zerodha: { name: "Zerodha", key: "API key", secret: "API secret",
    steps: (uri) => `<ol class="steps">
      <li>Open <b>developers.kite.trade</b> → sign up with your Zerodha client ID.</li>
      <li><b>Create new app</b> → choose type <b>Personal</b> (free).</li>
      <li>App name: <i>Family Wealth</i>. Redirect URL: <code class="copy" data-copy="${uri}">${uri}</code> <span class="muted">(click to copy)</span>. Postback URL: leave empty.</li>
      <li>Copy the <b>API key</b> and <b>API secret</b> into the form here.</li>
      <li>Click <b>Log in</b>. Zerodha sessions end every morning (~6 AM IST), so tap <b>Log in</b> once a day; it auto-syncs after that.</li></ol>` },
  fyers: { name: "Fyers", key: "App ID (e.g. XXXX-100)", secret: "Secret ID",
    steps: (uri) => `<ol class="steps">
      <li>Open <b>myapi.fyers.in</b> → <b>Dashboard → Create App</b> (free).</li>
      <li>Redirect URL: <code class="copy" data-copy="${uri}">${uri}</code> <span class="muted">(click to copy)</span>. Permissions: tick <b>Transactions Info</b> / profile, holdings.</li>
      <li>Copy the <b>App ID</b> and <b>Secret ID</b> into the form here.</li>
      <li>Optional: add your 4-digit <b>Fyers PIN</b> — it lets the app renew the daily session on its own for ~15 days (stored encrypted in your vault).</li>
      <li>Click <b>Log in</b> once; after that it syncs automatically.</li></ol>` },
};
VIEWS.brokers = {
  render() {
    const C = state.data.connections || [];
    const badge = (c) => c.status === "ok" ? `<span class="tag ok">● Connected</span>` : c.status === "login_required" ? `<span class="tag warn">● Needs login</span>` : c.status === "error" ? `<span class="tag err">● Error</span>` : `<span class="tag">● Not logged in</span>`;
    return `<div class="toolbar"><span class="muted">Your Zerodha / Fyers holdings sync into Indian Stocks and Assets → Mutual Funds automatically every ${state.data.settings.broker_sync_minutes ?? 30} min. Uses your own free developer app; keys and tokens stay encrypted on this Mac.</span><div class="spacer"></div>
      ${C.some((c) => c.logged_in) ? `<button class="btn" data-act="sync-all">↻ Sync all</button>` : ""}<button class="btn primary" data-act="add-conn">+ Connect broker</button></div>
    ${C.length ? `<div class="grid cols-2e">${C.map((c) => `<div class="card">
      <div class="card-h"><div class="avatar" style="background:${c.broker === "zerodha" ? "#387ed1" : "#5b3fd1"};width:34px;height:34px;font-size:13px">${c.broker === "zerodha" ? "Z" : "F"}</div>
        <div><h3>${BROKER[c.broker].name}${c.label ? " · " + esc(c.label) : ""}</h3><div class="sub">${esc(c.account_name || "")} ${ownerChip(c.owner)}</div></div><div class="spacer"></div>${badge(c)}</div>
      <dl class="kv"><dt>Last sync</dt><dd>${c.last_sync ? ago(c.last_sync) : "never"}</dd>
        <dt>Synced</dt><dd>${c.last_counts ? `${c.last_counts.stocks} stocks · ${c.last_counts.funds} mutual funds` : "—"}</dd>
        <dt>Session</dt><dd>${c.logged_in ? (c.broker === "fyers" ? (c.has_pin ? "auto-renews with PIN" : "daily login (add PIN to auto-renew)") : "valid until ~6 AM IST") : "log in required"}</dd>
        <dt>API key</dt><dd>${esc(c.api_key)}</dd></dl>
      ${c.last_error && c.status !== "ok" ? `<div class="down" style="font-size:12.5px;margin-top:10px">⚠ ${esc(c.last_error)}</div>` : ""}
      ${(c.warnings || []).map((w) => `<div class="muted" style="font-size:12px;margin-top:6px">ℹ ${esc(w)}</div>`).join("")}
      <div class="toolbar" style="margin:14px 0 0">
        <button class="btn sm ${c.logged_in && c.status === "ok" ? "" : "primary"}" data-login="${c.id}">${c.logged_in ? "Re-login" : "Log in"}</button>
        ${c.logged_in ? `<button class="btn sm" data-act="sync-conn" data-id="${c.id}">↻ Sync now</button>` : ""}
        <div class="spacer"></div><button class="btn sm ghost" data-act="edit-conn" data-id="${c.id}">Edit</button><button class="btn sm ghost danger" data-act="del-conn" data-id="${c.id}">Remove</button></div>
    </div>`).join("")}</div>` : ""}
    <div class="grid cols-2e" style="margin-top:16px">
      ${["zerodha", "fyers"].map((b) => `<div class="card"><div class="card-h"><h3>Set up ${BROKER[b].name}</h3><span class="sub">one-time, ~3 minutes</span></div>${BROKER[b].steps(`http://127.0.0.1:${location.port || 8765}/connect/${b}`)}</div>`).join("")}
    </div>`;
  },
  after() {
    $$("code.copy").forEach((el) => el.addEventListener("click", () => { navigator.clipboard?.writeText(el.dataset.copy); toast("Redirect URL copied"); }));
  },
};

async function brokerLogin(id) {
  try { const r = await api(`/api/connections/${id}/login`, { method: "POST" }); location.href = r.url; }
  catch (e) { toast(e.message, "error"); }
}

function openConnForm(conn = null) {
  const b0 = conn?.broker || "zerodha";
  openModal(conn ? `Edit ${BROKER[b0].name} connection` : "Connect a broker", `<form id="conn-form" class="form-grid">
    <div class="field"><label>Broker</label><select class="ctl" name="broker" ${conn ? "disabled" : ""}><option value="zerodha" ${b0 === "zerodha" ? "selected" : ""}>Zerodha (Kite)</option><option value="fyers" ${b0 === "fyers" ? "selected" : ""}>Fyers</option></select></div>
    <div class="field"><label>Label</label><input class="ctl" name="label" value="${esc(conn?.label || "")}" placeholder="e.g. Akshay, Priya"></div>
    <div class="field"><label>Whose account?</label><select class="ctl" name="owner">${ownerOpts().map(([v, l]) => `<option value="${v}" ${(conn?.owner || defOwner()) === v ? "selected" : ""}>${esc(l)}</option>`).join("")}</select></div>
    <div class="field"><label>Auto-sync</label><select class="ctl" name="auto_sync"><option value="1">On</option><option value="0" ${conn?.auto_sync === "0" ? "selected" : ""}>Off (manual only)</option></select></div>
    <div class="field"><label id="k-label">${BROKER[b0].key}</label><input class="ctl" name="api_key" value="${esc(conn?.api_key_full || "")}" required autocomplete="off"></div>
    <div class="field"><label id="s-label">${BROKER[b0].secret}</label><input class="ctl" name="api_secret" type="password" ${conn ? `placeholder="•••••• (unchanged)"` : "required"} autocomplete="off"></div>
    <div class="field" id="pin-field" style="${b0 === "fyers" ? "" : "display:none"}"><label>Fyers PIN (optional)</label><input class="ctl" name="pin" type="password" inputmode="numeric" maxlength="6" placeholder="${conn?.has_pin ? "•••• (saved)" : "for automatic daily renewal"}" autocomplete="off"></div>
    <div class="field full hint">Redirect URL to register in the broker's developer console: <code id="redir">http://127.0.0.1:${location.port || 8765}/connect/${b0}</code></div>
  </form>`, async () => {
    const f = $("#conn-form");
    if (!f.reportValidity()) return false;
    const body = Object.fromEntries(new FormData(f));
    if (conn) body.broker = conn.broker;
    try {
      const r = conn ? await api(`/api/connections/${conn.id}`, { method: "PUT", body }) : await api("/api/connections", { method: "POST", body });
      await refresh();
      if (!conn || !r.logged_in) { toast("Saved — opening broker login…"); setTimeout(() => brokerLogin(r.id), 600); }
      else toast("Saved");
      return true;
    } catch (e) { toast(e.message, "error"); return false; }
  });
  const sel = $("#conn-form [name=broker]");
  sel.addEventListener("change", () => {
    const b = sel.value;
    $("#k-label").textContent = BROKER[b].key; $("#s-label").textContent = BROKER[b].secret;
    $("#pin-field").style.display = b === "fyers" ? "" : "none";
    $("#redir").textContent = `http://127.0.0.1:${location.port || 8765}/connect/${b}`;
  });
}

// ---------- Settings
VIEWS.settings = {
  render() {
    const f = fx(), st = state.data.settings;
    return `<div class="grid cols-2e">
      <div class="card"><div class="card-h"><h3>Exchange rates</h3><span class="sub">${esc(state.data.fx_updated || "defaults — please update")}</span></div>
        <form id="fx-form" class="form-grid">
          <div class="field"><label>AED per 1 USD</label><input class="ctl" name="AED" type="number" step="0.0001" value="${f.AED}"></div>
          <div class="field"><label>INR per 1 USD</label><input class="ctl" name="INR" type="number" step="0.0001" value="${f.INR}"></div>
          <div class="field full hint muted">Implied: 1 AED = ₹${(f.INR / f.AED).toFixed(3)} · 1 USD = AED ${f.AED}</div>
          <div class="field full" style="display:flex;gap:10px"><button class="btn primary" type="submit">Save rates</button><button class="btn" type="button" data-act="refresh-fx">↻ Fetch live rates</button></div>
        </form></div>
      <div class="card"><div class="card-h"><h3>Security</h3></div>
        <form id="sec-form" class="form-grid">
          <div class="field"><label>Auto-lock after (minutes idle, 0 = never)</label><input class="ctl" name="auto_lock_minutes" type="number" min="0" max="240" value="${st.auto_lock_minutes ?? 10}"></div>
          <div class="field"><label>Auto-update prices every (minutes, 0 = off)</label><input class="ctl" name="auto_refresh_minutes" type="number" min="0" max="1440" value="${st.auto_refresh_minutes ?? 15}"></div>
          <div class="field"><label>Broker sync every (minutes, 0 = off)</label><input class="ctl" name="broker_sync_minutes" type="number" min="0" max="1440" value="${st.broker_sync_minutes ?? 30}"></div>
          <div class="field"><label>Default currency</label><select class="ctl" name="base_currency">${state.meta.currencies.map((c) => `<option ${c === st.base_currency ? "selected" : ""}>${c}</option>`).join("")}</select></div>
          <div class="field full"><button class="btn" type="submit">Save</button></div>
        </form>
        <form id="pw-form" class="form-grid" style="margin-top:18px;border-top:1px solid var(--border);padding-top:18px">
          <div class="field"><label>Current password</label><input class="ctl" type="password" name="old" required autocomplete="current-password"></div>
          <div class="field"><label>New password (min 10)</label><input class="ctl" type="password" name="new" minlength="10" required autocomplete="new-password"></div>
          <div class="field full"><button class="btn" type="submit">Change master password</button></div>
        </form></div>
      <div class="card"><div class="card-h"><h3>Backup &amp; export</h3></div>
        <p class="sec" style="margin-top:0">The encrypted backup is safe to store on iCloud Drive or a USB stick — it can only be opened with your master password. Automatic encrypted backups (last 30) are also kept next to the vault.</p>
        <div class="toolbar"><a class="btn primary" href="/api/export/encrypted?t=${encodeURIComponent(state.token)}">⬇ Encrypted backup (.enc)</a></div>
        <p class="sec">CSV exports are <b>not encrypted</b> — handle with care.</p>
        <div class="toolbar">${["assets", "holdings", "loans", "goals"].map((c) => `<a class="btn sm" href="/api/export/csv/${c}?t=${encodeURIComponent(state.token)}">${c}.csv</a>`).join("")}</div></div>
      <div class="card"><div class="card-h"><h3>About this vault</h3></div>
        <dl class="kv"><dt>Encryption</dt><dd>AES-256-GCM, scrypt key derivation</dd><dt>Server</dt><dd>127.0.0.1 only</dd><dt>Price sources</dt><dd>Yahoo Finance · AMFI (mfapi.in) · open.er-api.com</dd>
          <dt>Members</dt><dd>${state.data.members.map((m) => esc(m.name)).join(", ")}</dd>
          <dt>Items</dt><dd>${state.data.assets.length} assets · ${state.data.holdings.length} holdings · ${state.data.loans.length} loans · ${state.data.goals.length} goals</dd></dl>
        <p class="muted" style="font-size:12px">Restore: quit the app, replace <code>vault.enc</code> in the data folder with a backup file, start again. The data folder path is printed when the app starts.</p></div>
    </div>`;
  },
  after() {
    $("#fx-form").addEventListener("submit", async (e) => { e.preventDefault(); const f = new FormData(e.target);
      try { await api("/api/fx", { method: "PUT", body: { AED: f.get("AED"), INR: f.get("INR") } }); toast("Rates saved"); await refresh(); } catch (err) { toast(err.message, "error"); } });
    $("#sec-form").addEventListener("submit", async (e) => { e.preventDefault(); const f = new FormData(e.target);
      try { await api("/api/settings", { method: "PUT", body: Object.fromEntries(f) }); toast("Settings saved"); await refresh(); } catch (err) { toast(err.message, "error"); } });
    $("#pw-form").addEventListener("submit", async (e) => { e.preventDefault(); const f = new FormData(e.target);
      try { await api("/api/change-password", { method: "POST", body: Object.fromEntries(f) }); toast("Password changed"); e.target.reset(); } catch (err) { toast(err.message, "error"); } });
  },
};

// ------------------------------------------------------------------ forms
const ownerOpts = () => [...state.data.members.map((m) => [m.id, m.name]), ["joint", "Joint (shared equally)"]];
const curOpts = () => state.meta.currencies.map((c) => [c, c]);
const goalOpts = () => [["", "— none —"], ...state.data.goals.map((g) => [g.id, g.name])];
const defOwner = () => (state.who !== "family" ? state.who : state.data.members[0].id);

const FORMS = {
  assets: () => {
    const M = ["market"], NAV = ["nav"], ACC = ["accrual"], APP = ["appreciation"], MAN = ["manual"];
    return [
      ["name", "Name", "text", { req: true, ph: "e.g. SBI FD, 22K gold jewellery, Parag Parikh Flexi Cap", full: true }],
      ["asset_class", "Asset class", "select", { opts: Object.entries(state.meta.asset_classes).filter(([k]) => !k.startsWith("stocks_")) }],
      ["method", "How is it valued?", "select", { opts: Object.entries(state.meta.valuation_methods), def: "manual" }],
      ["owner", "Owner", "select", { opts: ownerOpts(), def: defOwner() }],
      ["currency", "Currency", "select", { opts: curOpts(), def: state.cur }],
      // manual
      ["value", "Current value", "number", { show: MAN, creq: true }],
      // market
      ["symbol", "Symbol", "text", { show: M, creq: true, ph: "XAU, XAG, BTC-USD, VOO …", check: "market",
        hint: "XAU = gold / gram · XAG = silver / gram · crypto like BTC-USD, ETH-USD · any Yahoo Finance ticker" }],
      ["units", "Quantity", "number", { show: [...M, ...NAV], creq: true, hint: "grams for gold/silver · units for funds · coins for crypto" }],
      ["purity", "Gold purity (karat)", "select", { show: M, opts: [["24", "24K"], ["22", "22K"], ["21", "21K"], ["18", "18K"]], def: "24", hint: "Only used for XAU" }],
      ["premium_pct", "Premium / discount %", "number", { show: M, step: "0.1", hint: "e.g. +9 for Indian retail gold (duty + GST)" }],
      // nav
      ["nav_symbol", "AMFI scheme code", "text", { show: NAV, creq: true, check: "nav", mfsearch: true, ph: "e.g. 122639" }],
      // accrual
      ["principal", "Principal / balance", "number", { show: ACC, creq: true, hint: "Amount deposited, or EPF/PPF balance as of the start date" }],
      ["rate", "Interest rate (% p.a.)", "number", { show: ACC, creq: true, step: "0.01", hint: "EPF 8.25 · PPF 7.1 · check your FD receipt" }],
      ["start_date", "Start date", "date", { show: ACC, creq: true }],
      ["maturity_date", "Maturity date (optional)", "date", { show: ACC, hint: "Value stops growing after this" }],
      ["compounding", "Compounding", "select", { show: ACC, opts: [["4", "Quarterly (most Indian FDs)"], ["12", "Monthly"], ["2", "Half-yearly"], ["1", "Yearly (EPF/PPF)"], ["0", "Simple interest"]], def: "4" }],
      // appreciation
      ["base_value", "Value on reference date", "number", { show: APP, creq: true, hint: "Purchase price or last valuation" }],
      ["base_date", "Reference date", "date", { show: APP, creq: true }],
      ["growth_pct", "Yearly change %", "number", { show: APP, step: "0.1", def: 5, hint: "+5 for property · −15 for a car" }],
      // common
      ["invested", "Amount invested (optional)", "number", { hint: "Cost basis, to show gain" }],
      ["institution", "Bank / institution", "text"],
      ["goal_id", "Earmarked for goal", "select", { opts: goalOpts() }],
      ["notes", "Notes", "textarea", { full: true }],
    ];
  },
  holdings: (mk) => {
    const M = state.meta.markets[mk];
    return [
      ["symbol", "Symbol / ticker", "text", { req: true, check: "holding", ph: mk === "IN" ? "HDFCBANK" : mk === "US" ? "AAPL" : "EMAAR", hint: mk === "IN" ? "NSE/BSE trading symbol, e.g. HDFCBANK, RELIANCE, TCS" : "" }],
      ["exchange", "Exchange", "select", { opts: M.exchanges.map((e) => [e, e]) }],
      ["name", "Company name", "text", { full: true }],
      ["qty", "Quantity", "number", { req: true, step: "any" }],
      ["avg_cost", "Average buy price", "number", { req: true, step: "any" }],
      ["price", "Current price (LTP)", "number", { step: "any", hint: "Filled automatically — Check fills it now" }],
      ["currency", "Price currency", "select", { opts: curOpts(), def: M.currency }],
      ["owner", "Owner", "select", { opts: ownerOpts(), def: defOwner() }],
      ["sector", "Sector", "text"],
      ["quote_symbol", "Yahoo symbol override", "text", { hint: mk === "IN" ? "Auto: SYMBOL.NS / .BO" : mk === "UAE" ? "Auto: SYMBOL.AE — override if a fetch fails" : "Auto: same as ticker" }],
      ["goal_id", "Earmarked for goal", "select", { opts: goalOpts() }],
      ["notes", "Notes", "textarea", { full: true }],
    ];
  },
  loans: () => [
    ["name", "Name", "text", { req: true, ph: "e.g. Kochi flat home loan", full: true }],
    ["loan_type", "Type", "select", { opts: Object.entries(state.meta.loan_types), hint: "Credit card: principal 0, enter the due amount as Actual outstanding" }],
    ["lender", "Lender", "text"],
    ["owner", "Borrower", "select", { opts: ownerOpts(), def: defOwner() }],
    ["currency", "Currency", "select", { opts: curOpts(), def: state.cur }],
    ["principal", "Loan amount (principal)", "number", { req: true }],
    ["rate", "Interest rate (% p.a.)", "number", { step: "0.01", def: 0 }],
    ["tenure_months", "Tenure (months)", "number", { hint: "20 years = 240", def: 0 }],
    ["start_date", "First EMI date", "date", { req: true }],
    ["emi_override", "EMI amount (optional)", "number", { hint: "Leave blank to auto-calculate" }],
    ["outstanding_override", "Actual outstanding (optional)", "number", { hint: "From your bank statement — overrides the schedule" }],
    ["prepaid", "Total prepayments made", "number", { hint: "Reduces computed outstanding" }],
    ["notes", "Notes", "textarea", { full: true }],
  ],
  goals: () => [
    ["name", "Goal", "text", { req: true, ph: "e.g. Kids' education, House in Kerala", full: true }],
    ["owner", "For", "select", { opts: ownerOpts(), def: "joint" }],
    ["currency", "Currency", "select", { opts: curOpts(), def: state.cur }],
    ["target", "Target amount", "number", { req: true }],
    ["target_date", "Target date", "date", { req: true }],
    ["current_saved", "Saved so far (not in linked assets)", "number", { def: 0 }],
    ["expected_return", "Expected return (% p.a.)", "number", { def: 8, step: "0.1" }],
    ["priority", "Priority", "select", { opts: [["High", "High"], ["Medium", "Medium"], ["Low", "Low"]], def: "Medium" }],
    ["notes", "Notes", "textarea", { full: true }],
  ],
  members: () => [
    ["name", "Name", "text", { req: true, full: true }],
    ["color", "Colour", "select", { opts: [["#3987e5", "Blue"], ["#d95926", "Orange"], ["#199e70", "Aqua"], ["#c98500", "Yellow"], ["#d55181", "Magenta"], ["#9085e9", "Violet"]] }],
  ],
};

function fieldHtml([k, label, type, o = {}], item) {
  let v = item ? item[k] ?? "" : o.def ?? "";
  if (k === "nav_symbol" && item) v = item.method === "nav" ? item.symbol || "" : "";
  const common = `name="${k}" class="ctl" ${o.req ? "required" : ""}`;
  let input;
  if (type === "select") input = `<select ${common}>${o.opts.map(([val, lab]) => `<option value="${esc(val)}" ${String(val) === String(v) ? "selected" : ""}>${esc(lab)}</option>`).join("")}</select>`;
  else if (type === "textarea") input = `<textarea ${common} rows="2">${esc(v)}</textarea>`;
  else input = `<input ${common} type="${type}" ${type === "number" ? `step="${o.step || "any"}"` : ""} value="${esc(v)}" placeholder="${esc(o.ph || "")}">`;
  if (o.check) input = `<div style="display:flex;gap:6px">${input}<button type="button" class="btn sm" data-check="${o.check}" data-for="${k}">Check</button></div><div class="hint check-out" data-out="${k}"></div>`;
  const search = o.mfsearch ? `<input class="ctl" id="mf-q" placeholder="🔍 Search fund name, e.g. parag parikh flexi direct growth" style="margin-bottom:6px"><div id="mf-results" class="mf-results"></div>` : "";
  return `<div class="field ${o.full || o.mfsearch ? "full" : ""}" ${o.show ? `data-show="${o.show.join(",")}"` : ""} ${o.creq ? "data-creq" : ""}><label>${label}</label>${search}${input}${o.hint ? `<div class="hint">${o.hint}</div>` : ""}</div>`;
}

function openForm(coll, item = null, extra = {}) {
  const mk = item?.market || extra.market;
  const fields = coll === "holdings" ? FORMS.holdings(mk) : FORMS[coll]();
  const noun = { assets: "asset", holdings: `${state.meta.markets[mk]?.label.replace(/s$/, "") || "holding"}`, loans: "loan", goals: "goal", members: "family member" }[coll];
  const syncNote = item?.source ? `<div class="banner" style="margin-bottom:14px">⇆ Synced from ${esc(item.source_label || "broker")}. Quantity, cost and price are overwritten on every sync — goal and notes are kept.</div>` : "";
  openModal(`${item ? "Edit" : "Add"} ${noun}`, syncNote + `<form id="item-form" class="form-grid" data-market="${esc(mk || "")}">${fields.map((f) => fieldHtml(f, item)).join("")}</form>`, async () => {
    const form = $("#item-form");
    if (!form.reportValidity()) return false;
    const missing = $$(".field[data-creq]", form).filter((f) => f.style.display !== "none" && !$("[name]", f)?.value?.trim());
    if (missing.length) { toast(`Please fill: ${missing.map((f) => $("label", f).textContent).join(", ")}`, "error"); return false; }
    const body = Object.fromEntries(new FormData(form));
    if (coll === "holdings") body.market = mk;
    if (coll === "assets") {
      if (body.method === "nav") body.symbol = body.nav_symbol;
      delete body.nav_symbol;
      $$(".field[data-show]", form).forEach((f) => { if (f.style.display === "none") { const el = $("[name]", f); if (el && el.name !== "nav_symbol" && !(el.name === "symbol" && body.method === "nav")) delete body[el.name]; } });
    }
    try {
      if (item) await api(`/api/${coll}/${item.id}`, { method: "PUT", body });
      else await api(`/api/${coll}`, { method: "POST", body });
      toast(item ? "Saved" : "Added");
      if (coll === "members") { await loadAll(); renderShell(); } else await refresh();
      if ((coll === "assets" && ["market", "nav"].includes(body.method)) || coll === "holdings") {
        toast("Fetching live price…");
        [4000, 10000].forEach((ms) => setTimeout(() => { if (state.token && !$("#modal-root").children.length) refresh().catch(() => {}); }, ms));
      }
      return true;
    } catch (e) { toast(e.message, "error"); return false; }
  });
  wireForm(coll, item);
}

function wireForm(coll, item) {
  const form = $("#item-form");
  if (!form) return;
  const method = form.elements.method;
  const toggle = () => {
    if (!method) return;
    $$(".field[data-show]", form).forEach((f) => { f.style.display = f.dataset.show.split(",").includes(method.value) ? "" : "none"; });
    if (method.value === "market" && form.elements.asset_class.value === "gold" && !form.elements.symbol.value) form.elements.symbol.value = "XAU";
  };
  if (method) {
    method.addEventListener("change", toggle);
    form.elements.asset_class.addEventListener("change", () => {
      if (!item) { method.value = state.meta.default_method[form.elements.asset_class.value] || "manual"; }
      if (form.elements.asset_class.value === "vehicle" && !item) form.elements.growth_pct.value = -15;
      if (form.elements.asset_class.value === "real_estate" && !item) form.elements.growth_pct.value = 5;
      if (form.elements.asset_class.value === "retirement" && !item) form.elements.compounding.value = "1";
      toggle();
    });
    if (!item) form.elements.asset_class.dispatchEvent(new Event("change")); else toggle();
  }
  // live "Check" buttons
  $$("[data-check]", form).forEach((b) => b.addEventListener("click", async () => {
    const input = form.elements[b.dataset.for], out = $(`[data-out="${b.dataset.for}"]`, form);
    if (!input.value) return;
    out.textContent = "Checking…"; out.className = "hint check-out";
    let url = `/api/quote/test?kind=${b.dataset.check}&symbol=${encodeURIComponent(input.value)}`;
    if (b.dataset.check === "holding") url += `&market=${form.dataset.market}&exchange=${encodeURIComponent(form.elements.exchange.value)}&override=${encodeURIComponent(form.elements.quote_symbol.value)}`;
    const r = await api(url).catch((e) => ({ ok: false, error: e.message }));
    if (r.ok) {
      out.innerHTML = `✓ ${r.looked_up && b.dataset.check === "holding" ? esc(r.looked_up) + " · " : ""}${esc(r.name)} · <b>${r.ccy} ${numf(r.price, 4)}</b>${r.nav_date ? ` (NAV ${esc(r.nav_date)})` : ""}`;
      if (b.dataset.check === "holding" && form.elements.price && !item) form.elements.price.value = +r.price.toFixed(4);
      out.className = "hint check-out up";
      if (form.elements.name && !form.elements.name.value && !["XAU", "XAG"].includes(input.value.trim().toUpperCase())) form.elements.name.value = r.name;
      if (form.elements.currency && ["AED", "INR", "USD"].includes(r.ccy) && !item && coll === "assets" && b.dataset.check === "nav") form.elements.currency.value = r.ccy;
    } else { out.textContent = `✕ ${r.looked_up ? r.looked_up + ": " : ""}${r.error}`; out.className = "hint check-out down"; }
  }));
  // mutual fund search
  const q = $("#mf-q", form);
  if (q) {
    let timer;
    q.addEventListener("input", () => {
      clearTimeout(timer);
      timer = setTimeout(async () => {
        const box = $("#mf-results", form);
        if (q.value.trim().length < 3) { box.innerHTML = ""; return; }
        box.innerHTML = `<div class="muted" style="padding:6px">Searching…</div>`;
        try {
          const rs = await api(`/api/mf/search?q=${encodeURIComponent(q.value.trim())}`);
          box.innerHTML = rs.length ? rs.map((r) => `<button type="button" class="mf-item" data-code="${esc(r.code)}" data-name="${esc(r.name)}"><b>${esc(r.code)}</b> ${esc(r.name)}</button>`).join("") : `<div class="muted" style="padding:6px">No matches</div>`;
        } catch (e) { box.innerHTML = `<div class="down" style="padding:6px">${esc(e.message)}</div>`; }
      }, 350);
    });
    $("#mf-results", form).addEventListener("click", (e) => {
      const it = e.target.closest(".mf-item");
      if (!it) return;
      form.elements.nav_symbol.value = it.dataset.code;
      if (!form.elements.name.value) form.elements.name.value = it.dataset.name;
      form.elements.currency.value = "INR";
      $("#mf-results", form).innerHTML = ""; q.value = "";
      $('[data-check="nav"]', form).click();
    });
  }
}

function openModal(title, body, onSave, wide = false) {
  $("#modal-root").innerHTML = `<div class="modal-bg"><div class="modal ${wide ? "wide" : ""}" role="dialog" aria-modal="true">
    <div class="modal-h"><h2>${title}</h2><button class="icon-btn" data-close>✕</button></div>
    <div class="modal-b">${body}</div>
    ${onSave ? `<div class="modal-f"><button class="btn" data-close>Cancel</button><button class="btn primary" id="modal-save">Save</button></div>` : ""}
  </div></div>`;
  $$("[data-close]", $("#modal-root")).forEach((b) => b.addEventListener("click", closeModal));
  $(".modal-bg").addEventListener("mousedown", (e) => { if (e.target.classList.contains("modal-bg")) closeModal(); });
  if (onSave) {
    const save = async () => { $("#modal-save").disabled = true; if (await onSave()) closeModal(); else if ($("#modal-save")) $("#modal-save").disabled = false; };
    $("#modal-save").addEventListener("click", save);
    $("#item-form")?.addEventListener("submit", (e) => { e.preventDefault(); save(); });
    $("#modal-root input")?.focus();
  }
}
function closeModal() { $("#modal-root").innerHTML = ""; }
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeModal(); });

// ------------------------------------------------------------------ actions
function bindActions(root) {
  root.addEventListener("click", async (e) => {
    const t = e.target.closest("[data-act],[data-edit],[data-del],[data-nav-go],[data-schedule],[data-who],[data-login]");
    if (!t) return;
    if (t.dataset.navGo) { location.hash = t.dataset.navGo; return; }
    if (t.dataset.login) return brokerLogin(t.dataset.login);
    if (t.dataset.who) { state.who = t.dataset.who; localStorage.setItem("fw_who", state.who); $("#who-select").value = state.who; location.hash = "dashboard"; await refresh(); return; }
    if (t.dataset.schedule) return showSchedule(t.dataset.schedule);
    if (t.dataset.edit) {
      const [coll, id] = t.dataset.edit.split(":");
      return openForm(coll, state.data[coll].find((x) => x.id === id));
    }
    if (t.dataset.del) {
      const [coll, id] = t.dataset.del.split(":");
      const it = state.data[coll].find((x) => x.id === id);
      return openModal("Delete?", `<p>Delete <b>${esc(it?.name || it?.symbol || "this item")}</b>? This cannot be undone (but an encrypted backup from the last hour exists).</p>`, async () => {
        try { await api(`/api/${coll}/${id}`, { method: "DELETE" }); toast("Deleted"); if (coll === "members") { await loadAll(); renderShell(); } else await refresh(); return true; }
        catch (err) { toast(err.message, "error"); return false; }
      });
    }
    const act = t.dataset.act;
    if (act === "add-asset") openForm("assets");
    if (act === "add-holding") openForm("holdings", null, { market: t.dataset.market || state.view.split("-")[1] });
    if (act === "add-loan") openForm("loans");
    if (act === "add-goal") openForm("goals");
    if (act === "add-member") openForm("members");
    if (act === "add-conn") openConnForm();
    if (act === "edit-conn") openConnForm(state.data.connections.find((c) => c.id === t.dataset.id));
    if (act === "del-conn") {
      const c = state.data.connections.find((x) => x.id === t.dataset.id);
      openModal("Remove connection?", `<p>Remove <b>${BROKER[c.broker].name}${c.label ? " · " + esc(c.label) : ""}</b>? Its keys and tokens are deleted from the vault.</p>
        <label style="display:flex;gap:8px;align-items:center"><input type="checkbox" id="purge"> Also delete the holdings and funds it synced</label>
        <p class="muted" style="font-size:12.5px">If unticked, they stay as normal manual items.</p>`, async () => {
        try { await api(`/api/connections/${c.id}?purge=${$("#purge").checked ? 1 : 0}`, { method: "DELETE" }); toast("Removed"); await refresh(); return true; }
        catch (e) { toast(e.message, "error"); return false; }
      });
    }
    if (act === "sync-conn" || act === "sync-all") {
      t.disabled = true; t.textContent = "Syncing…";
      try {
        const rs = act === "sync-all" ? await api("/api/connections/sync-all", { method: "POST" }) : [await api(`/api/connections/${t.dataset.id}/sync`, { method: "POST" })];
        const bad = rs.filter((r) => !r.ok);
        toast(bad.length ? bad.map((r) => r.error).join(" · ") : `Synced ${rs.reduce((a, r) => a + (r.stocks || 0), 0)} stocks, ${rs.reduce((a, r) => a + (r.funds || 0), 0)} funds`, bad.length ? "error" : "ok");
      } catch (e) { toast(e.message, "error"); }
      await refresh();
    }
    if (act === "refresh-prices") {
      t.disabled = true; t.textContent = "Fetching…";
      try {
        const r = await api("/api/prices/refresh", { method: "POST", body: { market: t.dataset.market } });
        const br = (r.broker || []).filter((x) => x.ok).reduce((a, x) => a + (x.stocks || 0), 0);
        const brErr = (r.broker || []).filter((x) => !x.ok).map((x) => x.error);
        toast([br ? `${br} stocks refreshed from broker` : "", r.updated ? `${r.updated} from market data` : "", r.failed?.length ? `not found: ${r.failed.join(", ")}` : "", ...brErr].filter(Boolean).join(" · ") || "Up to date", r.failed?.length || brErr.length ? "error" : "ok");
        await refresh();
      } catch (err) { toast(err.message, "error"); t.disabled = false; t.textContent = "↻ Refresh prices"; }
    }
    if (act === "refresh-fx") {
      try { await api("/api/fx/refresh", { method: "POST" }); toast("Live rates loaded"); await refresh(); } catch (err) { toast(err.message, "error"); }
    }
  });
}

document.addEventListener("DOMContentLoaded", boot);
