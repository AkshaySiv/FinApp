"""Live price sources. Only symbols / scheme codes / currency codes are ever sent out.

- Stocks, ETFs, crypto, gold (XAU) & silver (XAG): Yahoo Finance chart API (delayed quotes)
- Indian mutual funds: AMFI daily NAV via api.mfapi.in (by scheme code)
- FX: open.er-api.com
"""
from __future__ import annotations

import json
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

from . import finance as F

TROY_OZ_G = 31.1034768
METALS = {"XAU": "GC=F", "XAG": "SI=F"}  # quoted in USD per troy ounce -> converted to per gram
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36"


def http_json(url: str, timeout: int = 10):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


# ---------------------------------------------------------------- yahoo (rate-limit aware)
import http.cookiejar
import threading
import time
import urllib.error

_CJ = http.cookiejar.CookieJar()
_YOPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(_CJ))
_YLOCK = threading.Lock()
_Y = {"cookie_at": 0.0, "cooldown_until": 0.0}
_CACHE: dict = {}          # (kind, symbol) -> (timestamp, quote)
CACHE_TTL = 300            # never re-fetch the same symbol more than once per 5 minutes
YAHOO_COOLDOWN = 600       # after a 429, leave Yahoo alone for 10 minutes


def _yget(url: str, timeout: int = 10):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json,text/plain,*/*",
                                               "Accept-Language": "en-US,en;q=0.9", "Referer": "https://finance.yahoo.com/"})
    with _YOPENER.open(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def _yahoo_session():
    """Yahoo throttles cookie-less clients hard; pick up its consent cookie once an hour."""
    with _YLOCK:
        if time.time() - _Y["cookie_at"] < 3600:
            return
        try:
            _YOPENER.open(urllib.request.Request("https://fc.yahoo.com", headers={"User-Agent": UA}), timeout=8)
        except Exception:
            pass  # fc.yahoo.com answers 404 but still sets the cookie
        _Y["cookie_at"] = time.time()


def _yahoo_chart(real: str) -> dict:
    if time.time() < _Y["cooldown_until"]:
        raise RuntimeError("Yahoo rate-limited — auto-retry at " + time.strftime("%H:%M", time.localtime(_Y["cooldown_until"])))
    _yahoo_session()
    last: Exception | None = None
    for host in ("query1", "query2"):
        for attempt in range(2):
            try:
                return _yget(f"https://{host}.finance.yahoo.com/v8/finance/chart/{urllib.parse.quote(real)}?range=1d&interval=1d")
            except urllib.error.HTTPError as e:
                last = e
                if e.code == 404:
                    raise ValueError("symbol not found") from None
                if e.code == 429:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                break
            except Exception as e:
                last = e
                break
    if isinstance(last, urllib.error.HTTPError) and last.code == 429:
        _Y["cooldown_until"] = time.time() + YAHOO_COOLDOWN
        raise RuntimeError("Yahoo rate-limited (429) — auto-retry in 10 min")
    raise last or RuntimeError("no response")


def yahoo_quote(symbol: str) -> dict:
    sym = symbol.strip()
    real = METALS.get(sym.upper(), sym)
    j = _yahoo_chart(real)
    res = (j.get("chart") or {}).get("result")
    if not res:
        err = ((j.get("chart") or {}).get("error") or {}).get("description") or "symbol not found"
        raise ValueError(err)
    m = res[0]["meta"]
    price = float(m["regularMarketPrice"])
    prev = float(m.get("chartPreviousClose") or m.get("previousClose") or price)
    ccy = m.get("currency") or "USD"
    if ccy == "GBp":  # London quotes in pence
        price, prev, ccy = price / 100, prev / 100, "GBP"
    name = m.get("longName") or m.get("shortName") or sym
    if sym.upper() in METALS:
        price, prev = price / TROY_OZ_G, prev / TROY_OZ_G
        name = METAL_NAMES[sym.upper()]
    return {"price": price, "prev": prev, "ccy": ccy, "name": name}


# ---------------------------------------------------------------- metals (no Yahoo needed)
METAL_NAMES = {"XAU": "Gold (24K) per gram", "XAG": "Silver per gram"}


def metal_quote(sym: str) -> dict:
    sym = sym.upper()
    errors = []
    if sym == "XAU":
        try:  # xaus.com — free, no key
            j = http_json("https://xaus.com/api/v1/spot")
            per_g = j.get("per_gram_usd") or (float(j["spot_usd_oz"]) / TROY_OZ_G)
            return {"price": float(per_g), "prev": None, "ccy": "USD", "name": METAL_NAMES[sym]}
        except Exception as e:
            errors.append(f"xaus: {type(e).__name__}")
    try:  # gold-api.com — free, no key, XAU + XAG
        j = http_json(f"https://api.gold-api.com/price/{sym}")
        return {"price": float(j["price"]) / TROY_OZ_G, "prev": None, "ccy": "USD", "name": METAL_NAMES[sym]}
    except Exception as e:
        errors.append(f"gold-api: {type(e).__name__}")
    try:
        return yahoo_quote(sym)
    except Exception as e:
        errors.append(f"yahoo: {e}")
    raise RuntimeError("; ".join(errors)[:150])


# ---------------------------------------------------------------- crypto (CoinGecko first)
COINGECKO = {"BTC": "bitcoin", "ETH": "ethereum", "SOL": "solana", "XRP": "ripple", "BNB": "binancecoin",
             "ADA": "cardano", "DOGE": "dogecoin", "USDT": "tether", "USDC": "usd-coin", "TRX": "tron",
             "AVAX": "avalanche-2", "DOT": "polkadot", "LINK": "chainlink", "MATIC": "matic-network",
             "LTC": "litecoin", "TON": "the-open-network"}


def crypto_quote(sym: str) -> dict | None:
    base, _, quote_ccy = sym.upper().partition("-")
    cid = COINGECKO.get(base)
    if not cid or quote_ccy not in ("USD", "INR", "AED", ""):
        return None
    vs = (quote_ccy or "USD").lower()
    j = http_json(f"https://api.coingecko.com/api/v3/simple/price?ids={cid}&vs_currencies={vs}&include_24hr_change=true")
    price = float(j[cid][vs])
    chg = j[cid].get(f"{vs}_24h_change")
    prev = price / (1 + chg / 100) if chg is not None else None
    return {"price": price, "prev": prev, "ccy": vs.upper(), "name": f"{cid.replace('-', ' ').title()} ({base})"}


def mf_nav(code: str) -> dict:
    j = http_json(f"https://api.mfapi.in/mf/{urllib.parse.quote(str(code).strip())}/latest")
    data = j.get("data") or []
    if not data:
        raise ValueError("scheme code not found")
    d = data[0]
    dd, mm, yy = d["date"].split("-")
    return {"price": float(d["nav"]), "prev": None, "ccy": "INR",
            "name": (j.get("meta") or {}).get("scheme_name") or str(code), "nav_date": f"{yy}-{mm}-{dd}"}


def mf_search(q: str) -> list[dict]:
    j = http_json(f"https://api.mfapi.in/mf/search?q={urllib.parse.quote(q)}")
    return [{"code": str(x["schemeCode"]), "name": x["schemeName"]} for x in (j or [])[:25]]


def fx_rates() -> dict:
    r = http_json("https://open.er-api.com/v6/latest/USD")["rates"]
    return {"USD": 1.0, "AED": float(r["AED"]), "INR": float(r["INR"])}


# ---------------------------------------------------------------- symbols
def holding_symbol(h: dict) -> str:
    if h.get("quote_symbol"):
        return h["quote_symbol"].strip()
    sym = (h.get("symbol") or "").strip().upper()
    mk, ex = h.get("market"), (h.get("exchange") or "").upper()
    if mk == "IN":
        return sym + (".BO" if ex == "BSE" else ".NS")
    if mk == "UAE":
        return sym + ".AE"
    return sym


def _quote_uncached(kind: str, sym: str) -> dict:
    if kind == "nav":
        return mf_nav(sym)
    if sym.strip().upper() in METALS:
        return metal_quote(sym.strip())
    if "-" in sym:
        try:
            q = crypto_quote(sym.strip())
            if q:
                return q
        except Exception:
            pass  # fall through to Yahoo
    return yahoo_quote(sym)


def quote(kind: str, sym: str, fresh: bool = False) -> dict:
    key = (kind, sym.strip().upper())
    hit = _CACHE.get(key)
    if hit and not fresh and time.time() - hit[0] < CACHE_TTL:
        return hit[1]
    q = _quote_uncached(kind, sym)
    _CACHE[key] = (time.time(), q)
    return q


# ---------------------------------------------------------------- refresh pipeline
def collect(data: dict, market: str | None = None) -> set[tuple[str, str]]:
    """Unique (kind, symbol) jobs. Read under the vault lock; network happens outside it."""
    jobs: set[tuple[str, str]] = set()
    for h in data.get("holdings", []):
        if (not market or h.get("market") == market) and h.get("symbol"):
            jobs.add(("yahoo", holding_symbol(h)))
    if not market:
        for a in data.get("assets", []):
            m, s = a.get("method"), (a.get("symbol") or "").strip()
            if s and m == "market":
                jobs.add(("yahoo", s))
            elif s and m == "nav":
                jobs.add(("nav", s))
    return jobs


def fetch(jobs: set[tuple[str, str]], with_fx: bool) -> dict:
    out: dict = {"quotes": {}, "errors": {}, "fx": None, "fx_error": None}

    def one(job):
        try:
            return job, quote(job[0], job[1]), None
        except Exception as e:  # network / symbol errors are reported per item
            return job, None, (str(e) or type(e).__name__)[:120]

    with ThreadPoolExecutor(max_workers=3) as ex:  # gentle on Yahoo
        for job, q, err in ex.map(one, jobs):
            if q:
                out["quotes"][job] = q
            else:
                out["errors"][job] = err
    if with_fx:
        try:
            out["fx"] = fx_rates()
        except Exception as e:
            out["fx_error"] = type(e).__name__
    return out


def _to(price: float, frm: str, to: str, fx: dict) -> float | None:
    if frm == to:
        return price
    if frm in F.CURRENCIES and to in F.CURRENCIES:
        return F.convert(price, frm, to, fx)
    return None


def apply(data: dict, res: dict) -> dict:
    now = datetime.now().isoformat(timespec="minutes")
    if res.get("fx"):
        data["fx"] = res["fx"]
        data["fx_updated"] = now + " (auto)"
    fx = data.get("fx") or F.DEFAULT_FX
    ok, failed = 0, []

    for h in data.get("holdings", []):
        if not h.get("symbol"):
            continue
        job = ("yahoo", holding_symbol(h))
        if job in res["quotes"]:
            q = res["quotes"][job]
            ccy = h.get("currency") or q["ccy"]
            p, pv = _to(q["price"], q["ccy"], ccy, fx), _to(q["prev"], q["ccy"], ccy, fx)
            if p is None:
                h["price_error"] = f"quoted in {q['ccy']}"
                failed.append(job[1])
                continue
            h.update(price=round(p, 4), prev_close=round(pv, 4), price_updated=now, price_error=None)
            if not h.get("name"):
                h["name"] = q["name"]
            ok += 1
        elif job in res["errors"]:
            h["price_error"] = res["errors"][job]
            failed.append(job[1])

    for a in data.get("assets", []):
        m, s = a.get("method"), (a.get("symbol") or "").strip()
        if m not in ("market", "nav") or not s:
            continue
        job = ("nav" if m == "nav" else "yahoo", s)
        if job in res["quotes"]:
            q = res["quotes"][job]
            if q["ccy"] not in F.CURRENCIES:
                a["price_error"] = f"quoted in {q['ccy']} (only AED/INR/USD supported)"
                failed.append(s)
                continue
            a.update(live_price=q["price"], live_ccy=q["ccy"], live_prev=q.get("prev"), live_name=q["name"],
                     price_updated=q.get("nav_date") or now, price_error=None)
            ok += 1
        elif job in res["errors"]:
            a["price_error"] = res["errors"][job]
            failed.append(s)

    data["last_refresh"] = {"at": now, "updated": ok, "failed": sorted(set(failed)),
                            "fx_error": res.get("fx_error")}
    return data["last_refresh"]
