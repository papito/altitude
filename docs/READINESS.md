# Readiness for live use

What a self-hosted digital asset manager like Altitude (images and video; tens of thousands of assets; repositories with View, Edit and Admin roles) needs before live use, and where the code stands on each item. It covers security, data safety and operations, not the product feature set.

"Ready for live use" comes down to two things:

1. Strangers, signed-in users with limited roles, and uploaded files can't get what they shouldn't.
2. People's photos and videos, which often can't be replaced, don't get lost.

Losing data counts as seriously as a security breach.

## Deployment

Altitude targets:

- **Self-hosted, on a LAN or VPN only**, not exposed to the public internet. Login rate limiting and 2FA drop to Strongly preferred; authorization and file hardening do not.
- **A semi-pro user who shares with clients.** Share links, the audit log and roles per repository are must-haves.

The two pull against each other: a client cannot reach a LAN/VPN-only server. Either clients join the VPN (Tailscale node sharing, for example), which makes them real accounts and puts [User management](#user-management) and [Permissions](#permissions) on the critical path, or only the share-link path is exposed (Tailscale Funnel, Cloudflare Tunnel), which puts that one path on the internet. The second is less work and the usual choice.

## How to read this

- ✅ holds in the code. `[ ]` is a gap, with its severity where one is assigned: **CRITICAL**, **HIGH**, **MEDIUM** or **LOW**.
- `(unconfirmed)` marks a finding that the code makes plausible but nobody has demonstrated.
- `Fix:` gives the recommended fix first, then newer alternatives. `Effort:` is S, M or L.
- Evidence names files and symbols, not line numbers. A change that closes or opens an item updates it here. Gaps in tests of existing behavior are tracked in [test-coverage.md](test-coverage.md).

## Scorecard

Graded 2026-10-11 at commit `a041215d`, from reading the code; no tests were run. The grades change only with a new audit, not as items close.

| Grade | Score |
|---|---|
| **Ready for live use** (the must-haves) | **3 / 10** |
| **Engineering quality** of what is built | **7 / 10** |

The core is excellent: search, the import pipeline, crash recovery, content serving and the tests. What is missing is most of what live use needs around it: managing users, sharing, backup, upgrades and installing. A few real defects could lose photos or let one user act on another's data. For a single owner on a LAN with no clients, readiness would be about 5.

| Area at grading | Score | Summary at grading |
|---|---|---|
| [Web security](#web-security) | 6 | Output escaping, SQL and content serving are strong. One stored XSS, and no security headers on pages. |
| [Video](#video) | 5 | Import and seeking are excellent. Only H.264 plays everywhere, and a video that cannot play fails with a snackbar. |
| [Login and sessions](#login-and-sessions) | 4 | bcrypt, no timing leak, strict cookies. A session cannot be revoked, there is no account recovery, and two setups at once can race. |
| [Permissions](#permissions) | 4 | Content, search and map are scoped to the repository and tested. The base service layer reaches entities by bare ID, which another account could exploit once one exists. |
| [Malicious files](#malicious-files) | 4 | Types come from the content and storage paths are UUIDs. No size caps, no image-size cap, decoders in the server process. |
| [Data safety](#data-safety) | 3 | Crash recovery is excellent. A 32-bit duplicate hash, no schema migrations in use, no backup story. |
| [Running and upgrading](#running-and-upgrading) | 3 | Search benchmarked at 1M assets. No Docker image, no releases, accepted uploads lost on restart, stack traces in errors. |
| Strongly preferred: data and operations ([Library and media](#library-and-media), [Operations and hygiene](#operations-and-hygiene)) | 3 | Time handling is good. No export, no mobile layout, no HEIC. |
| [User management](#user-management) | 2 | Only first-run setup creates an account, and roles are not enforced. |
| Strongly preferred: identity, sharing and hygiene ([Identity and access](#identity-and-access), [Sharing with clients](#sharing-with-clients), [Operations and hygiene](#operations-and-hygiene)) | 2 | The URL-signing code is solid, but nothing creates share links. No CI, SECURITY.md or LICENSE file. |

On a separate scale, how well the design leaves room for the [optional](#optional--later) and [newer](#newer-options-worth-a-look) options: **6 / 10**. Clean extension points for the file store and queries; the design assumes one server process.

Foundations to build on, beyond the items below:

- Clean layering: a store trait, one query funnel ([SearchQueries] `matching`), repository scoping in the queries, and a Scalameta test ([TransactionBoundaryTests]) that enforces transaction boundaries.
- The same code on SQLite (WAL, sqlite-vector) and PostgreSQL 18, with snapshot-consistent reads under time limits.
- pgvector HNSW over `halfvec` as a partial index on enrolled faces, with `iterative_scan = relaxed_order`; ArcFace quality tiers.
- Keyset cursors with search fingerprints and capped counts.
- WebP previews with exact alpha; htmx 4, with vendored libraries listed with their provenance.
- A typed Settings registry with Live and Restart settings, so tunables live on an Admin screen rather than in config files.

---

## Must-have

### Login and sessions

- ✅ Passwords are hashed with bcrypt at cost 10 ([Util] `hashPassword`).
- [ ] bcrypt rather than Argon2id, through jBCrypt 0.4 (2015, unmaintained). The 50-character limit ([Api] `MAX_PASSWORD_LENGTH`) is tight for passphrases, and a non-ASCII password can pass bcrypt's 72-byte cutoff.
  - Fix: Argon2id through Bouncy Castle (already a dependency), rehashing on the next login.
- ✅ A login for an unknown email checks a dummy hash of the same cost, and both failures answer the same 401, so accounts cannot be enumerated ([UserService] `loginAndSetUser`, [LoginControllerTests]).
- ✅ Session cookies are `HttpOnly`, `Secure` and `SameSite=Strict`, identical everywhere they are set ([SessionController], [SetupFormController]). Login issues a new token. Tokens are PASETO v2.local: encrypted and authenticated, with no algorithm confusion. Logout is POST-only.
- [ ] `Secure` is always on, so plain-HTTP LAN access (`http://nas:8080`) never gets a cookie and login loops. Nothing documents this.
  - Fix: document HTTPS through a reverse proxy (see the install item under [Running and upgrading](#running-and-upgrading)).
- [ ] Sessions cannot be revoked. A token lives 7 days ([Const] `MEMBER_ME_COOKIE_EXPIRATION_DAYS`), and the user and role are rebuilt from its claims without reading the database ([PasetoService] `validateTokenAndGetUser`). Logout only clears the cookie ([UserService] `logout` is a no-op), so a copied token, or a deleted or demoted account, keeps working for up to 7 days. There is no idle timeout or sliding renewal. The key is random per process ([PasetoService] `secretKey`), so every restart logs everyone out. [HIGH]
  - Fix: server-side sessions: a `session` table of random IDs stored hashed, with `last_seen` for idle expiry and an absolute cap. Revoke on logout, password change, role change and deletion, and load the account from the database per request. This is also the base for personal API tokens, "log out everywhere" and more than one server process.
- ✅ The setup form is refused once the instance is initialized ([SetupFormController]).
- [ ] Setup can be won by whoever reaches a fresh instance first: there is no setup token. A check-then-act race on an in-memory flag lets two concurrent submissions create two admins; the flag flips before the commit ([SystemService] `initializeSystem`, [SystemMetadataDao] `setInitialized`). The setup test is commented out ([test gap](test-coverage.md#user-creation-and-password-authentication)). [MEDIUM]
  - Fix: a one-time setup token printed to the log on an uninitialized start; atomic initialization (`UPDATE … WHERE is_initialized = false`, checking the row count).
- [ ] No account recovery. There is no admin reset, no self-service password change, and no CLI or env-var way in; recovering a locked-out Admin means editing the bcrypt hash in the database. [CRITICAL]
  - Fix: a CLI (`java -jar altitude.jar reset-admin-password <email>`) or a startup env var that resets an Admin's password; the admin reset and self-service change under [User management](#user-management).
- [ ] The dev login bypass works in any environment. [UserService] `getDevUser` reads `dev.user` and `dev.password` directly, not through `Environment.devSwitch` like every other `dev.*` key, and `requireLogin` checks it before the token ([decorators] `requireLogin`), so with those keys set every request is that user. A misspelled or unknown `ENV` falls back to dev ([Environment] `CURRENT`), which also reads `application-dev.conf`. [HIGH]
  - Fix: read `dev.user` through `devSwitch`; refuse to start on an unknown `ENV`.
- ✅ The post-login redirect is validated and unit-tested.
- [ ] `redirect` is decoded twice ([SessionController] `decodedRedirect`), so `/%2509/evil.com` becomes `/\t/evil.com`, which passes the check; browsers drop the tab and land on `//evil.com`. (unconfirmed) [LOW]
  - Fix: decode once and reject control characters.
- [ ] Login CSRF is not prevented. [LOW]

Login rate limiting is under [Identity and access](#identity-and-access).

### Permissions

As built, each repository has one owner (`repository.owner_account_id` in [all.sql]) and there is no membership table. `AccountType` is Admin, User or Guest and gates only the Settings screen. Today the app has one user and one repository, so the cross-repository holes below become reachable only once a second account exists.

- ✅ One login and ownership gate for `/r/:repoId` ([decorators] `requireLogin`, `ownedRepositoryOnly`); a foreign repository answers the same 404 as a missing one. The request context is cleared on pooled threads ([decorators] `repoContext`).
- ✅ Routes without login are deliberate: `/`, `/login`, `/logout`, `/api/logout`, `/setup`, `/htmx/setup` (refused once initialized), `/api/health`, `/static/` and `/assets/:name`. The exception is the import-status WebSocket below.
- [ ] Entities fetched by ID are not scoped to the repository. [BaseService] `getById`, `updateById` and `deleteById`, and [BaseDao] `getById`, have no repository predicate. Once a second account exists, its owner, given another repository's IDs, can: [CRITICAL]
  - read **asset detail** (`…/modals/asset-detail/:id`) as full JSON, including `extractedMetadata` and GPS;
  - on **people**, read the name and the edit-name, cover-face and merge modals; edit the name (ID taken from the body), hide, show, mark as a bad match and set the cover; and merge, which moves the other repository's faces through an unscoped `faceDao.updateByQuery` ([PeopleActionController], [SearchResultsController] `personOf`, [FaceDao] `getTopFaces`, [PersonService] `merge`);
  - on **folders**, open the rename and delete dialogs ([FolderActionController]), rename and move ([FolderService] `move`), and add under a foreign parent. Deleting a foreign folder is a no-op, because `updateByQuery` adds the repository;
  - on **albums**, hard-delete ([AlbumService] `deleteById`) and remove memberships ([AlbumDao] `removeAssets`); reading by ID, renaming and adding to a foreign album are closed by the scoped `AlbumService.getById`;
  - move assets into a foreign folder: the destination is not checked ([LibraryService] `moveAssetsToFolder`).

  Fix: scope by default: `BaseService.getById`, `updateById` and `deleteById` add the repository the way `query` and `updateByQuery` already do. Scope `FaceDao.getTopFaces`, `AlbumDao.removeAssets` and `getAssetIds`, the face update in `PersonService.merge` and `FolderDao.getById`, and check destination folders. Locations are the model to copy: a scoped `getById`, foreign IDs answered 404, tested ([LocationServiceTests]).
- ✅ Originals, previews and faces sit behind the repository gate in a per-repository file layout ([FileSystemStoreService]), and are served `private`, `nosniff` and `sandbox` ([ContentViewController] `withContentHeaders`). The folder layout protects them, not the query: the `asset.getById` in `getContent` is unscoped, which the item above closes.
- ✅ Search results, counts, map pins and GPS all read one `matching` relation ([SearchQueries]); name resolution is scoped ([SearchDao] `searchNames`); tested ([SearchMapTests]). Facets and autocomplete do not exist.
- ✅ People lists are scoped ([PersonDao]).
- ✅ Bulk asset actions use scoped locking selects ([AssetControllerTests], [LibraryServiceRecycleTests]). Exports and zip downloads do not exist yet (see [Library and media](#library-and-media)).
- [ ] Background-job results: the import-status WebSocket (`@cask.websocket("/import/status")` in [ImportController]) has no `@requireLogin` and takes `userId` from the query string, so anyone with an account ID can watch that account's imports, file names included, from any origin. [MEDIUM]
  - Fix: put it behind `requireLogin`, take the user from the request context, and check `Origin` on the handshake. [Test gap](test-coverage.md#import-orchestration-and-asynchronous-processing).
- ✅ IDs are UUIDv4 ([BaseDao] `genId`). Hard to guess is not authorized, though: IDs travel in signed URLs, logs and the DOM, so the scoping item above is what blocks ID guessing.
- [ ] The permission matrix is not tested. Foreign-repository tests exist for content, search, map, asset recycling and Locations, but none for people, faces, albums, folders, asset detail or the WebSocket, and no test that every route requires login. [CRITICAL]
  - Fix: the [repository isolation](test-coverage.md#repository-isolation-and-shared-service-transactions), [albums](test-coverage.md#albums-and-membership) and [people](test-coverage.md#people-face-ownership-and-merges) gaps in test-coverage.md, including one table-driven test over `App.allRoutes`.
- [ ] Role rules (a repository can't lose its last admin, an admin can't demote themselves) have nothing to guard until roles exist; see [User management](#user-management).
- [ ] Removing access does not take effect at once: that needs server-side sessions ([Login and sessions](#login-and-sessions)) and revocable share links ([Sharing with clients](#sharing-with-clients)).
- [ ] Upload cancellation is a global map keyed by an ID from non-secure `Random` ([ImportController] `uploadCancelRequest`). The repository cache is never invalidated ([RepositoryService] `getById`). [LOW]

### Web security

- ✅ No GET route changes state, every auth cookie is `SameSite=Strict`, and JSON PUT and DELETE need a CORS preflight.
- [ ] No CSRF token and no `Origin` or `Sec-Fetch-Site` check. `SameSite` does not stop same-site sibling subdomains.
  - Fix: reject a non-safe method whose `Sec-Fetch-Site` is `cross-site`.
- ✅ Output is escaped. Twirl escapes by default and no template uses raw `Html(...)`; file names, people, albums and Locations render through `@{...}`. Client JS builds DOM with `textContent` and `createElement` ([album-list.js], [location-list.js], [map-view.js], the announcements of [snackbar.js]), and Alpine binds text with `x-text` (the snackbar's messages). EXIF is never rendered raw. Escaping has [no tests](test-coverage.md#asset-persistence-queries-and-previews).
- [ ] Stored XSS through a file name. [ImportStatusWsActor] formats the raw `fileName` into HTML (`wsContent`) that htmx swaps into the page ([pipeline.scala.html]), so a file named `<img src=x onerror=…>.jpg` runs script in the app's origin. [HIGH]
  - Fix: escape the ticker, or send JSON and render it with `textContent`; a controller test with a `<script>` file name.
- [ ] The Leaflet attribution Setting renders as HTML ([map-view.js] `attribution`), so an Admin could store script that runs for every user. [LOW]
- [ ] No security headers on pages. `nosniff` is set only on content ([ContentViewController]); nothing sets `frame-ancestors` or `X-Frame-Options`, so every page can be framed (clickjacking on Purge, Settings and setup); no `Referrer-Policy` (low impact, since browsers default to `strict-origin-when-cross-origin`); no HSTS, and nothing documented for the proxy. [MEDIUM]
  - Fix: one `securityHeaders` decorator in `mainDecorators` ([decorators]): `nosniff`; `Content-Security-Policy: frame-ancestors 'none'; object-src 'none'; base-uri 'none'`; `Referrer-Policy: same-origin`; `Cross-Origin-Opener-Policy: same-origin`; `Cross-Origin-Resource-Policy: same-origin` on `/content`; HSTS behind a setting. Tests for the headers and for framing.
- [ ] No strict CSP. Alpine 3.13.10 is the standard build, which evaluates expressions, and 4 templates have inline scripts.
  - Fix: the `@alpinejs/csp` build, inline scripts moved into modules or given nonces, `script-src 'self'` (all JS is self-hosted), later `require-trusted-types-for 'script'`.
- ✅ Content carries an explicit `Content-Type`: Tika detects it ([MetadataExtractionService] `detectAssetType`) and the stored MIME type is served ([ContentViewController]).
- ✅ SVG and HTML are not served inline, though only incidentally: `text/*` fails the media-type check ([LibraryService] `SUPPORTED_MEDIA_TYPES`), and SVG passes as `image/*` but no ImageIO reader takes it, so the import drops it.
- [ ] Nothing rejects SVG explicitly, and [no test](test-coverage.md#import-orchestration-and-asynchronous-processing) covers it.
  - Fix: reject `image/svg+xml` explicitly. For defense in depth, serve originals from a cookieless sandbox origin through the existing signed URLs.
- ✅ Content is `Cache-Control: private, max-age=31536000, immutable`, never `public`, and errors are not cached ([ContentViewController] `withContentHeaders`, tested in [ContentViewControllerTests]).
- ✅ SQL is parameterized: values are bound ([Columns] `literal`), column names are checked against the schema, sorts are whitelisted ([SearchResultsController] `parseSort`), and FTS queries are built from `SearchWords` output and bound.
- ✅ No `X-Forwarded-*` or `Host` header is read, so none can be spoofed. Running behind TLS is part of the install item under [Running and upgrading](#running-and-upgrading).

### Malicious files

- [ ] No size caps and no disk-space check. cask 0.9.7 leaves Undertow's `MAX_ENTITY_SIZE` at `-1` ([App] does not configure the server), and the upload form sends every selected file in one POST ([upload-form.js]). There is no per-file cap ([ImportController], [LibraryService] `stagedFileToAsset`) and no `getUsableSpace` anywhere. Undertow writes to `java.io.tmpdir` first: on tmpfs the upload fills RAM, and on another filesystem the move into staging is a copy. Upload threads wait with `Await.result(..., Duration.Inf)`. [HIGH]
  - Fix: Undertow `MAX_ENTITY_SIZE` and `MULTIPART_MAX_ENTITY_SIZE`, a per-file cap, Undertow's temp directory under `../data/staging`, and a free-space check against a reserve; a test with an oversized multipart upload.
- ✅ Types come from the content: Tika's `DefaultDetector` on the UUID-named staged file ([MetadataExtractionService] `detectAssetType`).
- [ ] Every `image/*` and `video/*` subtype is accepted, so the decoder surface is wider than needed: JPEG 2000, HDR, PxM, and JBIG2 and PCX (`tika-parser-image-module` brings `jai-imageio-core` and `jbig2-imageio` into the ImageIO fallback), plus every FFmpeg demuxer. The test media type `"x-none"` is in the production allowlist ([LibraryService] `SUPPORTED_MEDIA_TYPES`). [MEDIUM]
  - Fix: allow named formats only, run FFmpeg with a format whitelist, and drop the JBIG2 and JAI plugins.
- [ ] Pixel and decompression bombs. Dimensions are read from the header before decoding ([AssetService] `getDimensionsAndDuration`) but never limited. OpenCV's default limit is about 1 gigapixel, roughly 3–4 GB of native memory per decode, and each image is decoded twice (faces and preview) times `import.parallelism`. The ImageIO fallback, which decodes every GIF, is unbounded, and [AssetWithData] `bytes` reads the whole file. GIF frame count and video dimensions and duration are not limited either (video sampling is bounded to 120 frames). The stage guard catches only `NonFatal` ([PipelineUtils] `guard`), so an `OutOfMemoryError` probably leaves the stage's future incomplete and that repository's import hung (unconfirmed). [HIGH]
  - Fix: maximum pixels and side length as Settings, checked in [ExtractMetadataFlow]; header-only probing already happens, so the cap is a few lines. The same limits for video dimensions, duration and GIF frames. Set `OPENCV_IO_MAX_IMAGE_PIXELS` to match, size-check the ImageIO fallback, and decode once instead of reading the file twice. Tests with a large-canvas PNG, a huge GIF canvas and a GIF with millions of frames.
- ✅ No path traversal: staged files are named by UUID ([StagingService] `freshPath`), store paths come from server IDs, and non-UUID IDs are refused ([ContentViewController] `getContent`). File names never become paths. There is no archive or sidecar import.
- [ ] File names have no length cap.
- [ ] Decoders are not hardened. OpenCV and FFmpeg run in the server process through JNI with no timeout, so a crafted file can hang an import and a segfault kills the server. Video decoding is in software, with up to 120 seeks for faces plus up to 120 for the preview. FFmpeg opens files with every demuxer and protocol ([VideoService] `withGrabber`), not `protocol_whitelist=file`. For decoder versions, see [Dependencies](#operations-and-hygiene). [HIGH]
  - Fix: a per-asset timeout and `protocol_whitelist=file` now; then decoding in a separate worker process with time and memory limits (cgroups or rlimits, seccomp), where `PipelineUtils.stage` is the seam. Newer: libvips through its `vips` command, decoders compiled to WASM (Wasmtime, or Chicory on the JVM), gVisor for a decoder container.
- ✅ A file that fails to decode drops only that asset ([PipelineUtils] `guard`, [ImportPipelineServiceTests]), and the cleanup means a crash does not leave the import looping.
- [ ] A staged file leaks until restart if detection or hashing throws (the upload handler in [ImportController]). [LOW]
  - Fix: delete staged files in a `finally`.

### Data safety

- [ ] No real schema migrations. Until the first real data, schema changes go into `all.sql` and the database is recreated (the rule under "Schema migrations" in [altitude/AGENTS.md]). That has to end before anyone has real data, and every upgrade after it needs a tested, forward-only migration. [MigrationService] `migrate` can run `<version>.sql` files, but none exist and `schemaVersion` stays 2 ([Altitude] `schemaVersion`). A database built from an older `all.sql` is stamped 2, starts without complaint and then fails at runtime on missing columns. There are no upgrade tests and no snapshot before migrating. [CRITICAL]
  - Fix: freeze the schema and start `<version>.sql` files with a `schemaVersion` bump per change, rewriting that rule in altitude/AGENTS.md in the same change. Test the upgrade from the last release's database to head on both engines, refuse to start on a version mismatch, and snapshot before migrating (`VACUUM INTO`, or `pg_dump` behind a flag).
- ✅ Originals are never touched: moved into the store once ([FileSystemStoreService] `addAsset`), copied for a server-side import ([StagingService] `stageCopy`), and renamed only in the database. No metadata is written into files.
- ✅ Only a copy of the same file is reported as a duplicate. The checksum is the SHA-256 of the file, streamed once from the staged file ([Sha256] `hex` in [LibraryService] `stagedFileToAsset`) and stored as 64 lowercase hex characters, `checksum CHAR(64)` (`COLLATE "C"` on PostgreSQL), under a unique index on `(repository_id, checksum) WHERE NOT is_recycled` (`asset_01` in [all.sql]); [CheckDuplicateFlow] and [LibraryService] `restoreRecycledAssets` compare nothing else. A collision between different files is not practical, by chance or crafted, so a file reported as "Duplicate asset" has the same bytes as one in the repository, and the stored value is what `sha256sum` prints for the original.
- ✅ The duplicate check is streamed and scoped to the repository, and recycled copies do not block a re-import.
- ✅ Trash exists: [LibraryService] `recycleAssets`, a purge behind a confirmation ([trashbin_header.scala.html]), and folder deletion in one transaction (`deleteFolderById`).
- [ ] The trash has no retention, auto-purge or `recycled_at` column. Recycling assets or deleting a folder permanently removes Album and Location memberships, and restoring does not bring them back. Purge cannot be undone. [MEDIUM]
  - Fix: `recycled_at` plus a retention Setting and an auto-purge; keep memberships through recycling.
- [ ] No backup or restore story for the database, the originals, derived data or config. Originals are stored as `files/<id[0:2]>/<id>` with no name or extension ([FileSystemStoreService] `filePath`), and folders, albums, people, Locations and file names live only in the database, so a file backup without the database is a folder of UUIDs. The only restore is the dev [Makefile]'s `restore-db`, which copies `altitude.db.bak` without its `-wal`/`-shm` files. [CRITICAL]
  - Fix: a documented, tested backup, database first and then files: Litestream, or `sqlite3 .backup` / `VACUUM INTO`, for SQLite; pgBackRest or WAL archiving (at least `pg_dump -Fc`) for PostgreSQL; restic or Kopia for `data/repositories/*/files`; a job that regenerates previews and faces. Nothing yet coordinates a database dump with a file snapshot.
- ✅ Backups have a good base: the signing key lives in the database, so a database backup carries it, and write-once, ID-named originals suit deduplicating backup tools.
- ✅ A crash mid-import leaves consistent rows. Files leave staging by an atomic rename, each stage is a transaction, and `discardImport` undoes every side effect, face counts and orphaned people included. Startup empties staging, prunes unfinished imports ([LibraryService] `pruneDanglingAssets`), requeues purges (`requeuePurgePending`) and reconciles stats. All of it is tested on both engines ([LibraryServicePruneTests], [PurgePipelineServiceTests], [ImportPipelineServiceTests]). Purge is two-phase and deletes files before the row, so a crash cannot leave a row pointing at deleted files. Stats are adjusted in transactions under `CHECK (dim_val >= 0)`.
- [ ] The database and the files can drift apart. `discardImport` commits the row delete and then deletes the files. `File.delete()`'s result is ignored and its `catch IOException` cannot fire ([FileSystemStoreService] `purgeAssetById`, `purgeFaceById`), so a failed delete is swallowed without a log. Nothing scans for orphans, so files without rows and rows without files are never found. Nothing is fsynced, and with `synchronous=NORMAL` a power cut can leave a committed import without its file. [HIGH]
  - Fix: a reconciliation job over `files/`, `preview/` and `faces/` that flags orphans and missing files, at startup and on a schedule, with an Admin page; fsync for originals and their directories.
- ✅ A full disk during import drops the asset and cleans up: a `StorageException` from [FileSystemStoreService] (`addAsset`, `putBinaryData`).
- [ ] Derived files are not written to a temp file and renamed, and nothing checks free space before accepting work (see [Malicious files](#malicious-files)). [MEDIUM]
  - Fix: temp file plus `ATOMIC_MOVE` for derived files.
- ✅ `ClearDb` refuses to run outside dev.

Accepted uploads lost on restart are under [Running and upgrading](#running-and-upgrading).

### Running and upgrading

- [ ] No install path. There is no Docker image: [docker-compose.yml] is a dev PostgreSQL with `POSTGRES_PASSWORD=dba`. The [README] is dev-only, says a Docker runner will come later, and names `application-dev.conf.postgres`, which does not exist. `make publish` builds a fat JAR with the build machine's natives only ([build.mill]), and the 174 MB face model is downloaded by hand, so the JAR runs only on the platform that built it. Nothing documents running behind TLS or a reverse proxy. [HIGH]
  - Fix: a multi-arch image (`linux/amd64`, `arm64`) with natives per platform, a jlink-trimmed JRE on distroless or Chainguard (Jib, or Mill's Docker module), the model fetched with a checksum, compose with volumes and a backup recipe, and a `HEALTHCHECK`; install docs that cover HTTPS through a reverse proxy. GraalVM native-image is not practical with javacpp.
- [ ] Config is hard to set. cask's `host="localhost"` and `port=8080` are not overridden, so the server cannot bind `0.0.0.0` in a container or move port. Environment overrides (`systemEnvironmentOverrides()`) apply only to tests ([Altitude] `preConfig`) and the config is never `.resolve()`d, so only `-D` properties work. Prod reads `application.conf` from the working directory while the data directory is relative to the JAR ([Environment] `ROOT_PATH`), and a missing config file is silently ignored. [MEDIUM]
  - Fix: configurable host and port; environment config (`systemEnvironmentOverrides`, `.resolve()`); `application.conf` next to the JAR; fail fast on a missing prod config.
- ✅ No secret ships in code: the session key is random per process, and the content-signing key is generated once, race-safely, and stored ([SystemService] `loadOrCreateSigningKey`).
- [ ] [reference.conf] ships `db.postgres.password=dba`. [LOW]
- [ ] No versioned releases, changelog or upgrade procedure: `publishVersion = "0.1.0-SNAPSHOT"` ([build.mill]), no CHANGELOG, no CI. [HIGH]
  - Fix: semantic versions shown in the UI and logs, git-cliff or release-please, CI, and an upgrade procedure that starts with "back up first".
- [ ] Accepted uploads are lost on restart, and nobody is told. The upload answers 200 once a file is queued, not imported ([ImportController] upload handler). Imports run on an in-memory `Source.queue` with no job table and no retry, and startup empties staging ([Altitude] `service.staging.clear()`, logged only at DEBUG by [StagingService] `clear`). The 30-second drain in [Altitude] `cleanup` never runs in production: nothing registers a shutdown hook, and only tests call it. [CRITICAL]
  - Fix: a JVM shutdown hook that calls `Altitude.cleanup()`, ordered before Pekko's coordinated shutdown; durable imports that keep staged files with a manifest or an `import_job` row and resume them at startup.
- ✅ Purge jobs are durable: `is_purged` rows are requeued at startup ([LibraryService] `requeuePurgePending`).
- [ ] The purge requeue loads every pending row into memory at once.
- [ ] Failures are not visible to the Admin. Only the WebSocket ticker shows them ([ImportStatusWsActor]): close the tab and they are gone. A failed import stream refuses every later upload while the health check says ok; automatic restart is dev-only (`dev.restart_pipeline`). [MEDIUM]
  - Fix: record failures where an Admin sees them, alongside durable imports; restart a failed queue in production.
- [ ] Server errors return stack traces. [App] does not override cask's `debugMode`, which defaults to `true`, so an unhandled exception's stack trace, with any SQL and paths in it, goes to the client. A refused upload throws out of `Await.result` and becomes such a 500. [HIGH]
  - Fix: `debugMode = false` outside dev, and an error handler that logs and answers a generic page or JSON.
- ✅ Logs keep signatures, tokens and decoded CRLF out: the request logger skips `/content`, so `sig` never appears ([decorators] `requestResponseLogger`).
- [ ] Logs are not production-ready. [logback.xml] logs to the console only, at `root=DEBUG` (third-party libraries included), with time but no date, ANSI colours and no rotation. `REQUEST_ID` is never cleared from the MDC ([decorators] `requestResponseLogger`). `/import/status?userId=` is logged. logback is declared twice, as 1.5.16 and 1.5.14 ([build.mill]). [LOW]
  - Fix: a prod logback profile: dated timestamps, no ANSI, logback's `JsonEncoder`, stdout or a rolling file, root at INFO.
- [ ] The health check always answers `{"status":"ok"}` ([HealthController]), with no database, queue or disk check. [MEDIUM]
  - Fix: `/livez` and `/readyz` with a database ping, queue state and free disk.
- ✅ Search holds up at scale: cursor paging, `MAX_RPP=500`, totals capped at 10k, a client page window, a PostgreSQL read statement timeout, covering indexes, plan tests, and a one-off 1M-asset benchmark on both engines ([search-performance plan]).
- [ ] The People tab is unbounded ([PeopleActionController]), and the folder tree and the album and Location lists come back whole. [MEDIUM]
  - Fix: paginate the People tab.
- [ ] No index for the file name, size and area sorts, or for metadata filters by `(field_id, value)` ([all.sql]).
- ✅ HTTP range requests work for seeking ([RangeStreaming], tested).
- [ ] No committed load-test harness: the 1M benchmark was one-off, and there is no HTTP load test, import-throughput test or JVM memory guidance.
  - Fix: commit a seeding and benchmark harness.

### User management

- [ ] None of the basics exist. Only setup creates an account ([SystemService] `initializeSystem` calls [UserService] `add`). There is no create, disable (`account` has no enabled column), delete, admin password reset or self-service password change ([UserDao] has only `addUser`, `getPasswordHashByEmail` and `getByEmail`). A second account means hand-writing an `account` row with a bcrypt hash. [CRITICAL]
  - Fix: an Admin Users screen (create, disable through `disabled_at`, reset, set role) and a self-service password change, both on server-side sessions so they take effect at once.
- ✅ Emails are unique case-insensitively (`account_01` on `lower(email)`), tested. The role column and its CHECK exist.
- ✅ `requireAdmin` is correct and tested ([decorators] `requireAdmin`, [SettingsControllerTests]).
- [ ] Roles grant nothing different. `AccountType` is Admin, User or Guest ([AccountType]), and only setup assigns one ([SetupFormController]). `requireAdmin` guards only the Settings page, its form and the nav link; nothing else reads `accountType`, so a Guest can upload, recycle and purge like anyone else. A role change would wait for the token to expire. [MEDIUM]
- [ ] A repository cannot be shared: access is ownership alone ([decorators] `ownedRepositoryOnly`), so nobody can see who has access. [MEDIUM]
  - Fix: `repository_member(repository_id, account_id, role)` with View, Edit and Admin, enforced where `ownedRepositoryOnly` checks today, with last-admin and self-demotion guards; Guest removed or defined as View.
- [ ] Deleting an account deletes its library. `owner_account_id … ON DELETE CASCADE` ([all.sql]), and 12 tables cascade from `repository`, so deleting an account row wipes every repository it owns and leaves its files orphaned on disk. Nothing documents this. [HIGH]
  - Fix: the owner foreign key as `RESTRICT`, with deletion requiring a transfer or archive first.
- [ ] A second user would hit two latent failures: a 500 at login without `last_active_repo_id` (the `.get` in [SessionController]), and a redirect to the first repository, which they do not own ([IndexController]; [RepositoryService] `getDefaultRepository` assumes there is one repository). [LOW]
- [ ] `User.forgetMe` is a leftover `println` stub ([User]). [LOW]

### Sharing with clients

- [ ] Clients have no way in. On a LAN/VPN-only server, either clients join the VPN, or the share-link path alone is exposed (see [Deployment](#deployment)).
  - Fix: expose only the share route, through Tailscale Funnel or Cloudflare Tunnel, and document it with the install.
- ✅ The signing primitive is solid. [ContentSigner] computes an HMAC-SHA256 over `path\n<exp>`, compares it with `MessageDigest.isEqual`, is pinned by an openssl known-answer test, and uses a race-safe persisted key ([SystemService] `loadOrCreateSigningKey`). Signed requests reuse the hardened content path and its safe logging ([decorators] `signedOnly`), and a signature opens one item as one data type ([ContentViewControllerTests]).
- [ ] Share links are only half-built. Nothing calls `sign()`. There is no issuer, no expiry cap, no password, no album scope and no per-link revocation: rotating the key, the only revocation, revokes every link. Effort: M.
  - Fix: a `share_link` table: asset or album scope, expiry with a server-side maximum, optional password, revoked flag. Sign `linkId|path` with a key ID for rotation.
- [ ] Shared originals carry their EXIF and GPS. Previews and face crops are re-encoded WebP without metadata, but a signed original is served as stored. Effort: S–M.
  - Fix: serve shares without metadata by default. `requireLoginOrSignature` ([decorators]) already tells shares from owners, so a watermark could also apply to shares only.
- [ ] No audit log. A failed login is a WARN without the client's IP ([SessionController]), a successful one only a DEBUG; a refused repository, a non-Admin and a bad signature are WARNs ([decorators]). Nothing is persisted, and there is no Admin view. Effort: M.
  - Fix: an append-only audit table written from the service layer (login success and failure with IP, role changes, recycle and purge, settings saves, share creation), with an Admin view. Log auth events at INFO now.

### Video

- ✅ Videos are detected from their content ([MetadataExtractionService] `detectAssetType`), and audio-only files are classed unsupported.
- [ ] Only H.264 plays everywhere. Nothing transcodes or remuxes ([video-support plan] left it out of scope), so the original is the only rendition, and every `video/*` type is accepted. What plays in browsers as of late 2026: [CRITICAL]
  - **Everywhere:** H.264 in MP4 or MOV (iPhone "Most Compatible", most DJI and GoPro H.264), VP9.
  - **Partly:** HEVC. Safari yes; Chrome and Edge only with a hardware decoder; Firefox with hardware on Windows and macOS, patchy on Linux. This covers iPhone "High Efficiency" (the default), Android HEVC and GoPro HEVC 10-bit.
  - **AV1:** broad, but Safari needs a hardware decoder.
  - **Nowhere:** ProRes, AVCHD `.MTS`/`.M2TS`, XAVC 4:2:2 10-bit, MPEG-2, AVI (MJPEG, DivX, Xvid), WMV/VC-1, 3GP/H.263. MKV plays in recent Chromium only.
  - The app's own test fixtures are MPEG-4 Part 2 ([TestVideos]), which no browser plays.

  Fix: remux first: H.264 or HEVC in TS, MTS or MKV to fragmented MP4 with faststart, copying packets (which also saves the extra round trip of a `moov` at the end). Then proxies: an H.264 (OpenH264) or AV1 (SVT-AV1) rendition for never-playable and over-bitrate originals, in a durable, lower-priority job queue. Newer: on-demand HLS or CMAF with fMP4 segments; hardware encoding through NVENC, VAAPI or QSV.
- ✅ Transcoding needs no extra runtime: the bundled LGPL FFmpeg build includes `libopenh264`, `libvpx`, `libaom`, `libsvtav1` and `h264_nvenc`, going by the strings in the jar.
- [ ] A video that cannot play fails silently or opaquely: a snackbar only ([detail-navigator.js]), with no poster and no download link (the app has no download control). Success fires on `loadedmetadata`, so an unplayable video track with playable audio gives a black frame with sound. [HIGH]
  - Fix: the Preview as `poster`, a check of `videoWidth === 0` and `navigator.mediaCapabilities.decodingInfo`, and "This browser can't play this format" with a download link.
- [ ] Codec facts are not stored. [VideoInfo] keeps size, duration, frame rate and audio, and only size and duration are stored; codec, profile and bit depth are never recorded. There is no lower-bitrate rendition, so a 100 Mb/s 4K original streams raw. [MEDIUM]
  - Fix: probe and store `codec`, `profile`, `pix_fmt`, `color_transfer`, container and bitrate, and derive a playability class: universal, HEVC-conditional, never.
- ✅ Seeking works: range streaming through `FileChannel.transferTo`, with client disconnects logged at DEBUG, for signed URLs too ([RangeStreaming], tested in [ContentViewControllerTests]).
- [ ] Only a single range is served (a 200 for a multi-range request, which the standard allows), and `bytes=5-3` answers 416 where RFC 9110 says to ignore it.
- ✅ Ingest is careful: a preview that skips black leaders ([VideoService] `previewFrame`); display-matrix rotation (`uprightRotation`, `TestVideos.portrait`); QuickTime and MP4 dates and GPS; face clustering across sampled frames, capped at 120.
- ✅ The player is careful: Media Session support, a muted-autoplay fallback, looping for short clips, stale requests dropped.
- [ ] HDR and 10-bit video is converted to BGR24 without tone mapping, so HLG and Dolby Vision previews likely look washed out (unconfirmed).
  - Fix: `zscale` and `tonemap` for HDR previews.

Decoder limits and timeouts are under [Malicious files](#malicious-files).

---

## Strongly preferred

### Identity and access

- [ ] Nothing slows password guessing: each guess is a bcrypt check (a CPU cost too), and the failed-login WARN has no IP ([SessionController]), so fail2ban cannot use it. [HIGH]
  - Fix: rate limiting per IP and per account (an in-process token bucket, or Bucket4j), with failures logged with the client IP.
- [ ] No 2FA: `account` has only `password_hash`, and there is no credential table and no WebAuthn route. Effort: M.
  - Fix: passkeys as the main login through Yubico java-webauthn-server or webauthn4j, with TOTP as a fallback.
- [ ] No OIDC or SSO: `/login` ([SessionController]) is the only login, with no identity provider and no trusted forward-auth header mode. Effort: M.
  - Fix: OIDC for Pocket ID, Authelia, Authentik or Keycloak, with just-in-time provisioning; it also hands 2FA and recovery to the provider. The seam is there: [PasetoService] `createToken` does not care how the user was verified.
- [ ] No personal API tokens. A Bearer header is accepted ([decorators] `AUTH_HEADER_NAME`) but carries the same per-process key and 7-day claims, so it dies on restart, with no scopes, listing or revocation, and it is [untested](test-coverage.md#session-token-creation-and-validation). Effort: M.
  - Fix: after server-side sessions: hashed opaque tokens, or PASETO v4 with a `jti` checked against the database and a persisted key; name, scopes such as `repo:read` and `upload`, expiry, last used, revoke. Newer: Biscuit tokens for capability-style scopes.
- [ ] No groups. They need repository membership first. Effort: L.

### Library and media

- ✅ Uploads show progress and an import status over a WebSocket.
- [ ] Upload cancel answers 404. The form posts to `/import/r/…/upload/…/cancel` ([upload_form.scala.html]), but the route is `/r/…/upload/…/cancel` ([ImportController] `cancelUpload`), and no other route matches. [MEDIUM]
  - Fix: one prefix for both; a [controller test](test-coverage.md#import-orchestration-and-asynchronous-processing) through the URL the form carries.
- [ ] Uploads cannot resume: each batch is one multipart POST ([upload-form.js]), so a dropped connection at 95% loses the batch. Effort: M.
  - Fix: tus resumable uploads with Uppy on the client, one asset per upload.
- [ ] No export, so the library is locked in. Originals download one at a time; XMP is only read on import ([MetadataExtractionService] `extract`); names, folders, albums, people, Locations and metadata exist only in the database. An RFC 6266 `Content-Disposition` encoder and the signature-gated content route are already there. Effort: M–L.
  - Fix: a streamed ZIP64 export of a selection, album or folder under original names and folder paths, then XMP sidecars (MWG face regions, Locations, albums, metadata) through the Adobe XMP Toolkit.
- [ ] No near-duplicate detection: no perceptual hash or embedding. Exact duplicates are under [Data safety](#data-safety). Effort: L.
- [ ] No integrity checks: nothing re-hashes stored originals to catch bitrot or flags missing files. The only scheduled job is the hourly SQLite `optimize` ([Altitude]), but the scheduler is there to run a sweep. Effort: S–M.
  - Fix: an integrity sweep on that scheduler, run with the reconciliation job from [Data safety](#data-safety) once the content hash is replaced.
- [ ] No storage quotas per user or per repository, though per-repository byte counters exist ([StatsService]). Effort: S.
  - Fix: check the quota from the counters before staging.
- [ ] HEIC and other phone formats likely fail as "Cannot decode image": dimensions come from ImageIO ([AssetService]) and decoding from OpenCV 4.9 with an ImageIO fallback ([ImageUtil] `decode`), with no TwelveMonkeys or libheif. This affects HEIC, HEIF, WebP, AVIF and most RAW files; iOS Safari usually converts HEIC to JPEG on upload. (unconfirmed) Effort: M.
  - Fix: libheif and libavif (bytedeco, or a `vips` subprocess) and WebP through TwelveMonkeys; consider libvips for the whole decode path.
- ✅ Time handling is principled and documented: a wall-clock capture time with the zone deliberately dropped, 12 sources tried in order with plausibility checks, and UTC container times handled ([CaptureDateResolver]).
- [ ] `OffsetTimeOriginal` is never read. "Imported:" ([result_cell.scala.html]) shows the JVM's zone on PostgreSQL ([PostgresOverrides] `getDateTimeField`) and UTC on SQLite ([SqliteOverrides] `toLocalDateTime`), never the viewer's, and without a label. Effort: S.
  - Fix: store `OffsetTimeOriginal` (EXIF 0x9011) beside the wall clock; render the import time in the viewer's zone with `<time datetime>` and `Intl.DateTimeFormat`.
- [ ] Not usable on a phone. The viewport meta is set, but the app CSS has no `@media` query, and fixed widths overflow a 360 px screen: the nav ([core.css]) and the split pane's `minSize: [100, 300]` ([index.scala.html]). Touch box selection is off ([box-selection.js]), and detail navigation is arrow keys only ([global.js]). Effort: L.
  - Fix: below a breakpoint, stack the explorer or put it in a drawer, wrap the nav, swipe in the detail view, and add `accept="image/*,video/*"` to the file input.

### Operations and hygiene

- [ ] No SMTP: there is no mail library, so password resets, invites and job-failure alerts cannot be emailed. Effort: M.
  - Fix: Jakarta Mail, once a reset flow exists.
- [ ] No metrics or crash reporting beyond the health endpoint. Effort: S–M.
  - Fix: the OpenTelemetry Java agent for request, JDBC and JVM metrics; Micrometer and Prometheus for queue depth, stage latency and drop causes; GlitchTip or Sentry for crashes.
- [ ] No supply-chain hygiene: there is no CI of any kind, and `.github` holds only [copilot-instructions.md]. Effort: S–M.
  - Fix: Renovate, a GitHub Actions job with OSV-Scanner or Trivy, and releases with a CycloneDX SBOM (syft), cosign keyless signing and SLSA provenance.
- [ ] No `SECURITY.md`. Effort: S.
- [ ] No LICENSE file: [build.mill] declares MIT in `pomSettings`, but no file exists. Effort: S.
- [ ] The face model's licence: the InsightFace `w600k_r50` model is published for non-commercial research, so semi-pro use may need a different model. (unconfirmed)
- [ ] Dependencies, as known at grading and not checked against release feeds or advisories (unconfirmed):
  - `commons-fileupload:1.5` ([build.mill]) is declared, unused, and in the range of CVE-2025-48976, a DoS fixed in 1.6.0.
  - `tika-core:3.0.0` is in the range of the 2025 Tika XXE advisories. Real exposure is low without the PDF module, but scanners will flag it.
  - `jbcrypt:0.4` and `jpaseto:0.7.0` are unmaintained.
  - OpenCV 4.9.0, FFmpeg 6.1.1 and javacv/javacpp 1.5.10 date from early 2024; metadata-extractor 2.19.0 is about 3 years old.
  - `postgresql:42.7.4` is in the range of CVE-2025-49146, which matters only with `channelBinding=require`.
  - Scala 3.6.3 is neither the LTS nor current.

  Fix: drop `commons-fileupload`; bump Tika and the bytedeco natives (javacpp-presets 1.5.11 or later: FFmpeg 7.x or 8.x, OpenCV 4.10+); let Renovate and OSV-Scanner keep them current.

---

## Optional / later

| Item | Status | Note |
|---|---|---|
| Native mobile apps with automatic backup | Absent, partial seam | A Bearer token is accepted ([decorators]), but search is HTML only and there is no JSON API to list, search or upload. Becomes strongly preferred if positioned against Google Photos. |
| LDAP | Seam exists | [PasetoService] `createToken` does not care how the user was verified. |
| S3, WebDAV, several storage roots | Seam exists, leaky | [FileStoreService] is a trait, and the `repository.file_store_type` and `file_store_config` columns exist but never pick a store. `assetFile: Path` puts the local filesystem in the interface; a range-read or stream method in its place, dispatching on `file_store_type`, would fix that. The ID-sharded layout suits object keys. |
| Permissions per album or folder | Query seam, no model | Every search, count and map query goes through [SearchQueries] `matching`, so an ACL is one more `.filter`; stored counters would leak around it. |
| Encryption at rest | Absent | Volume encryption is enough. |
| End-to-end encryption | Absent by design | Conflicts with server-side thumbnails, faces and search. |
| High availability, horizontal scaling | Blocked | Startup prunes every unfinished import and empties staging, so a second node would destroy the first's in-flight work. Queues ([QueuedPipeline]), WebSocket clients ([ImportStatusWsActor]), staged files and the session key are process-local, and SQLite has one writer. The PostgreSQL side is ready: `FOR UPDATE`, ordered stat locks, race-safe key creation. |
| Multi-tenant isolation | Seam exists | `repository_id` on every table; `repoContext`. |
| Webhooks, plugins | Partial seam | `.alsoTo` sinks in [ImportPipelineService] and `StatsService.transition`; no domain event bus. |
| Opt-in anonymous telemetry | Absent | Fine for privacy. |
| Asset versions, edit history | Absent | `asset_01` is unique by checksum. Originals are write-once, a good base. |
| Watermarking | Seam exists | `requireLoginOrSignature` tells shares from owners. |
| C2PA / Content Credentials | Absent, preserved | Originals are stored byte for byte, so any manifest survives; nothing reads it. |
| Hardware-accelerated transcoding | Absent | There is no transcoding to accelerate yet (see [Video](#video)). |
| Maintenance or read-only mode | Seam exists | Read-only transactions on both engines ([TransactionManager]); a global switch in `withTransaction` plus a decorator. |
| Hosted SaaS | Out of scope | It would add legal pages, GDPR data export and deletion, tenant isolation, billing and abuse handling (CSAM obligations included), and email deliverability. |

## Newer options worth a look

| Option | Status |
|---|---|
| Passkeys as the main sign-in; OIDC through a lightweight provider like Pocket ID | The login seam exists ([Identity and access](#identity-and-access)); sessions must become revocable first. |
| `Sec-Fetch-*` checks as an extra CSRF layer | Absent; `SameSite=Strict` is a strong base ([Web security](#web-security)). |
| A strict CSP with nonces, plus Trusted Types | Absent; 4 inline modules and the standard Alpine build are in the way. All JS is self-hosted, so `'self'` is easy. |
| `Cross-Origin-Resource-Policy` and `Cross-Origin-Opener-Policy` | Absent; cheap to add, CORP on content first. |
| User content from a separate sandbox origin | Mostly covered: `CSP: sandbox` and `nosniff` on content give an opaque origin on the same host, most of the benefit. |
| libvips | No ImageMagick to replace (OpenCV with an ImageIO fallback); libvips would also bring HEIC and AVIF. |
| AVIF or JPEG XL derivatives | Absent; there is only a 200 px WebP preview and the original. A mid-size display rendition matters more than its codec. |
| Decoders in a WASM or seccomp sandbox | Absent and hard: OpenCV and FFmpeg run in-process through JNI. `PipelineUtils.stage` is where an out-of-process worker would fit. |
| Litestream for SQLite | Compatible (WAL, [TransactionManager]), undocumented. |
| pgBackRest or WAL archiving for PostgreSQL | Compatible with the pgvector PostgreSQL 18 image, undocumented; there is no production compose. |
| restic or Kopia for originals | Very compatible: write-once, ID-named files. |

---

## Priority order

Entries leave this list when their items close.

**This week** (cheap, removes a lot of risk):

- Escape the import ticker ([Web security](#web-security)) and put login on its WebSocket ([Permissions](#permissions)).
- `debugMode = false` and an error handler ([Running and upgrading](#running-and-upgrading)).
- `dev.user` through `devSwitch`; refuse to start on an unknown `ENV` ([Login and sessions](#login-and-sessions)).
- A shutdown hook that calls `cleanup()` ([Running and upgrading](#running-and-upgrading)).
- One security-headers decorator and a `Sec-Fetch-Site` check on writes ([Web security](#web-security)).
- Undertow size caps and a max-pixels Setting ([Malicious files](#malicious-files)).
- The upload-cancel URL ([Library and media](#library-and-media)).
- SECURITY.md, a LICENSE file, Renovate, OSV-Scanner; drop `commons-fileupload` ([Operations and hygiene](#operations-and-hygiene)).

**Before real data:**

- Freeze the schema and start versioned migrations, with an upgrade test and a snapshot before each one ([Data safety](#data-safety)).
- A written, tested backup and restore ([Data safety](#data-safety)).
- Keep staged uploads across a restart and resume them ([Running and upgrading](#running-and-upgrading)).
- A Docker image and compose file, with a configurable host and port ([Running and upgrading](#running-and-upgrading)).

**Before clients:**

- Server-side sessions ([Login and sessions](#login-and-sessions)).
- Repository scoping by default, and a test over every route ([Permissions](#permissions)).
- User management and roles per repository ([User management](#user-management)).
- Share links and an audit log ([Sharing with clients](#sharing-with-clients)).
- A video fallback (poster and download), then remuxing and an H.264 or AV1 proxy queue ([Video](#video)).

**Later**, including the newer options: passkeys or OIDC through Pocket ID; decoders out of process (libvips) or in a WASM sandbox; HEIC through libheif; a strict CSP with Alpine's CSP build and Trusted Types; the OpenTelemetry Java agent; cosign and SLSA provenance; tus through Uppy; XMP sidecar export; a mobile layout.

[altitude/AGENTS.md]: ../altitude/AGENTS.md
[README]: ../README.md
[Makefile]: ../Makefile
[docker-compose.yml]: ../docker-compose.yml
[build.mill]: ../build.mill
[copilot-instructions.md]: ../.github/copilot-instructions.md
[search-performance plan]: ../plans/done/search-performance.md
[video-support plan]: ../plans/done/video-support.md
[all.sql]: ../altitude/resources/migrations/postgres/all.sql
[reference.conf]: ../altitude/resources/reference.conf
[logback.xml]: ../altitude/resources/logback.xml
[Altitude]: ../altitude/src/altitude/core/Altitude.scala
[Api]: ../altitude/src/altitude/core/Api.scala
[App]: ../altitude/src/altitude/core/App.scala
[Const]: ../altitude/src/altitude/core/Const.scala
[Environment]: ../altitude/src/altitude/core/Environment.scala
[ImportStatusWsActor]: ../altitude/src/altitude/core/actors/ImportStatusWsActor.scala
[AlbumDao]: ../altitude/src/altitude/core/dao/jdbc/AlbumDao.scala
[BaseDao]: ../altitude/src/altitude/core/dao/jdbc/BaseDao.scala
[FaceDao]: ../altitude/src/altitude/core/dao/jdbc/FaceDao.scala
[PersonDao]: ../altitude/src/altitude/core/dao/jdbc/PersonDao.scala
[SearchDao]: ../altitude/src/altitude/core/dao/jdbc/SearchDao.scala
[SystemMetadataDao]: ../altitude/src/altitude/core/dao/jdbc/SystemMetadataDao.scala
[UserDao]: ../altitude/src/altitude/core/dao/UserDao.scala
[PostgresOverrides]: ../altitude/src/altitude/core/dao/postgres/PostgresOverrides.scala
[SqliteOverrides]: ../altitude/src/altitude/core/dao/sqlite/SqliteOverrides.scala
[Columns]: ../altitude/src/altitude/core/dao/sql/Columns.scala
[SearchQueries]: ../altitude/src/altitude/core/dao/sql/search/SearchQueries.scala
[AccountType]: ../altitude/src/altitude/core/models/AccountType.scala
[AssetWithData]: ../altitude/src/altitude/core/models/AssetWithData.scala
[User]: ../altitude/src/altitude/core/models/User.scala
[VideoInfo]: ../altitude/src/altitude/core/models/VideoInfo.scala
[PipelineUtils]: ../altitude/src/altitude/core/pipeline/PipelineUtils.scala
[QueuedPipeline]: ../altitude/src/altitude/core/pipeline/QueuedPipeline.scala
[CheckDuplicateFlow]: ../altitude/src/altitude/core/pipeline/flows/CheckDuplicateFlow.scala
[ExtractMetadataFlow]: ../altitude/src/altitude/core/pipeline/flows/ExtractMetadataFlow.scala
[decorators]: ../altitude/src/altitude/core/routes/decorators.scala
[RangeStreaming]: ../altitude/src/altitude/core/routes/RangeStreaming.scala
[HealthController]: ../altitude/src/altitude/core/routes/api/HealthController.scala
[ContentViewController]: ../altitude/src/altitude/core/routes/web/ContentViewController.scala
[ImportController]: ../altitude/src/altitude/core/routes/web/ImportController.scala
[IndexController]: ../altitude/src/altitude/core/routes/web/IndexController.scala
[SessionController]: ../altitude/src/altitude/core/routes/web/SessionController.scala
[FolderActionController]: ../altitude/src/altitude/core/routes/web/partial/FolderActionController.scala
[PeopleActionController]: ../altitude/src/altitude/core/routes/web/partial/PeopleActionController.scala
[SearchResultsController]: ../altitude/src/altitude/core/routes/web/partial/SearchResultsController.scala
[SetupFormController]: ../altitude/src/altitude/core/routes/web/partial/SetupFormController.scala
[AlbumService]: ../altitude/src/altitude/core/service/AlbumService.scala
[AssetService]: ../altitude/src/altitude/core/service/AssetService.scala
[BaseService]: ../altitude/src/altitude/core/service/BaseService.scala
[ContentSigner]: ../altitude/src/altitude/core/service/ContentSigner.scala
[FolderService]: ../altitude/src/altitude/core/service/FolderService.scala
[ImportPipelineService]: ../altitude/src/altitude/core/service/ImportPipelineService.scala
[LibraryService]: ../altitude/src/altitude/core/service/LibraryService.scala
[MetadataExtractionService]: ../altitude/src/altitude/core/service/MetadataExtractionService.scala
[MigrationService]: ../altitude/src/altitude/core/service/MigrationService.scala
[PasetoService]: ../altitude/src/altitude/core/service/PasetoService.scala
[PersonService]: ../altitude/src/altitude/core/service/PersonService.scala
[RepositoryService]: ../altitude/src/altitude/core/service/RepositoryService.scala
[StagingService]: ../altitude/src/altitude/core/service/StagingService.scala
[StatsService]: ../altitude/src/altitude/core/service/StatsService.scala
[SystemService]: ../altitude/src/altitude/core/service/SystemService.scala
[UserService]: ../altitude/src/altitude/core/service/UserService.scala
[VideoService]: ../altitude/src/altitude/core/service/VideoService.scala
[FileStoreService]: ../altitude/src/altitude/core/service/filestore/FileStoreService.scala
[FileSystemStoreService]: ../altitude/src/altitude/core/service/filestore/FileSystemStoreService.scala
[TransactionManager]: ../altitude/src/altitude/core/transactions/TransactionManager.scala
[CaptureDateResolver]: ../altitude/src/altitude/core/util/CaptureDateResolver.scala
[ImageUtil]: ../altitude/src/altitude/core/util/ImageUtil.scala
[Sha256]: ../altitude/src/altitude/core/util/Sha256.scala
[Util]: ../altitude/src/altitude/core/util/Util.scala
[AssetControllerTests]: ../altitude/test/src/altitude/core/controller/AssetControllerTests.scala
[ContentViewControllerTests]: ../altitude/test/src/altitude/core/controller/ContentViewControllerTests.scala
[LoginControllerTests]: ../altitude/test/src/altitude/core/controller/LoginControllerTests.scala
[SettingsControllerTests]: ../altitude/test/src/altitude/core/controller/SettingsControllerTests.scala
[ImportPipelineServiceTests]: ../altitude/test/src/altitude/core/integration/ImportPipelineServiceTests.scala
[LibraryServicePruneTests]: ../altitude/test/src/altitude/core/integration/LibraryServicePruneTests.scala
[LibraryServiceRecycleTests]: ../altitude/test/src/altitude/core/integration/LibraryServiceRecycleTests.scala
[LocationServiceTests]: ../altitude/test/src/altitude/core/integration/LocationServiceTests.scala
[PurgePipelineServiceTests]: ../altitude/test/src/altitude/core/integration/PurgePipelineServiceTests.scala
[SearchMapTests]: ../altitude/test/src/altitude/core/integration/SearchMapTests.scala
[TransactionBoundaryTests]: ../altitude/test/src/altitude/core/unit/TransactionBoundaryTests.scala
[TestVideos]: ../altitude/test/src/altitude/test/TestVideos.scala
[index.scala.html]: ../altitude/views/index.scala.html
[pipeline.scala.html]: ../altitude/views/pipeline.scala.html
[result_cell.scala.html]: ../altitude/views/htmx/result_cell.scala.html
[trashbin_header.scala.html]: ../altitude/views/htmx/trashbin_header.scala.html
[upload_form.scala.html]: ../altitude/views/htmx/upload_form.scala.html
[core.css]: ../altitude/static/css/core.css
[global.js]: ../altitude/static/js/global.js
[album-list.js]: ../altitude/static/js/common/album-list.js
[location-list.js]: ../altitude/static/js/common/location-list.js
[snackbar.js]: ../altitude/static/js/common/snackbar.js
[upload-form.js]: ../altitude/static/js/fragments/upload-form.js
[map-view.js]: ../altitude/static/js/map/map-view.js
[box-selection.js]: ../altitude/static/js/search-results/box-selection.js
[detail-navigator.js]: ../altitude/static/js/search-results/detail-navigator.js
