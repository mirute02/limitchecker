#!/bin/sh
# hub を常駐させる。Linux（systemd）と macOS（launchd）のどちらでも同じ使い方。
#
#   ./deploy/install-hub.sh            登録して起動する
#   ./deploy/install-hub.sh --status   状態を見る
#   ./deploy/install-hub.sh --uninstall 登録を外す
#
# OS ごとの違いはこのスクリプトが吸収する。手順書を読み分ける必要はない。

set -eu

# --- 場所を特定する。macOS には realpath がないので使わない ---
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$SCRIPT_DIR/.." && pwd)

# --- python3 の実体を探す。pyenv や Homebrew でも正しい場所を指すように ---
PYTHON=$(command -v python3 || true)
if [ -z "$PYTHON" ]; then
    echo "python3 が見つかりません。先に入れてください。" >&2
    exit 1
fi

OS=$(uname -s)
ACTION=${1:-install}

LINUX_UNIT="$HOME/.config/systemd/user/limitchecker-hub.service"
MAC_PLIST="$HOME/Library/LaunchAgents/com.limitchecker.hub.plist"
MAC_LABEL="com.limitchecker.hub"

# ------------------------------------------------------------------
# 設定の確認
# ------------------------------------------------------------------
check_env() {
    if [ ! -f "$REPO/.env" ]; then
        echo "$REPO/.env がありません。"
        printf "  .env.example から作りますか? [y/N] "
        read -r answer
        case "$answer" in
            [yY]*) ;;
            *) echo "中止しました。" >&2; exit 1 ;;
        esac
        cp "$REPO/.env.example" "$REPO/.env"
        chmod 600 "$REPO/.env"
    fi

    if ! grep -q '^LIMITCHECKER_TOKEN=.\{16,\}' "$REPO/.env"; then
        echo "トークンが未設定か、16文字未満です。"
        printf "  生成して書き込みますか? [y/N] "
        read -r answer
        case "$answer" in
            [yY]*) ;;
            *) echo "中止しました。.env に LIMITCHECKER_TOKEN を設定してください。" >&2; exit 1 ;;
        esac
        token=$("$PYTHON" -c 'import secrets; print(secrets.token_urlsafe(32))')
        # sed -i は GNU と BSD で書式が違うため使わない
        grep -v '^LIMITCHECKER_TOKEN=' "$REPO/.env" > "$REPO/.env.tmp" || true
        printf 'LIMITCHECKER_TOKEN=%s\n' "$token" >> "$REPO/.env.tmp"
        mv "$REPO/.env.tmp" "$REPO/.env"
        chmod 600 "$REPO/.env"
        echo
        echo "  トークンを生成しました。Android アプリに同じ値を入れてください:"
        echo "    $token"
        echo
    fi
}

# ------------------------------------------------------------------
# Linux (systemd)
# ------------------------------------------------------------------
install_linux() {
    mkdir -p "$(dirname "$LINUX_UNIT")"
    sed -e "s|__REPO__|$REPO|g" -e "s|__PYTHON__|$PYTHON|g" \
        "$SCRIPT_DIR/limitchecker-hub.service.in" > "$LINUX_UNIT"
    systemctl --user daemon-reload
    systemctl --user enable limitchecker-hub
    # enable --now は「動いていなければ起動」でしかない。
    # .env は起動時にしか読まないため、設定を変えて再実行したときに
    # 反映されない。必ず restart する。
    systemctl --user restart limitchecker-hub
    # ログアウト後も動かす。失敗しても致命的ではない
    loginctl enable-linger "$(id -un)" 2>/dev/null || \
        echo "  注意: enable-linger に失敗しました。ログアウトすると停止します。"
    echo "登録しました: $LINUX_UNIT"
}

status_linux() {
    systemctl --user status limitchecker-hub --no-pager || true
    show_listening
    show_app_url
}

uninstall_linux() {
    systemctl --user disable --now limitchecker-hub 2>/dev/null || true
    rm -f "$LINUX_UNIT"
    systemctl --user daemon-reload
    echo "登録を外しました。"
}

# ------------------------------------------------------------------
# macOS (launchd)
# ------------------------------------------------------------------
install_mac() {
    mkdir -p "$(dirname "$MAC_PLIST")"
    sed -e "s|__REPO__|$REPO|g" -e "s|__PYTHON__|$PYTHON|g" \
        "$SCRIPT_DIR/com.limitchecker.hub.plist.in" > "$MAC_PLIST"
    launchctl unload "$MAC_PLIST" 2>/dev/null || true
    launchctl load "$MAC_PLIST"
    echo "登録しました: $MAC_PLIST"
}

status_mac() {
    if launchctl list | grep -q "$MAC_LABEL"; then
        launchctl list | grep "$MAC_LABEL"
        echo "稼働中です。"
    else
        echo "停止しています。"
    fi
    show_listening
}

# ------------------------------------------------------------------
# アプリに入れる URL をそのまま出す。
# 待ち受けアドレスと接続先が違うため、調べさせると間違える。
# ------------------------------------------------------------------
show_app_url() {
    bind=$(grep "^LIMITCHECKER_BIND=" "$REPO/.env" 2>/dev/null | cut -d= -f2- || true)
    port=$(grep "^LIMITCHECKER_PORT=" "$REPO/.env" 2>/dev/null | cut -d= -f2- || true)
    bind=${bind:-127.0.0.1}
    port=${port:-8787}

    echo
    echo "────────────────────────────────────────"
    echo " Android アプリに入れる値"
    echo "────────────────────────────────────────"

    if [ "$bind" = "127.0.0.1" ] || [ "$bind" = "localhost" ]; then
        echo "  hub の URL : http://127.0.0.1:$port"
        echo
        echo "  ※ この端末の中からしか繋がりません。"
        echo "     外から見るには .env を次のようにして、もう一度実行します。"
        echo "       LIMITCHECKER_BIND=tailscale"
    else
        dns=""
        if command -v tailscale >/dev/null 2>&1; then
            # MagicDNS 名（末尾のドットは落とす）
            dns=$(tailscale status --json 2>/dev/null \
                | tr ',' '\n' | grep -m1 '"DNSName"' | cut -d'"' -f4 | sed 's/\.$//' || true)
        fi
        if [ -n "$dns" ]; then
            echo "  hub の URL : http://$dns:$port"
            echo
            echo "  ※ IP（http://$bind:$port）では繋がりません。"
            echo "     Android は平文 HTTP を名前で照合するため、上の名前を使います。"
        else
            echo "  hub の URL : http://<マシン名>.<テイルネット名>.ts.net:$port"
            echo
            echo "  ※ MagicDNS 名が取れませんでした。tailscale status で確認してください。"
            echo "     IP（http://$bind:$port）では繋がりません。"
        fi
    fi

    echo
    echo "  接続コード : 次を実行すると6桁のコードが出ます"
    echo "               python3 $REPO/hub/pair.py"
    echo "────────────────────────────────────────"
}

# ------------------------------------------------------------------
# 実際に何を待ち受けているかを見せる。
# .env に書いた値と、実際に開いているポートがずれていることがあるため。
# ------------------------------------------------------------------
show_listening() {
    bind=$(grep "^LIMITCHECKER_BIND=" "$REPO/.env" 2>/dev/null | cut -d= -f2- || true)
    port=$(grep "^LIMITCHECKER_PORT=" "$REPO/.env" 2>/dev/null | cut -d= -f2- || true)
    bind=${bind:-127.0.0.1}
    port=${port:-8787}

    echo
    echo ".env の設定    : $bind:$port"

    actual=""
    if command -v ss >/dev/null 2>&1; then
        actual=$(ss -ltnp 2>/dev/null | grep ":$port " || true)
    elif command -v netstat >/dev/null 2>&1; then
        actual=$(netstat -an 2>/dev/null | grep "LISTEN" | grep "\.$port \|:$port " || true)
    fi

    if [ -n "$actual" ]; then
        echo "実際の待ち受け :"
        printf '%s\n' "$actual" | sed 's/^/  /'
    else
        echo "実際の待ち受け : ポート $port で待ち受けているプロセスが見つかりません"
    fi

    echo
    if [ "$bind" = "127.0.0.1" ] || [ "$bind" = "localhost" ]; then
        echo "注意: 127.0.0.1 で待ち受けているため、**他の端末からは接続できません**。"
        echo "      外から見るには .env の LIMITCHECKER_BIND を Tailscale の"
        echo "      アドレスに変えて、もう一度 ./deploy/install-hub.sh を実行します。"
    else
        echo "この端末以外から繋ぐときは、アプリに入れる URL に注意してください。"
        echo "  通る   : http://<マシン名>.<テイルネット名>.ts.net:$port"
        echo "  通らない: http://$bind:$port  （IP は平文 HTTP の許可対象外）"
        echo "MagicDNS 名は tailscale status で確認できます。"
    fi
}

uninstall_mac() {
    launchctl unload "$MAC_PLIST" 2>/dev/null || true
    rm -f "$MAC_PLIST"
    echo "登録を外しました。"
}

# ------------------------------------------------------------------

# Termux も uname では Linux を返すが systemd がない。先に見分ける。
if [ "$OS" = "Linux" ] && ! command -v systemctl >/dev/null 2>&1; then
    echo "systemd が見つかりません。"
    if [ -n "${TERMUX_VERSION:-}" ] || [ -d /data/data/com.termux ]; then
        cat <<'MSG'

Termux では systemd を使えません。次のどちらかにしてください。

  端末起動時に立ち上げる（Termux:Boot アプリが必要）:
    mkdir -p ~/.termux/boot
    cp deploy/termux-boot-hub.sh ~/.termux/boot/limitchecker-hub
    chmod +x ~/.termux/boot/limitchecker-hub

  その場で動かす:
    python3 hub/server.py

なお Android はメモリ不足でバックグラウンドを止めるため、
常用するなら Linux 機か Mac に置くほうが確実です。
MSG
    else
        echo "python3 $REPO/hub/server.py を直接動かすか、お使いの仕組みに登録してください。"
    fi
    exit 1
fi

case "$OS" in
    Linux)  install_fn=install_linux; status_fn=status_linux; uninstall_fn=uninstall_linux ;;
    Darwin) install_fn=install_mac;   status_fn=status_mac;   uninstall_fn=uninstall_mac ;;
    *) echo "$OS には対応していません。python3 $REPO/hub/server.py を直接動かしてください。" >&2; exit 1 ;;
esac

case "$ACTION" in
    --status)    "$status_fn" ;;
    --uninstall) "$uninstall_fn" ;;
    install|"")
        check_env
        "$install_fn"
        echo
        "$status_fn"
        show_app_url
        ;;
    *) echo "使い方: $0 [--status|--uninstall]" >&2; exit 1 ;;
esac
