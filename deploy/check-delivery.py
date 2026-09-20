#!/usr/bin/env python3
"""agent から hub へ実際に届くかを試す。

agent は送信先が分からないと黙って終わる。ステータス行には残量が出るため、
動いているように見えて hub には何も届いていない、という状態になりうる（D40）。
それを見つけるための確認。
"""

import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

PROBE_ID = "connectivity-probe"


def main() -> int:
    repo = pathlib.Path(sys.argv[1])
    sys.path.insert(0, str(repo / "agent"))
    try:
        import send as sender
    except Exception as e:
        print(f"  agent/send.py を読み込めません: {type(e).__name__}")
        return 0

    env = sender.load_env()
    url = sender.hub_url(env)
    token = env.get("LIMITCHECKER_TOKEN")

    if not token:
        print("  LIMITCHECKER_TOKEN が未設定です。")
        return 0
    if not url:
        print("  送信先が決まりません。")
        print("  .env に LIMITCHECKER_HUB_URL を書くか、LIMITCHECKER_BIND を設定してください。")
        return 0

    print(f"  送信先: {url}")

    # /ping は検証だけして保存しない。偽の残量で本物を上書きしないため（D41）。
    probe = {
        "service": "claude_code",
        "account": env.get("LIMITCHECKER_CLAUDE_ACCOUNT", "default"),
        "machine_id": env.get("LIMITCHECKER_MACHINE_ID", "local"),
        "machine_label": "probe",
        "rings": [
            {"slot": "outer", "label": "5h", "remaining": 1.0,
             "resets_at": int(time.time()) + 60}
        ],
        "updated_at": int(time.time()),
    }
    request = urllib.request.Request(
        url + "/ping",
        data=json.dumps(probe).encode(),
        method="POST",
        headers={"Authorization": f"Bearer {token}",
                 "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            result = json.loads(response.read().decode())
    except urllib.error.HTTPError as e:
        if e.code in (401, 403):
            print("  トークンが合っていません。hub 側の .env と同じ値か確認してください。")
        elif e.code == 404:
            print("  hub が古いままです。hub を置いたマシンで更新してください。")
            print("    git pull && ./deploy/install-hub.sh")
        else:
            print(f"  hub が {e.code} を返しました。")
        return 0
    except Exception:
        print("  hub に接続できません。hub が動いているか確認してください。")
        print("    ./deploy/install-hub.sh --status")
        return 0

    if result.get("accepted"):
        print("  OK: 送信が届き、内容も受理されました。")
        print("  （確認のための通信で、残量の値は書き換えていません）")
    else:
        print("  hub には届きましたが、内容が受理されませんでした。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
