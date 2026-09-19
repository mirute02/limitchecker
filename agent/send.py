#!/usr/bin/env python3
"""statusline.py がバックグラウンドで起動し、hub へ残量を送る。

statusLine 本体を待たせないため別プロセスに分けている。
送信に失敗しても何も出力しない。値が古いことはウィジェット側が
updated_at から判断してグレー表示にする（docs/decisions.md D1）。
"""

import json
import os
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
    for key in ("LIMITCHECKER_TOKEN", "LIMITCHECKER_HUB_URL"):
        if os.environ.get(key):
            env[key] = os.environ[key]
    return env


def main() -> int:
    env = load_env()
    hub = env.get("LIMITCHECKER_HUB_URL")
    token = env.get("LIMITCHECKER_TOKEN")
    if not hub or not token:
        return 0

    try:
        body = sys.stdin.buffer.read()
        json.loads(body)  # 壊れた内容を送らない
    except (OSError, ValueError):
        return 0

    request = urllib.request.Request(
        hub.rstrip("/") + "/ingest",
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
