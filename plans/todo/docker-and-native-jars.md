# Docker image, then native jars per platform

## Goals

- Altitude runs under Docker, and later natively as a fat jar, on every platform its native libraries support. Docker
  comes first (stage 1); the native jars follow (stage 2), and stage 1 builds nothing stage 2 has to undo.
- The Mill build packages the platform-specific natives (OpenCV, FFmpeg and OpenBLAS through bytedeco, ONNX Runtime,
  sqlite-jdbc, sqlite-vector) for a platform it is told, not only for the machine it runs on.
- A warning on the target system is acceptable while no vendor signing or notarization is paid for, as long as the
  person can still start Altitude.
- Intel Macs run the Docker image; there is no native Intel Mac jar.

Out of scope: publishing the image to a registry (stage 1 builds locally); a bytedeco upgrade (OpenCV 4.9, FFmpeg 6.1.1
and javacpp 1.5.10 stay, since a new OpenCV can move the face and Sequence thresholds); Java 25; installers, a tray and
First run; a reverse proxy service in compose; GPU inference.

## Platforms

| Platform | Docker (stage 1) | Native jar (stage 2) | Floor |
|---|---|---|---|
| Linux x64 | `linux/amd64` | `linux-x86_64` | glibc 2.29: Ubuntu 20.04+, Debian 11+, RHEL/Rocky 9+ |
| Linux arm64 (Pi 4/5 on a 64-bit OS, Ampere, Graviton) | `linux/arm64` | `linux-arm64` | glibc 2.29; 4, 16 and 64 KB page kernels (every native is 64 KB aligned) |
| macOS Apple Silicon | Docker Desktop, `linux/arm64` | `macosx-arm64` | macOS 15 (sqlite-vector's dylib; ONNX Runtime needs 14) |
| macOS Intel | Docker Desktop (macOS 14+), `linux/amd64` | none | ONNX Runtime ships no Intel Mac natives from 1.24.1 |
| Windows x64 | Docker Desktop, `linux/amd64` | `windows-x86_64` | Smart App Control, when on, blocks the unsigned DLLs |
| Windows on ARM | Docker Desktop, `linux/arm64` | the x64 jar on an x64 JRE (emulated), unverified | no Windows ARM build of bytedeco or ONNX Runtime |
| 32-bit ARM, RISC-V, ppc64le, Alpine/musl | none | none | ONNX Runtime has no natives there; bytedeco and ONNX Runtime need glibc |

## Decisions

| Area | Decision |
|---|---|
| Image architectures | One multi-arch image: `linux/amd64` and `linux/arm64`. |
| Platform switch | `ALTITUDE_PLATFORM` (`linux-x86_64`, `linux-arm64`, `macosx-arm64`, `windows-x86_64`) chooses the natives Mill packages; unset, it is the build machine's platform. Any other value fails the build. |
| Image build | A multi-stage `Dockerfile`. The build stage runs on `$BUILDPLATFORM` and calls `./mill altitude.dist` with the platform of `$TARGETARCH`; the runtime stage only copies, so no stage runs under emulation. Coursier and Mill caches are BuildKit cache mounts. |
| Image contents | Thin jars and unpacked natives, not the fat jar: `lib/` holds the dependency jars with their natives stripped, `altitude.jar` the application, `native/` one platform's libraries. The JVM options point javacpp, ONNX Runtime, sqlite-jdbc and sqlite-vector at `native/`, so nothing is unpacked at runtime and the root filesystem is read-only. The dependency jars are one layer and the application jar another. |
| Runtime base | `eclipse-temurin:21-jre-noble` (Ubuntu 24.04, glibc 2.39, `curl` for the health check, the `ubuntu` user at UID 1000). |
| Java | 21 in the image, and 21 the jar's floor: the build compiles with `-release 21`. |
| Database | SQLite in the Data directory by default. Compose's `postgres` profile adds `pgvector/pgvector:0.8.2-pg18-trixie` with its own volume and a password the `.env` file must set. |
| Configuration | Named environment variables, substituted in `reference.conf` as `models.dir` already is: `ALTITUDE_HOST`, `ALTITUDE_PORT`, `ALTITUDE_DATA_DIR`, `ALTITUDE_DB_ENGINE`, `ALTITUDE_DB_POSTGRES_URL`, `ALTITUDE_DB_POSTGRES_USER`, `ALTITUDE_DB_POSTGRES_PASSWORD`, `ALTITUDE_IMPORT_PARALLELISM_MAX`, `ALTITUDE_MODELS_DIR`, `ALTITUDE_LOG_LEVEL`, `ALTITUDE_LOG_FORMAT`. A key set in the config file wins over its variable. `ALTITUDE_CONFIG` names a config file; unset, prod reads `application.conf` in the jar's folder, and a missing file there means the defaults. A file `ALTITUDE_CONFIG` names that does not exist fails the start. |
| Environment | The `altitude.env` system property, then `ENV`, then prod. A value that is not dev, test or prod (in their accepted spellings) fails the start, naming the accepted values. The image passes `-Daltitude.env=prod`. |
| Paths | An absolute `fs.data.dir` is used as it is; a relative one is under the jar's folder. In the image the Data directory is `/data` and the Models directory `/models`, two volumes: `/data` is backed up, `/models` never is. Watch folders are host folders bind-mounted where the person likes. |
| User | `USER 1000:1000`. `/data` and `/models` exist in the image owned by it, so named volumes are writable; other owners set compose's `user:`. Root filesystem read-only, `/tmp` an anonymous volume (disk, not RAM: Tika and ImageIO spool large files there). |
| Memory | No limit in compose; the JVM's container-aware default heap. The README states 6 GB as the minimum and 8 GB or more as the recommendation, and how to cap import threads on a small machine. |
| First start | The start blocks on the model download as it does natively. `HEALTHCHECK` curls `/api/health` with a 15-minute start period and a 5-second start interval; the download's progress is in `docker logs`. Offline, the files are copied into `/models/v1/` by hand. |
| Shutdown | A `SIGTERM` (or Ctrl-C, or a logoff) stops Undertow taking requests and runs `Altitude.cleanup()`, as Pekko coordinated-shutdown tasks with phase timeouts raised to cover the queues' drains. Compose's `stop_grace_period` is 90 seconds. |
| Out of memory | `-XX:+ExitOnOutOfMemoryError` in the image and `restart: unless-stopped` in compose; the startup already fails an interrupted Import's pending files. |
| Errors | cask's `debugMode` is on in dev alone, so prod answers an error without a stack trace. |
| Logging | `logback.xml` is the prod configuration: ISO date and time with zone, no ANSI, root at `ALTITUDE_LOG_LEVEL` (INFO), no request lines, and JSON lines through logback's `JsonEncoder` when `ALTITUDE_LOG_FORMAT=json`. Dev runs with `logback-dev.xml`, today's file; tests keep `logback-test.xml`. |
| Time zone | Compose sets `TZ: ${TZ:-UTC}`; the README asks for it before the first Import, since Date Imported's day is a day in the server's time zone. The start logs the zone. |
| Address | The application binds `server.host` (default `localhost`) and `server.port` (default 8080). The image sets `ALTITUDE_HOST=0.0.0.0`; compose publishes `${ALTITUDE_PUBLISH_PORT:-8080}:8080` on every interface (a variable apart from `ALTITUDE_PORT`, which is the server's own). While no Admin exists, the start's WARN names the address and says the first visitor runs Setup. |
| HTTPS | A README recipe for Caddy, with notes for Traefik and nginx (pass `Host` through, allow the WebSocket upgrade, raise the upload body limit). No proxy service in compose. |
| Files | `Dockerfile`, `.dockerignore`, `compose.yaml` and `.env.example` at the root; the dev databases' files become `compose.dev.yaml` and `compose.test.yaml`. Mill's `./mill` wrapper is committed. |
| Image builds | Local alone: `make docker` builds and loads an image for the machine's architecture, `DOCKER_PLATFORM=linux/arm64` another. Building for the other architecture needs the `docker-buildx` package and, to run it, QEMU binfmt (`docker run --privileged --rm tonistiigi/binfmt --install arm64`), both installed once by the owner. |
| Stage 2 jars | One fat jar per platform, `altitude-<version>-<platform>.jar`, holding that platform's natives alone. A jar started on another platform fails with a message naming the right download, or Docker on an Intel Mac. |
| Stage 2 natives | ONNX Runtime and sqlite-vector are unpacked once into `<user cache>/altitude/natives/<version>-<platform>/`, beside the models cache, and loaded from there; javacpp keeps its own cache. Older versions' folders are pruned. |
| Stage 2 defaults | The jar's folder holds `application.conf` and `data/`. It binds `localhost` until the owner sets `ALTITUDE_HOST` or `server.host`. |
| Stage 2 Windows | The x64 jar. The README tells a person whom Smart App Control blocks to turn it off in Windows Security (no reinstall is needed) or to use Docker Desktop. No paid signing. |
| Stage 2 builds | A GitHub Actions matrix builds and smoke-runs each jar on its own system. The owner creates the workflow; where the jars are published is decided when stage 2 starts. |

## Stage 1: Docker

### A. Startup and configuration

1. `Environment.scala`: `CURRENT` from the `altitude.env` property, then `ENV`, then prod; an unrecognised value throws
   naming the accepted ones. Closes the readiness item where a misspelled `ENV` runs dev.
2. `Altitude.scala`: prod reads `ALTITUDE_CONFIG` when set (failing when it does not exist), else `application.conf` in
   `Environment.ROOT_PATH`, the jar's folder, instead of the working directory; the data path takes an absolute
   `fs.data.dir` as it is. The config is the same wherever the jar is started from.
3. `reference.conf` and `Const.Conf`: `server.host` and `server.port`, and the `${?ALTITUDE_...}` substitutions of the
   Decisions table, each with a comment. A container is configured by environment alone.
4. `App.scala`: `host`, `port` from the config, `debugMode` true in dev alone. The start logs the address, the Data
   directory, the Models directory and the time zone, and the not-initialized WARN names the address and says the first
   visitor runs Setup.
5. `App.scala`: the server is built over `defaultHandler` and held, so it can be stopped; Pekko's coordinated shutdown
   gets the server's stop in `service-unbind` and `Altitude.cleanup()` in `service-stop`, with that phase's timeout
   covering the Pipeline shutdown timeout of both queues plus the render pool's. Without it a stop loses accepted
   uploads that the queues could have finished.
6. `build.mill`: `-release 21` for scalac and `--release 21` for javac, so the jar's floor is checked when it compiles.
7. Tests: the environment's selection (the property first, each accepted `ENV` spelling, an unknown one failing); the prod
   config path (`ALTITUDE_CONFIG`, the jar's folder, a missing named file failing); an absolute and a relative
   `fs.data.dir`; an environment variable overriding a reference key and a file key winning over it; `debugMode` off
   outside dev; `cleanup()` running on a coordinated shutdown.

### B. Logging

8. `altitude/resources/logback.xml` becomes the prod configuration of the Decisions table; today's file moves to
   `logback-dev.xml`, which `run` and `runBackground` select with `-Dlogback.configurationFile` in `forkArgs`. The test
   module's `forkArgs` keep `logback-test.xml`. The image and a plain `java -jar` need no flag to log for prod.

### C. Natives from a folder, and the platform switch

9. `build.mill`: `ALTITUDE_PLATFORM` overrides `detectPlatform()`, checked against the four platforms. The bytedeco
   classifier jars, the ONNX Runtime natives and the sqlite-jdbc and sqlite-vector builds follow it.
10. `build.mill`: an `altitude.dist` task writing `out/altitude/dist.dest/`: `lib/` (the dependency jars without the
    bytedeco classifier jars, and the ONNX Runtime and sqlite-jdbc jars repacked without natives or `.dSYM` folders),
    `altitude.jar`, `native/` (the platform's bytedeco libraries unpacked, ONNX Runtime's two libraries, sqlite-jdbc's,
    sqlite-vector's) and `jvm-options`, the flags naming them: javacpp's library path, `onnxruntime.native.path`,
    `org.sqlite.lib.path` and `org.sqlite.lib.name`, `altitude.native.dir`. Whether javacpp loads best from
    `java.library.path` or from the unpacked tree on the classpath is settled by the smoke run's check that nothing is
    written to `~/.javacpp` or the temp folder.
11. `TransactionManager.scala`: with `altitude.native.dir` set, sqlite-vector loads from there; unset, it is extracted
    from the classpath as it is now. `Environment.resolveResourcePath`'s comment says so.
12. `./mill`: Mill's bootstrap script, reading the version from `build.mill`'s header.

### D. The image and compose

13. `Dockerfile`: the build stage (`eclipse-temurin:21-jdk-noble` on `$BUILDPLATFORM`, `$TARGETARCH` mapped to
    `linux-x86_64` or `linux-arm64`, cache mounts) and the runtime stage (`eclipse-temurin:21-jre-noble`; `lib/` copied
    before `altitude.jar` and `native/`, all `--chown=1000:1000`; empty `/data` and `/models` owned by 1000; `USER
    1000:1000`; `ALTITUDE_HOST=0.0.0.0`, `ALTITUDE_DATA_DIR=/data`, `ALTITUDE_MODELS_DIR=/models`; `VOLUME /data /models`;
    `EXPOSE 8080`; the `HEALTHCHECK`; an exec-form `ENTRYPOINT` of `java @/app/jvm-options -Daltitude.env=prod
    -XX:+ExitOnOutOfMemoryError -cp /app/altitude.jar:/app/lib/* altitude.core.App`). No `RUN` in the runtime stage.
14. `.dockerignore`: `out/`, `node_modules/`, `data/`, `.git/`, logs, `hs_err_pid*.log`, `application*.conf`, so the
    context is the sources alone and no local config or database reaches an image.
15. `compose.yaml`: the `altitude` service (`build: .`, `image: altitude:local`, the published port, `TZ`, the engine and
    PostgreSQL variables from `.env`, the `data` and `models` named volumes, `/tmp` as an anonymous volume, `read_only:
    true`, `stop_grace_period: 90s`, `restart: unless-stopped`, `depends_on` PostgreSQL healthy with `required: false`)
    and the `postgres` service under the `postgres` profile (its password required from `.env`, its volume at
    `/var/lib/postgresql`, `pg_isready` as its health check). `.env.example` shows `COMPOSE_PROFILES=postgres` with
    `ALTITUDE_DB_ENGINE=postgres` as the switch.
16. `docker-compose.yml` and `docker-compose.test.yml` become `compose.dev.yaml` and `compose.test.yaml`, with the same
    containers and ports; `make db` names them.
17. `Makefile`: `make docker` (`docker buildx build --load`, `DOCKER_PLATFORM` optional) and `make smoke-docker`.
18. `scripts/smoke-docker.sh`: runs the image with scratch volumes for `/data` and `/tmp` and the per-user models cache
    bind-mounted as `/models`, waits for the health check, runs Setup and imports the test photos with faces and a short
    video it makes with `ffmpeg -f lavfi`, checks faces were found and the Scene embeddings written, stops the container
    and checks the stop drained (no Import left in progress), that the root filesystem stayed read-only, and that the
    image holds natives for its own architecture alone.

### E. The docs

19. `README.md`: an Install with Docker section (`docker compose up`, then Setup at once; `TZ`; memory; the two volumes
    and what to back up; Watch folders as bind mounts, writable when a Watch folder deletes its files; the `postgres`
    profile; `user:` for other owners; the stop grace for NAS managers; the models offline; HTTPS through a proxy); Java 21
    in Development setup; the Packaging section names `make docker` and the dist output.
20. `altitude/AGENTS.md`: the build commands (`./mill`, `make docker`, the renamed compose files), the startup and
    shutdown order, the configuration rules and the environment variables, the prod logging file.
21. `docs/READINESS.md`: the install item (an image exists, local builds only), the config item, the `ENV` fallback, the
    stack traces and the shutdown hook move to what the code does now. `docs/test-coverage.md`: the new tests.

## Stage 2: native jars

### F. One jar per platform

22. `build.mill`: an `altitude.platformJar` task assembling `altitude-<version>-<platform>.jar` from the dist inputs of
    `ALTITUDE_PLATFORM` (that platform's natives only, ONNX Runtime and sqlite-jdbc trimmed to it, sqlite-vector's one
    build), with `Altitude-Platform` and `Enable-Native-Access: ALL-UNNAMED` in its manifest; `make dist-jars` builds the
    four. It replaces `make publish`.
23. The start compares the manifest's platform with `os.name` and `os.arch` before any native loads, and fails naming the
    jar to download, or Docker on an Intel Mac, instead of an `UnsatisfiedLinkError`.

### G. Natives unpacked once

24. A `NativeLibraries` helper unpacks ONNX Runtime and sqlite-vector into `<user cache>/altitude/natives/<version>-<platform>/`
    under a lock file, as the model files are, sets `onnxruntime.native.path` and `altitude.native.dir` before either
    loads, and prunes other versions' folders. `Environment.resolveResourcePath` and its temp folder go. A start no longer
    leaves about 30 MB in the temp folder.
25. Tests: unpacked once and reused, a second process waiting on the lock, an older version's folder pruned.

### H. Builds on each system

26. `.github/workflows/jars.yml` (the owner creates it): a matrix on `ubuntu-24.04`, `ubuntu-24.04-arm`, `macos-15` and
    `windows-2025`, each building its jar and running `scripts/smoke-jar.sh`.
27. `scripts/smoke-jar.sh`: starts the jar from a scratch folder with a scratch user cache and the models placed, waits for
    `/api/health`, imports the test photos and a video, stops it, and checks `data/` is beside the jar, nothing is left
    in the temp folder, and the address is `localhost`.

### I. The docs

28. `README.md`: an Install natively section with the Platforms table, `java -jar` per system, the Smart App Control step,
    the unverified Windows on ARM route, Docker for Intel Macs, and opening the server to the LAN with `ALTITUDE_HOST`.
29. `docs/READINESS.md`: the install item for the native jars.

## Verification

- `make test`, `make test-psql`, `make test-js` and `make lint` pass.
- `make docker` and `make smoke-docker` pass for `linux/amd64` here and for `linux/arm64` under QEMU.
- `docker compose up` from a clean clone with empty volumes downloads the models (progress in `docker logs`, the
  container `starting` until it answers, then `healthy`), and Setup, an import with faces and a video, and a Describe
  search work. A restart downloads nothing.
- `COMPOSE_PROFILES=postgres` with `ALTITUDE_DB_ENGINE=postgres` runs the same on PostgreSQL, and a missing password in
  `.env` stops `compose up` with its message.
- `docker compose stop` during an import of the test photos: the log shows the queue draining and the database closing
  inside the grace period, and the next start reports no interrupted Import.
- With `user: "1001:1001"` and bind-mounted folders owned by that user, Altitude starts and imports.
- `ENV=dve` stops the start with its message; `ENV=dev` in the container is ignored, since the property wins.
- An error answer in prod holds no stack trace.
- Stage 2: the matrix passes on all four systems; on a clean Windows 11 with Smart App Control on, the block and the
  README's step are confirmed; on an Apple Silicon Mac running macOS 15, the jar opens by the README's steps.
