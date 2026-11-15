/* Family Wealth — dashboard and unified Assets page (asset-class allocation, currency split). */
"use strict";

const CLASS_ICON = { cash: "◎", fixed_deposit: "▣", mutual_fund: "▥", stocks_in: "₹", stocks_us: "$", stocks_uae: "د", gold: "◆",
  real_estate: "⌂", retirement: "◷", bonds: "▤", crypto: "₿", insurance: "☂", vehicle: "▭", other: "◇" };
const STOCK_CLS = { stocks_in: "IN", stocks_us: "US", stocks_uae: "UAE" };
const openClass = (k) => { state.assetFilter = k; if (state.view === "assets") renderView(); else location.hash = "assets"; };

function classRows(s) {
  const total = s.totals.assets || 1, cur = state.cur;
  const rows = [...s.by_class].sort((a, b) => b.value - a.value);
  if (!rows.length) return emptyState("Nothing added yet.", "add-asset", "+ Add your first asset");
  return `<div class="alloc">${rows.map((c) => {
    const g = c.value - c.cost, has = Math.abs(g) > 0.5, share = c.value / total;
    return `<button class="alloc-row" data-cls="${c.key}">
      <span class="alloc-ico">${CLASS_ICON[c.key] || "◇"}</span>
      <span class="alloc-main"><span class="alloc-name">${esc(c.label)} <span class="muted">· ${c.count}</span></span><span class="bar"><span style="width:${Math.max(share * 100, 0.8)}%"></span></span></span>
      <span class="alloc-val"><b>${fmt(c.value, cur, { compact: true })}</b><span class="muted">${(share * 100).toFixed(1)}%</span></span>
      <span class="alloc-gain ${has ? signCls(g) : "muted"}">${has ? `${g >= 0 ? "▲" : "▼"} ${fmt(Math.abs(g), cur, { compact: true })}${c.cost ? `<span>${pct(g / c.cost)}</span>` : ""}` : "—"}</span>
      <span class="alloc-go">›</span></button>`; }).join("")}</div>`;
}

function currencyRows(s) {
  const total = s.by_currency.reduce((a, c) => a + c.value, 0) || 1, cur = state.cur;
  return `<div class="alloc">${[...s.by_currency].sort((a, b) => b.value - a.value).map((c) => {
    const share = c.value / total, native = conv(c.value, cur, c.currency), loans = conv(c.liabilities || 0, cur, c.currency);
    return `<div class="alloc-row static"><span class="alloc-ico">${{ INR: "₹", USD: "$", AED: "د" }[c.currency]}</span>
      <span class="alloc-main"><span class="alloc-name">${c.currency} <span class="muted">· ${(share * 100).toFixed(1)}% of assets</span></span><span class="bar"><span style="width:${Math.max(share * 100, 0.8)}%"></span></span>
        <span class="muted" style="font-size:12px">${loans > 0.5 ? `Loans in ${c.currency}: ${fmt(loans, c.currency, { compact: true })} · net ${fmt(native - loans, c.currency, { compact: true })}` : `No loans in ${c.currency}`}</span></span>
      <span class="alloc-val"><b>${fmt(native, c.currency, { compact: true })}</b><span class="muted">${c.currency !== cur ? `≈ ${fmt(c.value, cur, { compact: true })}` : "held"}</span></span></div>`; }).join("")}</div>`;
}

// ------------------------------------------------------------------ Dashboard
{
  const oldAfter = VIEWS.dashboard.after;
  VIEWS.dashboard = {
    render() {
      const s = state.summary, t = s.totals, cur = state.cur, hist = s.history;
      const first = hist.length > 1 ? hist[0] : null, change = first ? t.net_worth - first.net_worth : null;
      const who = state.who === "family" ? "Family" : esc(memberName(state.who));
      const tone = (x) => (x > 0.5 ? "hero-up" : x < -0.5 ? "hero-down" : "");
      const signed = (x, txt) => `<span class="${tone(x)}">${x >= 0 ? "▲" : "▼"} ${txt}</span>`;
      const stat = (l, v, sub = "") => `<div><div class="hero-l">${l}</div><div class="hero-v">${v}</div><div class="hero-s">${sub}</div></div>`;
      return `
      <div class="hero-card">
        <div class="hero-top"><div><div class="hero-l">NET WORTH · ${who.toUpperCase()}</div><div class="hero-big">${fmt(t.net_worth)}</div>
          <div class="hero-s">${change != null ? `${signed(change, fmt(Math.abs(change), cur, { compact: true }))} since ${fmtDate(first.date)}` : `Shown in ${cur}. Switch currency or person at the top.`}</div></div></div>
        <div class="hero-stats">
          ${stat("Assets", fmt(t.assets, cur, { compact: true }), `${s.by_class.reduce((a, c) => a + c.count, 0)} items`)}
          ${stat("Liabilities", fmt(t.liabilities, cur, { compact: true }), `${(t.debt_ratio * 100).toFixed(0)}% of assets`)}
          ${stat("Total gain", signed(t.gain, fmt(Math.abs(t.gain), cur, { compact: true })), `on ${fmt(t.invested, cur, { compact: true })} invested`)}
          ${stat("Equity P&amp;L", signed(t.equity_pnl, fmt(Math.abs(t.equity_pnl), cur, { compact: true })), `<span class="${tone(t.equity_pnl)}">${pct(t.equity_pnl_pct)}</span> · stocks + funds`)}
          ${stat("Monthly EMI", fmt(t.monthly_emi, cur, { compact: true }), `${s.loans.filter((l) => l.active).length} active loan(s)`)}
        </div>
      </div>

      <div class="grid" style="grid-template-columns:minmax(0,1.55fr) minmax(0,1fr);margin-top:16px;align-items:start">
        <div class="card"><div class="card-h"><h3>Assets by class</h3><span class="sub">share of ${fmt(t.assets, cur, { compact: true })} · click a row to manage</span><div class="spacer"></div><button class="btn sm" data-nav-go="assets">Manage assets</button></div>${classRows(s)}</div>
        <div class="grid">
          <div class="card"><div class="card-h"><h3>Currency allocation</h3><span class="sub">where your assets are held</span></div>${currencyRows(s)}</div>
          <div class="card"><div class="card-h"><h3>Family split</h3><span class="sub">joint items split equally</span></div>
            <div class="list">${s.by_member.map((m) => `<div class="list-row"><div class="avatar" style="background:${memberColor(m.id)};width:28px;height:28px;font-size:12px">${esc(m.name[0] || "?")}</div><div class="grow"><div class="t">${esc(m.name)}</div><div class="s">Assets ${fmt(m.assets, cur, { compact: true })} · Debt ${fmt(m.liabilities, cur, { compact: true })}</div></div><div style="font-weight:600">${fmt(m.net, cur, { compact: true })}</div></div>`).join("")}</div></div>
        </div>
      </div>

      <div class="grid cols-2" style="margin-top:16px">
        <div class="card"><div class="card-h"><h3>Net worth trend</h3><span class="sub">${cur} · one snapshot per day</span></div>
          ${hist.length < 2 ? `<div class="empty" style="padding:70px 0">The trend builds automatically: one snapshot is saved each day you use the app.</div>` : `<div class="chart-box"><canvas id="ch-hist" aria-label="Net worth over time"></canvas></div>`}</div>
        <div class="card"><div class="card-h"><h3>Upcoming EMIs</h3><div class="spacer"></div><button class="btn sm ghost" data-nav-go="loans">All loans →</button></div>
          ${s.upcoming_emis.length ? `<div class="list">${s.upcoming_emis.slice(0, 5).map((u) => `<div class="list-row"><div class="datepill"><b>${u.date.slice(8)}</b><span>${monthName(u.date)}</span></div><div class="grow"><div class="t">${esc(u.name)}</div><div class="s">${fmt(u.emi, u.currency)}</div></div><div>${fmt(u.emi_conv)}</div></div>`).join("")}</div>` : `<div class="empty">No active loans</div>`}</div>
      </div>

      <div class="card" style="margin-top:16px"><div class="card-h"><h3>Goals</h3><div class="spacer"></div><button class="btn sm ghost" data-nav-go="goals">Manage →</button></div>
        ${s.goals.length ? `<div class="grid cols-2e">${s.goals.slice(0, 6).map(goalRow).join("")}</div>` : emptyState("Set a goal such as a house, education or retirement.", "add-goal", "Add a goal")}</div>`;
    },
    after() {
      oldAfter();
      $$("[data-cls]").forEach((b) => b.addEventListener("click", () => openClass(b.dataset.cls)));
    },
  };
}

// ------------------------------------------------------------------ Assets: every class, stocks included, on one page
{
  const base = VIEWS.assets;
  const chips = () => {
    const AC = state.meta.asset_classes, by = Object.fromEntries(state.summary.by_class.map((c) => [c.key, c]));
    const keys = Object.keys(AC).filter((k) => by[k] || STOCK_CLS[k]).sort((a, b) => (by[b]?.value || 0) - (by[a]?.value || 0));
    const chip = (k, label, val) => `<button class="chip ${state.assetFilter === k ? "on" : ""}" data-chip="${k}">${label}${val != null ? ` <span class="muted">${fmt(val, state.cur, { compact: true })}</span>` : ""}</button>`;
    return `<div class="chips" style="margin:0 0 16px">${chip("all", "All classes", state.summary.totals.assets)}${keys.map((k) => chip(k, esc(AC[k]), by[k]?.value ?? 0)).join("")}</div>`;
  };
  VIEWS.assets = {
    render() {
      const f = state.assetFilter, mk = STOCK_CLS[f];
      if (f === "all") {
        return chips() + `<div class="toolbar"><span class="muted">Everything you own, by asset class. Click a class to see and edit what is inside it.</span><div class="spacer"></div>
            <button class="btn primary" data-act="add-asset">+ Add asset</button>${Object.entries(STOCK_CLS).map(([, m]) => `<button class="btn" data-act="add-holding" data-market="${m}">+ ${m} stock</button>`).join("")}</div>
          <div class="grid" style="grid-template-columns:minmax(0,1.55fr) minmax(0,1fr);align-items:start">
            <div class="card"><div class="card-h"><h3>Asset classes</h3><span class="sub">${state.cur}</span></div>${classRows(state.summary)}</div>
            <div class="card"><div class="card-h"><h3>Currency allocation</h3><span class="sub">where your assets are held</span></div>${currencyRows(state.summary)}</div></div>`;
      }
      return chips() + (mk ? viewStocks(mk) : base.render());
    },
    after() {
      const mk = STOCK_CLS[state.assetFilter];
      if (mk) afterStocks(mk); else if (state.assetFilter !== "all") base.after?.();
      $$("[data-chip]").forEach((b) => b.addEventListener("click", () => openClass(b.dataset.chip)));
      $$("[data-cls]").forEach((b) => b.addEventListener("click", () => openClass(b.dataset.cls)));
    },
  };
}
