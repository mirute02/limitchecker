#!/usr/bin/env python3
"""接続コードを発行する。

43文字のトークンを手で転記する代わりに、6桁のコードで一度だけ交換する。

    python3 hub/pair.py
    接続コード: 482913（5分間有効、1回限り）

短いコードは総当たりが効くため、次の3つを必ず揃える。
  - 有効期限を短くする（既定5分）
  - 試行回数を制限する（5回で無効化）
  - 一度使ったら消す

コード自体は保存しない。ハッシュだけを置き、hub 側で定数時間比較する。
"""

import hashlib
import json
import os
import secrets
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STATE_DIR = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local/state")) / "limitchecker"
PAIRING_PATH = STATE_DIR / "pairing.json"

TTL_SEC = 300
MAX_ATTEMPTS = 5
CODE_DIGITS = 6


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
    if os.environ.get("LIMITCHECKER_TOKEN"):
        env["LIMITCHECKER_TOKEN"] = os.environ["LIMITCHECKER_TOKEN"]
    return env


def hash_code(code: str) -> str:
    return hashlib.sha256(code.encode("utf-8")).hexdigest()


def main() -> int:
    env = load_env()
    if not env.get("LIMITCHECKER_TOKEN"):
        print("LIMITCHECKER_TOKEN が未設定です。先に hub を設定してください。", file=sys.stderr)
        return 2

    # 予測されないよう secrets を使う。連番や時刻由来は使わない。
    code = "".join(secrets.choice("0123456789") for _ in range(CODE_DIGITS))

    STATE_DIR.mkdir(parents=True, exist_ok=True)
    try:
        STATE_DIR.chmod(0o700)
    except OSError:
        pass

    record = {
        "code_hash": hash_code(code),
        "expires_at": int(time.time()) + TTL_SEC,
        "attempts": 0,
    }
    tmp = PAIRING_PATH.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(record))
    try:
        tmp.chmod(0o600)
    except OSError:
        pass
    tmp.replace(PAIRING_PATH)

    bind = env.get("LIMITCHECKER_BIND", "127.0.0.1")
    port = env.get("LIMITCHECKER_PORT", "8787")

    print()
    print(f"  接続コード: {code}")
    print(f"  hub の URL: http://{bind}:{port}")
    print()
    print(f"  {TTL_SEC // 60}分間有効。1回使うと無効になります。")
    print("  Android アプリで URL とこのコードを入れてください。")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
