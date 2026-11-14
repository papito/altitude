# Immutable static files: a build version in the path

## Goals

- A page load whose static files are already in the browser's cache sends no request for any of them: JS modules, plain
  scripts, vendored stylesheets and webfonts.
- An upgrade is picked up on the next page load with no user action: the pages link new URLs, and nothing stale is ever
  served under them.
- Development is unchanged: with `ENV=dev` an edited JS file shows on the next reload, with no restart.
- No source file is rewritten by the build, and there is still no bundler and no npm runtime dependency.

Out of scope: per-file content hashes and import maps, `modulepreload` links for cold loads, bundling or minifying, HTTP/2 or
TLS, the stylesheet bundle (`/assets/app.<hash>.css`, already immutable), asset content (`/content/`, already immutable).

## Measurements the design rests on

Every file under `/static/` is served `Cache-Control: no-cache` with a weak ETag (`decorators.revalidated`), so the browser
sends a conditional request for each one on every page load and gets a 304. The JS modules are unbundled ES modules; the
counts below are each page's static import graph, read from the sources.

| Page | JS modules | Source bytes | Import depth |
|---|---|---|---|
| Main (`index`) | 133 | 1.1 MB | 5 |
| Import (`pipeline`) | 53 | 395 KB | 4 |
| Users | 50 | 363 KB | 4 |
| Settings | 48 | 354 KB | 4 |
| Setup | 24 | 223 KB | 3 |
| Login | 22 | 216 KB | 3 |

On top of the modules each page revalidates its plain scripts (`theme-boot.js`, `htmx.min.js`, `axios.min.js`, and per page
`json-enc.js`, `hx-ws.js`, `interact.min.js`, `explorer-dock-boot.js`), `font-awesome.min.css` and the webfonts that stylesheet
loads. Over HTTP/1.1 the browser sends these six at a time, and a module's imports are only known once it has answered, so the
graph is walked level by level.

The time this costs is not measured. On a LAN it is small; over a VPN each wave of six costs a round trip.

## Decisions

| Concern | Decision |
|---|---|
| What makes a URL immutable | One build version for everything under `static/`, as a path segment: `/static/v-<version>/js/global.js`. A file's URL changes whenever any static file changes |
| The version | The first 8 hex characters of a SHA-256 over every file under `altitude/static/`, each file's relative path and bytes, in path order. Computed by the build, as the stylesheet bundle's hash is |
| How the server learns it | A one-line classpath resource, `static-version`, at the classpath root beside `static/`, written by the `resources` task and read once at startup. It is not compiled into `Assets`, so a JS edit recompiles nothing. A missing resource fails startup with a message naming it |
| Why no source is rewritten | Everything a static file references is relative: module imports, the `../webfonts/` URLs of `font-awesome.min.css`, the setup page's Worker (`new URL("./setup-background-worker.js", import.meta.url)`) and its imports. A file loaded under the prefix resolves all of them under the prefix |
| The versioned URL, current version | `200`, `Cache-Control: public, max-age=31536000, immutable`, `Vary: Accept-Encoding`, no ETag. The same header `/assets/:name` sends |
| The versioned URL, any other version | Answered as the plain path is: the current file, `no-cache` with an ETag. A page left open across an upgrade can still load a library it asks for late (Leaflet, supercluster, media-chrome, Flatpickr) rather than fail until reloaded |
| The plain URL (`/static/js/...`) | Unchanged: revalidated |
| A missing file | `404` with neither `Cache-Control` nor `ETag`, under either form |
| Dev | `ENV=dev` pages link the plain URLs, so nothing a developer edits is ever cached as immutable. Every other environment, tests included, links the versioned URLs |
| The URL helper | `Util.staticUrl(path, isDev = Environment.isDev)` beside `Util.contentUrl`, hand-written rather than generated, with the environment as a defaulted parameter as `Environment.devSetting` takes it, so a unit test covers both forms |
| One URL per module | A module reached under two URLs is two instances with separate state. Every reference to a static file goes through the helper (templates) or is relative to `import.meta.url` (JS); no template or module holds a `/static/` literal, and a test enforces it |
| Routing | One route owns `/static/`. Cask hands a subpath route every path below it and refuses a second route under it (`DispatchTrie`: "Routes overlap with subpath capture"), so the handler reads the version from the first remaining segment |

## Constraints

- Vendored files stay byte-identical to their upstream release (`static/js/lib/README.md`).
- `/static/` stays a public route with no login, as `RouteTableTests` and `docs/READINESS.md` declare it.
- No schema change, no new dependency.
- A `.` or `..` path segment is dropped before the classpath lookup, as Cask's `StaticUtil.makePathAndContentType` does now.

## Changes

Each group starts with its tests, red first.

### A. The version

1. **`build.mill`.** A task hashing the tree `staticResources()` points at (relative path and bytes of each file, sorted by
   path), giving the 8-character version. `resources` writes it to `static-version` at the root of its destination, beside the
   `static/` copy. The scaladoc says what the version covers and that the server reads it at startup.
2. **Reading it (`../../altitude/src/altitude/core/util/Util.scala`, or a small object beside `Environment` if it reads
   better).** The version read once from the classpath resource; absent or empty, startup fails with a message naming
   `static-version` and the `resources` task. Logged at INFO at startup.

### B. Serving

3. **Tests (`../../altitude/test/src/altitude/core/controller/StaticControllerTests.scala`).** Beside the four that exist:
   - a file under the current version answers 200 with its bytes, the immutable header and `Vary: Accept-Encoding`, and no ETag;
   - the same file under another well-formed version (`v-00000000`) answers 200 with the same bytes, `no-cache` and an ETag,
     and 304 when asked again with that ETag;
   - a missing file under the current version answers 404 with neither header;
   - a webfont under the current version is immutable too.
4. **`decorators.scala`.** The two header rules (`cachedForever`'s and `revalidated`'s) become functions both decorators call,
   and the static route picks between them per request: immutable when the path's first segment is `v-` followed by the
   current version, revalidated otherwise. `revalidated`'s docstring names what is still revalidated: the plain path and a
   version that is not the current one.
5. **`StaticController`.** The `/static/` route takes the remaining path segments itself: a leading `v-<8 hex>` segment is the
   version and is left out of the classpath path, the rest is looked up under `static/` with `.` and `..` segments dropped and
   the content type probed as Cask's `StaticUtil` does. `RouteTableTests`'s entry for `/static/` stays as it is.
6. **`Util.staticUrl`.** `staticUrl("js/global.js")` is `/static/js/global.js` in dev and `/static/v-<version>/js/global.js`
   otherwise. A unit test covers both, with `isDev` passed.

### C. Linking

7. **Templates.** Every `/static/` literal becomes `@Util.staticUrl(...)`: `includes/header_common` (five links, and the two
   inside the Twirl comment for the design tweaks panel), `index`, `pipeline`, `settings`, `users`, `setup` and `style_guide`,
   the specifiers of the inline module scripts of `index` (four imports) and `style_guide` (two) included.
8. **`common/vendor-loader.js`.** Its six absolute URLs become `new URL("../lib/leaflet.js", import.meta.url).href` and the
   like (`../../css/` for the two stylesheets), so a late library loads from the prefix the module itself came from.
9. **`StyleGuideControllerTests`.** Its two assertions on `/static/js/lib/...` expect the helper's URL.

### D. Guards and docs

10. **No `/static/` literal (unit bundle, sources found through `ALTITUDE_SOURCE_DIR` as `StyleGuideScanTests` finds them).**
    Fails, naming the file and line, when a template under `altitude/views/` or a module under `altitude/static/js/` outside
    `lib/` holds a quoted string starting with `/static/`.
11. **A page links the current version (`StaticControllerTests`).** The login page, fetched with no login, links every static
    file under `/static/v-<version>/`, and the entry module answers immutable at the linked URL.
12. **Docs.** `altitude/AGENTS.md`: the paragraph on how static files are served (the version, the two forms of a URL, dev) and
    the key-files row of `decorators.scala`. `altitude/views/AGENTS.md`: the rule that a template links static files through
    `Util.staticUrl` and a module refers to them relative to `import.meta.url`, and why (one URL per module).
    `static/js/lib/README.md`: how `vendor-loader.js` names the libraries it loads.

## Verification

- `make test-unit`, `make test-controllers`, `make test-js` and `make lint` pass.
- Dev (`make watch`, port 8080): a page's sources link `/static/js/...` as now, and an edited module shows on the next reload.
- An assembled jar on another port (`make publish`): the Import page's first load fetches each static file once under
  `/static/v-<version>/`; a second load sends no request for any of them. After a change to any static file and a rebuild, the
  version in the page's links differs and the changed file is served.
- With a page left open on the old version, opening the map still loads Leaflet.
