/**
 * Loads the vendored plain-script libraries that only some views need on first use, so no page waits
 * for them before it renders. Each file is requested once per page and every caller awaits the same
 * load; a failed load is forgotten, so the next caller retries. A library with an ES module build is
 * a dynamic `import()` instead.
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

// Appended to the head, so it comes after the application's stylesheets
function stylesheet(href) {
    return load(href, () =>
        Object.assign(document.createElement("link"), {
            rel: "stylesheet",
            href,
        }),
    )
}

/**
 * A vendored stylesheet in a cascade layer of its own, so every rule of the application's unlayered
 * bundle outranks it whatever the selectors and load order. For a library the application restyles.
 */
function layeredStylesheet(href, layer) {
    return load(href, () =>
        Object.assign(document.createElement("style"), {
            textContent: `@import url("${href}") layer(${layer});`,
        }),
    )
}

/** `window.L`, with the stylesheet its panes and controls need */
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

/** The `<media-*>` custom elements; markup already on the page upgrades once they load */
export function loadMediaChrome() {
    return script("/static/js/lib/media-chrome.js")
}

/** `window.flatpickr`, its stylesheet in the `flatpickr` layer so `search_panel.css` can restyle it */
export function loadFlatpickr() {
    return Promise.all([
        script("/static/js/lib/flatpickr.min.js"),
        layeredStylesheet("/static/css/flatpickr.css", "flatpickr"),
    ])
}
