# Describe search

## Goals

- The Advanced search has a **Describe** mode: the user writes what a photo shows ("woman in a park") and gets the
  still images of the Library that look most like it, best first.
- One vision-language model serves both Describe and the Sequence rule: **SigLIP 2 ViT-B/16 at 256 px**
  (`google/siglip2-base-patch16-256`, Apache 2.0) takes CLIP ViT-B/16's place as a Scene model.
- The Sequences are no worse than with CLIP, shown on a public near-duplicate dataset before CLIP is removed.
- The text side runs in the JVM with no new native library: our own tokenizer, ONNX Runtime for the text tower.
- One design on both engines, sized for a library of a million assets on PostgreSQL and of 100,000 on SQLite.

Out of scope: combining a description with any other Search filter or with the Search text, sorts other than
similarity, the map layout, Videos and animated images (they carry no Scene embedding), similar-image search ("more
like this"), an aesthetic score, multilingual UI copy (the model reads other languages; nothing is tuned for them).

## Status

Not started. Task group A ends in a gate: if the Sequence evaluation shows SigLIP 2 grouping worse than CLIP, work
stops there for a decision.

## Confirmed decisions

| Decision | Choice |
|---|---|
| Where it lives | A separate Describe mode of the Advanced search, not mixed into the Search text. |
| Model | SigLIP 2 B/16-256 replaces CLIP B/16 as a Scene model, for Describe and for the Sequence rule. |
| Proof for Sequences | Pair scores on a public near-duplicate dataset, the current rule against the new one; never dev data. |
| Tokenizer | Our own, in Scala, checked token for token against the reference tokenizer. |
| Ranking | Similarity only. |
| Where results end | A minimum match probability (a Setting) and a hard cap on the count (a Setting). |
| Combining | A description stands alone: no other Search filter beside it. |
| Schema | `all.sql` of both engines only; no versioned migration, no schema version bump. The database is recreated. |

Defaults taken, to change on request:

- The text tower's token-embedding table (256,000 × 768) is left out of the ONNX graph and stored as a raw
  little-endian float16 file, memory-mapped; the graph takes the looked-up embeddings. A query touches 64 rows of the
  table, so the 393 MB table costs almost no resident memory, where an fp32 graph holding it would cost about
  790 MB more. The alternative, the whole text tower as one fp32 graph (about 1.1 GB loaded), is simpler.
- The text tower loads on the first Describe search and stays loaded; the import never loads it.
- The cutoff is a probability, the SigLIP pair probability `sigmoid(cosine · t + b)` with the checkpoint's learned
  `t` and `b`, which is on a scale a user can read; it is converted to a cosine for the statement.
- `triaged=true` adds the triaged assets to a description's results, as it does to any search. A description searches
  outside the trash only.
- Results are flat (no grouping), on the grid layout only.
- `search.describe.max_results` defaults to 1,000; `search.describe.min_probability`'s default is chosen in B.5.
- Field label "Describe", placeholder "What's in the photo? e.g. woman in a park".

## Current behavior

- Every still image is embedded at import by two Scene models through ONNX Runtime (`OnnxSceneEmbedder`, one session
  per model, shared by the import threads): DINOv2 ViT-B/14 (768 floats) and the CLIP ViT-B/16 image tower (512
  floats, the projected embedding). `SceneModel` names each model's column, dimensions, ONNX resource, mean and std.
- Both models take one 224 × 224 square, prepared once for both: the short side fitted to 224 (`INTER_AREA` down,
  `INTER_CUBIC` up), a center crop, RGB, scaled to 0..1, normalized per model.
- `../../../scripts/fetch-models.py` exports both from their Hugging Face checkpoints (input `image` [1, 3, 224, 224], output
  `embedding`, opset 17) and checks each against PyTorch to 1e-4 before writing it.
- The embeddings are stored in `asset.dinov2_embedding` and `asset.clip_embedding`: `vector(768)` / `vector(512)` on
  PostgreSQL (moved out of line by `toast_tuple_target = 128`), float32 BLOBs on SQLite. No index covers them.
- The only reader is the Sequence rule (`SequenceRule.lookAlike`): two frames look alike when either model's cosine
  clears its threshold, `sequence.dinov2.min_similarity` 0.80 or `sequence.clip.min_similarity` 0.90, both described
  as starting points to calibrate on real files.
- Face vectors are searched on both engines: a pgvector HNSW index over `features::halfvec(512)` with
  `TransactionManager.withFaceVector` setting `hnsw.ef_search` and `iterative_scan = relaxed_order` (pgvector 0.8.2);
  on SQLite, `vector_init` on each connection and `vector_full_scan` (`FaceDao`).
- `ImportParallelism.SCENE_MODELS_COST` budgets 1,152 MB for the two image sessions; a forward pass is about 365 ms for
  DINOv2 and 270 ms for CLIP on one thread.
- The search routes take the Search text and the Search filters through `SearchRequestParser`; the Relevance sort is
  the only sort that is not an asset column, and it needs Search text.

## Design

### The model

`SceneModel.Clip` becomes `SceneModel.Siglip2`: name `siglip2`, 768 dimensions, column `siglip2_embedding`, resource
`/onnx/siglip2_b16_256_image.onnx`, mean and std 0.5 per channel. `SceneEmbeddings.clip` becomes `siglip2`;
`FieldConst.Asset.CLIP_EMBEDDING` becomes `SIGLIP2_EMBEDDING`.

The image embedding is the vision tower's pooled output (SigLIP's attention-pooling head), L2-normalized, as
`OnnxSceneEmbedder` normalizes every model's.

**Per-model preparation.** `SceneModel` carries its input side and its fit:

| Model | Input | Fit |
|---|---|---|
| DINOv2 | 224 | Short side to 224, center crop (unchanged). |
| SigLIP 2 | 256 | The whole image resized to 256 × 256, aspect not kept, as the checkpoint's processor does. |

The squash keeps the edges of a 3:2 frame in view, where a subject a description names is often found. The two
preparations share the decode and the RGB conversion; each does its own resize. The interpolation is `INTER_AREA` down
and `INTER_CUBIC` up for both, as today.

**Text tower.** Exported as `siglip2_b16_256_text.onnx`: input `token_embeddings` float32 [1, 64, 768], the token
embeddings already looked up; it adds the position embeddings and runs the encoder, the final layer norm and the head
on the last position, as `SiglipTextModel` pools; output `embedding` [1, 768]. The token-embedding table is written
beside it as `siglip2_token_embeddings.f16`: 256,000 rows of 768 little-endian float16, row = token ID. `t = exp(logit_scale)`
and `b = logit_bias` are read from the checkpoint by the script and kept as constants in `SceneModel.Siglip2`, as the
mean and std are.

**Tokenizer files.** `fetch-models.py` writes `siglip2_tokenizer.tsv` from the checkpoint's tokenizer: one line per
piece, `<id>\t<score>\t<piece>`, with the special tokens' IDs and the byte-fallback pieces (`<0x00>`..`<0xFF>`) among
them. The model files and the tokenizer file are fetched, like the other models, and not held by git.

### Tokenizer

`Siglip2Tokenizer` (`altitude/core/util/`) turns a description into exactly 64 token IDs, as the reference
`Siglip2Processor` does with `padding="max_length"`, `max_length=64`, `truncation=True`, after lowercasing (the paper
lowercases; the checkpoints' `tokenizer_config.json` names `GemmaTokenizer`, whose default does not, so the script and
the golden file lowercase explicitly):

1. Lowercase (`Locale.ROOT`), then replace every space with `▁` (U+2581). No dummy prefix, no other normalization,
   as Gemma's SentencePiece model is configured.
2. Split into Unicode code points; a code point with no piece becomes its UTF-8 bytes as `<0xNN>` pieces.
3. SentencePiece BPE: repeatedly merge the adjacent pair whose concatenation is a piece with the highest score, the
   leftmost on a tie, until no pair merges. A priority queue over the pairs keeps it near-linear; a description is
   short either way.
4. The special tokens the reference adds (BOS / EOS, as the golden file shows), truncation to 64, padding with the
   pad ID.

The vocabulary loads once, lazily, into a `HashMap[String, (Int, Float)]`.

Correctness is defined by a golden file, `altitude/test/resources/siglip2/tokens.json`, written by
`scripts/siglip2-golden.py` from the reference processor: about 200 descriptions covering plain words, punctuation,
digits, repeated and leading or trailing spaces, apostrophes, accents, CJK, emoji (byte fallback), mixed case, and
one past 64 tokens.

### Text embedding

`DescribeEmbedder` (trait, with `OnnxDescribeEmbedder`): description → `SceneEmbedding` of `SceneModel.Siglip2`.
Tokenize, look the 64 rows up in the memory-mapped table (a `FloatBuffer` per row, half to float), run the session,
L2-normalize. The session and the mapping open on the first call, under a lock; `Altitude` closes them. Results are
cached by the description's normalized text (lowercased, whitespace collapsed) in an LRU of 256 entries. A test
`Altitude` gets a switchable embedder that embeds nothing unless the suite opts in, as the Scene models do.

The description passed to the model is the text as written. No prompt template: SigLIP 2 is not trained with one, and
the golden check is over raw text.

### Storage and indexes

Both `all.sql`:

- `clip_embedding` is replaced by `siglip2_embedding`: `vector(768)` on PostgreSQL, a float32 BLOB on SQLite, with
  the table comments updated (the Sequence rule and Describe read it).
- PostgreSQL: `CREATE INDEX asset_describe ON asset USING hnsw ((siglip2_embedding::halfvec(768)) halfvec_cosine_ops);`
  Rows with no embedding are not indexed.
- The seed rows `sequence.clip.min_similarity` → `sequence.siglip2.min_similarity` (default from A.6), and
  `search.describe.min_probability`, `search.describe.max_results`.

SQLite: `setUpSqliteConnection` runs `vector_init('asset', 'siglip2_embedding', 'dimension=768,type=FLOAT32,distance=cosine')`
beside the face column's, when the `asset` table exists (`hasFaceTable` becomes a table check by name).

### The statement

A Describe search reads, for the repository, the assets outside the trash (and, without `triaged=true`, outside
triage) whose embedding is within the cutoff, nearest first, at most `max_results`, then pages through them.

- **Cutoff.** `minCosine = (logit(p) − b) / t` for the Setting's probability `p`, so `distance ≤ 1 − minCosine`.
- **PostgreSQL.** The inner select orders by `siglip2_embedding::halfvec(768) <=> ?::halfvec(768)` with
  `LIMIT max_results` and the repository, trash and triage filters (iterative scan, as for faces); the outer one takes
  the exact `vector` distance, applies the cutoff, and orders by distance, then ID. The transaction is set up as
  `withFaceVector` does, its helper generalized to `withVectorSearch` and its `ef_search` raised to at least the
  limit for this statement.
- **SQLite.** `vector_full_scan('asset', 'siglip2_embedding', vector_as_f32(?))` joined to `asset` with the same
  filters, cutoff and order. Exact.
- **Paging.** Flat pages of the grid's size. The cursor is `(distance, id)` of the last cell, with the description
  and `triaged` as its scope fingerprint; a page reruns the ranked statement and takes the rows after the cursor. The
  first page's total is the count of the ranked rows (at most `max_results`, so exact).

The DAO method is `SearchDao.describe(embedding, minCosine, limit, triaged, cursor, pageSize)`, per engine.

### Request and results

- Parameter `describe` on the results route (`SearchResultsController`), parsed by `SearchRequestParser` into
  `Scope.description`. A 400 when it is blank after trimming, longer than 300 characters, or beside any of `q`,
  `folderId`, `personId`, `locationId`, `albumId`, `subset`, a date range, `bbox`, the trash view, a sort, a grouping,
  or the map layout. `triaged=true` is allowed.
- The sort is `similarity`, implied and the only one; the sort menu shows "Similarity" disabled, and the grouping
  menu is disabled.
- Results title: "Search results" over one chip, `Describe: “woman in a park”` with the Describe icon, whose ×
  returns to Browse; **Edit** opens the panel in Describe mode.
- An empty result says "Nothing in this Library looks like that." beside the Edit action.

### The panel

`#searchPanel` gets a two-segment switch at its top, **Filters** | **Describe** (radio group semantics,
`aria-controls` on each). Filters is the panel as it is. Describe shows one multi-line text field (the label
"Describe", up to 300 characters, Enter searches, Shift+Enter is a newline) and the Search and Clear actions. Each mode
keeps its own values while the panel is open; Search runs the visible mode's search only. Opening the panel on a
Describe search opens Describe. The nav's quick search input is untouched by Describe. The `searchParams` store holds
`describe` and drops it when any filter is set, and drops the filters when it is set.

### Settings

In a new **Search** group of the Settings screen, both Live:

| Key | Label | Type | Description |
|---|---|---|---|
| `search.describe.min_probability` | Describe match threshold | Decimal(0, 1, exclusive) | How likely, by the model, a photo must be to match a description to be shown. Lower shows more, weaker matches. |
| `search.describe.max_results` | Describe result limit | Integer(1, 10000) | The most photos a description shows, best first. |

`sequence.siglip2.min_similarity` ("Sequence SigLIP 2 similarity") takes the CLIP setting's place in Triage, its
description on the same lines.

## Tasks

Server tasks are red-green-refactor. Subagents edit disjoint files and never run mill; the parent compiles and tests.

### A. SigLIP 2 as a Scene model, with the Sequence gate

1. `fetch-models.py`: export the SigLIP 2 image tower (input [1, 3, 256, 256]) and drop CLIP's; the PyTorch check
   at 1e-4 on the sample as for the others. README's model list and sizes.
2. `SceneModel`: the `Siglip2` case, per-model input side and fit; `OnnxSceneEmbedder`'s preparation split per model.
   Unit tests: DINOv2's square is unchanged byte for byte; SigLIP 2's is the squash of the whole frame.
3. Schema and Settings rename (`siglip2_embedding`, `sequence.siglip2.min_similarity`), `FieldConst`, `AssetDao`,
   `AssetRow`, `TestSettings`, `Settings` with its description.
4. `SceneEmbedderTests` and `SequenceServiceTests` on the new model: the ordering assertions (burst-like copy above
   the flip, the crop and another photo) must hold unchanged; the strict threshold re-measured for the synthesized
   burst and its comment updated.
5. `ImportParallelism`: re-measure the sessions' memory and one forward pass, update `SCENE_MODELS_COST` and the
   comment.
6. **Gate: Sequence evaluation** (scratch script, removed afterwards with its data). On a public near-duplicate
   collection of personal photos, California-ND (701 photos, annotated near-duplicate pairs) or INRIA Holidays if
   California-ND is unavailable or its terms forbid it: every pair of images of one event or group is scored by
   DINOv2, CLIP and SigLIP 2 through the app's preparation. Report precision, recall and F1 of the likeness test
   (`dinov2 ≥ 0.80 OR clip ≥ 0.90`) against the annotations, then of `dinov2 ≥ 0.80 OR siglip2 ≥ x` across `x`. The
   default `x` is the one whose union F1 is highest at a precision no lower than the CLIP rule's. If no `x` matches the
   CLIP rule's F1 within 0.01, stop and report. The numbers go into
   `../../../docs/curation-batch-detection.md` (Working thresholds) with the dataset named.
7. Docs: `../../../CONTEXT.md` (Scene model, Scene embedding), `altitude/AGENTS.md`, `docs/curation-batch-detection.md`
   (Global image embedding: what is built), `docs/test-coverage.md`.

### B. The text side

1. `fetch-models.py`: export the text tower without its token embeddings, the `.f16` table and the tokenizer TSV;
   the check feeds the same IDs to PyTorch and to ONNX Runtime with the table looked up in NumPy at float16, to
   1e-3.
2. `scripts/siglip2-golden.py`: writes `tokens.json` (description → 64 IDs) and `embeddings.json` (a dozen
   descriptions → their 768 floats from PyTorch).
3. `Siglip2Tokenizer` with its unit test over `tokens.json`: every description's IDs equal.
4. `DescribeEmbedder` and `OnnxDescribeEmbedder`, wired into `Altitude` (lazy, closed with it), the switchable test
   embedder. Unit test: cosine to the golden embedding ≥ 0.9999 for each description; a repeated description is
   served from the cache.
5. Default `search.describe.min_probability`: a scratch check on a public captioned set (COCO 2017 val, short
   captions cut to their noun phrase): the probability that keeps 90% of true image–caption pairs, rounded down to
   one significant figure. Recorded in `../../../docs/curation-batch-detection.md`.

### C. Search backend

1. Schema: the `asset_describe` index, the SQLite `vector_init`, the two seed rows; `Settings` with the Search group.
2. `TransactionManager.withVectorSearch` generalized from the face helper; the face callers unchanged in behavior.
3. `SearchDao.describe` per engine. Integration tests with the Scene models on (fixtures `images/cactus.jpg`, the
   `people/` photos): "a cactus" ranks `cactus.jpg` first; "a man in a suit" ranks a `people/` photo above
   `cactus.jpg`; a recycled asset is never returned; a triaged one only with `triaged=true`; another repository's never;
   the cutoff leaves out an asset below it; the cap holds; a Video is never returned; pages concatenate to the ranked
   list with no repeat or gap.
4. `SearchRequestParser` (`describe`, the 400s), `Scope.description`, the cursor's fingerprint, `SearchService.describe`
   (embed, convert the cutoff, read). Controller tests: each 400; a first page with total; a continuation; a cursor
   of a Search text rejected for a description and the reverse.

### D. Panel, results and docs

1. Panel: the Filters | Describe switch, the field, mode memory, Enter / Shift+Enter, focus and Escape as the panel
   has them; `searchParams` `describe` and its clearing rules; `search_panel.css` tokens reused.
2. Results title chip, Edit into Describe, the disabled sort and grouping menus, the empty state.
3. Browser check on the dev server with a dev login: a description from the nav panel, paging by scroll, Edit, the ×
   chip, the 400 path not reachable from the UI, keyboard only.
4. Docs: `../../../CONTEXT.md` (**Describe search**, **Description**; Advanced search and Search filter entries say a
   description stands alone), `altitude/views/AGENTS.md` if a convention is added, `docs/test-coverage.md` (the panel's
   Describe mode is browser-checked only), `docs/READINESS.md` if the text tower's memory changes an item.

## Verification

- `make test-unit`, `make test-sqlite`, `make test-psql` (drop the Postgres test DB's public schema once for the new
  Settings) and `make test-controllers` green; then `make lint`, and a compile after it.
- A.6's report: the CLIP rule's and the chosen SigLIP 2 rule's precision, recall and F1, and the default threshold.
- B.3 and B.4 golden tests green: every token equal, every embedding at cosine ≥ 0.9999.
- Memory: the server's resident size with the text tower loaded, before and after the first Describe, under 500 MB
  added (the text transformer is about 85M parameters, about 340 MB at fp32).
- Latency, medians, warm cache, first page with its total; scratch databases seeded with random unit vectors, removed
  afterwards:

  | Request | Target |
  |---|---|
  | PostgreSQL, 1M embedded assets, a description | ≤ 300 ms |
  | SQLite, 100k embedded assets, a description | ≤ 1 s |
  | Text embedding of a new description, one thread | ≤ 150 ms |

  If SQLite misses its target, its scan moves to sqlite-vector's quantized scan (`vector_quantize` at import,
  `vector_quantize_scan`) with an exact re-rank of the candidates, as a follow-up task.
- Import throughput on a batch of the test fixtures, before and after A, reported.
