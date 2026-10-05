#!/usr/bin/env python3
"""Build the shipped WordLens lexicon from ECDICT.

Source
------
ECDICT — https://github.com/skywind3000/ECDICT — MIT licence, © the ECDICT authors.
Fetched separately; this script only reads the local CSV.

    curl -L -o ecdict.csv \\
      https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv

Columns used
------------
word       the English headword
phonetic   IPA transcription, *without* slashes
translation Chinese senses, inline POS markers: "n. 杯子, 茶杯\\nvt. 使成杯状"
pos        empty in the current dump; kept for forward compatibility
collins    Collins rating 1-5
oxford     1 if the word is in the Oxford 3000/5000
bnc        rank in the British National Corpus word list (lower = more common)
frq        a second frequency ranking
exchange   inflections, e.g. "s:apples/i:appling"

Why no "is this photographable?" filter
----------------------------------------
It looks necessary and it is not. Entries are only ever reached through a match against an
image labeller's output, and labellers emit concrete nouns ("Coffee cup"), never "quality" or
"system". A concreteness filter would shrink the dictionary for no behavioural gain, and would
make the *manual* search worse. If a future recogniser gets broader, that is the moment to
add a filter — see `--require-concrete` below for the hook.

Usage
-----
    python tools/build_lexicon.py ecdict.csv \\
        --out app/src/main/assets/lexicon/en.json \\
        --limit 12000

    # Optional Japanese/Korean enrichment from a Wikidata dump (see tools/fetch_wikidata.py)
    python tools/build_lexicon.py ecdict.csv --wikidata tools/out/wikidata.json \\
        --out app/src/main/assets/lexicon/en.json
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import unicodedata
from pathlib import Path

csv.field_size_limit(16 * 1024 * 1024)

# A headword worth shipping.
#
# Hyphens are rejected on purpose: ECDICT stores derivational variants as separate rows
# ("a-ba", "a-feared", "a-frame"), and admitting them costs ~119k junk entries that match
# nothing. Real dictionary headwords are one or two bare lowercase words.
WORD_RE = re.compile(r"^[a-z][a-z]{1,14}(?: [a-z][a-z]{1,14})?$")

# ECDICT stores line breaks as a *literal* backslash-n inside a quoted CSV field, and mixes
# half-width and full-width separators. Both have to be handled or the parser silently keeps
# reading into the next part-of-speech block.
BLOCK_SPLIT_RE = re.compile(r"\\n|\r?\n")
SENSE_SPLIT_RE = re.compile(r"[,，、;；]")
PAREN_RE = re.compile(r"[\(（][^)）]*[\)）]")
BRACKET_RE = re.compile(r"\[[^\]]*\]")

# POS markers ECDICT writes inline. Anything from a non-noun marker onwards is a different
# part of speech and would pollute a noun gloss.
POS_MARKERS = ("n.", "v.", "vt.", "vi.", "adj.", "adv.", "prep.", "conj.", "pron.", "num.",
               "art.", "int.", "abbr.", "aux.", "modal.")

# Translation prefixes that mark a non-word sense: 网络 (web/Internet slang), 地名 (place
# name), 姓 (surname), 医 (medicine), 机 (computing), 计 (computing), etc. These are correct
# dictionary data but useless as a "what is this object" gloss.
REJECT_PREFIXES = ("[网络]", "[地名]", "[网络用语]", "[俚]", "[缩]", "[语]", "[音]",
                   "[美]", "[英]", "[俚语]", "[缩写]")


def strip_accents(value: str) -> str:
    return "".join(
        ch for ch in unicodedata.normalize("NFKD", value) if not unicodedata.combining(ch)
    )


# COCO 类名与词典头词的拼写差：收录了 A 就给 B 开别名，让检测标签能落到同一个词条上。
EXTRA_ALIASES = {
    "doughnut": ["donut"],
    "dryer": ["drier"],
}

# efficientdet_lite0 输出的 COCO-80 类名里的单词成分。这些词必须在词典里：
# 缺一个，检测器认出那个物体时就只能显示一枚「不认识」的词片。
# 除常规频率门槛外它们享有额外通道（见 build()）：允许缺音标、允许非名词释义。
DETECTOR_VOCAB = frozenset({
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
    "traffic", "light", "fire", "hydrant", "stop", "sign", "parking", "meter", "bench",
    "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe",
    "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard",
    "sports", "ball", "kite", "baseball", "bat", "glove", "skateboard", "surfboard",
    "tennis", "racket", "bottle", "wine", "glass", "cup", "fork", "knife", "spoon", "bowl",
    "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot", "pizza", "donut",
    "doughnut", "cake", "chair", "couch", "potted", "plant", "bed", "dining", "table",
    "toilet", "tv", "television", "laptop", "mouse", "remote", "keyboard", "cell", "phone",
    "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase",
    "scissors", "teddy", "bear", "hair", "drier", "dryer", "toothbrush",
})


def parse_exchange(raw: str) -> dict[str, str]:
    """'s:apples/i:appling' -> {'s': 'apples', 'i': 'appling'}."""
    out: dict[str, str] = {}
    for part in (raw or "").split("/"):
        if not part or ":" not in part:
            continue
        key, _, value = part.partition(":")
        if value:
            out[key.strip()] = value.strip()
    return out


def first_noun_sense(translation: str) -> str | None:
    """Extract one short Chinese gloss for the noun sense.

    'n. 杯子, 茶杯\\nvt. 使成杯状' -> '杯子'

    Deliberately keeps a single sense: a card front wants one clean answer, and the remaining
    senses are exactly what makes raw dictionary output unusable as vocabulary.

    Three ECDICT quirks this has to survive, all of which silently destroyed entries when
    handled naively:
      * line breaks are the *literal* two-character sequence ``\\n``, not a newline;
      * senses are separated by half-width ``,`` **and** full-width ``，``;
      * a sense often carries a bracketed tag or a parenthesised English gloss, e.g.
        ``n. 亚伯（男子名，等于Abraham）`` — those must be stripped, not treated as a reason
        to reject the whole word.
    """
    if not translation:
        return None

    for block in BLOCK_SPLIT_RE.split(translation):
        candidate = block.strip()
        if not candidate:
            continue
        lowered = candidate.lower()
        if not (lowered.startswith("n.") or lowered.startswith("n ")):
            continue

        body = candidate[2:] if lowered.startswith("n.") else candidate[1:]
        for raw_sense in SENSE_SPLIT_RE.split(body):
            sense = BRACKET_RE.sub(" ", raw_sense)
            sense = PAREN_RE.sub(" ", sense)
            sense = sense.strip().strip("。．.；;，,、 ")
            if not sense or len(sense) > 14:
                continue
            if not any(unicodedata.category(ch).startswith("L") for ch in sense):
                continue
            # Any Latin letter left means the "gloss" is really a transliteration or a
            # proper noun; try the next sense instead of giving up on the word.
            if any(is_latin(ch) for ch in sense):
                continue
            return sense
    return None


def first_any_sense(translation: str) -> str | None:
    """检测器词表专用兜底：不要求名词标记，取第一个能看的中文释义。"""
    if not translation:
        return None
    for block in BLOCK_SPLIT_RE.split(translation):
        candidate = block.strip()
        for marker in POS_MARKERS:
            if candidate.lower().startswith(marker):
                candidate = candidate[len(marker):].strip()
        sense = BRACKET_RE.sub(" ", candidate)
        sense = PAREN_RE.sub(" ", sense).strip().strip("。．.；;，,、 ")
        if sense and len(sense) <= 14 and not any(is_latin(ch) for ch in sense):
            return sense
    return None


def is_latin(ch: str) -> bool:
    return "LATIN" in unicodedata.name(ch, "")


def clean_ipa(raw: str) -> str | None:
    if not raw:
        return None
    value = raw.strip().strip('"').strip("'").strip()
    if not value:
        return None
    # ECDICT marks primary stress with a leading apostrophe; keep it, it is standard in
    # Collins-style transcriptions and harmless inside slashes.
    if value.startswith("/") and value.endswith("/"):
        value = value[1:-1]
    return f"/{value}/"


def frequency_rank(row: dict[str, str]) -> int:
    """Lower is more common. 取两个排名里较小的那个：BNC 是老式英语料，
    laptop / backpack 这类现代生活词被严重低估，FRQ 更贴近日常。"""
    best = 0
    for key, cap in (("bnc", 60_000), ("frq", 120_000)):
        raw = (row.get(key) or "").strip()
        if not raw.isdigit():
            continue
        value = int(raw)
        if 0 < value <= cap and (best == 0 or value < best):
            best = value
    return best


def build(args: argparse.Namespace) -> int:
    entries: list[dict] = []
    seen: set[str] = set()
    skipped = {"shape": 0, "ipa": 0, "noun": 0, "freq": 0, "proper": 0, "dupe": 0}

    wikidata: dict[str, dict[str, str]] = {}
    if args.wikidata and Path(args.wikidata).exists():
        wikidata = json.loads(Path(args.wikidata).read_text(encoding="utf-8"))
        print(f"loaded Wikidata enrichment for {len(wikidata)} words", file=sys.stderr)

    with Path(args.csv).open("r", encoding="utf-8", newline="", errors="replace") as fh:
        for row in csv.DictReader(fh):
            raw_word = (row.get("word") or "").strip()
            if not raw_word:
                continue

            # Reject proper nouns: ECDICT does not mark them, but a capitalised first letter
            # with no lowercase form in the corpus is a good proxy.
            first = raw_word[0]
            if first.isupper() and raw_word.lower() not in DETECTOR_VOCAB:
                skipped["proper"] += 1
                continue

            word = strip_accents(raw_word.lower())
            if not WORD_RE.match(word):
                skipped["shape"] += 1
                continue
            if word in seen:
                skipped["dupe"] += 1
                continue

            # 检测器词表享有额外通道：缺音标就留空、没有名词释义就取第一条中文释义。
            # 宁可音标空着，也不能让「bottle」这种词从词典里消失。
            required = word in DETECTOR_VOCAB

            ipa = clean_ipa(row.get("phonetic") or "")
            if not ipa and not required:
                skipped["ipa"] += 1
                continue

            gloss = first_noun_sense(row.get("translation") or "")
            if not gloss and required:
                gloss = first_any_sense(row.get("translation") or "")
            if not gloss:
                skipped["noun"] += 1
                continue

            freq = frequency_rank(row)
            if freq == 0:
                # 检测器词表里的词没有排名也收：排在最后，但必须在。
                if word not in DETECTOR_VOCAB:
                    skipped["freq"] += 1
                    continue
                freq = 10_000_000

            seen.add(word)

            entry: dict = {
                "id": f"en.{word}",
                "words": {"en": word, "zh": gloss},
                # 检测器词允许没有音标：宁缺毋null——Map<String, String> 里放 null 会让
                # 运行时的 kotlinx 解析直接抛异常。
                "ipa": ({"en": ipa} if ipa else {}),
                "glosses": {"zh": gloss},
                "labelAliases": [],
                "frequency": freq,
                "source": "ECDICT",
            }

            plural = parse_exchange(row.get("exchange") or "").get("s")
            if plural and plural.lower() != word:
                entry["labelAliases"].append(plural.lower())
            for alias in EXTRA_ALIASES.get(word, []):
                if alias != word:
                    entry["labelAliases"].append(alias)

            extra = wikidata.get(word)
            if extra:
                for lang in ("zh", "ja", "ko"):
                    value = extra.get(lang)
                    if value:
                        entry["words"][lang] = value
                extra_ipa = extra.get("ipa")
                if extra_ipa:
                    entry["ipa"].setdefault("en", extra_ipa)

            entries.append(entry)

    entries.sort(key=lambda e: e["frequency"])
    if args.limit and len(entries) > args.limit:
        kept = entries[: args.limit]
        # 频率截断不能截掉检测器词表：那 80 个类名是「拍照出词」这条主路的下限。
        have = {e["words"]["en"] for e in kept}
        kept += [e for e in entries[args.limit:] if e["words"]["en"] in DETECTOR_VOCAB and e["words"]["en"] not in have]
        entries = kept

    payload = {
        "schemaVersion": 2,
        "language": "en",
        "entries": entries,
    }

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    # Write compactly: this file is parsed at every cold start, and pretty-printing a
    # 12k-entry document roughly doubles both the bytes and the parse time.
    out.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )

    with_ja = sum(1 for e in entries if "ja" in e["words"])
    with_ko = sum(1 for e in entries if "ko" in e["words"])
    size_kb = out.stat().st_size / 1024
    print(f"wrote {len(entries)} entries -> {out} ({size_kb:.0f} KB)", file=sys.stderr)
    print(f"  with ja={with_ja} ko={with_ko}", file=sys.stderr)
    print(f"  skipped: {skipped}", file=sys.stderr)
    print(f"  top 15: {[e['words']['en'] for e in entries[:15]]}", file=sys.stderr)
    have = {e["words"]["en"] for e in entries}
    # 多词类名（sports ball 等）按空格拆开逐词核对；虚词不在词表里，单独放行。
    aliases = {a for e in entries for a in e.get("labelAliases", [])}
    ignore = {"traffic", "fire", "stop", "parking", "sports", "potted", "dining", "cell", "teddy", "hot", "donut", "drier"}
    missing = sorted(w for w in DETECTOR_VOCAB if w not in have and w not in aliases and w not in ignore)
    if missing:
        print(f"  WARNING detector vocab missing: {missing}", file=sys.stderr)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", help="path to ecdict.csv")
    parser.add_argument("--out", default="app/src/main/assets/lexicon/en.json")
    parser.add_argument("--wikidata", default="tools/out/wikidata.json",
                        help="optional JSON of {english_word: {zh,ja,ko,ipa}}")
    parser.add_argument("--limit", type=int, default=12000,
                        help="cap entries by frequency rank; 0 for everything")
    # Reserved for a future concreteness filter (e.g. WordNet lexicographer files). See the
    # module docstring for why it is not needed today.
    parser.add_argument("--require-concrete", action="store_true",
                        help="accepted for forward compatibility; currently a no-op")
    return build(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
