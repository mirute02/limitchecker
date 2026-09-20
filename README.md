# limitchecker

Claude Code と Codex の残量を Android のホーム画面と通知領域で把握するためのツール。
エージェントが承認待ちで止まっている状態も取りこぼさないようにする。

**状態: 開発中（段階1 完了 / agent と hub が動作）**

## 構成

| 要素 | 役割 |
| --- | --- |
| agent | 各マシンの statusLine とフックから値を受け取り、hub へ送る |
| hub | 全マシンの状態を集約し、残量をアカウント単位に畳んで返す |
| Android | ウィジェットと常設通知で表示する |

設計の詳細は [docs/design.md](docs/design.md)。

## セキュリティ

本リポジトリは公開予定のため、秘密情報と個人情報の扱いを
[docs/security.md](docs/security.md) に定めている。実装前に参照すること。

クローン後に秘密検出フックを有効化する:

```sh
git config core.hooksPath .githooks
cp .env.example .env   # 実値を記入する
```

## 使い方（現状）

依存は Python 3 のみ。追加インストールは不要。

### 1. 設定

```sh
cp .env.example .env
python3 -c "import secrets; print(secrets.token_urlsafe(32))"   # トークンを生成して .env に記入
chmod 600 .env
```

`LIMITCHECKER_BIND` は既定が `127.0.0.1`。外出先から見る場合のみ Tailscale の
アドレスを書く。`0.0.0.0` は hub が起動を拒否する。

### 2. hub を常駐させる（1台だけ）

Linux でも macOS でも同じです。

```sh
./deploy/install-hub.sh
```

systemd と launchd の違いはスクリプトが吸収します。起動時に自動で立ち上がり、
落ちたら再起動します。トークンが未設定ならその場で生成して表示します。

```sh
./deploy/install-hub.sh --status      # 状態を見る
./deploy/install-hub.sh --uninstall   # 登録を外す
```

試すだけなら直接起動でも構いません。トークン未設定、16文字未満、
`0.0.0.0` 指定のいずれでも起動しません。

```sh
python3 hub/server.py
```

### 3. agent を仕込む（残量を取りたいマシンごと）

`settings.json` に以下を足す。

```json
{
  "statusLine": {
    "type": "command",
    "command": "python3 /path/to/limitchecker/agent/statusline.py"
  }
}
```

Claude Code のステータス行に残量が出るようになり、同時に hub へ送られる。
送信は 60 秒に間引かれ、バックグラウンドで行われるためステータス行は待たない。

### 4. 確認

```sh
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8787/status
```

## 実装状況

| 段階 | 内容 | 状態 |
| --- | --- | --- |
| 0 | 未確定事項の確認 | 完了（[結果](docs/findings-statusline.md)） |
| 1 | agent と hub | 完了 |
| 2 | ウィジェット | 未着手 |
| 3 | フックとイベント | 未着手 |
| 4 | 即時通知 | 未着手 |
| 5 | マシンチップ | hub 側は完了 |
| 6 | Codex 対応 | 未着手 |
