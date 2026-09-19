"""把内置的思源宋体(Noto Serif SC)裁成子集,这是安装包里最大的单个文件。

背景:全量 CJK 字体约 14MB;而 App 用到的中文字符集中在常用字范围。
保留 GB2312 全部汉字(一级 3755 + 二级 3008 = 6763 字)+ ASCII + 中文标点 + 全角符号,
覆盖课程名/教师/地点等绝大多数中文输入;极罕见字由系统字体自动回退,不影响显示。

用法(需要 Python 与 fonttools):
    python -m pip install fonttools brotli
    python tools/subset-font.py [源字体路径]

源字体默认取 tools/fonts/noto_serif_sc_medium.ttf(不入库,避免仓库变大),
可从 Google Fonts 获取:https://fonts.google.com/noto/specimen/Noto+Serif+SC
产物直接覆盖 app/src/main/res/font/noto_serif_sc_medium.ttf。
"""

import sys
from pathlib import Path

from fontTools import subset

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SOURCE = ROOT / "tools/fonts/noto_serif_sc_medium.ttf"
TARGET = ROOT / "app/src/main/res/font/noto_serif_sc_medium.ttf"


def gb2312_unicodes() -> set[int]:
    """GB2312 全字符集(汉字 + 标点 + 全角符号)。"""
    codes: set[int] = set()
    for high in range(0xA1, 0xFA):
        for low in range(0xA1, 0xFF):
            try:
                codes.add(ord(bytes([high, low]).decode("gb2312")))
            except UnicodeDecodeError:
                continue
    return codes


def wanted_unicodes() -> set[int]:
    codes = gb2312_unicodes()
    codes |= set(range(0x20, 0x7F))       # 基本拉丁(ASCII)
    codes |= {0xA0, 0xB7, 0xD7}           # 不换行空格 / 间隔号 / 乘号
    codes |= set(range(0x2000, 0x2070))   # 通用标点(—…‘’“”等)
    codes |= set(range(0x2100, 0x2140))   # 字母式符号(№ ℃ 等)
    codes |= set(range(0x2190, 0x21A0))   # 箭头
    codes |= set(range(0x2460, 0x2500))   # 带圈数字
    codes |= set(range(0x25A0, 0x2600))   # 几何图形
    codes |= set(range(0x3000, 0x3040))   # CJK 标点与全角
    codes |= set(range(0xFE10, 0xFE70))   # 竖排标点 / 小写变体
    codes |= set(range(0xFF00, 0xFFF0))   # 全角字符
    return codes


def main() -> None:
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SOURCE
    if not source.exists():
        sys.exit(
            f"找不到源字体:{source}\n"
            "请先下载 Noto Serif SC Medium 放到 tools/fonts/ 下再运行本脚本。"
        )
    before = TARGET.stat().st_size if TARGET.exists() else 0

    options = subset.Options()
    options.layout_features = ["*"]          # 保留全部 OpenType 特性(标点挤压等)
    options.name_IDs = ["*"]
    options.notdef_outline = True
    options.recalc_bounds = True
    options.drop_tables += ["DSIG"]

    font = subset.load_font(str(source), options)
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=wanted_unicodes())
    subsetter.subset(font)
    subset.save_font(font, str(TARGET), options)

    after = TARGET.stat().st_size
    print(f"源字体 : {source}  ({source.stat().st_size / 1048576:.2f} MB)")
    print(f"子集   : {TARGET}  ({after / 1048576:.2f} MB,原 {before / 1048576:.2f} MB)")
    print(f"保留字形: {len(subsetter.glyphs_retained) if hasattr(subsetter, 'glyphs_retained') else 'n/a'}")


if __name__ == "__main__":
    main()
