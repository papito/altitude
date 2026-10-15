/**
 * The page side of the setup page's background scene, started by setup-page.js on `#setupBackground`. The scene itself
 * is drawn by a module Worker, setup-background-worker.js, on the canvas handed to it with
 * `transferControlToOffscreen()`, so the page's thread draws nothing. This module gives the Worker what a Worker cannot
 * read for itself, then keeps it posted: the palette the stylesheet resolves, the canvas's size and pixel ratio, the
 * tab's visibility and the reduced-motion preference; and it fades the canvas in once the Worker has drawn its first
 * frame. Every message is a plain object with a `type` (the protocol is under **Setup background** in views/AGENTS.md).
 * Without `transferControlToOffscreen` nothing starts and there is no fallback: the canvas stays empty and the page
 * looks as it does without a scene.
 */

/**
 * The color `token` paints on this page, as sRGB channels `[r, g, b]` of 0–255. An inline `color: var(--token)` on the
 * canvas makes the browser resolve the token's recipe (`light-dark()`, `color-mix()`, a relative color) against the
 * page's theme, which a Worker, having no stylesheet, cannot. The channels go to the Worker rather than the computed
 * string: the Worker applies alphas of its own to these colors (a cloud's gradient stops, the shadows), which takes the
 * channels, and the string's form varies with the recipe and the browser (`rgb()`, `color(srgb …)`, `oklab(…)`).
 * Painting it into a one-pixel scratch context, which accepts any color the browser can compute, and reading the pixel
 * back is the parse.
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

/**
 * The scene's palette, resolved once on the page: the tokens of core.css under the names the Worker reads them by.
 * The navigation lights are the status colors, red to port and green to starboard, and the strobe is the text white.
 */
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
        lightRight: probe("--success-font-color"),
        strobe: probe("--theme-text"),
    }
}

/** The canvas's CSS size (it fills the viewport) and the pixel ratio, as `init` and `resize` carry them */
function readSize(canvas) {
    const { width, height } = canvas.getBoundingClientRect()
    return { width, height, dpr: devicePixelRatio }
}

/**
 * Calls `onChange` whenever `devicePixelRatio` changes: the window moved to a screen of another density, or the page
 * was zoomed. There is no event for it, so a media query for the current ratio is listened to: its `change` fires once
 * the ratio leaves that value, and a query for the new ratio is armed in its place. The listener is `once`, so the old
 * query does not fire again should the ratio come back to its value.
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

/**
 * Starts the scene on `canvas`: resolves the palette, hands the canvas to the Worker with the size and the preferences
 * it starts from, and posts every change of them after. The canvas is shown on the Worker's `ready`, so the form never
 * sits on a flash of blank canvas.
 */
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

    // A Worker that does not load (the file missing, a browser without module Workers) or throws fires this, and the
    // canvas stays transparent: no retry, no fallback. The browser reports the error itself, with the file and line;
    // this line names the feature, so a missing scene is explained from the console alone
    worker.addEventListener("error", () => {
        console.error("Setup background: the Worker failed, no scene drawn")
    })

    const postSize = (size) => worker.postMessage({ type: "resize", ...size })

    // The observer reports the size it finds on observe(), repeating init's, then every change of the viewport; the
    // ratio is read at that moment, as the Worker scales its bitmap by it
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
