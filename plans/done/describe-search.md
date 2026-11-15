# Describe search

## Goals

- One vision-language model serves the Sequence rule and the search by a description: **SigLIP 2 ViT-B/16 at
  256 px** (`google/siglip2-base-patch16-256`, Apache 2.0) is a Scene model in CLIP ViT-B/16's place.
- The Sequences are no worse than with CLIP, shown on a public near-duplicate dataset.
- A description, the words that say what a photo shows ("woman in a park"), is embedded in the JVM with no new
  native library: our own tokenizer, ONNX Runtime for the text tower.
- A Describe search ranks the still images of a repository by how much they look like a description, best first, on
  both engines, sized for a library of a million assets on PostgreSQL and of 100,000 on SQLite.

Out of scope: sorts other than similarity, the map layout, Videos and animated images (they carry no Scene
embedding), similar-image search ("more like this"), an aesthetic score, multilingual UI copy (the model reads other
languages; nothing is tuned for them).

## How it works

**The model.** `SceneModel.Siglip2`: name `siglip2`, 768 dimensions, column `asset.siglip2_embedding`, resource
`/onnx/siglip2_b16_256_image.onnx`, mean and std 0.5. The image embedding is the vision tower's pooled output,
L2-normalized. Each Scene model carries its input side and its fit (`SceneFit`): DINOv2 fits the short side to 224
and crops the center; SigLIP 2 squashes the whole image to 256 × 256, as its checkpoint's processor does. The two
preparations share the decode; each resizes for itself (`INTER_AREA` when neither side is enlarged, `INTER_CUBIC`
otherwise). Both models are 768 floats, so the per-model field of `SceneEmbeddings`, not the size, keeps them apart.
`SceneModel.SIGLIP2_LOGIT_SCALE` (112.90) and `SIGLIP2_LOGIT_BIAS` (−16.77) are the checkpoint's `t` and `b`.

**The Sequence rule.** Two frames look alike when DINOv2's cosine is at least `sequence.dinov2.min_similarity`
(0.80) or SigLIP 2's at least `sequence.siglip2.min_similarity` (0.90).

**The model files** are fetched, not in git. `scripts/fetch-models.py` exports from the checkpoint, checking each
against PyTorch before writing it: the image tower; the text tower without its token embeddings
(`siglip2_b16_256_text.onnx`, input `token_embeddings` [1, 64, 768], output `embedding` [1, 768]); the
token-embedding table (`siglip2_token_embeddings.f16`, 256,000 × 768 little-endian float16); and the tokenizer file
(`siglip2_tokenizer.tsv`: ID, score, whether the piece is matched whole, the piece).

**The tokenizer.** `Siglip2Tokenizer` turns a description into exactly 64 token IDs, as the checkpoint's processor
(`SiglipProcessor` over a `GemmaTokenizer`) does after lowercasing: lowercase by Unicode's default conversion, the
final sigma included; the reference's added tokens taken out whole; spaces to `▁`; code points with no piece as
their UTF-8 byte pieces; BPE merges in the reference's order, which the tokenizer file's scores reproduce; no BOS, an
EOS (ID 1) after at most 63 tokens, padding with ID 0. `scripts/siglip2-golden.py` writes the golden files it is
held to.

**A description's embedding.** `DescribeEmbedder` (`OnnxDescribeEmbedder`): the description is normalized (white
space collapsed, lowercased), tokenized, its 64 rows looked up in the memory-mapped table and widened from float16,
the text tower run, the output L2-normalized, a `SceneEmbedding` of SigLIP 2. The tower opens on the first
description, once, and stays until `Altitude` closes it; an import never loads it. The 256 descriptions used last are
cached by the normalized text, the very text that is embedded. Test instances get a switchable embedder that embeds
nothing unless a suite opts in.

**Storage.** `siglip2_embedding` is `vector(768)` on PostgreSQL and a float32 BLOB on SQLite. PostgreSQL has
`asset_describe`, an HNSW index over `siglip2_embedding::halfvec(768)` by cosine; SQLite registers the column with
sqlite-vector on each connection.

**The search.** `describe` on the results route is the search's description, 1 to 300 characters, in the
repository view, laid out on the grid, ordered by similarity alone. `SearchService.describe` embeds it, turns
`search.describe.min_probability` into a cosine (`sigmoid(cosine · t + b)` inverted) and reads
`SearchDao.describe(query, ranking)` inside `TransactionManager.withVectorSearch(limit)`: the assets the query
matches, within the cutoff, nearest first then by ID, at most `search.describe.max_results`, in flat pages with a
`(distance, id)` cursor and an exact total on the first page. SQLite ranks by sqlite-vector's exact scan.
PostgreSQL ranks through `asset_describe` with pgvector's iterative scan, its scan limits lifted so that the limit's
rows are found past the rows the filters drop, or exactly, without the index, when few assets are to be ranked.

**Settings.** A **Search** group, both Live: `search.describe.min_probability` (0.00009, a decimal between 0 and 1,
both excluded) and `search.describe.max_results` (1,000, at most `Const.Search.TOTAL_CAP`). A Decimal Setting is
written as a plain decimal, with no exponent.

## Decisions

| Decision | Choice |
|---|---|
| Model | SigLIP 2 B/16-256 in CLIP B/16's place, for the Sequence rule and for a description. |
| Proof for Sequences | Pair scores on a public near-duplicate dataset, the CLIP rule against the new one; never dev data. |
| SigLIP 2 Sequence threshold | 0.90: above the CLIP rule on precision, recall and F1. |
| Tokenizer | Our own, in Scala, checked token for token against the reference. |
| Token-embedding table | Outside the ONNX graph, float16, memory-mapped. |
| Ranking | Similarity only. |
| Where results end | A minimum match probability and a cap on the count, both Settings. |
| Match probability default | 0.00009: the probability that keeps 90% of true image–phrase pairs of a public captioned set. |
| Schema | `all.sql` of both engines only; the database is recreated. |

## Changes

### A. SigLIP 2 as a Scene model

1. `fetch-models.py` exports the SigLIP 2 image tower in CLIP's place; README's model list.
2. `SceneModel.Siglip2`, `SceneFit`, the per-model preparation in `OnnxSceneEmbedder`.
3. `siglip2_embedding` and `sequence.siglip2.min_similarity` in both `all.sql`, `FieldConst`, `AssetDao`, `AssetRow`,
   `Settings`, `TestSettings`.
4. `SceneEmbedderTests`, `SequenceServiceTests`, `SequenceRuleTests` on the new model.
5. `ImportParallelism.SCENE_MODELS_COST` 1,216 MB.
6. Docs: `CONTEXT.md`, `altitude/AGENTS.md`, `docs/curation-batch-detection.md`, `docs/test-coverage.md`.

### B. The text side

1. `fetch-models.py`: the text tower, the token table, the tokenizer file.
2. `scripts/siglip2-golden.py` with `altitude/test/resources/siglip2/tokens.json` and `embeddings.json`.
3. `Siglip2Tokenizer` with `Siglip2TokenizerTests`.
4. `DescribeEmbedder`, `OnnxDescribeEmbedder`, `OnnxSessions`, the wiring in `Altitude`,
   `SwitchableDescribeEmbedder`, `DescribeEmbedderTests`.

### C. Search backend

1. `asset_describe`, the SQLite `vector_init`, the two seed rows, the Search group of `Settings`, plain decimals.
2. `TransactionManager.withVectorSearch`, which the face search's helpers share.
3. `SearchDao.describe` per engine with `DescribeSearchTests`.
4. `SearchRequestParser`, `Scope.description`, the cursor's fingerprint, `SearchService.describe`, the results route;
   `SearchRequestParserTests`, `SearchResultsControllerTests`.

## Verification

- `make test-unit` (358), `make test-controllers` (331), `make test-sqlite` (806, one cancelled as PostgreSQL-only)
  and `make test-psql` (810) pass, `make test-js` passes (105), and `make lint` is clean.
- The Sequence rule, on INRIA Holidays (1,491 photos in 500 groups; every pair scored, the pairs of one group the
  positives), through the app's own preparation:

  | Rule | Precision | Recall | F1 |
  |---|---|---|---|
  | `dinov2 ≥ 0.80 OR clip ≥ 0.90` | 0.538 | 0.744 | 0.625 |
  | `dinov2 ≥ 0.80 OR siglip2 ≥ 0.90` | 0.610 | 0.752 | 0.674 |

  One ordering of `SceneEmbedderTests` holds for DINOv2 alone: SigLIP 2 scores another portrait (0.69) about as near
  as a tight crop of the same photo (0.68).
- The tokenizer equals the reference on the 375 golden descriptions; the embedder is within a cosine of 0.9999 of
  PyTorch on the 12 golden ones.
- A new description is embedded in about 91 ms, warm, on one thread; the first takes about 0.8 s.
- Memory: the first description adds about 526 to 578 MB to a server with the Scene models open, and the mapped
  table brings it to about 880 MB as descriptions vary, of which about 360 MB is file cache the system can take back.
  This is above the 500 MB the design aimed for.
- Import throughput on 102 fixture files: 7.0 images a second with CLIP, 6.4 with SigLIP 2.
- A description alone, measured once on scratch databases of random unit vectors, removed afterwards, medians, warm,
  a first page with its total: 205 to 264 ms on PostgreSQL over 1,000,000 embedded assets, 167 ms on SQLite over
  100,000. `search.describe.max_results` at 10,000 takes about 1.4 s a page on PostgreSQL at that size.
- The vector index can miss a row: an asset whose row was rewritten many times can become unreachable in
  `asset_describe`, whatever the scan's settings, until the index is rebuilt. The exact ranking is not affected.
  `docs/test-coverage.md` records it.
