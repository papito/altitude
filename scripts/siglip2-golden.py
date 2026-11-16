#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "torch==2.15.0",
#   "transformers==5.22.1",
#   "pillow==13.0.0",
#   "numpy==2.5.4",
# ]
#
# [[tool.uv.index]]
# name = "pytorch-cpu"
# url = "https://download.pytorch.org/whl/cpu"
# explicit = true
#
# [tool.uv.sources]
# torch = { index = "pytorch-cpu" }
# ///
"""Writes the golden files SigLIP 2's text side is tested against, from the reference implementation: the processor and
PyTorch model of `google/siglip2-base-patch16-256`, the checkpoint that `export-models.py` of
`renegade-otter/altitude-models` exports.

    altitude/test/resources/siglip2/tokens.json      {"description", "ids"}: the 64 token IDs the reference processor
                                                     gives each description, for `Siglip2TokenizerTests`
    altitude/test/resources/siglip2/embeddings.json  {"description", "embedding"}: the 768 floats of each description's
                                                     L2-normalized embedding, for `DescribeEmbedderTests`

The IDs are of the description in lowercase, padded and truncated to 64 tokens as the model was trained. The lowercasing
is done here: SigLIP 2 was trained on lowercased text, but the checkpoint's tokenizer (`GemmaTokenizer`) keeps the case.
A golden description is stored as written, since the application's tokenizer lowercases it and its embedder collapses
its whitespace (`normalized`).

The descriptions cover what a description can hold, from plain words to other scripts, emoji, byte fallbacks, the pieces
the tokenizer matches whole and descriptions past 64 tokens; the run fails if a kind the tests rely on is gone
(`check_coverage`). Both files are tracked. Run from the repository root after a change to the descriptions or to the
checkpoint:

    uv run scripts/siglip2-golden.py

The versions pinned above are those of `export-models.py`.
"""

from __future__ import annotations

import json
from pathlib import Path

import torch
from transformers import AutoProcessor, SiglipModel

REPO_ROOT = Path(__file__).resolve().parent.parent
OUTPUT_DIR = REPO_ROOT / "altitude" / "test" / "resources" / "siglip2"

CHECKPOINT = "google/siglip2-base-patch16-256"
MAX_TOKENS = 64
PAD_ID = 0
EOS_ID = 1
# The IDs of the byte-fallback pieces <0x00>..<0xFF>, in the checkpoint's vocabulary.
BYTE_IDS = range(217, 473)
EMBEDDING_DECIMALS = 7

PLAIN = [
    "woman in a park",
    "a woman in a park",
    "a man in a suit",
    "a cactus",
    "a cactus in a pot on a windowsill",
    "two dogs running on the beach",
    "a red car parked on a street at night",
    "children playing football in the rain",
    "sunset over the ocean",
    "a bowl of fruit on a wooden table",
    "snow covered mountains under a blue sky",
    "a black and white portrait of an old man",
    "birthday cake with candles",
    "a bride and groom dancing",
    "city skyline at dusk",
    "a cat sleeping on a sofa",
    "people sitting around a campfire",
    "a bicycle leaning against a brick wall",
    "close up of a bee on a sunflower",
    "an empty road through a forest in autumn",
    "a baby laughing",
    "fireworks",
    "dog",
    "a",
    "the eiffel tower",
    "a plate of spaghetti with tomato sauce and basil",
    "a group of friends taking a selfie",
    "a lighthouse on a rocky coast during a storm",
    "a girl reading a book under a tree",
    "an airplane wing seen from a window seat",
    "a street market with colorful umbrellas",
    "a man holding a fish he caught",
    "graduation ceremony",
    "a horse galloping across a field",
    "underwater photo of a sea turtle",
    "a crowded subway platform",
    "handwritten notes on a whiteboard",
    "a screenshot of a spreadsheet",
    "an aerial view of farmland",
    "blurry photo of a concert stage with purple lights",
    "photosynthesis",
    "antidisestablishmentarianism",
    "supercalifragilisticexpialidocious",
    "a photograph",
    "unrecognizable",
]

PUNCTUATION = [
    "hello, world!",
    "a dog, a cat, and a bird.",
    "what is this?",
    "wait... what",
    "wow!!!",
    "really???",
    "yes!?",
    "mother-in-law",
    "black-and-white photo",
    "a (very) small dog",
    'a sign that says "open"',
    "a sign that says 'closed'",
    "rock & roll",
    "salt/pepper",
    "50% off",
    "#sunset #nofilter",
    "@home",
    "a+b=c",
    "c++ and c#",
    "price: $20",
    "http://example.com/a?b=c&d=e",
    "someone@example.com",
    "semi;colon",
    "under_score",
    "back\\slash",
    "pipe|pipe",
    "tilde~",
    "caret^",
    "star*",
    "[brackets] {braces} <angles>",
    "em—dash and en–dash",
    "“curly quotes” and ‘single’",
    "ellipsis…",
    "a.b.c",
    "end.",
    ".",
    ",",
    "?",
    "-",
    "(",
    "¿qué? ¡sí!",
    "«guillemets»",
    "bullet • point",
    "10 °c",
    "5 × 3 ÷ 2 ± 1",
    "© 2024 ™ ®",
    "€ £ ¥ ₹",
]

DIGITS = [
    "2 dogs",
    "3 people at a table",
    "1990s",
    "3.14",
    "1,000,000",
    "24mm f/1.4",
    "iso 100",
    "4k",
    "2024-05-17",
    "12:30",
    "007",
    "0",
    "1234567890",
    "route 66",
    "a 5 year old boy",
    "1st 2nd 3rd 4th",
    "h2o",
    "½ cup",
    "x²",
]

SPACES = [
    " leading",
    "trailing ",
    " both ",
    "two  spaces",
    "three   spaces",
    "  two leading",
    "two trailing  ",
    " ",
    "  ",
    "   ",
    "a b",
    "a  b",
    "a   b",
    "a    b",
    "a" + " " * 10 + "b",
    "a" + " " * 30 + "b",
    "a" + " " * 31 + "b",
    "a" + " " * 32 + "b",
    "a" + " " * 45 + "b",
    "a\tb",
    "a\t\tb",
    "\ttab first",
    "a\nb",
    "a\n\nb",
    "a \n b",
    "line one\nline two\nline three",
    "a dog)\nand a cat",
    "\n",
    "\n\n\n",
    "a" + "\n" * 31 + "b",
    "a" + "\n" * 33 + "b",
    "a\r\nb",
    "a\rb",
    "a b",
    "a　b",
    "a​b",
    "a b",
    "a­b",
    "a\x0bb",
    "a\x0cb",
]

APOSTROPHES = [
    "don't",
    "it's a dog",
    "women's shoes",
    "o'clock",
    "rock'n'roll",
    "the dogs' bowls",
    "don’t",
    "l'été",
    "'quoted'",
    "'",
    "''",
    "y'all'd've",
]

ACCENTS_AND_SCRIPTS = [
    "café",
    "café",
    "naïve",
    "über",
    "niño",
    "são paulo",
    "crème brûlée",
    "ærøskøbing",
    "łódź",
    "smörgåsbord",
    "piñata",
    "tiếng việt",
    "con mèo trên ghế",
    "москва зимой",
    "привет мир",
    "київ",
    "αθήνα",
    "καλημέρα κόσμε",
    "قطة على الأريكة",
    "חתול על הספה",
    "बिल्ली सोफे पर",
    "แมวบนโซฟา",
    "ქართული",
    "հայերեն",
    "ä",
    "é̂",
]

CASE = [
    "A Woman In A Park",
    "A WOMAN IN A PARK",
    "NEW YORK CITY",
    "iPhone",
    "McDonald's",
    "eBay",
    "NASA",
    "LaTeX",
    "CamelCaseWord",
    "Café",
    "ÉCOLE",
    "ÜBER",
    "МОСКВА",
    "ΑΘΗΝΑ",
    # A capital sigma lowercases by its place: final at a word's end, medial elsewhere.
    "ΟΔΟΣ",
    "ΟΔΟΣ ΟΔΟΣ",
    "ΣΊΣΥΦΟΣ",
    "Σ",
    "ΑΣ.",
    # The place is read by Unicode's rule, which looks at the nearest character that is not case-ignorable on each side
    # and at nothing beyond it: a digit before the sigma makes it medial, and a digit after it leaves it final.
    "ΣΑΣ17037woman",
    "YOUNG60946Σ",
    "ΣΑΣ...park",
    "ΣΑΣ`city",
    "ΣΑΣ-and",
    "ΑΣ'Α",
    "ΑΣ:Α",
    "Α\u0301Σ\u0301",
    "1Σ",
    "ΑΣ1Σ",
    # The dotted capital I lowercases to two characters, an i and a combining dot.
    "İstanbul",
    "IŞIK",
    "I",
    "ẞ",
    "Straße",
    "ǅ",
    "Ⅷ",
    "Ⓐ",
    "ＡＢＣ１２３",
    "Ა",
    "Ω",
    "K",
    "Å",
]

CJK = [
    "猫",
    "東京タワー",
    "日本の桜",
    "北京的秋天",
    "一只猫坐在沙发上",
    "한국의 가을",
    "山と川",
    "a 猫 on a 沙发",
    "ｶﾀｶﾅ",
    "繁體中文",
    "こんにちは、世界！",
    "「引用」",
]

EMOJI = [
    "😀",
    "🐶 dog",
    "a dog 🐶",
    "❤️",
    "👍🏽",
    "👨‍👩‍👧‍👦",
    "🇫🇷",
    "1️⃣",
    "🎉🎉🎉",
    "☀ sunny",
    "🫨",
    "🪿 goose",
    "🩷",
    "🫎",
]

# Characters the vocabulary has no piece for: each becomes the pieces of its UTF-8 bytes.
BYTE_FALLBACK = [
    "\U00013000",
    "a\U00013000b",
    "\U00013000\U00013001",
    "\U00020000",
    "\U0002a700 \U00030000",
    "\U000f0000",
    "a \U0001fae8 b \U0001fabf c",
    "\ud7fb",
    "\u0bf5",
    "\u2e80",
    "\U0001f700\U0001fbf0",
    "\U00016800",
    "a\u0000b",
    "\u0000",
    # A capital with a piece whose lowercase has none
    "\U00010400",
]

# Rare characters that do have a piece: private use, noncharacters, controls, separators.
RARE = [
    "\ue000",
    "a\ue000b",
    "\U0010ffff",
    "\U000e0041",
    "\ufffd",
    "\u0001",
    "\x7f",
    "\u0085",
    "\u2028",
    "\U0001f9ff\U0001fa70\U0001fa80",
]

# What the tokenizer matches whole in the text, before anything else, and near misses of it.
WHOLE_PIECES = [
    "<b>bold</b>",
    "<i>a</i> <u>b</u> <s>c</s>",
    "<h1>title</h1>",
    "<table><tr><td>cell</td></tr></table>",
    "<em>very</em> <strong>much</strong>",
    "<code>x</code><sub>1</sub><sup>2</sup>",
    "<blockquote>",
    "<br>",
    "<div>",
    "< b >",
    "<B>",
    "<eos>",
    "<pad>",
    "<bos>",
    "<unk>",
    "<mask>",
    "<EOS>",
    "a<eos>b",
    "a <eos> b",
    "<eos><eos>",
    "<eo s>",
    "<start_of_turn>user",
    "<end_of_turn>",
    "<unused0>",
    "<unused98>",
    "<unused99>",
    "<2mass>",
    "[@BOS@]",
    "<bb>",
    "<<b>>",
    "<b",
    "b>",
    "</b",
    "<0x41>",
    "<0xE2>",
    "a▁b",
    "▁",
    "▁▁",
    "a▁▁b",
    "a ▁ b",
]

RUNS = [
    "!!",
    "!!!!",
    "!!!!!!!",
    "...",
    "....",
    ".......",
    "aaa",
    "aaaa",
    "aaaaaaa",
    "aaaaaaaaaaaaaaaa",
    "hahaha",
    "hahahahahaha",
    "--",
    "---",
    "-----",
    "=====",
    "______",
    "*****",
    "#####",
    "zzzzzz",
    "ooooh",
    "nooooooo",
    "xoxoxo",
    "abababab",
    "\t\t\t",
    "\t" * 31,
    "\t" * 33,
    "\n" * 40,
    "a\n\n\n\n\nb",
    "0000000",
    "1111111111",
    "((()))",
    "////",
    "\\\\\\\\",
    "~~~~",
    "猫猫猫猫",
    "😀😀😀😀",
    "::::",
    ";;;;",
    ",,,,",
    "''''",
    '""""',
]

LONG = [
    "a wide photograph of a busy night market in a narrow street, with red paper lanterns hanging overhead, steam rising "
    "from food stalls, a vendor in a white apron handing a paper bag to a child, bicycles parked along the wall on the "
    "left, neon signs in several colors reflected in puddles on the wet pavement, and a crowd of people walking away "
    "from the camera under umbrellas",
    "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen "
    "nineteen twenty twenty-one twenty-two twenty-three twenty-four twenty-five twenty-six twenty-seven twenty-eight "
    "twenty-nine thirty thirty-one thirty-two thirty-three thirty-four thirty-five thirty-six thirty-seven thirty-eight",
    "猫" * 100,
    "😀" * 70,
    "\U0001fae8" * 20,
    "a " * 150,
    "word" + " " * 200 + "word",
]

# The descriptions the embeddings are checked on: a dozen, of every length the model sees.
EMBEDDED = [
    "woman in a park",
    "a man in a suit",
    "a cactus",
    "two dogs running on the beach",
    "A Red Car Parked On A Street At Night",
    "a sign that says \"open\", 24 hours",
    "crème brûlée",
    "東京タワー",
    "a dog 🐶",
    "dog",
    "line one\nline two",
    LONG[0],
]


def normalized(description: str) -> str:
    """A description as the application's embedder hands it to its tokenizer: each run of whitespace one space and none
    at the ends. `EMBEDDED` holds no whitespace but spaces and a line feed, which Python and the JVM both count as
    whitespace."""
    return " ".join(description.split())


def padded_to(count: int, token_count) -> str:
    """A description of plain words whose tokens, the <eos> not among them, number exactly `count`."""
    words = ["tree", "river", "stone", "cloud", "bird", "road", "house", "field"]
    text = ""
    at = 0
    while token_count(text) < count:
        text = f"{text} {words[at % len(words)]}".strip()
        at += 1
    if token_count(text) != count:
        raise SystemExit(f"No description of exactly {count} tokens was built: {token_count(text)}")
    return text


def check_coverage(entries: list[dict]) -> None:
    """Fails unless the golden descriptions still hold the cases the tests rely on."""
    by_text = {entry["description"]: entry["ids"] for entry in entries}
    if len(by_text) != len(entries):
        raise SystemExit("A description is listed twice")
    if any(len(ids) != MAX_TOKENS for ids in by_text.values()):
        raise SystemExit(f"A description has not {MAX_TOKENS} IDs")
    full = [text for text, ids in by_text.items() if ids[-1] != PAD_ID]
    if len(full) < 5 or any(by_text[text][-1] != EOS_ID for text in full):
        raise SystemExit("Fewer than five descriptions fill the 64 tokens, or one that does not ends in <eos>")
    fallen = [text for text, ids in by_text.items() if any(token in BYTE_IDS for token in ids)]
    if len(fallen) < 10:
        raise SystemExit(f"Only {len(fallen)} descriptions fall back to bytes")
    for text in BYTE_FALLBACK:
        if text not in fallen:
            raise SystemExit(f"{text!r} is listed as a byte fallback and has a piece for every character")
    if by_text[""] != [EOS_ID] + [PAD_ID] * (MAX_TOKENS - 1):
        raise SystemExit("The empty description is not an <eos> and pads")
    if by_text["A Woman In A Park"] != by_text["a woman in a park"]:
        raise SystemExit("The IDs are not those of the lowercase description")


def write_entries(path: Path, entries: list[dict]) -> None:
    """A JSON list with an entry a line, in ASCII, so a diff shows the entries that changed."""
    lines = ",\n".join(json.dumps(entry, ensure_ascii=True, separators=(",", ":")) for entry in entries)
    path.write_text(f"[\n{lines}\n]\n", encoding="ascii", newline="\n")
    print(f"wrote {path.relative_to(REPO_ROOT)}: {len(entries)} descriptions, {path.stat().st_size / 1000:.0f} kB")


def main() -> None:
    processor = AutoProcessor.from_pretrained(CHECKPOINT)

    def token_ids(descriptions: list[str]) -> torch.Tensor:
        # Lowercased here: the tokenizer the checkpoint names does not, and the model was trained on lowercase text.
        lowered = [description.lower() for description in descriptions]
        return processor(
            text=lowered, padding="max_length", max_length=MAX_TOKENS, truncation=True, return_tensors="pt"
        )["input_ids"]

    def token_count(description: str) -> int:
        # The tokenizer alone, uncut, without the <eos>
        return len(processor.tokenizer(description.lower(), add_special_tokens=False)["input_ids"])

    # One token short of full, exactly full with its <eos>, and one over, which loses its last token to the <eos>.
    at_the_limit = [padded_to(count, token_count) for count in (MAX_TOKENS - 2, MAX_TOKENS - 1, MAX_TOKENS)]
    descriptions = (
        [""]
        + PLAIN
        + PUNCTUATION
        + DIGITS
        + SPACES
        + APOSTROPHES
        + ACCENTS_AND_SCRIPTS
        + CASE
        + CJK
        + EMOJI
        + BYTE_FALLBACK
        + RARE
        + WHOLE_PIECES
        + RUNS
        + LONG
        + at_the_limit
    )
    ids = token_ids(descriptions)
    tokens = [{"description": text, "ids": row} for text, row in zip(descriptions, ids.tolist())]
    check_coverage(tokens)

    model = SiglipModel.from_pretrained(CHECKPOINT).eval()
    with torch.no_grad():
        pooled = model.get_text_features(input_ids=token_ids([normalized(text) for text in EMBEDDED])).pooler_output
    # Unit length, as `SiglipModel.forward` normalizes the text embeddings it compares and as the application stores them
    unit = pooled / pooled.norm(p=2, dim=-1, keepdim=True)
    embeddings = [
        {"description": text, "embedding": [round(value, EMBEDDING_DECIMALS) for value in row]}
        for text, row in zip(EMBEDDED, unit.tolist())
    ]

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    write_entries(OUTPUT_DIR / "tokens.json", tokens)
    write_entries(OUTPUT_DIR / "embeddings.json", embeddings)


if __name__ == "__main__":
    main()
