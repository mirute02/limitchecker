package com.limitchecker

/**
 * hub の準備手順。
 *
 * **文字列リソースには置かない。** XML では改行がエスケープ次第で空白に畳まれ、
 * `<` や `>` もタグとして解釈される。実際にその両方で壊れた。
 * ここで型付きの並びとして持てば、区切りの判定も要らず、
 * コマンドごとのコピーボタンが構造として保証される（docs/decisions.md D22）。
 */
object SetupSteps {

    sealed class Item {
        /** 見出し */
        data class Head(val text: String) : Item()

        /** 説明文 */
        data class Body(val text: String) : Item()

        /** そのままコピーして実行できる1コマンド */
        data class Command(val text: String) : Item()
    }

    val items: List<Item> = listOf(
        Item.Body("hub は残量を集約して返すサーバです。どこか1台で動いていれば足ります。全マシンで動かす必要はありません。"),

        Item.Head("1. 置く場所を決める"),
        Item.Body("常時起動している Linux 機を勧めます。Mac でも手順は同じです。"),
        Item.Body("Android でも動きますが、メモリ不足でバックグラウンドが止められるため常用には向きません。"),

        Item.Head("2. 取得して常駐させる"),
        Item.Body("次の3つは Linux でも macOS でもそのまま使えます。"),
        Item.Command("git clone <リポジトリ> limitchecker"),
        Item.Command("cd limitchecker"),
        Item.Command("./deploy/install-hub.sh"),
        Item.Body("systemd（Linux）と launchd（macOS）の違いは、スクリプトが吸収します。起動時に自動で立ち上がり、落ちたら再起動します。以後さわる必要はありません。"),
        Item.Body("トークンが未設定なら、その場で生成して画面に出します。表示された値を、この画面のトークン欄に入れてください。"),

        Item.Head("3. 状態を見る・やめる"),
        Item.Command("./deploy/install-hub.sh --status"),
        Item.Command("./deploy/install-hub.sh --uninstall"),

        Item.Head("4. 待ち受け先"),
        Item.Body("同じ端末だけで使うなら、既定のままで構いません。外出先から見るなら .env の次の行を Tailscale のアドレスに変えます。0.0.0.0 は安全のため起動を拒否します。"),
        Item.Command("LIMITCHECKER_BIND=127.0.0.1"),
        Item.Body("変えたあとは登録し直します。"),
        Item.Command("./deploy/install-hub.sh"),

        Item.Head("5. 残量を送る側"),
        Item.Body("作業マシンごとに設定します。常駐プロセスは不要です。Claude Code の settings.json に次を足すだけです。"),
        Item.Command("\"statusLine\": { \"type\": \"command\", \"command\": \"python3 /path/to/limitchecker/agent/statusline.py\" }"),
        Item.Body("Claude Code を動かすと、ステータス行に残量が出て、同時に hub へ送られます。"),

        Item.Head("6. この画面で確認"),
        Item.Body("URL とトークンを入れて「保存して接続を確認」を押します。成功すると、上のプレビューにドーナツが出ます。"),

        Item.Head("覚えておくこと"),
        Item.Body("残量は Claude Code が動いているときにしか更新されません。しばらく使っていないと値が古くなります。10分を超えると薄く、1時間を超えるとグレーになって「未更新」と出ます。故障ではなく仕様です。"),
    )

    /** コピーできるコマンドの数。テストで本数を確かめるために使う。 */
    val commandCount: Int get() = items.count { it is Item.Command }
}
