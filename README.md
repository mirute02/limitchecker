# limitchecker

Claude Code と Codex の残量を Android のホーム画面と通知領域で把握するためのツール。
エージェントが承認待ちで止まっている状態も取りこぼさないようにする。

**状態: 開発中（段階0 / 未確定事項の確認）**

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
