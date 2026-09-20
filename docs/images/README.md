# スクリーンショット

README に載せる画像を置く場所。

## 入れる前に

`deploy/sanitize-image.py` を通すこと。EXIF やテキストチャンクなどの
付加情報を落とす。

```sh
python3 deploy/sanitize-image.py docs/images/*.png
```

**ただし画面に写り込んだ文字は落とせない。** 次が写っていないか目で確認する。

- マシン名、MagicDNS 名、Tailscale のアドレス
- トークン、接続コード
- 他アプリの通知の中身
- 他アプリのアイコンから推測できる情報
- アカウント名、メールアドレス

`.gitignore` は画像を除外していないので、`git add` すれば入る。
入れてよいか確かめてから追加すること。
