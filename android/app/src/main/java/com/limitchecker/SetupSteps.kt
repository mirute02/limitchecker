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

        Item.Head("2. hub を常駐させる", Where.WORK_MACHINE),
        Item.Body("次の3つを、そのマシンの端末で打ちます。Linux でも macOS でも同じです。"),
        Item.Command("git clone https://github.com/mirute02/limitchecker.git"),
        Item.Command("cd limitchecker"),
        Item.Command("./deploy/install-hub.sh"),
        Item.Body("systemd（Linux）と launchd（macOS）の違いは、スクリプトが吸収します。起動時に自動で立ち上がり、落ちたら再起動します。以後さわる必要はありません。"),
        Item.Body("状態を見る、やめるときはこうします。"),
        Item.Command("./deploy/install-hub.sh --status"),
        Item.Command("./deploy/install-hub.sh --uninstall"),

        Item.Head("3. 残量を送る設定", Where.WORK_MACHINE),
        Item.Body("Claude Code や Codex を使うマシンごとに実行します。常駐プロセスは不要です。"),
        Item.Command("./deploy/install-agent.sh"),
        Item.Body("settings.json に statusLine を足し、Codex があれば定期実行も登録します。既存の設定は壊しません。別の statusLine が既にある場合は、上書きせず中止します。"),
        Item.Body("Codex の通信に回避策が要る環境（Termux など）では、.env でラッパーを指定します。alias はスクリプト内で展開されないため、指定しないと回避策を通りません。"),
        Item.Command("LIMITCHECKER_CODEX_BIN=/path/to/codex-wrapper"),

        Item.Head("4. この端末で接続する", Where.THIS_PHONE),
        Item.Body("hub を置いたマシンで接続コードを発行します。"),
        Item.Command("python3 hub/pair.py"),
        Item.Body("6桁のコードが出るので、この画面の「接続コードで設定」に hub の URL と一緒に入れます。長いトークンを手で写さずに済みます。"),
        Item.Body("コードは5分間有効で、1回使うと無効になります。5回間違えると打ち切ります。"),

        Item.Head("5. 外出先から見る（任意）", Where.WORK_MACHINE),
        Item.Body(".env を次のようにして、もう一度 install-hub.sh を実行します。tailscale と書けば、その機械の Tailscale アドレスを自動で引きます。"),
        Item.Command("LIMITCHECKER_BIND=tailscale"),
        Item.Command("./deploy/install-hub.sh"),
        Item.Body("実行すると、この画面に入れる URL をそのまま表示します。調べる必要はありません。"),

        Item.Head("5b. URL は IP ではなく名前", Where.THIS_PHONE),
        Item.Body("待ち受けるアドレスと、この画面に入れる URL は別物です。平文 HTTP で接続できるのは、この端末自身と *.ts.net のみです。"),
        Item.Body("IP アドレスはこの条件に一致しないため、http://100.x.y.z:8787 を入れると通信する前に拒否されます。短縮名（gpu だけ）も .ts.net で終わらないので通りません。"),
        Item.Command("http://gpu.tailnet-name.ts.net:8787"),

        Item.Head("覚えておくこと", Where.THIS_PHONE),
        Item.Body("Claude Code の残量は、Claude Code が動いているときにしか更新されません。しばらく使っていないと値が古くなります。10分を超えると薄く、1時間を超えるとグレーになって「未更新」と出ます。故障ではなく仕様です。"),
        Item.Body("Codex は定期実行で取るため、この制約を受けません。"),
    )

    /** コピーできるコマンドの数。本数を数えて確かめるために使う。 */
    val commandCount: Int get() = items.count { it is Item.Command }
}
