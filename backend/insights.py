"""Deterministic portfolio analysis. The app does the arithmetic; the AI only explains it.

- Stock health: scored on the stock's own market behaviour (1-year return vs its index,
  trend, distance from 52-week high) — independent of what you paid for it.
- Household findings: emergency cover, EMI burden, savings rate, concentration, goals.
- Loan check: what a new loan does to cash flow, cover and goals.
"""
from __future__ import annotations

import math
import statistics
import time
from datetime import date

from . import finance as F
from . import pricing as P

BENCH = {"IN": ("^NSEI", "Nifty 50"), "US": ("^GSPC", "S&P 500")}
HEALTH_TTL = 20 * 3600
PAUSE = 1.2  # seconds between history requests — stay well under Yahoo's limits


# ================================================================ stock health
def metrics_from_closes(closes: list[float]) -> dict | None:
    c = [x for x in closes if x]
    if len(c) < 60:
        return None
    last = c[-1]
    ret = lambda n: (last / c[-1 - n] - 1) if len(c) > n else None
    rets = [c[i] / c[i - 1] - 1 for i in range(1, len(c))]
    peak, mdd = c[0], 0.0
    for x in c:
        peak = max(peak, x)
        mdd = min(mdd, x / peak - 1)
    return {
        "ret_1y": (last / c[0] - 1) if len(c) >= 200 else None, "ret_6m": ret(126), "ret_3m": ret(63),
        "from_high": last / max(c) - 1, "max_dd": mdd,
        "above_200": (last > statistics.fmean(c[-200:])) if len(c) >= 200 else None,
        "golden": (statistics.fmean(c[-50:]) > statistics.fmean(c[-200:])) if len(c) >= 200 else None,
        "vol": statistics.pstdev(rets) * math.sqrt(252), "days": len(c),
    }


def score(m: dict, b: dict | None, bname: str | None) -> dict:
    pct = lambda x: f"{x * 100:+.0f}%"
    s, why, ex1 = 50.0, [], None
    r1, r6 = m.get("ret_1y"), m.get("ret_6m")
    clamp = lambda x, lim: max(-lim, min(lim, x))
    if r1 is not None:
        if b and b.get("ret_1y") is not None:
            ex1 = r1 - b["ret_1y"]
            s += clamp(ex1 * 80, 25)
            why.append(f"1-year {pct(r1)} vs {bname} {pct(b['ret_1y'])}")
        else:
            s += clamp(r1 * 50, 20)
            why.append(f"1-year return {pct(r1)}")
    if r6 is not None:
        ex6 = r6 - b["ret_6m"] if b and b.get("ret_6m") is not None else r6
        s += clamp(ex6 * 60, 10)
    if m.get("above_200") is not None:
        s += 8 if m["above_200"] else -8
        why.append("above its 200-day average" if m["above_200"] else "below its 200-day average")
    if m.get("golden") is not None:
        s += 5 if m["golden"] else -5
    fh = m["from_high"]
    s += 5 if fh > -0.10 else -10 if fh < -0.30 else -5 if fh < -0.20 else 0
    if fh < -0.15:
        why.append(f"{abs(fh) * 100:.0f}% below its 52-week high")
    s = max(0, min(100, round(s)))
    label = "Strong" if s >= 65 else "Neutral" if s >= 45 else "Weak" if s >= 30 else "Poor"
    return {"score": s, "label": label, "why": why, "excess_1y": ex1}


def health_jobs(data: dict, force: bool = False) -> list[str]:
    now, cache = time.time(), data.get("health", {})
    syms, markets = [], set()
    for h in data.get("holdings", []):
        if h.get("symbol"):
            syms.append(P.holding_symbol(h))
            markets.add(h.get("market"))
    want = [BENCH[m][0] for m in markets if m in BENCH] + list(dict.fromkeys(syms))
    return [s for s in want if force or now - cache.get(s, {}).get("ts", 0) > HEALTH_TTL]


def fetch_health(symbols: list[str], progress: dict) -> tuple[dict, str | None]:
    out, err = {}, None
    for i, s in enumerate(symbols):
        progress["done"] = i
        try:
            m = metrics_from_closes(P.yahoo_history(s))
            out[s] = {"ts": time.time(), "m": m, "error": None if m else "not enough price history"}
        except ValueError as e:  # unknown symbol: remember the miss, don't keep retrying
            out[s] = {"ts": time.time(), "m": None, "error": str(e)}
        except Exception as e:  # rate limit / offline: stop and retry later
            err = (str(e) or type(e).__name__)[:160]
            break
        time.sleep(PAUSE)
    progress["done"] = len(out)
    return out, err


def apply_health(data: dict, out: dict) -> None:
    cache = data.setdefault("health", {})
    cache.update(out)
    keep = {P.holding_symbol(h) for h in data.get("holdings", []) if h.get("symbol")} | {b[0] for b in BENCH.values()}
    for k in [k for k in cache if k not in keep]:
        cache.pop(k)


def stock_health(data: dict, cur: str, who: str) -> list[dict]:
    fx, members, cache = data.get("fx") or F.DEFAULT_FX, data.get("members", []), data.get("health", {})
    names = {m["id"]: m["name"] for m in members}
    groups: dict = {}
    for h in data.get("holdings", []):
        sh = F.share_for(h.get("owner", "joint"), members, who)
        if sh == 0 or not h.get("symbol"):
            continue
        v, c, ccy = F.item_value_native("holding", h, fx)
        g = groups.setdefault(P.holding_symbol(h), {"h": h, "v": 0.0, "c": 0.0, "owners": []})  # same stock held twice = one row
        g["v"] += F.convert(v, ccy, cur, fx) * sh
        g["c"] += F.convert(c, ccy, cur, fx) * sh
        o = names.get(h.get("owner"), "Joint")
        if o not in g["owners"]:
            g["owners"].append(o)
    rows = []
    for sym, g in groups.items():
        h, e = g["h"], cache.get(sym) or {}
        m = e.get("m")
        bench = BENCH.get(h.get("market"))
        b = (cache.get(bench[0]) or {}).get("m") if bench else None
        row = {"id": h["id"], "symbol": h["symbol"], "name": h.get("name") or "", "market": h.get("market"),
               "owner": " + ".join(g["owners"]), "value": g["v"],
               "pnl_pct": (g["v"] / g["c"] - 1) if g["c"] else None, "bench": bench[1] if bench else None,
               "error": e.get("error"), "score": None, "label": None, "why": [], "excess_1y": None}
        if m:
            row.update({k: m.get(k) for k in ("ret_1y", "ret_6m", "ret_3m", "from_high", "above_200", "vol")})
            row.update(score(m, b, bench[1] if bench else None))
        rows.append(row)
    total = sum(r["value"] for r in rows) or 1
    for r in rows:
        r["weight"] = r["value"] / total
    return sorted(rows, key=lambda r: (r["score"] is None, r["score"] or 0))


# ================================================================ household
def cashflow(data: dict, cur: str) -> dict:
    cf, fx = data.get("cashflow") or {}, data.get("fx") or F.DEFAULT_FX
    ids = {m["id"]: m["name"] for m in data.get("members", [])}
    incomes = {ids[k]: F.convert(F.num(v.get("amount")), v.get("currency") or cur, cur, fx)
               for k, v in (cf.get("incomes") or {}).items() if k in ids and F.num(v.get("amount")) > 0}
    e = cf.get("expenses") or {}
    income = sum(incomes.values())
    return {"income": income, "incomes": incomes, "set": income > 0,
            "expenses": F.convert(F.num(e.get("amount")), e.get("currency") or cur, cur, fx),
            "expected_return": F.num(cf.get("expected_return"), 10.0)}


def _class(s: dict, *keys) -> float:
    return sum(c["value"] for c in s["by_class"] if c["key"] in keys)


def findings(s: dict, stocks: list[dict], cf: dict, family: bool) -> tuple[list[dict], dict]:
    t, out = s["totals"], []
    add = lambda level, title, detail: out.append({"level": level, "title": title, "detail": detail})
    p = lambda x: f"{x * 100:.0f}%"
    liquid = _class(s, "cash", "fixed_deposit")
    ratios: dict = {"liquid": liquid, "cashflow_set": cf["set"] and family}

    if cf["set"] and family:
        outgo = cf["expenses"] + t["monthly_emi"]
        surplus = cf["income"] - outgo
        ratios.update(surplus=surplus, foir=t["monthly_emi"] / cf["income"], savings_rate=surplus / cf["income"])
        if outgo > 0:
            months = liquid / outgo
            ratios["emergency_months"] = months
            lvl = "good" if months >= 6 else "warn" if months >= 3 else "bad"
            add(lvl, f"Emergency cover: {months:.1f} months",
                "Cash and deposits against monthly expenses plus EMIs. Six months is the usual target."
                if lvl != "good" else "Cash and deposits cover six months or more of expenses and EMIs.")
        f = ratios["foir"]
        if t["monthly_emi"] > 0:
            add("bad" if f > 0.4 else "warn" if f > 0.3 else "good", f"EMIs take {p(f)} of income",
                "Lenders usually cap total EMIs at 40–50% of income; under 30% leaves comfortable room.")
        sr = ratios["savings_rate"]
        add("good" if sr >= 0.2 else "warn" if sr >= 0.05 else "bad", f"Savings rate: {p(sr)} of income",
            "What is left each month after expenses and EMIs.")
        need = sum(g["required_monthly_conv"] for g in s["goals"])
        ratios["goals_need"] = need
        if need > 0:
            add("good" if need <= surplus else "warn", "Goals are fully funded by your surplus" if need <= surplus
                else "Goals need more than you save",
                f"Goals together need {need:,.0f} a month; your monthly surplus is {surplus:,.0f}.")
    elif family:
        add("info", "Add income and expenses", "Set them in Settings → Cash flow to unlock emergency cover, "
            "EMI burden, savings rate and loan affordability.")

    if t["assets"] > 0:
        dr = t["debt_ratio"]
        if t["liabilities"] > 0:
            add("bad" if dr > 0.5 else "warn" if dr > 0.3 else "good", f"Debt is {p(dr)} of assets", "Total outstanding loans against everything you own.")
        big = max(s["by_class"], key=lambda c: c["value"], default=None)
        if big and big["value"] / t["assets"] > 0.5:
            add("warn", f"{big['label']} is {p(big['value'] / t['assets'])} of your assets",
                "More than half your wealth sits in one asset class.")
        ccy = max(s["by_currency"], key=lambda c: c["value"])
        if ccy["value"] / t["assets"] > 0.8:
            add("info", f"{p(ccy['value'] / t['assets'])} of assets are in {ccy['currency']}", "Your wealth moves almost entirely with one currency.")

    if stocks:
        top = max(stocks, key=lambda r: r["weight"])
        ratios["top_holding"] = {"symbol": top["symbol"], "weight": top["weight"]}
        if top["weight"] > 0.2:
            add("bad" if top["weight"] > 0.35 else "warn", f"{top['symbol']} is {p(top['weight'])} of your stocks",
                "A single company above 20% of the equity portfolio is concentration risk.")
        scored = [r for r in stocks if r["score"] is not None]
        weak = [r for r in scored if r["label"] in ("Weak", "Poor")]
        ratios["weak"] = {"count": len(weak), "share": sum(r["weight"] for r in weak), "scored": len(scored), "total": len(stocks)}
        if weak:
            add("warn", f"{len(weak)} holding(s) are lagging the market",
                ", ".join(r["symbol"] for r in weak[:8]) + f" — {p(ratios['weak']['share'])} of your stocks by value.")
        div = [r["symbol"] for r in weak if (r["pnl_pct"] or 0) > 0.10]
        if div:
            add("warn", "In profit, but losing momentum", ", ".join(div[:8]) + ": you are up on these, yet the stocks themselves are trailing their index.")
        rec = [r["symbol"] for r in scored if r["label"] == "Strong" and (r["pnl_pct"] or 0) < -0.10]
        if rec:
            add("info", "At a loss, but the stock is strong", ", ".join(rec[:8]) + ": you are down on these, though they are currently beating their index.")
    return out, ratios


# ================================================================ loan check
def loan_check(data: dict, p: dict) -> dict:
    lc = p.get("currency") if p.get("currency") in F.CURRENCIES else data.get("settings", {}).get("base_currency", "AED")
    amount, rate, n = F.num(p.get("amount")), F.num(p.get("rate")), int(F.num(p.get("tenure_months")))
    s = F.summarize({**data, "snapshots": []}, lc, "family")
    t, cf = s["totals"], cashflow(data, lc)
    emi = F.emi_amount(amount, rate, n)
    liquid, invest = _class(s, "cash", "fixed_deposit"), t["stocks_value"] + _class(s, "mutual_fund")
    goals_need = sum(g["required_monthly_conv"] for g in s["goals"])

    def state(emis: float) -> dict:
        outgo = cf["expenses"] + emis
        return {"emis": emis, "foir": emis / cf["income"] if cf["set"] else None,
                "surplus": cf["income"] - outgo if cf["set"] else None,
                "cover": liquid / outgo if outgo > 0 else None}

    before, after = state(t["monthly_emi"]), state(t["monthly_emi"] + emi)
    bad, warn, good = [], [], []
    pc = lambda x: f"{x * 100:.0f}%"
    if not cf["set"]:
        verdict = "unknown"
        warn.append("Add your monthly income and expenses in Settings → Cash flow to judge affordability.")
    else:
        if after["surplus"] < 0:
            bad.append(f"Monthly cash flow turns negative: short by {-after['surplus']:,.0f} {lc} after this EMI.")
        if after["foir"] > 0.5:
            bad.append(f"Total EMIs would be {pc(after['foir'])} of income — above the 50% most lenders allow.")
        elif after["foir"] > 0.4:
            warn.append(f"Total EMIs would be {pc(after['foir'])} of income — above the 40% comfort line.")
        else:
            good.append(f"Total EMIs stay at {pc(after['foir'])} of income, inside the 40% comfort line.")
        if after["cover"] is not None:
            if after["cover"] < 1:
                bad.append(f"Cash and deposits would cover only {after['cover']:.1f} months of expenses and EMIs.")
            elif after["cover"] < 3:
                warn.append(f"Emergency cover falls to {after['cover']:.1f} months; three to six is the usual minimum.")
            else:
                good.append(f"Emergency cover remains {after['cover']:.1f} months.")
        if goals_need > 0 and after["surplus"] >= 0:
            if goals_need > after["surplus"]:
                warn.append(f"Your goals need {goals_need:,.0f} {lc} a month but only {after['surplus']:,.0f} would be left.")
            else:
                good.append("Your goals stay fully funded from the remaining surplus.")
        if t["assets"] and (t["liabilities"] + amount) / t["assets"] > 0.6:
            warn.append(f"Debt would rise to {pc((t['liabilities'] + amount) / t['assets'])} of your current assets.")
        verdict = "avoid" if bad else "caution" if warn else "ok"

    r = rate / 1200
    max_emi = max(0.4 * cf["income"] - t["monthly_emi"], 0) if cf["set"] else None
    max_loan = None
    if max_emi is not None and n > 0:
        max_loan = max_emi * n if r == 0 else max_emi * ((1 + r) ** n - 1) / (r * (1 + r) ** n)
    exp = cf["expected_return"]
    if rate > exp + 0.5:
        alt = (f"The loan costs {rate:g}% a year, more than the {exp:g}% you expect investments to earn. "
               f"Paying part of it from your own funds would be cheaper, as long as emergency cash stays intact.")
    elif rate < exp - 0.5:
        alt = (f"The loan costs {rate:g}%, less than the {exp:g}% you expect investments to earn, so borrowing and "
               f"staying invested can come out ahead — but loan interest is certain and investment returns are not.")
    else:
        alt = f"The loan rate ({rate:g}%) is close to your expected investment return ({exp:g}%); neither route has a clear cost advantage."
    return {"currency": lc, "amount": amount, "rate": rate, "tenure_months": n, "purpose": p.get("purpose") or "",
            "emi": emi, "total_interest": emi * n - amount, "total_payable": emi * n,
            "before": before, "after": after, "verdict": verdict, "bad": bad, "warn": warn, "good": good,
            "alternative": alt, "liquid": liquid, "investments": invest, "goals_need": goals_need,
            "max_emi": max_emi, "max_loan": max_loan, "income": cf["income"], "expenses": cf["expenses"]}


# ================================================================ context for the AI
def build_context(data: dict, cur: str, loan: dict | None = None) -> str:
    s = F.summarize({**data, "snapshots": []}, cur, "family")
    t, cf = s["totals"], cashflow(data, cur)
    stocks = stock_health(data, cur, "family")
    fnd, ratios = findings(s, stocks, cf, True)
    fx = data.get("fx") or F.DEFAULT_FX
    n = lambda x: f"{x:,.0f}"
    pc = lambda x: "n/a" if x is None else f"{x * 100:+.0f}%"
    L = [f"Today is {date.today():%d %b %Y}. All amounts are in {cur} unless another currency is shown.",
         f"FX: 1 USD = {fx['AED']:.4f} AED = {fx['INR']:.2f} INR.", "", "== HOUSEHOLD =="]
    L.append("Members: " + ", ".join(f"{m['name']} (net worth {n(m['net'])})" for m in s["by_member"]))
    if cf["set"]:
        L.append("Monthly income: " + n(cf["income"]) + " (" + ", ".join(f"{k} {n(v)}" for k, v in cf["incomes"].items()) + ")")
        L.append(f"Monthly expenses: {n(cf['expenses'])}. Existing EMIs: {n(t['monthly_emi'])}. Monthly surplus: {n(ratios.get('surplus', 0))}.")
    else:
        L.append("Monthly income and expenses: NOT PROVIDED (ask the user to add them in Settings → Cash flow).")
    L += ["", "== BALANCE SHEET ==", f"Net worth {n(t['net_worth'])} = assets {n(t['assets'])} − liabilities {n(t['liabilities'])}.",
          "By asset class: " + "; ".join(f"{c['label']} {n(c['value'])} ({c['value'] / t['assets'] * 100:.0f}%)"
                                         for c in sorted(s["by_class"], key=lambda c: -c["value"])) if t["assets"] else "No assets.",
          "By currency: " + "; ".join(f"{c['currency']} {n(c['value'])}" for c in s["by_currency"] if c["value"] > 0)]
    L += ["", "== STOCKS (market health is about the stock itself, not the user's buy price; score 0-100) =="]
    for r in sorted(stocks, key=lambda r: -r["value"])[:60]:
        h = (f"health {r['score']}/100 {r['label']}; " + "; ".join(r["why"])) if r["score"] is not None else "health: no market data yet"
        L.append(f"- {r['symbol']} [{r['market']}, {r['owner']}]: value {n(r['value'])}, {r['weight'] * 100:.1f}% of stocks, user's P&L {pc(r['pnl_pct'])}; {h}")
    if not stocks:
        L.append("(none)")
    L += ["", "== OTHER ASSETS =="]
    members = data.get("members", [])
    for a in data.get("assets", []):
        v, c, ccy = F.asset_value(a, fx)
        L.append(f"- {a.get('name')} [{F.ASSET_CLASSES.get(a.get('asset_class'), 'Other')}]: {n(v)} {ccy}" + (f", invested {n(c)}" if abs(v - c) > 1 else ""))
    L += ["", "== LOANS =="]
    for l in s["loans"]:
        L.append(f"- {l.get('name')} [{l['type_label']}]: outstanding {n(l['outstanding'])} {l.get('currency')}, {F.num(l.get('rate')):g}% p.a., "
                 f"EMI {n(l['emi'])}, {l['emis_left']} EMIs left, ends {l['end_date'] or 'n/a'}")
    if not s["loans"]:
        L.append("(none)")
    L += ["", "== GOALS =="]
    for g in s["goals"]:
        L.append(f"- {g.get('name')}: target {n(F.num(g.get('target')))} {g.get('currency')} by {g.get('target_date')}, "
                 f"{g['progress'] * 100:.0f}% funded, needs {n(g['required_monthly'])} {g.get('currency')}/month")
    if not s["goals"]:
        L.append("(none)")
    L += ["", "== AUTOMATED FINDINGS (computed by the app) =="] + [f"- [{f['level']}] {f['title']}. {f['detail']}" for f in fnd]
    if loan:
        k = loan_check(data, loan)
        c = k["currency"]
        st = lambda d: (f"EMIs {n(d['emis'])}, EMI/income {pc(d['foir']).replace('+', '')}, surplus {n(d['surplus'])}, "
                        f"emergency cover {d['cover']:.1f} months") if d["foir"] is not None and d["cover"] is not None else f"EMIs {n(d['emis'])}"
        L += ["", f"== LOAN BEING CONSIDERED (amounts in {c}; computed by the app — use these numbers) ==",
              f"Amount {n(k['amount'])}, {k['rate']:g}% p.a., {k['tenure_months']} months, purpose: {k['purpose'] or 'not stated'}.",
              f"EMI {n(k['emi'])}; total interest {n(k['total_interest'])}; total payable {n(k['total_payable'])}.",
              f"Before: {st(k['before'])}.", f"After: {st(k['after'])}.",
              f"Rule-based verdict: {k['verdict']}. Concerns: {' '.join(k['bad'] + k['warn']) or 'none'} Positives: {' '.join(k['good']) or 'none'}",
              f"Own funds available: cash+deposits {n(k['liquid'])}, stocks+mutual funds {n(k['investments'])}. {k['alternative']}"]
        if k["max_loan"] is not None:
            L.append(f"Largest loan that keeps EMIs within 40% of income at this rate and tenure: {n(k['max_loan'])}.")
    rules = ("You are the private financial analyst inside Family Wealth, a personal finance app running on the user's own "
             "computer. Answer using ONLY the data below. Rules: quote the actual figures; never invent numbers, prices, "
             "news or company facts — you have no internet access, so say so if asked about news or fundamentals; if data "
             "needed for the answer is missing, say exactly what to add. Be direct and specific to this household: give a "
             "clear view, the two or three numbers that drive it, and concrete next steps. Keep answers short and use plain "
             "language. Stock 'health' reflects market performance versus the index, not the user's purchase price. "
             "When you give a recommendation, add one line that this is analysis, not regulated financial advice.")
    return rules + "\n\n" + "\n".join(L)
