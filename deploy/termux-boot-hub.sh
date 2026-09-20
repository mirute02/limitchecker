#!/bin/sh
# Android（Termux）で端末起動時に hub を立ち上げる。
#
# Termux:Boot アプリを入れたうえで:
#   mkdir -p ~/.termux/boot
#   cp deploy/termux-boot-hub.sh ~/.termux/boot/limitchecker-hub
#   chmod +x ~/.termux/boot/limitchecker-hub
#
# 常用するなら Linux 機に置くほうが確実。Android はメモリ不足時に
# バックグラウンドプロセスを止めることがある。

termux-wake-lock
# リポジトリの場所を決め打ちしない。このスクリプトの位置から辿る。
# ~/.termux/boot/ に置く場合は LIMITCHECKER_REPO で明示する。
REPO=${LIMITCHECKER_REPO:-$(cd "$(dirname "$0")/.." 2>/dev/null && pwd)}
if [ ! -f "$REPO/hub/server.py" ]; then
    echo "hub/server.py が見つかりません。LIMITCHECKER_REPO を設定してください。" >&2
    exit 1
fi
cd "$REPO" || exit 1
exec python3 hub/server.py >/dev/null 2>&1
