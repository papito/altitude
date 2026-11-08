# ArcFace through ONNX Runtime

## Goals

- The face recognition network exists once in the process and every import thread runs it at once: one ONNX Runtime
  session, thread-safe, with no pool to wait on. Today the one OpenCV `Net` tops out at about 24 embeddings a second
  with 12 threads, and on a 20-face photo a thread waits about 400 ms per face for it.
- The embeddings are the ones OpenCV DNN produces today, so every stored face vector keeps matching.
- `util/NetPool` goes away.

Out of scope: moving YuNet off OpenCV (`FaceDetectorYN` bundles its prior boxes, decoding and NMS), a different or
quantized recognition model (either changes the stored vectors), embedding the faces of a photo in one batched pass (the
model's batch dimension is dynamic, a later option), moving `w600k_r50.onnx` out of `resources/opencv/` (the README's
download step and the git-ignore entry stay), GPU execution providers.

## Language

Nothing new. ONNX Runtime is a library, not a domain concept; the glossary's face terms stand.

## Design

### The session

`util/ArcFaceEmbedder` sits beside `util/SceneEmbedder` and follows its conventions. It takes the process's
`OrtEnvironment` (`OrtEnvironment.getEnvironment`) and opens one `OrtSession` over `resources/opencv/w600k_r50.onnx`
in its constructor, with one intra-op and one inter-op thread: the parallelism is the import threads', and twelve
concurrent passes each spreading over every core would thrash. `FaceDetectionService` holds it as
`private val arcFace`, built with the service, so a missing model file fails the start, as the Scene models' does.

The model has one input, `input.1` (N × 3 × 112 × 112, the batch dimension dynamic), and one output, `683` (1 × 512).
`run(alignedFace: Mat): Array[Float]` builds the input exactly as `getArcFaceEmbedding` builds its blob today: the
aligned 112 × 112 BGR face, channels swapped to RGB, scaled by 1/255 with no mean subtracted, laid out NCHW as one
`FloatBuffer` of shape 1 × 3 × 112 × 112. It returns the raw output row and fails when the row is not
`EMBEDDING_DIMENSIONS` long, as `SceneEmbedder` checks its models' dimensions. The tensor and the result are closed in
`finally` (`OnnxTensor` and `OrtSession.Result` are `AutoCloseable`).

The postprocessing stays in `FaceDetectionService.getArcFaceEmbedding`, which calls `arcFace.run` instead of borrowing
the net: the row L2-normalized, its norm before normalization the face's quality, and a zero norm leaving the vector as
it is, as now. `FaceDetectionService.Embedding` stays where it is, so `util` imports nothing from `service`.

`ArcFaceEmbedder` is `AutoCloseable` with a close that acts once (ONNX Runtime warns on a second close of a session),
and the environment is the process's and stays open. `FaceDetectionService.close()` closes it, and `Altitude.cleanup`
calls it after the pipelines have shut down, beside `service.asset.close()`, since the pipelines' threads run the
session.

### Equivalence and speed before the cutover

While both paths exist, a test in `FaceDetectionTests` (integration, since detection reads the Settings) takes every
face YuNet finds in the `people/` fixtures, aligns it with `alignCropFaceFromDetection`, and embeds it through the
OpenCV net (`getArcFaceEmbedding`) and through `arcFace.run`, normalized in the test. Per face it asserts a cosine
distance under 1e-4 between the two vectors and norms within 1e-3 of each other. The test is deleted with the OpenCV
path; the recognition suites remain the permanent guard, and the concurrency test in `FaceDetectionTests` (eight
threads, twenty embeddings each, every one its own face's vector) becomes the guard of the session's thread-safety.

The cutover also waits on the speed, measured the way the pool was, with generated 24-megapixel JPEGs on 24 cores. One
pass on one thread is compared with OpenCV's 40 to 52 ms, and the import's wall time with 12 threads on photos of three
faces and of twenty is compared with the one-net pool's. The OpenCV net spreads one pass over many cores, so the
session's single-threaded pass is expected to be slower on its own and faster in total once the threads stop queueing
for the net.

### Memory

`ImportParallelism.ARCFACE_NETS` and `POOL_COST` give way to `ARCFACE_SESSION_COST`, and
`FIXED_COST = ARCFACE_SESSION_COST + SCENE_MODELS_COST`. The figure is measured the way `SCENE_MODELS_COST` was
(resident once opened, and after a first pass) and recorded in the class's doc comment beside the Scene models'. ONNX
Runtime pre-packs the weights for its kernels and keeps both copies (the Scene models' 691 MB of weights hold about
1,050 MB resident), so the session may hold close to the 370 MB the OpenCV net holds with Winograd off.
`session.disable_prepacking` stays unset, as it is for the Scene models, and the measurement records what it would
save.

## Tasks

1. **`ArcFaceEmbedder`.** `util/ArcFaceEmbedder` opens the session in its constructor with one intra-op and one
   inter-op thread, offers `run(alignedFace: Mat): Array[Float]` with the OpenCV path's preprocessing, and closes once.
   `FaceDetectionService` builds it as `private val arcFace` and gains `close()`, which `Altitude.cleanup` calls after
   the pipelines shut down. The `build.mill` comment on onnxruntime names ArcFace beside the Scene models. Rationale:
   the new path is built beside the old one, and the pipeline does not use it yet.
2. **Equivalence test.** The `FaceDetectionTests` test over the `people/` fixtures: the OpenCV net against
   `arcFace.run`, cosine distance under 1e-4 and norms within 1e-3 per face. It is red until task 1 is right.
   Rationale: every repository's stored vectors depend on it.
3. **Measurement.** One pass on one thread, the 12-thread import wall time on three-face and twenty-face photos, and
   the session's resident memory, all against the pool's figures. Rationale: the cutover trades a serialized,
   multi-core pass for concurrent single-core ones, and the numbers decide whether the trade holds.
4. **Cutover.** `getArcFaceEmbedding` calls `arcFace.run`. `util/NetPool`, `NetPoolTests` and its entry in
   `AllUnitTestSuites`, the OpenCV ArcFace factory with its Winograd setting, and the equivalence test are deleted.
   `ARCFACE_NETS` and `POOL_COST` become `ARCFACE_SESSION_COST` at task 3's figure, with the measurements in the doc
   comments of `ImportParallelism` and `ArcFaceEmbedder`. The concurrency test's comments speak of the shared session,
   not the pool's nets. Rationale: one copy of the weights, with no pool to size or wait on.
5. **Docs.** `altitude/AGENTS.md`: the architecture line on ONNX Runtime (it runs ArcFace too), the import paragraph's
   fixed cost, the networks paragraph of the face section (YuNet thread-local, ArcFace one shared session, with task
   3's figures in place of the pool's), the Scene embedding paragraph's "as the ArcFace pool is", the model-files note,
   and a row for `util/ArcFaceEmbedder.scala` in the file table. `docs/faces.md`: the parallelism note and step 3,
   Embedding. `docs/READINESS.md`: the memory guidance's fixed cost. `docs/test-coverage.md`: the concurrency entry.
   Rationale: no drift.
