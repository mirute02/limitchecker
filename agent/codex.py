#!/usr/bin/env python3
"""Codex の残量を取り、hub へ送る。

Codex App Server の JSON-RPC `account/rateLimits/read` を使う。

**`~/.codex/auth.json` を読まない。** 認証は app-server が自分で扱うため、
こちらは JSON-RPC を投げるだけでよい（docs/decisions.md D28）。
これは Claude Code 側で statusLine の出力を受け取るのと同じ立場で、
「ログイン済みの CLI が取得した結果を見る」方針を保っている。

`thread/start` も `turn/start` も行わないためモデルへの推論要求にはならず、
5時間枠・週次枠を消費しない。

Claude Code の statusLine と違い、こちらは呼んでくれる相手がいないので、
cron / systemd timer / launchd から定期的に実行する。
"""

import json
import os
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STATE_DIR = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local/state")) / "limitchecker"

# app-server の起動と応答を待つ上限
TIMEOUT_SEC = 60

# rate limit の窓。分単位で返るので、これで5時間枠と週次枠を見分ける。
WINDOW_FIVE_HOUR_MIN = 300
WINDOW_WEEK_MIN = 10_080


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
    for key in (
        "LIMITCHECKER_TOKEN",
        "LIMITCHECKER_HUB_URL",
        "LIMITCHECKER_MACHINE_ID",
        "LIMITCHECKER_MACHINE_LABEL",
        "LIMITCHECKER_CODEX_ACCOUNT",
        "LIMITCHECKER_CODEX_BIN",
        "CODEX_BIN",
    ):
        if os.environ.get(key):
            env[key] = os.environ[key]
    return env


def fetch_rate_limits(codex_bin: str) -> dict | None:
    """app-server に問い合わせて rateLimits を得る。失敗したら None。

    標準入力を閉じると応答前にサーバが終了するため、応答を受け取るまで開けておく。
    """
    requests = (
        '{"method":"initialize","id":1,"params":{"clientInfo":'
        '{"name":"limitchecker","title":"limitchecker","version":"0.1.0"}}}\n'
        '{"method":"initialized","params":{}}\n'
        '{"method":"account/rateLimits/read","id":2}\n'
    )

    try:
        proc = subprocess.Popen(
            [codex_bin, "app-server"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            # 自分をリーダーとする新しいプロセスグループで起動する。
            # こうしないと、ラッパー越しに起動した場合に孫プロセスが残る。
            start_new_session=True,
        )
    except (OSError, ValueError):
        return None

    # 読み取りを別スレッドに逃がす。readline() はブロックするので、
    # 同じスレッドで締切を見ても評価されない。応答しない app-server を
    # 相手にすると、cron が10分ごとに起動する子プロセスが溜まる（D43）。
    result: list = []

    def reader() -> None:
        try:
            for line in proc.stdout:
                try:
                    message = json.loads(line)
                except ValueError:
                    continue
                if message.get("id") != 2:
                    continue
                if "error" not in message and isinstance(message.get("result"), dict):
                    result.append(message["result"])
                return
        except (OSError, ValueError):
            pass

    thread = threading.Thread(target=reader, daemon=True)
    try:
        proc.stdin.write(requests)
        proc.stdin.flush()
    except OSError:
        pass

    # **stdin は閉じない。** 閉じると app-server が応答前に終了する。
    # 最初にこの経路を調べたときに判明した挙動で、書き直しで一度見失った。
    thread.start()
    thread.join(timeout=TIMEOUT_SEC)

    try:
        proc.stdin.close()
    except OSError:
        pass

    # 応答を得ても得なくても、子プロセスは必ず落とす。
    # ラッパー経由だと孫が残るため、プロセスグループごと落とす。
    def stop(sig: int) -> None:
        try:
            os.killpg(os.getpgid(proc.pid), sig)
        except OSError:
            try:
                proc.send_signal(sig)
            except OSError:
                pass

    try:
        stop(signal.SIGTERM)
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            stop(signal.SIGKILL)
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
    except OSError:
        pass

    # stdout は閉じない。読み取りスレッドがバッファのロックを持ったままなので、
    # 別スレッドから close するとデッドロックする（実測で確認）。
    # プロセス終了時に解放されるので閉じる必要もない。
    return result[0] if result else None


def extract_rings(result: dict) -> list:
    """rateLimits から残量だけをホワイトリストで抜き出す。

    Claude 側の agent と同じ方針。プラン名や残クレジットなど、
    表示に要らないものは取り込まない。
    """
    limits = result.get("rateLimits")
    if not isinstance(limits, dict):
        return []

    rings = []
    for key in ("primary", "secondary"):
        window = limits.get(key)
        if not isinstance(window, dict):
            continue
        used = window.get("usedPercent")
        if not isinstance(used, (int, float)) or isinstance(used, bool):
            continue
        minutes = window.get("windowDurationMins")
        if minutes == WINDOW_FIVE_HOUR_MIN:
            slot, label = "outer", "5h"
        elif isinstance(minutes, (int, float)) and minutes >= WINDOW_WEEK_MIN:
            slot, label = "middle", "7d"
        else:
            continue

        resets_at = window.get("resetsAt")
        rings.append(
            {
                "slot": slot,
                "label": label,
                "remaining": round(max(0.0, min(1.0, 1.0 - used / 100.0)), 4),
                "resets_at": int(resets_at)
                if isinstance(resets_at, (int, float)) and not isinstance(resets_at, bool)
                else None,
            }
        )
    return rings


def main() -> int:
    env = load_env()
    # Termux など、通信に回避策の要る環境ではラッパーを指すこと。
    # alias はスクリプト内で展開されないため、ここで明示する必要がある。
    codex_bin = env.get("LIMITCHECKER_CODEX_BIN") or env.get("CODEX_BIN") or "codex"

    result = fetch_rate_limits(codex_bin)
    if result is None:
        print("limitchecker: Codex の残量を取得できません", file=sys.stderr)
        return 1

    rings = extract_rings(result)
    if not rings:
        print("limitchecker: Codex の応答に残量が含まれていません", file=sys.stderr)
        return 1

    report = {
        "service": "codex",
        "account": env.get("LIMITCHECKER_CODEX_ACCOUNT", "default"),
        "machine_id": env.get("LIMITCHECKER_MACHINE_ID", "local"),
        "machine_label": env.get("LIMITCHECKER_MACHINE_LABEL", "Local"),
        "rings": rings,
        "updated_at": int(time.time()),
    }

    STATE_DIR.mkdir(parents=True, exist_ok=True)
    try:
        STATE_DIR.chmod(0o700)
    except OSError:
        pass
    path = STATE_DIR / "codex-state.json"
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(report, ensure_ascii=False))
    try:
        tmp.chmod(0o600)
    except OSError:
        pass
    tmp.replace(path)

    # hub へ送るのは Claude 側と同じ経路を使う
    sender = Path(__file__).resolve().parent / "send.py"
    try:
        child = subprocess.Popen(
            [sys.executable, str(sender)],
            stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        child.stdin.write(json.dumps(report).encode())
        child.stdin.close()
        child.wait(timeout=30)
    except Exception:
        pass

    for ring in rings:
        print(f"{ring['label']} {round(ring['remaining'] * 100)}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())
