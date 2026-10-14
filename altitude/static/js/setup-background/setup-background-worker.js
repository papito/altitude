/**
 * The setup page's background scene, drawn by this module Worker on the OffscreenCanvas the page side,
 * setup-background.js, hands it: a top-down view of a jet over clouds and farmland that scroll down the screen, the
 * view flying up. The page posts the palette, the size, the tab's visibility and the reduced-motion preference (the
 * protocol is under **Setup background** in views/AGENTS.md); everything else, the ground's bands, the clouds, the jet,
 * the clock and the frame loop, is here. All sizes are CSS px: the context is scaled by the pixel ratio, capped at 2,
 * so a 3× phone does not triple the fill rate. The scene is random (Math.random, through `between`) and never repeats:
 * what scrolls off the bottom is dropped, and new ground and clouds are generated above the top edge.
 */

const TAU = 2 * Math.PI

// The pixel ratio the bitmap is scaled by at most
const MAX_DPR = 2
// The longest step a frame advances the scene by, in ms: a resumed tab or a stalled frame moves it one short step
const MAX_STEP = 100

// The ground: its speed in px/s, the ranges of a band's height and a cell's width, and the gap of road color between
// cells and between bands
const GROUND_SPEED = 50
const BAND_HEIGHT = { min: 60, max: 220 }
const CELL_WIDTH = { min: 60, max: 320 }
const ROAD = 3

// The clouds: their speed (1.6 × the ground's, so they read as nearer), how many are on screen, the circles of one and
// the ellipse their centres fall in (its half-axes), the gradient's alpha at a circle's centre, how far above the top
// edge a new cloud's top may spawn, and the shadow's offset and alpha
const CLOUD_SPEED = 80
const CLOUD_COUNT = { min: 4, max: 8 }
const CLOUD_CIRCLES = { min: 4, max: 8 }
const CLOUD_RADIUS = { min: 30, max: 90 }
const CLOUD_SPREAD = { x: 110, y: 60 }
const CLOUD_ALPHA = 0.6
const CLOUD_GAP = 400
const CLOUD_SHADOW = { x: -40, y: 40, alpha: 0.28 }

// The jet: its length in px and the x of its anchor as a fraction of the width, on a wide viewport and on one narrower
// than the threshold, where it is smaller. It rises out of the bottom edge, the nearest thing in the scene: the edge
// cuts it TAIL_CUT of its length behind its centre (nose at -0.5, tail at 0.5), so the nose, the wings and the engines
// show and the tailplane and the fin stay below it. It holds still: the scenery moves under it. Then the outline's
// width in px, and the shadow's offset in px and its alpha
const JET_WIDE = { length: 420, x: 0.18 }
const JET_NARROW = { length: 270, x: 0.14 }
const NARROW_VIEWPORT = 600
const TAIL_CUT = 0.28
const OUTLINE = 1
const JET_SHADOW = { x: -54, y: 54, alpha: 0.3 }

// The lights, in seconds: the navigation lights are on for `on` of every `period`; the strobe flashes twice every
// `period`, each flash `flash` long, the second starting `gap` after the first. A lamp's radius is a fraction of the
// jet's length and its glow reaches HALO times that; the lamp itself stays on the wingtip between blinks, dimmed
// halfway to the outline ink, so the tips keep their shape
const NAV_LIGHT = { period: 1, on: 0.5 }
const STROBE = { period: 1.2, flash: 0.06, gap: 0.12 }
const LIGHT_RADIUS = 0.02
const HALO = 3

// The canvas, its context and CSS size, and the preferences, as `init` and the messages after it set them
let canvas = null
let ctx = null
let width = 0
let height = 0
let dpr = 1
let reduced = false
let hidden = false
// The palette as the page resolved it, and the CSS color strings built from it once
let palette = null
let colors = null

// The clock: the pending frame's handle (0 when none), the previous frame's timestamp (null once the clock is reset, so
// the next frame measures no gap) and the scene's running time in seconds, which the lights read
let frame = 0
let last = null
let t = 0

// The scene: the ground's bands, topmost first; the clouds; the jet with its paths, its length and its anchor
let bands = []
let clouds = []
let jet = null

/** A random number from `min` up to `max`: the scene's one call of Math.random */
function between(min, max) {
    return min + Math.random() * (max - min)
}

/** A random integer from `min` to `max`, both included */
function integer(min, max) {
    return Math.floor(between(min, max + 1))
}

/** The CSS color of the palette's `[r, g, b]` channels at `alpha`, in the comma form every browser parses */
function rgba([r, g, b], alpha) {
    return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

/** The channels halfway between two of the palette's colors: a lamp that is not lit, its color toward the ink */
function halfway(a, b) {
    return a.map((channel, i) => Math.round((channel + b[i]) / 2))
}

/** Runs `paint` with the origin moved to (x, y), the context restored after */
function placed(x, y, paint) {
    ctx.save()
    ctx.translate(x, y)
    paint()
    ctx.restore()
}

/**
 * Sizes the bitmap to the CSS size at the pixel ratio, capped, and scales the context to match, so the scene draws in
 * CSS px. Sizing the bitmap clears it and resets the context's state, so the scale and the joins are set after.
 */
function sizeBitmap() {
    const scale = Math.min(dpr, MAX_DPR)
    canvas.width = Math.round(width * scale)
    canvas.height = Math.round(height * scale)
    ctx.setTransform(scale, 0, 0, scale, 0, 0)
    // The jet's wingtips are sharp: a mitre there would spike
    ctx.lineJoin = "round"
}

/** The index of a field color other than `previous` (null for a band's first cell): a pick among the other five */
function pickField(previous) {
    const count = colors.fields.length
    if (previous === null) {
        return integer(0, count - 1)
    }
    // A pick among one fewer, stepping over the previous color
    const pick = integer(0, count - 2)
    return pick < previous ? pick : pick + 1
}

/**
 * A band from `top` to `bottom`, cut into cells across the current width, each a field color unlike its left
 * neighbour's
 */
function makeBand(top, bottom) {
    const cells = []
    let x = 0
    let previous = null
    while (x < width) {
        const cellWidth = between(CELL_WIDTH.min, CELL_WIDTH.max)
        const field = pickField(previous)
        cells.push({ x, width: cellWidth, field })
        x += cellWidth
        previous = field
    }
    return { top, bottom, cells }
}

/** A band's random height */
function bandHeight() {
    return between(BAND_HEIGHT.min, BAND_HEIGHT.max)
}

/**
 * Drops the bands whose top has passed the bottom edge and keeps the ground covered: bands are added above the topmost
 * until one lies wholly above the top edge (from the bottom edge up when the ground is empty: at init, and after the
 * width grows), and below the lowest when a viewport grown taller has uncovered ground under it.
 */
function recycleBands() {
    while (bands.length > 0 && bands[bands.length - 1].top >= height) {
        bands.pop()
    }
    while (bands.length === 0 || bands[0].bottom > 0) {
        const bottom = bands.length === 0 ? height : bands[0].top
        bands.unshift(makeBand(bottom - bandHeight(), bottom))
    }
    while (bands[bands.length - 1].bottom < height) {
        const top = bands[bands.length - 1].bottom
        bands.push(makeBand(top, top + bandHeight()))
    }
}

/** The road under everything, then every cell, each leaving the gap to its right and below it */
function drawGround() {
    ctx.fillStyle = colors.road
    ctx.fillRect(0, 0, width, height)
    for (const band of bands) {
        const cellHeight = band.bottom - band.top - ROAD
        for (const cell of band.cells) {
            ctx.fillStyle = colors.fields[cell.field]
            ctx.fillRect(cell.x, band.top, cell.width - ROAD, cellHeight)
        }
    }
}

/** A circle's gradient, at the origin: the cloud color, fading from CLOUD_ALPHA at the centre to nothing at the rim */
function cloudGradient(radius) {
    const gradient = ctx.createRadialGradient(0, 0, 0, 0, 0, radius)
    gradient.addColorStop(0, colors.cloudCore)
    gradient.addColorStop(1, colors.cloudRim)
    return gradient
}

/**
 * A cloud centred at (x, y): circles whose centres fall within the ellipse, each with its gradient built here, so no
 * frame builds one; `outline` holds the circles as one path, for the shadow; `up` is the reach from the centre to the
 * top edge, which tells when the cloud has left the viewport and how far above it a new one spawns.
 */
function makeCloud(x, y) {
    const circles = []
    const outline = new Path2D()
    let up = 0
    const count = integer(CLOUD_CIRCLES.min, CLOUD_CIRCLES.max)
    for (let i = 0; i < count; i++) {
        // A uniform angle and a square-rooted distance spread the centres evenly over the ellipse's area
        const angle = between(0, TAU)
        const distance = Math.sqrt(between(0, 1))
        const dx = Math.cos(angle) * CLOUD_SPREAD.x * distance
        const dy = Math.sin(angle) * CLOUD_SPREAD.y * distance
        const radius = between(CLOUD_RADIUS.min, CLOUD_RADIUS.max)
        circles.push({ dx, dy, radius, gradient: cloudGradient(radius) })
        // moveTo first, so the path joins the circles with no line between them
        outline.moveTo(dx + radius, dy)
        outline.arc(dx, dy, radius, 0, TAU)
        up = Math.max(up, radius - dy)
    }
    return { x, y, circles, outline, up }
}

/** A cloud above the top edge at a random x, its top edge a random gap of up to CLOUD_GAP above the viewport */
function spawnCloud() {
    const cloud = makeCloud(between(0, width), 0)
    cloud.y = -between(0, CLOUD_GAP) - cloud.up
    return cloud
}

/** Replaces each cloud whose top edge has passed the bottom edge with one spawned above the top */
function recycleClouds() {
    for (let i = 0; i < clouds.length; i++) {
        if (clouds[i].y - clouds[i].up >= height) {
            clouds[i] = spawnCloud()
        }
    }
}

/** Every cloud's circles, each its gradient's square moved to its place */
function drawClouds() {
    for (const cloud of clouds) {
        for (const { dx, dy, radius, gradient } of cloud.circles) {
            placed(cloud.x + dx, cloud.y + dy, () => {
                ctx.fillStyle = gradient
                ctx.fillRect(-radius, -radius, 2 * radius, 2 * radius)
            })
        }
    }
}

/** The clouds' shadows on the ground: each cloud's outline in the translucent shadow ink, offset down-left */
function drawCloudShadows() {
    ctx.fillStyle = colors.cloudShadow
    for (const cloud of clouds) {
        placed(cloud.x + CLOUD_SHADOW.x, cloud.y + CLOUD_SHADOW.y, () => {
            ctx.fill(cloud.outline)
        })
    }
}

/** A closed polygon through `points` as a Path2D */
function polygon(points) {
    const path = new Path2D()
    const [[x0, y0], ...rest] = points
    path.moveTo(x0, y0)
    for (const [x, y] of rest) {
        path.lineTo(x, y)
    }
    path.closePath()
    return path
}

/** A light's halo, at the origin: the solid dot out to its radius, then a glow fading to nothing at HALO times it */
function haloGradient(color) {
    const reach = LIGHT_RADIUS * HALO
    const gradient = ctx.createRadialGradient(0, 0, 0, 0, 0, reach)
    gradient.addColorStop(0, rgba(color, 1))
    gradient.addColorStop(1 / HALO, rgba(color, 1))
    gradient.addColorStop(1 / HALO + 0.01, rgba(color, 0.5))
    gradient.addColorStop(1, rgba(color, 0))
    return gradient
}

/**
 * The jet's paths, nose up, in units of its length: centreline x = 0, nose at y = -0.5, tail at y = 0.5. They are
 * drawn under a scale of the length, so one builder serves both sizes and the paths are built once. Every part runs
 * clockwise (the planforms as listed, the pods and the fin as Path2D draws them), so their union, the silhouette the
 * shadow fills, has no hole where parts overlap.
 */
function buildJet() {
    // A slim body, 9 % of the length wide, with an ogive nose and a tapered tail cone
    const fuselage = new Path2D()
    fuselage.moveTo(0, -0.5)
    fuselage.quadraticCurveTo(0.045, -0.48, 0.045, -0.36)
    fuselage.lineTo(0.045, 0.32)
    fuselage.lineTo(0.02, 0.49)
    fuselage.quadraticCurveTo(0, 0.5, -0.02, 0.49)
    fuselage.lineTo(-0.045, 0.32)
    fuselage.lineTo(-0.045, -0.36)
    fuselage.quadraticCurveTo(-0.045, -0.48, 0, -0.5)
    fuselage.closePath()

    // Swept back from 40 % of the way back, 85 % of the length tip to tip; the root chord runs under the fuselage
    const wings = polygon([
        [-0.425, 0.17], // left tip, leading edge
        [-0.04, -0.1], // root, leading edge
        [0.04, -0.1],
        [0.425, 0.17], // right tip, leading edge
        [0.425, 0.22], // right tip, trailing edge
        [0.04, 0.12], // root, trailing edge
        [-0.04, 0.12],
        [-0.425, 0.22], // left tip, trailing edge
    ])

    // The tailplane: the wings' shape in small, at the tail
    const tailplane = polygon([
        [-0.15, 0.44],
        [-0.03, 0.36],
        [0.03, 0.36],
        [0.15, 0.44],
        [0.15, 0.47],
        [0.03, 0.46],
        [-0.03, 0.46],
        [-0.15, 0.47],
    ])

    // The engine pods under the wings, near the fuselage: drawn before the wings, so only their fronts show ahead of
    // the leading edge, each with its intake as a dot of the outline ink. moveTo first, so the stroke draws no line
    // from one pod to the other
    const engines = new Path2D()
    const intakes = new Path2D()
    for (const x of [-0.14, 0.14]) {
        engines.moveTo(x + 0.028, -0.04)
        engines.ellipse(x, -0.04, 0.028, 0.11, 0, 0, TAU)
        intakes.moveTo(x + 0.014, -0.12)
        intakes.arc(x, -0.12, 0.014, 0, TAU)
    }

    // The fin, seen from above: a thin strip on the centreline near the tail
    const fin = new Path2D()
    fin.rect(-0.01, 0.3, 0.02, 0.18)

    // The skin's order: each later part covers the outline of the one under it where they meet
    const parts = [engines, wings, tailplane, fuselage, fin]
    const silhouette = new Path2D()
    for (const part of parts) {
        silhouette.addPath(part)
    }

    return {
        parts,
        silhouette,
        intakes,
        // The wingtips, where the lights sit
        left: [-0.425, 0.195],
        right: [0.425, 0.195],
        halos: {
            left: haloGradient(palette.lightLeft),
            right: haloGradient(palette.lightRight),
            strobe: haloGradient(palette.strobe),
        },
    }
}

/**
 * Sizes and anchors the jet for the viewport: smaller on a narrow one, and anchored so the bottom edge cuts it
 * TAIL_CUT of its length behind its centre, whatever the height
 */
function placeJet() {
    const size = width < NARROW_VIEWPORT ? JET_NARROW : JET_WIDE
    jet.length = size.length
    jet.x = width * size.x
    jet.y = height - TAIL_CUT * size.length
}

/** Runs `paint` in the jet's frame: its anchor offset by (dx, dy) px, scaled to its length */
function inJetFrame(dx, dy, paint) {
    placed(jet.x + dx, jet.y + dy, () => {
        ctx.scale(jet.length, jet.length)
        paint()
    })
}

/** The jet's shadow on the ground: the silhouette in the translucent shadow ink, offset down-left */
function drawJetShadow() {
    inJetFrame(JET_SHADOW.x, JET_SHADOW.y, () => {
        ctx.fillStyle = colors.jetShadow
        ctx.fill(jet.silhouette)
    })
}

/** The jet's skin, every part filled and outlined in order, then the intakes, then its lights */
function drawJet() {
    inJetFrame(0, 0, () => {
        ctx.fillStyle = colors.jet
        ctx.strokeStyle = colors.jetOutline
        // The frame is scaled to the length, so the outline is divided by it to stay about a pixel
        ctx.lineWidth = OUTLINE / jet.length
        for (const part of jet.parts) {
            ctx.fill(part)
            ctx.stroke(part)
        }
        ctx.fillStyle = colors.jetOutline
        ctx.fill(jet.intakes)
        drawLights()
    })
}

/** Which lights are lit at the scene's time */
function litLights() {
    const nav = t % NAV_LIGHT.period < NAV_LIGHT.on
    const phase = t % STROBE.period
    const flash = (start) => phase >= start && phase < start + STROBE.flash
    return { nav, strobe: flash(0) || flash(STROBE.gap) }
}

/**
 * The wingtip lamps, in the jet's frame: each tip carries its lamp at all times, a dimmed dot of the navigation
 * light's color with the skin's outline, so the tips keep their shape between blinks; a lit lamp glows over it, red
 * to port and green to starboard, and the strobe's white over both
 */
function drawLights() {
    const { nav, strobe } = litLights()
    drawLamp(jet.left, colors.lampLeft)
    drawLamp(jet.right, colors.lampRight)
    if (nav) {
        drawGlow(jet.left, jet.halos.left)
        drawGlow(jet.right, jet.halos.right)
    }
    if (strobe) {
        drawGlow(jet.left, jet.halos.strobe)
        drawGlow(jet.right, jet.halos.strobe)
    }
}

/** A lamp's housing at `[x, y]` of the jet's frame: a dot of `color`, outlined as the skin is */
function drawLamp([x, y], color) {
    ctx.fillStyle = color
    ctx.beginPath()
    ctx.arc(x, y, LIGHT_RADIUS, 0, TAU)
    ctx.fill()
    ctx.stroke()
}

/** A lit lamp's glow at `[x, y]` of the jet's frame: its halo's square moved there */
function drawGlow([x, y], halo) {
    const reach = LIGHT_RADIUS * HALO
    placed(x, y, () => {
        ctx.fillStyle = halo
        ctx.fillRect(-reach, -reach, 2 * reach, 2 * reach)
    })
}

/** The scene from scratch for the current size: the ground, the clouds spread over the viewport, the jet */
function generateScene() {
    bands = []
    recycleBands()
    clouds = []
    const count = integer(CLOUD_COUNT.min, CLOUD_COUNT.max)
    for (let i = 0; i < count; i++) {
        clouds.push(makeCloud(between(0, width), between(0, height)))
    }
    jet = buildJet()
    placeJet()
}

/** Moves the scene on by `dt` seconds and replaces what has scrolled off the bottom */
function advance(dt) {
    t += dt
    for (const band of bands) {
        band.top += GROUND_SPEED * dt
        band.bottom += GROUND_SPEED * dt
    }
    for (const cloud of clouds) {
        cloud.y += CLOUD_SPEED * dt
    }
    recycleBands()
    recycleClouds()
}

/** One frame, in the order the layers stack: road, cells, cloud shadows, jet shadow, clouds, jet, lights */
function draw() {
    drawGround()
    drawCloudShadows()
    drawJetShadow()
    drawClouds()
    drawJet()
}

/** Whether the scene moves: a reduced-motion user gets a still, a hidden tab gets no frames */
function animating() {
    return !reduced && !hidden
}

/**
 * A frame: the scene advances by the time since the last one, clamped, or by nothing right after a clock reset, is
 * drawn, and the next frame is requested. The callback only runs while the loop is on (a stop cancels the pending
 * frame, and no message runs while a frame does), so it always continues the loop.
 */
function onFrame(timestamp) {
    const dt = last === null ? 0 : Math.min(timestamp - last, MAX_STEP)
    last = timestamp
    advance(dt / 1000)
    draw()
    frame = requestAnimationFrame(onFrame)
}

/** Starts the loop with a fresh clock, so its first frame measures no gap, unless a frame is already pending */
function startLoop() {
    if (frame !== 0) {
        return
    }
    last = null
    frame = requestAnimationFrame(onFrame)
}

/** Cancels the pending frame, if any; the frame drawn last stays as the still */
function stopLoop() {
    if (frame !== 0) {
        cancelAnimationFrame(frame)
        frame = 0
    }
}

/** Runs the loop while the scene animates and stops it otherwise */
function syncLoop() {
    if (animating()) {
        startLoop()
    } else {
        stopLoop()
    }
}

/**
 * Takes the canvas and the palette, builds the scene and draws its first frame at once, without waiting for a frame
 * callback: the page fades the canvas in on `ready`, and a hidden document gets no frame callbacks. The loop then
 * runs unless motion is reduced or the tab is hidden.
 */
function onInit(message) {
    canvas = message.canvas
    // Opaque: the road fill covers the bitmap every frame, so the browser composites it without blending
    ctx = canvas.getContext("2d", { alpha: false })
    width = message.width
    height = message.height
    dpr = message.dpr
    reduced = message.reduced
    hidden = message.hidden
    palette = message.palette
    colors = {
        road: rgba(palette.road, 1),
        fields: palette.fields.map((field) => rgba(field, 1)),
        cloudCore: rgba(palette.cloud, CLOUD_ALPHA),
        cloudRim: rgba(palette.cloud, 0),
        cloudShadow: rgba(palette.cloudShadow, CLOUD_SHADOW.alpha),
        jet: rgba(palette.jet, 1),
        jetOutline: rgba(palette.jetOutline, 1),
        jetShadow: rgba(palette.cloudShadow, JET_SHADOW.alpha),
        lampLeft: rgba(halfway(palette.lightLeft, palette.jetOutline), 1),
        lampRight: rgba(halfway(palette.lightRight, palette.jetOutline), 1),
    }
    sizeBitmap()
    generateScene()
    draw()
    self.postMessage({ type: "ready" })
    syncLoop()
}

/**
 * A new size or pixel ratio. The observer's first notification repeats init's size: a no-op. A new ratio alone
 * resizes the bitmap; a new size also refits the scene: the ground is regenerated when the width grows, as its cells
 * are cut for the width, and kept otherwise, with bands dropped or added as the height asks; a cloud now past an edge
 * scrolls off and is replaced as usual; the jet is anchored again. A frame is drawn at once in every case: sizing the
 * bitmap clears it, and the blank bitmap would reach the page at the end of this task, a flash before the next frame
 * fills it, while a still (reduced or hidden) would otherwise stay blank or stretched.
 */
function onResize(message) {
    const sameSize = message.width === width && message.height === height
    if (sameSize && message.dpr === dpr) {
        return
    }
    const wider = message.width > width
    width = message.width
    height = message.height
    dpr = message.dpr
    sizeBitmap()
    if (!sameSize) {
        if (wider) {
            bands = []
        }
        recycleBands()
        recycleClouds()
        placeJet()
    }
    draw()
}

/** The tab hidden or shown: hidden, it gets no frames; shown, the loop resumes with a fresh clock, so nothing jumps */
function onVisible(message) {
    hidden = message.hidden
    syncLoop()
}

/** Reduced motion switched on or off: on, the frame drawn last stays as the still; off, the loop resumes */
function onMotion(message) {
    reduced = message.reduced
    syncLoop()
}

// Every message is dispatched on its `type`
const handlers = {
    init: onInit,
    resize: onResize,
    visible: onVisible,
    motion: onMotion,
}

self.addEventListener("message", ({ data }) => {
    handlers[data.type]?.(data)
})
