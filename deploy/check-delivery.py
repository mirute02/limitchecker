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

    probe = {
        "service": "claude_code",
        "account": env.get("LIMITCHECKER_CLAUDE_ACCOUNT", "default"),
        "machine_id": PROBE_ID,
        "machine_label": "probe",
        "rings": [
            {"slot": "outer", "label": "5h", "remaining": 1.0,
             "resets_at": int(time.time()) + 60}
        ],
        "updated_at": int(time.time()),
    }
    subprocess.run(
        [sys.executable, str(repo / "agent" / "send.py")],
        input=json.dumps(probe).encode(),
        capture_output=True, timeout=30,
    )

    request = urllib.request.Request(
        url + "/status", headers={"Authorization": f"Bearer {token}"}
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            data = json.loads(response.read().decode())
    except urllib.error.HTTPError as e:
        print(f"  hub が {e.code} を返しました。トークンが合っているか確認してください。")
        return 0
    except Exception:
        print("  hub に接続できません。hub が動いているか確認してください。")
        print("    ./deploy/install-hub.sh --status")
        return 0

    ids = [m["id"] for m in data.get("machines", [])]
    if PROBE_ID in ids:
        print("  OK: 送信が届きました。")
    else:
        print("  hub には繋がりましたが、送ったものが記録されていません。")
        print(f"  hub が見ているマシン: {ids or 'なし'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
