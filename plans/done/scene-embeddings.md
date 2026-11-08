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
- **Sequence**: still images in triage shot as one scene, within a minute of each other on the same camera, looking
  alike by their Scene embeddings, or by their perceptual hashes where either has none.

The code says `SceneEmbedding`, `SceneModel` and `SceneEmbedder`; the UI and the docs say Scene embedding and Scene
model.

## Design

### Models

| Scene model | Export | Input | Output | Weights |
|---|---|---|---|---|
| DINOv2 ViT-B/14 (`facebook/dinov2-base`, Apache 2.0) | `resources/onnx/dinov2_vitb14.onnx` | 1 × 3 × 224 × 224 RGB, ImageNet mean and std | the pooled CLS token, 768 floats | 346.5 MB |
| CLIP ViT-B/16 image tower (`openai/clip-vit-base-patch16`, MIT) | `resources/onnx/clip_vitb16_image.onnx` | 1 × 3 × 224 × 224 RGB, CLIP mean and std | the projected image embedding, 512 floats | 344.8 MB |

The preprocessing is this project's own, not the checkpoints' reference transform (DINOv2's processor resizes the short side
to 256 bicubic and crops 224; CLIP's resizes the short side to 224 bicubic): one pass fits the short side to 224 for both
models, `INTER_AREA` for the downscale an import is and `INTER_CUBIC` where an image under 224 px is enlarged, and
center-crops the square. The similarity thresholds are calibrated to it; the models are not re-exported for it.

`scripts/export-scene-models.py` exports both from the Hugging Face checkpoints: `uv run scripts/export-scene-models.py`,
its dependencies pinned in a PEP 723 header (torch 2.15.0 from the PyTorch CPU index, transformers 5.22.1, onnx 1.23.2,
onnxscript 0.7.2, onnxruntime 1.31.1), opset 17, a static batch of 1, one input named `image` and one output named
`embedding`. Each export is run through ONNX Runtime on a sample image and compared with the PyTorch model to within
1e-4 before it is written. The first run needs the internet, for the CPU build of torch and the checkpoints. The two
files sit under `resources/onnx/`, git-ignored (`/altitude/resources/onnx/*.onnx`) like the ArcFace model, and the
README lists them under the model downloads with the script as the way to produce them. Both licences allow commercial
use; `docs/READINESS.md` records them beside the ArcFace licence item.

### Inference (`core/util/SceneEmbedder`)

`build.mill` has `com.microsoft.onnxruntime:onnxruntime:1.31.1` (the CPU artifact, with natives for Linux x64 and
aarch64, macOS and Windows bundled, so deployment does not change). `SceneEmbedder` holds the process's one
`OrtEnvironment` and one `OrtSession` per Scene model, opened in its constructor in order (a later session failing to open
closes the ones open already before the failure propagates). It lives on `AssetService`
(`private val sceneEmbedder`), built with the service as the ArcFace pool is, so a missing model file fails the start and
never an import (a library imported without embeddings would fall back to the hash silently); `AssetService.close()`,
which `Altitude.cleanup` calls after the pipelines have shut down, closes the sessions (`SceneEmbedder` is `AutoCloseable`;
a second `close()` is a no-op). Each session is opened with one
intra-op and one inter-op thread: the parallelism is the import threads', and twelve concurrent passes each spreading
over every core would thrash. An `OrtSession` is thread-safe, so there is no pool.

`embed(image: Mat, name: String): SceneEmbeddings` takes the upright decoded image `AssetService.measureImage` already
has, prepares it once for both models (fits the short side to 224, `INTER_AREA` shrinking and `INTER_CUBIC` enlarging, center-crops 224 × 224, converts
the gray, BGR or BGRA decode to RGB) and for each model scales by 1/255, subtracts the model's mean and divides by its
std per channel (folded into one multiply and add per channel), lays the result out NCHW as `float[1][3][224][224]`,
runs the session and L2-normalizes the output row. The tensor and the result are closed in `finally`. A `NonFatal`
throw from one model leaves that model's embedding `None` and is logged at WARN with the file name, as the measurement
handles its own failure; the other model's embedding stands. An image that cannot be prepared at all (a channel count
no decode produces) gives neither.

The memory rule (`ImportParallelism`) counts the two sessions in its fixed cost: `SCENE_MODELS_COST` is 1152 MB and
`FIXED_COST = POOL_COST + SCENE_MODELS_COST`, which the automatic rule subtracts from half the memory. Measured on the
machine of the face figures (Ryzen 9 5900, 24 threads, 31 GB, ONNX Runtime 1.31.1, generated 6000 × 4000 JPEGs): the
two sessions hold about 1,050 MB resident once opened (DINOv2 590, CLIP about 455) and about 1,100 MB after passes (691
MB of weights, which the runtime pre-packs for its GEMM kernels and keeps both copies of; `session.disable_prepacking`
would save about 300 MB for passes 8 to 10% slower and is not set). On one thread a pass takes about 365 ms for DINOv2
and 270 ms for CLIP; both models over a 24-megapixel image cost about 0.7 s, of which fitting the image about 50 ms.
With 12 threads embedding at once a pass of both takes about 1.1 s and the process embeds about 10.7 images a second,
the passes' working memory adding about 45 MB a thread, inside `THREAD_COST`'s margin. The figures are in the Scaladoc
of `ImportParallelism` and `SceneEmbedder`. Every imported still image is embedded, so an integration suite bundle
takes about 17 minutes an engine.

### Storage

Two nullable columns on `asset`, in `all.sql` of both engines:

| Column | PostgreSQL | SQLite |
|---|---|---|
| `dinov2_embedding` | `vector(768)` | `BLOB` of 768 float32, raw little-endian |
| `clip_embedding` | `vector(512)` | `BLOB` of 512 float32, the same way |

`BaseDao` has three per-engine members for a vector: `vectorPlaceholder` (`?::vector` on PostgreSQL, `?` on SQLite),
`vectorBind` (the pgvector literal of `PostgresOverrides.vectorLiteral`, moved there from `postgres.FaceDao`, which
still uses it; the raw little-endian float32 bytes on SQLite, bound as bytes and not through `vector_as_f32`, which is
the layout a Face's features are stored in) and `getVectorField` (a `PGobject`'s text on PostgreSQL, a `byte[]` on
SQLite). On SQLite the columns are declared after `public_metadata` and `user_metadata` and before `extracted_metadata`:
a row's columns are stored in order and a read walks the overflow pages of every large value before its column, so the
JSON every general read selects comes first and the raw extracted metadata, the least read, last.
`AssetImportServiceTests` proves the read-back on both engines, float32-exact.

No index: the rule compares a frame with the handful of candidates its capture window holds, in Scala. A vector index
comes with similar-image search, not here. On PostgreSQL the vectors are toasted out of line (`toast_tuple_target`
128), and the Sequence candidates' statement alone selects them: `AssetRow` leaves both columns out, so `getById`,
`queryRecords` and the search statements (whose `assetColumns` are built from `AssetRow`'s labels) never select them, and
`AssetDao`'s hand-written reads build their column lists from the same labels (`assetWithoutBulkyMetadata`: `AssetRow`'s
columns without `user_metadata` and `extracted_metadata`, which `makeModel` reads as empty when absent; `candidateColumns`:
that list and the two embedding columns, for `sequenceCandidatesStatement`). `AssetDao.markAsCompleted`, a hand-written update, writes them with the flag, the five
metrics and the hash in the completion update, refusing an embedding of the wrong dimensions, so an asset is never
complete without them, and they are NULL for a Video, an animated image or a still image a model could not embed.

The models (`models/SceneEmbedding.scala`): `SceneEmbedding(values: IArray[Float])`, equal by its values (`equals` and
`hashCode` over the array; `toString` shows the dimensions and the first values), with `similarity(other)`, the dot
product of two unit vectors, and `SceneModel` (`Dinov2`, `Clip`), each with its `dbValue`, `label` (`DINOv2`, `CLIP`),
`dimensions` (768, 512), `column` (`FieldConst.Asset.DINOV2_EMBEDDING`, `CLIP_EMBEDDING`), `resourcePath`, `mean` and `std`;
`SceneEmbeddings(dinov2: Option[SceneEmbedding], clip: Option[SceneEmbedding])` with `of(model)`;
`Asset.sceneEmbeddings: SceneEmbeddings`. The JSON form of an embedding is its float array; no API answers it today.

### The rule

`SequenceRule.isEligible` is a still image in triage with a camera-clock capture time, a camera model and a hash (an
embedding exists only where the decode succeeded, so the hash is the floor, and the SQL prefilter is unchanged).
`matches` holds when both are eligible, captured at most `sequence.max_gap_ms` apart, on the same device model, at the
same width and height, and **look alike** (`lookAlike`):

- when both frames carry the embedding of the Setting's Scene model, their similarity is at least that model's
  threshold (`sequence.dinov2.min_similarity` or `sequence.clip.min_similarity`);
- otherwise, their hashes are at most `sequence.max_hash_distance` bits apart.

The similarity replaces the hash test rather than joining it, because the hash at any usable distance rejects a
reframed shot. Each model has its own threshold since their cosines live on different scales (CLIP's are compressed
toward 1). The dimension test stays: a portrait and a landscape frame of one scene are not one Sequence.

`assign` reads the Scene model, its threshold, the gap and the hash distance from one Settings snapshot per asset and
logs the model with each assignment. Sequences chain transitively, so a long static session within a minute of itself
is one Sequence; the Curation window handles that.

Observed on `BurstFrames` (`images/2.jpg`, each frame shifted and brightened): the frames measure 0.984 to 0.998 alike
by DINOv2 and 0.969 to 0.996 by CLIP; a frame against the far picture 0.25 to 0.27 and 0.61 to 0.67; a half-size copy
0.986 to 0.996 by DINOv2, so only the size test keeps it out; CLIP is near-invariant to a horizontal mirror (0.99),
DINOv2 is not (0.96).

### Settings

All Live, in the Triage section, in this order, seeded in both `all.sql` and `TestSettings`:

| Key | Default | Type | Accessor |
|---|---|---|---|
| `sequence.max_gap_ms` | 60000 | `Integer(1)` | `sequenceMaxGapMs` |
| `sequence.embedding_model` | `dinov2` | `Choice` of a `ChoiceOption(dbValue, label)` per Scene model | `sequenceEmbeddingModel: SceneModel` |
| `sequence.dinov2.min_similarity` | 0.80 | `Decimal(-1, 1)` | `sequenceDinov2MinSimilarity` |
| `sequence.clip.min_similarity` | 0.90 | `Decimal(-1, 1)` | `sequenceClipMinSimilarity` |
| `sequence.max_hash_distance` | 10 | `Integer(0, 64)` | `sequenceMaxHashDistance` |

`sequenceMinSimilarity(model)` is the chosen model's threshold. `SettingType.Choice(options)`, each a `ChoiceOption` of
the stored value and the label the screen shows: `error` names the labels, "Must be one of DINOv2, CLIP",
`settings_form.scala.html` renders it as a `<select>` of the labels posting the values, `autocomplete="off"`, with the
current option selected and the help's `Default:` by its label (`SettingType.label`), the Reset
button in `settings-page.js` fills a select as it does a text field (`querySelector("input, select")`, the default
being one of the options), `settings_form.css`'s one grid rule keeps an input or a select on the first row beside its
Reset, and `core.css` gives a select the error mark an input has (`form :is(input, select).error`). The help texts say
what each threshold tunes and that the hash distance only decides a frame with no embedding of the chosen model. The
thresholds are starting points to calibrate on real files, like the rest of the section.

### Tests

- `SceneEmbedderTests` (unit, needs the model files as the face tests need ArcFace): each Scene model's embedding of a
  photo has its dimensions and unit norm, and is the same on a second pass; a shifted, brightened copy of a photo is
  closer to it than a flipped copy, a heavy crop, or another photo, which is the least similar of all; a gray and a
  BGRA decode of a picture embed as the BGR one does; an image of an unsupported channel count gives no embedding
  rather than an error. Orderings, never values.
- `CoreModelTests`: Scene embeddings travel through JSON as float arrays, and are absent when unset.
- `ImportParallelismTests`: the automatic rule subtracts the fixed cost.
- `SettingsServiceTests`: the Sequence Settings open the Triage section, Live on both engines, in the order of the
  screen; the Scene model is a choice of the models' values, and anything else, a label among it, is refused by naming
  the labels; a similarity
  threshold is a cosine, inclusive of -1 and 1; a saved Scene model and its threshold reach their accessors at once.
- `SettingsControllerTests`: a choice renders as a select of the labels with the current option selected and the default
  by its label, and a saved option is stored and selected; a posted choice that is no option is refused as a validation
  replacement that writes nothing.
- `SequenceRuleTests` (unit): two frames match within the gap, on one camera, at one size, looking alike by the chosen
  Scene model; each Scene model's similarity is compared with its own threshold; the Setting chooses which Scene
  model's embedding is read; the hash decides only when either frame lacks the chosen Scene model's embedding; the
  Settings decide the gap, each model's threshold and the hash distance.
- `SequenceServiceTests` (both engines): frames a minute apart pair, and frames over a minute apart do not; another
  camera, another size, a wide gap or a different picture does not pair (the far picture's similarity asserted under
  each model's default, the half-size copy alike by either and out by size alone); the Settings decide the gap and the
  likeness, and the hash distance does not where the embedding decides; changing the Scene model changes the next
  assignment; the candidates are read with their Scene embeddings, and a search result without them; the candidates'
  plan still names `asset_triage_date_taken`. The no-embedding fallback is the unit suite's.
- `AssetImportServiceTests`: a still image is imported with the Scene embedding of each model, stored with its
  completion; a still image's Scene embeddings are stored with its completion and read back float32-exact; a general
  read of an asset carries no Scene embeddings, and an embedding of the wrong size is refused; a Video completes with
  no Scene embeddings.
- `CurationServiceTests`: its gap numbers are a minute apart, under the new default.
- The export script is run by hand; it has no test in the build.

## Tasks

1. **Dependency and sessions.** `build.mill` has onnxruntime; `SceneEmbedder` opens the environment and the two
   sessions at startup with one thread each and offers `embed(image, name)` with the preprocessing above; the export
   script and the README entry; `.gitignore`. Rationale: the models exist once in the process, and a missing file fails
   the start.
2. **Embedder tests.** `SceneEmbedderTests` as specified. Rationale: the one place the preprocessing is checked against
   what the models were trained on.
3. **Models and storage.** `SceneEmbedding`, `SceneModel`, `SceneEmbeddings`, the `Asset` field, the two columns in
   both `all.sql`, the DAO mapping (`makeModel`, the bulky-columns rule in `AssetRow` and `assetWithoutBulkyMetadata`,
   the candidates statement selecting them, the per-engine `BaseDao` vector members), `markAsCompleted`;
   `AssetImportServiceTests`. Rationale: the embeddings are derived data stored with the metrics.
4. **Measurement.** `AssetService.measureImage` embeds the decoded image after `ImageMeasurement.measure`, over the
   same `Mat`. `ImportParallelism`'s fixed cost and the pass timing measured and written beside the constants.
   Rationale: one decode per image.
5. **Settings.** `SettingType.Choice`, the five Triage keys with their defaults, accessors and help texts, the
   `<select>` in `settings_form.scala.html` with its Reset, grid and error mark, `SettingsServiceTests` and
   `SettingsControllerTests`. Rationale: the model is a Live choice, not a restart.
6. **The rule.** `SequenceRule.matches` reads the chosen embedding and falls back to the hash; `SequenceRuleTests` and
   `SequenceServiceTests`. Rationale: the last task, so every earlier one is tested on its own.
7. **Docs.** `CONTEXT.md` (the three glossary entries), `altitude/AGENTS.md` (**Sequences and Image quality**: the
   embedder, the columns, the rule, the Settings, the tests; the build section's model files; the memory rule; the
   `BaseDao` members; `Choice`), `altitude/views/AGENTS.md` (the `Choice` control), `docs/curation-batch-detection.md`
   (Part 1 §1, §2 and §6; Part 2 §7 says the Curation Settings apply from the next read and the Sequence Settings to
   the next assignment; the research notes' embedding section states what is implemented and keeps SigLIP 2, the
   aesthetic head and similar-image search as options), `docs/READINESS.md` (the licences, the memory figure),
   `docs/test-coverage.md`, `README.md`. Rationale: no drift.
