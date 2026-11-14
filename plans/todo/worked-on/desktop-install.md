# Desktop install: one-click installers, First run, and the release pipeline

## Goals

- A person gets Altitude from the Microsoft Store on Windows and from the GitHub release page elsewhere, installs it the
  way their system installs apps, and starts Altitude from the Start menu, Applications, or their Linux desktop's app
  menu. No Java, Docker, terminal or config file is needed.
- Packages for Windows x64 (an MSIX through the Microsoft Store, which runs with Smart App Control on), macOS on Apple
  Silicon, Linux x64 and Linux arm64. Windows on ARM runs the Windows x64 package under the system's x64 emulation.
- Starting Altitude starts the server and opens the browser on it. A tray (menu bar) icon shows it is running, opens it
  again, opens the data folder and the logs folder, and quits it cleanly.
- Starting it again while it runs opens the browser on the running server, never a second server.
- The first start opens First run in the browser: it downloads the three models (827 MB) with progress, asks where the
  Data folder goes, and hands over to Setup.
- A new version opens the Data folder of an older one and migrates its schema on start, after copying the database aside.
- No fees: the Store's registration is free and the Store signs the Windows package; the macOS and Linux packages carry
  no paid signature, and the install docs give macOS's one-time approval step.
- A headless start (`--headless`, or a system with no display) runs the server alone, for Docker and servers.

Out of scope: an update check in Altitude (the Store updates the copies it installed); Intel Macs; a native Windows on
ARM build; start at login; moving the Data folder from the UI; PostgreSQL on a desktop install (still reachable by editing
the config file); package managers (Homebrew, winget, Scoop); the Docker image; macOS notarization and a paid Windows
signing certificate.

## How it works

**Two folders.** The App home is a per-user folder the launcher creates: `~/Library/Application Support/Altitude` on macOS,
`%LOCALAPPDATA%\Altitude` on Windows, `$XDG_DATA_HOME/altitude` (`~/.local/share/altitude`) on Linux. `ALTITUDE_HOME`
overrides it. It holds `application.conf`, `models/`, `logs/`, `instance.lock` and `instance.json`, and never a photo. The
Data folder holds the database, the Libraries' files and staging, as `fs.data.dir` does in dev. First run chooses it and
writes its absolute path as `fs.data.dir` into the App home's `application.conf`. An App home has one Data folder.

**The environment.** `Environment.CURRENT` comes from the `altitude.env` system property, then the `ENV` variable, then
prod; a value it does not recognise is prod. The packaged launcher passes `-Daltitude.env=prod`, so an `ENV` the person's
shell sets (POSIX `sh` reads its startup file's path from `ENV`) never puts an installed app in dev. In prod
`Environment.ROOT_PATH` is the App home (the `altitude.home` property the launcher sets) and the config is
`<App home>/application.conf` over `reference.conf`. Dev and test keep the working directory as the root and read their
configs as they do.

**Models.** The classpath carries one model, YuNet (233 KB). ArcFace (`w600k_r50.onnx`, 174 MB) and the two Scene models
(`dinov2_vitb14.onnx`, `clip_vitb16_image.onnx`, about 330 MB each) live in a models folder: `<App home>/models` in prod,
the repository's `models/` (git-ignored) in dev, and `ALTITUDE_MODELS_DIR` under test, set in the test module's `forkEnv`.
`Environment.modelPath(file)` gives a model's path, and ArcFace and the Scene models load from it in place, never copied.
`altitude/resources/models.json` lists each model's file name, size, SHA-256 and URL. ArcFace's URL is its pinned Hugging
Face revision; the Scene models' are assets of the `models-1` release of `papito/altitude`. A model file sits under its
final name only once its checksum has passed, so a start checks presence and size alone. The server's rule holds: a missing
model fails the start. First run gets the models before the server starts.

**Natives.** A packaged app carries its native libraries unpacked in its `native/` folder: JavaCPP, OpenCV, FFmpeg and
OpenBLAS, ONNX Runtime, sqlite-jdbc and sqlite-vector, for its one platform. The JVM options point each library there
(`onnxruntime.native.path`, `org.sqlite.lib.path` and `org.sqlite.lib.name`, JavaCPP's load path, and `altitude.native.dir`
for sqlite-vector), so nothing is written to the temp folder or `~/.javacpp` at runtime. Dev and test load natives from
the jars as they do. The packaged ONNX Runtime jar holds no natives and no `.dSYM` folders. On Windows every file the app
runs or loads is inside its MSIX, so the package's signature covers it; the runtime extracting a library to a folder
outside the package would be blocked by Smart App Control.

**The launcher.** `altitude.desktop.Launcher` is the packaged main class; `make run` and `make watch` keep
`altitude.core.App`. In order, it:

1. Resolves the App home, creates it, and sets `altitude.home` and the logs folder before any logger exists. The packaged
   JVM options name `logback-desktop.xml`, which writes `<App home>/logs/altitude.log`, rolled daily, 14 days kept.
2. Takes `instance.lock` (`FileChannel.tryLock`). When another process holds it, it reads `instance.json` (the address),
   waits up to 30 seconds for `/api/health`, opens the browser there and exits. If the health route never answers, a native
   dialog names the log file.
3. Unless headless, puts up the tray icon (AWT `SystemTray`): Open Altitude, Open data folder, Open logs folder, Quit. Where
   the system tray is unsupported (GNOME without the AppIndicator extension), a small window says "Altitude is running at
   <address>" with Open and Quit. On macOS, opening the app from the Dock while it runs (`AppReopenedListener`) opens the
   browser, and Quit from the Dock (`QuitHandler`) is the same quit.
4. Picks the address from `server.host` (default `localhost`) and `server.port` (default 8080). On `localhost`, a port in
   use moves to a free one. On any other host, a port in use fails the start with a dialog, since LAN clients have the
   address bookmarked. It writes the address to `instance.json`.
5. Starts First run when the config names no Data folder or a model is missing. Otherwise it starts the app.
6. To start the app, it builds the Undertow server over `App.defaultHandler` on the address, holding the server so that it
   can stop it, waits for `/api/health`, and opens the browser. A start that throws shows a native dialog with the error's
   message and the log file's path, then exits.
7. On Quit (tray, Dock, status window, or the JVM's shutdown hook on SIGTERM or logoff), it stops taking requests, runs
   `Altitude.cleanup()` (Watch folder scans stop, the pipelines finish what they accepted up to their limit, renders queue
   again for the next start, the pools close), releases the lock and exits.

`App` takes `host` and `port` from the config, and `debugMode` is on in dev alone, so a prod error answers without a stack
trace.

**First run.** `altitude.desktop.FirstRunRoutes` is a small set of Cask routes the launcher serves on the address. Their
listener is bound to `localhost` whatever `server.host` says, and they refuse a POST whose `Origin` is not their own. The
page uses the app's layout and stylesheet bundle, and has two parts.

- **Models.** Downloading starts when the page is served, one file at a time, into `models/<file>.part`. It resumes with
  HTTP range requests and goes through the system's proxy (`java.net.useSystemProxies=true`). Each file is checked against
  the manifest's SHA-256 and renamed into place; a mismatch deletes the part and fetches it again once. The page polls
  `/first-run/status` every second for each file's bytes and rate. A failure shows its reason and Retry, and the manual
  route: the files' URLs and the folder to put them in, which the page checks again on Retry.
- **Data folder.** A path field starts at `Pictures/Altitude` in the person's home (the XDG pictures folder on Linux).
  Browse… opens the system's folder dialog on the desktop the server runs on and fills the field: a macOS `FileDialog`
  with `apple.awt.fileDialogForDirectories`, a directories-only `JFileChooser` elsewhere. The field also takes a typed
  path. The folder must be absolute, exist or be creatable, and take a probe file written and deleted. It cannot be inside
  the installed app or the App home, and cannot be on a network file system (a UNC path, or a file store of type smbfs,
  nfs, cifs, afpfs, webdav or fuse.sshfs), since SQLite needs local file locks. A folder that is not empty is refused
  unless it is a Data folder (`db/altitude.db` in it), which is then used as it is. The page shows the free space.

Continue is enabled once both parts are done. It writes `fs.data.dir` into the config, stops the First run listener and
starts the app on the same address. The page polls `/api/health`, then goes to `/`: Setup for a new Data folder, the login
for an existing one. The download runs while the folder is chosen; closing the browser does not stop it, and Open in the
tray brings the page back.

**Upgrades.** A new version starts on an existing Data folder like any start. Before `MigrationService` runs a
`<version>.sql` on a SQLite database, it checkpoints the WAL and copies the database to `db/backups/altitude-v<old>.db`,
keeping the newest three. A database whose version is newer than the app's fails the start with "This Data folder was used
by a newer version of Altitude", which the launcher shows. A PostgreSQL database is not copied; the docs give `pg_dump`.

**Distribution inputs.** For each platform (`linux-x86_64`, `linux-arm64`, `windows-x86_64`, `macosx-arm64`), Mill builds
`out/altitude/dist/<platform>.dest/`. It holds `lib/` (the app's jar and the dependency jars, with natives stripped),
`native/`, `jvm-options`, `modules` (the jlink module list) and the icons. The platform comes from `ALTITUDE_PLATFORM`, or
the build machine when unset, and the bytedeco classifier jars, the ONNX Runtime natives and the sqlite-vector build
follow it. Either packaging option consumes these folders.

**Runtime.** Each installer bundles a Temurin 21 runtime trimmed with jlink. The module list is `jdeps --print-module-deps`
over the classpath plus the modules loaded by reflection (`java.desktop` for the tray, dialogs and ImageIO, `java.net.http`,
`jdk.unsupported`, `java.naming`, `java.management`, `jdk.charsets`, `jdk.crypto.ec`, `jdk.zipfs`), confirmed by the smoke
run.

**Packaging, Option A: Conveyor.** `conveyor.conf` at the repository root. One Linux runner builds the four platforms'
inputs and runs Conveyor. Conveyor makes the Windows MSIX for the Store with the Store's identity, a zip of the Windows
app, the macOS zip for Apple Silicon, and the Linux `.deb` and `.tar.gz` for x64 and arm64, plus a download page, and
publishes all but the MSIX to the GitHub release. The macOS app is self-signed, an ad-hoc signature, which Apple Silicon
requires to run at all. `app.vcs-url` names the public repository, which gets the free open-source key, and the download
page and the README credit Conveyor, as that key's terms require. Conveyor's update checks are off. The Conveyor root key
is generated once, kept as a repository secret and backed up offline.

**Packaging, Option B: jpackage and JReleaser.** A GitHub Actions matrix builds each installer on its own system:
`ubuntu-24.04` and `ubuntu-24.04-arm` (`.deb`, `.rpm`, `.tar.gz` for x64 and arm64), `windows-2025` and `macos-15`
(Apple Silicon `.dmg`). On Windows, jpackage makes the app image and the `.msi` for the GitHub release (with the WiX version
the jpackage in use supports), and the Windows SDK's `makeappx` packs the same app image with
`packaging/windows/AppxManifest.xml` into the MSIX for the Store. jpackage ad-hoc signs a macOS app bundle when no signing
identity is given. Its `--java-options` point the natives at `$APPDIR/native`, and `--app-content` adds the
`native/` folder to the app. A final job runs JReleaser (`jreleaser.yml`): the GitHub release from the tag, SHA-256
checksums, a changelog from the commits and a CycloneDX SBOM.

**Windows: the Microsoft Store.** Either option's MSIX carries the identity reserved in Partner Center (the package name
and the publisher's `CN`), the `runFullTrust` capability, and file system write virtualization off
(`desktop6:FileSystemWriteVirtualization` disabled, with the restricted `rescap:unvirtualizedResources` capability, which
the submission justifies). With virtualization off, the App home is the real `%LOCALAPPDATA%\Altitude`: Open logs folder
shows Explorer the real files, and an uninstall leaves the App home and the Data folder in place. The Store signs the
package when it publishes it, so Smart App Control and SmartScreen trust every file in it. The release workflow submits the
MSIX with the Microsoft Store Developer CLI (`msstore publish`), under Partner Center credentials kept as repository
secrets, first to a package flight for a private audience and, once that copy passes, to everyone. The listing has its
description, screenshots, age rating and a privacy policy (`docs/privacy.md`, published). A submission waits for
certification, up to three business days. The Store updates installed copies once a version is published; Altitude itself
checks for nothing. The GitHub release also carries an unsigned Windows build, for PCs without the Store.

**Install docs.** The README's Install section covers each system:

- macOS: open the download, drag Altitude to Applications and open it. macOS says it cannot verify the app; System
  Settings, Privacy & Security, Open Anyway, once. The Terminal alternative is
  `xattr -dr com.apple.quarantine /Applications/Altitude.app`.
- Windows: get it from the Microsoft Store; a Windows on ARM PC runs the same package under emulation, with imports
  slower than on a native build. The unsigned build on the GitHub release is for PCs without the Store: SmartScreen says it
  protected the PC (More info, Run anyway, once), and it runs only with Smart App Control off.
- Linux: install the `.deb` (or the `.rpm` under Option B) with the system's installer, or unpack the `.tar.gz`.
- First run needs the internet once, for 827 MB, or the files put in the models folder by hand.
- Sharing on the LAN: `server.host = "0.0.0.0"` in `<App home>/application.conf`, then a restart; HTTPS through a reverse
  proxy as the readiness notes give it.

## Decisions

- **Option A or B is chosen before group E.** Option A builds and publishes all four platforms from one Linux runner, makes
  the Store's MSIX itself, and makes a download page. Its free key comes with a credit requirement, its terms are not
  promised for ever, and it adds a key that must not be lost. Option B uses free tools alone, but it needs four runners,
  packs the MSIX with `makeappx` from a manifest of its own, and has more workflow to keep up.
- **The Data folder is chosen at First run, before Setup.** The database lives in it and `Altitude` opens the database
  when it is built. A separate First run server leaves Altitude's startup rules as they are: the database is there and the
  models are present before it starts.
- **The models download in First run, in the browser.** The whole first start is one page; the launcher shows native
  dialogs only for errors.
- **The Scene models are GitHub release assets.** Each file is far under GitHub's 2 GiB limit per asset, a release has no
  limit on total size or bandwidth, and assets of a public repository need no token. The account's plan does not matter,
  and Git LFS quotas do not apply to release assets. The `models-1` tag is versioned apart from the app, so a release of the
  app does not upload the models again.
- **ArcFace is fetched from its pinned Hugging Face revision, not hosted by Altitude.** InsightFace releases its pretrained
  models for non-commercial research only, so Altitude does not redistribute it.
- **Windows goes through the Microsoft Store.** Smart App Control runs an app only when every executable and DLL it loads
  is signed by a certificate authority in Microsoft's Trusted Root Program; a self-signed certificate does not count, and
  there is no exception for one app. Of the Windows natives, only ONNX Runtime's DLLs and the Microsoft C++ runtime DLLs in
  JavaCPP's jar are signed. OpenCV, FFmpeg, OpenBLAS, JavaCPP's JNI library, sqlite-jdbc and sqlite-vector, about 110
  files, are not. The SignPath Foundation's free certificate signs a project's own binaries alone, never upstream ones. The
  Store signs the whole MSIX with one catalog signature over every file, and its registration is free. Azure Artifact
  Signing (about $10 a month) is the paid way to sign each file and keep Windows installers on GitHub, without review.
- **No paid signing on macOS.** The Apple Developer Program is the only route to notarization; without it, macOS asks for
  Open Anyway once.
- **Thin jars and unpacked natives, not a fat jar.** Nothing is extracted at runtime, a start reads no 800 MB from inside a
  jar, and each installer carries one platform's natives.
- **No Intel Mac build.** macOS 27 is the last release for Intel Macs, and ONNX Runtime has shipped no Intel Mac natives
  since 1.24.
- **Windows on ARM runs the x64 build under emulation (Prism).** No Windows ARM build of OpenCV, FFmpeg or OpenBLAS is
  published (JavaCPP's presets up to 1.5.14), nor of ONNX Runtime's Java natives or sqlite-vector. A native build would mean
  compiling those three libraries and ONNX Runtime for Windows ARM.
- **Linux arm64 is a native build.** Every native library has a Linux arm64 build, and so does Temurin.
- **The tray is AWT, with a status window where no tray exists.** No new dependency.
- **The default host is `localhost`.** A desktop install is private until its owner opts into the LAN.
- **Altitude has no update check.** The Store updates the copies it installed when a version is published, and the
  migration copy covers the database; elsewhere a release is a new installer the person downloads.
- **The runtime is Temurin 21**, the JDK the project builds and tests on. Group F is the move to 25.

## Constraints

- The First run page follows [altitude/views/AGENTS.md](../../../altitude/views/AGENTS.md). App home, Data folder and First run
  go into [CONTEXT.md](../../../CONTEXT.md).
- From the first release on, a schema change ships as `<version>.sql` with the schema version raised; `all.sql` alone serves
  only a new database.
- The First run listener never binds beyond `localhost`.
- A packaged app writes nothing inside its install folder.
- `make run`, `make watch` and the tests behave as they do: the working directory is the root, logs go to the console, and
  the models come from `models/`.
- `.github/workflows/`, the `models-1` release and every Store submission are outward-facing: the owner creates them, or
  gives the go-ahead. The Partner Center account and the app's name reservation are the owner's.
- On Windows, nothing executable is loaded from outside the package.

## Changes

### A. The environment, the folders and the server's lifecycle

- `Environment.scala`: `altitude.env` before `ENV`, an unrecognised value is prod; `ROOT_PATH` in prod is `altitude.home`;
  `modelPath(file)` and `nativeDir`. `resolveResourcePath` remains for YuNet, and for sqlite-vector where no native folder
  is set.
- `Altitude.scala`: prod reads `<ROOT_PATH>/application.conf`; an absolute `fs.data.dir` is used as it is.
- `reference.conf`: `server.host` and `server.port`.
- `App.scala`: `host`, `port` and `debugMode` overrides.
- `MigrationService.scala`: the SQLite copy before a stepwise migration, keeping three; the refusal of a newer database.
- `FaceDetectionService.scala`, `SceneEmbedder.scala`: model paths from `Environment.modelPath`.
- `TransactionManager.scala`: sqlite-vector from `Environment.nativeDir` when set.
- Tests: the environment's selection (each `ENV` value, the property first), the prod config path, the migration copy and
  the newer-database refusal (`StartupTests`), the model paths.

### B. The models

- `altitude/resources/onnx/*` and `../../../altitude/resources/opencv/w600k_r50.onnx` move to `models/` at the repository root
  (git-ignored); YuNet stays on the classpath.
- `altitude/resources/models.json`, the manifest.
- `../../../scripts/fetch-models.py` writes to `models/`, checks each file against the manifest, and with `--release` prints the
  Scene models' SHA-256 and sizes for the manifest and the `gh release create models-1` command.
- `../../../build.mill`: the test module's `forkEnv` sets `ALTITUDE_MODELS_DIR`.
- `altitude/src/altitude/desktop/ModelDownloader.scala`: resume, checksum, rename into place, progress, the system proxy.
  Its tests run against a local `com.sun.net.httpserver` server: an interrupted transfer resumes, a bad checksum fetches
  again once and then fails, a file in place is not fetched.
- README: the Models section names `models/`.

### C. The launcher and First run

- `altitude/src/altitude/desktop/`: `Launcher.scala`, `AppHome.scala` (each system's folders), `InstanceLock.scala`,
  `Tray.scala` (tray and status window), `FirstRunRoutes.scala`, `DataFolderCheck.scala`.
- `altitude/views/first_run.scala.html` and its progress partial; its styles under `../../../altitude/static/css`.
- `altitude/resources/logback-desktop.xml`.
- The quit path runs `Altitude.cleanup()`, from the tray, the Dock and the shutdown hook.
- Tests: `DataFolderCheck`'s rules; a second process on a held lock; `FirstRunRoutes` (the `Origin` check, the folder
  answers, the status); the launcher's choice of First run (no Data folder, a missing model) or the app.
- Makefile: `make run-desktop` runs the launcher with `ALTITUDE_HOME=out/desktop-home`.

### D. The distribution inputs and the runtime

- `../../../build.mill`: the platform from `ALTITUDE_PLATFORM`; ONNX Runtime by platform; a `dist` task writing `lib/`, `native/`
  (unpacked from the classifier jars and the ONNX Runtime jar), `jvm-options`, `modules` and the icons; the ONNX Runtime jar
  repacked without natives.
- `make dist` (with `PLATFORM=`) replaces `make publish`.
- `scripts/smoke-dist.sh`: runs the dist folder on its jlinked runtime, headless, with a scratch App home and the
  repository's models, waits for `/api/health`, imports the test photos through the API, quits, and fails if anything was
  written to the temp folder or `~/.javacpp`.
- `packaging/icons/`: `altitude.icns`, `altitude.ico`, `altitude.png`.

### E. Packaging and the release (one option)

- Option A: `conveyor.conf`, with the Store identity and the manifest's capabilities and virtualization setting;
  `.github/workflows/release.yml` on a `v*` tag, one Linux runner; the Conveyor root key as a secret; the Conveyor credit on
  the download page and in the README.
- Option B: `.github/workflows/release.yml` on a `v*` tag, the four-system matrix and the JReleaser job; `jreleaser.yml`;
  the jpackage arguments per system (name, vendor, icons, `--java-options` from `jvm-options`, `--app-content native`);
  `packaging/windows/AppxManifest.xml` and the `makeappx` step.
- Both: the `msstore publish` step, flight first, with the Partner Center credentials as secrets; the Store listing's text
  and screenshots; `docs/privacy.md`; the README's Install section; `../../../docs/READINESS.md`'s install item; the build commands in
  [altitude/AGENTS.md](../../altitude/AGENTS.md).

### F. Optional: Java 25 with an AOT cache

- Build and test on JDK 25 (Scala 3.6.3, Mill, scalafix), with `--enable-native-access=ALL-UNNAMED` for the JNI libraries.
- A training run at packaging time (start on a seeded Data folder, import the test photos, quit) with `-XX:AOTCacheOutput`,
  and the installer starting with `-XX:AOTCache=$APPDIR/altitude.aot`.
- Kept only if the time to `/api/health` drops measurably on each system.

## Verification

- `make test`, `make test-psql` and `make test-js` pass.
- Smart App Control, before the first public submission: the Store-signed package from the private flight installs on a
  clean Windows 11 with Smart App Control on, runs First run, imports photos with faces and a video and runs the Scene
  models. No "Smart App Control has blocked part of this app" notice appears, and the
  Microsoft-Windows-CodeIntegrity/Operational log holds no block event. The same on Windows 11 on ARM.
- macOS, before group E under Option A: Conveyor's self-signed zip opens on an Apple Silicon Mac by the README's steps
  alone.
- On each system (Windows 11 x64 and on ARM from the Store flight, Windows 11 x64 from the GitHub build with Smart App
  Control off, macOS on Apple Silicon, Ubuntu 24.04 on x64 and on arm64, and Fedora for the `.rpm` under Option B), install
  by the README's steps and start:
  - First run opens in the browser. The download survives a network cut and resumes; a corrupted part is fetched again.
  - Browse opens the folder dialog. A network share is refused, a non-empty folder is refused, an existing Data folder
    opens to the login.
  - Setup, then an import of photos with faces: faces are found and the Scene models run.
  - Quit from the tray, start again: no Import is left in progress and the data is intact.
  - A second start opens the browser on the running server. With 8080 taken, the server moves to a free port.
  - Nothing is written in the install folder; neither the temp folder nor `~/.javacpp` holds Altitude's natives or models.
  - On Windows from the Store, Open logs folder shows the log file in Explorer, and an uninstall leaves the App home and the
    Data folder.
- Upgrade: install release N and import photos; install N+1 with a schema version raised: the copy is in `db/backups/` and
  the data is intact. Reinstall N: the start is refused with the newer-database dialog.
- With `ENV` set to a path in the environment, the installed app runs as prod (`/style-guide` answers 404).
- On Windows on ARM, the smoke run passes under emulation, and the time of an import of the test photos is recorded
  against Windows x64.
- Each installer's size is recorded in the release notes.
