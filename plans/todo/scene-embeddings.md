# Scene embeddings for Sequences

## Goals

- A Sequence is frames of one scene shot within a minute of each other, not only a burst of a few seconds: the
  default `sequence.max_gap_ms` is 60000.
- Whether two frames look alike is decided by a global image embedding compared by cosine similarity, which holds
  under reframing, zoom and a moving subject, where the perceptual hash does not. The hash remains the test for a frame
  with no embedding.
- Every still image is embedded by two models at import, DINOv2 ViT-B/14 and CLIP ViT-B/16, and a Live Setting picks
  which one the rule compares, so the choice changes with no re-import.
- The models run through ONNX Runtime: one session per model in the process, thread-safe, whatever the import
  parallelism.

Out of scope: similar-image search, text search over the CLIP embedding, an aesthetic score, re-embedding a library
imported before this (a schema change recreates the database), moving ArcFace or YuNet off OpenCV DNN, GPU execution
providers.

## Language

Glossary (`CONTEXT.md`):

- **Scene embedding**: a vector a vision model computes over a whole still image, describing its content and layout.
  Two Scene embeddings of one model compare by cosine similarity, 1 for the same picture; embeddings of different
  models never compare. A frame has one per **Scene model**.
- **Scene model**: one of the vision models the import embeds every still image with, DINOv2 or CLIP. The Setting
  `sequence.embedding_model` names the one the Sequence rule reads.
- **Sequence**: its definition reads "within a minute" where it reads "a few seconds", and "looking alike" is decided
  by the Scene embedding.

The code says `SceneEmbedding`, `SceneModel` and `SceneEmbedder`; the UI and the docs say Scene embedding and Scene
model.

## Design

### Models

| Scene model | Export | Input | Output | Weights |
|---|---|---|---|---|
| DINOv2 ViT-B/14 (`facebook/dinov2-base`, Apache 2.0) | `resources/onnx/dinov2_vitb14.onnx` | 1 × 3 × 224 × 224 RGB, ImageNet mean and std | the pooled CLS token, 768 floats | about 350 MB |
| CLIP ViT-B/16 image tower (`openai/clip-vit-base-patch16`, MIT) | `resources/onnx/clip_vitb16_image.onnx` | 1 × 3 × 224 × 224 RGB, CLIP mean and std | the projected image embedding, 512 floats | about 350 MB |

`scripts/export-scene-models.py` exports both from the Hugging Face checkpoints (pinned `torch`, `transformers` and
`onnx` versions in its header; opset 17; static batch of 1; one output named `embedding`) and checks each export
against the PyTorch model on a sample image to within 1e-4 before writing it. The two files sit beside the ArcFace
model, are in `.gitignore` as it is, and the README lists them under the model downloads with the script as the way to
produce them. Both licences allow commercial use; `docs/READINESS.md` records them beside the ArcFace licence item.

### Inference (`core/util/SceneEmbedder`)

`build.mill` adds `com.microsoft.onnxruntime:onnxruntime` (the CPU artifact, with natives for Linux x64 and aarch64,
macOS and Windows bundled, so deployment does not change). `SceneEmbedder` holds the process's one `OrtEnvironment` and
one `OrtSession` per Scene model, opened when the service is built as the ArcFace pool is, so a missing model file fails
the start and never an import (a library imported without embeddings would fall back to the hash silently). Each session is opened
with one intra-op and one inter-op thread: the parallelism is the import threads', and twelve concurrent passes each
spreading over every core would thrash. An `OrtSession` is thread-safe, so there is no pool.

`embed(image: Mat): SceneEmbeddings` takes the upright decoded image `AssetService.measureImage` already has, and for
each model: fits the short side to 224 (`INTER_AREA`), center-crops 224 × 224, swaps BGR to RGB, scales by 1/255,
subtracts the model's mean and divides by its std per channel, lays the result out NCHW as `float[1][3][224][224]`,
runs the session and L2-normalizes the output row. The tensor and the result are closed in `finally`. A `NonFatal`
throw from one model leaves that model's embedding `None` and is logged at WARN with the file name, as the measurement
handles its own failure; the other model's embedding stands.

The memory rule (`ImportParallelism`) counts the two sessions in its fixed cost: one copy of each model's weights plus
the runtime's arena, measured as the ArcFace pool is and put beside the constant. A forward pass per model on one
thread is measured on the generated 24-megapixel JPEGs the face figures use, and the figure goes in the same comment.

### Storage

Two nullable columns on `asset`, in `all.sql` of both engines:

| Column | PostgreSQL | SQLite |
|---|---|---|
| `dinov2_embedding` | `vector(768)` | `BLOB` of 768 float32, written through `vector_as_f32` as `face.features` is |
| `clip_embedding` | `vector(512)` | `BLOB` of 512 float32, the same way |

On SQLite the blob is read back as raw little-endian float32, which is what `vector_as_f32` stores; `face.features` is
never read back, so this read is new and `AssetImportServiceTests` proves it.

No index: the rule compares a frame with the handful of candidates its capture window holds, in Scala. A vector index
comes with similar-image search, not here. On PostgreSQL the vectors are toasted out of line (`toast_tuple_target`
128), and no general asset read selects them: `assetWithoutBulkyMetadata` reads NULL for both, as it does for the
metadata JSON, and only `sequenceCandidatesStatement` selects them. `markAsCompleted` writes them with the metrics and
the hash in the completion update, so an asset is never complete without them, and they are NULL for a Video, an
animated image or a still image a model could not embed.

The models (`models/SceneEmbedding.scala`): `SceneEmbedding(values: Array[Float])` with `similarity(other)`, the dot
product of two unit vectors, and `SceneModel` (`Dinov2`, `Clip`), each with its `dbValue`, its dimensions and its
column; `SceneEmbeddings(dinov2: Option[SceneEmbedding], clip: Option[SceneEmbedding])` with `of(model)`;
`Asset.sceneEmbeddings: SceneEmbeddings`. The JSON form of an embedding is its float array; no API answers it today.

### The rule

`SequenceRule.isEligible` is unchanged: a still image in triage with a camera-clock capture time, a camera model and a
hash (an embedding exists only where the decode succeeded, so the hash is the floor, and the SQL prefilter stays as it
is). `matches` holds when both are eligible, captured at most `sequence.max_gap_ms` apart, on the same device model, at
the same width and height, and **look alike**:

- when both frames carry the embedding of the Setting's Scene model, their similarity is at least that model's
  threshold (`sequence.dinov2.min_similarity` or `sequence.clip.min_similarity`);
- otherwise, their hashes are at most `sequence.max_hash_distance` bits apart.

The similarity replaces the hash test rather than joining it, because the hash at any usable distance rejects a
reframed shot. Each model has its own threshold since their cosines live on different scales (CLIP's are compressed
toward 1). The dimension test stays: a portrait and a landscape frame of one scene are not one Sequence.

`assign` reads the Scene model, its threshold, the gap and the hash distance from one Settings snapshot per asset.
Sequences chain transitively as before, so a long static session within a minute of itself is one Sequence; the
Curation window handles that.

### Settings

All Live, in the Triage section, in this order:

| Key | Default | Type |
|---|---|---|
| `sequence.max_gap_ms` | 60000 | `Integer(1)` |
| `sequence.embedding_model` | `dinov2` | `Choice(SceneModel.values.map(_.dbValue))` |
| `sequence.dinov2.min_similarity` | 0.80 | `Decimal(-1, 1)` |
| `sequence.clip.min_similarity` | 0.90 | `Decimal(-1, 1)` |
| `sequence.max_hash_distance` | 10 | `Integer(0, 64)` |

`SettingType.Choice(options)` is new: `error` is "Must be one of a, b" and `settings_form.scala.html` renders it as a
`<select>`, the Reset button setting its value as it does a text field's. The help texts say what each threshold
tunes and that the hash distance only decides a frame with no embedding of the chosen model. The thresholds are
starting points to calibrate on real files, like the rest of the section.

### Tests

- `SceneEmbedderTests` (unit, needs the model files as the face tests need ArcFace): each model's embedding of a
  fixture has its dimensions and unit norm; the embedding of a shifted and brightened copy (`BurstFrames`' transform)
  is more similar to the original than another photo's, and more similar than a copy flipped or heavily cropped;
  orderings, never values. A gray decode embeds as the color one does.
- `SettingsServiceTests`: `Choice` accepts its options and refuses anything else; the new defaults and accessors.
- `SequenceRuleTests` (unit): for each Scene model, a similarity on both sides of its threshold in and out; the Setting
  choosing which embedding is read; the hash deciding when either frame lacks the chosen model's embedding, and not
  when both have it (a near hash with a low similarity is no match; a far hash with a high similarity is); the gap
  default; everything existing stands.
- `SequenceServiceTests` (both engines): the synthesized burst pairs by embedding; frames a minute apart pair and
  frames over a minute apart do not; the far picture stays out; changing `sequence.embedding_model` changes the next
  assignment; the candidates' plan still names `asset_triage_date_taken`; the candidates are read with their
  embeddings and a search read is not (the bulky-columns rule).
- `AssetImportServiceTests`: a still image imported with both embeddings stored and read back; a Video with neither.
- `SettingsControllerTests`: the `Choice` rendered as a select with the current value, an invalid posted value refused.
- The export script is run by hand; it has no test in the build.

## Tasks

1. **Dependency and sessions.** `build.mill` gains onnxruntime; `SceneEmbedder` opens the environment and the two
   sessions at startup with one thread each and offers `embed(image)` with the preprocessing above; the export script
   and the README entry; `.gitignore`. Rationale: the models exist once in the process, and a missing file fails the
   start.
2. **Embedder tests.** `SceneEmbedderTests` as specified, red until task 1 is right. Rationale: the one place the
   preprocessing is checked against what the models were trained on.
3. **Models and storage.** `SceneEmbedding`, `SceneModel`, `SceneEmbeddings`, the `Asset` field, the two columns in
   both `all.sql`, the DAO mapping (`makeModel`, the bulky-columns rule in `assetWithoutBulkyMetadata`, the candidates
   statement selecting them, the per-engine write: `?::vector` on PostgreSQL, bytes on SQLite), `markAsCompleted`.
   `AssetImportServiceTests` red first. Rationale: the embeddings are derived data stored with the metrics.
4. **Measurement.** `AssetService.measureImage` embeds the decoded image after `ImageMeasurement.measure`, over the
   same `Mat`. `ImportParallelism`'s fixed cost and the pass timing measured and written beside the constants.
   Rationale: one decode per image.
5. **Settings.** `SettingType.Choice`, the five Triage keys with their defaults, accessors and help texts, the
   `<select>` in `settings_form.scala.html`, `SettingsServiceTests` and `SettingsControllerTests` red first. Rationale: the
   model is a Live choice, not a restart.
6. **The rule.** `SequenceRule.matches` reads the chosen embedding and falls back to the hash; `SequenceRuleTests` and
   `SequenceServiceTests` red first. Rationale: the last task, so every earlier one is tested on its own.
7. **Docs.** `CONTEXT.md` (the three glossary entries), `altitude/AGENTS.md` (**Sequences and Image quality**: the
   embedder, the columns, the rule, the Settings, the tests; the build section's dependency list; the memory rule),
   `altitude/views/AGENTS.md` (the `Choice` control), `docs/curation-batch-detection.md` (Part 1 §1, §2 and §6; the
   research notes' embedding section states what is implemented and keeps SigLIP 2, the aesthetic head and similar-image
   search as options; the line saying a changed threshold applies without re-import names the Curation Settings only,
   since the Sequence Settings apply at assignment), `docs/READINESS.md` (the licences, the memory figure), `README.md`.
   Rationale: no drift.
