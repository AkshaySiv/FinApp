"""Pure finance calculations: currency conversion, ownership splits, loans, goals, summary."""
from __future__ import annotations

import math
from datetime import date, datetime

CURRENCIES = ("AED", "INR", "USD")

ASSET_CLASSES = {
    "cash": "Cash & Bank",
    "fixed_deposit": "Fixed Deposits",
    "mutual_fund": "Mutual Funds",
    "stocks_in": "Indian Stocks",
    "stocks_us": "US Stocks",
    "stocks_uae": "UAE Stocks",
    "gold": "Gold",
    "real_estate": "Real Estate",
    "retirement": "Retirement (EPF/PPF/NPS)",
    "bonds": "Bonds",
    "crypto": "Crypto",
    "insurance": "Insurance (cash value)",
    "vehicle": "Vehicles",
    "other": "Other",
}

MARKETS = {
    "IN": {"label": "Indian Stocks", "currency": "INR", "cls": "stocks_in", "exchanges": ["NSE", "BSE"]},
    "US": {"label": "US Stocks", "currency": "USD", "cls": "stocks_us", "exchanges": ["NASDAQ", "NYSE", "ETF"]},
    "UAE": {"label": "UAE Stocks", "currency": "AED", "cls": "stocks_uae", "exchanges": ["DFM", "ADX"]},
}

LOAN_TYPES = {
    "home": "Home loan", "car": "Car loan", "personal": "Personal loan", "education": "Education loan",
    "gold": "Gold loan", "credit_card": "Credit card", "business": "Business loan", "other": "Other",
}

DEFAULT_FX = {"USD": 1.0, "AED": 3.6725, "INR": 88.5}  # units per 1 USD

VALUATION_METHODS = {
    "manual": "Manual value",
    "market": "Live market price (stocks, ETFs, gold, silver, crypto)",
    "nav": "Mutual fund NAV — India (AMFI, daily)",
    "accrual": "Interest accrual (FD, RD, EPF, PPF, bonds)",
    "appreciation": "Yearly growth / depreciation (property, vehicles)",
    "broker": "Synced from broker (Zerodha / Fyers)",
}
DEFAULT_METHOD = {
    "cash": "manual", "fixed_deposit": "accrual", "mutual_fund": "nav", "gold": "market", "real_estate": "appreciation",
    "retirement": "accrual", "bonds": "accrual", "crypto": "market", "insurance": "manual", "vehicle": "appreciation",
    "other": "manual",
}


# ---------- basics ----------
def num(x, default=0.0) -> float:
    try:
        v = float(x)
        return v if math.isfinite(v) else default
    except (TypeError, ValueError):
        return default


def convert(amount: float, frm: str, to: str, fx: dict) -> float:
    if frm == to:
        return amount
    return amount / num(fx.get(frm), 1.0) * num(fx.get(to), 1.0)


def owner_shares(owner: str, members: list[dict]) -> dict[str, float]:
    """owner is a member id or 'joint' (split equally across all members)."""
    ids = [m["id"] for m in members]
    if owner in ids:
        return {owner: 1.0}
    if not ids:
        return {}
    return {i: 1.0 / len(ids) for i in ids}


def share_for(owner: str, members: list[dict], who: str) -> float:
    """Fraction of an item attributable to `who` ('family' = 100%)."""
    if who == "family":
        return 1.0
    return owner_shares(owner, members).get(who, 0.0)


def parse_date(s) -> date | None:
    if not s:
        return None
    try:
        return datetime.strptime(str(s)[:10], "%Y-%m-%d").date()
    except ValueError:
        return None


def add_months(d: date, n: int) -> date:
    m = d.month - 1 + n
    y = d.year + m // 12
    m = m % 12 + 1
    days = [31, 29 if (y % 4 == 0 and (y % 100 != 0 or y % 400 == 0)) else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    return date(y, m, min(d.day, days[m - 1]))


def months_between(a: date, b: date) -> int:
    return (b.year - a.year) * 12 + (b.month - a.month)


# ---------- loans ----------
def emi_amount(principal: float, annual_rate: float, months: int) -> float:
    if months <= 0 or principal <= 0:
        return 0.0
    r = annual_rate / 1200.0
    if r == 0:
        return principal / months
    f = (1 + r) ** months
    return principal * r * f / (f - 1)


def amortization(loan: dict) -> list[dict]:
    P = num(loan.get("principal"))
    rate = num(loan.get("rate"))
    n = int(num(loan.get("tenure_months")))
    start = parse_date(loan.get("start_date")) or date.today()
    emi = num(loan.get("emi_override")) or emi_amount(P, rate, n)
    r = rate / 1200.0
    bal, rows = P, []
    for k in range(1, n + 1):
        if bal <= 0.005:
            break
        interest = bal * r
        principal_part = min(emi - interest, bal) if emi > interest else 0.0
        if k == n:  # final installment clears rounding
            principal_part = bal
        bal = max(bal - principal_part, 0.0)
        rows.append({"n": k, "date": add_months(start, k - 1).isoformat(), "emi": round(principal_part + interest, 2),
                     "interest": round(interest, 2), "principal": round(principal_part, 2), "balance": round(bal, 2)})
        if emi <= interest and k > 1200:
            break
    return rows


def loan_status(loan: dict, today: date | None = None) -> dict:
    today = today or date.today()
    sched = amortization(loan)
    start = parse_date(loan.get("start_date")) or today
    paid = [r for r in sched if parse_date(r["date"]) <= today]
    emi = num(loan.get("emi_override")) or (sched[0]["emi"] if sched else 0.0)
    if loan.get("loan_type") == "credit_card" and not sched:
        emi = num(loan.get("emi_override"))
    sched_outstanding = paid[-1]["balance"] if paid else num(loan.get("principal"))
    override = loan.get("outstanding_override")
    outstanding = num(override) if override not in (None, "") else max(sched_outstanding - num(loan.get("prepaid")), 0.0)
    nxt = next((r for r in sched if parse_date(r["date"]) > today), None)
    total_interest = sum(r["interest"] for r in sched)
    interest_paid = sum(r["interest"] for r in paid)
    principal = num(loan.get("principal"))
    return {
        "emi": round(emi, 2),
        "outstanding": round(outstanding, 2),
        "emis_paid": len(paid),
        "emis_total": len(sched),
        "emis_left": max(len(sched) - len(paid), 0),
        "next_due": nxt["date"] if nxt else None,
        "end_date": sched[-1]["date"] if sched else None,
        "total_interest": round(total_interest, 2),
        "interest_paid": round(interest_paid, 2),
        "progress": round(1 - outstanding / principal, 4) if principal > 0 else 0,
        "active": outstanding > 0.01,
        "start": start.isoformat(),
    }


# ---------- goals ----------
def required_monthly(target: float, current: float, months: int, annual_return: float) -> float:
    if months <= 0:
        return max(target - current, 0.0)
    i = annual_return / 1200.0
    if i == 0:
        return max((target - current) / months, 0.0)
    fv_current = current * (1 + i) ** months
    gap = target - fv_current
    if gap <= 0:
        return 0.0
    return gap * i / ((1 + i) ** months - 1)


# ---------- asset valuation ----------
def years_between(a: date, b: date) -> float:
    return max((b - a).days, 0) / 365.25


def asset_value(a: dict, fx: dict, today: date | None = None) -> tuple[float, float, str]:
    """(current value, cost basis, currency) for an asset, according to its valuation method."""
    today = today or date.today()
    ccy = a.get("currency") or "USD"
    method = a.get("method") or "manual"
    manual = num(a.get("value"))
    inv_raw = a.get("invested")
    inv = num(inv_raw) if inv_raw not in (None, "") else None

    if method in ("market", "nav", "broker"):
        p = a.get("live_price")
        if p in (None, ""):
            v = manual or (inv or 0.0)  # until the first successful fetch: manual value, else amount invested
        else:
            price = convert(num(p), a.get("live_ccy") or ccy, ccy, fx)
            if method == "market" and str(a.get("symbol", "")).strip().upper() in ("XAU", "XAG"):
                karat = num(a.get("purity"), 24) or 24
                price *= min(karat, 24) / 24
            price *= 1 + num(a.get("premium_pct")) / 100
            v = num(a.get("units")) * price
        return v, (inv if inv is not None else v), ccy

    if method == "accrual":
        P = num(a.get("principal"))
        r = num(a.get("rate")) / 100
        n = int(num(a.get("compounding"), 4))
        start = parse_date(a.get("start_date")) or today
        mat = parse_date(a.get("maturity_date"))
        t = years_between(start, min(today, mat) if mat else today)
        v = P * (1 + r * t) if n <= 0 else P * (1 + r / n) ** (n * t)
        return v, (inv if inv is not None else P), ccy

    if method == "appreciation":
        B = num(a.get("base_value"))
        g = num(a.get("growth_pct")) / 100
        start = parse_date(a.get("base_date")) or today
        v = B * (1 + g) ** years_between(start, today) if g > -1 else 0.0
        return v, (inv if inv is not None else B), ccy

    return manual, (inv if inv is not None else manual), ccy


# ---------- holdings ----------
def holding_values(h: dict) -> tuple[float, float]:
    qty = num(h.get("qty"))
    price = num(h.get("price")) or num(h.get("avg_cost"))  # no price fetched yet: carry at cost, not zero
    return qty * price, qty * num(h.get("avg_cost"))


def item_value_native(kind: str, item: dict, fx: dict) -> tuple[float, float, str]:
    """(value, cost, currency) in the item's own currency."""
    if kind == "holding":
        v, c = holding_values(item)
        return v, c, item.get("currency") or MARKETS.get(item.get("market"), {}).get("currency", "USD")
    return asset_value(item, fx)


# ---------- summary ----------
def summarize(data: dict, cur: str, who: str = "family") -> dict:
    fx = data.get("fx") or DEFAULT_FX
    members = data.get("members", [])
    names = {m["id"]: m["name"] for m in members}
    C = lambda amt, frm: convert(amt, frm, cur, fx)

    by_class: dict[str, float] = {k: 0.0 for k in ASSET_CLASSES}
    class_cost: dict[str, float] = {k: 0.0 for k in ASSET_CLASSES}
    class_count: dict[str, int] = {k: 0 for k in ASSET_CLASSES}
    liab_ccy = {c: 0.0 for c in CURRENCIES}
    by_currency = {c: 0.0 for c in CURRENCIES}
    member_assets = {m["id"]: 0.0 for m in members}
    member_liab = {m["id"]: 0.0 for m in members}
    total_cost = 0.0
    markets = {k: {"label": v["label"], "value": 0.0, "cost": 0.0, "count": 0, "day_native_currency": v["currency"]}
               for k, v in MARKETS.items()}
    goal_linked: dict[str, float] = {}

    def add_asset(kind, item, cls):
        nonlocal total_cost
        v, c, ccy = item_value_native(kind, item, fx)
        owner = item.get("owner", "joint")
        for mid, sh in owner_shares(owner, members).items():
            member_assets[mid] = member_assets.get(mid, 0.0) + C(v, ccy) * sh
        s = share_for(owner, members, who)
        if s == 0:
            return 0.0, 0.0
        vv, cc = C(v, ccy) * s, C(c, ccy) * s
        by_class[cls] = by_class.get(cls, 0.0) + vv
        class_cost[cls] = class_cost.get(cls, 0.0) + cc
        class_count[cls] = class_count.get(cls, 0) + 1
        by_currency[ccy] = by_currency.get(ccy, 0.0) + vv
        total_cost += cc
        if item.get("goal_id"):
            goal_linked[item["goal_id"]] = goal_linked.get(item["goal_id"], 0.0) + C(v, ccy)  # full value, goal-level
        return vv, cc

    mf = {"value": 0.0, "cost": 0.0, "count": 0}
    for a in data.get("assets", []):
        vv, cc = add_asset("asset", a, a.get("asset_class", "other"))
        if a.get("asset_class") == "mutual_fund" and share_for(a.get("owner", "joint"), members, who) > 0:
            mf["value"] += vv
            mf["cost"] += cc
            mf["count"] += 1
    for h in data.get("holdings", []):
        mk = h.get("market", "US")
        vv, cc = add_asset("holding", h, MARKETS.get(mk, MARKETS["US"])["cls"])
        if share_for(h.get("owner", "joint"), members, who) > 0:
            markets[mk]["value"] += vv
            markets[mk]["cost"] += cc
            markets[mk]["count"] += 1
    for m in markets.values():
        m["pnl"] = m["value"] - m["cost"]
        m["pnl_pct"] = (m["pnl"] / m["cost"]) if m["cost"] else 0.0

    loans_out, liabilities, monthly_emi, upcoming = [], 0.0, 0.0, []
    for ln in data.get("loans", []):
        st = loan_status(ln)
        ccy = ln.get("currency", "AED")
        owner = ln.get("owner", "joint")
        for mid, sh in owner_shares(owner, members).items():
            member_liab[mid] = member_liab.get(mid, 0.0) + C(st["outstanding"], ccy) * sh
        s = share_for(owner, members, who)
        if s == 0:
            continue
        out_c = C(st["outstanding"], ccy) * s
        emi_c = C(st["emi"], ccy) * s if st["active"] else 0.0
        liabilities += out_c
        liab_ccy[ccy] = liab_ccy.get(ccy, 0.0) + out_c
        monthly_emi += emi_c
        row = {**ln, **st, "outstanding_conv": out_c, "emi_conv": emi_c, "owner_name": names.get(owner, "Joint"),
               "type_label": LOAN_TYPES.get(ln.get("loan_type"), "Loan")}
        loans_out.append(row)
        if st["active"] and st["next_due"]:
            upcoming.append({"id": ln["id"], "name": ln.get("name"), "date": st["next_due"], "emi": st["emi"],
                             "currency": ccy, "emi_conv": emi_c})
    upcoming.sort(key=lambda r: r["date"])

    today = date.today()
    goals_out = []
    for g in data.get("goals", []):
        s = share_for(g.get("owner", "joint"), members, who)
        if s == 0:
            continue
        gccy = g.get("currency", cur)
        target = num(g.get("target"))
        saved_native = num(g.get("current_saved")) + convert(goal_linked.get(g["id"], 0.0), cur, gccy, fx)
        td = parse_date(g.get("target_date"))
        months = max(months_between(today, td), 0) if td else 0
        req = required_monthly(target, saved_native, months, num(g.get("expected_return"), 8.0))
        goals_out.append({**g, "saved": round(saved_native, 2), "progress": min(saved_native / target, 1.0) if target else 0,
                          "months_left": months, "required_monthly": round(req, 2),
                          "target_conv": C(target, gccy), "saved_conv": C(saved_native, gccy),
                          "required_monthly_conv": C(req, gccy), "owner_name": names.get(g.get("owner"), "Joint")})

    total_assets = sum(by_class.values())
    stocks_value = sum(m["value"] for m in markets.values())
    stocks_cost = sum(m["cost"] for m in markets.values())

    history = []
    for snap in data.get("snapshots", []):
        sfx = snap.get("fx") or fx
        key = who if who in snap.get("net_usd", {}) else "family"
        history.append({"date": snap["date"],
                        "net_worth": convert(snap["net_usd"].get(key, 0.0), "USD", cur, sfx),
                        "assets": convert(snap["assets_usd"].get(key, 0.0), "USD", cur, sfx),
                        "liabilities": convert(snap["liab_usd"].get(key, 0.0), "USD", cur, sfx)})

    return {
        "currency": cur,
        "who": who,
        "fx": fx,
        "totals": {
            "assets": total_assets, "liabilities": liabilities, "net_worth": total_assets - liabilities,
            "monthly_emi": monthly_emi, "invested": total_cost, "gain": total_assets - total_cost,
            "stocks_value": stocks_value, "stocks_pnl": stocks_value - stocks_cost,
            "stocks_pnl_pct": (stocks_value - stocks_cost) / stocks_cost if stocks_cost else 0.0,
            "debt_ratio": liabilities / total_assets if total_assets else 0.0,
            "equity_value": stocks_value + mf["value"], "equity_cost": stocks_cost + mf["cost"],
            "equity_pnl": stocks_value + mf["value"] - stocks_cost - mf["cost"],
            "equity_pnl_pct": ((stocks_value + mf["value"]) / (stocks_cost + mf["cost"]) - 1) if stocks_cost + mf["cost"] else 0.0,
        },
        "equity": [{"key": k, "label": m["label"], "value": m["value"], "cost": m["cost"], "pnl": m["pnl"],
                    "pnl_pct": m["pnl_pct"], "count": m["count"], "nav": f"stocks-{k}"} for k, m in markets.items()]
                  + [{"key": "MF", "label": "Mutual Funds", "value": mf["value"], "cost": mf["cost"],
                      "pnl": mf["value"] - mf["cost"], "pnl_pct": (mf["value"] / mf["cost"] - 1) if mf["cost"] else 0.0,
                      "count": mf["count"], "nav": "assets"}],
        "by_class": [{"key": k, "label": ASSET_CLASSES[k], "value": v, "cost": class_cost.get(k, 0.0),
                      "count": class_count.get(k, 0)} for k, v in by_class.items() if v > 0.005],
        "by_currency": [{"currency": k, "value": v, "liabilities": liab_ccy.get(k, 0.0)} for k, v in by_currency.items()],
        "by_member": [{"id": m["id"], "name": m["name"], "assets": member_assets.get(m["id"], 0.0),
                       "liabilities": member_liab.get(m["id"], 0.0),
                       "net": member_assets.get(m["id"], 0.0) - member_liab.get(m["id"], 0.0)} for m in members],
        "markets": markets,
        "loans": loans_out,
        "upcoming_emis": upcoming,
        "goals": goals_out,
        "history": history,
    }


def snapshot(data: dict) -> dict:
    """Today's net worth (USD) for family + each member, with the FX used."""
    fx = data.get("fx") or DEFAULT_FX
    out = {"date": date.today().isoformat(), "fx": dict(fx), "net_usd": {}, "assets_usd": {}, "liab_usd": {}}
    for who in ["family"] + [m["id"] for m in data.get("members", [])]:
        s = summarize({**data, "snapshots": []}, "USD", who)["totals"]
        out["net_usd"][who] = round(s["net_worth"], 2)
        out["assets_usd"][who] = round(s["assets"], 2)
        out["liab_usd"][who] = round(s["liabilities"], 2)
    return out
