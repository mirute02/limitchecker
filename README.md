# limitchecker

Claude Code と Codex の残量を、Android のホーム画面と通知領域で把握するためのツール。

長い作業に入る前に残量が分からず途中で止まる、という困りごとを解決する。

## 特徴: 認証情報を持たない

**このアプリは Claude や OpenAI のアカウントにログインしない。** API キーも要求しない。

残量は、すでにログイン済みの Claude Code が statusLine に渡してくる値をそのまま使う。
そのため:

- `~/.claude/.credentials.json` を読まない・コピーしない・送らない
- Android アプリが持つ秘密は、自分で立てた hub のトークン1つだけ
- 認証情報の保管場所がそもそも存在しない

代償として、**残量は Claude Code が動いているときにしか更新されない**。
値が古くなったら、正確なふりをせずグレーにして「未更新」と表示する。

## 仕組み

```
作業マシン（Mac / Linux）              1台だけ           Android
┌──────────────────┐          ┌─────────┐      ┌──────────┐
│ Claude Code          │          │          │      │ ウィジェット │
│   └ statusLine ──→ agent │ ──POST──→│   hub    │──GET─→│ 常設通知    │
└──────────────────┘          └─────────┘      └──────────┘
```

| 要素 | 役割 | 常駐 |
| --- | --- | --- |
| agent | statusLine から残量だけを抜き出して hub へ送る | 不要 |
| hub | 全マシンの状態を集約し、アカウント単位に畳んで返す | 1台のみ |
| Android | ウィジェットと通知で表示する | — |

残量の枠はアカウントに紐づくため、マシンが増えてもリングは1組のまま。
マシンの一覧だけが増える。

## 表示

- **二重ドーナツ**: 外側が5時間枠、中央が週次枠。中心にリセットまでの時間
- **残量は弧の長さ**で表す。色が読めなくても情報が失われない
- **配色は5種類**から選べる。既定は色覚特性があっても判別できる組み合わせ
- **背景は不透明・半透明・透過**から選べる
- **ウィジェットは 1×1 まで縮小可能**。幅に応じて表示する要素を減らす
- **通知センターに常設**できる。ステータスバーには残量の形か数字を出す

## セットアップ

依存は Python 3 のみ。hub と agent に追加インストールは要らない。

### 1. hub を常駐させる（どこか1台）

常時起動している Linux 機を勧める。Mac でも手順は同じ。

```sh
git clone https://github.com/mirute02/limitchecker.git
cd limitchecker
./deploy/install-hub.sh
```

systemd（Linux）と launchd（macOS）の違いはスクリプトが吸収する。
起動時に自動で立ち上がり、落ちたら再起動する。
トークンが未設定ならその場で生成して表示するので、その値を Android に入れる。

```sh
./deploy/install-hub.sh --status      # 状態を見る
./deploy/install-hub.sh --uninstall   # 登録を外す
```

外出先から見るなら `.env` の `LIMITCHECKER_BIND` を Tailscale のアドレスに変えて
登録し直す。`0.0.0.0` は安全のため起動を拒否する。

### 2. agent を仕込む（Claude Code を使うマシンごと）

常駐プロセスは不要。`settings.json` に以下を足すだけ。

```json
{
  "statusLine": {
    "type": "command",
    "command": "python3 /path/to/limitchecker/agent/statusline.py"
  }
}
```

Claude Code のステータス行に残量が出るようになり、同時に hub へ送られる。
送信は60秒に間引き、バックグラウンドで行うためステータス行を待たせない。

### 3. Android アプリ

APK は配布していないため、自分でビルドする。

```sh
cd android
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleDebug
```

必要なもの: JDK 17 以上、Android SDK（compileSdk 37）、Gradle 9 系。
できた `app/build/outputs/apk/debug/app-debug.apk` を端末に入れる。

アプリを開いて hub の URL とトークンを入れ、「保存して接続を確認」を押す。
ウィジェットを置かなくても、設定画面のプレビューで見た目を確認できる。

## 権限

| 権限 | 用途 | 実行時確認 |
| --- | --- | --- |
| `INTERNET` | hub への通信 | なし |
| `ACCESS_NETWORK_STATE` | 圏外判定（省電力） | なし |
| `POST_NOTIFICATIONS` | 常設通知（任意機能） | あり |

`WAKE_LOCK` / `RECEIVE_BOOT_COMPLETED` / `FOREGROUND_SERVICE` は WorkManager が
自動で追加する。実行時確認が出るのは `POST_NOTIFICATIONS` だけ。

位置情報、ストレージ、連絡先、`QUERY_ALL_PACKAGES`、アクセシビリティ、
電池最適化の除外は**要求しない**。

## 電池

| 状態 | バックグラウンドの通信 |
| --- | --- |
| ウィジェットも通知もなし | ゼロ |
| ウィジェットか通知あり | 15分ごと。**画面 OFF 中は停止** |

残量は分単位で変化しないため、短い間隔にしても情報は増えず通信回数だけ増える。

## セキュリティ

秘密情報と個人情報の扱いは [docs/security.md](docs/security.md) に定めている。
監査の記録は [docs/audit-2026-09-20.md](docs/audit-2026-09-20.md)。

開発に参加する場合は、秘密検出フックを有効にする。

```sh
git config core.hooksPath .githooks
```

## 制約

- 残量は **Claude Code の実行中にしか更新されない**。10分を超えると薄く、
  1時間を超えるとグレーになる
- **モデル別の週次枠は取得できない**。statusLine が返すのは5時間枠と週次枠のみ
  （[確認結果](docs/findings-statusline.md)）
- **Codex は未対応**。リングはグレーで「取得不可」と表示される

## ドキュメント

| | |
| --- | --- |
| [docs/design.md](docs/design.md) | 当初の設計書 |
| [docs/decisions.md](docs/decisions.md) | 設計書からの変更点と、その理由 |
| [docs/findings-statusline.md](docs/findings-statusline.md) | statusLine が返す値の実測 |
| [docs/security.md](docs/security.md) | セキュリティ方針 |

## 実装状況

| 段階 | 内容 | 状態 |
| --- | --- | --- |
| 0 | statusLine の実測 | 完了 |
| 1 | agent と hub | 完了 |
| 2 | ウィジェット | 完了 |
| 3 | フックとイベント検知 | 未着手 |
| 4 | 即時通知 | 未着手 |
| 5 | マシン別表示 | hub 側のみ完了 |
| 6 | Codex 対応 | 未着手 |

常設通知は段階4 の前倒しとして実装済み（前景サービスを使わない方式）。

## ライセンス

MIT License. 詳細は [LICENSE](LICENSE) を参照。
