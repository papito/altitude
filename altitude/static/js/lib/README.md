# Vendored front-end libraries

There is no bundler and no npm runtime dependency. Each file is copied verbatim from the
upstream release listed here so it can be checked byte-for-byte against its source. Update
this table whenever a file is replaced. `.prettierignore` excludes this directory.

| File                  | Library                           | Version                | Source                                                                                                           |
|-----------------------|-----------------------------------|------------------------|------------------------------------------------------------------------------------------------------------------|
| `htmx.min.js`         | htmx                              | 4.0.0                  | `dist/htmx.min.js` in https://registry.npmjs.org/htmx.org/-/htmx.org-4.0.0.tgz                                    |
| `hx-ws.js`            | htmx WebSocket extension          | 4.0.0                  | `dist/ext/hx-ws.js` in the same tarball                                                                          |
| `hx-alpine-compat.js` | htmx Alpine.js integration        | 4.0.0                  | `dist/ext/hx-alpine-compat.js` in the same tarball                                                               |
| `json-enc.js`         | htmx 4 community `json-enc`       | commit `9a8186c` (2026-03-04) | https://github.com/bigskysoftware/htmx-4-community-extensions/blob/9a8186c71ac612fd398c2a7a27fb80534c1bd886/src/json-enc/json-enc.js |
| `alpine.esm.min.js`   | Alpine.js                         | 3.13.10                | https://www.npmjs.com/package/alpinejs                                                                           |
| `alpine-focus.esm.js` | Alpine.js focus plugin (`x-trap`) | 3.13.10                | `dist/module.esm.js` in https://registry.npmjs.org/@alpinejs/focus/-/focus-3.13.10.tgz (bundles focus-trap 6.9.4 and tabbable 5.3.3, MIT) |
| `axios.min.js`        | axios                             | 1.14.0                 | https://www.npmjs.com/package/axios                                                                              |
| `interact.min.js`     | interact.js                       | 1.10.27                | https://www.npmjs.com/package/interactjs                                                                         |
| `split.es.js`         | Split.js                          | not recorded           | https://www.npmjs.com/package/split.js                                                                           |

htmx configuration lives in the `htmx-config` meta tag in `views/includes/header_common.scala.html`.
Extensions activate by script inclusion (`hx-ext` no longer exists): `json-enc.js` is loaded by the
pages with JSON forms, `hx-ws.js` by the import pipeline page, `hx-alpine-compat.js` by the main page.

The focus plugin is registered with `Alpine.plugin(focus)` in `static/js/app.js` before Alpine starts;
the modal hosts in `views/includes/html_common.scala.html` rely on its `x-trap` directive.
`alpine-focus.esm.js` has a local patch that cancels delayed trap activation when the trap is
released or its element is removed, preventing a closed dialog from intercepting Tab.
