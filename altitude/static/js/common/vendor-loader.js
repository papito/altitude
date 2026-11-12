/**
 * Loads the vendored plain-script libraries that only some views need (static/js/lib/README.md) the
 * first time one of those views asks, so no page waits for them before it renders: Leaflet and its
 * stylesheet for the map view and the Location pin editor, supercluster for the map view,
 * media-chrome for a Video in asset detail, Flatpickr and its stylesheet for a date range field
 * (the advanced search panel's). Each file is requested once per page and every caller
 * awaits the same load; a failed load is forgotten, so the next caller tries again. A library with
 * an ES module build is a dynamic `import()` instead.
 */
const loads = new Map()

function load(url, createEl) {
    if (!loads.has(url)) {
        const loaded = new Promise((resolve, reject) => {
            const el = createEl()
            el.addEventListener("load", resolve, { once: true })
            el.addEventListener(
                "error",
                () => {
                    loads.delete(url)
                    el.remove()
                    reject(new Error(`Could not load ${url}`))
                },
                { once: true },
            )
            document.head.append(el)
        })
        loads.set(url, loaded)
    }

    return loads.get(url)
}

function script(src) {
    return load(src, () =>
        Object.assign(document.createElement("script"), { src }),
    )
}

// Appended to the head, so it follows the application's stylesheets as the static link did
function stylesheet(href) {
    return load(href, () =>
        Object.assign(document.createElement("link"), {
            rel: "stylesheet",
            href,
        }),
    )
}

/**
 * A vendored stylesheet imported into a cascade layer of its own: every rule of the application's
 * bundle, which is in no layer, outranks every rule of it, whatever their selectors and whichever
 * loads last. For a library whose look the application restyles in its own tokens.
 */
function layeredStylesheet(href, layer) {
    return load(href, () =>
        Object.assign(document.createElement("style"), {
            textContent: `@import url("${href}") layer(${layer});`,
        }),
    )
}

/** `window.L`, with the stylesheet its panes and controls need to lay out */
export function loadLeaflet() {
    return Promise.all([
        script("/static/js/lib/leaflet.js"),
        stylesheet("/static/css/leaflet.css"),
    ])
}

/** `window.L` and `window.Supercluster` */
export function loadMapLibraries() {
    return Promise.all([
        loadLeaflet(),
        script("/static/js/lib/supercluster.min.js"),
    ])
}

/** The `<media-*>` custom elements of asset detail's video player; markup already on the page upgrades */
export function loadMediaChrome() {
    return script("/static/js/lib/media-chrome.js")
}

/**
 * `window.flatpickr`, with the stylesheet its calendar needs, in the `flatpickr` layer: the
 * `dateRange` component's, whose calendar `static/css/htmx/search_panel.css` restyles
 */
export function loadFlatpickr() {
    return Promise.all([
        script("/static/js/lib/flatpickr.min.js"),
        layeredStylesheet("/static/css/flatpickr.css", "flatpickr"),
    ])
}
