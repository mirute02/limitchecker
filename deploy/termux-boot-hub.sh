#!/data/data/com.termux/files/usr/bin/sh
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
cd "$HOME/src/limitchecker" || exit 1
exec python3 hub/server.py >/dev/null 2>&1
