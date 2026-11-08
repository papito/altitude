# Import memory: a shared recognition net and a memory-aware parallelism

## Goals

- An import never takes more native memory than the machine affords: the automatic `import.parallelism` (0) counts the
  machine's memory as well as its cores.
- An operator who ships a config but never logs in can cap the import threads from the server config.
- The face recognition network is shared by the import threads instead of copied into each of them, and takes half
  the memory it does today.
- The Settings screen tells the Admin what is running and why.

Out of scope: moving ArcFace to ONNX Runtime (`plans/arcface-onnx-runtime.md`), bounding the memory of one decode
(READINESS: pixel bombs), a memory budget for the heap.

## Measurements the design rests on

One hundred 24-megapixel JPEGs imported on a 24-core, 31 GB machine (12 import threads), trunk and the
`batch-curation` branch alike: resident memory peaks at 12.8 to 13.4 GB and 11 GB stays after a full GC, with the heap
under 1 GB. The holdings are the thread-local networks, created on a thread's first detection and kept for the life of
the fixed import dispatcher: an ArcFace `Net` holds about 780 MB resident after its first forward pass (174 MB of
weights, about 370 MB with Winograd off), a YuNet `FaceDetectorYN` about 70 MB. The decodes in flight are transient:
a 24-megapixel image is 72 MB decoded, and a thread holds one at a time, since `DetectFacesFlow`, `MeasureImageFlow` and
`AddPreviewFlow` each decode an image and release it inside their work and no decoded image rides on the element. An
image is decoded three times over its import. The FP16 CPU target changes nothing. glibc arena limits change nothing.

Generated 24-megapixel JPEGs imported with 12 threads on 24 cores, Winograd off, one shared net: a forward pass takes
40 to 52 ms and the net tops out at about 24 embeddings a second, twelve nets at about 38. With every thread on a 20-face
photo a borrow waits about 400 ms and such a photo holds its thread and its decoded image for about 9 s. A second net
cuts the wall time about 3% on photos of three faces and 7% on photos of twenty, for roughly 350 MB. Resident memory
peaks at 3.9 to 5.1 GB, the heap included.

## Language

`CONTEXT.md` defines **Setting**, **Live setting** and **Restart setting**. `import.parallelism` stays a Restart
setting. The cap is server configuration (`reference.conf` / `application.conf`), not a Setting: it is the one key an
operator sets on a machine where no Admin tunes anything, and the glossary's "avoid Config" for a Setting stands. The
value the dispatcher is built with is the **running** parallelism, the word the Settings screen already uses for a
Restart setting.

## Design

### The shared ArcFace pool

`FaceDetectionService` keeps one `NetPool` of one ArcFace net (`ImportParallelism.ARCFACE_NETS`) instead of a
`ThreadLocal[Net]`. The net is created on the first borrow (`readNetFromONNX`, then `enableWinograd(false)`), and lives
for the life of the service. `getArcFaceEmbedding` borrows it for its one forward pass and returns it in a `finally`, so
a throw never loses it; a borrower waits while it is out (a semaphore over a queue). Blocking an import thread there is
fine: a forward pass takes 40 to 52 ms, a second net buys little for its memory (see the measurements), and a borrow
never nests another borrow, so the wait cannot deadlock. The pool is a class of its own (`util/NetPool`, generic over
what it holds, taking a factory), unit-tested with a counting factory. YuNet stays thread-local: 70 MB a thread, and its
detector carries per-call state.

Winograd off: `Net.enableWinograd(false)` right after the net is read. It is a convolution algorithm, not a model
change, so the embeddings move at float noise; the recognition suites over the `people/` fixtures are the guard. The
figure in the Setting's help and the docs is the measured one, about 370 MB a net.

### The automatic parallelism

`util/ImportParallelism.resolve(setting, cores, totalMemoryBytes, cap)` is a pure function:

- `setting > 0`: that many threads.
- `setting == 0`: `min(cores / 2, (totalMemory / 2 - POOL_COST) / THREAD_COST)`, never fewer than `MIN_THREADS` (2).
  `POOL_COST` is the one shared net (384 MB); `THREAD_COST` is 512 MB: a YuNet, one decode of a large image and the file
  copy a decode leaves for the finalizer, about 150 MB at 24 megapixels, and a deliberate margin for a larger image and
  for native allocator overhead. Both are code constants beside the measurements that justify them, not Settings.
- then `min(that, cap)` when `cap > 0`. A cap under `MIN_THREADS` is a startup error (`IllegalStateException` from
  `Altitude`), never a silent 2.

`Altitude.importParallelism` calls it with `Runtime.availableProcessors`, the container-aware
`com.sun.management.OperatingSystemMXBean.getTotalMemorySize` and `Const.Conf.IMPORT_PARALLELISM_MAX`
(`import.parallelism.max`, `reference.conf`, 0 = no cap), and logs at INFO the running value and its reason: "12
threads: the Setting", "6 threads: automatic, half of 24 cores or 15 GB of 31 GB", "4 threads: capped by
import.parallelism.max". The result is kept on `Altitude` as `importParallelismResolution` (value and reason), for the
Settings screen.

### The Settings screen

The `import.parallelism` field shows, under the pending line, "Running N threads: <reason>", from
`Altitude.importParallelismResolution` passed by `SettingsFormController` into `settings_form` as a note per Setting
name (a `Map[String, String]`, one entry today). The help text says what a thread costs and what is shared.

## Tasks

1. **`util/NetPool`.** A bounded, lazy, blocking pool generic over its element, with `borrow[A](use: T => A)`;
   unit tests (`NetPoolTests`, unit bundle): creates at most `size` through the factory, hands a free one back before
   making another, returns the element when `use` throws, blocks a third borrower until a return. Rationale: the
   pool is the piece with concurrency to get right, so it is tested alone.
2. **ArcFace through the pool, Winograd off.** `FaceDetectionService` replaces `arcFaceNetLocal` with a `NetPool[Net]`
   of one whose factory reads the model and calls `enableWinograd(false)`; `getArcFaceEmbedding` borrows. The
   recognition suites stay green. Rationale: memory drops from 780 MB a thread to 370 MB in all, before any
   parallelism rule.
3. **`util/ImportParallelism`.** The pure rule with its constants and unit tests (`ImportParallelismTests`): an
   explicit Setting, the core half, the memory half winning on a small box, the floor, the cap, the cap under the
   floor. `Altitude.importParallelism` uses it, with `Const.Conf.IMPORT_PARALLELISM_MAX` and its `reference.conf`
   entry (0, with the comment amended: this one key caps a Setting on purpose), and logs the resolution.
   Rationale: the rule is testable only as a function of its inputs.
4. **The Settings screen's running line and help text.** `SettingsFormController.form` passes the resolution;
   `settings_form` renders it under the field; the `import.parallelism` help text gives the measured costs and names
   the cap. A controller test asserts the line. Rationale: an Admin on a remote box sees why 0 gave 4.
5. **Docs.** `altitude/AGENTS.md` (the startup order line, the import pipeline paragraph, the Settings paragraph:
   `FaceDetectionService` pools ArcFace and keeps YuNet thread-local), `docs/faces.md` line 17, `docs/READINESS.md`
   (item 208 gains the memory guidance: heap plus `POOL_COST` plus `THREAD_COST` per thread; item 147 says three
   decodes per image, one in flight per thread), `reference.conf`'s header comment. Rationale: the Do section's no-drift
   rule.
