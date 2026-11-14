/* Family Wealth — Insights, loan check and local AI advisor (Ollama). */
"use strict";

state.chat = [];
state.chatBusy = false;
state.loan = null;

// ------------------------------------------------------------------ tiny safe markdown
function md(src) {
  src = String(src).replace(/<think>[\s\S]*?(<\/think>|$)/g, "");
  const inline = (s) => s.replace(/\*\*(.+?)\*\*/g, "<b>$1</b>").replace(/`([^`]+)`/g, "<code>$1</code>");
  let out = "", list = null, pre = false;
  const close = () => { if (list) { out += `</${list}>`; list = null; } };
  for (const raw of esc(src).split("\n")) {
    if (raw.trim().startsWith("```")) { close(); out += pre ? "</pre>" : "<pre>"; pre = !pre; continue; }
    if (pre) { out += raw + "\n"; continue; }
    const l = raw.trim();
    let m;
    if (!l) { close(); continue; }
    if ((m = l.match(/^#{1,4}\s+(.*)/))) { close(); out += `<p class="md-h">${inline(m[1])}</p>`; }
    else if ((m = l.match(/^[-*•]\s+(.*)/))) { if (list !== "ul") { close(); out += "<ul>"; list = "ul"; } out += `<li>${inline(m[1])}</li>`; }
    else if ((m = l.match(/^\d+[.)]\s+(.*)/))) { if (list !== "ol") { close(); out += "<ol>"; list = "ol"; } out += `<li>${inline(m[1])}</li>`; }
    else if (l.startsWith("|")) { close(); if (!/^\|[\s:|-]+\|?$/.test(l)) out += `<p class="md-row">${inline(l.replace(/^\||\|$/g, "").split("|").map((x) => x.trim()).join(" · "))}</p>`; }
    else { close(); out += `<p>${inline(l)}</p>`; }
  }
  close();
  return out + (pre ? "</pre>" : "");
}

const LEVEL = { good: ["✓", "Good", "ok"], warn: ["!", "Watch", "warn"], bad: ["✕", "Act", "err"], info: ["i", "Note", ""] };
const HEALTH_TAG = { Strong: ["▲", "ok"], Neutral: ["●", ""], Weak: ["▼", "warn"], Poor: ["✕", "err"] };
const p0 = (x) => (x == null ? "—" : `${(x * 100).toFixed(0)}%`);
const sp = (x) => (x == null ? "—" : `<span class="${signCls(x)}">${pct(x, 0)}</span>`);

// ------------------------------------------------------------------ Insights
VIEWS.insights = {
  render() { return `<div id="ins-root"><div class="card empty">Analysing your portfolio…</div></div>`; },
  async after() {
    let d;
    try { d = await api(`/api/insights?currency=${state.cur}&who=${state.who}`); } catch { return; }
    const root = $("#ins-root");
    if (!root || state.view !== "insights") return;
    const r = d.ratios, S = d.stocks, cur = state.cur, h = d.health;
    const scored = S.filter((x) => x.score != null);
    const healthNote = h.running ? `Fetching 1-year price history… ${h.done}/${h.total}`
      : h.error ? `⚠ Market history paused: ${esc(h.error)}`
      : S.length ? `Market health for ${scored.length} of ${S.length} holdings` : "";
    const kp = (label, val, sub) => kpi(label, val, sub);
    root.innerHTML = `
    <div class="toolbar"><span class="muted">Stock health is judged on how the stock itself has performed against its index over the past year, not on what you paid for it. ${healthNote}</span><div class="spacer"></div>
      <button class="btn" id="ins-refresh" ${h.running ? "disabled" : ""}>↻ Re-analyse stocks</button><button class="btn primary" data-nav-go="advisor">✦ Ask the AI advisor</button></div>
    <div class="grid kpis">
      ${kp("Emergency cover", r.emergency_months != null ? `${r.emergency_months.toFixed(1)} mo` : "—", r.cashflow_set ? "cash + deposits ÷ monthly outgo" : `<a href="#settings">add income &amp; expenses</a>`)}
      ${kp("EMIs / income", r.foir != null ? p0(r.foir) : "—", "comfortable under 30%")}
      ${kp("Savings rate", r.savings_rate != null ? p0(r.savings_rate) : "—", r.surplus != null ? `${fmt(r.surplus, cur, { compact: true })} left each month` : "")}
      ${kp("Largest holding", r.top_holding ? p0(r.top_holding.weight) : "—", r.top_holding ? `${esc(r.top_holding.symbol)} · share of stocks` : "")}
      ${kp("Lagging stocks", r.weak ? `${r.weak.count} of ${r.weak.scored}` : "—", r.weak ? `${p0(r.weak.share)} of stocks by value` : "needs market history")}
    </div>
    <div class="grid cols-2e" style="margin-top:16px">
      <div class="card"><div class="card-h"><h3>What stands out</h3><span class="sub">${state.who === "family" ? "family" : esc(memberName(state.who))}</span></div>
        <div class="list">${d.findings.map((f) => `<div class="list-row" style="align-items:flex-start"><span class="tag ${LEVEL[f.level][2]}" style="min-width:64px;text-align:center">${LEVEL[f.level][0]} ${LEVEL[f.level][1]}</span><div class="grow"><div class="t" style="white-space:normal">${esc(f.title)}</div><div class="s" style="font-size:12.5px">${esc(f.detail)}</div></div></div>`).join("") || `<div class="empty">Nothing to flag yet.</div>`}</div></div>
      <div class="card"><div class="card-h"><h3>1-year return vs index</h3><span class="sub">percentage points ahead or behind</span></div>
        ${scored.some((x) => x.excess_1y != null) ? `<div class="chart-box" style="height:${Math.max(scored.filter((x) => x.excess_1y != null).length, 4) * 24 + 50}px"><canvas id="ch-excess" aria-label="One-year return versus index, by holding"></canvas></div>`
          : `<div class="empty" style="padding:70px 10px">${h.running ? "Price history is loading…" : "No market history yet. Click Re-analyse stocks."}</div>`}</div>
    </div>
    <div class="card" style="margin-top:16px"><div class="card-h"><h3>Stock health</h3><span class="sub">weakest first · score 0–100</span></div>
      ${S.length ? `<div class="table-wrap"><table><thead><tr><th>Stock</th><th class="num">Weight</th><th class="num">Your P&amp;L</th><th class="num">1Y return</th><th class="num">vs index</th><th class="num">From 52w high</th><th>Trend</th><th>Health</th><th>Why</th></tr></thead><tbody>
        ${S.map((x) => { const tg = HEALTH_TAG[x.label] || ["?", ""]; return `<tr>
          <td><b>${esc(x.symbol)}</b> <span class="flag">${x.market}</span></td><td class="num">${p0(x.weight)}</td><td class="num">${sp(x.pnl_pct)}</td>
          <td class="num">${sp(x.ret_1y)}</td><td class="num">${x.excess_1y != null ? `<span class="${signCls(x.excess_1y)}">${pct(x.excess_1y, 0).replace("%", " pts")}</span>` : "—"}</td>
          <td class="num">${x.from_high != null ? `−${Math.abs(x.from_high * 100).toFixed(0)}%` : "—"}</td>
          <td>${x.above_200 == null ? "—" : x.above_200 ? "↑ above 200-day" : "↓ below 200-day"}</td>
          <td>${x.score != null ? `<div class="score"><div class="bar"><span style="width:${x.score}%"></span></div><b>${x.score}</b> <span class="tag ${tg[1]}">${tg[0]} ${x.label}</span></div>` : `<span class="muted">${esc(x.error || "pending")}</span>`}</td>
          <td class="muted" style="white-space:normal;min-width:220px;font-size:12.5px">${esc(x.why.join(" · "))}</td></tr>`; }).join("")}
        </tbody></table></div>` : `<div class="empty">No stock holdings yet.</div>`}</div>`;
    $("#ins-refresh").addEventListener("click", async () => { await api("/api/insights/refresh", { method: "POST" }); toast("Re-analysing — this takes a minute or two"); setTimeout(() => state.view === "insights" && renderView(), 2500); });
    if (h.running) setTimeout(() => state.view === "insights" && !$("#modal-root").children.length && renderView(), 6000);
    const ex = scored.filter((x) => x.excess_1y != null).sort((a, b) => b.excess_1y - a.excess_1y);
    mkChart("ch-excess", {
      type: "bar",
      data: { labels: ex.map((x) => x.symbol), datasets: [{ data: ex.map((x) => x.excess_1y * 100), backgroundColor: ex.map((x) => (x.excess_1y >= 0 ? cssVar("--s1") : cssVar("--s8"))), borderRadius: 4, barThickness: 12 }] },
      options: { indexAxis: "y", maintainAspectRatio: false,
        scales: { x: { grid: { color: cssVar("--grid") }, border: { display: false }, ticks: { callback: (v) => `${v > 0 ? "+" : ""}${v}`, maxTicksLimit: 6 } }, y: { grid: { display: false }, ticks: { color: cssVar("--text-secondary"), autoSkip: false } } },
        plugins: { tooltip: { callbacks: { label: (c) => ` ${c.raw >= 0 ? "Ahead of" : "Behind"} index by ${Math.abs(c.raw).toFixed(0)} pts` } } } },
    });
  },
};

// ------------------------------------------------------------------ AI advisor
const PROMPTS = ["How healthy is our overall financial position?", "Which holdings look weakest, and what should I consider doing?", "Where am I too concentrated?", "Are we on track for our goals?"];

function chatHtml() {
  if (!state.chat.length) return `<div class="empty" style="padding:30px 10px">Ask anything about your finances. The AI sees your holdings, loans, goals and the app's calculations, and runs entirely on this Mac.</div>`;
  return state.chat.map((m) => `<div class="msg ${m.role}"><div class="who">${m.role === "user" ? "You" : "✦ Advisor"}</div><div class="body">${m.role === "user" ? esc(m.content) : md(m.content) || `<span class="muted">Thinking…</span>`}</div></div>`).join("");
}
function paintChat(scroll = true) {
  const log = $("#chat-log");
  if (!log) return;
  log.innerHTML = chatHtml();
  if (scroll) log.scrollTop = log.scrollHeight;
  const b = $("#chat-send"); if (b) b.disabled = state.chatBusy;
}

async function sendChat(text, extra = {}) {
  if (state.chatBusy || !text.trim()) return;
  state.chat.push({ role: "user", content: text.trim() });
  const reply = { role: "assistant", content: "" };
  const history = state.chat.map(({ role, content }) => ({ role, content }));
  state.chat.push(reply);
  state.chatBusy = true; paintChat();
  try {
    const res = await fetch("/api/ai/chat", { method: "POST", headers: { "Content-Type": "application/json", "X-Session": state.token || "" },
      body: JSON.stringify({ messages: history, currency: state.cur, ...extra }) });
    if (res.status === 401) { lockUI(); return; }
    if (!res.ok) throw new Error((await res.json().catch(() => ({}))).detail || `Error ${res.status}`);
    const reader = res.body.getReader(), dec = new TextDecoder();
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      reply.content += dec.decode(value, { stream: true });
      const el = $("#chat-log .msg:last-child .body");
      if (el) { el.innerHTML = md(reply.content); $("#chat-log").scrollTop = $("#chat-log").scrollHeight; }
    }
  } catch (e) { reply.content += `\n\n⚠ ${e.message}`; }
  state.chatBusy = false; paintChat();
}

function loanHtml(k) {
  const c = k.currency, V = { ok: ["✓ Looks affordable", "ok"], caution: ["! Proceed with caution", "warn"], avoid: ["✕ Not advisable as it stands", "err"], unknown: ["? Need your income to judge", ""] }[k.verdict];
  const row = (l, a, b) => `<tr><td>${l}</td><td class="num">${a}</td><td class="num"><b>${b}</b></td></tr>`;
  const mo = (x) => (x == null ? "—" : `${x.toFixed(1)} mo`);
  return `<div class="verdict ${V[1]}">${V[0]}</div>
    <dl class="kv" style="margin:12px 0"><dt>New EMI</dt><dd><b>${fmt(k.emi, c)}</b> / month</dd><dt>Total interest</dt><dd>${fmt(k.total_interest, c)}</dd><dt>Total payable</dt><dd>${fmt(k.total_payable, c)}</dd>
      ${k.max_loan != null ? `<dt>Most you could borrow at 40% EMI/income</dt><dd>${fmt(k.max_loan, c, { compact: true })}</dd>` : ""}</dl>
    <table><thead><tr><th></th><th class="num">Now</th><th class="num">With loan</th></tr></thead><tbody>
      ${row("Monthly EMIs", fmt(k.before.emis, c), fmt(k.after.emis, c))}${row("EMIs / income", p0(k.before.foir), p0(k.after.foir))}
      ${row("Monthly surplus", k.before.surplus == null ? "—" : fmt(k.before.surplus, c), k.after.surplus == null ? "—" : fmt(k.after.surplus, c))}${row("Emergency cover", mo(k.before.cover), mo(k.after.cover))}</tbody></table>
    <ul class="reasons">${k.bad.map((x) => `<li class="down">✕ ${esc(x)}</li>`).join("")}${k.warn.map((x) => `<li>! ${esc(x)}</li>`).join("")}${k.good.map((x) => `<li class="up">✓ ${esc(x)}</li>`).join("")}</ul>
    <p class="sec" style="font-size:12.5px"><b>Borrow or use own funds?</b> ${esc(k.alternative)} You hold ${fmt(k.liquid, c, { compact: true })} in cash and deposits and ${fmt(k.investments, c, { compact: true })} in stocks and mutual funds.</p>
    <button class="btn primary" id="loan-ai" style="width:100%;justify-content:center">✦ Ask the AI to assess this loan</button>`;
}

VIEWS.advisor = {
  render() {
    const L = state.loan?.params || {};
    return `<div id="ai-status"></div>
    <div class="grid" style="grid-template-columns:minmax(0,1.5fr) minmax(0,1fr);align-items:start">
      <div class="card"><div class="card-h"><h3>✦ Ask your portfolio</h3><span class="sub">answers use your real numbers</span><div class="spacer"></div><button class="btn sm ghost" id="chat-clear">Clear</button></div>
        <div id="chat-log" class="chat-log"></div>
        <div class="chips">${PROMPTS.map((q) => `<button class="chip" data-q="${esc(q)}">${esc(q)}</button>`).join("")}</div>
        <form id="chat-form" class="chat-form"><textarea class="ctl" id="chat-in" rows="2" placeholder="e.g. Should I prepay the home loan or keep investing?"></textarea><button class="btn primary" id="chat-send" type="submit">Send</button></form></div>
      <div class="card"><div class="card-h"><h3>Loan check</h3><span class="sub">should I take this loan?</span></div>
        <form id="loan-form" class="form-grid">
          <div class="field"><label>Loan amount</label><input class="ctl" name="amount" type="number" required value="${L.amount || ""}"></div>
          <div class="field"><label>Currency</label><select class="ctl" name="currency">${state.meta.currencies.map((c) => `<option ${c === (L.currency || state.cur) ? "selected" : ""}>${c}</option>`).join("")}</select></div>
          <div class="field"><label>Interest rate (% p.a.)</label><input class="ctl" name="rate" type="number" step="0.01" required value="${L.rate || ""}"></div>
          <div class="field"><label>Tenure (years)</label><input class="ctl" name="years" type="number" step="0.5" required value="${L.tenure_months ? L.tenure_months / 12 : ""}"></div>
          <div class="field full"><label>Purpose</label><select class="ctl" name="purpose">${["Home", "Car", "Personal", "Education", "Business or investment", "Debt consolidation", "Other"].map((x) => `<option ${x === L.purpose ? "selected" : ""}>${x}</option>`).join("")}</select></div>
          <div class="field full"><button class="btn" type="submit" style="width:100%;justify-content:center">Check affordability</button></div>
        </form><div id="loan-out" style="margin-top:14px">${state.loan ? loanHtml(state.loan.result) : ""}</div></div>
    </div>`;
  },
  after() {
    paintChat();
    const bindLoanAi = () => $("#loan-ai")?.addEventListener("click", () => {
      const k = state.loan.result;
      sendChat(`Should I take a ${k.purpose.toLowerCase()} loan of ${fmt(k.amount, k.currency)} at ${k.rate}% for ${k.tenure_months / 12} years? Assess it against my current finances, say whether using my own funds would be better, and suggest a safer structure if needed.`, { loan: state.loan.params });
    });
    bindLoanAi();
    $("#chat-form").addEventListener("submit", (e) => { e.preventDefault(); const t = $("#chat-in"); sendChat(t.value); t.value = ""; });
    $("#chat-in").addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); $("#chat-form").requestSubmit(); } });
    $("#chat-clear").addEventListener("click", () => { if (!state.chatBusy) { state.chat = []; paintChat(); } });
    $$(".chip").forEach((b) => b.addEventListener("click", () => sendChat(b.dataset.q)));
    $("#loan-form").addEventListener("submit", async (e) => {
      e.preventDefault();
      const f = Object.fromEntries(new FormData(e.target));
      const params = { amount: +f.amount, rate: +f.rate, tenure_months: Math.round(+f.years * 12), currency: f.currency, purpose: f.purpose };
      try { state.loan = { params, result: await api("/api/loan-check", { method: "POST", body: params }) }; $("#loan-out").innerHTML = loanHtml(state.loan.result); bindLoanAi(); }
      catch (err) { toast(err.message, "error"); }
    });
    api("/api/ai/status").then((s) => {
      const el = $("#ai-status");
      if (!el) return;
      if (s.running && s.models.length) {
        el.innerHTML = `<div class="toolbar"><span class="tag ok">● Local AI ready</span><span class="muted">Runs on this Mac through Ollama. Nothing is sent to the internet.</span><div class="spacer"></div><label class="muted">Model</label>
          <select class="ctl compact" id="ai-model">${s.models.map((m) => `<option ${m === s.model ? "selected" : ""}>${esc(m)}</option>`).join("")}</select></div>`;
        $("#ai-model").addEventListener("change", (e) => api("/api/settings", { method: "PUT", body: { ai_model: e.target.value } }).then(() => toast("Model saved")));
      } else {
        el.innerHTML = `<div class="banner" style="display:block"><b>${s.running ? "Ollama is running, but no model is installed yet." : "The local AI isn't running yet."}</b> The loan check and Insights work without it; the chat needs it. One-time setup in Terminal:
          <ol class="steps" style="margin-top:8px">${s.running ? "" : `<li>Install: <code>brew install ollama</code> (or download from ollama.com)</li><li>Start it: <code>ollama serve</code> (leave it running), or open the Ollama app</li>`}
          <li>Download a model: <code>ollama pull llama3.1:8b</code> (about 5 GB; good on 16 GB Macs). On 8 GB use <code>llama3.2:3b</code>; on 32 GB+ a larger model such as <code>qwen2.5:14b</code> reasons better.</li><li>Reload this page.</li></ol></div>`;
      }
    }).catch(() => {});
  },
};

// ------------------------------------------------------------------ Settings: cash flow card
{
  const baseRender = VIEWS.settings.render, baseAfter = VIEWS.settings.after;
  VIEWS.settings.render = () => {
    const cf = state.data.cashflow || {}, inc = cf.incomes || {}, ex = cf.expenses || {};
    const curSel = (name, v) => `<select class="ctl" name="${name}">${state.meta.currencies.map((c) => `<option ${c === (v || state.cur) ? "selected" : ""}>${c}</option>`).join("")}</select>`;
    const card = `<div class="card"><div class="card-h"><h3>Cash flow</h3><span class="sub">used by Insights, the loan check and the AI</span></div>
      <form id="cf-form" class="form-grid" style="grid-template-columns:2fr 1fr">
        ${state.data.members.map((m) => `<div class="field"><label>${esc(m.name)}: monthly take-home income</label><input class="ctl" type="number" name="inc_${m.id}" value="${inc[m.id]?.amount || ""}"></div><div class="field"><label>Currency</label>${curSel(`cur_${m.id}`, inc[m.id]?.currency)}</div>`).join("")}
        <div class="field"><label>Household monthly expenses (excluding EMIs)</label><input class="ctl" type="number" name="exp" value="${ex.amount || ""}"></div><div class="field"><label>Currency</label>${curSel("exp_cur", ex.currency)}</div>
        <div class="field"><label>Expected return on investments (% p.a.)</label><input class="ctl" type="number" step="0.5" name="er" value="${cf.expected_return ?? 10}"></div><div class="field" style="align-self:end"><button class="btn primary" type="submit">Save</button></div>
      </form></div>`;
    return baseRender().replace(/<\/div>\s*$/, card + "</div>");
  };
  VIEWS.settings.after = () => {
    baseAfter();
    $("#cf-form").addEventListener("submit", async (e) => {
      e.preventDefault();
      const f = new FormData(e.target), incomes = {};
      state.data.members.forEach((m) => (incomes[m.id] = { amount: f.get(`inc_${m.id}`), currency: f.get(`cur_${m.id}`) }));
      try { await api("/api/cashflow", { method: "PUT", body: { incomes, expenses: { amount: f.get("exp"), currency: f.get("exp_cur") }, expected_return: f.get("er") } }); toast("Cash flow saved"); await refresh(); }
      catch (err) { toast(err.message, "error"); }
    });
  };
}
