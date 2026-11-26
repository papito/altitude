# Altitude DAM #

A self-hosted web-based digital asset manager (DAM) that is meant to be simple, resilient, and fast.

There are plenty of media managers in existence at this point, but they often require considerable technical
skills to just get up and running. Altitude is meant to be easy-to-use and easy-to-deploy.

The **only** thing needed is Docker on the target system, and Altitude will take care
of the rest: see [Install with Docker](#install-with-docker).

You don't need to know what Postgres or microservices are, or how to clear the cache state 
from Redis if something is broken (there are no microservices and there is no Redis).

### Running under Postgres
Altitude will use Sqlite by default. The database cannot be changed after the fact, so if you would like to use Postgres,
choose it before running the app for the first time in setup mode: under Docker with the `postgres` profile
([PostgreSQL](#postgresql)), in development with `db.engine=postgres` in `application-dev.conf`
([Developer settings](#developer-settings)).

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

## Install with Docker

Altitude runs as one container, built on your machine from this repository: no image is published. You will need
Docker Engine 25 or later with the Compose plugin (2.20 or later) and Buildx; Docker Desktop has all three. The image
is built for 64-bit x86 (`linux/amd64`) or 64-bit ARM (`linux/arm64`), whichever the machine is.

```sh
git clone https://github.com/papito/altitude.git
cd altitude
docker compose up -d
```

The first run builds the image. The first start then downloads the model files, about 1.6 GB, and answers no request
until it has them. Follow it with:

```sh
docker compose logs -f altitude
docker compose ps
```

`docker compose ps` shows the container as `starting` until the server answers, then `healthy`. A download that takes
more than about 15 minutes shows it as `unhealthy` and goes on all the same.

Then open http://localhost:8080 and run Setup **at once**. Altitude is published on port 8080 of every network
interface of the machine, and until Setup is done, whoever reaches that address first creates the Admin account. The
log carries a warning for as long as no Admin exists.

What you may change goes in a file named `.env` beside `compose.yaml`. [.env.example](.env.example) lists every
setting, each commented out:

```sh
cp .env.example .env
```

A change to `.env` takes effect at the next `docker compose up -d`. `ALTITUDE_PUBLISH_PORT=9000` in it, for one,
publishes Altitude on port 9000 instead of 8080.

Changes to the container itself (a folder of the host, another user) go in a file named `compose.override.yaml`
beside `compose.yaml`, which Compose reads with it. The sections below each show the lines they need; one file holds
them all, under one `services:` key.

Altitude is a technology preview. After a `git pull`, `docker compose up -d --build` builds the image again, but a
newer version is not promised to open an older version's database: see Data safety in
[docs/READINESS.md](docs/READINESS.md).

### Sign in from another machine

A browser keeps Altitude's session cookie over HTTPS, or over plain HTTP on `localhost` alone. So
`http://localhost:8080` works as it is, while at `http://192.168.1.20:8080`, from another machine, a sign-in does not
hold: the login page comes back. For a server reached on a LAN or a VPN only, put this line in `.env`:

```sh
ALTITUDE_SECURE_COOKIES=false
```

The session cookie then travels over plain HTTP, where the network can read it, and every start logs a warning that
says so. Anywhere else, leave the line out and serve [HTTPS](#https).

### Time zone

Set the server's time zone in `.env` before the first Import:

```sh
TZ=Europe/Berlin
```

The day an asset was imported on (Date Imported) is a day in the server's time zone, which is UTC unless this line
names another. Each start logs the zone it runs in.

### Memory

Altitude needs 6 GB of memory at least, and 8 GB or more is recommended. `compose.yaml` sets no limit, so the
container has what the machine has. Docker Desktop runs containers in a virtual machine with a memory limit of its
own, which must allow as much (Settings → Resources, or `.wslconfig` with the WSL 2 backend).

An import works on several files at once: half the cores at most, and fewer where the memory is short. On a small
machine, cap it in `.env` (2 is the least):

```sh
ALTITUDE_IMPORT_PARALLELISM_MAX=2
```

### Your data, and what to back up

| Docker volume | In the container | What it holds |
|---|---|---|
| `altitude-app_data` | `/data` | The Data directory: the database, every Library's files, and the uploads not yet imported. **A backup must cover it.** |
| `altitude-app_models` | `/models` | The Models directory: the model files. They can be downloaded again, so they are never part of a backup. |

Stop the server (`docker compose stop`) before copying the Data directory, so that the database's files are copied
whole.

`docker compose down` removes the container and keeps both volumes. `docker compose down -v` deletes them, **your
Libraries included**. A plain `down` also leaves behind the unnamed volume that was the container's `/tmp`;
`docker volume prune` removes such leftovers. While the container exists that volume holds the server's temporary
files, which every start clears, so it does not grow from one run to the next.

### A folder of the host as the Data directory

To keep the Data directory in a folder of the host, create the folder first and give it to user 1000, the user the
container runs as. Docker creates a folder that does not exist as root, and Altitude cannot write there.

```sh
sudo mkdir -p /srv/altitude/data
sudo chown 1000:1000 /srv/altitude/data
```

Then, in `compose.override.yaml`:

```yaml
services:
  altitude:
    volumes:
      - /srv/altitude/data:/data
      - db:/data/db
volumes:
  db:
```

The second line keeps the SQLite database in a Docker volume of its own, `altitude-app_db`. SQLite needs a local
disk, and a network share is none, nor is any folder of the host under Docker Desktop: the database can be corrupted
there, and a start that recognizes such a folder under its database logs a warning. A backup then covers the folder
and the `altitude-app_db` volume. [PostgreSQL](#postgresql) is the other way to keep the database off such a folder, and
needs no `db` line.

The folder starts empty: what the `altitude-app_data` volume held stays in that volume.

### Watch folders

A Watch folder is a directory on the server, which under Docker means a directory inside the container. Mount the
folder of the host in `compose.override.yaml`:

```yaml
services:
  altitude:
    volumes:
      - /mnt/photos/incoming:/watch/incoming
```

After `docker compose up -d`, add the Watch folder on the Import page, as an Admin, with the path inside the
container: `/watch/incoming`. User 1000 must be able to read the folder. A Watch folder that deletes the files it has
imported must also be able to write to it; any other can be mounted read-only (`/mnt/photos/incoming:/watch/incoming:ro`).
Mount it outside `/data`: a Watch folder may not overlap the Data directory.

### PostgreSQL

Altitude keeps its database in SQLite unless `.env` holds these three lines, with a password of your own:

```sh
COMPOSE_PROFILES=postgres
ALTITUDE_DB_ENGINE=postgres
ALTITUDE_DB_POSTGRES_PASSWORD=choose-a-password
```

The first starts a PostgreSQL container beside Altitude, with a volume of its own, `altitude-app_postgres`, and the
second points Altitude at it. Decide before Setup: the two databases share no data, so switching starts with an empty
one. A backup then covers the `altitude-app_postgres` volume together with the Data directory.

The password is set when the PostgreSQL volume is first filled. Changing the line afterwards changes what Altitude
sends, not what PostgreSQL expects.

Without a password neither container runs, and `docker compose up -d` does not stop for it: PostgreSQL refuses to
create its database, Altitude's start fails naming `ALTITUDE_DB_POSTGRES_PASSWORD`, and both are started again and
again. `docker compose ps` and `docker compose logs` show it.

### Files that belong to another user

The container runs as user 1000, group 1000. Where the folders of the host belong to another user, as on many NAS
systems, run it as that user in `compose.override.yaml`:

```yaml
services:
  altitude:
    user: "1001:1001"
    volumes:
      - /srv/altitude/data:/data
      - /srv/altitude/models:/models
```

Every folder the server writes to must then be that user's. A Docker volume is created for user 1000, so mount
`/data` and `/models` on folders of the host that the user owns, as here. Where `/data` is a network share, give a
SQLite database a folder of a local disk as `/data/db`, owned by that user too, in place of the `db` volume above.

### Stopping

`docker compose stop` gives the server 120 seconds to finish: it refuses new requests, lets those under way end,
and imports the files it has already accepted before it closes the database.

- How long the queues get is the Pipeline shutdown timeout Setting, 30 seconds by default. Whatever stops the
  container must allow three times that Setting plus 30 seconds. If you raise the Setting, raise
  `stop_grace_period` in `compose.override.yaml` with it (`stop_grace_period: 300s` under `altitude:`).
- A NAS's container manager, or anything else that stops containers with a timeout of its own, must be given 120
  seconds or more for this one.
- A stop cut short loses nothing already imported. The uploaded files it cut off are listed as failed in the Import
  history at the next start, to upload again; a Watch folder reads its files again.

### Without a route to the internet

The first start downloads the model files from GitHub and Hugging Face. A server that cannot reach them does not
start, and its log (`docker compose logs altitude`) lists each missing file with its URL, size and SHA-256. Download
the seven files on another machine ([Models](#models) lists them), put them in a folder named `v1`, and mount that
folder's parent as `/models`:

```sh
sudo mkdir -p /srv/altitude/models/v1
# copy the seven files into /srv/altitude/models/v1, then:
sudo chown -R 1000:1000 /srv/altitude/models
```

```yaml
services:
  altitude:
    volumes:
      - /srv/altitude/models:/models
```

Building the image needs the internet as well. Build it with `docker compose build` on a connected machine of the
server's architecture, and move it: `docker save -o altitude.tar altitude:local` there, `docker load -i altitude.tar`
on the server.

### HTTPS

To sign in from another machine without `ALTITUDE_SECURE_COOKIES=false`, put a reverse proxy that serves HTTPS in
front of Altitude. `compose.yaml` holds none. With [Caddy](https://caddyserver.com) on the same machine, this is the
whole `Caddyfile`:

```
altitude.example.com {
	reverse_proxy 127.0.0.1:8080
}
```

Caddy gets a certificate for a public name by itself. For a name only your network knows, add a line `tls internal`
inside the braces: Caddy then signs the certificate itself, and each device must trust Caddy's root certificate.

So that only the proxy reaches port 8080, publish it on the machine's loopback address alone, in `.env`:

```sh
ALTITUDE_PUBLISH_PORT=127.0.0.1:8080
```

Any other proxy must do three things, which Caddy does unasked:

- Pass the `Host` header on as the browser sent it. Otherwise the Import page's live status is refused.
- Let WebSocket connections through, which carry that live status.
- Take a request body of any size: an upload is one request per file, however large the file.

Traefik does the first two unasked and limits no body's size, but its entry point's read timeout
(`respondingTimeouts.readTimeout`) must allow the longest upload. nginx needs all three set: `proxy_set_header Host
$http_host`, the `Upgrade` and `Connection` headers of its WebSocket proxying, and `client_max_body_size 0` (it
refuses a body over 1 MB otherwise).

## Development setup

You will need:

* Java 21 or later (a JDK: the build compiles for Java 21)
* Docker (for the Postgres containers, and to build the image)

Mill needs no install: `./mill` (`mill.bat` on Windows) downloads the version `build.mill` names, and the Makefile
calls it. The build does not run on an Intel Mac, for which ONNX Runtime ships no native libraries; Altitude runs
there in Docker.

### Environment

`ALTITUDE_ENV` names the environment: `dev`, `test` or `prod`. The Makefile sets it (`dev` for `make run` and
`make watch`, `test` for the test targets), so a start by hand needs it too: unset, the environment is `prod`. A
value that names no environment stops the start. The `altitude.env` system property, when set, wins over the
variable. `ENV` is not read.

### Models

The model files, about 1.6 GB, are in neither git nor the build. The first start downloads them into the Models
directory, and a file in place there is never downloaded again. Each file is pinned in code by its URL, its size and its
SHA-256 (`ModelFile` in `altitude/src/altitude/core/util/ModelFiles.scala`) and checked against its pins before it gets
its name; the start waits for the downloads and logs their progress, and a download cut off resumes at the next start.

The files of the pinned set are in `<Models directory>/v1/`. The Models directory is `models.dir`:

* empty, the default: the per-user cache, `$XDG_CACHE_HOME/altitude/models`, else `~/.cache/altitude/models`, which
  the dev server, the tests and every worktree share
* or set in `application.conf` (`application-dev.conf` in development), or by the `ALTITUDE_MODELS_DIR` environment
  variable, which wins over a `models.dir` in the file. `~/` is your home folder; any other relative path is under the
  application's folder. A user with no home folder (some containers) must set one of the two.

The Docker image sets `ALTITUDE_MODELS_DIR=/models`, and `compose.yaml` mounts a volume there, so the files outlive
the container.

An instance with no route to the internet takes the files by hand: download each from its URL into
`<Models directory>/v1/` under its name. A start that cannot download them fails with the same list: the files
missing, their sizes, SHA-256 and URLs, and the directory to put them in. A start takes a file as in place by its
size alone, so check the files placed by hand against their SHA-256 (`sha256sum -c SHA256SUMS` with the release's
file, for the first six).

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
    make test-focused-controllers
    make test-focused-unit

### Running all tests against a particular database:

    make test-sqlite
    make test-psql
    
### Running controller and unit tests separately

    make test-controllers
    make test-unit
    
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

A native jar for each platform:

    make dist-jars

The jars are `out/dist-jars/altitude-<version>-<platform>.jar`, each holding one platform's native libraries alone.
One runs on its own platform with:

    java -jar altitude-<version>-<platform>.jar

Without `ORT_DISABLE_TELEMETRY=1` ONNX Runtime, which runs the Scene models, sends usage data to Microsoft; the jar's
launcher starts the JVM once more with it set, the image sets it, and so does the build for a dev or test run. The jar
runs as `prod`: it listens on `localhost:8080`, and its `application.conf`, if any, and its `data/` are in the jar's
own folder.

## Design decisions and other resources

These are covered in the [WIKI](https://github.com/papito/altitude/wiki)

## Contacting the author
If you have any questions about this project, please use [this form](https://renegadeotter.com/contact.html) to drop a line.
