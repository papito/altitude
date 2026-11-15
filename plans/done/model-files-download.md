# Model files: downloaded once, kept outside the build

## Goals

- The model files live in the public repository `renegade-otter/altitude-models`, as the assets of a tagged release, not
  in this repository or its build. ArcFace is the exception: it is downloaded from the pinned Hugging Face revision of
  `maze/faceX` that the face code was tuned with, never re-hosted, since its licence is InsightFace's non-commercial
  research licence.
- On start, the instance looks for the files in its models directory and downloads the missing ones, once. A file in
  place is never downloaded again.
- One mechanism for the dev server, the tests, the fat JAR and, later, docker-compose: the directory is a setting, a
  per-user cache by default, and a volume in a container.
- `scripts/fetch-models.py` leaves this repository. Its export part (the Scene models and SigLIP 2's text side, checked
  against PyTorch) becomes the release tooling of `altitude-models`; its ArcFace download becomes the application's.

## Decisions

| Area | Decision |
|---|---|
| Hosting | Release assets of `altitude-models`, at `https://github.com/renegade-otter/altitude-models/releases/download/<tag>/<file>`. Git and Git LFS hold none of them (four are over git's 100 MB limit; LFS bandwidth is metered). |
| The set | The application pins one release tag (`v1`) and, for every file, its URL, its size and its SHA-256, in code (`ModelFile`). The pins in code are the trust anchor; the release's `SHA256SUMS` is for people. A new set is a new tag and new pins. |
| The files | `dinov2_vitb14.onnx`, `siglip2_b16_256_image.onnx`, `siglip2_b16_256_text.onnx`, `siglip2_token_embeddings.f16`, `siglip2_tokenizer.tsv`, `face_detection_yunet_2023mar.onnx` from the release; `w600k_r50.onnx` from `huggingface.co/maze/faceX` at revision `e010b5098c3685fd00b22dd2aec6f37320e3d850`. YuNet leaves git too, so `altitude/resources/` holds no model. `sqlite-vector` stays on the classpath. |
| Directory | `models.dir`. Empty (the default) is the per-user cache: `$XDG_CACHE_HOME/altitude/models`, else `~/.cache/altitude/models`. `ALTITUDE_MODELS_DIR` overrides it, for a container's volume. A leading `~` expands to the home; a relative path is under the application's folder. With neither an absolute `XDG_CACHE_HOME` nor an absolute home (a container user with no passwd entry), the start fails asking for `models.dir`. The files of a set are in a subdirectory named by its tag (`<models.dir>/v1/`), so two sets never mix; an older set's directory is left alone. |
| One directory, many processes | Dev, every test bundle, every worktree and a second instance share the cache. A download holds a lock file (`<models.dir>/.lock`, `FileChannel.lock`) and, inside one JVM, a process-wide lock on the directory, since a JVM cannot hold two file locks on one file. A process that waits logs it at INFO, and checks again once it has the lock. |
| In place | A file is in place when it exists under its name with its pinned size. The SHA-256 is checked once, on download, before the file gets its name; a start does not read 1.6 GB to hash it. |
| Download | `java.net.http.HttpClient` over HTTP/1.1 (a cancelled body's connection is closed, never pooled for the retry), redirects followed (GitHub and Hugging Face both redirect to a CDN), a generic `User-Agent` (`Altitude`), the JVM's proxy settings. The client is built at the first request and shut down after the downloads, so a start with every file in place builds none. Each file streams to `<file>.part` through a `FileChannel`, hashed as it is written (`Sha256.Digest`), forced to the disk, checked for size and SHA-256 and moved to its name atomically. |
| Bounds | A declared `Content-Length`, or a 206's `Content-Range`, that is not the rest of the pinned size fails before the body is read; a body longer than the pin stops at the pin, its `.part` deleted, and fails. A `.part` larger than its pin is dropped and the file downloads from zero. |
| Resume and retry | A `.part` left by an interrupted start is resumed with a `Range` request (its bytes hashed first); a server that answers 200 instead of 206 restarts it. An I/O error, a 5xx, a 429 or a 408 is retried three times, 2, 4 and 8 seconds apart, or after the `Retry-After` the answer names (seconds or a date, at most 60 seconds), resuming. A hash or size mismatch deletes the `.part` and fails at once; any other status fails at once. |
| Stalls | The client's timeout covers the response headers only, so a watchdog closes a body stream that has delivered no byte for 60 seconds; the stall is then an I/O error, retried and resumed like any other. |
| Start | The download blocks the start: `Altitude` ensures the files after `importParallelism` and before the actor system, so nothing ever runs without its model and a failed download leaves no non-daemon thread to keep the process up. It logs at INFO the files missing with their total size, each file's start, every 10% of it, and its end with its time and rate. |
| Failure | Before downloading, the free space of the directory's file store is checked against the bytes missing; too little fails the start. Every I/O error after the first in-place check (the directory, the lock, the file store, a download) fails the start with a `ModelFilesException` carrying the error as its cause: the error, the root of its cause chain and the host, the files missing with their pinned URLs, sizes and hashes, and the directory to put them in, so an instance with no route to the internet takes them by hand. A redirected failure names the pinned URL and only the host it ended at, never the CDN's signed path. |
| Readers | The readers take paths from `ModelFiles`, never from the classpath: `OnnxSceneEmbedder`, `OnnxDescribeEmbedder` (text tower and token table), `Siglip2Tokenizer` (the vocabulary file), `FaceDetectionService` (YuNet, ArcFace). A fat JAR no longer carries or extracts any model. |
| Tests | The test instances use the same cache, so a machine downloads once for dev and tests alike; the download logs at INFO in test runs (`logback-test.xml`), so a first run's 1.6 GB does not look hung. The unit suites read the files through `TestModelFiles`, over `Altitude.testConfig`. The downloader's own tests run against local HTTP servers (the JDK's `com.sun.net.httpserver.HttpServer`) and temporary directories, never the network, with the waits recorded rather than slept and its loggers off. |

The v1 assets are the files the thresholds are calibrated on, as they are on the dev machine now:

| File | Size (bytes) | SHA-256 |
|---|---|---|
| `dinov2_vitb14.onnx` | 346462331 | `53feca43cf4c943e056b7c3e2fe72e8a25c97be6ab22cc2df5dc7fd0a23c1ebb` |
| `siglip2_b16_256_image.onnx` | 371802977 | `e3c6f8acddca4e08cba87284e2aff36d7e8de0d267871f378dd315385307b7af` |
| `siglip2_b16_256_text.onnx` | 342852627 | `f93c2d65225f7d934e5846db3c7b452707941223101199bef6ed2838f4ab10fd` |
| `siglip2_token_embeddings.f16` | 393216000 | `3b559c1353ed1cfbe37db73d684e9a1ca095586e820704346446a51a69f6ecc4` |
| `siglip2_tokenizer.tsv` | 6166714 | `946f9003e78dc5d947b60bdfa9ed6184895f0ff4a378b4f395c27e49ed1d0871` |
| `face_detection_yunet_2023mar.onnx` | 232589 | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` |
| `w600k_r50.onnx` (Hugging Face) | 174383860 | `4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43` |

## Work

### A. The release in `altitude-models`

1. `export-models.py`: the export of the two Scene models and SigLIP 2's text side, without ArcFace, from
   `facebook/dinov2-base` at `f9e44c814b77203eaa57a6bdbbd535f21ede1415` and `google/siglip2-base-patch16-256` at
   `3f9f96cb90da5dbc758b01813f2f6f1aee24c1ab`, writing to `dist/`, plus YuNet downloaded from OpenCV Zoo at
   `f12e12798e8314f7c074a6656816c048dcc95b7a` and checked against its SHA-256, and a `SHA256SUMS` over the release files.
   It refuses to finish while `dist/` holds any other file. Its PEP 723 header and its PyTorch checks are those of the
   application's export.
2. `README.md`: each file, the checkpoint it comes from, its licence (DINOv2 and SigLIP 2 Apache 2.0, YuNet MIT), the
   revisions and versions of each release (v1's assets were exported before the revisions were pinned, so its
   revisions are not recorded), how a release is made (from an empty `dist/`, `uv run export-models.py`, then
   `gh release create` with a named list of files), and why ArcFace is not there. `LICENSE-APACHE-2.0`,
   `LICENSE-YUNET-MIT`, `NOTICE`; `.gitignore` for `dist/`.
3. Release `v1`: the six files above from the dev machine, with `SHA256SUMS`, the two licence files and `NOTICE`. The
   sizes and hashes of the assets match the table above.

### B. The download (red-green, the downloader's tests first)

1. `util/ModelFiles.scala`: `ModelFile`, the enum of the seven files, each holding a `ModelFileSpec` (name, URL, size,
   SHA-256), the tag and ArcFace's revision inline constants so the cases never read them unset; `ModelFiles`, the
   root, the set's directory, the lock file and `path(file)`; `ModelDownloader`, which ensures files in a `ModelFiles`
   (lock, check, free space, download, resume, retry, verify, move), its client factory, waits and free space given so
   the tests can point it at their servers. `Sha256.Digest`, the incremental hash it and `Sha256` share.
2. `ModelDownloaderTests`: missing files downloaded and verified, and a second ensure sends no request; a file of the
   wrong size downloaded again; a hash mismatch fails and leaves neither the file nor its `.part`; a `.part` resumed with
   `Range`; a 200 answer to `Range` restarting from zero; a redirect followed; a 503 retried then served; two threads
   ensuring at once download each file once; the failure message naming the files, their URLs and the directory.
3. `Const.Conf.MODELS_DIR`; `reference.conf`: `models.dir=""` and `models.dir=${?ALTITUDE_MODELS_DIR}`, with a comment
   on the default and the container case.
4. `Altitude`: `final val models: ModelFiles`, ensured after `importParallelism` and before the actor system;
   `newSceneEmbedder` and `newDescribeEmbedder` take it. The startup order comment updated.
5. The stall watchdog, with a test against a local server that stops sending mid-file.
6. `ModelFilesException` with a cause; `logback-test.xml` at INFO for the download.

### C. The readers off the classpath

1. `SceneModel`: `file: ModelFile` in place of `resourcePath`. `OnnxSceneEmbedder(models)` opens its sessions from it.
2. `OnnxDescribeEmbedder(models, cacheSize)`: the text tower and the token table from it. `Siglip2Tokenizer`: the
   vocabulary read from a path the embedder gives it (`MAX_TOKENS` and `lowercase` stay on the object); the missing-file
   message no longer names `fetch-models.py`.
3. `FaceDetectionService`: YuNet and ArcFace paths from `app.models`.
4. `Environment.resolveResourcePath`: its comment says what it is for now, the SQLite vector extension.
5. Tests: `TestAltitudeApp`, `SwitchableSceneEmbedder`, `SwitchableDescribeEmbedder`, and the unit suites
   `SceneEmbedderTests`, `DescribeEmbedderTests`, `Siglip2TokenizerTests` get the files from one test helper that
   ensures them from the test config.
6. Out of the tree: YuNet `git rm`; the untracked files of `altitude/resources/onnx/` and
   `altitude/resources/opencv/w600k_r50.onnx` moved (not deleted) to `~/.cache/altitude/models/v1/`, which the first
   start then finds in place; the two model lines of `.gitignore` removed; `scripts/fetch-models.py` removed.

### D. The docs

1. `README.md`: the Models section says the first start downloads about 1.6 GB to the cache, how to set `models.dir`
   or `ALTITUDE_MODELS_DIR`, how to place the files by hand, and the docker-compose volume; `uv` is no longer needed to
   run the app.
2. `altitude/AGENTS.md`: the startup order, **Scene embedding**, **Description embedding**, the model files paragraph of
   the tests section, the Key Files table (`ModelFiles.scala` in, `fetch-models.py` out).
3. `docs/faces.md`: the YuNet and ArcFace paths. `scripts/siglip2-golden.py`: its references to `fetch-models.py` name
   the export script of `altitude-models`.
4. `docs/READINESS.md`: the models are no longer packed into the JAR or extracted at every start (the install path item
   stays open for the rest); the Scene models are pinned by SHA-256; ArcFace's licence item keeps its source.
5. `docs/test-coverage.md`: the downloader's coverage; the export script's line moves out with it.
6. `plans/todo/worked-on/desktop-install.md`: its Models paragraph, the `resolveResourcePath` clause and its first
   steps describe the model files as `ModelFile` pins them.

## Verification

- `make test` (all four bundles, against the shared cache), `make lint`.
- With an empty `ALTITUDE_MODELS_DIR`: the dev server downloads the seven files once with progress lines, starts, and a
  restart downloads nothing; an import embeds and detects faces; a Describe search works.
- Kill the server mid-download: the next start resumes the `.part`.
- Two test bundles started against an empty directory: one downloads, the other waits and then finds the files.
- `make publish`: the fat JAR holds no `.onnx`, `.f16` or tokenizer file, and started from a scratch directory with
  `models.dir` set it uses that directory and writes nothing under `/tmp` for models.
