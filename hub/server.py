#!/usr/bin/env python3
"""limitchecker hub — 各マシンの agent から残量を集約し、ウィジェットへ返す。

標準ライブラリのみで動く。依存は追加しない。

セキュリティ方針は docs/security.md。要点をコード側で強制する:
  - 既定の待ち受けは 127.0.0.1。0.0.0.0 は拒否する（設定ミスによる全世界公開を防ぐ）
  - 既定トークンを持たない。未設定なら起動しない
  - トークンの比較は hmac.compare_digest（定数時間）
  - Authorization ヘッダとリクエストボディをログに出さない
  - machine_id / account は文字種と長さを制限する
  - エラー応答に内部パスやスタックトレースを含めない
"""

import hmac
import json
import os
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STATE_DIR = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local/state")) / "limitchecker"
STORE_PATH = STATE_DIR / "hub.json"

# machine_id / account に許す形。ログやファイル名に載る値なので、
# パストラバーサルとログ汚染を防ぐために絞る。
ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")

MAX_BODY_BYTES = 64 * 1024
MIN_TOKEN_LEN = 16

SERVICE_LABELS = {"claude_code": "Claude Code", "codex": "Codex"}

_lock = threading.Lock()


def load_env() -> dict:
    env = {}
    path = Path(os.environ.get("LIMITCHECKER_ENV", REPO_ROOT / ".env"))
    try:
        for line in path.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            env[key.strip()] = value.strip()
    except OSError:
        pass
    for key in ("LIMITCHECKER_TOKEN", "LIMITCHECKER_BIND", "LIMITCHECKER_PORT"):
        if os.environ.get(key):
            env[key] = os.environ[key]
    return env


def load_store() -> dict:
    try:
        data = json.loads(STORE_PATH.read_text())
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def save_store(store: dict) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    try:
        STATE_DIR.chmod(0o700)
    except OSError:
        pass
    tmp = STORE_PATH.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(store, ensure_ascii=False))
    try:
        tmp.chmod(0o600)
    except OSError:
        pass
    tmp.replace(STORE_PATH)


def clean_report(payload: dict) -> dict | None:
    """agent から届いた内容を検証し、必要なフィールドだけを取り出す。

    agent 側と同じくホワイトリストで扱う。想定外のフィールドは保存しない。
    """
    if not isinstance(payload, dict):
        return None

    service = payload.get("service")
    if service not in SERVICE_LABELS:
        return None

    machine_id = payload.get("machine_id")
    account = payload.get("account")
    if not (isinstance(machine_id, str) and ID_RE.match(machine_id)):
        return None
    if not (isinstance(account, str) and ID_RE.match(account)):
        return None

    label = payload.get("machine_label")
    if not isinstance(label, str) or not label.strip():
        label = machine_id
    label = label.strip()[:32]

    rings = []
    for ring in payload.get("rings") or []:
        if not isinstance(ring, dict):
            continue
        slot = ring.get("slot")
        remaining = ring.get("remaining")
        if slot not in ("outer", "middle", "inner"):
            continue
        if not isinstance(remaining, (int, float)) or isinstance(remaining, bool):
            continue
        ring_label = ring.get("label")
        resets_at = ring.get("resets_at")
        rings.append(
            {
                "slot": slot,
                "label": str(ring_label)[:16] if isinstance(ring_label, str) else slot,
                "remaining": round(max(0.0, min(1.0, float(remaining))), 4),
                "resets_at": int(resets_at)
                if isinstance(resets_at, (int, float)) and not isinstance(resets_at, bool)
                else None,
            }
        )
    if not rings:
        return None

    updated_at = payload.get("updated_at")
    if not isinstance(updated_at, (int, float)) or isinstance(updated_at, bool):
        updated_at = time.time()

    return {
        "service": service,
        "account": account,
        "machine_id": machine_id,
        "machine_label": label,
        "rings": rings,
        "updated_at": int(updated_at),
        "received_at": int(time.time()),
    }


def iso(ts) -> str | None:
    if ts is None:
        return None
    return time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime(int(ts)))


def build_status(store: dict) -> dict:
    """描画に必要なものを1回の応答で返す（docs/design.md の GET /status）。"""
    reports = [r for r in store.values() if isinstance(r, dict)]

    # 残量はアカウント単位に畳む。枠は共有なので合算せず、最も新しい報告を採用する。
    freshest: dict = {}
    for report in reports:
        key = (report["service"], report["account"])
        current = freshest.get(key)
        if current is None or report["updated_at"] > current["updated_at"]:
            freshest[key] = report

    services = []
    for service_id in ("claude_code", "codex"):
        picked = [r for (s, _), r in freshest.items() if s == service_id]
        if not picked:
            services.append(
                {
                    "id": service_id,
                    "label": SERVICE_LABELS[service_id],
                    "available": False,
                    "error": "usage_unavailable",
                }
            )
            continue
        best = max(picked, key=lambda r: r["updated_at"])
        services.append(
            {
                "id": service_id,
                "label": SERVICE_LABELS[service_id],
                "available": True,
                "account": best["account"],
                "updated_at": iso(best["updated_at"]),
                "rings": [
                    {
                        "slot": r["slot"],
                        "label": r["label"],
                        "remaining": r["remaining"],
                        "resets_at": iso(r["resets_at"]),
                    }
                    for r in best["rings"]
                ],
            }
        )

    machines = {}
    for report in reports:
        mid = report["machine_id"]
        current = machines.get(mid)
        if current is None or report["updated_at"] > current["updated_at"]:
            machines[mid] = report

    machine_list = [
        {
            "id": r["machine_id"],
            "label": r["machine_label"],
            # イベント検知は段階3。ここでは報告が届いていることだけを示す。
            "state": "idle",
            "unread": {"waiting_input": 0, "completed": 0},
            "last_seen": iso(r["updated_at"]),
        }
        for r in sorted(machines.values(), key=lambda r: r["machine_id"])
    ]

    return {
        "fetched_at": iso(time.time()),
        "services": services,
        "machines": machine_list,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "limitchecker"
    sys_version = ""
    token = ""

    def log_message(self, fmt, *args):
        """既定のログはリクエスト行をそのまま出す。パスとヘッダを載せないよう差し替える。"""
        sys.stderr.write(f"{time.strftime('%H:%M:%S')} {self.command} -> {args[1] if len(args) > 1 else ''}\n")

    def _send(self, code: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        prefix = "Bearer "
        if not header.startswith(prefix):
            return False
        # 定数時間比較。トークンはどこにも出力しない。
        return hmac.compare_digest(header[len(prefix):], self.token)

    def do_GET(self):
        if not self._authorized():
            self._send(401, {"error": "unauthorized"})
            return
        if self.path.split("?")[0] != "/status":
            self._send(404, {"error": "not_found"})
            return
        with _lock:
            status = build_status(load_store())
        self._send(200, status)

    def do_POST(self):
        if not self._authorized():
            self._send(401, {"error": "unauthorized"})
            return
        if self.path.split("?")[0] != "/ingest":
            self._send(404, {"error": "not_found"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._send(400, {"error": "bad_request"})
            return
        if length <= 0 or length > MAX_BODY_BYTES:
            self._send(413, {"error": "payload_too_large"})
            return

        try:
            payload = json.loads(self.rfile.read(length))
        except ValueError:
            self._send(400, {"error": "bad_request"})
            return

        report = clean_report(payload)
        if report is None:
            # 何が不正だったかは返さない（内部構造を推測させない）
            self._send(400, {"error": "bad_request"})
            return

        with _lock:
            store = load_store()
            store[f"{report['service']}:{report['machine_id']}"] = report
            save_store(store)
        self._send(200, {"ok": True})


def main() -> int:
    env = load_env()

    token = env.get("LIMITCHECKER_TOKEN", "")
    if not token:
        print(
            "LIMITCHECKER_TOKEN が未設定です。.env に設定してください。\n"
            '  生成: python3 -c "import secrets; print(secrets.token_urlsafe(32))"',
            file=sys.stderr,
        )
        return 2
    if len(token) < MIN_TOKEN_LEN:
        print(f"LIMITCHECKER_TOKEN が短すぎます（{MIN_TOKEN_LEN} 文字以上）。", file=sys.stderr)
        return 2

    bind = env.get("LIMITCHECKER_BIND", "127.0.0.1").strip()
    if bind in ("0.0.0.0", "::", ""):
        print(
            f"LIMITCHECKER_BIND に {bind or '(空)'} は指定できません。\n"
            "127.0.0.1 か、Tailscale のアドレスを明示してください。",
            file=sys.stderr,
        )
        return 2

    try:
        port = int(env.get("LIMITCHECKER_PORT", "8787"))
    except ValueError:
        print("LIMITCHECKER_PORT が数値ではありません。", file=sys.stderr)
        return 2

    Handler.token = token
    server = ThreadingHTTPServer((bind, port), Handler)
    print(f"limitchecker hub: http://{bind}:{port} で待ち受けます", file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
