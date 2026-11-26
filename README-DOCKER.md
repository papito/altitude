# Install with Docker

Altitude runs as one container, built on your machine from this repository: no image is published. You will need
Docker Engine 25 or later with the Compose plugin (2.20 or later) and Buildx; Docker Desktop has all three. The image
is built for 64-bit x86 (`linux/amd64`) or 64-bit ARM (`linux/arm64`), whichever the machine is. Without Docker,
Altitude runs as one jar on Java 21: see [README-NATIVE.md](README-NATIVE.md).

```sh
git clone https://github.com/papito/altitude.git
cd altitude
docker compose up -d
```

The first run builds the image. The first start then downloads the model files (close to 2GB) and answers no request
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

## Sign in from another machine

A browser keeps Altitude's session cookie over HTTPS, or over plain HTTP on `localhost` alone. So
`http://localhost:8080` works as it is, while at `http://192.168.1.20:8080`, from another machine, a sign-in does not
hold: the login page comes back. For a server reached on a LAN or a VPN only, put this line in `.env`:

```sh
ALTITUDE_SECURE_COOKIES=false
```

The session cookie then travels over plain HTTP, where the network can read it, and every start logs a warning that
says so. Anywhere else, leave the line out and serve [HTTPS](#https).

## Time zone

Set the server's time zone in `.env` before the first Import:

```sh
TZ=Europe/Berlin
```

The day an asset was imported on (Date Imported) is a day in the server's time zone, which is UTC unless this line
names another. Each start logs the zone it runs in.

## Memory

Altitude needs 6 GB of memory at least, and 8 GB or more is recommended. `compose.yaml` sets no limit, so the
container has what the machine has. Docker Desktop runs containers in a virtual machine with a memory limit of its
own, which must allow as much (Settings → Resources, or `.wslconfig` with the WSL 2 backend).

An import works on several files at once: half the cores at most, and fewer where the memory is short. On a small
machine, cap it in `.env` (2 is the least):

```sh
ALTITUDE_IMPORT_PARALLELISM_MAX=2
```

## Your data, and what to back up

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

## A folder of the host as the Data directory

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

## Watch folders

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

## PostgreSQL

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

## Files that belong to another user

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

## Stopping

`docker compose stop` gives the server 120 seconds to finish: it refuses new requests, lets those under way end,
and imports the files it has already accepted before it closes the database.

- How long the queues get is the Pipeline shutdown timeout Setting, 30 seconds by default. Whatever stops the
  container must allow three times that Setting plus 30 seconds. If you raise the Setting, raise
  `stop_grace_period` in `compose.override.yaml` with it (`stop_grace_period: 300s` under `altitude:`).
- A NAS's container manager, or anything else that stops containers with a timeout of its own, must be given 120
  seconds or more for this one.
- A stop cut short loses nothing already imported. The uploaded files it cut off are listed as failed in the Import
  history at the next start, to upload again; a Watch folder reads its files again.

## Without a route to the internet

The first start downloads the model files from GitHub and Hugging Face. A server that cannot reach them does not
start, and its log (`docker compose logs altitude`) lists each missing file with its URL, size and SHA-256. Download
the seven files on another machine ([Models](README.md#models) lists them), put them in a folder named `v1`, and mount that
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

## HTTPS

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
