/**
 * The setup page's background scene, drawn by this module Worker on the OffscreenCanvas the page side,
 * setup-background.js, hands it: a top-down view of a jet over clouds and farmland that scroll down the screen, the
 * view flying up. The page posts the palette, the size, the tab's visibility and the reduced-motion preference (the
 * protocol is under **Setup background** in views/AGENTS.md); everything else, the ground's bands, the clouds, the jet,
 * the clock and the frame loop, is here. All sizes are CSS px: the context is scaled by the pixel ratio, capped at 2,
 * so a 3× phone does not triple the fill rate. The scene is random (Math.random, through `between`) and never repeats:
 * what scrolls off the bottom is dropped, and new ground and clouds are generated above the top edge. The fields'
 * textures are setup-background-textures.js's, one per cell at random.
 */
import { buildFieldTextures } from "./setup-background-textures.js"

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
const LIGHT_RADIUS = 0.014
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

// The scene: the ground's bands, topmost first; the clouds; the jet with its paths, its length and its anchor; the
// fields' textures, built once the context exists
let bands = []
let clouds = []
let jet = null
let textures = []

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

/** The shadow ink at `alpha`: what darkens a field's texture or a part of the jet */
function dark(alpha) {
    return rgba(palette.cloudShadow, alpha)
}

/** The cloud white at `alpha`: what lightens them */
function light(alpha) {
    return rgba(palette.cloud, alpha)
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
 * neighbour's, with a texture at random and a seed for what the texture varies by cell
 */
function makeBand(top, bottom) {
    const cells = []
    let x = 0
    let previous = null
    while (x < width) {
        const cellWidth = between(CELL_WIDTH.min, CELL_WIDTH.max)
        const field = pickField(previous)
        const texture = integer(0, textures.length - 1)
        cells.push({
            x,
            width: cellWidth,
            field,
            texture,
            seed: between(0, TAU),
        })
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

/**
 * The road under everything, then every cell, each leaving the gap to its right and below it: its color, then its
 * texture painted over it from its corner
 */
function drawGround() {
    ctx.fillStyle = colors.road
    ctx.fillRect(0, 0, width, height)
    for (const band of bands) {
        const cellHeight = band.bottom - band.top - ROAD
        for (const cell of band.cells) {
            const cellWidth = cell.width - ROAD
            ctx.fillStyle = colors.fields[cell.field]
            ctx.fillRect(cell.x, band.top, cellWidth, cellHeight)
            placed(cell.x, band.top, () => {
                textures[cell.texture].paint(
                    ctx,
                    cellWidth,
                    cellHeight,
                    cell.seed,
                )
            })
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

/**
 * `points` of the right side and their mirror on the left as one path of two polygons, the mirror's points reversed
 * so both run the same way round (a part of the silhouette must, or the union would hole where parts overlap)
 */
function mirrored(points) {
    const path = polygon(points)
    path.addPath(polygon(points.map(([x, y]) => [-x, y]).reverse()))
    return path
}

/** A rectangle with corners rounded by `r`, clockwise, added to `path` */
function roundedRect(path, x, y, w, h, r) {
    path.moveTo(x + r, y)
    path.arcTo(x + w, y, x + w, y + h, r)
    path.arcTo(x + w, y + h, x, y + h, r)
    path.arcTo(x, y + h, x, y, r)
    path.arcTo(x, y, x + w, y, r)
    path.closePath()
}

/**
 * The shading that makes a tube from x0 to x1 read as round: dark along both sides, a light along the crown, at
 * `strength` of alpha. Built in the jet's units, like its paths, and painted under the same scale.
 */
function tubeShade(x0, x1, strength) {
    const gradient = ctx.createLinearGradient(x0, 0, x1, 0)
    gradient.addColorStop(0, dark(strength))
    gradient.addColorStop(0.25, dark(0))
    gradient.addColorStop(0.5, light(strength))
    gradient.addColorStop(0.75, light(0))
    gradient.addColorStop(1, dark(strength))
    return gradient
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
 * The jet's paths and shading, nose up, in units of its length: centreline x = 0, nose at y = -0.5, tail at y = 0.5.
 * They are drawn under a scale of the length, so one builder serves both sizes and everything is built once. The
 * proportions are a twin-engine wide-body's seen from above: a tube a tenth of the length wide with an ogive nose and
 * a tapered tail, wings spanning the length tip to tip and swept 32°, their trailing edge kinked inboard, two pods
 * ahead of the leading edge on pylons, a tailplane and a fin. Every part of the silhouette, the union the shadow
 * fills, runs clockwise, so no overlap holes it. The markings (slats, flaps, fairings, pylons, the pods' intake lip,
 * mouth and exhaust, the cockpit glazing, the windows and the doors) sit on the parts and are not in the silhouette.
 */
function buildJet() {
    // The wing, right side: the leading edge runs from the root to the tip, the trailing edge from the root to a
    // kink and on to the tip, as functions of x
    const ROOT = 0.05
    const TIP = 0.5
    const KINK = 0.18
    const leadingEdge = (x) => -0.13 + ((x - ROOT) / (TIP - ROOT)) * 0.285
    const trailingEdge = (x) =>
        x <= KINK
            ? 0.075 + ((x - ROOT) / (KINK - ROOT)) * 0.025
            : 0.1 + ((x - KINK) / (TIP - KINK)) * 0.085

    const wings = polygon([
        [-TIP, 0.155],
        [-ROOT, -0.13],
        [ROOT, -0.13],
        [TIP, 0.155],
        [TIP, 0.185],
        [KINK, 0.1],
        [ROOT, 0.075],
        [-ROOT, 0.075],
        [-KINK, 0.1],
        [-TIP, 0.185],
    ])
    // The slats along the leading edge, the flaps along the trailing edge inboard and outboard of the kink
    const slats = mirrored([
        [ROOT, -0.13],
        [TIP, 0.155],
        [0.49, 0.168],
        [ROOT, -0.108],
    ])
    const flaps = mirrored([
        [ROOT, 0.075],
        [KINK, 0.1],
        [KINK, 0.072],
        [ROOT, 0.047],
    ])
    flaps.addPath(
        mirrored([
            [KINK, 0.1],
            [TIP, 0.185],
            [TIP, 0.165],
            [KINK, 0.082],
        ]),
    )
    // The rear spar's panel line, root to tip, and the flap track fairings hanging off the trailing edge
    const spars = new Path2D()
    const fairings = new Path2D()
    for (const side of [-1, 1]) {
        spars.moveTo(side * ROOT, -0.02)
        spars.lineTo(side * TIP, 0.168)
        for (const x of [0.11, 0.17, 0.27, 0.37]) {
            const y = trailingEdge(x) + 0.002
            fairings.moveTo(side * x + 0.005, y)
            fairings.ellipse(side * x, y, 0.005, 0.022, 0, 0, TAU)
        }
    }

    // The pods, their fronts well ahead of the leading edge, each shaded as a tube; the pylons from inside a pod's
    // tail back over the wing, widening as they go; the intake lip, the mouth behind it and the exhaust as bands
    // across every pod
    const POD = { x: 0.115, y: -0.175, w: 0.08, h: 0.095, r: 0.022 }
    const pods = [-1, 1].map((side) => {
        const x = side === 1 ? POD.x : -POD.x - POD.w
        const path = new Path2D()
        roundedRect(path, x, POD.y, POD.w, POD.h, POD.r)
        return { path, shade: tubeShade(x, x + POD.w, 0.4), x }
    })
    const lips = new Path2D()
    const mouths = new Path2D()
    const exhausts = new Path2D()
    const pylons = new Path2D()
    for (const { x } of pods) {
        lips.rect(x, POD.y, POD.w, 0.01)
        mouths.rect(x, POD.y + 0.01, POD.w, 0.012)
        exhausts.rect(x, POD.y + POD.h - 0.008, POD.w, 0.008)
        const centre = x + POD.w / 2
        const top = POD.y + POD.h - 0.012
        const bottom = leadingEdge(Math.abs(centre)) + 0.09
        pylons.addPath(
            polygon([
                [centre - 0.009, top],
                [centre + 0.009, top],
                [centre + 0.016, bottom],
                [centre - 0.016, bottom],
            ]),
        )
    }

    // The fuselage: an ogive nose, a tube, a tail cone; the cockpit glazing across the nose; a door and a line of
    // windows along each side, the windows giving way at the doors
    const fuselage = new Path2D()
    fuselage.moveTo(0, -0.5)
    fuselage.bezierCurveTo(0.035, -0.5, 0.05, -0.46, 0.05, -0.4)
    fuselage.lineTo(0.05, 0.3)
    fuselage.bezierCurveTo(0.05, 0.4, 0.02, 0.47, 0.012, 0.5)
    fuselage.lineTo(-0.012, 0.5)
    fuselage.bezierCurveTo(-0.02, 0.47, -0.05, 0.4, -0.05, 0.3)
    fuselage.lineTo(-0.05, -0.4)
    fuselage.bezierCurveTo(-0.05, -0.46, -0.035, -0.5, 0, -0.5)
    fuselage.closePath()
    const cockpit = polygon([
        [-0.028, -0.447],
        [-0.016, -0.468],
        [0.016, -0.468],
        [0.028, -0.447],
        [0.02, -0.44],
        [-0.02, -0.44],
    ])
    const DOORS = [-0.37, -0.14, 0.08, 0.27]
    const windows = new Path2D()
    const doors = new Path2D()
    for (const side of [-1, 1]) {
        const x = side * 0.042
        for (const y of DOORS) {
            doors.rect(x - 0.006, y - 0.011, 0.012, 0.022)
        }
        for (let y = -0.4; y <= 0.3; y += 0.014) {
            if (DOORS.every((door) => Math.abs(y - door) > 0.016)) {
                windows.moveTo(x + 0.0025, y)
                windows.arc(x, y, 0.0025, 0, TAU)
            }
        }
    }

    // The tailplane, and the fin seen from above: a thin strip on the centreline near the tail
    const tailplane = mirrored([
        [0.03, 0.34],
        [0.17, 0.44],
        [0.17, 0.46],
        [0.03, 0.455],
    ])
    const fin = new Path2D()
    fin.rect(-0.008, 0.3, 0.016, 0.18)

    const silhouette = new Path2D()
    for (const part of [
        wings,
        fairings,
        pylons,
        ...pods.map((pod) => pod.path),
        tailplane,
        fuselage,
        fin,
    ]) {
        silhouette.addPath(part)
    }

    return {
        wings,
        wingShade: tubeShade(-TIP, TIP, 0.12),
        slats,
        flaps,
        spars,
        fairings,
        pylons,
        pods,
        lips,
        mouths,
        exhausts,
        fuselage,
        fuselageShade: tubeShade(-0.05, 0.05, 0.35),
        cockpit,
        windows,
        doors,
        tailplane,
        fin,
        silhouette,
        // The wingtips, where the lamps sit
        left: [-TIP, 0.17],
        right: [TIP, 0.17],
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

/** A part of the jet's skin: its color, its `shade` over it when it has one, and the soft outline */
function skin(path, shade = null) {
    ctx.fillStyle = colors.jet
    ctx.fill(path)
    if (shade) {
        ctx.fillStyle = shade
        ctx.fill(path)
    }
    ctx.stroke(path)
}

/** A marking on the skin: `path` filled with `color`, no outline */
function marking(path, color) {
    ctx.fillStyle = color
    ctx.fill(path)
}

/** A line on the skin: `path` stroked in `color`, the outline's width */
function line(path, color) {
    ctx.save()
    ctx.strokeStyle = color
    ctx.stroke(path)
    ctx.restore()
}

/**
 * The jet's skin, part over part so each covers what is under it: the wings with their slats, flaps, spar line and
 * fairings, the pylons, the pods with their bands kept inside their rounded corners, the tailplane, the fuselage
 * over all of them with its glazing, windows and doors, the fin, then the lights
 */
function drawJet() {
    inJetFrame(0, 0, () => {
        // The frame is scaled to the length, so the outline is divided by it to stay about a pixel
        ctx.lineWidth = OUTLINE / jet.length
        ctx.strokeStyle = colors.jetOutlineSoft
        skin(jet.wings, jet.wingShade)
        marking(jet.slats, colors.slats)
        marking(jet.flaps, colors.flaps)
        line(jet.spars, colors.spars)
        skin(jet.fairings)
        skin(jet.pylons)
        marking(jet.pylons, colors.pylons)
        for (const pod of jet.pods) {
            skin(pod.path, pod.shade)
            ctx.save()
            ctx.clip(pod.path)
            marking(jet.lips, colors.lips)
            marking(jet.mouths, colors.mouths)
            marking(jet.exhausts, colors.exhausts)
            ctx.restore()
        }
        skin(jet.tailplane)
        skin(jet.fuselage, jet.fuselageShade)
        marking(jet.cockpit, colors.glass)
        marking(jet.windows, colors.glass)
        line(jet.doors, colors.doors)
        skin(jet.fin)
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
        // The jet's markings: its outline softened, the glazing and the doors in the outline ink, the rest in the
        // shadow and cloud inks over the skin
        jetOutlineSoft: rgba(palette.jetOutline, 0.6),
        glass: rgba(palette.jetOutline, 0.85),
        doors: rgba(palette.jetOutline, 0.5),
        slats: light(0.25),
        flaps: dark(0.1),
        spars: dark(0.18),
        pylons: dark(0.12),
        lips: light(0.5),
        mouths: dark(0.3),
        exhausts: dark(0.3),
    }
    sizeBitmap()
    textures = buildFieldTextures(ctx, { dark, light })
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
