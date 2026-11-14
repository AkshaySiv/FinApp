"""Family Wealth — local-only personal finance server.

Binds to 127.0.0.1 only. All data is kept in an AES-256-GCM encrypted vault.
"""
from __future__ import annotations

import csv
import hmac
import io
import json
import os
import secrets
import threading
import time
import urllib.parse
import urllib.request
import uuid
from datetime import datetime
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles

from . import finance as F
from . import pricing as P
from . import brokers as B
from fastapi.responses import RedirectResponse
from .vault import Vault, VaultError

ROOT = Path(__file__).resolve().parent.parent
STATIC = ROOT / "static"
DATA_DIR = Path(os.environ.get("FW_DATA_DIR", Path.home() / "Library" / "Application Support" / "FamilyWealth"))
PORT = int(os.environ.get("FW_PORT", "8765"))

vault = Vault(DATA_DIR)
SESSION = {"token": None, "last": 0.0}
STATE = {"rev": 0, "last_refresh": 0.0, "refreshing": False, "pending": False, "last_broker_sync": 0.0}
LOGIN_STATES: dict = {}   # connection id -> (nonce, expires_at) for the OAuth round trip
SYNC_LOCK = threading.Lock()
REFRESH_LOCK = threading.Lock()
FAILED = {"count": 0, "until": 0.0}

app = FastAPI(title="Family Wealth", docs_url=None, redoc_url=None, openapi_url=None)

# ---------------------------------------------------------------- schemas
S, N, I = "str", "num", "int"
SCHEMAS = {
    "members": {"name": S, "color": S},
    "assets": {"name": S, "asset_class": S, "owner": S, "currency": S, "value": N, "invested": N,
               "institution": S, "goal_id": S, "notes": S, "as_of": S,
               "method": S, "units": N, "symbol": S, "purity": N, "premium_pct": N,
               "principal": N, "rate": N, "start_date": S, "maturity_date": S, "compounding": I,
               "base_value": N, "base_date": S, "growth_pct": N},
    "holdings": {"market": S, "exchange": S, "symbol": S, "name": S, "qty": N, "avg_cost": N, "price": N,
                 "currency": S, "owner": S, "sector": S, "quote_symbol": S, "goal_id": S, "notes": S},
    "loans": {"name": S, "loan_type": S, "lender": S, "owner": S, "currency": S, "principal": N, "rate": N,
              "tenure_months": I, "start_date": S, "emi_override": N, "outstanding_override": N,
              "prepaid": N, "notes": S},
    "goals": {"name": S, "owner": S, "currency": S, "target": N, "target_date": S, "current_saved": N,
              "expected_return": N, "priority": S, "notes": S},
}


def clean(coll: str, payload: dict) -> dict:
    out = {}
    for k, t in SCHEMAS[coll].items():
        if k not in payload:
            continue
        v = payload[k]
        if v is None or v == "":
            out[k] = None if t != S else ""
            continue
        if t == S:
            out[k] = str(v)[:500]
        elif t == N:
            try:
                out[k] = float(v)
            except (TypeError, ValueError):
                raise HTTPException(400, f"{k} must be a number")
        elif t == I:
            try:
                out[k] = int(float(v))
            except (TypeError, ValueError):
                raise HTTPException(400, f"{k} must be a whole number")
    if "currency" in out and out["currency"] and out["currency"] not in F.CURRENCIES:
        raise HTTPException(400, "currency must be AED, INR or USD")
    if coll == "holdings" and out.get("symbol"):
        out["symbol"] = out["symbol"].strip().upper()
    return out


def new_data(member_names: list[str]) -> dict:
    palette = ["#3987e5", "#d95926", "#199e70", "#c98500"]
    members = [{"id": uuid.uuid4().hex[:8], "name": n.strip(), "color": palette[i % 4]}
               for i, n in enumerate(member_names) if n and n.strip()]
    if not members:
        members = [{"id": uuid.uuid4().hex[:8], "name": "Me", "color": palette[0]}]
    return {"version": 1, "created": datetime.now().isoformat(timespec="seconds"),
            "settings": {"base_currency": "AED", "auto_lock_minutes": 10, "auto_refresh_minutes": 15, "broker_sync_minutes": 30},
            "fx": dict(F.DEFAULT_FX), "fx_updated": None,
            "members": members, "assets": [], "holdings": [], "loans": [], "goals": [], "snapshots": [],
            "connections": []}


def persist() -> None:
    d = vault.data
    snap = F.snapshot(d)
    d["snapshots"] = [s for s in d.get("snapshots", []) if s["date"] != snap["date"]] + [snap]
    d["snapshots"] = sorted(d["snapshots"], key=lambda s: s["date"])[-2000:]
    vault.save()
    STATE["rev"] += 1


# ---------------------------------------------------------------- automatic pricing
def run_refresh(market: str | None = None) -> dict:
    """Fetch live prices / NAVs / FX and apply them. Network calls happen outside the vault lock."""
    if not REFRESH_LOCK.acquire(blocking=False):
        STATE["pending"] = True  # run again as soon as the current refresh finishes
        return {"busy": True}
    STATE["pending"] = False
    STATE["refreshing"] = True
    try:
        with vault.lock:
            if not vault.unlocked:
                return {"locked": True}
            skip = broker_priced_sources()
            jobs = P.collect(vault.data, market, skip)
        res = P.fetch(jobs, with_fx=market is None)
        with vault.lock:
            if not vault.unlocked:
                return {"locked": True}
            stats = P.apply(vault.data, res, skip)
            persist()
        STATE["last_refresh"] = time.time()
        if skip and market in (None, "IN"):  # broker-synced stocks get fresh prices from the broker itself
            synced = sync_all(auto=True)
            STATE["last_broker_sync"] = time.time()
            stats = {**stats, "broker": [{k: r.get(k) for k in ("ok", "stocks", "funds", "error")} for r in synced]}
        return stats
    finally:
        STATE["refreshing"] = False
        REFRESH_LOCK.release()
        if STATE.get("pending"):
            refresh_soon()


def refresh_soon():
    threading.Thread(target=run_refresh, daemon=True).start()


# ---------------------------------------------------------------- broker sync
CONN_FIELDS = {"broker": S, "label": S, "owner": S, "api_key": S, "api_secret": S, "pin": S, "auto_sync": S}
SECRET_FIELDS = ("api_secret", "pin", "access_token", "refresh_token")


def public_conn(c: dict) -> dict:
    out = {k: v for k, v in c.items() if k not in SECRET_FIELDS}
    out["api_key"] = (c.get("api_key") or "")[:4] + "…" if c.get("api_key") else ""
    out["api_key_full"] = c.get("api_key") or ""  # app id / api key is not a secret; secret & tokens are
    out["has_secret"] = bool(c.get("api_secret"))
    out["has_pin"] = bool(c.get("pin"))
    out["logged_in"] = bool(c.get("access_token"))
    out["redirect_uri"] = B.redirect_uri(c.get("broker", "zerodha"), PORT)
    return out


def _conn(cid: str) -> dict:
    c = next((x for x in vault.data.setdefault("connections", []) if x["id"] == cid), None)
    if not c:
        raise HTTPException(404, "connection not found")
    return c


def broker_priced_sources() -> set:
    """Connections whose session is live — their holdings are priced by the broker, not Yahoo."""
    return {c["id"] for c in vault.data.get("connections", []) if c.get("access_token") and c.get("status") == "ok"}


def sync_connection(cid: str) -> dict:
    """Fetch from the broker outside the vault lock, then apply under it."""
    with vault.lock:
        if not vault.unlocked:
            return {"ok": False, "error": "locked"}
        conn = dict(_conn(cid))
    now = datetime.now().isoformat(timespec="minutes")
    upd: dict = {}
    try:
        if conn["broker"] == "zerodha":
            fetched = B.kite_fetch(conn)
        else:
            fetched, tok = B.fyers_fetch(conn)
            upd.update(tok or {})
        with vault.lock:
            c = _conn(cid)
            c.update(upd)
            stats = B.apply_sync(vault.data, c, fetched)
            c.update(status="ok", last_sync=now, last_error=None, warnings=fetched.get("warnings") or [],
                     last_counts=stats)
            persist()
        return {"ok": True, **stats, "warnings": fetched.get("warnings") or []}
    except B.AuthExpired as e:
        with vault.lock:
            c = _conn(cid)
            c.update(status="login_required", last_error=str(e), access_token=None if conn["broker"] == "zerodha" else c.get("access_token"))
            persist()
        return {"ok": False, "login_required": True, "error": str(e)}
    except Exception as e:
        with vault.lock:
            c = _conn(cid)
            c.update(status="error", last_error=(str(e) or type(e).__name__)[:200])
            persist()
        return {"ok": False, "error": (str(e) or type(e).__name__)[:200]}


def sync_all(auto: bool = False) -> list:
    if not SYNC_LOCK.acquire(blocking=False):
        return []
    try:
        with vault.lock:
            if not vault.unlocked:
                return []
            ids = [c["id"] for c in vault.data.get("connections", [])
                   if c.get("access_token") and (not auto or c.get("auto_sync", "1") != "0")]
        return [{"id": i, **sync_connection(i)} for i in ids]
    finally:
        SYNC_LOCK.release()


def _refresh_loop():
    while True:
        time.sleep(20)
        try:
            if vault.unlocked:
                mins = F.num(vault.data.get("settings", {}).get("auto_refresh_minutes"), 15)
                if mins > 0 and time.time() - STATE["last_refresh"] > mins * 60:
                    run_refresh()
                bmins = F.num(vault.data.get("settings", {}).get("broker_sync_minutes"), 30)
                if bmins > 0 and time.time() - STATE["last_broker_sync"] > bmins * 60:
                    STATE["last_broker_sync"] = time.time()
                    sync_all(auto=True)
        except Exception:
            pass


threading.Thread(target=_refresh_loop, daemon=True).start()


# ---------------------------------------------------------------- security middleware
OPEN_PATHS = {"/api/status", "/api/setup", "/api/unlock"}
PASSIVE_PATHS = {"/api/rev"}  # background polling must not keep the session awake


@app.middleware("http")
async def guard(request: Request, call_next):
    host = (request.headers.get("host") or "").split(":")[0]
    if host not in ("127.0.0.1", "localhost"):
        return JSONResponse({"detail": "Forbidden host"}, 403)  # blocks DNS-rebinding
    origin = request.headers.get("origin")
    if origin and urllib.parse.urlparse(origin).hostname not in ("127.0.0.1", "localhost"):
        return JSONResponse({"detail": "Forbidden origin"}, 403)
    path = request.url.path
    if path.startswith("/api/") and path not in OPEN_PATHS:
        tok = request.headers.get("x-session") or request.query_params.get("t") or ""
        if not (vault.unlocked and SESSION["token"] and hmac.compare_digest(tok, SESSION["token"])):
            return JSONResponse({"detail": "locked"}, 401)
        if path not in PASSIVE_PATHS:
            SESSION["last"] = time.time()
    resp = await call_next(request)
    resp.headers["Content-Security-Policy"] = ("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                                               "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")
    resp.headers["X-Frame-Options"] = "DENY"
    resp.headers["X-Content-Type-Options"] = "nosniff"
    resp.headers["Referrer-Policy"] = "no-referrer"
    if path.startswith("/api/"):
        resp.headers["Cache-Control"] = "no-store"
    elif path == "/" or path.startswith("/static/"):
        resp.headers["Cache-Control"] = "no-cache"  # always revalidate so app updates show up immediately
    return resp


def _auto_lock_loop():
    while True:
        time.sleep(15)
        try:
            if vault.unlocked:
                mins = F.num(vault.data.get("settings", {}).get("auto_lock_minutes"), 10)
                if mins > 0 and time.time() - SESSION["last"] > mins * 60:
                    vault.lock_now()
                    SESSION["token"] = None
        except Exception:
            pass


threading.Thread(target=_auto_lock_loop, daemon=True).start()


def _new_session() -> str:
    SESSION["token"] = secrets.token_urlsafe(32)
    SESSION["last"] = time.time()
    return SESSION["token"]


# ---------------------------------------------------------------- auth
@app.get("/api/status")
def status():
    return {"exists": vault.exists(), "unlocked": vault.unlocked, "data_dir": str(DATA_DIR)}


@app.post("/api/setup")
async def setup(req: Request):
    body = await req.json()
    pw = body.get("password") or ""
    if vault.exists():
        raise HTTPException(400, "Vault already exists")
    if len(pw) < 10:
        raise HTTPException(400, "Use a master password of at least 10 characters")
    data = new_data(body.get("members") or [])
    if body.get("base_currency") in F.CURRENCIES:
        data["settings"]["base_currency"] = body["base_currency"]
    vault.create(pw, data)
    persist()
    return {"token": _new_session()}


@app.post("/api/unlock")
async def unlock(req: Request):
    now = time.time()
    if now < FAILED["until"]:
        raise HTTPException(429, f"Too many attempts. Wait {int(FAILED['until'] - now) + 1}s")
    body = await req.json()
    try:
        vault.unlock(body.get("password") or "")
    except VaultError as e:
        FAILED["count"] += 1
        if FAILED["count"] >= 3:
            FAILED["until"] = now + min(2 ** (FAILED["count"] - 2), 300)
        raise HTTPException(401, str(e))
    FAILED["count"] = 0
    persist()  # records today's snapshot
    STATE["last_refresh"] = 0.0
    STATE["last_broker_sync"] = 0.0
    refresh_soon()
    return {"token": _new_session()}


@app.get("/api/rev")
def rev():
    return {"rev": STATE["rev"], "refreshing": STATE["refreshing"],
            "last_refresh": (vault.data or {}).get("last_refresh")}


@app.post("/api/refresh")
def refresh_all():
    r = run_refresh()
    if r.get("busy"):
        raise HTTPException(409, "A refresh is already running")
    return r


@app.get("/api/mf/search")
def mf_search(q: str):
    if len(q.strip()) < 3:
        return []
    try:
        return P.mf_search(q.strip())
    except Exception as e:
        raise HTTPException(502, f"Fund search unavailable ({type(e).__name__})")


@app.get("/api/quote/test")
def quote_test(symbol: str, kind: str = "market", market: str = "", exchange: str = "", override: str = ""):
    try:
        if kind == "holding":
            symbol = P.holding_symbol({"symbol": symbol, "market": market, "exchange": exchange, "quote_symbol": override})
        q = P.quote("nav" if kind == "nav" else "yahoo", symbol, fresh=True)
        return {"ok": True, "looked_up": symbol, **q}
    except Exception as e:
        return {"ok": False, "looked_up": symbol, "error": str(e)[:150] or type(e).__name__}


@app.post("/api/lock")
def lock():
    vault.lock_now()
    SESSION["token"] = None
    return {"ok": True}


@app.post("/api/change-password")
async def change_password(req: Request):
    body = await req.json()
    new = body.get("new") or ""
    if len(new) < 10:
        raise HTTPException(400, "New password must be at least 10 characters")
    try:
        vault.change_password(body.get("old") or "", new)
    except VaultError as e:
        raise HTTPException(400, str(e))
    return {"ok": True}


# ---------------------------------------------------------------- reads
@app.get("/api/ping")
def ping():
    return {"ok": True}


@app.get("/api/meta")
def meta():
    return {"currencies": F.CURRENCIES, "asset_classes": F.ASSET_CLASSES, "markets": F.MARKETS,
            "loan_types": F.LOAN_TYPES, "valuation_methods": F.VALUATION_METHODS, "default_method": F.DEFAULT_METHOD}


@app.get("/api/data")
def get_data():
    d = vault.data
    out = {k: d.get(k) for k in ("settings", "fx", "fx_updated", "last_refresh", "members", "holdings", "loans", "goals")}
    fx = d.get("fx") or F.DEFAULT_FX
    out["assets"] = []
    for a in d.get("assets", []):
        v, c, _ = F.asset_value(a, fx)
        out["assets"].append({**a, "calc_value": round(v, 2), "calc_cost": round(c, 2)})
    out["connections"] = [public_conn(c) for c in d.get("connections", [])]
    out["rev"] = STATE["rev"]
    return out


@app.get("/api/summary")
def summary(currency: str = "AED", who: str = "family"):
    if currency not in F.CURRENCIES:
        raise HTTPException(400, "bad currency")
    return F.summarize(vault.data, currency, who)


@app.get("/api/loans/{lid}/schedule")
def loan_schedule(lid: str):
    ln = next((x for x in vault.data["loans"] if x["id"] == lid), None)
    if not ln:
        raise HTTPException(404, "not found")
    return {"loan": ln, "status": F.loan_status(ln), "schedule": F.amortization(ln)}


# ---------------------------------------------------------------- broker connections
@app.get("/api/connections")
def list_connections():
    return [public_conn(c) for c in vault.data.get("connections", [])]


def _clean_conn(body: dict) -> dict:
    out = {k: str(body[k]).strip()[:300] for k in CONN_FIELDS if k in body and body[k] is not None}
    if "broker" in out and out["broker"] not in ("zerodha", "fyers"):
        raise HTTPException(400, "broker must be zerodha or fyers")
    for k in ("api_secret", "pin"):  # blank = keep existing secret
        if k in out and not out[k]:
            out.pop(k)
    if out.get("pin") and not out["pin"].isdigit():
        raise HTTPException(400, "PIN must be digits")
    return out


@app.post("/api/connections")
async def add_connection(req: Request):
    c = _clean_conn(await req.json())
    if not c.get("broker") or not c.get("api_key") or not c.get("api_secret"):
        raise HTTPException(400, "Broker, API key / App ID and secret are required")
    c.update(id=uuid.uuid4().hex[:10], status="new", created=datetime.now().isoformat(timespec="seconds"))
    with vault.lock:
        vault.data.setdefault("connections", []).append(c)
        persist()
    return public_conn(c)


@app.put("/api/connections/{cid}")
async def edit_connection(cid: str, req: Request):
    patch = _clean_conn(await req.json())
    patch.pop("broker", None)
    with vault.lock:
        c = _conn(cid)
        if ("api_key" in patch and patch["api_key"] != c.get("api_key")) or "api_secret" in patch:
            c.update(access_token=None, refresh_token=None, status="new")  # credentials changed -> log in again
        c.update(patch)
        if "owner" in patch:  # keep synced items' owner in step
            for coll in ("holdings", "assets"):
                for it in vault.data[coll]:
                    if it.get("source") == cid:
                        it["owner"] = patch["owner"]
        persist()
        return public_conn(c)


@app.delete("/api/connections/{cid}")
def delete_connection(cid: str, purge: int = 0):
    with vault.lock:
        _conn(cid)
        vault.data["connections"] = [c for c in vault.data["connections"] if c["id"] != cid]
        for coll in ("holdings", "assets"):
            if purge:
                vault.data[coll] = [x for x in vault.data[coll] if x.get("source") != cid]
            else:  # keep as normal manual items
                for x in vault.data[coll]:
                    if x.get("source") == cid:
                        for k in ("source", "source_key", "source_label"):
                            x.pop(k, None)
                        if x.get("method") == "broker":
                            x["method"] = "manual"
                            x["value"] = round(F.num(x.get("units")) * F.num(x.get("live_price")), 2)
        persist()
    return {"ok": True}


@app.post("/api/connections/{cid}/login")
def connection_login(cid: str):
    c = _conn(cid)
    nonce = secrets.token_urlsafe(16)
    LOGIN_STATES[cid] = (nonce, time.time() + 600)
    url = B.kite_login_url(c, nonce) if c["broker"] == "zerodha" else B.fyers_login_url(c, nonce, PORT)
    return {"url": url}


@app.post("/api/connections/{cid}/sync")
def connection_sync(cid: str):
    _conn(cid)
    return sync_connection(cid)


@app.post("/api/connections/sync-all")
def connections_sync_all():
    return sync_all()


def _check_state(cid: str, nonce: str) -> None:
    st = LOGIN_STATES.pop(cid, None)
    if not st or not hmac.compare_digest(st[0], nonce or "") or time.time() > st[1]:
        raise HTTPException(400, "Login link expired or invalid — start again from the Brokers page")


def _back(msg: str, ok: bool) -> RedirectResponse:
    return RedirectResponse(f"/#brokers?{'ok' if ok else 'err'}={urllib.parse.quote(msg)}", status_code=303)


@app.get("/connect/zerodha")
def zerodha_callback(request_token: str = "", status: str = "", c: str = "", s: str = ""):
    if not vault.unlocked:
        return _back("App was locked — unlock and try again", False)
    try:
        _check_state(c, s)
        if status != "success" or not request_token:
            return _back("Zerodha login cancelled", False)
        conn = dict(_conn(c))
        tok = B.kite_exchange(conn, request_token)
        with vault.lock:
            _conn(c).update(tok, status="ok", last_error=None, token_at=datetime.now().isoformat(timespec="minutes"))
            persist()
    except HTTPException as e:
        return _back(e.detail, False)
    except Exception as e:
        return _back(str(e)[:150], False)
    r = sync_connection(c)
    return _back(f"Zerodha connected · {r.get('stocks', 0)} stocks, {r.get('funds', 0)} funds synced" if r.get("ok") else r.get("error", "sync failed"), r.get("ok", False))


@app.get("/connect/fyers")
def fyers_callback(auth_code: str = "", state: str = "", s: str = ""):
    if not vault.unlocked:
        return _back("App was locked — unlock and try again", False)
    cid, _, nonce = state.partition(".")
    try:
        _check_state(cid, nonce)
        if s != "ok" or not auth_code:
            return _back("Fyers login cancelled", False)
        conn = dict(_conn(cid))
        tok = B.fyers_exchange(conn, auth_code)
        with vault.lock:
            _conn(cid).update(tok, status="ok", last_error=None, token_at=datetime.now().isoformat(timespec="minutes"))
            persist()
    except HTTPException as e:
        return _back(e.detail, False)
    except Exception as e:
        return _back(str(e)[:150], False)
    r = sync_connection(cid)
    return _back(f"Fyers connected · {r.get('stocks', 0)} stocks, {r.get('funds', 0)} funds synced" if r.get("ok") else r.get("error", "sync failed"), r.get("ok", False))


# ---------------------------------------------------------------- CRUD
@app.post("/api/{coll}")
async def create_item(coll: str, req: Request):
    if coll not in SCHEMAS:
        raise HTTPException(404, "unknown collection")
    item = clean(coll, await req.json())
    item["id"] = uuid.uuid4().hex[:10]
    item["updated"] = datetime.now().isoformat(timespec="seconds")
    with vault.lock:
        vault.data.setdefault(coll, []).append(item)
        persist()
    if coll == "holdings" or item.get("method") in ("market", "nav"):
        refresh_soon()
    return item


@app.put("/api/{coll}/{iid}")
async def update_item(coll: str, iid: str, req: Request):
    if coll not in SCHEMAS:
        raise HTTPException(404, "unknown collection")
    patch = clean(coll, await req.json())
    with vault.lock:
        for it in vault.data.get(coll, []):
            if it["id"] == iid:
                changed_src = any(k in patch and patch[k] != it.get(k)
                                  for k in ("symbol", "method", "quote_symbol", "exchange", "market"))
                if changed_src:
                    for k in ("live_price", "live_ccy", "live_prev", "live_name", "price_error"):
                        it.pop(k, None)
                it.update(patch)
                it["updated"] = datetime.now().isoformat(timespec="seconds")
                persist()
                if changed_src and (coll == "holdings" or it.get("method") in ("market", "nav")):
                    refresh_soon()
                return it
    raise HTTPException(404, "not found")


@app.delete("/api/{coll}/{iid}")
def delete_item(coll: str, iid: str):
    if coll not in SCHEMAS:
        raise HTTPException(404, "unknown collection")
    with vault.lock:
        d = vault.data
        if coll == "members":
            if len(d["members"]) <= 1:
                raise HTTPException(400, "At least one member is required")
            for c in ("assets", "holdings", "loans", "goals"):  # orphaned items become joint
                for it in d.get(c, []):
                    if it.get("owner") == iid:
                        it["owner"] = "joint"
        if coll == "goals":
            for c in ("assets", "holdings"):
                for it in d.get(c, []):
                    if it.get("goal_id") == iid:
                        it["goal_id"] = ""
        before = len(d.get(coll, []))
        d[coll] = [x for x in d.get(coll, []) if x["id"] != iid]
        if len(d[coll]) == before:
            raise HTTPException(404, "not found")
        persist()
    return {"ok": True}


# ---------------------------------------------------------------- settings & FX
@app.put("/api/settings")
async def put_settings(req: Request):
    body = await req.json()
    s = vault.data["settings"]
    if body.get("base_currency") in F.CURRENCIES:
        s["base_currency"] = body["base_currency"]
    if "auto_lock_minutes" in body:
        s["auto_lock_minutes"] = max(0, min(int(F.num(body["auto_lock_minutes"], 10)), 240))
    if "broker_sync_minutes" in body:
        s["broker_sync_minutes"] = max(0, min(int(F.num(body["broker_sync_minutes"], 30)), 1440))
    if "auto_refresh_minutes" in body:
        s["auto_refresh_minutes"] = max(0, min(int(F.num(body["auto_refresh_minutes"], 15)), 1440))
    persist()
    return s


@app.put("/api/fx")
async def put_fx(req: Request):
    body = await req.json()
    for c in ("AED", "INR"):
        v = F.num(body.get(c), 0)
        if v > 0:
            vault.data["fx"][c] = v
    vault.data["fx"]["USD"] = 1.0
    vault.data["fx_updated"] = datetime.now().isoformat(timespec="minutes") + " (manual)"
    persist()
    return {"fx": vault.data["fx"], "fx_updated": vault.data["fx_updated"]}


@app.post("/api/fx/refresh")
def refresh_fx():
    try:
        vault.data["fx"] = P.fx_rates()
        vault.data["fx_updated"] = datetime.now().isoformat(timespec="minutes") + " (open.er-api.com)"
    except Exception as e:
        raise HTTPException(502, f"Could not fetch rates ({type(e).__name__}). Enter them manually in Settings.")
    persist()
    return {"fx": vault.data["fx"], "fx_updated": vault.data["fx_updated"]}


@app.post("/api/prices/refresh")
async def refresh_prices(req: Request):
    body = await req.json() if (await req.body()) else {}
    r = run_refresh(body.get("market"))
    if r.get("busy"):
        raise HTTPException(409, "A refresh is already running — try again in a few seconds")
    return r


# ---------------------------------------------------------------- export
@app.get("/api/export/encrypted")
def export_encrypted():
    return Response(vault.encrypted_export(), media_type="application/octet-stream",
                    headers={"Content-Disposition": f'attachment; filename="family-wealth-{datetime.now():%Y%m%d}.enc"'})


@app.get("/api/export/csv/{coll}")
def export_csv(coll: str):
    if coll not in SCHEMAS:
        raise HTTPException(404, "unknown collection")
    rows = vault.data.get(coll, [])
    cols = ["id"] + list(SCHEMAS[coll].keys())
    buf = io.StringIO()
    w = csv.DictWriter(buf, fieldnames=cols, extrasaction="ignore")
    w.writeheader()
    for r in rows:
        w.writerow(r)
    return Response(buf.getvalue(), media_type="text/csv",
                    headers={"Content-Disposition": f'attachment; filename="{coll}.csv"'})


# ---------------------------------------------------------------- static
app.mount("/static", StaticFiles(directory=STATIC), name="static")


@app.get("/")
def index():
    return FileResponse(STATIC / "index.html")


def main():
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning")


if __name__ == "__main__":
    main()
