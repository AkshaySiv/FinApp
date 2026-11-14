"""Local AI via Ollama. Talks to the loopback interface only — nothing leaves this Mac."""
from __future__ import annotations

import json
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:11434"
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # never route loopback via a proxy


def status() -> dict:
    try:
        with _OPENER.open(BASE + "/api/tags", timeout=3) as r:
            j = json.loads(r.read().decode())
        return {"running": True, "models": [m["name"] for m in j.get("models", [])]}
    except Exception:
        return {"running": False, "models": []}


def chat_stream(model: str, messages: list[dict]):
    body = {"model": model, "messages": messages, "stream": True,
            "options": {"num_ctx": 8192, "temperature": 0.3}}
    req = urllib.request.Request(BASE + "/api/chat", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        r = _OPENER.open(req, timeout=600)
    except urllib.error.HTTPError as e:
        try:
            msg = json.loads(e.read().decode()).get("error")
        except Exception:
            msg = None
        raise RuntimeError(msg or f"Ollama error {e.code}") from None
    except Exception:
        raise RuntimeError("Ollama is not running. Start it with: ollama serve") from None
    with r:
        for line in r:
            if not line.strip():
                continue
            j = json.loads(line)
            if j.get("error"):
                raise RuntimeError(j["error"])
            c = (j.get("message") or {}).get("content")
            if c:
                yield c
            if j.get("done"):
                break
