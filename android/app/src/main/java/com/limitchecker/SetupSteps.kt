package com.limitchecker

/**
 * hub の準備手順。
 *
 * **文字列リソースには置かない。** XML では改行がエスケープ次第で空白に畳まれ、
 * `<` や `>` もタグとして解釈される。実際にその両方で壊れた（D22）。
 *
 * 各段階には**どこで作業するか**を持たせる。コマンドの大半は Mac や Linux 側で
 * 打つもので、この端末で打つものではない。それが分からないと、
 * スマホでコマンドを打とうとして詰まる（D26）。
 */
object SetupSteps {

    /** その段階をどこで行うか。 */
    enum class Where(val labelRes: Int) {
        /** Mac や Linux。hub を置くマシン。 */
        WORK_MACHINE(R.string.where_work_machine),

        /** この Android 端末。 */
        THIS_PHONE(R.string.where_this_phone),
    }

    sealed class Item {
        /** 見出し。どこで作業するかを併せて示す。 */
        data class Head(val text: String, val where: Where) : Item()

        /** 説明文 */
        data class Body(val text: String) : Item()

        /** そのままコピーして実行できる1コマンド */
        data class Command(val text: String) : Item()
    }

    val items: List<Item> = listOf(
        Item.Body("hub は残量を集約して返すサーバです。どこか1台で動いていれば足ります。全マシンで動かす必要はありません。"),
        Item.Body("下のコマンドはほとんどが Mac や Linux 側で打つものです。この端末で打つ必要はありません。見出しに作業する場所を書いてあります。"),

        Item.Head("1. 置く場所を決める", Where.WORK_MACHINE),
        Item.Body("常時起動している Linux 機を勧めます。Mac でも手順は同じです。"),
        Item.Body("Android でも動きますが、メモリ不足でバックグラウンドが止められるため常用には向きません。"),

        Item.Head("2. 取得して常駐させる", Where.WORK_MACHINE),
        Item.Body("次の3つを、そのマシンの端末で打ちます。Linux でも macOS でも同じです。"),
        Item.Command("git clone <リポジトリ> limitchecker"),
        Item.Command("cd limitchecker"),
        Item.Command("./deploy/install-hub.sh"),
        Item.Body("systemd（Linux）と launchd（macOS）の違いは、スクリプトが吸収します。起動時に自動で立ち上がり、落ちたら再起動します。以後さわる必要はありません。"),
        Item.Body("トークンが未設定なら、その場で生成して画面に出します。その値をこの端末に持ってきて、上のトークン欄に入れます。"),

        Item.Head("3. 状態を見る・やめる", Where.WORK_MACHINE),
        Item.Command("./deploy/install-hub.sh --status"),
        Item.Command("./deploy/install-hub.sh --uninstall"),

        Item.Head("4. 待ち受け先", Where.WORK_MACHINE),
        Item.Body("同じ端末だけで使うなら、既定のままで構いません。外出先から見るなら .env の次の行を Tailscale のアドレスに変えます。0.0.0.0 は安全のため起動を拒否します。"),
        Item.Command("LIMITCHECKER_BIND=127.0.0.1"),
        Item.Body("変えたあとは登録し直します。"),
        Item.Command("./deploy/install-hub.sh"),

        Item.Head("5. 残量を送る設定", Where.WORK_MACHINE),
        Item.Body("Claude Code を使うマシンごとに設定します。常駐プロセスは不要です。settings.json に次を足すだけです。"),
        Item.Command("\"statusLine\": { \"type\": \"command\", \"command\": \"python3 /path/to/limitchecker/agent/statusline.py\" }"),
        Item.Body("Claude Code を動かすと、ステータス行に残量が出て、同時に hub へ送られます。"),

        Item.Head("6. 接続する", Where.THIS_PHONE),
        Item.Body("この画面の上に戻り、hub の URL と、手順2で表示されたトークンを入れて「保存して接続を確認」を押します。成功するとプレビューにドーナツが出ます。"),

        Item.Head("覚えておくこと", Where.THIS_PHONE),
        Item.Body("残量は Claude Code が動いているときにしか更新されません。しばらく使っていないと値が古くなります。10分を超えると薄く、1時間を超えるとグレーになって「未更新」と出ます。故障ではなく仕様です。"),
    )

    /** コピーできるコマンドの数。本数を数えて確かめるために使う。 */
    val commandCount: Int get() = items.count { it is Item.Command }
}
