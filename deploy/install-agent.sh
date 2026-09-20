#!/bin/sh
# 作業マシンに agent を仕込む。Claude Code の statusLine と、Codex の定期実行。
#
#   ./deploy/install-agent.sh            両方入れる
#   ./deploy/install-agent.sh --claude   Claude Code だけ
#   ./deploy/install-agent.sh --codex    Codex だけ
#   ./deploy/install-agent.sh --status   今の状態を見る
#   ./deploy/install-agent.sh --uninstall 外す
#
# settings.json は既存の設定を壊さずに statusLine だけを足す。
# 移植性の注意は install-hub.sh と同じ（shebang は /bin/sh、sed -i を使わない）。

set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$SCRIPT_DIR/.." && pwd)

PYTHON=$(command -v python3 || true)
if [ -z "$PYTHON" ]; then
    echo "python3 が見つかりません。" >&2
    exit 1
fi

CLAUDE_SETTINGS="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/settings.json"
CRON_TAG="# limitchecker-codex"
ACTION=${1:-all}

# ------------------------------------------------------------------
# Claude Code: settings.json に statusLine を足す
# ------------------------------------------------------------------
install_claude() {
    "$PYTHON" - "$CLAUDE_SETTINGS" "$REPO" "$PYTHON" <<'PY'
import json, os, sys, pathlib

settings_path = pathlib.Path(sys.argv[1])
repo, python = sys.argv[2], sys.argv[3]
# 空白を含むパスでも Claude Code が起動できるよう引用する
command = f'"{python}" "{repo}/agent/statusline.py"' if " " in f"{python}{repo}" \
    else f"{python} {repo}/agent/statusline.py"

settings_path.parent.mkdir(parents=True, exist_ok=True)
data = {}
if settings_path.exists():
    try:
        data = json.loads(settings_path.read_text())
    except ValueError:
        print("  settings.json が壊れています。手で直してから再実行してください。")
        raise SystemExit(1)
    if not isinstance(data, dict):
        print("  settings.json の形式が想定と違います。")
        raise SystemExit(1)

current = data.get("statusLine")
existing = current.get("command", "") if isinstance(current, dict) else ""

# python3 の書き方（絶対パスか否か）で文字列は変わるので、
# 対象スクリプトを指しているかで判定する。
marker = f"{repo}/agent/statusline.py"
if marker in existing:
    print("  Claude Code: 設定済みです")
    raise SystemExit(0)

if existing:
    print("  すでに別の statusLine が設定されています:")
    print(f"    {existing}")
    print("  上書きすると元の表示が失われます。中止しました。")
    print("  置き換えるなら、その行を消してから再実行してください。")
    raise SystemExit(1)

# 既存の設定は触らず statusLine だけ足す
data["statusLine"] = {"type": "command", "command": command}
tmp = settings_path.with_suffix(".json.tmp")
tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
# 元の権限を引き継ぐ。settings.json の env に API キーを置いている人がいるため、
# umask 次第で 600 から 644 に緩んでしまうのを防ぐ（D43）。
try:
    tmp.chmod(settings_path.stat().st_mode & 0o7777)
except OSError:
    tmp.chmod(0o600)
tmp.replace(settings_path)
print(f"  Claude Code: {settings_path} に statusLine を追加しました")
PY
}

uninstall_claude() {
    "$PYTHON" - "$CLAUDE_SETTINGS" "$REPO" <<'PY'
import json, sys, pathlib
settings_path = pathlib.Path(sys.argv[1])
repo = sys.argv[2]
if not settings_path.exists():
    print("  Claude Code: 設定はありません"); raise SystemExit(0)
try:
    data = json.loads(settings_path.read_text())
except ValueError:
    print("  settings.json が壊れています"); raise SystemExit(1)
sl = data.get("statusLine")
if isinstance(sl, dict) and repo in str(sl.get("command", "")):
    del data["statusLine"]
    tmp = settings_path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    try:
        tmp.chmod(settings_path.stat().st_mode & 0o7777)
    except OSError:
        tmp.chmod(0o600)
    tmp.replace(settings_path)
    print("  Claude Code: statusLine を外しました")
else:
    print("  Claude Code: 本ツールの設定は見つかりません（他の設定は触りません）")
PY
}

status_claude() {
    if [ -f "$CLAUDE_SETTINGS" ]; then
        # パスを python のソースに埋め込まない。引数で渡す。
        "$PYTHON" -c '
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    print("  Claude Code: settings.json を読めません"); raise SystemExit
sl = d.get("statusLine")
cmd = sl.get("command", "") if isinstance(sl, dict) else ""
print("  Claude Code: 設定済み" if sys.argv[2] in cmd
      else ("  Claude Code: 別の statusLine が設定されています" if cmd
            else "  Claude Code: 未設定"))
' "$CLAUDE_SETTINGS" "$REPO"
    else
        echo "  Claude Code: settings.json がありません"
    fi
}

# ------------------------------------------------------------------
# Codex: 定期実行を登録する
# ------------------------------------------------------------------
codex_line() {
    # パスに空白が入っても壊れないよう引用する
    printf "*/10 * * * * cd '%s' && '%s' agent/codex.py >/dev/null 2>&1 %s\n" \
        "$REPO" "$PYTHON" "$CRON_TAG"
}

install_codex() {
    if ! command -v codex >/dev/null 2>&1 && [ -z "${LIMITCHECKER_CODEX_BIN:-}" ]; then
        echo "  Codex: codex が見つからないので飛ばします"
        return 0
    fi
    if ! command -v crontab >/dev/null 2>&1; then
        echo "  Codex: crontab がありません。次を任意の仕組みで10分ごとに実行してください:"
        echo "    cd $REPO && $PYTHON agent/codex.py"
        return 0
    fi
    if crontab -l 2>/dev/null | grep -qF "$CRON_TAG"; then
        echo "  Codex: 登録済みです"
        return 0
    fi
    { crontab -l 2>/dev/null || true; codex_line; } | crontab -
    echo "  Codex: 10分ごとの定期実行を登録しました"
}

uninstall_codex() {
    command -v crontab >/dev/null 2>&1 || { echo "  Codex: crontab がありません"; return 0; }
    if crontab -l 2>/dev/null | grep -qF "$CRON_TAG"; then
        crontab -l 2>/dev/null | grep -vF "$CRON_TAG" | crontab -
        echo "  Codex: 定期実行を外しました"
    else
        echo "  Codex: 登録はありません"
    fi
}

status_codex() {
    if command -v crontab >/dev/null 2>&1 && crontab -l 2>/dev/null | grep -qF "$CRON_TAG"; then
        echo "  Codex: 登録済み"
    else
        echo "  Codex: 未登録"
    fi
}

# ------------------------------------------------------------------

# ------------------------------------------------------------------
# 送信経路を実際に試す。
# agent は送信先が分からないと黙って終わるため、動いているように見えて
# hub に何も届かないことがある（D40）。
# ------------------------------------------------------------------
check_delivery() {
    echo
    echo "-- hub への送信 --"

    if [ ! -f "$REPO/.env" ]; then
        echo "  .env がありません。先に ./deploy/install-hub.sh を実行してください。"
        return 0
    fi

    "$PYTHON" "$SCRIPT_DIR/check-delivery.py" "$REPO"
}

case "$ACTION" in
    --status)    status_claude; status_codex; check_delivery ;;
    --uninstall) uninstall_claude; uninstall_codex ;;
    --claude)    install_claude ;;
    --codex)     install_codex ;;
    all|"")
        install_claude || true
        install_codex
        echo
        echo "  hub の URL とトークンを Android アプリに入れてください。"
        echo "  接続コードを使うなら、hub を置いたマシンで:"
        echo "    python3 hub/pair.py"
        ;;
    *) echo "使い方: $0 [--claude|--codex|--status|--uninstall]" >&2; exit 1 ;;
esac
