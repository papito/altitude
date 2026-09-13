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

htmx configuration lives in the `htmx-config` meta tag in `views/includes/header_common.scala.html`.
Extensions activate by script inclusion (`hx-ext` no longer exists): `json-enc.js` is loaded by the
pages with JSON forms, `hx-ws.js` by the import pipeline page. The app uses no morph swaps, so htmx's
`hx-alpine-compat.js` extension is not loaded: Alpine's own mutation observer initializes swapped-in
markup.

Leaflet is loaded as a plain script (`window.L`, like interact.js) by `index.scala.html`, for the
Location pin editor (`js/fragments/location-editor.js`) and the map view; supercluster (`window.Supercluster`)
the same way, for the map view's on-screen clustering (`js/map/map-view.js`).

The focus plugin is registered with `Alpine.plugin(focus)` in `static/js/app.js` before Alpine starts;
the modal hosts in `views/includes/html_common.scala.html` rely on its `x-trap` directive.
`alpine-focus.esm.js` has a local patch that cancels delayed trap activation when the trap is
released or its element is removed, preventing a closed dialog from intercepting Tab.
