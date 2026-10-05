#!/usr/bin/env python3
# Copyright (c) 2026 ilyskyo
# SPDX-License-Identifier: MIT
"""release 数据可发现性冒烟测试。

要证明的是一件很小的事：**R8 处理过的那个产物，仍然能把 deck.json / diary.json 原样读回来。**

为什么这一类回归必须靠脚本抓：WordLens 的用户数据是 filesDir 下的明文 JSON，由
kotlinx.serialization 通过 Java 反射读写类名、成员名与枚举名。R8 把这些名字改掉或裁掉时，
既不会崩溃也不会报错——`WordLensJson` 配了 `coerceInputValues = true`，读不出来的枚举名会被
**夹成默认值**。现场表现是用户的复习状态、来源标记、心情标签一夜之间变成默认值，而日志里
一行都没有。JVM 单测跑的是没被混淆的 class，抓不到；UI 测试跑的是 debug 包，也抓不到。
所以唯一有效的闸是：**读真实 release 产物的元数据**。

一条实测校正（别把断言读成「不通过就一定正在丢数据」）：补规则之前的 release 包里，
`StudyDirection RECOGNIZE -> T` 这种改名**只发生在字段名上**；用 dexdump 反汇编 `classes.dex`
能看到 `<clinit>` 传给 `Enum.<init>` 的还是 `const-string "RECOGNIZE"`，`$VALUES` 也还是全的，
所以那一版产物其实读得回来。这个脚本因此不是「事故探测器」，而是**契约闸门**：
它要求落盘要用的名字是被 `app/proguard-rules.pro` 明确钉住的，而不是碰巧没被 R8 动到。
两种情况的区别要在版本升级时才显现——换一个 R8 版本、打开更多 `-optimizations`、
或者哪天代码里不再直接引用某个常量，「碰巧」就没了。

实现路径与理由（对应任务里的 (a)/(b) 两种）：

1. 主断言走 `app/build/outputs/mapping/release/mapping.txt`（AGP 在 minify 打开时一定产出，
   不需要 dexdump / apkanalyzer / 任何额外依赖）。mapping.txt 的格式是「原名 -> 改名」，
   于是它能直接回答本项目唯一关心的问题：**哪些名字被改走了**。
   它比读 dex 更强的地方在于：mapping.txt 逐字段记录了 R8 对枚举常量做了什么
   （`StudyDirection RECOGNIZE -> T` 这一行意味着规则没钉住它），
   而 dex 里只能看到结果、看不到「本来会不会被改」。
2. 辅助佐证走 APK：用标准库 `zipfile` 读出 `classes.dex`，在里面找落盘用的字符串常量
   （JSON 元素名 + 枚举值名）。这一步把「规则写对了」和「产物里真的有这些名字」分开验证——
   规则可能被别的配置吃掉，而 dex 不会说谎。短名字（<=6 字符）在字符串池里可能被别的
   字符串偶然带出来，所以命中只算弱证据；反过来缺失一定是真缺失，算硬失败。

任何一条主断言不成立就非零退出。**mapping.txt 不存在时直接报错退出**（而不是「没有就跳过」），
因为「跳过」在这条流水线上等于放行一个没有任何验证的 release 包。
"""

from __future__ import annotations

import argparse
import re
import sys
import zipfile
from pathlib import Path

# Windows 控制台默认不是 UTF-8，而这个脚本的输出全是中文诊断：一旦乱码，读日志的人就会把
# 「看不清」当成「没跑」，等于这条闸失效。启动就强制改回来。
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

# ── 必须存活的名字清单 ────────────────────────────────────────────────────────
# 这张表就是「哪些名字必须存活」的答案本身。改数据模型时同步改这里；
# 改不动这张表就说明落盘格式变了，需要的是迁移方案而不是放宽断言。

MODEL = "com.ilyskyo.wordlens.data.model"
REPO = "com.ilyskyo.wordlens.data.repository"

#: 直接落盘枚举名的类型：类名 -> 必须逐字保住的常量名。
#: WordCard.states / EventCard.states 的 **key** 也是枚举名（代码是 `states[direction.name]`），
#: 所以 StudyDirection 既出现在 JSON 的 ReviewLog.direction 里，也出现在 map 的 key 上。
PERSISTED_ENUMS: dict[str, list[str]] = {
    f"{MODEL}.CardOrigin": ["STICKER", "SCENE", "TEXT"],
    f"{MODEL}.EntrySource": ["MANUAL", "ON_DEVICE", "CLOUD", "IMPORTED"],
    f"{MODEL}.EntryMood": [
        "QUIET", "WARM", "CROWDED", "MESSY", "BRIGHT", "GREY", "BUSY", "ORDINARY",
    ],
    f"{MODEL}.OverlayLayer": ["ITEM", "AMBIENCE"],
    f"{MODEL}.RatingPalette": ["WARM", "COOL", "MUTED"],  # 落 DataStore 的是 name
    f"{MODEL}.ReviewSource": ["USER", "AI", "DERIVED"],
    f"{MODEL}.StudyDirection": ["RECOGNIZE", "RECALL"],  # 既是 map key 也是 DataStore 的值
}

#: 有 kotlinx.serialization 生成的 `$$serializer` 的类型：两个用户文档根 + 三个资产文件根
#: 及其全部嵌套类型。序列化器被裁掉或改名时，`DeckDocument.serializer()` 就找不到描述符，
#: 而 `ignoreUnknownKeys + coerceInputValues` 把失败摊平成「空文档」。
SERIALIZER_CLASSES: list[str] = [
    f"{REPO}.DeckDocument",   # filesDir/deck.json 的根
    f"{REPO}.DiaryDocument",  # filesDir/diary.json 的根
    f"{MODEL}.WordCard",
    f"{MODEL}.FsrsState",     # Map<String, FsrsState> 的值类型
    f"{MODEL}.ReviewLog",
    f"{MODEL}.Entry",
    f"{MODEL}.EntryObject",
    f"{MODEL}.EventCard",
    f"{MODEL}.LexiconFile",   # assets/lexicon/*.json
    f"{MODEL}.LexiconEntry",
    f"{MODEL}.SceneTaxonomy",  # assets/scenes/scenes.json
    f"{MODEL}.SceneKind",
    f"{MODEL}.AmbienceFile",  # assets/scenes/ambience.json
    f"{MODEL}.AmbienceWord",
    f"{MODEL}.AmbienceCue",
]

#: 枚举不生成 $$serializer（本项目实测：连带 @Serializable 注解的 EntrySource / CardOrigin /
#: ReviewSource 也没有），序列化器是运行时从 Companion 反射建 EnumSerializer 得来的，
#: 所以它们要和上面的类一起核对 Companion。
COMPANION_CLASSES: list[str] = [
    *SERIALIZER_CLASSES,
    f"{MODEL}.CardOrigin",
    f"{MODEL}.EntrySource",
    f"{MODEL}.ReviewSource",
]

#: dex 里必须能找到的 JSON 元素名。它们在 $$serializer 里是字符串常量，
#: 理论上不受改名影响；找不到就说明序列化器被裁掉了——比 mapping 更硬的一份证据。
#: 只列 >=7 字符的：`log`、`mood`、`cards` 这类短名在字符串池里可能偶然命中，
#: 命中了也证明不了什么，留在那个位置只会让读日志的人误以为验过了。
JSON_ELEMENT_NAMES: list[str] = [
    "schemaVersion", "headword", "glosses", "states", "origin", "mastered",
    "photoPath", "takenAt", "objects", "summarySource",
]

# R8 判定整个类无用时会写成 `X -> R8$$REMOVED$$CLASS$$123`，那不是「保住了原名」。
REMOVED_MARKERS = ("R8$$REMOVED$$CLASS$$", "$$REMOVED$$")

# mapping.txt 的三种行：顶层类声明（不缩进）、字段映射、方法映射（都缩进）。
_CLASS_LINE = re.compile(r"^(?P<orig>[\w.$]+)(?: -> (?P<obf>[\w.$<>\[\]-]+))?:(?:\s*$)")
_FIELD_LINE = re.compile(
    r"^\s+(?P<type>[\w.$]+(?:\[\])?) (?P<name>[\w$<>]+) -> (?P<obf>[\w$<>]+)\s*$"
)
_METHOD_LINE = re.compile(
    r"^\s+(?:(?:\d+:\d+:)\s*)?(?P<ret>[\w.$]+(?:\[\])?)\s+"
    r"(?P<owner>[\w.$]+\.)?(?P<name>[\w$<>]+)\([^)]*\)"
    r"(?:\s*:\s*\d+:\d+)? -> (?P<obf>[\w$<>]+)\s*$"
)


class Mapping:
    """`mapping.txt` 的按需索引：类 -> 改名，类 -> {成员原名: 成员改名}。"""

    def __init__(self, path: Path) -> None:
        self.classes: dict[str, str] = {}
        self.fields: dict[str, dict[str, str]] = {}
        self.methods: dict[str, dict[str, str]] = {}
        self._parse(path)

    def _parse(self, path: Path) -> None:
        current: str | None = None
        # 七十 MB 的文件逐行流式读，不整块进内存；注释行（`# {"id":...}`）与续行直接跳过。
        with path.open("r", encoding="utf-8", errors="replace") as handle:
            for raw in handle:
                line = raw.rstrip("\n")
                if not line.strip() or line.lstrip().startswith("#"):
                    continue
                if not line.startswith(" "):
                    match = _CLASS_LINE.match(line)
                    if not match:
                        current = None
                        continue
                    current = match.group("orig")
                    self.classes.setdefault(current, match.group("obf") or current)
                    self.fields.setdefault(current, {})
                    self.methods.setdefault(current, {})
                    continue
                if current is None:
                    continue
                field = _FIELD_LINE.match(line)
                if field:
                    self.fields[current][field.group("name")] = field.group("obf")
                    continue
                method = _METHOD_LINE.match(line)
                if method:
                    self.methods[current][method.group("name")] = method.group("obf")

    def obf_of(self, orig: str) -> str | None:
        return self.classes.get(orig)

    def is_removed(self, orig: str) -> bool:
        obf = self.classes.get(orig)
        return obf is not None and any(marker in obf for marker in REMOVED_MARKERS)

    def is_kept(self, orig: str) -> bool:
        return self.classes.get(orig) == orig


class Report:
    """累积结果。failures 按「被检查的名字」归组，汇总表就能逐行对齐，不用拿字符串去猜。"""

    def __init__(self) -> None:
        self.failures: dict[str, list[str]] = {}
        self.warnings: list[str] = []

    def fail(self, subject: str, message: str) -> None:
        self.failures.setdefault(subject, []).append(message)

    @property
    def failure_count(self) -> int:
        return sum(len(messages) for messages in self.failures.values())

    def status(self, subject: str) -> str:
        return "FAIL" if subject in self.failures else "OK"


def check_mapping(mapping: Mapping, report: Report) -> None:
    """主断言：落盘要用的名字在 R8 之后还在不在。"""

    # 1) 枚举常量名。本项目最要命的一条：常量名本身就是落盘的字符串。
    for klass, constants in PERSISTED_ENUMS.items():
        if klass not in mapping.classes:
            report.fail(klass, "整个不在 mapping 里（被删了，或类名写法变了）")
            continue
        if mapping.is_removed(klass):
            report.fail(klass, "被 R8 判定为无用并移除")
            continue
        kept = mapping.fields[klass]
        for constant in constants:
            obf = kept.get(constant)
            if obf is None:
                report.fail(
                    klass,
                    f"{constant} 在 mapping 里没有条目 —— 常量字段被 R8 从类里删掉了"
                    f"（对象可能还在 $VALUES 里，所以这一版不一定读不回），"
                    f"但名字不再由规则钉住：不再直接引用它、或换一版 R8，就没有任何东西保证还在",
                )
            elif obf != constant:
                report.fail(
                    klass,
                    f"{constant} 被改名为 {obf} —— proguard 里的枚举成员规则没起作用。"
                    f"实测当前 R8（9.3.16）不会重写 <clinit> 传给 Enum.<init> 的 name 字符串，"
                    f"所以这不代表数据已经在丢；但落盘名字从此取决于 R8 的实现细节，"
                    f"而 coerceInputValues 会把真出事的那天吞成默认值",
                )
        # values()/valueOf()：反射建 EnumSerializer 与按名字回读的入口。
        # 这两个才是「当场就坏」的东西，改名或裁掉没有侥幸。
        for method in ("values", "valueOf"):
            obf = mapping.methods[klass].get(method)
            if obf is None:
                report.fail(klass, f"{method}() 被裁掉 —— 反射取不到序列化器就退化成默认值")
            elif obf != method:
                report.fail(klass, f"{method}() 被改名为 {obf}")
        # 类名允许改：它不落盘（descriptor 的 serial name 是字符串常量）。只当变化哨兵。
        # 常量本身没过的时候不要再说「已核对通过」——那行提示会变成误导。
        if not mapping.is_kept(klass) and klass not in report.failures:
            report.warnings.append(
                f"（可接受）枚举类名被改：{klass} -> {mapping.obf_of(klass)}；常量名已核对通过"
            )

    # 2) 生成的 $$serializer 与数据类本体。
    for klass in SERIALIZER_CLASSES:
        serializer = f"{klass}$$serializer"
        if serializer not in mapping.classes:
            report.fail(
                serializer,
                f"不存在 —— kotlinx.serialization 没为 {klass} 留下序列化器，或它被 R8 移除；"
                f"文档会读成默认值而不是报错",
            )
        elif mapping.is_removed(serializer):
            report.fail(serializer, "被 R8 移除")
        elif not mapping.is_kept(serializer):
            report.fail(
                serializer,
                f"被改名为 {mapping.obf_of(serializer)} —— "
                f"`-keep,includedescriptorclasses class **$$serializer {{ *; }}` 没生效",
            )
        if klass not in mapping.classes or mapping.is_removed(klass):
            report.fail(klass, "数据类本体不在产物里 —— 字段自然也无从读起")
        elif not mapping.is_kept(klass) and klass not in report.failures:
            report.warnings.append(
                f"（可接受）数据类名被改：{klass} -> {mapping.obf_of(klass)}；"
                f"JSON 元素名是 $$serializer 里的字符串常量，不依赖这个类名"
            )

    # 3) Companion 字段：AppContainer 调的是 `DeckDocument.serializer()`，而枚举的序列化器
    #    是运行时按名字从 Companion 上找 `serializer()` 建出来的。名字改了反射就查不到。
    for klass in COMPANION_CLASSES:
        fields = mapping.fields.get(klass, {})
        if fields.get("Companion", "Companion") != "Companion":
            report.fail(
                klass,
                f"Companion 被改名为 {fields['Companion']} —— 反射按名字取 "
                f"cachedSerializer/serializer() 的路径断了",
            )
        if klass in mapping.classes and not fields:
            report.warnings.append(
                f"（核对不到）{klass} 在 mapping 里没有成员条目，Companion 未验证"
            )


def check_apk(apk: Path, report: Report) -> list[str]:
    """在 dex 里找落盘用的字符串常量，作为 mapping 之外的第二份证据。

    返回「命中但不足以证明」的短名字。dex 字符串是 `长度字节 + 原文`，把长度一起匹配能挡掉
    绝大多数偶然命中，但 <=6 字符的名字仍可能撞车，所以它们不计入证明；
    反过来**缺失一定是真缺失**，一律算硬失败。
    """
    with zipfile.ZipFile(apk) as archive:
        dex_names = sorted(n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        if not dex_names:
            report.fail(apk.name, "里找不到 classes*.dex —— 这不是一个正常的 release APK")
            return []
        blob = b"".join(archive.read(name) for name in dex_names)

    values = JSON_ELEMENT_NAMES + sorted(
        {name for constants in PERSISTED_ENUMS.values() for name in constants if len(name) > 6}
    )
    weak: list[str] = []
    for value in values:
        encoded = value.encode("utf-8")
        needle = bytes([len(encoded)]) + encoded if len(encoded) < 128 else encoded
        if needle in blob:
            if len(value) <= 6:
                weak.append(value)
        else:
            report.fail(
                "classes.dex",
                f"字符串 \"{value}\" 找不到 —— 落盘要用的名字没进产物，读回这份数据一定退化成默认值",
            )
    return weak


def main(argv: list[str]) -> int:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--mapping",
        type=Path,
        default=root / "app/build/outputs/mapping/release/mapping.txt",
        help="R8 mapping.txt 路径（默认 app/build/outputs/mapping/release/mapping.txt）",
    )
    parser.add_argument(
        "--apk",
        type=Path,
        default=None,
        help="release APK 路径；缺省时在 app/build/outputs/apk/release 下自动找",
    )
    args = parser.parse_args(argv)

    print("WordLens release 数据可发现性冒烟")
    print("=" * 72)

    mapping_path: Path = args.mapping
    if not mapping_path.is_file():
        # 明确失败，绝不静默通过：没有 mapping.txt 就等于这一步什么都没验。
        print(
            f"错误：找不到 mapping.txt：{mapping_path}\n"
            f"      先跑 `./gradlew assembleRelease`（本项目 release 已开 minify，一定会产出），\n"
            f"      产物在 app/build/outputs/mapping/release/mapping.txt。",
            file=sys.stderr,
        )
        return 2

    print(f"mapping : {mapping_path}")
    mapping = Mapping(mapping_path)
    if not mapping.classes:
        print("错误：mapping.txt 解析结果为空，文件被截断或格式变了。", file=sys.stderr)
        return 2
    print(f"          解析到 {len(mapping.classes)} 个类")

    report = Report()
    check_mapping(mapping, report)

    apk = args.apk
    if apk is None:
        candidates = sorted((root / "app/build/outputs/apk/release").glob("*.apk"))
        apk = candidates[0] if candidates else None
    apk_checked = apk is not None and Path(apk).is_file()
    if not apk_checked:
        print(
            "APK     : 未找到 app/build/outputs/apk/release/*.apk —— dex 字符串佐证这一步没跑，"
            "本次只有 mapping.txt 层面的断言生效",
            file=sys.stderr,
        )
    else:
        print(f"APK     : {apk}")
        weak = check_apk(Path(apk), report)
        if weak:
            print(f"          弱证据命中（名字太短，不足以证明）: {', '.join(weak)}")

    print("-" * 72)
    print("必须存活的名字：")
    for klass, constants in PERSISTED_ENUMS.items():
        short = klass.rsplit(".", 1)[-1]
        print(f"  [{report.status(klass)}] 枚举常量 {short:<15}{' '.join(constants)}")
    for klass in SERIALIZER_CLASSES:
        serializer = f"{klass}$$serializer"
        print(f"  [{report.status(serializer)}] 序列化器 {serializer.rsplit('.', 1)[-1]}")
    if apk_checked:
        print(f"  [{report.status('classes.dex')}] dex 里的落盘字符串常量")
    print("-" * 72)

    for line in report.warnings:
        print(f"提示: {line}")

    if report.failure_count:
        print(f"\n失败 {report.failure_count} 项：", file=sys.stderr)
        for subject, messages in report.failures.items():
            for message in messages:
                print(f"  [x] {subject}: {message}", file=sys.stderr)
        print(
            "\n结论：release 产物的落盘格式不稳定。修 app/proguard-rules.pro，不要修这些断言。",
            file=sys.stderr,
        )
        return 1

    print("\n通过：落盘的成员名、枚举名与生成的序列化器在 release 产物里都保住了。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
