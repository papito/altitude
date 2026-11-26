# Docker image, then native jars per platform

## Goals

- Altitude runs under Docker, and later natively as a fat jar, on every platform its native libraries support. Docker
  comes first (stage 1); the native jars follow (stage 2), and stage 1 builds nothing stage 2 has to undo.
- The Mill build packages the platform-specific natives (OpenCV, FFmpeg and OpenBLAS through bytedeco, ONNX Runtime,
  sqlite-jdbc, sqlite-vector) for a platform it is told, not only for the machine it runs on.
- A warning on the target system is acceptable while no vendor signing or notarization is paid for, as long as the
  person can still start Altitude.
- Intel Macs run the Docker image; there is no native Intel Mac jar.

Out of scope: publishing the image to a registry (stage 1 builds locally and on GitHub's runners); a bytedeco upgrade
(OpenCV 4.9, FFmpeg 6.1.1 and javacpp 1.5.10 stay, since a new OpenCV can move the face and Sequence thresholds); Java
25; installers, a tray and First run; a reverse proxy service in compose; GPU inference.

## Platforms

| Platform | Docker (stage 1) | Native jar (stage 2) | Floor |
|---|---|---|---|
| Linux x64 | `linux/amd64` | `linux-x86_64` | glibc 2.29: Ubuntu 20.04+, Debian 11+, RHEL/Rocky 9+ |
| Linux arm64 (Pi 4/5 with 8 GB on a 64-bit OS, Ampere, Graviton) | `linux/arm64` | `linux-arm64` | glibc 2.29; 4, 16 and 64 KB page kernels (every native is 64 KB aligned) |
| macOS Apple Silicon | Docker Desktop, `linux/arm64` | `macosx-arm64` | macOS 15 (sqlite-vector's dylib; ONNX Runtime needs 14) |
| macOS Intel | Docker Desktop (macOS 14+), `linux/amd64` | none | ONNX Runtime ships no Intel Mac natives from 1.24.1 |
| Windows x64 | Docker Desktop, `linux/amd64` | `windows-x86_64` | Smart App Control, when on, blocks the unsigned DLLs |
| Windows on ARM | Docker Desktop, `linux/arm64` | the x64 jar on an x64 JRE (emulated), unverified | no Windows ARM build of bytedeco or ONNX Runtime |
| 32-bit ARM, RISC-V, ppc64le, Alpine/musl | none | none | ONNX Runtime has no natives there; bytedeco and ONNX Runtime need glibc |

Docker's floor is Engine 25 (the health check's start interval) and Compose 2.20 (`depends_on.required`). A native Linux
jar also needs GTK 2, ALSA and XCB installed (`libgtk2.0-0`, `libasound2`, `libxcb1`, `libxcb-shm0` on Debian and Ubuntu):
bytedeco's `opencv_highgui` and `avdevice` link them, and a headless server has none.

## Decisions

| Area | Decision |
|---|---|
| Image architectures | One multi-arch image: `linux/amd64` and `linux/arm64`. |
| Platform switch | `ALTITUDE_PLATFORM` (`linux-x86_64`, `linux-arm64`, `macosx-arm64`, `windows-x86_64`) chooses the natives the packaging tasks take; unset, it is the build machine's platform. Any other value fails the build. It is a Mill input (`Task.Input` over `Task.env`), so the daemon sees each call's value. Compile, run and test keep the build machine's natives whatever it says, so a switch recompiles nothing. |
| Image build | A multi-stage `Dockerfile`. The `lib` stage runs on `$BUILDPLATFORM` and names no target architecture, so a two-architecture build compiles once: `./mill altitude.distLib`. The `native` stage runs on `$BUILDPLATFORM` with the platform of `$TARGETARCH`: `./mill altitude.distNative`. A `volumes` stage makes the empty folders the image's volumes mount on. The runtime stage copies all three and has one `RUN`, the `apt-get install` of the Runtime base row, which runs on the target platform (under QEMU in a cross-build). Mill runs with `--no-daemon`, so no daemon's files stay in a cache. Coursier's cache, the Mill launcher's downloads and Mill's `out/` are BuildKit cache mounts, `out/` with `sharing=locked` since the stages share it; each `RUN` copies its result out of the mount to `/dist` with `cp -a`, which keeps `native/`'s links, where `COPY --from` can see it. |
| Image contents | Thin jars and natives in a folder, not the fat jar: `lib/` holds the dependency jars with their natives stripped, `altitude.jar` the application without the sqlite-vector builds (both the same for every architecture), `native/` one platform's libraries, flat. The image sets `LD_LIBRARY_PATH=/app/native`, since bytedeco's libraries name each other with no path of their own, and the JVM options (`jvm-options`, which names the folder as `/app/native`) point javacpp (`java.library.path`), ONNX Runtime, sqlite-jdbc and sqlite-vector at `native/` and switch off javacpp's library cache and its links to the system's copies, so nothing is unpacked at runtime and the root filesystem is read-only. The dependency jars are one layer and the application jar another. |
| Runtime base | `eclipse-temurin:21-jre-noble` (Ubuntu 24.04, glibc 2.39, `curl` for the health check, the `ubuntu` user at UID 1000), plus `libgtk2.0-0t64`, `libasound2t64`, `libxcb1` and `libxcb-shm0` (about 117 MB): without them `Loader.load(opencv_java)` and `FFmpegFrameGrabber` fail to load. |
| Java | 21 in the image, and 21 the jar's floor: the build compiles with `-release 21`. |
| Database | SQLite in the Data directory by default. Compose's `postgres` profile adds `pgvector/pgvector:0.8.2-pg18-trixie` with its own volume and a password the `.env` file sets. Compose interpolates a service's variables whatever its profile, so the password is not a required variable (a plain `docker compose up` needs no `.env`): empty, the PostgreSQL image refuses to initialize and says so, and Altitude's start fails naming `ALTITUDE_DB_POSTGRES_PASSWORD`. `docker compose up -d` does not stop for it: both containers are started again until `.env` holds a password. |
| Configuration | An environment variable wins over the config file, which wins over `reference.conf`. The variables are named: `ALTITUDE_HOST`, `ALTITUDE_PORT`, `ALTITUDE_SECURE_COOKIES`, `ALTITUDE_DATA_DIR`, `ALTITUDE_DB_ENGINE`, `ALTITUDE_DB_POSTGRES_URL`, `ALTITUDE_DB_POSTGRES_USER`, `ALTITUDE_DB_POSTGRES_PASSWORD`, `ALTITUDE_IMPORT_PARALLELISM_MAX`, `ALTITUDE_MODELS_DIR`. `environment.conf`, a resource of `key=${?ALTITUDE_...}` lines alone, is layered over the file (the keys whose variable is set, no others) and the whole resolved together. A variable that is set but empty is an empty value, never the key's default: an empty host, port or Data directory, and on PostgreSQL an empty password, fails the start naming its variable. `ALTITUDE_LOG_LEVEL` and `ALTITUDE_LOG_FORMAT` are the Logging row's, with no config key. `ALTITUDE_CONFIG` names a config file, a relative one under the jar's folder; unset or empty, prod reads `application.conf` in the jar's folder, and a missing file there means the defaults. A file `ALTITUDE_CONFIG` names that does not exist fails the start. `make clear-db` reads `application-dev.conf` alone: no variable steers what it clears. Tests read `ALTITUDE_MODELS_DIR` alone, through `reference.conf` as they do now. |
| Environment | The `altitude.env` system property, then `ALTITUDE_ENV`, then prod. `ENV` is not read: it is a POSIX shell's own variable. A value that is not dev, test or prod (in their accepted spellings) fails the start, naming the accepted values, and so does such an `ALTITUDE_ENV` where the property wins; an empty one is unset. The image passes `-Daltitude.env=prod`; the Makefile sets `ALTITUDE_ENV`. |
| Session cookie | `server.secure_cookies` (default true, `ALTITUDE_SECURE_COOKIES`). False drops `Secure` from the session cookie, for plain HTTP on a LAN or VPN address, where a browser would otherwise never keep it; the start logs a WARN while it is false. |
| Paths | An absolute `fs.data.dir` is used as it is; a relative one is under the jar's folder. In the image the Data directory is `/data` and the Models directory `/models`, two volumes: `/data` is backed up, `/models` never is. Watch folders are host folders bind-mounted where the person likes. |
| SQLite's disk | WAL needs a local disk. The start logs a WARN when the SQLite folder's file store is `nfs`, `nfs4`, `cifs`, `smb3`, `9p`, `virtiofs`, `fakeowner` (Docker Desktop for Mac's, unconfirmed on a Mac) or a `fuse` type: a network share, a host folder shared into a container, or a mount through FUSE. The image holds an empty `/data/db` owned by UID 1000, so a named volume mounted there keeps the database on local disk while `/data` is a bind mount; the README gives that recipe and PostgreSQL as the other way. Compose's default stays the one `data` volume. |
| User | `USER 1000:1000`. `/app` and all it holds are root's, which the server's user reads and cannot write. `/data`, `/data/db` and `/models` exist in the image owned by UID 1000, so named volumes are writable; other owners set compose's `user:`. Compose drops every capability (`cap_drop: [ALL]`) and sets `no-new-privileges`. Root filesystem read-only, `/tmp` an anonymous volume (disk, not RAM: Tika and ImageIO spool large files there). |
| Temp folder | The image's JVM spools to `/tmp/altitude` (`-Djava.io.tmpdir`), not `/tmp` itself, where the JVM keeps files of its own. `-Daltitude.tmpdir.own=true` says the folder is the server's alone: each start makes it, empties it of what a killed run left and leaves it writable by any user. Without the property a start touches no temp folder. |
| Memory | No limit in compose; the JVM's container-aware default heap. The README states 6 GB as the minimum and 8 GB or more as the recommendation, that Docker Desktop's own memory setting must allow it, and how to cap import threads on a small machine. |
| First start | The start blocks on the model download as it does natively. `HEALTHCHECK` curls `/api/health` on `${ALTITUDE_PORT:-8080}` with a 15-minute start period and a 5-second start interval; the download's progress is in `docker logs`. Offline, the files are copied into `/models/v1/` by hand. |
| Shutdown | A `SIGTERM` (or Ctrl-C, or a logoff) runs as Pekko coordinated-shutdown tasks (`ServerShutdown`). `service-unbind`: a `GracefulShutdownHandler` over cask's `defaultHandler` answers new requests 503. `service-requests-done`: the requests under way get 10 seconds, in a phase of 15. `service-stop`: `Altitude.cleanup()`, then Undertow stops, also after a cleanup that failed, with a 5-second `SHUTDOWN_TIMEOUT` so a request still held cannot hang the stop. `App.main` builds the server itself, which leaves out the shutdown hook cask registers to stop Undertow at once. The `service-stop` task is registered the moment the instance is built, before the startup tasks, and the two of the requests once there is a server, so a stop during the start drains and closes too and leaves the server unstarted; the start runs outside `App`'s initializer, which a shutdown thread would otherwise wait for. `cleanup()` bounds itself by the Pipeline shutdown timeout Setting as it reads at the stop: three drains (import, purge, renders), 30 seconds each by default. The `service-stop` phase's own timeout is 10 minutes, a backstop set in the config overlay the actor system is built with, not in `reference.conf`, whose order against Pekko's own is not fixed. Compose's `stop_grace_period` is 120 seconds: the three default drains and 30 seconds for the requests, the embedders and the database. The Setting's description says the grace period must cover three times its value plus 30 seconds. |
| Failed start | A start that fails ends the process with status 1 after one ERROR line, `Altitude could not start: …`, whatever failed: a refused setting, a model that does not open, a startup task, an address that cannot be bound (named in the line). Left to itself, the actor system's threads would keep the JVM up with no server. `server.port` must be a whole number from 1 to 65535, checked before anything is opened. |
| Out of memory, crashes | `-XX:+ExitOnOutOfMemoryError` in the image and `restart: unless-stopped` in compose; the startup already fails an interrupted Import's pending files. `-XX:+ErrorFileToStderr` sends a JVM crash's report to `docker logs`, the working directory being read-only. |
| ONNX Runtime | Its native library sends usage data to Microsoft unless `ORT_DISABLE_TELEMETRY=1` is in the process's environment before the library loads; the Java API's `setTelemetry(false)` does not stop it. The image sets the variable, `build.mill` sets it for `run` and the tests, and a start without it logs a WARN. `OnnxEnvironment` owns the one `OrtEnvironment` and holds a second native reference to it, taken by reflection (an ONNX Runtime upgrade that breaks it fails the start, naming the version): ONNX Runtime's own shutdown hook runs beside the drain and would otherwise free the environment under the sessions still embedding. The Scene embedder closes once the images being embedded have their embeddings and refuses every image after it, whose file fails as interrupted by a restart instead of being stored with no embedding. |
| Errors | cask's `debugMode` is on in dev alone, so prod answers an error without a stack trace. A route that takes a form is `decorators.formPost`, never cask's `postForm`, whose 400 carries the parser's stack trace in every environment: `/login` answers a body that is no form a bare 400. |
| Bodies without a login | The login's form and the Setup submission are limited to 64 KB (`decorators.formPost`, `decorators.smallBody`), so neither is read whole into memory: a longer declared body is answered 413 unread, and a chunked one loses its connection as it passes the limit. The server sets no limit of its own, since an upload is streamed to a file. |
| Logging | `logback.xml` is the prod configuration: ISO date and time with zone, no ANSI, no caller data, no request lines. It defines two appenders, `text` and `json`: logback's `JsonEncoder`, one object a line with the timestamp in epoch milliseconds, the formatted message (not the pattern and its arguments), the stack trace and the MDC: the request's ID and, once the request has an account, the account's ID as its user. Each request starts with a cleared MDC, and the text pattern shows both. `ALTITUDE_LOG_FORMAT` (`text` or `json`, default `text`) names the root's appender and `ALTITUDE_LOG_LEVEL` (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` or `OFF`, default `INFO`) its level, each through a logback `PropertyDefiner` that trims the value, ignores its case and takes an empty one for unset: no conditional, so no Janino. logback itself would give an empty or unknown format no appender and an unknown level DEBUG, so the definers answer the default for an unknown value and the start then fails, naming the variable and its accepted values. Both are read from the environment (or a system property of the same name) alone: logback starts before the config file is read, so neither is a config key. Dev runs with `logback-dev.xml`, which Mill's `run`, `runBackground` and `runMain` name and nothing packaged carries; tests keep `logback-test.xml`. |
| Time zone | Compose sets `TZ: ${TZ:-UTC}`; the README asks for it before the first Import, since Date Imported's day is a day in the server's time zone. The start logs the zone. |
| Address | The application binds `server.host` (default `localhost`) and `server.port` (default 8080). The image sets `ALTITUDE_HOST=0.0.0.0`; compose publishes `${ALTITUDE_PUBLISH_PORT:-8080}:8080` on every interface (a variable apart from `ALTITUDE_PORT`, which is the server's own and which compose does not pass). While no Admin exists, the start's WARN names the address and says the first visitor runs Setup. |
| HTTPS | Sign-in over plain HTTP works on `localhost` alone unless `ALTITUDE_SECURE_COOKIES=false`. A README recipe for Caddy, with notes for Traefik and nginx (pass `Host` through, allow the WebSocket upgrade, raise the upload body limit). No proxy service in compose. |
| Files | `Dockerfile`, `.dockerignore`, `compose.yaml` (compose project `altitude-app`, so its volumes are `altitude-app_data` and `altitude-app_models` wherever the folder is) and `.env.example` at the root, `.env` git-ignored; the dev databases' files are `compose.dev.yaml` and `compose.test.yaml`, project `altitude`. Mill's `./mill` and `mill.bat` wrappers are committed, and the Makefile calls `./mill`. `.gitattributes` pins line endings (`mill` and `*.sh` LF, `mill.bat` CRLF, the sqlite-vector builds binary), so a Git for Windows clone builds the image. |
| Image builds | `make docker` builds and loads an image for the machine's architecture (`docker buildx build --load`; the cache mounts need BuildKit, so the `docker-buildx` package, installed once by the owner). `DOCKER_PLATFORM=linux/arm64` cross-builds the other, which needs QEMU binfmt for the runtime stage's `RUN` (`docker run --privileged --rm tonistiigi/binfmt --install arm64`). The arm64 image is run on GitHub's arm runner, not here. |
| Stage 1 CI | `.github/workflows/docker.yml` (the owner creates it): on `ubuntu-24.04` and `ubuntu-24.04-arm`, each natively, `make docker` and `make smoke-docker`, which needs `curl` and `ffmpeg` on the runner. The models folder (`~/.cache/altitude/models`) is an `actions/cache` entry keyed by the set's tag; the first start fills it from the set's release (`renegade-otter/altitude-models` `v1`, and ArcFace from Hugging Face). |
| Stage 2 jars | One fat jar per platform, `altitude-<version>-<platform>.jar`, holding that platform's natives alone. Its main class is a launcher compiled for Java 8: on a Java older than 21, or on another platform, it prints a message naming what to install or which jar to download (Docker on an Intel Mac) and exits, instead of an `UnsupportedClassVersionError` or an `UnsatisfiedLinkError`. Started without `ORT_DISABLE_TELEMETRY=1`, it starts the JVM once more with the variable set and waits for it, since ONNX Runtime's telemetry has no switch inside a running process (the ONNX Runtime row): with `-jar` and the jar when it was started from the jar, so the manifest's attributes apply, else with `-cp` and `java.class.path`. |
| Stage 2 natives | ONNX Runtime and sqlite-vector are unpacked once into `<user cache>/altitude/natives/<build>-<platform>/`, beside the models cache, and loaded from there; javacpp keeps its own cache. `<build>` is a hash of the jar's native files, so a rebuilt jar of the same version never reuses another build's. Each start touches its folder; folders untouched for 30 days are pruned, so no start removes what another running instance loads from. |
| Stage 2 defaults | The jar's folder holds `application.conf` and `data/`. It binds `localhost` until the owner sets `ALTITUDE_HOST` or `server.host`. |
| Stage 2 Windows | The x64 jar. The README tells a person whom Smart App Control blocks to turn it off in Windows Security (no reinstall is needed) or to use Docker Desktop. No paid signing. |
| Stage 2 builds | A GitHub Actions matrix builds each jar on its own system, runs the Unit and SQLite suites and the JS tests there, and smoke-runs the jar. The owner creates the workflow; where the jars are published is decided when stage 2 starts. |

## Stage 1: Docker

### A. Startup and configuration

1. `Environment.scala`: `CURRENT` from the `altitude.env` property, then `ALTITUDE_ENV`, then prod; an unrecognised value
   throws naming the accepted ones (an `ALTITUDE_ENV` also where the property wins; an empty value is unset), and `ENV`
   is no longer read. The `Makefile`'s targets set `ALTITUDE_ENV`. Closes the readiness item where a misspelled `ENV`
   runs dev.
2. `Altitude.scala`: one config loader for dev and prod, `Altitude.loadConfig`, which `ClearDb` uses too, with no
   variables: `environment.conf` (the keys whose variable is set) over the environment's file over
   `ConfigFactory.defaultReference()`, resolved together. Prod's file is the one
   `ALTITUDE_CONFIG` names (failing when it does not exist), else `application.conf` in `Environment.ROOT_PATH`, the
   jar's folder, instead of the working directory. The loader takes the `ALTITUDE_` variables as a map, so a test passes
   its own. The config is the same wherever the jar is started from.
3. `reference.conf` and `Const.Conf`: `server.host`, `server.port` and `server.secure_cookies`, each key that has a
   variable commented with its name; `environment.conf` holds the substitutions of the Decisions table. A container is
   configured by environment alone.
4. `App.scala`: `host`, `port` from the config, `debugMode` true in dev alone. The start logs the address, the Data
   directory, the Models directory and the time zone, and the not-initialized WARN names the address and says the first
   visitor runs Setup. The start is a lazy value outside the object's initializer; `main` logs a failed one in one ERROR
   line and exits with status 1, and a bind that fails names its address.
5. `SessionController`: `authCookie`'s `Secure` follows `server.secure_cookies`, and the start logs a WARN while it is
   false. `/login` is `decorators.formPost`: a body that is no form is answered a bare 400, and the form is limited to
   64 KB. `SetupFormController`'s submission takes `decorators.smallBody`, the same limit.
6. `App.scala` and `ServerShutdown.scala`: `main` builds the server over a `GracefulShutdownHandler` around
   `defaultHandler`, with `UndertowOptions.SHUTDOWN_TIMEOUT`. `ServerShutdown.registerCleanup` adds the `service-stop`
   task when the instance is built, and `registerRequests` the other two of the Shutdown row once there is a server;
   `Altitude.scala`'s actor system overlay sets the `service-requests-done` and `service-stop` phase timeouts.
   Without it a stop loses accepted uploads that the queues could have finished. The Pipeline shutdown timeout
   Setting's description names the grace period it needs. `SceneEmbedder.close` waits for the images being embedded
   under a read/write lock, and a later one throws `SceneEmbedderClosedException`, which fails its file as interrupted.
7. `Altitude.scala`: with `db.engine=postgres` and an empty password, the start fails naming
   `ALTITUDE_DB_POSTGRES_PASSWORD`; an empty `server.host`, a `server.port` that is no port number and an empty
   `fs.data.dir` fail it the same way, each by its variable (`serverHostOf`, `serverPortOf`, `dataPathOf`), before a
   database is opened.
8. `Altitude.scala`: on SQLite, the start logs the WARN of the SQLite's disk row from the database folder's
   `FileStore` type.
9. `build.mill`: `-release 21` for scalac and `--release 21` for javac, so the jar's floor is checked when it compiles.
10. Tests: the environment's selection (the property first, each accepted `ALTITUDE_ENV` spelling, an unknown one
    failing, `ENV` ignored); the prod config path (`ALTITUDE_CONFIG`, the jar's folder, a missing named file failing); an
    absolute and a relative `fs.data.dir`; a variable winning over a file key, and a file key over a reference key;
    `Secure` on and off; `debugMode` off outside dev; the three shutdown tasks in their order, with `cleanup()` run, a
    cleanup that dies of an error, and a stop before there is a server; the empty PostgreSQL password, host, port and
    Data directory; the file store types that warn; a failed start exiting with status 1 (`App` run as a second JVM);
    `clearDb` deaf to the variables; an oversized login and Setup body; the embedder's close under load. The suites are
    `StartupConfigTests`, `AppStartTests`, `ClearDbTests`, `ServerShutdownTests`, `ErrorAnswerControllerTests`,
    `LoginControllerTests`, `SetupFormControllerTests` and `SceneEmbedderTests`.

### B. Logging

11. `altitude/resources/logback.xml` becomes the prod configuration of the Decisions table; today's file moves to
    `logback-dev.xml`, which `run` and `runBackground` select with `-Dlogback.configurationFile` in `forkArgs`. The test
    module's `forkArgs` keep `logback-test.xml`. The image and a plain `java -jar` need no flag to log for prod: `forkArgs`
    alone carries the dev flag. The MDC's keys are `Const.LogContext`'s:
    `decorators.requestResponseLogger` clears the MDC and sets the request's ID, and `UserService.switchContextToUser`
    sets the account's ID.
12. A `LogSettings` object resolves the two logging variables to a value or an error; the two `PropertyDefiner`s
    `logback.xml` names read it, and `App.scala` fails the start on an error (`LogSettings.requireValid`) before
    anything is built. Tests: each accepted value in either case, an empty one as unset, an unknown one answering the
    default and failing the start (`LogSettingsTests`); the request's ID and the account's ID in a request's lines
    (`LogContextControllerTests`).

### C. Natives from a folder, and the platform switch

13. `build.mill`: `ALTITUDE_PLATFORM` as a `Task.Input`, checked against the four platforms. `mvnDeps` keeps
    `detectPlatform()`, which loses its Intel Mac case (the build fails there, naming Docker); the packaging tasks
    resolve the chosen platform's bytedeco classifier jars themselves.
14. `build.mill`: three tasks writing under `out/altitude/`. `altitude.distLib`: `lib/` (the dependency jars without the
    bytedeco classifier jars, and the ONNX Runtime and sqlite-jdbc jars repacked without natives or `.dSYM` folders) and
    `altitude.jar` without `sqlite-vector/`. `altitude.distNative`: `native/`, flat (the platform's bytedeco shared
    libraries, without the `ffmpeg` and `ffprobe` executables and OpenBLAS's `include/` and `lib/`; the links javacpp
    makes when it unpacks and the libraries are loaded through, `libopenblas_nolapack.so.0` to `libopenblas.so.0`, and
    each versioned library under its name without the version, as links, or as copies for Windows; ONNX Runtime's two
    libraries, three on Windows, sqlite-jdbc's, sqlite-vector's), and `jvm-options`, the flags naming it as
    `/app/native`: `java.library.path`, `onnxruntime.native.path`, `org.sqlite.lib.path` and `org.sqlite.lib.name`,
    `altitude.native.dir`, and javacpp's `cacheLibraries=false` and `canCreateSymbolicLink=false`. A dependency jar
    still holding a native library fails `distLib`. `altitude.dist`: both, in `dist.dest/`. `forkEnv` sets
    `ORT_DISABLE_TELEMETRY=1` for `run` and the tests, and `OnnxEnvironment` (the ONNX Runtime row) is in
    `SceneEmbedder.scala`, with `OnnxEnvironmentTests`.
15. `TransactionManager.scala`: with `altitude.native.dir` set, sqlite-vector loads from there; unset, it is extracted
    from the classpath as it is now. `Environment.resolveResourcePath`'s comment says so.
16. `altitude/resources/sqlite-vector/macos-x86/` and `windows-x86/vector.lib` are deleted, and
    `TransactionManager.vectorExtensionPath` loses its Intel Mac folder: no supported platform loads them.
17. `./mill` and `mill.bat`: Mill's bootstrap scripts, reading the version from `build.mill`'s header; the Makefile's
    targets call `./mill`. `.gitattributes`: the line endings of the Files row.

### D. The image and compose

18. `Dockerfile`: the `lib`, `native` and `volumes` stages of the Image build row (`eclipse-temurin:21-jdk-noble` on
    `$BUILDPLATFORM`, `$TARGETARCH` mapped to `linux-x86_64` or `linux-arm64` in `native` alone, any other failing the
    build) and the runtime stage (`eclipse-temurin:21-jre-noble`; the `apt-get install` first; empty `/data`,
    `/data/db` and `/models` copied `--chown=1000:1000`; `lib/`, `native/`, `jvm-options` and last `altitude.jar`, with
    no `--chown`, so root's; `USER 1000:1000`; `ALTITUDE_HOST=0.0.0.0`, `ALTITUDE_DATA_DIR=/data`,
    `ALTITUDE_MODELS_DIR=/models`, `LD_LIBRARY_PATH=/app/native`, `ORT_DISABLE_TELEMETRY=1`; `VOLUME /data /models`;
    `EXPOSE 8080`; the `HEALTHCHECK`; an exec-form `ENTRYPOINT` of `java @/app/jvm-options -Daltitude.env=prod
    -XX:+ExitOnOutOfMemoryError -XX:+ErrorFileToStderr -Djava.io.tmpdir=/tmp/altitude -Daltitude.tmpdir.own=true
    -cp /app/altitude.jar:/app/lib/* altitude.core.App`). `TempFolder.scala` is the Temp folder row, run first in the
    start, with `TempFolderTests`.
19. `.dockerignore`: `out/`, `node_modules/`, `data/`, `debug/`, databases (`*.db`, `*.sqlite` and their WAL files),
    `application*.conf*`, `.env*`, `*.md` and logs, each at any depth (`**/`), since `altitude/` is copied whole;
    `.git/`, `.idea/`, `.bsp/`, `.claude/`, `docs/`, `plans/`, `requests/`, `altitude/test/` and `hs_err_pid*.log` at
    the root. The context is the build's sources alone, no local config, secret or database reaches an image, and a
    change to a document rebuilds nothing.
20. `compose.yaml`: `name: altitude-app` and the `altitude` service (`build: .`, `image: altitude:local`, the published
    port, `TZ`, `ALTITUDE_SECURE_COOKIES`, the engine and PostgreSQL variables from `.env`, each with a default but the
    password, the two logging variables and `ALTITUDE_IMPORT_PARALLELISM_MAX` when `.env` sets them (an unset one is
    not passed empty), the `data` and `models` named volumes, `/tmp` as an anonymous volume, `read_only: true`,
    `cap_drop: [ALL]`, `security_opt: no-new-privileges:true`, `stop_grace_period: 120s`, `restart: unless-stopped`,
    `depends_on` PostgreSQL healthy with `required: false`) and the `postgres` service under the `postgres` profile
    (its password `${ALTITUDE_DB_POSTGRES_PASSWORD:-}`, its volume at `/var/lib/postgresql`, `pg_isready` as its health
    check).
    `.env.example`, every line commented, shows `COMPOSE_PROFILES=postgres` with `ALTITUDE_DB_ENGINE=postgres` and the
    password as the switch, `ALTITUDE_SECURE_COOKIES=false` for plain HTTP, `TZ`, the published port, the logging
    variables and the import thread cap.
21. `docker-compose.yml` and `docker-compose.test.yml` become `compose.dev.yaml` and `compose.test.yaml`, with the same
    containers and ports, under the project name `altitude`; `make db` names them.
22. `Makefile`: `make docker` (`docker buildx build --load`, `DOCKER_PLATFORM` optional) and `make smoke-docker`.
23. `scripts/smoke-docker.sh` (it needs `curl` and `ffmpeg` on the host): runs the image as the caller's UID, read-only,
    with every capability dropped and no new privileges, with scratch volumes for `/data` and `/tmp` and a models
    folder bind-mounted as `/models` (the per-user models cache unless `MODELS_DIR` names another; an empty one is
    filled by the start's download), waits for the health check, runs Setup and imports the test photos with faces and
    a short video it makes with `ffmpeg -f lavfi`, checks faces were found and the Scene embeddings written. It restarts
    the container and checks the start emptied `/tmp/altitude` and left the rest of `/tmp` alone. It then starts a
    second import, sized by the machine's cores, and stops the container while Scene embeddings are under way (tried
    once more with other photos when the stop came too late), and checks the stop drained (no Import left in progress,
    no crash report in the log), that a third start reports no interrupted Import, that the root filesystem stayed
    read-only with nothing written to the home or temp folders by javacpp, that `/app` is root's and not writable, and
    that the image holds natives for its own architecture alone. A log line that must be absent is first found in the
    sources, so a reworded one fails its check.
24. `.github/workflows/docker.yml` (the owner creates it): the Stage 1 CI row.

### E. The docs

25. `README.md`: an Install with Docker section (`docker compose up`, then Setup at once; sign-in over plain HTTP and
    `ALTITUDE_SECURE_COOKIES`; `TZ`; memory, Docker Desktop's included; the two volumes and what to back up; a
    bind-mounted `/data` with a named volume at `/data/db`, the host folder created first and owned by the container's
    user; Watch folders as bind mounts, writable when a Watch folder deletes its files; the `postgres` profile; `user:`
    for other owners; the stop grace for NAS managers; the models offline; HTTPS through a proxy); Java 21 and
    `ALTITUDE_ENV` in Development setup; a Logging section; the Packaging section names `make docker`,
    `make smoke-docker` and the dist output.
26. `altitude/AGENTS.md`: the build commands (`./mill`, `make docker`, the renamed compose files), `ALTITUDE_ENV`, the
    startup and shutdown order, the configuration rules (the precedence, `environment.conf`) and the environment
    variables (an empty one is an empty value), the prod logging file and its two variables, which are no config keys,
    the log context, `formPost` and the body limit for routes without a login, the failed start's exit, ONNX Runtime's
    telemetry variable and its retained environment, the image's temp folder.
27. `docs/READINESS.md`: the install item (an image exists, local builds only), the config item, the `ENV` fallback,
    the `Secure` cookie on plain HTTP, the stack traces and the shutdown hook move to what the code does now; it also
    records that a JSON body behind a login is read whole with no limit, and that a container whose start keeps failing
    is restarted without end. `docs/test-coverage.md`: the new tests.

## Stage 2: native jars

### F. One jar per platform

28. `build.mill`: an `altitude.platformJar` task assembling `altitude-<version>-<platform>.jar` from the dist inputs of
    `ALTITUDE_PLATFORM`: the launcher's class, `distLib`'s `altitude.jar` and `lib/`, the platform's bytedeco classifier
    jars, and `platformNatives` (ONNX Runtime's folder of the platform without `.dSYM`, sqlite-jdbc's one library,
    sqlite-vector's one build, each at its classpath path). `mill.javalib.Assembly` merges them, `reference.conf` and
    `META-INF/services/` concatenated, signatures, manifests, `INDEX.LIST` and `module-info.class` dropped, and the jar is
    written again in name order with one entry time, so the same inputs give the same bytes. Its manifest has
    `Altitude-Platform`, `Altitude-Build` (the first 16 hex digits of a SHA-256 of its native files' names, lengths and
    bytes), `Implementation-Version`, `Enable-Native-Access: ALL-UNNAMED` and `Multi-Release: true`. A native file of
    another platform fails the task. `make dist-jars` builds the four and copies each to `out/dist-jars/`. It replaces
    `make publish`.
29. A `launcher` Mill module: one Java class compiled with `--release 8`, the jar's `Main-Class`. It reads
    `java.specification.version` and compares the manifest's platform with `os.name` and `os.arch` before any other
    class loads, and prints the Stage 2 jars row's message and exits with status 1; an Intel Java on Apple Silicon is told
    to install an arm64 one and a 32-bit Java a 64-bit one. With no `Altitude-Platform`, as from a classes folder, it
    checks the Java alone. With `ORT_DISABLE_TELEMETRY=1` in its environment it then calls `altitude.core.App.main` by
    reflection, or the class the `altitude.launcher.main` property names, which its tests set. Without it, it starts the
    same JVM once more (`java.home`'s `java`, the runtime's own input arguments, `-jar` and the jar when started from it,
    else `-cp` and `java.class.path`, the same program arguments) with the variable added and `JDK_JAVA_OPTIONS`,
    `JAVA_TOOL_OPTIONS` and `_JAVA_OPTIONS` removed, its input and output inherited, and exits with that process's status,
    143 on a stop. A debugger agent or a JMX port among the input arguments is met first with a line saying that setting
    the variable skips the second JVM. A shutdown hook, registered before the second JVM starts, passes a stop on to it
    (`Process.destroy`, a SIGTERM on Linux and macOS) and waits, so the drain still runs. On Windows, where `destroy`
    kills, a Ctrl-C in the console reaches both processes by itself. Tests (`LauncherTests`, a stub in place of `App`):
    the variable present in the second JVM, its exit status passed on, a SIGTERM to the launcher waiting for the second
    JVM's shutdown work, a jar started with `java -jar` relaunched with `-jar` and another platform's jar refused, and the
    checks as functions.
30. `App.scala`: the start loads all of FFmpeg's libraries (`FFmpegFrameGrabber.tryLoad()`) after OpenCV. On Linux, an
    `UnsatisfiedLinkError` from either that names GTK, ALSA or XCB is reported in the failed start's line with the
    Debian and Ubuntu packages to install, under their names before and after the 64-bit time transition
    (`SystemLibraries`, `SystemLibrariesTests`).

### G. Natives unpacked once

31. A `NativeLibraries` helper unpacks ONNX Runtime and sqlite-vector into `<user cache>/altitude/natives/<build>-<platform>/`
    under a lock file, as the model files are, sets `onnxruntime.native.path` and `altitude.native.dir` before either
    loads (leaving them alone when they are already set, as in the image), touches its folder and prunes the folders
    untouched for 30 days. `Environment.resolveResourcePath` and its temp folder go. A start no longer leaves about 30 MB
    in the temp folder.
32. Tests: unpacked once and reused, a second process waiting on the lock, a stale folder pruned and a fresh one kept,
    the properties left alone when set.

### H. Builds on each system

33. `.github/workflows/jars.yml` (the owner creates it): a matrix on `ubuntu-24.04`, `ubuntu-24.04-arm`, `macos-15` and
    `windows-2025`, each building its jar, running the Unit and SQLite suites and the JS tests, and running
    `scripts/smoke-jar.sh`. `ALTITUDE_MODELS_DIR` names a folder `actions/cache` keeps, keyed by the set's tag: the
    first run's download fills it for the suites and the smoke run alike.
34. `scripts/smoke-jar.sh`: starts the jar from a scratch folder with a scratch user cache and the models folder named,
    waits for `/api/health`, imports the test photos and a video, stops it, and checks `data/` is beside the jar,
    nothing is left in the temp folder, and the address is `localhost`.

### I. The docs

35. `README.md`: an Install natively section with the Platforms table, `java -jar` per system, the Linux packages, the
    Smart App Control step, the unverified Windows on ARM route, Docker for Intel Macs, and opening the server to the
    LAN with `ALTITUDE_HOST` and `ALTITUDE_SECURE_COOKIES`.
36. `docs/READINESS.md`: the install item for the native jars.

## Verification

- `make test`, `make test-psql`, `make test-js` and `make lint` pass.
- `make docker` and `make smoke-docker` pass for `linux/amd64` here; `DOCKER_PLATFORM=linux/arm64 make docker` builds
  here; the `docker.yml` workflow passes on both runners.
- `docker compose up` from a clean clone with no `.env` and empty volumes downloads the models (progress in `docker
  logs`, the container `starting` until it answers, then `healthy`), and Setup, an import with faces and a video, and a
  Describe search work. A restart downloads nothing.
- From another machine on the LAN over plain HTTP, sign-in works with `ALTITUDE_SECURE_COOKIES=false` and the start
  logs its WARN; without it the cookie is never kept.
- `COMPOSE_PROFILES=postgres` with `ALTITUDE_DB_ENGINE=postgres` runs the same on PostgreSQL, and with no password in
  `.env` the PostgreSQL container refuses to initialize, naming it, Altitude's start fails naming its variable, and both
  are started again until a password is set.
- `docker compose stop` during an import of the test photos: the log shows the queue draining and the database closing
  inside the grace period, no crash report, and the next start reports no interrupted Import.
- With `user: "1001:1001"` and bind-mounted folders owned by that user, Altitude starts and imports; with a named
  volume at `/data/db` under a bind-mounted `/data`, the database is in the volume. Inside the container no capability
  is in effect and `/app` cannot be written.
- A container killed with files in `/tmp/altitude` starts again on the same `/tmp` volume with the folder emptied.
- `server.host=localhost` in a mounted `application.conf` loses to the image's `ALTITUDE_HOST`; `ALTITUDE_ENV=dve`
  stops the start with its message; `ALTITUDE_ENV=dev` in the container is ignored, since the property wins.
- A start that cannot go on exits with status 1 and one ERROR line: a taken port, a port that is no number, an empty
  port, host or Data directory, an unresolvable host, PostgreSQL with no password.
- An error answer in prod holds no stack trace. A login or Setup body declared over 64 KB is answered 413, and a
  chunked one of hundreds of megabytes leaves the server's memory where it was.
- `ALTITUDE_LOG_FORMAT=json` logs one JSON object a line; `ALTITUDE_LOG_FORMAT=jsno` and `ALTITUDE_LOG_LEVEL=verbose`
  each stop the start with a logged message naming the accepted values; an empty one of either logs as the default.
- Stage 2: the matrix passes on all four systems; a Java 17 start prints the launcher's message; a `java -jar` start
  with no `ORT_DISABLE_TELEMETRY` logs no telemetry WARN and writes nothing under the home folder's Microsoft cache; on
  a clean Windows 11
  with Smart App Control on, the block and the README's step are confirmed; on an Apple Silicon Mac running macOS 15,
  the jar opens by the README's steps.
