#!/usr/bin/env python3
"""statusline.py がバックグラウンドで起動し、hub へ残量を送る。

statusLine 本体を待たせないため別プロセスに分けている。
送信に失敗しても何も出力しない。値が古いことはウィジェット側が
updated_at から判断してグレー表示にする（docs/decisions.md D1）。
"""

import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
TIMEOUT_SEC = 10


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
    for key in ("LIMITCHECKER_TOKEN", "LIMITCHECKER_HUB_URL",
                "LIMITCHECKER_BIND", "LIMITCHECKER_PORT"):
        if os.environ.get(key):
            env[key] = os.environ[key]
    return env


def hub_url(env: dict) -> str | None:
    """送信先を決める。

    hub と同じマシンでは `.env` を共有しているので、待ち受け設定から導出できる。
    LIMITCHECKER_HUB_URL を書き忘れても動くようにするため（D40）。

    注意: BIND が Tailscale のアドレスなら、hub は 127.0.0.1 で待っていない。
    同じマシンからでもそのアドレスへ送る必要がある。
    """
    explicit = env.get("LIMITCHECKER_HUB_URL")
    if explicit:
        return explicit.rstrip("/")

    bind = (env.get("LIMITCHECKER_BIND") or "").strip()
    port = (env.get("LIMITCHECKER_PORT") or "8787").strip()
    if not bind:
        return None
    if bind.lower() == "tailscale":
        # hub 側と同じ引き方をする
        try:
            out = subprocess.run(
                ["tailscale", "ip", "-4"], capture_output=True, text=True, timeout=10
            )
            for line in out.stdout.splitlines():
                candidate = line.strip()
                if re.match(
                    r"^100\.(6[4-9]|[7-9][0-9]|1[0-1][0-9]|12[0-7])\."
                    r"[0-9]{1,3}\.[0-9]{1,3}$",
                    candidate,
                ):
                    return f"http://{candidate}:{port}"
        except (OSError, subprocess.SubprocessError):
            return None
        return None
    return f"http://{bind}:{port}"


def main() -> int:
    env = load_env()
    hub = hub_url(env)
    token = env.get("LIMITCHECKER_TOKEN")
    if not hub or not token:
        return 0

    try:
        body = sys.stdin.buffer.read()
        json.loads(body)  # 壊れた内容を送らない
    except (OSError, ValueError):
        return 0

    request = urllib.request.Request(
        hub + "/ingest",
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SEC):
            pass
    except (urllib.error.URLError, OSError, ValueError):
        # 到達できないのは普通のこと（hub 停止中、圏外、VPN 未接続）。黙って諦める。
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
