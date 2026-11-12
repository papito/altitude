# Readiness for live use

What a self-hosted digital asset manager like Altitude (images and video; tens of thousands of assets; Libraries reached by Admins, and by Users and Guests through Permissions granted per Library) needs before live use, and where the code stands on each item. It covers security, data safety and operations, not the product feature set.

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
| [Login and sessions](#login-and-sessions) | 4 | bcrypt, no timing leak, strict cookies. An account change or a password change reaches every session of the account at its next request, but logout does not revoke a token, there is no account recovery, and two setups at once can race. |
| [Permissions](#permissions) | 7 | Every read and write by ID is scoped to the repository by default, the schema refuses a reference into another repository, every route declares who may call it, and one test covers every route for every account type. The Users page grants them, within the creator's ceiling. |
| [Malicious files](#malicious-files) | 4 | Types come from the content and storage paths are UUIDs. No size caps, no image-size cap, decoders in the server process. |
| [Data safety](#data-safety) | 3 | Crash recovery is excellent. A 32-bit duplicate hash, no schema migrations in use, no backup story. |
| [Running and upgrading](#running-and-upgrading) | 3 | Search benchmarked at 1M assets. No Docker image, no releases, accepted uploads lost on restart, stack traces in errors. |
| Strongly preferred: data and operations ([Library and media](#library-and-media), [Operations and hygiene](#operations-and-hygiene)) | 3 | Time handling is good. No export, no mobile layout, no HEIC. |
| [User management](#user-management) | 2 | The Users page adds, edits, deactivates and deletes accounts and grants their Permissions per Library, with an audit log of the account events. No account recovery for the last Admin, no groups. |
| Strongly preferred: identity, sharing and hygiene ([Identity and access](#identity-and-access), [Sharing with clients](#sharing-with-clients), [Operations and hygiene](#operations-and-hygiene)) | 2 | The URL-signing code is solid, but nothing creates share links. No CI, SECURITY.md or LICENSE file. |

On a separate scale, how well the design leaves room for the [optional](#optional--later) and [newer](#newer-options-worth-a-look) options: **6 / 10**. Clean extension points for the file store and queries; the design assumes one server process.

Foundations to build on, beyond the items below:

- Clean layering: a store trait, one query funnel ([SearchQueries] `matching`), repository scoping by default in the DAOs and in the queries, backed by foreign keys, and a Scalameta test ([TransactionBoundaryTests]) that enforces transaction boundaries.
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
- ✅ A login is a username or an email address, folded with `NameKey.of` and compared to the key column (an "@" makes it an email). A login naming no account checks a dummy hash of the same cost, and it, a wrong password and a deactivated account answer the same 401, so accounts cannot be enumerated ([UserService] `loginAndSetUser`, [LoginControllerTests]). The password check only reads; the last login time and an audit event, a failure's with the client IP, are written after it in a short transaction of their own ([UserServiceTests]).
- ✅ Session cookies are `HttpOnly`, `Secure` and `SameSite=Strict`, identical everywhere they are set ([SessionController], [SetupFormController]). Login issues a new token. Tokens are PASETO v2.local: encrypted and authenticated, with no algorithm confusion. Logout is POST-only.
- [ ] `Secure` is always on, so plain-HTTP LAN access (`http://nas:8080`) never gets a cookie and login loops. Nothing documents this.
  - Fix: document HTTPS through a reverse proxy (see the install item under [Running and upgrading](#running-and-upgrading)).
- ✅ A change to an account reaches its sessions at their next request. The token carries the account, its type, its Create Libraries flag, its grants and the version of the account it was minted at, and is read with no database query ([PasetoService] `readToken`); an in-memory map of the process holds each account's current and revoked-below versions ([AccountVersions]). A token below the current version is rebuilt from the database and its cookie replaced, keeping its expiry; one of an account deactivated or deleted since, a stale Bearer token, and one below the revoked-below version are refused ([UserService] `checkToken`, [LoginTokenTests], [LoginControllerTests]). The refusal clears the cookie only for a token that will never be good again (it does not decrypt or has expired, or its account is gone or deactivated), so a request in flight while an account changes its own password cannot log the changer out. An htmx request with no login is a 401 carrying `HX-Redirect` to the login for the page it came from, and the client sends the window to the login on any 401, htmx's or axios's. A token is judged and rebuilt against one snapshot of its account's versions, so a revocation that lands meanwhile leaves the rebuilt token refused; a login reads the version once the account is named and checks again, under the account's row lock, that the password hash is the one it checked. Every account write bumps the version once committed ([RepositoryService] `setGrant` and `replaceGrants`; [UserService] `create`, `update`, `deactivate`, `reactivate` and `delete`), and a password change, an Admin's or the account's own, `revoke`s every token of the account; the session that changed its own password is given a new token in the same answer ([OwnPasswordController] `renewedSession`, [UsersControllerTests]). An open import status socket of the account closes on either ([ImportStatusWsActor] `CloseAllFor`): on a bump with 1013, so the page reconnects and its handshake checks the login again, refused for an account deactivated or deleted; on a revocation with 1008, which it does not reconnect on ([ImportControllerTests]). A refused session cookie is cleared by the answer that refuses it.
- [ ] Logout does not revoke the token: it only clears the cookie ([UserService] `logout` is a no-op), so a copied token keeps working for up to 7 days ([Const] `MEMBER_ME_COOKIE_EXPIRATION_DAYS`) unless its account changes. There is no idle timeout or sliding renewal. The key is random per process ([PasetoService] `secretKey`), so every restart logs everyone out, and the version map is in memory, so it does not serve more than one server process. [MEDIUM]
  - Fix: revoke on logout through the version map (per token, a `jti` with a short denylist, rather than every session of the account); or server-side sessions, a `session` table of random IDs stored hashed, with `last_seen` for idle expiry and an absolute cap, which is also the base for personal API tokens, "log out everywhere" and more than one server process.
- ✅ The setup form is refused once the instance is initialized ([SetupFormController]).
- [ ] Setup can be won by whoever reaches a fresh instance first: there is no setup token. A check-then-act race on an in-memory flag lets two concurrent submissions create two admins; the flag flips before the commit ([SystemService] `initializeSystem`, [SystemMetadataDao] `setInitialized`). The setup test is commented out ([test gap](test-coverage.md#user-creation-and-password-authentication)). [MEDIUM]
  - Fix: a one-time setup token printed to the log on an uninitialized start; atomic initialization (`UPDATE … WHERE is_initialized = false`, checking the row count).
- [ ] No way back in for the last Admin. An Admin sets any account's password and every account changes its own ([User management](#user-management)), but an instance whose only Admin forgets the password has no CLI or env-var way in; it means editing the bcrypt hash in the database. [HIGH]
  - Fix: a CLI (`java -jar altitude.jar reset-admin-password <email>`) or a startup env var that resets an Admin's password.
- ✅ The dev login bypass is read through `Environment.devSetting`, the text counterpart of `devSwitch` ([UserService] `devCredentials`): `dev.user` and `dev.password` take effect only under `ENV=dev`, and are ignored with a WARN at startup anywhere else (`EnvironmentTests`). A dev login that fails is not retried for 30 seconds.
- [ ] A misspelled or unknown `ENV` falls back to dev ([Environment] `CURRENT`), which also reads `application-dev.conf` and turns the dev bypass on when it is set there. [HIGH]
  - Fix: refuse to start on an unknown `ENV`.
- ✅ The post-login redirect is validated and unit-tested.
- [ ] `redirect` is decoded twice ([SessionController] `decodedRedirect`), so `/%2509/evil.com` becomes `/\t/evil.com`, which passes the check; browsers drop the tab and land on `//evil.com`. (unconfirmed) [LOW]
  - Fix: decode once and reject control characters.
- [ ] Login CSRF is not prevented. [LOW]

Login rate limiting is under [Identity and access](#identity-and-access).

### Permissions

As built, a Library has no owner: an Admin reaches every Library by its account type, and anyone else a Library it holds a grant in (`repository_grant` in [all.sql]: an account, a Library and a bitmask of the nine Permissions, [RepositoryService] `setGrant`, at most 35 per account, what the login token fits). Every Permission is enforced, and the Users page writes the grants ([User management](#user-management)).

- ✅ One login and access gate for `/r/:repoId` ([decorators] `requireLogin`, `accessibleRepositoryOnly`): an Admin passes for every Library, anyone else for one its login token carries a grant in, with no database query; a Library the account cannot see answers the same 404 as a missing one. The token's Permissions in the Library are put in the request context (`RequestContext.permissions`), which the gates after it and the services read (below). The request context is cleared on pooled threads ([decorators] `repoContext`).
- ✅ Routes without login are deliberate: `/`, `/login`, `/logout`, `/api/logout`, `/setup`, `/htmx/setup` (refused once initialized), `/api/health`, `/static/` and `/assets/:name`.
- ✅ Every read and write by ID is scoped to the context repository by default ([BaseDao] `repositoryScoped`; six DAOs opt out: those of the four tables with no repository, and those of the grants and the audit log, which are read across Libraries), hand-written statements on a repository's tables carry the repository, and a write by ID that matches no row of the repository is `NotFoundException` ([BaseService]). A reference a request names is read in the repository before it is written: a destination or parent folder ([LibraryService] `moveAssetsToFolder`, [FolderService] `add`) and a cover Face, which must also be the Person's and on no asset marked for purging ([PersonService] `setFaceAsCover`). Another repository's ID gets the 404 an unknown one gets and names nothing of it ([BaseController] `answering`, `answeringJson`). The schema backs it up ([all.sql], both engines): `repository_id` is `NOT NULL` on every table of a repository, and the references between a repository's rows are foreign keys over the repository and the ID, so the database refuses one into another repository; a Person's cover, an Import file's asset and an Import's Watch folder are single-column keys, their repository checked in code. Tested on both engines ([RepositoryIsolationTests]) and over every route ([RouteTableTests]).
- ✅ Originals, previews and faces sit behind the repository gate in a per-repository file layout ([FileSystemStoreService]), and are served `private`, `nosniff` and `sandbox` ([ContentViewController] `withContentHeaders`). The reads behind them are scoped too: another repository's original, preview or face is a 404, signed or not ([ContentViewControllerTests]).
- ✅ Search results, counts, map pins and GPS all read one `matching` relation ([SearchQueries]); name resolution is scoped ([SearchDao] `searchNames`); tested ([SearchMapTests]). The advanced search panel's comboboxes complete folder, people and Location names as they are typed, matched on the server by the same rule ([SearchPanelController]), and its form and a filtered search's results title name what a search's IDs name through reads scoped to the Library, each by its ID, so another Library's ID has no chip and matches nothing ([SearchResultsController], [RouteTableTests]). Facets do not exist.
- ✅ People lists are scoped ([PersonDao]).
- ✅ Bulk asset actions use scoped locking selects ([AssetControllerTests], [LibraryServiceRecycleTests]). Exports and zip downloads do not exist yet (see [Library and media](#library-and-media)).
- ✅ Background-job results: the import-status WebSocket is a Library's (`/import/r/:repoId/status`, [ImportController] `pipelineStatus`). It takes the account from the login token, never from the request, refuses a handshake with no login with a 401, one for a Library the account cannot see with a 404, and a Guest's or that of an account without Import in the Library with a 403, and an Import's status is pushed to the sockets of its own Library alone ([ImportStatusWsActor]; [ImportControllerTests], [ImportStatusWsActorTests], [RouteTableTests]). The Import history's partials take Import in the Library too, and read the Library's Imports alone ([ImportHistoryController]). A handshake whose `Origin` is not the server's own (an http or https URL whose host and port, as written, are the `Host` header's) is refused with a 403 ([decorators] `isSameOriginOrNone`), so a page of another site cannot open it with a visitor's session, even where a browser would send the `SameSite=Strict` cookie. A handshake with no `Origin` is let through to the login check: every browser sends one, so only a client that is no browser omits it, and such a client holds the session token itself. The check trusts the `Host` header, so a reverse proxy must pass it through unchanged.
- ✅ IDs are UUIDv4 ([BaseDao] `genId`). Hard to guess is not authorized, though: IDs travel in signed URLs, logs and the DOM, so the scoping item above is what blocks ID guessing.
- ✅ Every route declares who may call it, by a decorator after `requireLogin` that reads the token's Permissions in the path's Library, with no database query ([decorators]): `requirePermission` (one Permission, or any of several), `requireMember` (no Guest: the Triage partials), `requireAccountCreator` (an Admin or the holder of Create Users or Create Guests in any Library: the Users page and Add User), `requireLibraryCreator` (an Admin or the Create Libraries flag) and `requireAdmin`; each refuses with one 403 (`decorators.forbidden`: `{"error": ...}` in JSON, as a service's refusal is too). Organize gates every move and every folder, album, Location and People write, merges of People included, Edit Metadata a Face's Person and a Person's name, a Curate Permission all of Curation, Purge both purges, Import the Import page, uploads, the Import history and the list of the Library's Watch folders; every write and dialog of a Watch folder is an Admin's ([WatchFolderController], [WatchFolderControllerTests]). A Guest sees Browse alone: a route that takes the view as a parameter (the main page, the search, the map, the People list) refuses it Triage, the Trash and the triaged assets, a route that takes an asset's ID (the Detail panel, the asset-detail modal, the asset's page, its Preview and original) answers it a triaged or recycled asset as an unknown one, and the import status WebSocket refuses its handshake ([decorators] `refusedView`, `visibleOnly`).
- ✅ The rules that depend on the asset are checked in the service, in the transaction of the write, over the assets read in the Library first, so another Library's ID stays a 404 ([AssetRules]): Curate own reaches only the account's own assets (`owner_user_id`), a sorted asset takes Recycle to recycle, a triaged one a Curate Permission, and a restore, from the trash or by a move out of it, takes what recycling would; deleting a folder that holds assets recycles them under the same rule; a Curation reopen checks every triaged Pick, in the trash or not, and a resolve and a reopen read every ID they are sent before any check, so a foreign or unknown ID is a 404 even beside a refused one. One refused asset fails the whole operation with `ForbiddenException`, a 403 through `answering` and `answeringJson` ([AssetPermissionTests], both engines; [PermissionControllerTests]). Curation shows Curate own without Curate all its own frames alone, so a set never holds one it could not resolve, and judges a Sequence complete over those frames, so another account's frames do not keep it in the queue. The controls follow the same bitmask: the nav, the batch operations (Recycle by the view: Recycle in Browse, a Curate Permission in Triage; Curate Selected in Browse with Recycle too), the explorer's menus, drag and drop, the Detail panel's corrections, Curation's entry points and the Trash render or bind an action only where the account holds its Permission.
- ✅ The permission matrix is tested route by route ([RouteTableTests]): each row says who may call its route and must be what the route's decorators declare, read off the instances cask builds from the annotations; a route that changes a Library and declares no gate fails it. Every route is sent a request by an Admin, a Guest, a User holding no Permission, a User holding every Permission, a User with the Create Libraries flag and, for a route that needs a Permission, a User holding all but it and a User holding each alone, and answers 403 exactly where its row refuses. An account with no grant in a Library gets a 404 on every route of it, every route but the public ones refuses an anonymous request, and every route answers another repository's IDs, of a Library the user holds every Permission in too, as it answers unknown ones, changing nothing.
- ✅ One active Admin is always left. An Admin cannot deactivate, delete or demote its own account, and every Admin's write locks the active Admins' rows first and checks, under that lock, that the actor is one of them: the actor is an active Admin and never the one it removes, so two Admins removing each other at once leave one, the second refused as no longer an Admin ([UserService] `update`, `deactivate`, `delete` and `setPassword` over [UserDao] `lockActiveAdmins`). [AccountManagementTests] (both engines) runs four such races, the second write waiting on the first's lock: deactivate × deactivate, demote × demote, delete × delete, and a demotion × a delete of its actor; each was proved red on PostgreSQL with `lockActiveAdmins` replaced by an unlocked read in the method under test (`deactivate`, `update`, `delete`, `delete` respectively). `setPassword`'s use of the lock is not raced by a test. A creator's row is locked too, and a creator deactivated since its login creates nothing.
- ✅ A change of access takes effect at the session's next request: a grant, and every account write, bumps the account's version, and the token is rebuilt from the database ([Login and sessions](#login-and-sessions)). Share links, once they exist, need revoking of their own ([Sharing with clients](#sharing-with-clients)).
- [ ] The repository cache is never invalidated ([RepositoryService] `getById`). [LOW]

### Web security

- ✅ No GET route changes state, every auth cookie is `SameSite=Strict`, and JSON PUT and DELETE need a CORS preflight.
- [ ] No CSRF token and no `Origin` or `Sec-Fetch-Site` check. `SameSite` does not stop same-site sibling subdomains.
  - Fix: reject a non-safe method whose `Sec-Fetch-Site` is `cross-site`.
- ✅ Output is escaped. Twirl escapes by default and no template passes a value through raw `Html(...)`, whose uses are fixed markup (a Watch folder card's and dialog's attributes, an empty slot) and the style guide's own scan; file names, people, albums and Locations render through `@{...}`. Client JS builds DOM with `textContent` and `createElement` ([album-list.js], [location-list.js], [map-view.js], the announcements of [snackbar.js]), and Alpine binds text with `x-text` (the snackbar's messages). EXIF is never rendered raw. Escaping has [no tests](test-coverage.md#asset-persistence-queries-and-previews).
- ✅ No file or folder name is formatted into HTML by hand. The Import history, an Import's files and the status the import feed pushes are rendered by Twirl ([ImportHistoryController], [ImportStatusWsActor] `statusMessage`), which escapes an uploaded folder's name, a file's path and its reason, as it does a Watch folder's name, path and status reason on its card ([watch_folders.scala.html]); the feed's messages are JSON, and nothing of them is swapped as raw text. Tested with names holding markup, in the partials and in the pushed message ([ImportControllerTests], [ImportStatusWsActorTests]).
- [ ] The Leaflet attribution Setting renders as HTML ([map-view.js] `attribution`), so an Admin could store script that runs for every user. [LOW]
- [ ] No security headers on pages. `nosniff` is set only on content ([ContentViewController]); nothing sets `frame-ancestors` or `X-Frame-Options`, so every page can be framed (clickjacking on Purge, Settings and setup); no `Referrer-Policy` (low impact, since browsers default to `strict-origin-when-cross-origin`); no HSTS, and nothing documented for the proxy. [MEDIUM]
  - Fix: one `securityHeaders` decorator in `mainDecorators` ([decorators]): `nosniff`; `Content-Security-Policy: frame-ancestors 'none'; object-src 'none'; base-uri 'none'`; `Referrer-Policy: same-origin`; `Cross-Origin-Opener-Policy: same-origin`; `Cross-Origin-Resource-Policy: same-origin` on `/content`; HSTS behind a setting. Tests for the headers and for framing.
- [ ] No strict CSP. Alpine 3.13.10 is the standard build, which evaluates expressions, and 2 templates have inline scripts (`index`, `style_guide`).
  - Fix: the `@alpinejs/csp` build, inline scripts moved into modules or given nonces, `script-src 'self'` (all JS is self-hosted), later `require-trusted-types-for 'script'`.
- ✅ Content carries an explicit `Content-Type`: Tika detects it ([MetadataExtractionService] `detectAssetType`) and the stored MIME type is served ([ContentViewController]).
- ✅ SVG and HTML are not served inline, though only incidentally: `text/*` fails the media-type check ([LibraryService] `SUPPORTED_MEDIA_TYPES`), and SVG passes as `image/*` but no ImageIO reader takes it, so the import drops it.
- [ ] Nothing rejects SVG explicitly, and [no test](test-coverage.md#import-orchestration-and-asynchronous-processing) covers it.
  - Fix: reject `image/svg+xml` explicitly. For defense in depth, serve originals from a cookieless sandbox origin through the existing signed URLs.
- ✅ Content is `Cache-Control: private, max-age=31536000, immutable`, never `public`, and errors are not cached ([ContentViewController] `withContentHeaders`, tested in [ContentViewControllerTests]).
- ✅ SQL is parameterized: values are bound ([Columns] `literal`), column names are checked against the schema, sorts are whitelisted ([SearchResultsController] `parseSort`), and FTS queries are built from `SearchWords` output and bound.
- ✅ No `X-Forwarded-*` or `Host` header is read, so none can be spoofed. Running behind TLS is part of the install item under [Running and upgrading](#running-and-upgrading).

### Malicious files

- [ ] No size caps and no disk-space check. cask 0.9.7 leaves Undertow's `MAX_ENTITY_SIZE` at `-1` ([App] does not configure the server). An upload is one file per request ([upload-run.js]), its body streamed straight into staging ([ImportController] `uploadFile`, [StagingService] `stage`), but there is no per-file cap ([ImportService] `upload`) and no `getUsableSpace` anywhere. A Watch folder's scan copies a file of any size into staging the same way, one file at a time per Watch folder ([WatchFolderService] `take`). Upload threads wait with `Await.result(..., Duration.Inf)`. [HIGH]
  - Fix: Undertow `MAX_ENTITY_SIZE`, a per-file cap, and a free-space check against a reserve; a test with an oversized upload.
- ✅ Types come from the content: Tika's `DefaultDetector` on the UUID-named staged file ([MetadataExtractionService] `detectAssetType`).
- [ ] Every `image/*` and `video/*` subtype is accepted, so the decoder surface is wider than needed: JPEG 2000, HDR, PxM, and JBIG2 and PCX (`tika-parser-image-module` brings `jai-imageio-core` and `jbig2-imageio` into the ImageIO fallback), plus every FFmpeg demuxer. The test media type `"x-none"` is in the production allowlist ([LibraryService] `SUPPORTED_MEDIA_TYPES`). [MEDIUM]
  - Fix: allow named formats only, run FFmpeg with a format whitelist, and drop the JBIG2 and JAI plugins.
- [ ] Pixel and decompression bombs. Dimensions are read from the header before decoding ([AssetService] `getDimensionsAndDuration`) but never limited. OpenCV's default limit is about 1 gigapixel, roughly 3–4 GB of native memory per decode, and each image is decoded three times over its import (faces, measurement and preview), with one decode in flight per import thread at a time. The ImageIO fallback, which decodes every GIF, is unbounded, and [AssetWithData] `bytes` reads the whole file. GIF frame count and video dimensions and duration are not limited either (video sampling is bounded to 120 frames). The stage guard catches only `NonFatal` ([PipelineUtils] `guard`), so an `OutOfMemoryError` probably leaves the stage's future incomplete and that repository's import hung (unconfirmed). [HIGH]
  - Fix: maximum pixels and side length as Settings, checked in [ExtractMetadataFlow]; header-only probing already happens, so the cap is a few lines. The same limits for video dimensions, duration and GIF frames. Set `OPENCV_IO_MAX_IMAGE_PIXELS` to match, size-check the ImageIO fallback, and decode once instead of three times. Tests with a large-canvas PNG, a huge GIF canvas and a GIF with millions of frames.
- ✅ No path traversal: staged files are named by UUID ([StagingService] `freshPath`), store paths come from server IDs, and non-UUID IDs are refused ([ContentViewController] `getContent`). File names never become paths. An uploaded file's relative path names folders of the Library, never a path on disk: one with a `..`, an empty name or an ignored name is refused before its body is read ([RelPath] `segments`, [ImportController] `uploadFile`), and its folder names are held to the folder name rule when the file is sorted into them ([FolderService] `ensurePath`). The one path on the server that a request names is a Watch folder's, which only an Admin sets ([Data safety](#data-safety)). There is no archive or sidecar import.
- [ ] File names have no length cap, and neither has the relative path an upload gives a file ([ImportController] `uploadFile`).
- [ ] Decoders are not hardened. OpenCV and FFmpeg run in the server process through JNI with no timeout, so a crafted file can hang an import and a segfault kills the server. Video decoding is in software, with up to 120 seeks for faces plus up to 120 for the preview. FFmpeg opens files with every demuxer and protocol ([VideoService] `withGrabber`), not `protocol_whitelist=file`. For decoder versions, see [Dependencies](#operations-and-hygiene). [HIGH]
  - Fix: a per-asset timeout and `protocol_whitelist=file` now; then decoding in a separate worker process with time and memory limits (cgroups or rlimits, seccomp), where `PipelineUtils.stage` is the seam. Newer: libvips through its `vips` command, decoders compiled to WASM (Wasmtime, or Chicory on the JVM), gVisor for a decoder container.
- ✅ A file that fails to decode drops only that asset ([PipelineUtils] `guard`, [ImportPipelineServiceTests]), and the cleanup means a crash does not leave the import looping.
- ✅ A staged file does not outlive an upload that fails: a refused one is deleted, and one that fails in detection or hashing, or that the import queue refuses, is deleted and its Import file recorded failed ([ImportService] `upload`, tested in [ImportServiceTests]).

### Data safety

- [ ] No real schema migrations. Until the first real data, schema changes go into `all.sql` and the database is recreated (the rule under "Schema migrations" in [altitude/AGENTS.md]). That has to end before anyone has real data, and every upgrade after it needs a tested, forward-only migration. [MigrationService] `migrate` can run `<version>.sql` files, but none exist and `schemaVersion` stays 2 ([Altitude] `schemaVersion`). A database built from an older `all.sql` is stamped 2, starts without complaint and then fails at runtime on missing columns. There are no upgrade tests and no snapshot before migrating. [CRITICAL]
  - Fix: freeze the schema and start `<version>.sql` files with a `schemaVersion` bump per change, rewriting that rule in altitude/AGENTS.md in the same change. Test the upgrade from the last release's database to head on both engines, refuse to start on a version mismatch, and snapshot before migrating (`VACUUM INTO`, or `pg_dump` behind a flag).
- ✅ Originals are never touched: an uploaded file is moved into the store once ([FileSystemStoreService] `addAsset`), a Watch folder's file is copied out of its directory, never moved ([WatchFolderService] `take`), and an asset is renamed only in the database. No metadata is written into files. The one delete of a source file is a Watch folder's, which the next two items hold.
- ✅ A Watch folder cannot be pointed at the app's own files or at another Watch folder's. Only an Admin sets its path ([WatchFolderController]), which must be absolute and an existing directory the server can list, writable when imported files are deleted, and must overlap neither one of the app's own directories nor another Watch folder of any Library (the same directory, or one inside the other), each compared by real path, symbolic links resolved; of two overlapping ones added at once the second is refused ([WatchFolderService] `pathProblem`, [WatchFolderDao] `lockPaths`). The app's own directories are every directory it stores into, each resolved on its own: the data directory, staging, the SQLite database's directory and the file store down to each Library's directories, so a store that a symbolic link in the data directory keeps on another volume is refused where it really is ([WatchFolderService] `ownDirectories`). They are also told by what they are, the file system's identity of a directory, so a second path to one of them that no symbolic link explains (a volume mounted twice, a bind mount) is refused, as the directory itself or as one above the path. Every scan checks the rules again, and a directory that fails them is not read: the Watch folder shows Unavailable, with the reason. A scan's walk refuses to enter one of the app's own directories wherever it meets it, by that identity, and then reads nothing of the Watch folder at all, which shows Unavailable naming the directory; this is what stops a scan when the store lies inside the watched directory under a second path. A scan neither follows nor reads a symbolic link in the directory, and leaves out the ignored names and empty files. The path rules, the symbolic link out of the data directory and the hard link are tested on both engines ([WatchFolderServiceTests]); a directory under a second mount cannot be made by a test, and was checked by hand in a mount namespace.
- ✅ A Watch folder deletes a source file only where it was told to, and only once the file is in the Library. Deletion is off by default, and switching it on shows, before the save, the number of files in the folder now and what will be deleted ([watch_folder_form.scala.html]). Deleted: a file whose import has completed, and a file that is a duplicate of an asset of the Library whose own import is complete, each after the transaction that says so has committed ([SettleImportFileFlow], [WatchFolderService] `settled`, `deleteSource`). Never deleted: an unsupported file, a failed one, a file whose size or modified time changed since its copy was staged, anything behind a symbolic link put in the place of the file or of a directory above it, a file in or below one of the app's own directories under whatever path, a duplicate that is the stored file of the asset it matches (the same file under a second path, which is remembered as final, never as handled), and the watched directory itself; a subdirectory goes only when a delete leaves it empty. A paused Watch folder and a removed one delete nothing, and a paused one reads the file again once it is resumed. Switching deletion on for files imported earlier reads them again, so each is deleted as a duplicate found in the Library, never on the word of the Watch folder's memory. A delete that fails is logged at WARN and the file stays. Tested on both engines ([WatchFolderServiceTests]).
- [ ] A Watch folder tells the app's own directories under a second path by the file system's identity of a directory (device and inode). Two mounts that give one directory different identities are not told apart from two directories: the same network share mounted twice with separate device numbers (NFS with `nosharecache`, or once over NFS and once over SMB), or a store on a file system that reports no stable inode. Such a path is then scanned as any directory, and with deletion on the store's files are deleted as duplicates of themselves; only a file that is the same file as the matched asset's stored file by that same identity is spared. [MEDIUM]
  - Fix: a marker file the app writes into each of its own directories, which the path rules and the walk refuse wherever they find it.
- [ ] Before a Watch folder deletes a file it compares the file's size and modified time with those of the copy it imported and does not read it again, so a file rewritten in place under the same size and modified time, after its copy was staged, is deleted unimported. The copy is opened with `NOFOLLOW_LINKS`, which covers the file alone: a directory above it swapped for a symbolic link between the scan's walk and the copy would be read through (unconfirmed). [LOW]
  - Fix: compare the source's SHA-256 with the imported asset's checksum before the delete; open the file through a directory handle (`SecureDirectoryStream`).
- ✅ Only a copy of the same file is reported as a duplicate. The checksum is the SHA-256 of the file, streamed once from the staged file ([Sha256] `hex` in [LibraryService] `stagedFileToAsset`) and stored as 64 lowercase hex characters, `checksum CHAR(64)` (`COLLATE "C"` on PostgreSQL), under a unique index on `(repository_id, checksum) WHERE NOT is_recycled` (`asset_01` in [all.sql]); [CheckDuplicateFlow] and [LibraryService] `restoreRecycledAssets` compare nothing else. A collision between different files is not practical, by chance or crafted, so a file reported as "Duplicate asset" has the same bytes as one in the repository, and the stored value is what `sha256sum` prints for the original.
- ✅ The duplicate check is streamed and scoped to the repository, and recycled copies do not block a re-import.
- ✅ Trash exists: [LibraryService] `recycleAssets`, a purge behind a confirmation ([trashbin_header.scala.html]), and folder deletion in one transaction (`deleteFolderById`).
- [ ] The trash has no retention, auto-purge or `recycled_at` column. Recycling assets or deleting a folder permanently removes Album and Location memberships, and restoring does not bring them back. Purge cannot be undone. [MEDIUM]
  - Fix: `recycled_at` plus a retention Setting and an auto-purge; keep memberships through recycling.
- [ ] No backup or restore story for the database, the originals, derived data or config. Originals are stored as `files/<id[0:2]>/<id>` with no name or extension ([FileSystemStoreService] `filePath`), and folders, albums, people, Locations and file names live only in the database, so a file backup without the database is a folder of UUIDs. The only restore is the dev [Makefile]'s `restore-db`, which copies `altitude.db.bak` without its `-wal`/`-shm` files. [CRITICAL]
  - Fix: a documented, tested backup, database first and then files: Litestream, or `sqlite3 .backup` / `VACUUM INTO`, for SQLite; pgBackRest or WAL archiving (at least `pg_dump -Fc`) for PostgreSQL; restic or Kopia for `data/repositories/*/files`; a job that regenerates previews and faces. Nothing yet coordinates a database dump with a file snapshot.
- ✅ Backups have a good base: the signing key lives in the database, so a database backup carries it, and write-once, ID-named originals suit deduplicating backup tools.
- ✅ A crash mid-import leaves consistent rows. Files leave staging by an atomic rename, each stage is a transaction, and `discardImport` undoes every side effect, face counts and orphaned people included. Startup empties staging, fails the Import files a stop left pending and closes their Imports ([ImportService] `failInterrupted`), prunes unfinished imports ([LibraryService] `pruneDanglingAssets`), requeues purges (`requeuePurgePending`) and reconciles stats. All of it is tested on both engines ([LibraryServicePruneTests], [PurgePipelineServiceTests], [ImportPipelineServiceTests], [ImportServiceTests]). Purge is two-phase and deletes files before the row, so a crash cannot leave a row pointing at deleted files. Stats are adjusted in transactions under `CHECK (dim_val >= 0)`.
- [ ] The database and the files can drift apart. `discardImport` commits the row delete and then deletes the files. `File.delete()`'s result is ignored and its `catch IOException` cannot fire ([FileSystemStoreService] `purgeAssetById`, `purgeFaceById`), so a failed delete is swallowed without a log. Nothing scans for orphans, so files without rows and rows without files are never found. Nothing is fsynced, and with `synchronous=NORMAL` a power cut can leave a committed import without its file. [HIGH]
  - Fix: a reconciliation job over `files/`, `preview/` and `faces/` that flags orphans and missing files, at startup and on a schedule, with an Admin page; fsync for originals and their directories.
- ✅ A full disk during import drops the asset and cleans up: a `StorageException` from [FileSystemStoreService] (`addAsset`, `putBinaryData`).
- [ ] Derived files are not written to a temp file and renamed, and nothing checks free space before accepting work (see [Malicious files](#malicious-files)). [MEDIUM]
  - Fix: temp file plus `ATOMIC_MOVE` for derived files.
- ✅ `ClearDb` refuses to run outside dev.

Accepted uploads lost on restart are under [Running and upgrading](#running-and-upgrading).

### Running and upgrading

- [ ] No install path. There is no Docker image: [docker-compose.yml] is a dev PostgreSQL with `POSTGRES_PASSWORD=dba`. The [README] is dev-only, says a Docker runner will come later, and names `application-dev.conf.postgres`, which does not exist. `make publish` builds a fat JAR with the build machine's natives only ([build.mill]), and the 174 MB face model and the two 350 MB Scene models are fetched separately, by hand ([fetch-models.py]), so the JAR runs only on the platform that built it. Nothing documents running behind TLS or a reverse proxy. [HIGH]
  - Fix: a multi-arch image (`linux/amd64`, `arm64`) with natives per platform, a jlink-trimmed JRE on distroless or Chainguard (Jib, or Mill's Docker module), the model fetched with a checksum, compose with volumes and a backup recipe, and a `HEALTHCHECK`; install docs that cover HTTPS through a reverse proxy. GraalVM native-image is not practical with javacpp.
- [ ] Config is hard to set. cask's `host="localhost"` and `port=8080` are not overridden, so the server cannot bind `0.0.0.0` in a container or move port. Environment overrides (`systemEnvironmentOverrides()`) apply only to tests ([Altitude] `preConfig`) and the config is never `.resolve()`d, so only `-D` properties work. Prod reads `application.conf` from the working directory while the data directory is relative to the JAR ([Environment] `ROOT_PATH`), and a missing config file is silently ignored. [MEDIUM]
  - Fix: configurable host and port; environment config (`systemEnvironmentOverrides`, `.resolve()`); `application.conf` next to the JAR; fail fast on a missing prod config.
- ✅ No secret ships in code: the session key is random per process, and the content-signing key is generated once, race-safely, and stored ([SystemService] `loadOrCreateSigningKey`).
- [ ] [reference.conf] ships `db.postgres.password=dba`. [LOW]
- [ ] No versioned releases, changelog or upgrade procedure: `publishVersion = "0.1.0-SNAPSHOT"` ([build.mill]), no CHANGELOG, no CI. [HIGH]
  - Fix: semantic versions shown in the UI and logs, git-cliff or release-please, CI, and an upgrade procedure that starts with "back up first".
- [ ] Accepted uploads are lost on restart. The loss is reported: at startup every Import file still pending is failed, "Interrupted by a restart", and its Import closed, and the Import history marks the Import and lists those files, to upload again ([ImportService] `failInterrupted`, tested in [ImportServiceTests]). The files themselves are gone: the upload answers 200 once a file is queued, not imported ([ImportController] `uploadFile`), imports run on an in-memory `Source.queue` with no retry, and startup empties staging ([Altitude] `service.staging.clear()`, logged only at DEBUG by [StagingService] `clear`). A Watch folder's files are not lost that way: a file is only copied, it is remembered as handled only once its import has committed, and the scan at startup reads again what a stop cut off ([WatchFolderService]). The 30-second drain in [Altitude] `cleanup` never runs in production: nothing registers a shutdown hook, and only tests call it. [CRITICAL]
  - Fix: a JVM shutdown hook that calls `Altitude.cleanup()`, ordered before Pekko's coordinated shutdown; durable uploads that keep staged files across a restart and offer the pending Import files again at startup.
- ✅ Purge jobs are durable: `is_purged` rows are requeued at startup ([LibraryService] `requeuePurgePending`).
- [ ] The purge requeue loads every pending row into memory at once.
- [ ] A failed import stream is not visible. A file's failure is: every Import file is recorded with its outcome and reason, and the Import history shows them to every holder of Import in the Library, live and after ([ImportService], [ImportHistoryController]). But a failed import stream refuses every later upload while the health check says ok, and each scan of a Watch folder then records one failed file, "Import queue unavailable", in an Import of its own, leaving the file for a later scan ([WatchFolderService] `refused`); automatic restart is dev-only (`dev.restart_pipeline`). [MEDIUM]
  - Fix: restart a failed queue in production, and say so in the health check.
- [ ] Server errors return stack traces. [App] does not override cask's `debugMode`, which defaults to `true`, so an unhandled exception's stack trace, with any SQL and paths in it, goes to the client. An upload whose body cannot be staged throws out of its route ([ImportController] `uploadFile`, [StagingService] `stage`) and becomes such a 500. [HIGH]
  - Fix: `debugMode = false` outside dev, and an error handler that logs and answers a generic page or JSON.
- ✅ Logs keep signatures, tokens and decoded CRLF out: the request logger skips `/content`, so `sig` never appears ([decorators] `requestResponseLogger`).
- [ ] An uploaded file's name reaches the log as it was sent in the import pipeline's lines, so a holder of Import can put a line break in one and forge a log line: the queue's refusal at WARN ([QueuedPipeline] `refused`), a drop at INFO (`AssetErrorLoggingSink`) and a stage's failure at ERROR ([PipelineUtils] `guarded`) name the file by the name that ends its path. So can whoever writes a file into a Watch folder's directory, whose paths [WatchFolderService] logs. The Import record's own lines escape an Import's name and a file's path ([ImportService] `logged`, tested in [ImportServiceTests]). [LOW]
  - Fix: `Util.printable` wherever the pipeline and the Watch folders name a file in a log line.
- [ ] Logs are not production-ready. [logback.xml] logs to the console only, at `root=DEBUG` (third-party libraries included), with time but no date, ANSI colours and no rotation. `REQUEST_ID` is never cleared from the MDC ([decorators] `requestResponseLogger`). logback is declared twice, as 1.5.16 and 1.5.14 ([build.mill]). [LOW]
  - Fix: a prod logback profile: dated timestamps, no ANSI, logback's `JsonEncoder`, stdout or a rolling file, root at INFO.
- [ ] The health check always answers `{"status":"ok"}` ([HealthController]), with no database, queue or disk check. [MEDIUM]
  - Fix: `/livez` and `/readyz` with a database ping, queue state and free disk.
- ✅ Search holds up at scale: cursor paging, `MAX_RPP=500`, totals capped at 10k, a client page window, a PostgreSQL read statement timeout, covering indexes, plan tests, and a one-off 1M-asset benchmark on both engines ([search-performance plan]).
- [ ] The People tab is unbounded ([PeopleActionController]), and the folder tree and the album and Location lists come back whole. [MEDIUM]
  - Fix: paginate the People tab.
- [ ] No index for the file name, size and area sorts, or for metadata filters by `(field_id, value)` ([all.sql]).
- ✅ HTTP range requests work for seeking ([RangeStreaming], tested).
- [ ] No committed load-test harness: the 1M benchmark was one-off, and there is no HTTP load test or import-throughput test. The memory guidance rests on a few one-off imports of 24-megapixel JPEGs and no repeatable test: budget the JVM heap plus `FIXED_COST` (`POOL_COST`, 384 MB for the one shared face recognition net, and `SCENE_MODELS_COST`, 1152 MB for the two Scene models' ONNX Runtime sessions) plus `THREAD_COST` (512 MB) per import thread ([ImportParallelism]), and cap the threads with `import.parallelism.max` in [reference.conf].
  - Fix: commit a seeding and benchmark harness.

### User management

- ✅ The Users page manages accounts (`/users/r/:repoId`, [UsersController], [UserActionController]): an Admin, or an account holding Create Users or Create Guests in any Library, adds accounts through the Add User modal; an Admin edits any account (name, username, email, type, grants, the Create Libraries flag and the new-Library grant), sets its password, deactivates, reactivates and deletes it. Anyone else sees the accounts it created, read-only. Every write is [UserService]'s, in one transaction with its audit event, and bumps the account's version, or revokes its tokens for a password and a deactivation, so a reactivated account logs in again ([AccountManagementTests], both engines; [UsersControllerTests]). An Admin's edit is saved only over the state its form was opened over (another Admin's change, or a new Library's grant, since then refuses it with a form error), compared under the account's row lock. Account and Library IDs a request names are UUIDs or a 400.
- ✅ A creator who is no Admin gives only what it may pass on: a User where it holds Create Users, a Guest where it holds Create Guests, with no Permission it does not hold there, and never an Admin, the Create Libraries flag or the new-Library grant (its switch or its Permissions). The service refuses anything above that ceiling with a 403 whatever the form sends, reading the creator's grants in the write's transaction; the modal does not render it ([UserService] `create`). It sees the accounts it created, an Admin's promotion of one hiding it.
- ✅ Accepted exposure: a creator who is no Admin can tell whether an email address or a username is taken by an account it cannot see, since the Add User form says so on the field (`EMAIL_TAKEN`, `USERNAME_TAKEN`). The creator needs the message to pick another, and Admins choose who holds Create Users and Create Guests.
- ✅ An email address and a username each name one account whatever their case or Unicode form (`account_01` on `email_key`, `account_02` on `username_key`, the name keys folded in Scala, never by SQL), and a login compares the key column of what it names, so the lookup and the index cannot differ; tested on both engines, two adds racing on one username included ([UserServiceTests]). A taken one is refused on its field in the form ([AccountManagementTests]). A username keeps to one rule, 3 to 32 ASCII letters, digits, `.`, `_` and `-`, so it never holds an "@" ([User] `validUsername`, applied by [UserService] `add` and `create` and the setup form).
- ✅ `requireAdmin` is correct and tested ([decorators] `requireAdmin`, [SettingsControllerTests]).
- ✅ The account types and the Permissions are enforced ([Permissions](#permissions)): `requireAdmin` guards the Settings page, its form and the Admin's account writes, `requireLibraryCreator` the New library dialog and its save ([LibraryActionController]), for an Admin or an account with the Create Libraries flag, and a Guest sees Browse alone. A type change by an Admin takes effect at the account's next request: to Admin its grants go, to Guest it keeps its Libraries with no Permission.
- ✅ A creator who is no Admin holds every Permission in the Library it creates, and a Library created grants every account whose new-Library switch is on the Permissions the Admin chose for it; an account at its bound of grants is skipped with a WARN, so another account's bound never fails a create, and a creator at its own bound is refused with the bound under the dialog's field, creating no Library ([RepositoryService] `addRepository`, [RepositoryServiceTests], [LibraryActionControllerTests]).
- ✅ Deleting an account never deletes a Library, and keeps every asset: in one transaction its assets in every Library become the deleting Admin's, its grants go and the accounts it created lose their creator; the importer record of each asset keeps naming it ([UserService] `delete`; `asset.owner_user_id` refuses a delete that would leave an asset without an owner, [all.sql]; [AccountManagementTests]). An edit of an account rewrites the importer records of the assets it imported, in every Library.
- ✅ A second account lands in a Library it can see. Login and `/` open the account's last active Library, read from the database (the token carries none), while the account can see it, and otherwise the first Library it can see by name, which is recorded ([RepositoryService] `landingRepositoryOf`, [SessionController], [IndexController]). An account that can see no Library is answered a plain-text 404, "There is no library to open", not a 500.
- ✅ An import in flight escapes no account write. The importer record is built from the account as stored when the import inserts the asset, in the insert's transaction, which share-locks the account's row ([LibraryService] `persistAndIndex`, [UserService] `lockImporter`), so an update or a delete of the account waits for the insert and then reaches its asset; a file whose account was deleted or deactivated while it waited fails with a clear import error, "The account that imported this file no longer exists" or "is deactivated" ([AssetImportServiceTests], both engines). Accepted: such a file is dropped, not given to the deleting Admin.
- [ ] `User.forgetMe` is a leftover `println` stub ([User]). [LOW]

### Sharing with clients

- [ ] Clients have no way in. On a LAN/VPN-only server, either clients join the VPN, or the share-link path alone is exposed (see [Deployment](#deployment)).
  - Fix: expose only the share route, through Tailscale Funnel or Cloudflare Tunnel, and document it with the install.
- ✅ The signing primitive is solid. [ContentSigner] computes an HMAC-SHA256 over `path\n<exp>`, compares it with `MessageDigest.isEqual`, is pinned by an openssl known-answer test, and uses a race-safe persisted key ([SystemService] `loadOrCreateSigningKey`). Signed requests reuse the hardened content path and its safe logging ([decorators] `signedOnly`), and a signature opens one item as one data type ([ContentViewControllerTests]).
- [ ] Share links are only half-built. Nothing calls `sign()`. There is no issuer, no expiry cap, no password, no album scope and no per-link revocation: rotating the key, the only revocation, revokes every link. Effort: M.
  - Fix: a `share_link` table: asset or album scope, expiry with a server-side maximum, optional password, revoked flag. Sign `linkId|path` with a key ID for rotation.
- [ ] Shared originals carry their EXIF and GPS. Previews and face crops are re-encoded WebP without metadata, but a signed original is served as stored. Effort: S–M.
  - Fix: serve shares without metadata by default. `requireLoginOrSignature` ([decorators]) already tells shares from owners, so a watermark could also apply to shares only.
- [ ] The audit log records the account events alone. The append-only `audit_event` table holds each with its actor and target as snapshots, in the transaction of its change, logged at INFO once that commits ([AuditService] `record`, [AuditServiceTests]): logins, successful and failed, with the client IP ([UserService] `loginAndSetUser`), and accounts created, edited, deactivated, reactivated and deleted, Permission and type changes and passwords set, with the client IP of the request ([UserService], [AccountManagementTests]). Nothing else is recorded: a refused repository, a non-Admin and a bad signature are WARNs ([decorators]), and recycling, purging, Settings saves and shares are not audited. There is no Admin view. Effort: M.
  - Fix: record recycle and purge, settings saves and share creation, with an Admin view.

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

- [ ] Nothing slows password guessing: each guess is a bcrypt check (a CPU cost too). A failed login is logged at WARN with the client IP ([SessionController]) and recorded in the audit log ([UserService] `loginAndSetUser`), so fail2ban can use the log (the typed login escaped and cut to 64 characters, so it cannot forge a line); behind a reverse proxy the IP is the proxy's, since `X-Forwarded-For` is not read. Every failed login also adds an `audit_event` row, with up to 64 characters of the login as typed: the table grows without bound under guessing, and a password typed into the login field by mistake is kept in it. A login with a control character is a failed login, never looked up. A wrong current password given to change one's own password is not slowed either: it is logged at WARN and recorded as a failed change with the client IP ([UserService] `changeOwnPassword`), so a stolen session cannot guess the password unseen, but it can guess. [HIGH]
  - Fix: rate limiting per IP and per account (an in-process token bucket, or Bucket4j), over logins and own password changes, which also bounds the audit rows; retention for failed-login events.
- [ ] No 2FA: `account` has only `password_hash`, and there is no credential table and no WebAuthn route. Effort: M.
  - Fix: passkeys as the main login through Yubico java-webauthn-server or webauthn4j, with TOTP as a fallback.
- [ ] No OIDC or SSO: `/login` ([SessionController]) is the only login, with no identity provider and no trusted forward-auth header mode. Effort: M.
  - Fix: OIDC for Pocket ID, Authelia, Authentik or Keycloak, with just-in-time provisioning; it also hands 2FA and recovery to the provider. The seam is there: [PasetoService] `createToken` does not care how the user was verified.
- [ ] No personal API tokens. A Bearer header is accepted ([decorators] `authenticate`) but carries the same per-process key and 7-day claims, so it dies on restart and on any change to its account (a stale Bearer token cannot be replaced, so it is refused), with no scopes or listing ([LoginControllerTests]). Effort: M.
  - Fix: after server-side sessions: hashed opaque tokens, or PASETO v4 with a `jti` checked against the database and a persisted key; name, scopes such as `repo:read` and `upload`, expiry, last used, revoke. Newer: Biscuit tokens for capability-style scopes.
- [ ] No groups: Permissions are granted per account and Library, and no set of accounts shares a grant. Effort: L.

### Library and media

- ✅ Uploads show progress and an import status over a WebSocket.
- ✅ Cancelling an upload closes its Import: the page stops sending and posts to the Import's close route ([upload-run.js], [ImportController] `closeImport`, tested in [ImportControllerTests]); leaving the page closes it too, by a beacon.
- ✅ A dropped connection costs one file, not the upload: each file is a request of its own, sent again twice and then recorded failed while the rest go on ([upload-run.js]), and choosing the same files again skips the ones already in the Library as duplicates.
- [ ] An upload that its page never closes stays in progress. The page closes its Import when it finishes, on Cancel and, by a beacon, when it is left; a browser that crashes or loses the network does none of those, and nothing times an Import out, so it heads the Import history as in progress, with files remaining, until the next server start closes it ([ImportService] `failInterrupted`). [LOW]
  - Fix: close an upload's Import that has taken no file for a while, on the daily schedule.
- [ ] A single file's upload cannot resume: a connection dropped at 95% of a large video sends that file again from the start. Effort: M.
  - Fix: tus resumable uploads with Uppy on the client, one asset per upload.
- [ ] No export, so the library is locked in. Originals download one at a time; XMP is only read on import ([MetadataExtractionService] `extract`); names, folders, albums, people, Locations and metadata exist only in the database. An RFC 6266 `Content-Disposition` encoder and the signature-gated content route are already there. Effort: M–L.
  - Fix: a streamed ZIP64 export of a selection, album or folder under original names and folder paths, then XMP sidecars (MWG face regions, Locations, albums, metadata) through the Adobe XMP Toolkit.
- [ ] No near-duplicate detection: no perceptual hash or embedding. Exact duplicates are under [Data safety](#data-safety). Effort: L.
- [ ] No integrity checks: nothing re-hashes stored originals to catch bitrot or flags missing files. The scheduled jobs are the hourly SQLite `optimize`, the daily Import history prune ([Altitude]) and the Watch folder scans ([WatchFolderService]), so the scheduler is there to run a sweep. Effort: S–M.
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
- ✅ The Scene models' licences allow commercial use: DINOv2 ViT-B/14 (`facebook/dinov2-base`) is Apache 2.0 and the CLIP ViT-B/16 image tower (`openai/clip-vit-base-patch16`) is MIT, as [fetch-models.py] records beside each checkpoint.
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

- `debugMode = false` and an error handler ([Running and upgrading](#running-and-upgrading)).
- Refuse to start on an unknown `ENV` ([Login and sessions](#login-and-sessions)).
- A shutdown hook that calls `cleanup()` ([Running and upgrading](#running-and-upgrading)).
- One security-headers decorator and a `Sec-Fetch-Site` check on writes ([Web security](#web-security)).
- Undertow size caps and a max-pixels Setting ([Malicious files](#malicious-files)).
- SECURITY.md, a LICENSE file, Renovate, OSV-Scanner; drop `commons-fileupload` ([Operations and hygiene](#operations-and-hygiene)).

**Before real data:**

- Freeze the schema and start versioned migrations, with an upgrade test and a snapshot before each one ([Data safety](#data-safety)).
- A written, tested backup and restore ([Data safety](#data-safety)).
- Keep staged uploads across a restart and resume them ([Running and upgrading](#running-and-upgrading)).
- A Docker image and compose file, with a configurable host and port ([Running and upgrading](#running-and-upgrading)).

**Before clients:**

- Server-side sessions ([Login and sessions](#login-and-sessions)).
- Share links, and an audit log beyond the account events ([Sharing with clients](#sharing-with-clients)).
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
[ImportStatusWsActorTests]: ../altitude/test/src/altitude/core/unit/ImportStatusWsActorTests.scala
[ImportHistoryController]: ../altitude/src/altitude/core/routes/web/partial/ImportHistoryController.scala
[WatchFolderController]: ../altitude/src/altitude/core/routes/web/partial/WatchFolderController.scala
[WatchFolderDao]: ../altitude/src/altitude/core/dao/WatchFolderDao.scala
[BaseDao]: ../altitude/src/altitude/core/dao/jdbc/BaseDao.scala
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
[SettleImportFileFlow]: ../altitude/src/altitude/core/pipeline/flows/SettleImportFileFlow.scala
[ExtractMetadataFlow]: ../altitude/src/altitude/core/pipeline/flows/ExtractMetadataFlow.scala
[BaseController]: ../altitude/src/altitude/core/routes/BaseController.scala
[decorators]: ../altitude/src/altitude/core/routes/decorators.scala
[AssetRules]: ../altitude/src/altitude/core/service/AssetRules.scala
[AssetPermissionTests]: ../altitude/test/src/altitude/core/integration/AssetPermissionTests.scala
[PermissionControllerTests]: ../altitude/test/src/altitude/core/controller/PermissionControllerTests.scala
[RangeStreaming]: ../altitude/src/altitude/core/routes/RangeStreaming.scala
[HealthController]: ../altitude/src/altitude/core/routes/api/HealthController.scala
[ContentViewController]: ../altitude/src/altitude/core/routes/web/ContentViewController.scala
[ImportController]: ../altitude/src/altitude/core/routes/web/ImportController.scala
[IndexController]: ../altitude/src/altitude/core/routes/web/IndexController.scala
[SessionController]: ../altitude/src/altitude/core/routes/web/SessionController.scala
[PeopleActionController]: ../altitude/src/altitude/core/routes/web/partial/PeopleActionController.scala
[SearchPanelController]: ../altitude/src/altitude/core/routes/web/partial/SearchPanelController.scala
[SearchResultsController]: ../altitude/src/altitude/core/routes/web/partial/SearchResultsController.scala
[SetupFormController]: ../altitude/src/altitude/core/routes/web/partial/SetupFormController.scala
[LibraryActionController]: ../altitude/src/altitude/core/routes/web/partial/LibraryActionController.scala
[OwnPasswordController]: ../altitude/src/altitude/core/routes/web/partial/OwnPasswordController.scala
[UserActionController]: ../altitude/src/altitude/core/routes/web/partial/UserActionController.scala
[UsersController]: ../altitude/src/altitude/core/routes/web/UsersController.scala
[AccountManagementTests]: ../altitude/test/src/altitude/core/integration/AccountManagementTests.scala
[AssetImportServiceTests]: ../altitude/test/src/altitude/core/integration/AssetImportServiceTests.scala
[UsersControllerTests]: ../altitude/test/src/altitude/core/controller/UsersControllerTests.scala
[LibraryActionControllerTests]: ../altitude/test/src/altitude/core/controller/LibraryActionControllerTests.scala
[AccountVersions]: ../altitude/src/altitude/core/service/AccountVersions.scala
[AssetService]: ../altitude/src/altitude/core/service/AssetService.scala
[AuditService]: ../altitude/src/altitude/core/service/AuditService.scala
[BaseService]: ../altitude/src/altitude/core/service/BaseService.scala
[ContentSigner]: ../altitude/src/altitude/core/service/ContentSigner.scala
[FolderService]: ../altitude/src/altitude/core/service/FolderService.scala
[ImportPipelineService]: ../altitude/src/altitude/core/service/ImportPipelineService.scala
[LibraryService]: ../altitude/src/altitude/core/service/LibraryService.scala
[ImportService]: ../altitude/src/altitude/core/service/ImportService.scala
[WatchFolderService]: ../altitude/src/altitude/core/service/WatchFolderService.scala
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
[ImportParallelism]: ../altitude/src/altitude/core/util/ImportParallelism.scala
[fetch-models.py]: ../scripts/fetch-models.py
[Sha256]: ../altitude/src/altitude/core/util/Sha256.scala
[RelPath]: ../altitude/src/altitude/core/util/RelPath.scala
[Util]: ../altitude/src/altitude/core/util/Util.scala
[AssetControllerTests]: ../altitude/test/src/altitude/core/controller/AssetControllerTests.scala
[ContentViewControllerTests]: ../altitude/test/src/altitude/core/controller/ContentViewControllerTests.scala
[LoginTokenTests]: ../altitude/test/src/altitude/core/integration/LoginTokenTests.scala
[ImportControllerTests]: ../altitude/test/src/altitude/core/controller/ImportControllerTests.scala
[ImportServiceTests]: ../altitude/test/src/altitude/core/integration/ImportServiceTests.scala
[WatchFolderServiceTests]: ../altitude/test/src/altitude/core/integration/WatchFolderServiceTests.scala
[WatchFolderControllerTests]: ../altitude/test/src/altitude/core/controller/WatchFolderControllerTests.scala
[LoginControllerTests]: ../altitude/test/src/altitude/core/controller/LoginControllerTests.scala
[RouteTableTests]: ../altitude/test/src/altitude/core/controller/RouteTableTests.scala
[SettingsControllerTests]: ../altitude/test/src/altitude/core/controller/SettingsControllerTests.scala
[ImportPipelineServiceTests]: ../altitude/test/src/altitude/core/integration/ImportPipelineServiceTests.scala
[LibraryServicePruneTests]: ../altitude/test/src/altitude/core/integration/LibraryServicePruneTests.scala
[LibraryServiceRecycleTests]: ../altitude/test/src/altitude/core/integration/LibraryServiceRecycleTests.scala
[PurgePipelineServiceTests]: ../altitude/test/src/altitude/core/integration/PurgePipelineServiceTests.scala
[RepositoryIsolationTests]: ../altitude/test/src/altitude/core/integration/RepositoryIsolationTests.scala
[RepositoryServiceTests]: ../altitude/test/src/altitude/core/integration/RepositoryServiceTests.scala
[UserServiceTests]: ../altitude/test/src/altitude/core/integration/UserServiceTests.scala
[AuditServiceTests]: ../altitude/test/src/altitude/core/integration/AuditServiceTests.scala
[SearchMapTests]: ../altitude/test/src/altitude/core/integration/SearchMapTests.scala
[TransactionBoundaryTests]: ../altitude/test/src/altitude/core/unit/TransactionBoundaryTests.scala
[TestVideos]: ../altitude/test/src/altitude/test/TestVideos.scala
[index.scala.html]: ../altitude/views/index.scala.html
[result_cell.scala.html]: ../altitude/views/htmx/result_cell.scala.html
[trashbin_header.scala.html]: ../altitude/views/htmx/trashbin_header.scala.html
[watch_folders.scala.html]: ../altitude/views/htmx/watch_folders.scala.html
[watch_folder_form.scala.html]: ../altitude/views/htmx/watch_folder_form.scala.html
[core.css]: ../altitude/static/css/core.css
[global.js]: ../altitude/static/js/global.js
[album-list.js]: ../altitude/static/js/common/album-list.js
[location-list.js]: ../altitude/static/js/common/location-list.js
[snackbar.js]: ../altitude/static/js/common/snackbar.js
[upload-run.js]: ../altitude/static/js/import/upload-run.js
[map-view.js]: ../altitude/static/js/map/map-view.js
[box-selection.js]: ../altitude/static/js/search-results/box-selection.js
[detail-navigator.js]: ../altitude/static/js/search-results/detail-navigator.js
