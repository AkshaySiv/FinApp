"""Encrypted on-disk vault.

Your data lives in ONE file, encrypted with AES-256-GCM. The key is derived from
your master password with scrypt (memory-hard, slows down brute force). The
password and key are never written to disk; the key lives only in memory while
the app is unlocked and is wiped on lock / auto-lock / shutdown.
"""
from __future__ import annotations

import base64
import json
import os
import shutil
import threading
import time
from datetime import datetime
from pathlib import Path

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

FORMAT = "fwv1"
AAD = b"family-wealth-vault-v1"
SCRYPT_N, SCRYPT_R, SCRYPT_P = 2**17, 8, 1  # ~128 MB, ~0.3-0.6s on Apple silicon
BACKUP_KEEP = 30
BACKUP_MIN_INTERVAL = 3600  # seconds between automatic backups


def _b64(b: bytes) -> str:
    return base64.b64encode(b).decode()


def _unb64(s: str) -> bytes:
    return base64.b64decode(s.encode())


def _derive(password: str, salt: bytes, n=SCRYPT_N, r=SCRYPT_R, p=SCRYPT_P) -> bytes:
    return Scrypt(salt=salt, length=32, n=n, r=r, p=p).derive(password.encode("utf-8"))


class VaultError(Exception):
    pass


class Vault:
    def __init__(self, data_dir: Path):
        self.dir = Path(data_dir)
        self.path = self.dir / "vault.enc"
        self.backup_dir = self.dir / "backups"
        self._key: bytes | None = None
        self._kdf: dict | None = None
        self.data: dict | None = None
        self.lock = threading.RLock()
        self._last_backup = 0.0

    # ---------- state ----------
    def exists(self) -> bool:
        return self.path.exists()

    @property
    def unlocked(self) -> bool:
        return self._key is not None and self.data is not None

    # ---------- lifecycle ----------
    def create(self, password: str, initial: dict) -> None:
        with self.lock:
            if self.exists():
                raise VaultError("Vault already exists")
            self._ensure_dirs()
            salt = os.urandom(16)
            self._kdf = {"name": "scrypt", "n": SCRYPT_N, "r": SCRYPT_R, "p": SCRYPT_P, "salt": _b64(salt)}
            self._key = _derive(password, salt)
            self.data = initial
            self.save()

    def unlock(self, password: str) -> None:
        with self.lock:
            blob = json.loads(self.path.read_text())
            if blob.get("format") != FORMAT:
                raise VaultError("Unknown vault format")
            kdf = blob["kdf"]
            key = _derive(password, _unb64(kdf["salt"]), kdf["n"], kdf["r"], kdf["p"])
            try:
                pt = AESGCM(key).decrypt(_unb64(blob["nonce"]), _unb64(blob["ct"]), AAD)
            except InvalidTag:
                raise VaultError("Wrong password") from None
            self._key, self._kdf = key, kdf
            self.data = json.loads(pt.decode("utf-8"))

    def lock_now(self) -> None:
        with self.lock:
            self._key = None
            self._kdf = None
            self.data = None

    def change_password(self, old: str, new: str) -> None:
        with self.lock:
            blob = json.loads(self.path.read_text())
            kdf = blob["kdf"]
            key = _derive(old, _unb64(kdf["salt"]), kdf["n"], kdf["r"], kdf["p"])
            if key != self._key:
                raise VaultError("Current password is incorrect")
            salt = os.urandom(16)
            self._kdf = {"name": "scrypt", "n": SCRYPT_N, "r": SCRYPT_R, "p": SCRYPT_P, "salt": _b64(salt)}
            self._key = _derive(new, salt)
            self._backup(force=True)
            self.save(skip_backup=True)

    # ---------- persistence ----------
    def save(self, skip_backup: bool = False) -> None:
        with self.lock:
            if not self.unlocked:
                raise VaultError("Vault is locked")
            if not skip_backup:
                self._backup()
            nonce = os.urandom(12)
            ct = AESGCM(self._key).encrypt(nonce, json.dumps(self.data, separators=(",", ":")).encode(), AAD)
            blob = {"format": FORMAT, "kdf": self._kdf, "nonce": _b64(nonce), "ct": _b64(ct),
                    "saved_at": datetime.now().isoformat(timespec="seconds")}
            tmp = self.path.with_suffix(".tmp")
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as f:
                json.dump(blob, f)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, self.path)
            os.chmod(self.path, 0o600)

    def encrypted_export(self) -> bytes:
        """The encrypted file as-is — safe to copy to iCloud / USB as a backup."""
        return self.path.read_bytes()

    def _ensure_dirs(self) -> None:
        for d in (self.dir, self.backup_dir):
            d.mkdir(parents=True, exist_ok=True)
            os.chmod(d, 0o700)

    def _backup(self, force: bool = False) -> None:
        if not self.path.exists():
            return
        now = time.time()
        if not force and now - self._last_backup < BACKUP_MIN_INTERVAL:
            return
        self._ensure_dirs()
        dst = self.backup_dir / f"vault-{datetime.now():%Y%m%d-%H%M%S}.enc"
        shutil.copy2(self.path, dst)
        os.chmod(dst, 0o600)
        self._last_backup = now
        backups = sorted(self.backup_dir.glob("vault-*.enc"))
        for old in backups[:-BACKUP_KEEP]:
            old.unlink(missing_ok=True)
