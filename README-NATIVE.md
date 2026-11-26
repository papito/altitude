# Install natively

Without Docker, Altitude runs as one jar on Java 21. There is a jar per platform, each holding that platform's native
libraries alone. No jar is published yet: build them from this repository, as
[Building the jars](#building-the-jars) shows. To run Altitude under Docker instead, see
[README-DOCKER.md](README-DOCKER.md).

| System | Jar | At least |
|---|---|---|
| Linux on 64-bit x86 | `altitude-<version>-linux-x86_64.jar` | glibc 2.29: Ubuntu 20.04, Debian 11, RHEL or Rocky 9 |
| Linux on 64-bit ARM: a Raspberry Pi 4 or 5 with 8 GB on a 64-bit OS, Ampere, Graviton | `altitude-<version>-linux-arm64.jar` | glibc 2.29, on a kernel of 4, 16 or 64 KB pages |
| macOS on Apple Silicon | `altitude-<version>-macosx-arm64.jar` | macOS 15 |
| macOS on Intel | None: run [Docker Desktop](README-DOCKER.md), macOS 14 or later | |
| Windows on 64-bit x86 | `altitude-<version>-windows-x86_64.jar` | Smart App Control off ([Windows](#windows)) |
| Windows on ARM | None of its own: run Docker Desktop, or the `windows-x86_64` jar on an x64 Java, emulated (not verified) | |
| 32-bit ARM, RISC-V, ppc64le, Alpine and other musl systems | None, and no Docker image either | |

Each jar needs a 64-bit Java 21 or later, of the same processor as the jar; a runtime (JRE) is enough. Eclipse Temurin
21, from [adoptium.net](https://adoptium.net), is one. Put the jar in a folder of its own, and start it with:

```sh
java -jar altitude-<version>-<platform>.jar
```

The jar checks the Java and the system before anything else. A Java older than 21, or the jar of another system, stops
it with a line that says what to install, which jar to take instead, or that Altitude runs there with Docker.

The first start downloads the model files, about 1.6 GB, and answers no request until it has them; the log, in the
terminal, shows the progress. Then open http://localhost:8080 and run Setup.

Altitude needs 6 GB of memory at least, and 8 GB or more is recommended. On a small machine, cap the import threads as
[Memory](README-DOCKER.md#memory) in the Docker guide says, with `import.parallelism.max=2` in `application.conf` or
`ALTITUDE_IMPORT_PARALLELISM_MAX=2` in the environment.

## Linux

bytedeco's OpenCV and FFmpeg link GTK 2, ALSA and XCB, which a desktop has and a headless server often lacks. On Debian
or Ubuntu, install them with:

```sh
sudo apt install libgtk2.0-0 libasound2 libxcb1 libxcb-shm0
```

On Ubuntu 24.04, Debian 13 and later, two of them have new names:

```sh
sudo apt install libgtk2.0-0t64 libasound2t64 libxcb1 libxcb-shm0
```

Other distributions have the same libraries under names of their own. A start that misses one fails with one line,
`Altitude could not start:`, which names the missing file and both sets of packages.

```sh
java -jar altitude-<version>-linux-x86_64.jar
```

On a 64-bit ARM machine, the jar is `altitude-<version>-linux-arm64.jar`.

## macOS

The jar is for an Apple Silicon Mac on macOS 15 or later. Install an arm64 Java 21 (Temurin's `aarch64` build for
macOS), then start the jar in Terminal:

```sh
java -jar altitude-<version>-macosx-arm64.jar
```

An Intel Java, which Rosetta runs on an Apple Silicon Mac, reports an Intel Mac: the jar then stops and says to install
an arm64 Java. An Intel Mac has no jar, since ONNX Runtime ships no native libraries for it. Run Altitude there with
[Docker Desktop](README-DOCKER.md), on macOS 14 or later.

## Windows

Install an x64 Java 21, then start the jar in PowerShell or a Command Prompt:

```sh
java -jar altitude-<version>-windows-x86_64.jar
```

Smart App Control blocks libraries that are not signed, and most of the jar's native libraries are not. When it is
on, which a new installation of Windows 11 can have, the start fails as they load. Turn it off in Windows Security > App &
browser control > Smart App Control; no reinstall of Windows is needed. Or run Altitude with
[Docker Desktop](README-DOCKER.md) instead.

Windows on ARM has no jar: neither bytedeco nor ONNX Runtime builds for it. Docker Desktop runs the image there. The
`windows-x86_64` jar on an x64 Java 21, which Windows runs emulated, may run too, but that is not verified.

On Windows, Ctrl-C in the jar's window is the stop that drains ([Stopping the jar](#stopping-the-jar)).

## Where the jar keeps its files

Altitude's folder is the jar's, wherever it is started from, so the user who starts it must be able to write to the
jar's folder.

| Where | What it holds |
|---|---|
| `application.conf`, beside the jar | The config file, if there is one. Without it, the defaults apply. |
| `data/`, beside the jar | The Data directory: the database, every Library's files, and the uploads not yet imported. **A backup must cover it.** |
| `~/.cache/altitude/models/` | The Models directory: the model files. They can be downloaded again, so they are never part of a backup. |
| `~/.cache/altitude/natives/` | ONNX Runtime's, SQLite's and sqlite-vector's native libraries. The first start of each build of a jar unpacks them into a folder of that build's own, and later starts load them from there. A folder no start has used for 30 days is removed. |
| `~/.javacpp/cache/` | OpenCV's, FFmpeg's and OpenBLAS's native libraries, which javacpp, the library that loads them, unpacks itself. |

`~` is the home folder, `C:\Users\<you>` on Windows. Where `XDG_CACHE_HOME` names an absolute path, the
`altitude/` folder is in it, in place of `~/.cache/altitude/`. A start that cannot write the natives folder fails,
naming it.

`application.conf` takes the keys of [reference.conf](altitude/resources/reference.conf), which describes each one.
To keep the Data directory and the model files elsewhere, for one:

```
fs.data.dir="/srv/altitude/data"
models.dir="/srv/altitude/models"
```

A relative path is under the jar's folder; in `models.dir` alone, `~/` is the home folder. An environment variable wins
over the file: `ALTITUDE_DATA_DIR` and `ALTITUDE_MODELS_DIR` here, and `reference.conf` names the variable of each key
that has one. `ALTITUDE_CONFIG` names another config file, a relative one under the jar's folder.

Stop the server before copying the Data directory, so that the database's files are copied whole. Keep it on a local
disk: on a network share SQLite's database can be corrupted, and a start that recognizes one under its database logs a
warning.

A Watch folder can be any folder of the machine outside the Data directory. The user who starts the jar must be able to
read it, and to write to it when the Watch folder deletes the files it has imported. Date Imported's day is a day in
the machine's own time zone, which each start logs.

A newer jar is not promised to open an older version's database: see Data safety in
[docs/READINESS.md](docs/READINESS.md).

## Reaching it from another machine

The jar listens on `localhost`, port 8080, so this machine alone can reach it, and each start logs the address. Run
Setup before you open it to the network: until Setup is done, whoever reaches the address first creates the Admin
account, and the log carries a warning for as long as no Admin exists.

For a server reached on a LAN or a VPN only, put these lines in `application.conf` beside the jar:

```
server.host="0.0.0.0"
server.secure_cookies=false
```

`ALTITUDE_HOST=0.0.0.0` and `ALTITUDE_SECURE_COOKIES=false` in the environment do the same. The first takes requests on
every network interface of the machine. The second lets the session cookie travel over plain HTTP, as
[Sign in from another machine](README-DOCKER.md#sign-in-from-another-machine) in the Docker guide explains, and every
start then logs a warning that says so. `server.port`, or `ALTITUDE_PORT`, moves it off port 8080.

Anywhere else, keep both lines out and serve HTTPS through a reverse proxy, as [HTTPS](README-DOCKER.md#https) in the
Docker guide shows: a proxy on the same machine reaches the jar on `localhost` as it is.

## ONNX Runtime's telemetry

ONNX Runtime, which runs the Scene models, sends usage data to Microsoft unless `ORT_DISABLE_TELEMETRY=1` is in the
process's environment when its library loads, and a running Java cannot set its own environment. So the jar starts Java
once more, with the same options and arguments and the variable set, and waits for it: a running jar is two `java`
processes, and the second one is the server.

Where `ORT_DISABLE_TELEMETRY=1` is set already, the jar runs in the one process. Set it yourself for a debugger or JMX,
whose port the second process would otherwise take a second time, and for a Java agent (`-javaagent:`, `-agentlib:`,
`-agentpath:`) or a flight recording (`-XX:StartFlightRecording`), which would otherwise run in both processes; the jar
says so when it sees one among the options.
On Linux and macOS:

```sh
ORT_DISABLE_TELEMETRY=1 java -jar altitude-<version>-<platform>.jar
```

In PowerShell, `$env:ORT_DISABLE_TELEMETRY = "1"` first.

## Stopping the jar

Ctrl-C in its terminal, or a SIGTERM to the `java` process you started (`kill <pid>` on Linux and macOS), stops it as
[Stopping](README-DOCKER.md#stopping) in the Docker guide describes for the container: it refuses new requests, lets
those under way end, and imports the files it has already accepted before it closes the database. Anything that stops it with a timeout of its own, such as a
service manager, must allow three times the Pipeline shutdown timeout Setting plus 30 seconds: 120 seconds at the
Setting's default.

On Windows, Ctrl-C in the jar's window is the one stop that drains: nothing outside the window can ask it to.

## Building the jars

With the JDK of [Development setup](README.md#development-setup), from a clone of this repository:

```sh
make dist-jars
```

It builds the four jars, `out/dist-jars/altitude-<version>-<platform>.jar`, whichever machine it runs on; copy the one
for your system to the machine it is to run on. `<version>` is `publishVersion` in `build.mill`. Windows has no `make`:
there, `.\mill.bat altitude.platformJar` builds the Windows jar alone, into `out\altitude\platformJar.dest\`.
