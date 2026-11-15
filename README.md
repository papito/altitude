# Altitude DAM #

A self-hosted web-based digital asset manager (DAM) that is meant to be simple, resilient, and fast.

There are plenty of media managers in existence at this point, but they often require considerable technical
skills to just get up and running. Altitude is meant to be easy-to-use and easy-to-deploy.

The **only** thing needed is a Java runtime on the target system, and Altitude will take care 
of the rest (Dockerized runner will come later, when it makes more sense). 

You don't need to know what Postgres or microservices are, or how to clear the cache state 
from Redis if something is broken (there are no microservices and there is no Redis).

### Running under Postgres
Altitude will use Sqlite by default. The database cannot be changed after the fact, so if you would like to use Postgres, 
move `application-dev.conf.postgres` into `application-dev.conf` before running the app for the first time
in setup mode.

### Technology Stack

* Scala 3 & Cask
* Postgres OR Sqlite
* HTMX
* OpenCV
* Pekko (formerly Akka) Streams

## Status

This project is still in its "technology preview" stage. At this point it features:

* Web-based setup
* Streams-based fast import pipeline
* Facial detection and recognition
* Default result display with lazy loading and infinite scrolling
* Drag-and-drop file and folder management
* Text search over people, locations, folders, albums, file names and metadata, sorted by relevance
* Rudimentary results display
* A test suite that runs against both Postgres and Sqlite

Missing features:

* Right now there is only the single-user, single-library mode.
* No metadata view (but it IS being extracted and saved).

## Development setup

You will need:

* Java 17 or up (21 recommended)
* Docker (if using Postgres)
* [Mill](https://mill-build.org/mill/cli/installation-ide.html)

### Models

The model files, about 1.6 GB, are in neither git nor the build. The first start downloads them into the models
directory, and a file in place there is never downloaded again. Each file is pinned in code by its URL, its size and its
SHA-256 (`ModelFile` in `altitude/src/altitude/core/util/ModelFiles.scala`) and checked against its pins before it gets
its name; the start waits for the downloads and logs their progress, and a download cut off resumes at the next start.

The files of the pinned set are in `<models dir>/v1/`. The models directory is `models.dir`:

* empty, the default: the per-user cache, `$XDG_CACHE_HOME/altitude/models`, else `~/.cache/altitude/models`, which
  the dev server, the tests and every worktree share
* or set in `application.conf` (`application-dev.conf` in development), or by the `ALTITUDE_MODELS_DIR` environment
  variable, which a `models.dir` in the file overrides. `~/` is your home folder; any other relative path is under the
  application's folder. A user with no home folder (some containers) must set one of the two.

Under docker-compose, point `ALTITUDE_MODELS_DIR` at a named volume, so the files outlive the container:

```yaml
services:
  altitude:
    environment:
      - ALTITUDE_MODELS_DIR=/models
    volumes:
      - models:/models
volumes:
  models:
```

An instance with no route to the internet takes the files by hand: download each from its URL into `<models dir>/v1/`
under its name. A start that cannot download them fails with the same list: the files missing, their sizes, SHA-256
and URLs, and the directory to put them in. A start takes a file as in place by its size alone, so check the files
placed by hand against their SHA-256 (`sha256sum -c SHA256SUMS` with the release's file, for the first six).

| File | Size | What it is |
|---|---|---|
| `dinov2_vitb14.onnx` | 346 MB | DINOv2 ViT-B/14, a Scene model |
| `siglip2_b16_256_image.onnx` | 372 MB | The SigLIP 2 ViT-B/16 image tower at 256 pixels, a Scene model |
| `siglip2_b16_256_text.onnx` | 343 MB | The same checkpoint's text tower, which embeds a description |
| `siglip2_token_embeddings.f16` | 393 MB | The text tower's token-embedding table |
| `siglip2_tokenizer.tsv` | 6 MB | The text tower's tokenizer |
| `face_detection_yunet_2023mar.onnx` | 233 kB | YuNet, the face detector |
| `w600k_r50.onnx` | 174 MB | ArcFace (InsightFace `w600k_r50`), the face embedding net |

The first six are the assets of release `v1` of
[renegade-otter/altitude-models](https://github.com/renegade-otter/altitude-models), at
`https://github.com/renegade-otter/altitude-models/releases/download/v1/<file>`; that repository's README has each
file's source checkpoint and licence.
ArcFace is under InsightFace's non-commercial research licence, so it is not re-hosted: it comes from a pinned revision
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
# Run the tests
make test
# With hot reload
make watch
```

Run `npm install` to install dev-time dependencies (these are just formatters and linters)
in order to have the `make lint` command work.

[See the Makefile](https://github.com/papito/altitude/blob/trunk/Makefile) for all the available commands.


### Logging

See `logback.xml` for configuration.

### Style and formatting

Linting configurations are in:

* `.scalafmt.conf`
* `.scalafix.conf`
* `.prettierrc`
* `.eslintrc`
* `.prettierignore`
 
### Running a tagged test(s):
Update your test as such:

```
test("work in progress", Focused) {
}
```

Then:

    make test-focused

For integration tests, **this will run more than once, against all database types**.

To run with better accuracy, use one of the following Make targets:

    make test-focused-sqlite
    make test-focused-psql
    make test-focused-controller
    make test-focused-unit

### Running all tests against a particular database:

    make test-sqlite
    make test-psql
    
### Running controller and unit tests separately

    make test-controller
    make test-unit
    
### Packaging (make a fat jar)

    make publish

The jar will be in `target/`. The jar can be run with:

    java -jar [jar name]

## Design decisions and other resources

These are covered in the [WIKI](https://github.com/papito/altitude/wiki)

## Contacting the author
If you have any questions about this project, please use [this form](https://renegadeotter.com/contact.html) to drop a line.
