#!/usr/bin/env bash
# 段階0: Claude Code が statusLine に渡す JSON をそのまま保存する確認用スクリプト。
#
# 出力はセッション ID と作業ディレクトリを含むため、リポジトリの外に書く。
# 保存先: ${XDG_STATE_HOME:-~/.local/state}/limitchecker/
#
# 有効化: settings.json の statusLine.command に本スクリプトの絶対パスを指定する。
# 無効化: その設定を消すだけでよい。

set -u

dir="${XDG_STATE_HOME:-$HOME/.local/state}/limitchecker"
mkdir -p "$dir" 2>/dev/null
chmod 700 "$dir" 2>/dev/null

input=$(cat)

# 最新の1件（構造の確認用）
printf '%s' "$input" > "$dir/statusline-latest.json"

# 時系列（rate_limits がどう変化するかの確認用）
printf '%s\t%s\n' "$(date -Iseconds)" "$input" >> "$dir/statusline-log.ndjson"

chmod 600 "$dir"/statusline-* 2>/dev/null

# statusLine はこの標準出力をステータス行として表示する。
# 確認中と分かる最小限の表示にとどめる。
echo "limitchecker: capturing"
