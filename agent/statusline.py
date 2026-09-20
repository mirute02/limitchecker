#!/usr/bin/env python3
"""Claude Code の statusLine から呼ばれ、残量だけを抜き出して保存・送信する。

Claude Code は statusLine を頻繁に呼ぶ（実測で3分に20回）。ステータス行の描画を
ブロックするため、この処理は速く終える必要がある。hub への送信は間引いたうえで
バックグラウンドに逃がし、本体は即座に1行を返す。

セキュリティ上の不変条件（docs/security.md, docs/findings-statusline.md）:
  statusLine の JSON にはパス・セッション名・課金額・トークン使用量が含まれる。
  ここでは rate_limits だけを**明示的に拾う**。除外方式にすると Claude Code の
  更新で新しいフィールドが増えたときに漏れるため、必ずホワイトリストで扱う。
"""

import json
import os
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STATE_DIR = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local/state")) / "limitchecker"

# hub へ送る最短間隔（秒）。statusLine の呼び出し頻度に対して送信を間引く。
POST_INTERVAL_SEC = 60

# 残量の配色しきい値。ウィジェット側と同じ値を使う（docs/design.md）。
THRESHOLD_OK = 0.50
THRESHOLD_WARN = 0.20

# rate_limits のどのキーをどのリングに割り当てるか。
# D7 でリングは2周（外側=5時間枠 / 中央=週次枠）に確定している。
RING_MAP = (
    ("outer", "five_hour", "5h"),
    ("middle", "seven_day", "7d"),
)


def load_env() -> dict:
    """リポジトリ直下の .env を読む。無ければ空で動く（ローカル保存のみになる）。"""
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
    # 環境変数を優先する
    for key in list(env) + [
        "LIMITCHECKER_TOKEN",
        "LIMITCHECKER_HUB_URL",
        "LIMITCHECKER_MACHINE_ID",
        "LIMITCHECKER_MACHINE_LABEL",
        "LIMITCHECKER_CLAUDE_ACCOUNT",
    ]:
        if os.environ.get(key):
            env[key] = os.environ[key]
    return env


def extract_rings(payload: dict) -> list:
    """rate_limits だけをホワイトリストで抜き出し、残量に変換する。

    payload の他のフィールドには一切触れない。
    """
    limits = payload.get("rate_limits")
    if not isinstance(limits, dict):
        return []

    rings = []
    for slot, key, label in RING_MAP:
        window = limits.get(key)
        if not isinstance(window, dict):
            continue
        used = window.get("used_percentage")
        if not isinstance(used, (int, float)) or isinstance(used, bool):
            continue
        resets_at = window.get("resets_at")
        rings.append(
            {
                "slot": slot,
                "label": label,
                # statusLine は使用率を返すので残量に変換する
                "remaining": round(max(0.0, min(1.0, 1.0 - used / 100.0)), 4),
                "resets_at": int(resets_at)
                if isinstance(resets_at, (int, float)) and not isinstance(resets_at, bool)
                else None,
            }
        )
    return rings


def build_report(rings: list, env: dict) -> dict:
    """hub へ送る内容。マシン名とアカウントはユーザが設定したラベルのみを使う。

    ホスト名は自動取得しない（環境名の露出を避けるため、docs/security.md）。
    """
    return {
        "service": "claude_code",
        "account": env.get("LIMITCHECKER_CLAUDE_ACCOUNT", "default"),
        "machine_id": env.get("LIMITCHECKER_MACHINE_ID", "local"),
        "machine_label": env.get("LIMITCHECKER_MACHINE_LABEL", "Local"),
        "rings": rings,
        "updated_at": int(time.time()),
    }


def write_state(report: dict) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    try:
        STATE_DIR.chmod(0o700)
    except OSError:
        pass
    path = STATE_DIR / "state.json"
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(report, ensure_ascii=False))
    try:
        tmp.chmod(0o600)
    except OSError:
        pass
    tmp.replace(path)


def maybe_post(report: dict, env: dict) -> None:
    """間引いたうえでバックグラウンドに送信を投げる。statusLine は待たない。

    **送信先はここで決めない。** send.py が .env の BIND/PORT から導出するため、
    LIMITCHECKER_HUB_URL が無くても送れる。ここで URL の有無を判定していたせいで、
    HUB_URL を書いていない環境では一度も送信されなかった（D42）。

    BIND=tailscale の解決には `tailscale ip -4` の実行が要る。それをここで
    行うと statusLine を最長10秒塞ぐため、送信先の決定は子プロセスに任せる。
    """
    if not env.get("LIMITCHECKER_TOKEN"):
        return

    stamp = STATE_DIR / "last-post"
    now = time.time()
    try:
        age = now - stamp.stat().st_mtime
        # 未来の時刻は無視する。時計の巻き戻しやバックアップからの復元で
        # mtime が未来になると、差が負のまま永久に送信されなくなる。
        if 0 <= age < POST_INTERVAL_SEC:
            return
    except OSError:
        pass
    stamp.write_text(str(int(now)))

    sender = Path(__file__).resolve().parent / "send.py"
    try:
        child = subprocess.Popen(
            [sys.executable, str(sender)],
            stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        # 明示的に閉じないと送信側が EOF を待って止まる。待ちはしない。
        child.stdin.write(json.dumps(report).encode())
        child.stdin.close()
    except Exception:
        # 送信できなくてもステータス行は出す。鮮度はウィジェット側が判断する。
        pass


def colorize(text: str, remaining: float) -> str:
    if remaining > THRESHOLD_OK:
        code = "32"  # 緑
    elif remaining >= THRESHOLD_WARN:
        code = "33"  # 黄
    else:
        code = "31"  # 赤
    return f"\033[{code}m{text}\033[0m"


def format_reset(resets_at) -> str:
    if not resets_at:
        return ""
    remain = int(resets_at - time.time())
    if remain <= 0:
        return ""
    if remain >= 86400:
        return f"{remain // 86400}d"
    return f"{remain // 3600}:{remain % 3600 // 60:02d}"


def render(rings: list) -> str:
    """ステータス行。D8 に合わせて5時間枠を主役にする。"""
    if not rings:
        return "limitchecker: 残量を取得できません"

    parts = []
    for ring in rings:
        pct = f"{round(ring['remaining'] * 100)}%"
        chunk = colorize(f"{ring['label']} {pct}", ring["remaining"])
        if ring["slot"] == "outer":
            reset = format_reset(ring["resets_at"])
            if reset:
                chunk += f" {reset}"
        parts.append(chunk)
    return " · ".join(parts)


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        print("limitchecker: 残量を取得できません")
        return 0

    if not isinstance(payload, dict):
        print("limitchecker: 残量を取得できません")
        return 0

    env = load_env()
    rings = extract_rings(payload)

    if rings:
        report = build_report(rings, env)
        try:
            write_state(report)
            maybe_post(report, env)
        except Exception:
            pass

    print(render(rings))
    return 0


if __name__ == "__main__":
    sys.exit(main())
