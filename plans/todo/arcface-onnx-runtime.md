# ArcFace through ONNX Runtime

## Goals

- The face recognition network exists once in the process, whatever the import parallelism: one ONNX Runtime session,
  thread-safe, shares one copy of the weights across every import thread.
- The embeddings are the ones OpenCV DNN produces today, so every stored face vector keeps matching.
- The ArcFace pool of `plans/import-memory-and-net-pool.md` goes away.

Out of scope: moving YuNet off OpenCV (`FaceDetectorYN` bundles its prior boxes, decoding and NMS), a different
recognition model, GPU execution providers.

## Language

Nothing new. ONNX Runtime is a library, not a domain concept; the glossary's face terms stand.

## Design

`build.mill` adds `com.microsoft.onnxruntime:onnxruntime` (the CPU artifact; its jar bundles the natives for Linux
x64 and aarch64, macOS and Windows, so deployment does not change). `service/ArcFaceSession` (or a util beside
`ImageMeasurement`) holds the one `OrtEnvironment` and one `OrtSession` over `w600k_r50.onnx`, opened lazily on first
use with `setIntraOpNumThreads(1)` and `setInterOpNumThreads(1)`: the parallelism is the import threads', and twelve
concurrent passes each spreading over every core would thrash. A call builds the input tensor exactly as the OpenCV path
builds its blob: the aligned 112 × 112 BGR face, channels swapped to RGB, scaled by 1/255 with no mean subtracted, laid
out NCHW as `float[1][3][112][112]`; the output row is L2-normalized and its norm before normalization is the face's
quality, as `FaceDetectionService.Embedding` holds it today. The tensor and the result are closed in `finally`
(`OnnxTensor` and `OrtSession.Result` are `AutoCloseable`).

Equivalence before the cutover: while both paths exist, a unit test embeds every face the detector finds in the
`people/` fixtures through OpenCV DNN and through the session and asserts a cosine distance under 1e-4 per face, and
norms within 1e-3. It is deleted with the OpenCV path. The recognition suites remain the permanent guard.

The memory rule's fixed cost changes from the pool to the session (one copy of the weights plus the runtime's arena;
measure it as the pool was measured and put the figure beside the constant).

## Tasks

1. **Dependency and session.** `build.mill` gains onnxruntime; `ArcFaceSession` opens the environment and the session
   lazily with one intra-op and one inter-op thread, and offers `embed(alignedFace: Mat): Embedding` with the OpenCV
   path's preprocessing and postprocessing. Rationale: the new path is built beside the old one, unused by the
   pipeline yet.
2. **Equivalence test.** `ArcFaceEquivalenceTests` (unit bundle) over the `people/` fixtures: OpenCV DNN against the
   session, cosine distance under 1e-4 and norms within 1e-3 per face. Red until task 1 is right. Rationale: the
   stored vectors of every repository depend on it.
3. **Cutover.** `getArcFaceEmbedding` calls the session; `NetPool`, the OpenCV ArcFace factory and the equivalence
   test are deleted; `ImportParallelism.POOL_COST` becomes `SESSION_COST`, measured. Rationale: one copy of the
   weights, no pool to size.
4. **Docs.** `altitude/AGENTS.md` (the Settings paragraph on how `FaceDetectionService` reads the nets, the build
   section's dependency list), `docs/faces.md` (the recognition runtime), `docs/READINESS.md` memory guidance,
   `altitude/static/js/lib/README.md` untouched (not a vendored JS library). Rationale: no drift.
