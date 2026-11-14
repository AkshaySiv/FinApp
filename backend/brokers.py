"""Broker sync: Zerodha (Kite Connect) and Fyers (API v3).

Only your own developer app talks to the broker. API secrets, access tokens and the optional
Fyers PIN are stored inside the encrypted vault and never sent to the browser.

Zerodha: free "Personal" Kite Connect app. Access token is valid until ~6 AM IST next day,
         so Zerodha needs one login per day (Zerodha does not issue refresh tokens to individuals).
Fyers:   free API v3 app. Access token is daily, but a refresh token (valid ~15 days) plus your
         Fyers PIN lets the app renew it automatically without a login.
"""
from __future__ import annotations

import hashlib
import json
import time
import uuid
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

from . import pricing as P

KITE_API = "https://api.kite.trade"
KITE_LOGIN = "https://kite.zerodha.com/connect/login"
FYERS_API = "https://api-t1.fyers.in/api/v3"


class AuthExpired(Exception):
    """Token missing/expired — user must log in again (or refresh failed)."""


class BrokerError(Exception):
    pass


def _req(method: str, url: str, headers: dict | None = None, form: dict | None = None, js: dict | None = None):
    data, h = None, {"User-Agent": "FamilyWealth/1.0", "Accept": "application/json", **(headers or {})}
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        h["Content-Type"] = "application/x-www-form-urlencoded"
    elif js is not None:
        data = json.dumps(js).encode()
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            body = json.loads(e.read().decode() or "{}")
        except Exception:
            body = {}
        return e.code, body


def redirect_uri(broker: str, port: int) -> str:
    return f"http://127.0.0.1:{port}/connect/{broker}"


# ================================================================ Zerodha
def kite_login_url(conn: dict, state: str) -> str:
    rp = urllib.parse.urlencode({"c": conn["id"], "s": state})
    return f"{KITE_LOGIN}?" + urllib.parse.urlencode({"v": "3", "api_key": conn["api_key"], "redirect_params": rp})


def kite_exchange(conn: dict, request_token: str) -> dict:
    checksum = hashlib.sha256((conn["api_key"] + request_token + conn["api_secret"]).encode()).hexdigest()
    code, j = _req("POST", f"{KITE_API}/session/token", headers={"X-Kite-Version": "3"},
                   form={"api_key": conn["api_key"], "request_token": request_token, "checksum": checksum})
    if code != 200 or j.get("status") != "success":
        raise BrokerError(j.get("message") or f"Zerodha login failed (HTTP {code})")
    d = j["data"]
    return {"access_token": d["access_token"], "account_name": d.get("user_name") or d.get("user_id"),
            "account_id": d.get("user_id")}


def _kite_get(conn: dict, path: str):
    code, j = _req("GET", f"{KITE_API}{path}", headers={
        "X-Kite-Version": "3", "Authorization": f"token {conn['api_key']}:{conn.get('access_token', '')}"})
    if code == 200 and j.get("status") == "success":
        return j.get("data") or []
    et = j.get("error_type", "")
    if code == 403 and et == "TokenException":
        raise AuthExpired("Zerodha session expired — log in again")
    raise BrokerError(j.get("message") or f"HTTP {code}")


def kite_fetch(conn: dict) -> dict:
    if not conn.get("access_token"):
        raise AuthExpired("Not logged in")
    out = {"equity": [], "mf": [], "warnings": []}
    for h in _kite_get(conn, "/portfolio/holdings"):
        qty = float(h.get("quantity") or 0) + float(h.get("t1_quantity") or 0)
        if qty <= 0:
            continue
        out["equity"].append({
            "key": h.get("isin") or h["tradingsymbol"], "symbol": h["tradingsymbol"], "exchange": h.get("exchange", "NSE"),
            "isin": h.get("isin"), "qty": qty, "avg_cost": float(h.get("average_price") or 0),
            "price": float(h.get("last_price") or 0), "prev_close": float(h.get("close_price") or 0) or None})
    try:
        for m in _kite_get(conn, "/mf/holdings"):
            units = float(m.get("quantity") or 0)
            if units <= 0:
                continue
            out["mf"].append({
                "key": m.get("tradingsymbol") or m.get("fund"), "isin": m.get("tradingsymbol"), "name": m.get("fund"),
                "units": units, "avg_cost": float(m.get("average_price") or 0), "nav": float(m.get("last_price") or 0),
                "nav_date": (m.get("last_price_date") or "")[:10] or None, "folio": m.get("folio")})
    except AuthExpired:
        raise
    except BrokerError as e:
        out["warnings"].append(f"Mutual funds not available from Zerodha API: {e}")
    return out


# ================================================================ Fyers
def _fyers_hash(conn: dict) -> str:
    return hashlib.sha256(f"{conn['api_key']}:{conn['api_secret']}".encode()).hexdigest()


def fyers_login_url(conn: dict, state: str, port: int) -> str:
    return f"{FYERS_API}/generate-authcode?" + urllib.parse.urlencode({
        "client_id": conn["api_key"], "redirect_uri": redirect_uri("fyers", port), "response_type": "code",
        "state": f"{conn['id']}.{state}"})


def fyers_exchange(conn: dict, auth_code: str) -> dict:
    code, j = _req("POST", f"{FYERS_API}/validate-authcode",
                   js={"grant_type": "authorization_code", "appIdHash": _fyers_hash(conn), "code": auth_code})
    if j.get("s") != "ok" or not j.get("access_token"):
        raise BrokerError(j.get("message") or f"Fyers login failed (HTTP {code})")
    return {"access_token": j["access_token"], "refresh_token": j.get("refresh_token"),
            "refresh_at": time.time()}


def fyers_refresh(conn: dict) -> dict:
    if not (conn.get("refresh_token") and conn.get("pin")):
        raise AuthExpired("Fyers session expired — log in again (save your PIN to renew automatically)")
    if time.time() - float(conn.get("refresh_at") or 0) > 14.5 * 86400:
        raise AuthExpired("Fyers refresh token is older than 15 days — log in again")
    code, j = _req("POST", f"{FYERS_API}/validate-refresh-token",
                   js={"grant_type": "refresh_token", "appIdHash": _fyers_hash(conn),
                       "refresh_token": conn["refresh_token"], "pin": str(conn["pin"])})
    if j.get("s") != "ok" or not j.get("access_token"):
        raise AuthExpired(f"Fyers auto-renew failed: {j.get('message') or code} — log in again")
    return {"access_token": j["access_token"]}


FYERS_AUTH_CODES = {-8, -15, -16, -17, -300, -352}


def _fyers_holdings(conn: dict) -> dict:
    code, j = _req("GET", f"{FYERS_API}/holdings", headers={"Authorization": f"{conn['api_key']}:{conn.get('access_token', '')}"})
    if j.get("s") == "ok":
        return j
    if code in (401, 403) or j.get("code") in FYERS_AUTH_CODES:
        raise AuthExpired(j.get("message") or "token expired")
    raise BrokerError(j.get("message") or f"HTTP {code}")


def fyers_fetch(conn: dict) -> tuple[dict, dict | None]:
    """Returns (data, token_update). Renews the access token with refresh_token + PIN when needed."""
    upd = None
    if not conn.get("access_token"):
        raise AuthExpired("Not logged in")
    try:
        j = _fyers_holdings(conn)
    except AuthExpired:
        upd = fyers_refresh(conn)
        j = _fyers_holdings({**conn, **upd})
    out = {"equity": [], "mf": [], "warnings": []}
    for h in j.get("holdings") or []:
        sym_full = str(h.get("symbol") or "")
        qty = float(h.get("remainingQuantity", h.get("quantity")) or 0) + float(h.get("qty_t1") or 0)
        if qty <= 0:
            continue
        isin = h.get("isin") or ""
        exch, _, rest = sym_full.partition(":")
        is_exchange_traded = exch in ("NSE", "BSE") and "-" in rest
        if is_exchange_traded:
            out["equity"].append({
                "key": isin or rest, "symbol": rest.rsplit("-", 1)[0], "exchange": exch, "isin": isin, "qty": qty,
                "avg_cost": float(h.get("costPrice") or 0), "price": float(h.get("ltp") or 0), "prev_close": None})
        elif isin.startswith("INF") or "MF" in str(h.get("holdingType", "")).upper():
            out["mf"].append({
                "key": isin or sym_full, "isin": isin, "name": rest or sym_full, "units": qty,
                "avg_cost": float(h.get("costPrice") or 0), "nav": float(h.get("ltp") or 0), "nav_date": None})
        else:
            out["warnings"].append(f"Skipped unrecognised holding {sym_full}")
    return out, upd


# ================================================================ ISIN -> AMFI scheme code
_AMFI = {"at": 0.0, "map": {}, "names": {}}


def amfi_map() -> tuple[dict, dict]:
    """ISIN -> AMFI scheme code, from AMFI's public daily NAV file. Cached for 24h."""
    if _AMFI["map"] and time.time() - _AMFI["at"] < 86400:
        return _AMFI["map"], _AMFI["names"]
    text = None
    for url in ("https://www.amfiindia.com/spages/NAVAll.txt", "https://portal.amfiindia.com/spages/NAVAll.txt"):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": P.UA})
            with urllib.request.urlopen(req, timeout=25) as r:
                text = r.read().decode("utf-8", "ignore")
            break
        except Exception:
            continue
    if not text:
        return _AMFI["map"], _AMFI["names"]
    m, names = {}, {}
    for line in text.splitlines():
        parts = line.split(";")
        if len(parts) >= 6 and parts[0].strip().isdigit():
            code = parts[0].strip()
            names[code] = parts[3].strip()
            for isin in (parts[1].strip(), parts[2].strip()):
                if isin.startswith("IN"):
                    m[isin] = code
    _AMFI.update(at=time.time(), map=m, names=names)
    return m, names


# ================================================================ apply to vault data
def apply_sync(data: dict, conn: dict, fetched: dict) -> dict:
    """Upsert broker holdings into the vault. Items previously synced from this connection that no
    longer exist at the broker are removed. User-owned fields (goal, notes) are preserved."""
    now = datetime.now().isoformat(timespec="minutes")
    src = conn["id"]
    broker_label = {"zerodha": "Zerodha", "fyers": "Fyers"}[conn["broker"]]
    stats = {"stocks": 0, "funds": 0, "removed": 0}

    # ---- stocks -> holdings (Indian market)
    existing = {h.get("source_key"): h for h in data["holdings"] if h.get("source") == src}
    seen = set()
    for e in fetched["equity"]:
        k = e["key"]
        seen.add(k)
        h = existing.get(k)
        if not h:
            h = {"id": uuid.uuid4().hex[:10], "market": "IN", "currency": "INR",
                 "source": src, "source_key": k, "goal_id": "", "notes": ""}
            data["holdings"].append(h)
        h.update(symbol=e["symbol"], exchange=e["exchange"] if e["exchange"] in ("NSE", "BSE") else "NSE",
                 qty=e["qty"], avg_cost=round(e["avg_cost"], 4), owner=conn.get("owner") or "joint",
                 isin=e.get("isin"), source_label=f"{broker_label} · {conn.get('label') or ''}".strip(" ·"),
                 updated=now)
        if e["price"]:
            h.update(price=e["price"], price_updated=now, price_error=None)
        if e.get("prev_close"):
            h["prev_close"] = e["prev_close"]
        stats["stocks"] += 1
    before = len(data["holdings"])
    data["holdings"] = [h for h in data["holdings"] if not (h.get("source") == src and h.get("source_key") not in seen)]
    stats["removed"] += before - len(data["holdings"])

    # ---- mutual funds -> assets
    isin_map, amfi_names = amfi_map() if fetched["mf"] else ({}, {})
    existing = {a.get("source_key"): a for a in data["assets"] if a.get("source") == src}
    seen = set()
    for f in fetched["mf"]:
        k = f["key"]
        seen.add(k)
        a = existing.get(k)
        if not a:
            a = {"id": uuid.uuid4().hex[:10], "asset_class": "mutual_fund", "currency": "INR",
                 "source": src, "source_key": k, "goal_id": "", "notes": "", "institution": broker_label}
            data["assets"].append(a)
        code = isin_map.get(f.get("isin") or "")
        a.update(name=(amfi_names.get(code) if code else None) or f.get("name") or k,
                 units=f["units"], invested=round(f["units"] * f["avg_cost"], 2), owner=conn.get("owner") or "joint",
                 isin=f.get("isin"), source_label=f"{broker_label} · {conn.get('label') or ''}".strip(" ·"), updated=now)
        if code:
            a.update(method="nav", symbol=code)  # NAV keeps updating daily even without a broker login
        else:
            a.update(method="broker", symbol="")
        if f.get("nav"):
            a.update(live_price=f["nav"], live_ccy="INR", price_updated=f.get("nav_date") or now, price_error=None)
        stats["funds"] += 1
    before = len(data["assets"])
    data["assets"] = [a for a in data["assets"] if not (a.get("source") == src and a.get("source_key") not in seen)]
    stats["removed"] += before - len(data["assets"])
    return stats
