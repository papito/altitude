/**
 * The page side of the setup page's background scene. A module Worker draws it on the transferred canvas, so the page's
 * thread draws nothing; this module gives the Worker what a Worker cannot read for itself (the palette, the canvas's size
 * and pixel ratio, the tab's visibility, the reduced-motion preference) and keeps it posted. Every message is a plain
 * object with a `type`.
 */

/**
 * The color `token` paints on this page, as sRGB `[r, g, b]` of 0–255. An inline `color: var(--token)` makes the browser
 * resolve the token against the page's theme, which a Worker, having no stylesheet, cannot. The Worker applies its own
 * alphas, so it needs channels, and the computed string's form varies (`rgb()`, `color(srgb …)`, `oklab(…)`): painting it
 * into a one-pixel scratch context and reading the pixel back is the parse.
 */
function probeColor(canvas, context, token) {
    canvas.style.color = `var(${token})`
    const color = getComputedStyle(canvas).color
    canvas.style.color = ""

    // Cleared first, so a translucent color does not blend with the one before it
    context.clearRect(0, 0, 1, 1)
    context.fillStyle = color
    context.fillRect(0, 0, 1, 1)
    const [r, g, b] = context.getImageData(0, 0, 1, 1).data
    return [r, g, b]
}

/** The scene's palette, resolved once: the navigation lights are the status colors, red to port and green to starboard */
function resolvePalette(canvas) {
    const scratch = document.createElement("canvas")
    scratch.width = 1
    scratch.height = 1
    // The pixel is read back once per token; the hint keeps it a cheap CPU read, which Chrome otherwise warns about
    const context = scratch.getContext("2d", { willReadFrequently: true })
    const probe = (token) => probeColor(canvas, context, token)
    const field = (n) => probe(`--setup-background-field-${n}`)

    return {
        fields: [1, 2, 3, 4, 5, 6].map(field),
        fieldEdge: probe("--setup-background-field-edge"),
        road: probe("--setup-background-road"),
        cloud: probe("--setup-background-cloud"),
        cloudShadow: probe("--setup-background-cloud-shadow"),
        jet: probe("--setup-background-jet"),
        jetOutline: probe("--setup-background-jet-outline"),
        lightLeft: probe("--error-color"),
        lightRight: probe("--success-color"),
        strobe: probe("--theme-neutral-4"),
    }
}

/** The canvas's CSS size and the pixel ratio, as `init` and `resize` carry them */
function readSize(canvas) {
    const { width, height } = canvas.getBoundingClientRect()
    return { width, height, dpr: devicePixelRatio }
}

/**
 * Calls `onChange` whenever `devicePixelRatio` changes (another screen density, or a zoom). There is no event for it, so a
 * query for the current ratio fires once the ratio leaves it and arms one for the new ratio. The listener is `once`, so the
 * old query does not fire again should the ratio come back.
 */
function watchResolution(onChange) {
    const query = matchMedia(`(resolution: ${devicePixelRatio}dppx)`)
    query.addEventListener(
        "change",
        () => {
            onChange()
            watchResolution(onChange)
        },
        { once: true },
    )
}

/** Starts the scene on `canvas`, shown on the Worker's `ready` so the form never sits on a flash of blank canvas */
export function startSetupBackground(canvas) {
    // No fallback: the canvas stays empty and the page looks as it does without a scene
    if (typeof canvas.transferControlToOffscreen !== "function") {
        return
    }

    const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)")
    const palette = resolvePalette(canvas)
    const worker = new Worker(
        new URL("./setup-background-worker.js", import.meta.url),
        { type: "module" },
    )
    const offscreen = canvas.transferControlToOffscreen()

    worker.postMessage(
        {
            type: "init",
            canvas: offscreen,
            ...readSize(canvas),
            palette,
            reduced: reducedMotion.matches,
            hidden: document.hidden,
        },
        [offscreen],
    )

    worker.addEventListener("message", (event) => {
        if (event.data.type === "ready") {
            canvas.classList.add("ready")
        }
    })

    // A Worker that does not load or throws: no retry, no fallback. The browser reports the error with its file and
    // line; this names the feature, so a missing scene is explained from the console alone
    worker.addEventListener("error", () => {
        console.error("Setup background: the Worker failed, no scene drawn")
    })

    const postSize = (size) => worker.postMessage({ type: "resize", ...size })

    // The first report repeats init's size; the ratio is read with each, as the Worker scales its bitmap by it
    const resizeObserver = new ResizeObserver(([entry]) => {
        const { width, height } = entry.contentRect
        postSize({ width, height, dpr: devicePixelRatio })
    })
    resizeObserver.observe(canvas)

    watchResolution(() => postSize(readSize(canvas)))

    document.addEventListener("visibilitychange", () => {
        worker.postMessage({ type: "visible", hidden: document.hidden })
    })

    reducedMotion.addEventListener("change", (event) => {
        worker.postMessage({ type: "motion", reduced: event.matches })
    })
}
