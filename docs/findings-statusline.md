# 段階0 の確認結果: statusLine の JSON

確認日: 2026-09-19 / Claude Code 2.1.278

設計書の「未確定事項 1」への回答。**モデル別の週次枠は含まれない。**

## 結論

| 設計書のリング | データ | 状態 |
| --- | --- | --- |
| 外側: 5時間枠 | `rate_limits.five_hour` | **あり** |
| 中央: 週次枠 | `rate_limits.seven_day` | **あり** |
| 内側: モデル別週次枠 | — | **なし** |

`rate_limits` のキーは `five_hour` と `seven_day` の2つのみ。
Opus 等のモデル別の枠は渡ってこない。

## 取得できる値

以下は合成値（実機の値ではない）。

```json
{
  "rate_limits": {
    "five_hour": { "used_percentage": 17, "resets_at": 1789831200 },
    "seven_day": { "used_percentage": 28, "resets_at": 1790240400 }
  }
}
```

- `used_percentage` は整数（**使用率**）。設計書は残量表示なので `100 - used` で変換する
- `resets_at` は Unix エポック秒。設計書の API 仕様は ISO8601 なので変換する
- 実測でリセット時刻は妥当な値だった（5時間枠は約1時間後、週次枠は約4.8日後）

## 重要: agent はホワイトリスト方式で抽出する

statusLine の JSON には残量以外に大量の情報が含まれる。確認できたものだけでも:

- `session_id` / `transcript_path` / `cwd` / `workspace.*` — パスとセッション識別子
- `session_name` — ユーザが付けたセッション名（案件名が入りうる）
- `cost.total_cost_usd` — 課金額
- `context_window.*` — トークン使用量
- `prompt_id` / `prompt_cache.*`

**agent はこの JSON をそのまま hub へ転送してはならない。**
`rate_limits` と取得時刻だけを抜き出して送る。
除外リスト方式だと Claude Code の更新で新しいフィールドが増えたときに漏れるため、
必ず**必要なフィールドだけを明示的に拾う**実装にする。

なお、本確認用の `agent/capture-statusline.sh` は構造調査が目的なので全体を保存する。
保存先はリポジトリ外・パーミッション 600。調査が済んだら設定から外す。

## 内側リングの扱い

データ源がないため、設計書の代替案から選ぶ必要がある。→ [decisions.md](decisions.md) D7
