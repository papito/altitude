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
models, exported from their Hugging Face checkpoints to ONNX.

    altitude/resources/opencv/w600k_r50.onnx       ArcFace, InsightFace w600k_r50 (non-commercial research licence), for
                                                   `FaceDetectionService`: a 512-float face embedding
    altitude/resources/onnx/dinov2_vitb14.onnx     DINOv2 ViT-B/14, facebook/dinov2-base (Apache 2.0), for
                                                   `OnnxSceneEmbedder`: the pooled CLS token, 768 floats
    altitude/resources/onnx/clip_vitb16_image.onnx CLIP ViT-B/16 image tower, openai/clip-vit-base-patch16 (MIT), for
                                                   `OnnxSceneEmbedder`: the projected image embedding, 512 floats

The ArcFace net is downloaded from a pinned revision of a Hugging Face repository and checked against its pinned SHA-256;
a mismatch fails the run and writes nothing.

The Scene models take one input named `image`, float32 of shape [1, 3, 224, 224], RGB, scaled to 0..1 and normalized per
channel with the model's mean and std (listed in MODELS below and in `SceneModel`), and give one output named
`embedding`. Opset 17, static batch of 1. Before a file is written, the export is run through ONNX Runtime on a sample
image and compared with the PyTorch model to a max absolute difference of 1e-4; a larger difference fails the run and
writes nothing.

Run from the repository root:

    uv run scripts/fetch-models.py [--force] [path/to/sample.jpg]

A model already in place is skipped: the ArcFace net when its SHA-256 matches, a Scene model when its file exists.
`--force` downloads and exports them all again. The sample defaults to a test fixture of the repository. The first run
downloads the CPU build of torch into uv's cache, and an export its checkpoint (a few hundred MB) into Hugging Face's
cache. The versions pinned above are the newest at the
time of writing and work together; torch comes from the PyTorch CPU wheel index so no CUDA wheels are pulled. Two quirks of
this torch's (dynamo) exporter are handled below: it emits opset 18 and converts down to 17 through the onnx converter,
and it can name an intermediate value like the output, which `rename_colliding_intermediates` undoes before the check.
"""

from __future__ import annotations

import argparse
import hashlib
import urllib.request
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from PIL import Image
from transformers import CLIPVisionModelWithProjection, Dinov2Model

REPO_ROOT = Path(__file__).resolve().parent.parent
RESOURCES_DIR = REPO_ROOT / "altitude" / "resources"
OUTPUT_DIR = RESOURCES_DIR / "onnx"
DEFAULT_SAMPLE = REPO_ROOT / "altitude" / "test" / "resources" / "import" / "people" / "bullock.jpg"

INPUT_SIZE = 224
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
    mean: tuple[float, float, float]
    std: tuple[float, float, float]
    dimensions: int

    def load(self) -> torch.nn.Module:
        """The checkpoint wrapped so that forward(image) gives the embedding row alone."""
        # Eager attention exports as plain matmul and softmax, which every opset and ONNX Runtime build handles.
        if self.name == "dinov2":
            model = Dinov2Model.from_pretrained(self.checkpoint, attn_implementation="eager")
            return _Wrapper(model, lambda out: out.pooler_output)
        model = CLIPVisionModelWithProjection.from_pretrained(self.checkpoint, attn_implementation="eager")
        return _Wrapper(model, lambda out: out.image_embeds)


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
        mean=(0.485, 0.456, 0.406),
        std=(0.229, 0.224, 0.225),
        dimensions=768,
    ),
    SceneModelSpec(
        name="clip",
        checkpoint="openai/clip-vit-base-patch16",
        output_file="clip_vitb16_image.onnx",
        mean=(0.48145466, 0.4578275, 0.40821073),
        std=(0.26862954, 0.26130258, 0.27577711),
        dimensions=512,
    ),
]


def preprocess(sample: Path, spec: SceneModelSpec) -> np.ndarray:
    """The sample as the model's input: short side fitted to 224, center crop, RGB, normalized, NCHW float32.

    This is the preprocessing `OnnxSceneEmbedder` does, so the check runs the models on an input shaped as the application
    shapes one; the resize filter differs (Pillow here, OpenCV there), which the check tolerates since both sides of it
    see the same tensor.
    """
    image = Image.open(sample).convert("RGB")
    scale = INPUT_SIZE / min(image.size)
    width = max(INPUT_SIZE, round(image.width * scale))
    height = max(INPUT_SIZE, round(image.height * scale))
    image = image.resize((width, height), Image.Resampling.BOX)
    left = (width - INPUT_SIZE) // 2
    top = (height - INPUT_SIZE) // 2
    image = image.crop((left, top, left + INPUT_SIZE, top + INPUT_SIZE))
    pixels = np.asarray(image, dtype=np.float32) / 255.0
    pixels = (pixels - np.array(spec.mean, dtype=np.float32)) / np.array(spec.std, dtype=np.float32)
    return np.ascontiguousarray(pixels.transpose(2, 0, 1))[np.newaxis, ...]


def rename_colliding_intermediates(path: Path, output_name: str) -> None:
    """Renames the intermediate values the exporter named like the graph's output.

    The dynamo exporter names a value after the module that produces it, and CLIP's `position_embedding` gives one called
    `embedding`, which is also the output's name; the ONNX checker refuses a graph that assigns a name twice. The nodes are
    in topological order, so the last producer of the name is the one the exporter attached the output to: that one output
    keeps the name, and every other mention of it, in any node's inputs (a consumer may sit after the last producer) and in
    the other producers' outputs, means the intermediate and is renamed. Afterwards the name is asserted to be mentioned by
    that one output alone.
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


def main() -> None:
    parser = argparse.ArgumentParser(description="Fetches the ArcFace net and exports the two Scene models.")
    parser.add_argument("--force", action="store_true", help="download and export every model again")
    parser.add_argument("sample", nargs="?", type=Path, default=DEFAULT_SAMPLE, help="the image the exports are checked on")
    args = parser.parse_args()
    if not args.sample.is_file():
        raise SystemExit(f"No sample image at {args.sample}")
    download(ARCFACE, args.force)
    print(f"torch {torch.__version__}, onnx {onnx.__version__}, onnxruntime {ort.__version__}; sample {args.sample}")
    for spec in MODELS:
        export(spec, args.sample, args.force)


if __name__ == "__main__":
    main()
