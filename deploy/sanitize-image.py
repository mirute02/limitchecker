#!/usr/bin/env python3
"""スクリーンショットを公開できる状態にする。

やること:
  - 付加情報（EXIF・テキストチャンク・撮影時刻）を全て落とす
  - 画素だけを残して書き直す
  - 前後のバイト数と、残った情報を報告する

写り込んだ文字（マシン名・URL・通知の中身）は落とせない。
それは目で確認するしかない。
"""

import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    print("Pillow が要ります: pip install pillow", file=sys.stderr)
    raise SystemExit(1)


def inspect(path: Path) -> dict:
    data = path.read_bytes()
    found = {}
    for marker in (b"tEXt", b"zTXt", b"iTXt", b"eXIf", b"tIME", b"Exif"):
        if marker in data:
            found[marker.decode("latin1")] = data.count(marker)
    return found


def main() -> int:
    if len(sys.argv) < 2:
        print("使い方: sanitize-image.py <画像> [<画像>...]", file=sys.stderr)
        return 2

    for arg in sys.argv[1:]:
        src = Path(arg)
        if not src.exists():
            print(f"  {src}: ありません")
            continue

        before = src.stat().st_size
        found = inspect(src)

        with Image.open(src) as img:
            # 画素だけを写し取る。付加情報は引き継がない。
            clean = Image.new(img.mode, img.size)
            clean.paste(img)
            clean.save(src, format="PNG", optimize=True)

        after = src.stat().st_size
        left = inspect(src)

        print(f"  {src.name}")
        print(f"    {before:,} → {after:,} バイト")
        print(f"    除去前の付加情報: {found or 'なし'}")
        print(f"    除去後            : {left or 'なし'}")
        if left:
            print("    **まだ残っています。確認してください**")
    print()
    print("  ※ 画面に写り込んだ文字は落とせません。目で確認してください。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
