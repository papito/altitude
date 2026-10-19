# Vendored front-end libraries

There is no bundler and no npm runtime dependency. Each file is copied verbatim from the
upstream release listed here so it can be checked byte-for-byte against its source. Update
this table whenever a file is replaced. `.prettierignore` excludes this directory.

| File                  | Library                           | Version                | Source                                                                                                           |
|-----------------------|-----------------------------------|------------------------|------------------------------------------------------------------------------------------------------------------|
| `htmx.min.js`         | htmx                              | 4.0.0                  | `dist/htmx.min.js` in https://registry.npmjs.org/htmx.org/-/htmx.org-4.0.0.tgz                                    |
| `hx-ws.js`            | htmx WebSocket extension          | 4.0.0                  | `dist/ext/hx-ws.js` in the same tarball                                                                          |
| `json-enc.js`         | htmx 4 community `json-enc`       | commit `9a8186c` (2026-03-04) | https://github.com/bigskysoftware/htmx-4-community-extensions/blob/9a8186c71ac612fd398c2a7a27fb80534c1bd886/src/json-enc/json-enc.js |
| `alpine.esm.min.js`   | Alpine.js                         | 3.13.10                | https://www.npmjs.com/package/alpinejs                                                                           |
| `alpine-focus.esm.js` | Alpine.js focus plugin (`x-trap`) | 3.13.10                | `dist/module.esm.js` in https://registry.npmjs.org/@alpinejs/focus/-/focus-3.13.10.tgz (bundles focus-trap 6.9.4 and tabbable 5.3.3, MIT) |
| `axios.min.js`        | axios                             | 1.14.0                 | https://www.npmjs.com/package/axios                                                                              |
| `interact.min.js`     | interact.js                       | 1.10.27                | https://www.npmjs.com/package/interactjs                                                                         |
| `split.es.js`         | Split.js                          | not recorded           | https://www.npmjs.com/package/split.js                                                                           |
| `leaflet.js`          | Leaflet                           | 1.9.4                  | `dist/leaflet.js` in https://registry.npmjs.org/leaflet/-/leaflet-1.9.4.tgz, byte-identical; its BSD-2 license is `leaflet.LICENSE`. `dist/leaflet.css` of the same tarball is `static/css/leaflet.css`; the marker images it references are not vendored, every pin is an `L.divIcon` |
| `supercluster.min.js` | supercluster                      | 9.1.0                  | `dist/supercluster.min.js` in https://registry.npmjs.org/supercluster/-/supercluster-9.1.0.tgz, byte-identical (kdbush is bundled); its ISC license is `supercluster.LICENSE` |
| `viselect.esm.js`     | Viselect (`@viselect/vanilla`)    | 3.9.0                  | `dist/viselect.mjs` in https://registry.npmjs.org/@viselect/vanilla/-/vanilla-3.9.0.tgz, byte-identical; renamed to `.js` so it is served as JavaScript. Its MIT license is `viselect.LICENSE` |
| `media-chrome.js`     | media-chrome                      | 4.19.2                 | `dist/iife/index.js` in https://registry.npmjs.org/media-chrome/-/media-chrome-4.19.2.tgz, byte-identical, renamed; its MIT license is `media-chrome.LICENSE` |
| `tokentweak.js`       | tokentweak                        | 0.3.0                  | `dist/tokentweak.js` in `tokentweak-0.3.0.tgz` (`npm pack` of the author's own package, built from `~/projects/tokentweak`; not on the registry yet), byte-identical; its MIT license is `tokentweak.LICENSE`. Refreshed by `make vendor-tokentweak`, below |
| `tokentweak-boot.js`  | tokentweak boot script            | 0.3.0                  | `dist/boot.js` in the same tarball, byte-identical, renamed                                                      |

`tokentweak.js` and `tokentweak-boot.js` are dev only: `views/includes/header_common.scala.html` references them
inside `@if(Environment.isDev)` and nothing else does. The boot file is a plain blocking script for the head, not
`defer`: it reads the storage key from the `data-key` of its own script tag and adopts the stored override
stylesheet before the first paint, so a page never flashes the untweaked look. The module is imported by the
adapter `js/design-tweaks.js`, which configures the panel for Altitude (see **Design tweaks** in `views/AGENTS.md`).

These two files and the license are the one library with a make target, `make vendor-tokentweak`
(`scripts/vendor-tokentweak.sh`), because the package is the author's own and changes with the app. It unpacks a
tarball in a temporary directory and copies `dist/tokentweak.js`, `dist/boot.js` and `LICENSE` here under their
vendored names, leaving a file alone when it is already identical; nothing is installed. `TOKENTWEAK` says which
tarball: a registry version (`make vendor-tokentweak TOKENTWEAK=0.3.0`; the default is `latest`), an `npm pack`
tarball (`TOKENTWEAK=../tokentweak/tokentweak-0.3.0.tgz`), or a checkout (`TOKENTWEAK=../tokentweak`), which is
packed as it stands, so run `npm run build` there first. The target then says whether the two rows above already
record the version it vendored; updating them is still by hand, as for every other library.

htmx configuration lives in the `htmx-config` meta tag in `views/includes/header_common.scala.html`.
Extensions activate by script inclusion (`hx-ext` no longer exists): `json-enc.js` is loaded by the
pages with JSON forms, `hx-ws.js` by the import pipeline page. The app uses no morph swaps, so htmx's
`hx-alpine-compat.js` extension is not loaded: Alpine's own mutation observer initializes swapped-in
markup.

The plain scripts the pages link are `defer`, except `htmx.min.js`: run while the page is still parsing, htmx
starts on DOMContentLoaded, after its deferred extensions and the modules, where a deferred htmx would start
before them. Leaflet is a plain script (`window.L`, like interact.js) for the Location pin editor
(`js/fragments/location-editor.js`) and the map view, and supercluster (`window.Supercluster`) for the map
view's on-screen clustering (`js/map/map-view.js`); `js/common/vendor-loader.js` loads them, with
`static/css/leaflet.css`, when the first editor or map asks. media-chrome, a plain script defining the
`<media-*>` custom elements of asset detail's video player, is loaded the same way by the first Video shown
(`js/search-results/detail-navigator.js`); the player's markup is on the page before and upgrades then. No module
imports these three.

The focus plugin is registered with `Alpine.plugin(focus)` in `static/js/app.js` before Alpine starts;
the modal hosts in `views/includes/html_common.scala.html` rely on its `x-trap` directive.
`alpine-focus.esm.js` has two local patches. One cancels delayed trap activation when the trap is
released or its element is removed, preventing a closed dialog from intercepting Tab. The other
passes `tabbableOptions: { getShadowRoot: true }` to every trap, so controls inside open shadow
roots count as inside it: without it Tab cannot enter the sliders of asset detail's video player,
whose inputs are in shadow DOM, and focus is pulled back to the control before them.

## Adding a library

1. Copy the upstream release file unchanged, renaming it only so it is served as JavaScript (`.mjs` → `.js`),
   and add its row to the table with the tarball path it came from. Its license goes next to it as
   `<name>.LICENSE` unless the file's own header carries it. A local patch is described in this README.
2. Prefer the library's ES module build, and load it by where it is needed:
   - by modules on every page that uses it: a static `import` from `lib/` (Alpine, Viselect);
   - by some views only: `await import("../lib/<file>.js")` where the view first needs it, so no page
     waits for it and the browser fetches and runs it once.
3. A plain script (one that defines a global) is loaded by where it is needed:
   - at startup: a `defer` `<script>` in the templates of the pages that use it, or in
     `views/includes/header_common.scala.html` for every page (axios, interact.js);
   - by some views only: a `load…()` export in `js/common/vendor-loader.js` (Leaflet, supercluster,
     media-chrome), awaited by the view.

   Either way, its global goes in `eslint.config.js`. An htmx extension is a `defer` script on the pages that
   use it, which runs before htmx starts.
4. A library's stylesheet is copied unchanged to `static/css/<name>.css` and linked on its own, or loaded with
   the script through `vendor-loader.js` (`leaflet.css`). It is never `@import`ed into `static/css/app.css`: the
   bundle is served from `/assets/`, where the stylesheet's relative URLs (fonts, images under `static/`) would
   not resolve, and it would no longer match its source. Its file name goes in `StyleGuideController.excludedFiles`,
   so the style guide's token audit does not list its color literals as the app's.
5. Caching needs nothing: files under `/static/` keep their upstream names and are revalidated on every use, so a
   replaced file is picked up on the next load.
6. Add the library to the list under **Stack** in `views/AGENTS.md`, and a new `vendor-loader.js` export to that
   file's row there.
