#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "torch==2.15.0",
#   "transformers==5.22.1",
#   "onnx==1.23.2",
#   "onnxscript==0.7.2",
#   "onnxruntime==1.31.1",
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
"""Fetches the model files the application needs and git does not hold: the ArcFace net, downloaded, and the two Scene
models with SigLIP 2's text side, exported from their Hugging Face checkpoints.

    altitude/resources/opencv/w600k_r50.onnx           ArcFace, InsightFace w600k_r50 (non-commercial research licence),
                                                       for `FaceDetectionService`: a 512-float face embedding
    altitude/resources/onnx/dinov2_vitb14.onnx         DINOv2 ViT-B/14, facebook/dinov2-base (Apache 2.0), for
                                                       `OnnxSceneEmbedder`: the pooled CLS token, 768 floats
    altitude/resources/onnx/siglip2_b16_256_image.onnx SigLIP 2 ViT-B/16 image tower at 256 px,
                                                       google/siglip2-base-patch16-256 (Apache 2.0), for
                                                       `OnnxSceneEmbedder`: the vision tower's pooled output (its
                                                       attention-pooling head), 768 floats
    altitude/resources/onnx/siglip2_b16_256_text.onnx  The same checkpoint's text tower without its token-embedding
                                                       table, for `OnnxDescribeEmbedder`: the pooled output, 768 floats
    altitude/resources/onnx/siglip2_token_embeddings.f16
                                                       That table: 256,000 rows of 768 little-endian float16, row =
                                                       token ID, which `OnnxDescribeEmbedder` maps into memory
    altitude/resources/onnx/siglip2_tokenizer.tsv      The checkpoint's tokenizer for `Siglip2Tokenizer`, a line per piece

The ArcFace net is downloaded from a pinned revision of a Hugging Face repository and checked against its pinned SHA-256;
a mismatch fails the run and writes nothing.

The Scene models take one input named `image`, float32 of shape [1, 3, side, side] with the model's own side (224 for
DINOv2, 256 for SigLIP 2), RGB, scaled to 0..1 and normalized per channel with the model's mean and std (all listed in
MODELS below and in `SceneModel`), and give one output named `embedding`. Opset 17, static batch of 1. Before a file is
written, the export is run through ONNX Runtime on a sample image, fitted to the model's square as the application fits
one, and compared with the PyTorch model to a max absolute difference of 1e-4; a larger difference fails the run and
writes nothing.

The text tower takes one input named `token_embeddings`, float32 of shape [1, 64, 768]: the rows of the token-embedding
table for the 64 token IDs of a description, looked up by the caller. It adds the position embeddings and runs the
encoder, the final layer norm and the head over the last position, as `SiglipTextModel` pools, and gives one output named
`embedding` of shape [1, 768]. The table stays out of the graph because a description reads 64 of its 256,000 rows: as a
float16 file mapped into memory, only the pages around the rows read are resident, and the system can drop them, where
inside an fp32 graph the table would be 790 MB of the process's own memory. The three text files are exported together
and checked together before any is written: the IDs of a few descriptions go to the PyTorch text model, and their rows,
looked up in the float16 table with NumPy, to the export through ONNX Runtime, and the two outputs agree to a max
absolute difference of 1e-3.

The tokenizer file is the checkpoint's tokenizer (`GemmaTokenizer`, a BPE of 256,000 pieces) reduced to what
`Siglip2Tokenizer` needs to give the same IDs, one line per piece in ID order, tab-separated:

    <id> <score> <whole> <piece>

`piece` is the piece's text, with a backslash, a tab, a line feed and a carriage return each written as two characters,
a backslash and then a backslash, `t`, `n` or `r`; the special tokens (`<pad>`, `<eos>`, ...) and the byte-fallback
pieces (`<0x00>`..`<0xFF>`) are among the lines. `whole` is 1 for the pieces the tokenizer matches whole in the text
before anything else (its added tokens: the special tokens, runs of line feeds, a few HTML tags), else 0. `score` orders
the merges: the reference tokenizer holds an ordered list of merges (two pieces whose concatenation is a piece), built
from the SentencePiece scores the checkpoint no longer ships, and it always applies the first merge of the list that
fits. The list is every split of every piece into two pieces, ordered by the merged piece's score, the higher first,
then by the length of the left piece and of the right piece in code points, the longer first, then by the merged piece's
ID. The script gives each piece that a merge builds a score that makes this true (0 for the first rank class, -1 for the
next, ...), rebuilds the list from the scores by that rule, and fails unless it equals the reference's list, merge for
merge. It also fails if the tokenizer is no longer shaped as `Siglip2Tokenizer` assumes (`check_tokenizer_shape`).

`t = exp(logit_scale)` and `b = logit_bias`, the checkpoint's scale and bias of the pair probability
`sigmoid(cosine * t + b)`, are constants of `SceneModel` (`SIGLIP2_LOGIT_SCALE`, `SIGLIP2_LOGIT_BIAS`) and of this script
(`LOGIT_SCALE`, `LOGIT_BIAS`); the text export fails if the checkpoint's differ from them.

Run from the repository root:

    uv run scripts/fetch-models.py [--force] [path/to/sample.jpg]

A model already in place is skipped: the ArcFace net when its SHA-256 matches, a Scene model when its file exists, the
text side when its three files exist. `--force` downloads and exports them all again. The sample defaults to a test
fixture of the repository. The first run downloads the CPU build of torch into uv's cache, and an export its checkpoint
(a few hundred MB) into Hugging Face's cache. The versions pinned above are the newest at the time of writing and work
together; torch comes from the PyTorch CPU wheel index so no CUDA wheels are pulled. Two quirks of this torch's (dynamo)
exporter are handled below: it emits opset 18 and converts down to 17 through the onnx converter, and it can name an
intermediate value like the output, which `rename_colliding_intermediates` undoes before the check.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import urllib.request
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from PIL import Image
from transformers import AutoTokenizer, Dinov2Model, SiglipModel, SiglipVisionModel

REPO_ROOT = Path(__file__).resolve().parent.parent
RESOURCES_DIR = REPO_ROOT / "altitude" / "resources"
OUTPUT_DIR = RESOURCES_DIR / "onnx"
DEFAULT_SAMPLE = REPO_ROOT / "altitude" / "test" / "resources" / "import" / "people" / "bullock.jpg"

OPSET = 17
MAX_ABS_DIFF = 1e-4

DOWNLOAD_CHUNK = 1 << 20


@dataclass(frozen=True)
class DownloadSpec:
    name: str
    url: str
    sha256: str
    size: int
    path: Path


# The revision and the hash pin the exact file the face code and its Settings were tuned with; the hash is the one Hugging
# Face lists for the file at that revision.
ARCFACE = DownloadSpec(
    name="arcface",
    url="https://huggingface.co/maze/faceX/resolve/e010b5098c3685fd00b22dd2aec6f37320e3d850/w600k_r50.onnx",
    sha256="4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43",
    size=174_383_860,
    path=RESOURCES_DIR / "opencv" / "w600k_r50.onnx",
)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        while chunk := file.read(DOWNLOAD_CHUNK):
            digest.update(chunk)
    return digest.hexdigest()


def download(spec: DownloadSpec, force: bool) -> None:
    print(f"== {spec.name}: {spec.url}")
    if spec.path.is_file() and not force:
        if sha256_of(spec.path) == spec.sha256:
            print(f"   {spec.path.relative_to(REPO_ROOT)} is in place and its SHA-256 matches; skipped")
            return
        print(f"   {spec.path.relative_to(REPO_ROOT)} is in place but its SHA-256 differs; downloading it again")

    spec.path.parent.mkdir(parents=True, exist_ok=True)
    # As with the exports, the download lands beside its final name and is renamed only once its hash matches.
    partial = spec.path.with_suffix(".onnx.part")
    partial.unlink(missing_ok=True)
    digest = hashlib.sha256()
    received = 0
    next_report = 0.1
    try:
        with urllib.request.urlopen(spec.url, timeout=60) as response, partial.open("wb") as out:
            while chunk := response.read(DOWNLOAD_CHUNK):
                out.write(chunk)
                digest.update(chunk)
                received += len(chunk)
                if received >= next_report * spec.size:
                    print(f"   {received / 1_000_000:.0f} of {spec.size / 1_000_000:.0f} MB")
                    next_report += 0.1
    except BaseException:
        partial.unlink(missing_ok=True)
        raise

    if digest.hexdigest() != spec.sha256:
        partial.unlink()
        raise SystemExit(f"{spec.name}: the download's SHA-256 is {digest.hexdigest()}, expected {spec.sha256}")
    partial.replace(spec.path)
    print(f"   wrote {spec.path.relative_to(REPO_ROOT)}: {spec.path.stat().st_size / 1_000_000:.1f} MB")


@dataclass(frozen=True)
class SceneModelSpec:
    name: str
    checkpoint: str
    output_file: str
    # The side of the square the model takes, and how an image is fitted to it: "crop" is the short side scaled to the
    # input side and a center crop, "squash" the whole image scaled to the square with its aspect not kept.
    input_size: int
    fit: str
    mean: tuple[float, float, float]
    std: tuple[float, float, float]
    dimensions: int

    def load(self) -> torch.nn.Module:
        """The checkpoint wrapped so that forward(image) gives the embedding row alone."""
        # Eager attention exports as plain matmul and softmax, which every opset and ONNX Runtime build handles.
        if self.name == "dinov2":
            model = Dinov2Model.from_pretrained(self.checkpoint, attn_implementation="eager")
        else:
            # The fixed-resolution SigLIP 2 checkpoints are of the SigLIP architecture; the vision tower's pooled output
            # is its attention-pooling head's, the image embedding the text tower's is compared with.
            model = SiglipVisionModel.from_pretrained(self.checkpoint, attn_implementation="eager")
        return _Wrapper(model, lambda out: out.pooler_output)


class _Wrapper(torch.nn.Module):
    def __init__(self, model: torch.nn.Module, select):
        super().__init__()
        self.model = model
        self.select = select

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        return self.select(self.model(pixel_values=image))


MODELS = [
    SceneModelSpec(
        name="dinov2",
        checkpoint="facebook/dinov2-base",
        output_file="dinov2_vitb14.onnx",
        input_size=224,
        fit="crop",
        mean=(0.485, 0.456, 0.406),
        std=(0.229, 0.224, 0.225),
        dimensions=768,
    ),
    SceneModelSpec(
        name="siglip2",
        checkpoint="google/siglip2-base-patch16-256",
        output_file="siglip2_b16_256_image.onnx",
        input_size=256,
        fit="squash",
        mean=(0.5, 0.5, 0.5),
        std=(0.5, 0.5, 0.5),
        dimensions=768,
    ),
]


def preprocess(sample: Path, spec: SceneModelSpec) -> np.ndarray:
    """The sample as the model's input: fitted to the model's square by its fit, RGB, normalized, NCHW float32.

    This is the preprocessing `OnnxSceneEmbedder` does, so the check runs the models on an input shaped as the application
    shapes one; the resize filter differs (Pillow here, OpenCV there), which the check tolerates since both sides of it
    see the same tensor.
    """
    side = spec.input_size
    image = Image.open(sample).convert("RGB")
    if spec.fit == "squash":
        image = image.resize((side, side), Image.Resampling.BOX)
    else:
        scale = side / min(image.size)
        width = max(side, round(image.width * scale))
        height = max(side, round(image.height * scale))
        image = image.resize((width, height), Image.Resampling.BOX)
        left = (width - side) // 2
        top = (height - side) // 2
        image = image.crop((left, top, left + side, top + side))
    pixels = np.asarray(image, dtype=np.float32) / 255.0
    pixels = (pixels - np.array(spec.mean, dtype=np.float32)) / np.array(spec.std, dtype=np.float32)
    return np.ascontiguousarray(pixels.transpose(2, 0, 1))[np.newaxis, ...]


def rename_colliding_intermediates(path: Path, output_name: str) -> None:
    """Renames the intermediate values the exporter named like the graph's output.

    The dynamo exporter names a value after the module that produces it, and SigLIP 2's `position_embedding` gives one
    called `embedding`, which is also the output's name; the ONNX checker refuses a graph that assigns a name twice. The
    nodes are in topological order, so the last producer of the name is the one the exporter attached the output to: that
    one output keeps the name, and every other mention of it, in any node's inputs (a consumer may sit after the last
    producer) and in the other producers' outputs, means the intermediate and is renamed. Afterwards the name is asserted
    to be mentioned by that one output alone.
    """
    model = onnx.load(str(path))
    producers = [node for node in model.graph.node if output_name in node.output]
    if len(producers) <= 1:
        return
    last = producers[-1]
    renamed = f"{output_name}_intermediate"
    for node in model.graph.node:
        node.input[:] = [renamed if name == output_name else name for name in node.input]
        if node is not last:
            node.output[:] = [renamed if name == output_name else name for name in node.output]
    for info in model.graph.value_info:
        if info.name == output_name:
            info.name = renamed
    mentions = [
        f"{node.op_type} {node.name}"
        for node in model.graph.node
        if output_name in node.input or (node is not last and output_name in node.output)
    ]
    mentions += [f"value_info {info.name}" for info in model.graph.value_info if info.name == output_name]
    if mentions:
        raise SystemExit(f"{path.name}: {output_name} is still mentioned beyond the output itself by {', '.join(mentions)}")
    print(f"   renamed an intermediate value called {output_name} to {renamed}")
    onnx.save(model, str(path))


def export(spec: SceneModelSpec, sample: Path, force: bool) -> None:
    print(f"== {spec.name}: {spec.checkpoint}")
    final_path = OUTPUT_DIR / spec.output_file
    if final_path.is_file() and not force:
        print(f"   {final_path.relative_to(REPO_ROOT)} is in place; skipped")
        return
    model = spec.load().eval()
    image = torch.from_numpy(preprocess(sample, spec))

    with torch.no_grad():
        expected = model(image).numpy()
    if expected.shape != (1, spec.dimensions):
        raise SystemExit(f"{spec.name}: PyTorch output has shape {expected.shape}, expected (1, {spec.dimensions})")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    # The export lands beside its final name and is renamed only once it passes the check, so a failed run leaves no file
    # the application would load.
    candidate = final_path.with_suffix(".onnx.unchecked")
    candidate.unlink(missing_ok=True)

    torch.onnx.export(
        model,
        (image,),
        str(candidate),
        input_names=["image"],
        output_names=["embedding"],
        opset_version=OPSET,
        dynamo=True,
        external_data=False,
    )
    rename_colliding_intermediates(candidate, "embedding")
    onnx.checker.check_model(str(candidate))

    session = ort.InferenceSession(str(candidate), providers=["CPUExecutionProvider"])
    (actual,) = session.run(["embedding"], {"image": image.numpy()})
    if actual.shape != expected.shape:
        candidate.unlink()
        raise SystemExit(f"{spec.name}: ONNX output has shape {actual.shape}, expected {expected.shape}")
    max_diff = float(np.max(np.abs(actual - expected)))
    print(f"   max |onnx - torch| on {sample.name}: {max_diff:.3g}")
    if not np.isfinite(max_diff) or max_diff > MAX_ABS_DIFF:
        candidate.unlink()
        raise SystemExit(f"{spec.name}: the ONNX export differs from PyTorch by {max_diff:.3g}, over {MAX_ABS_DIFF}")

    candidate.replace(final_path)
    print(f"   wrote {final_path.relative_to(REPO_ROOT)}: {final_path.stat().st_size / 1_000_000:.1f} MB")


TEXT_CHECKPOINT = "google/siglip2-base-patch16-256"
TEXT_TOWER_FILE = "siglip2_b16_256_text.onnx"
TOKEN_EMBEDDINGS_FILE = "siglip2_token_embeddings.f16"
TOKENIZER_FILE = "siglip2_tokenizer.tsv"

# What a description is tokenized to, and the table's shape; `Siglip2Tokenizer` and `OnnxDescribeEmbedder` hold the same.
MAX_TOKENS = 64
VOCABULARY_SIZE = 256_000
TEXT_DIMENSIONS = 768
TEXT_MAX_ABS_DIFF = 1e-3

# t = exp(logit_scale) and b = logit_bias of the checkpoint, as `SceneModel` holds them.
LOGIT_SCALE = 112.90117645263672
LOGIT_BIAS = -16.77180290222168
LOGIT_TOLERANCE = 1e-4

# The descriptions the text export is checked on, lowercase as the application lowercases one. The empty one is the EOS
# and 63 pads, the input most unlike the others.
CHECK_DESCRIPTIONS = ["a woman in a park", "a red car parked on a street at night", "two dogs running on the beach", ""]

BYTE_PIECES = [f"<0x{byte:02X}>" for byte in range(256)]


class _TextTower(torch.nn.Module):
    """The text tower from the looked-up token embeddings on: forward(token_embeddings) gives the pooled row."""

    def __init__(self, text_model: torch.nn.Module):
        super().__init__()
        self.embeddings = text_model.embeddings
        self.encoder = text_model.encoder
        self.final_layer_norm = text_model.final_layer_norm
        self.head = text_model.head

    def forward(self, token_embeddings: torch.Tensor) -> torch.Tensor:
        hidden = self.embeddings(inputs_embeds=token_embeddings)
        hidden = self.final_layer_norm(self.encoder(inputs_embeds=hidden).last_hidden_state)
        # SigLIP pools the last position, which is a pad for every description under 64 tokens.
        return self.head(hidden[:, -1, :])


def check_tokenizer_shape(tokenizer: dict) -> None:
    """Fails unless the reference tokenizer is shaped as `Siglip2Tokenizer` assumes.

    The Scala tokenizer does these steps and no others: the added tokens matched whole in the text as written, every
    space of the rest replaced with U+2581, a byte-fallback BPE over each stretch, one <eos> appended, cut to 64 with
    the <eos> kept, padded with <pad>.
    """
    model = tokenizer["model"]
    expected = {
        "normalizer": {"type": "Replace", "pattern": {"String": " "}, "content": "\u2581"},
        # The split is on a space, and none is left after the normalizer: a stretch between added tokens is one word.
        "pre_tokenizer": {"type": "Split", "pattern": {"String": " "}, "behavior": "MergedWithPrevious", "invert": False},
        "model type": "BPE",
        "model options": (None, None, None, True, False),
    }
    actual = {
        "normalizer": tokenizer["normalizer"],
        "pre_tokenizer": tokenizer["pre_tokenizer"],
        "model type": model["type"],
        "model options": (
            model["dropout"],
            model["continuing_subword_prefix"],
            model["end_of_word_suffix"],
            model["byte_fallback"],
            model["ignore_merges"],
        ),
    }
    for name, value in expected.items():
        if actual[name] != value:
            raise SystemExit(f"tokenizer: {name} is {actual[name]}, expected {value}")

    single = tokenizer["post_processor"]["single"]
    if tokenizer["post_processor"]["type"] != "TemplateProcessing" or single != [
        {"Sequence": {"id": "A", "type_id": 0}},
        {"SpecialToken": {"id": "<eos>", "type_id": 0}},
    ]:
        raise SystemExit(f"tokenizer: the post-processor is not one <eos> after the text: {single}")

    vocabulary = model["vocab"]
    if sorted(vocabulary.values()) != list(range(VOCABULARY_SIZE)):
        raise SystemExit(f"tokenizer: the IDs are not 0..{VOCABULARY_SIZE - 1}")
    if (vocabulary.get("<pad>"), vocabulary.get("<eos>")) != (0, 1):
        raise SystemExit("tokenizer: <pad> and <eos> are not the IDs 0 and 1")

    for token in tokenizer["added_tokens"]:
        # Matched in the text as written, anywhere, with nothing stripped around it: the one way `Siglip2Tokenizer` matches.
        if token["normalized"] or token["single_word"] or token["lstrip"] or token["rstrip"]:
            raise SystemExit(f"tokenizer: the added token {token['content']!r} is not matched plainly: {token}")
        if vocabulary.get(token["content"]) != token["id"]:
            raise SystemExit(f"tokenizer: the added token {token['content']!r} is not the piece of its ID")

    # A character that is no piece becomes its UTF-8 bytes' pieces, and the unknown token if a byte has none, which
    # `Siglip2Tokenizer` does not implement. The bytes of a character of several are 0x80 and up; a lone byte is the
    # ASCII character itself, so a byte piece may be missing only where that character is a piece.
    for byte, piece in enumerate(BYTE_PIECES):
        if piece not in vocabulary and (byte >= 0x80 or chr(byte) not in vocabulary):
            raise SystemExit(f"tokenizer: no piece for the byte {piece}, so the unknown token is reachable")


def merge_scores(vocabulary: dict[str, int], merges: list[tuple[str, str]]) -> dict[str, int]:
    """A score per piece that a merge builds, from which the reference's ordered merge list can be rebuilt.

    The list is walked in order with the rule's key, (left length, right length) falling and then the ID rising; where
    the key steps backwards a lower score begins. The result is then proven: the list rebuilt from the scores must be
    the reference's, or the run fails.
    """
    scores: dict[str, int] = {}
    score = 0
    previous = None
    for left, right in merges:
        piece = left + right
        key = (-len(left), -len(right), vocabulary[piece])
        if previous is not None and key < previous:
            score -= 1
        if scores.setdefault(piece, score) != score:
            raise SystemExit(f"tokenizer: the merges of {piece!r} are not together in the merge list")
        previous = key

    splits = [
        (piece[:at], piece[at:])
        for piece in vocabulary
        for at in range(1, len(piece))
        if piece[:at] in vocabulary and piece[at:] in vocabulary
    ]
    if any(left + right not in scores for left, right in splits):
        raise SystemExit("tokenizer: a split of a piece into two pieces is not in the merge list")
    splits.sort(key=lambda pair: (-scores[pair[0] + pair[1]], -len(pair[0]), -len(pair[1]), vocabulary[pair[0] + pair[1]]))
    if splits != merges:
        raise SystemExit("tokenizer: the merge list rebuilt from the scores is not the reference's")
    return scores


def write_tokenizer(reference, path: Path) -> None:
    """Writes the tokenizer file from the checkpoint's tokenizer, after the checks its format rests on."""
    tokenizer = json.loads(reference.backend_tokenizer.to_str())
    check_tokenizer_shape(tokenizer)
    vocabulary: dict[str, int] = tokenizer["model"]["vocab"]
    merges = [(left, right) for left, right in tokenizer["model"]["merges"]]
    scores = merge_scores(vocabulary, merges)
    whole = {token["content"] for token in tokenizer["added_tokens"]}
    escapes = str.maketrans({"\\": "\\\\", "\t": "\\t", "\n": "\\n", "\r": "\\r"})
    with path.open("w", encoding="utf-8", newline="\n") as out:
        for piece, piece_id in sorted(vocabulary.items(), key=lambda item: item[1]):
            out.write(f"{piece_id}\t{scores.get(piece, 0)}\t{int(piece in whole)}\t{piece.translate(escapes)}\n")
    print(f"   tokenizer: {len(vocabulary)} pieces, {len(merges)} merges rebuilt from {len(scores)} scores, {len(whole)} whole")


def export_text(force: bool) -> None:
    print(f"== siglip2 text: {TEXT_CHECKPOINT}")
    finals = [OUTPUT_DIR / name for name in (TEXT_TOWER_FILE, TOKEN_EMBEDDINGS_FILE, TOKENIZER_FILE)]
    if all(path.is_file() for path in finals) and not force:
        print(f"   {', '.join(path.name for path in finals)} are in place; skipped")
        return

    model = SiglipModel.from_pretrained(TEXT_CHECKPOINT, attn_implementation="eager").eval()
    scale = float(model.logit_scale.exp().item())
    bias = float(model.logit_bias.item())
    print(f"   t = exp(logit_scale) = {scale!r}, b = logit_bias = {bias!r}")
    if abs(scale - LOGIT_SCALE) > LOGIT_TOLERANCE or abs(bias - LOGIT_BIAS) > LOGIT_TOLERANCE:
        raise SystemExit(f"siglip2 text: the checkpoint's t and b are not {LOGIT_SCALE} and {LOGIT_BIAS}, as SceneModel holds")

    text_model = model.text_model
    table = text_model.embeddings.token_embedding.weight.detach().numpy()
    if table.shape != (VOCABULARY_SIZE, TEXT_DIMENSIONS):
        raise SystemExit(f"siglip2 text: the token-embedding table has shape {table.shape}")
    halves = table.astype("<f2")
    if not np.isfinite(halves).all():
        raise SystemExit("siglip2 text: a token embedding does not fit a float16")

    tokenizer = AutoTokenizer.from_pretrained(TEXT_CHECKPOINT)
    token_ids = tokenizer(
        CHECK_DESCRIPTIONS, padding="max_length", max_length=MAX_TOKENS, truncation=True, return_tensors="pt"
    )["input_ids"]
    with torch.no_grad():
        expected = text_model(input_ids=token_ids).pooler_output.numpy()

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    # As with the Scene models, each file lands beside its final name and is renamed only once the check has passed.
    candidates = [path.with_name(path.name + ".unchecked") for path in finals]
    tower_candidate, table_candidate, tokenizer_candidate = candidates
    for candidate in candidates:
        candidate.unlink(missing_ok=True)
    try:
        write_tokenizer(tokenizer, tokenizer_candidate)
        halves.tofile(table_candidate)

        tower = _TextTower(text_model).eval()
        # Out of the module before the export, so the graph cannot hold the table.
        tower.embeddings.token_embedding = None
        torch.onnx.export(
            tower,
            (torch.zeros(1, MAX_TOKENS, TEXT_DIMENSIONS),),
            str(tower_candidate),
            input_names=["token_embeddings"],
            output_names=["embedding"],
            opset_version=OPSET,
            dynamo=True,
            external_data=False,
        )
        rename_colliding_intermediates(tower_candidate, "embedding")
        onnx.checker.check_model(str(tower_candidate))

        # The check reads the table as the application does: the float16 file, a row per token ID.
        rows = np.memmap(table_candidate, dtype="<f2", mode="r", shape=(VOCABULARY_SIZE, TEXT_DIMENSIONS))
        session = ort.InferenceSession(str(tower_candidate), providers=["CPUExecutionProvider"])
        max_diff = 0.0
        for ids, row in zip(token_ids.numpy(), expected):
            (actual,) = session.run(["embedding"], {"token_embeddings": rows[ids].astype(np.float32)[np.newaxis, ...]})
            if actual.shape != (1, TEXT_DIMENSIONS):
                raise SystemExit(f"siglip2 text: ONNX output has shape {actual.shape}, expected (1, {TEXT_DIMENSIONS})")
            max_diff = max(max_diff, float(np.max(np.abs(actual[0] - row))))
        del rows
        print(f"   max |onnx - torch| on {len(CHECK_DESCRIPTIONS)} descriptions: {max_diff:.3g}")
        if not np.isfinite(max_diff) or max_diff > TEXT_MAX_ABS_DIFF:
            raise SystemExit(f"siglip2 text: the export differs from PyTorch by {max_diff:.3g}, over {TEXT_MAX_ABS_DIFF}")
    except BaseException:
        for candidate in candidates:
            candidate.unlink(missing_ok=True)
        raise

    for candidate, final_path in zip(candidates, finals):
        candidate.replace(final_path)
        print(f"   wrote {final_path.relative_to(REPO_ROOT)}: {final_path.stat().st_size / 1_000_000:.1f} MB")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Fetches the ArcFace net and exports the two Scene models and SigLIP 2's text side."
    )
    parser.add_argument("--force", action="store_true", help="download and export every model again")
    parser.add_argument("sample", nargs="?", type=Path, default=DEFAULT_SAMPLE, help="the image the exports are checked on")
    args = parser.parse_args()
    if not args.sample.is_file():
        raise SystemExit(f"No sample image at {args.sample}")
    download(ARCFACE, args.force)
    print(f"torch {torch.__version__}, onnx {onnx.__version__}, onnxruntime {ort.__version__}; sample {args.sample}")
    for spec in MODELS:
        export(spec, args.sample, args.force)
    export_text(args.force)


if __name__ == "__main__":
    main()
