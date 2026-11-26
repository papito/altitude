# Altitude DAM #

A self-hosted or desktop-based digital asset manager (DAM) that is meant to be simple, resilient, and fast.

There are plenty of media managers in existence at this point, but they often require considerable technical
skills to just get up and running. Altitude is meant to be easy-to-use and easy-to-deploy.

Altitude is designed to be hands-off and require little maintenance.

The **only** thing needed is Docker on the target system, and Altitude will take care
of the rest: see [README-DOCKER.md](README-DOCKER.md). Without Docker, it runs as one jar on Java 21: see
[README-NATIVE.md](README-NATIVE.md).


### Running under Postgres
Altitude will use Sqlite by default. The database cannot be changed after the fact, so if you would like to use Postgres,
choose it before running the app for the first time in setup mode: under Docker with the `postgres` profile
([PostgreSQL](README-DOCKER.md#postgresql)), in development with `db.engine=postgres` in `application-dev.conf`
([Developer settings](#developer-settings)).

### Technology Stack

* Scala 3 & Cask
* Postgres OR Sqlite
* HTMX
* OpenCV
* Pekko (formerly Akka) 

## Install

* [README-DOCKER.md](README-DOCKER.md): Installing and configuring Altitude under Docker. 
* [README-NATIVE.md](README-NATIVE.md): Running Altitude natively without Docker, as one jar, closer to the metal. 

## Development setup

You will need:

* Java 21 or later (a JDK: the build compiles for Java 21)
* Docker (for the Postgres/S3 containers, and to build the image)

> [!NOTE]
> Mill needs no install. 

> [!WARNING]
> The build does not run on an Intel Mac, for which ONNX Runtime ships no native libraries; Altitude runs
there in Docker.

### Environment

`ALTITUDE_ENV` names the environment: `dev`, `test` or `prod`. The Makefile sets it (`dev` for `make run` and
`make watch`, `test` for the test targets), so a start by hand needs it too: unset, the environment is `prod`. A
value that names no environment will force an abort. The `altitude.env` system property, when set, wins over the
variable.

### Models

The model files are in neither this git nor the build. The first start downloads them into the Models
directory, and a file in place there is never downloaded again. Each file is pinned in code by its URL, its size and its
SHA-256 (`ModelFile` in `altitude/src/altitude/core/util/ModelFiles.scala`).

The files of the pinned set are in `<Models directory>/v1/`. The Models directory is `models.dir`:

* empty, the default: the per-user cache, `$XDG_CACHE_HOME/altitude/models`, else `~/.cache/altitude/models`, which
  the dev server, the tests and every worktree share
* or set in `application.conf` (`application-dev.conf` in development), or by the `ALTITUDE_MODELS_DIR` environment
  variable, which wins over a `models.dir` in the file. `~/` is your home folder; any other relative path is under the
  application's folder. A user with no home folder (some containers) must set one of the two.

The Docker image sets `ALTITUDE_MODELS_DIR=/models`, and `compose.yaml` mounts a volume there, so the files outlive
the container.

| File | Size | What it is |
|---|---|---|
| `dinov2_vitb14.onnx` | 346 MB | DINOv2 ViT-B/14, a Scene model |
| `siglip2_b16_256_image.onnx` | 372 MB | The SigLIP 2 ViT-B/16 image tower at 256 pixels, a Scene model |
| `siglip2_b16_256_text.onnx` | 343 MB | The same checkpoint's text tower, which embeds a description |
| `siglip2_token_embeddings.f16` | 393 MB | The text tower's token-embedding table |
| `siglip2_tokenizer.tsv` | 6 MB | The text tower's tokenizer |
| `face_detection_yunet_2023mar.onnx` | 233 kB | YuNet, the face detector |
| `w600k_r50.onnx` | 174 MB | ArcFace (InsightFace `w600k_r50`), the face embedding net |

The models live on [renegade-otter/altitude-models](https://github.com/renegade-otter/altitude-models), at
`https://github.com/renegade-otter/altitude-models/releases/download/v1/<file>`.

ArcFace is under InsightFace's non-commercial research license, so it is not re-hosted: it comes from a pinned revision
of [maze/faceX](https://huggingface.co/maze/faceX/resolve/e010b5098c3685fd00b22dd2aec6f37320e3d850/w600k_r50.onnx).

### Developer settings

`application-dev.conf.example` has all supported overrides to:

* Enable Postgres as the data store.
* Define dev user and disable login gate after each hot reload cycle.

To enable, copy or rename the file as `application-dev.conf`

### Build & Run

```sh
# Bring up the Postgres database (dev and test at once)
make db
# Run the tests (README-TESTING.md has the rest)
make test
# With hot reload
make watch
```

Run `npm install` to install dev-time dependencies (these are just formatters and linters)
in order to have the `make lint` command work.

[See the Makefile](https://github.com/papito/altitude/blob/trunk/Makefile) for all the available commands.


### Logging

The logback files are in `altitude/resources/`. A dev run (`make run`, `make watch`) logs by `logback-dev.xml`. A
packaged start, the Docker image's included, logs by `logback.xml`, which takes two environment variables:
`ALTITUDE_LOG_FORMAT` (`text` or `json`, one object a line) and `ALTITUDE_LOG_LEVEL` (`TRACE`, `DEBUG`, `INFO`,
`WARN`, `ERROR` or `OFF`; `INFO` unless set). The tests log by `altitude/test/resources/logback-test.xml`.

### Style and formatting

Linting configurations are in:

* `.scalafmt.conf`
* `.scalafix.conf`
* `.prettierrc.json`
* `eslint.config.js`
* `.prettierignore`
 
### Testing

[README-TESTING.md](README-TESTING.md): how the tests are organized, how to run all of them or one, and what to know
before writing one.

### Packaging

The Docker image:

    make docker

It builds the image for this machine's architecture and loads it as `altitude:local`, the image `compose.yaml` runs;
`DOCKER_PLATFORM=linux/arm64 make docker` (or `linux/amd64`) builds the other architecture's, which needs QEMU (the
Makefile names the command). `make smoke-docker` then starts that image, imports into it and stops it mid-import,
checking what the image promises; it needs `curl` and `ffmpeg` on the machine.

What the image holds in `/app` is the build's dist output:

    ./mill altitude.dist

It writes `out/altitude/dist.dest/`: `lib/` (the dependency jars, without their native libraries), `altitude.jar`,
`native/` (one platform's native libraries) and `jvm-options` (the JVM flags that load them from `/app/native`, the
folder's place in the image). `ALTITUDE_PLATFORM` (`linux-x86_64`, `linux-arm64`, `macosx-arm64` or `windows-x86_64`)
names the platform whose libraries `native/` holds; unset, it is the build machine's.

The native jars, one per platform:

    make dist-jars

It writes `out/dist-jars/altitude-<version>-<platform>.jar`, each jar holding one platform's native libraries alone;
[README-NATIVE.md](README-NATIVE.md) says how they run. `./mill altitude.platformJar` builds this machine's alone,
into `out/altitude/platformJar.dest/`. `make smoke-jar` then starts that jar (`JAR=<path>` names another) from a
scratch folder, imports into it, stops it and starts it again, checking what the jar promises; it needs `curl` and
`ffmpeg` on the machine, and `scripts/smoke-jar.sh`'s header lists the rest.

## Design decisions and other resources

These are covered in the [WIKI](https://github.com/papito/altitude/wiki)

## Contacting the author
If you have any questions about this project, please use [this form](https://renegadeotter.com/contact.html) to drop a line.
