/**
 * The setup page's background scene, drawn by this module Worker on the OffscreenCanvas the page side,
 * setup-background.js, hands it: a top-down view of a jet over clouds and farmland that scroll down the screen, the
 * view flying up. The page posts the palette, the size, the tab's visibility and the reduced-motion preference (the
 * protocol is under **Setup background** in views/AGENTS.md); everything else, the ground's blocks of fields with their
 * roads and vehicles, the clouds, the jet, the clock and the frame loop, is here. All sizes are CSS px: the context is scaled by the pixel ratio, capped at 2,
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

// The ground: its speed in px/s, the ranges of a band's height and a field's width, the line of field-edge color
// between cells and between bands (and along each side of a road, its verges), and a road's width
const GROUND_SPEED = 50
const BAND_HEIGHT = { min: 60, max: 220 }
const CELL_WIDTH = { min: 60, max: 320 }
const EDGE = 3
const ROAD = 10

// The roads: a block of ground, from one road across to the next, is this many bands tall, and the roads down a block
// stand this many fields apart (a span between two is the sum of that many field widths, and each band cuts it into
// as many cells)
const BLOCK_BANDS = { min: 2, max: 5 }
const SPAN_FIELDS = { min: 2, max: 5 }

// The vehicles: their size along and across the road, their speed over the ground in px/s, how many a block's road
// across starts with, the chance that a road down a block starts with one, and the chance that one turns at a junction
// it passes. A vehicle's road is ACROSS, the block's road across, or the index of a road down the block
const VEHICLE = { length: 5, width: 3 }
const VEHICLE_SPEED = { min: 15, max: 35 }
const VEHICLES_ACROSS = { min: 0, max: 2 }
const VEHICLE_CHANCE = 0.5
const TURN_CHANCE = 0.35
const ACROSS = -1

// The clouds: their speed (1.6 × the ground's, so they read as nearer), how many are on screen, the circles of one and
// the ellipse their centres fall in (its half-axes), the scales a cloud is drawn at (one picked per cloud, applied to
// the radii and the ellipse), the gradient's alpha at a circle's centre, how far above the top edge a new cloud's top
// may spawn, and the shadow's offset and alpha
const CLOUD_SPEED = 80
const CLOUD_COUNT = { min: 4, max: 8 }
const CLOUD_CIRCLES = { min: 4, max: 8 }
const CLOUD_RADIUS = { min: 30, max: 90 }
const CLOUD_SPREAD = { x: 110, y: 60 }
const CLOUD_SCALES = [1, 2, 3]
const CLOUD_ALPHA = 0.6
const CLOUD_GAP = 400
const CLOUD_SHADOW = { x: -40, y: 40, alpha: 0.28 }

// The jet: its length in px and the x of its anchor as a fraction of the width, on a wide viewport and on one narrower
// than the threshold, where it is smaller. It rises out of the bottom edge, the nearest thing in the scene: the edge
// cuts it TAIL_CUT of its length behind its centre (nose at -0.5, tail at 0.5), so the nose, the wings and the engines
// show and the tailplane and the fin stay below it. It holds still: the scenery moves under it. Then the outline's
// width in px
const JET_WIDE = { length: 420, x: 0.18 }
const JET_NARROW = { length: 270, x: 0.14 }
const NARROW_VIEWPORT = 600
const TAIL_CUT = 0.28
const OUTLINE = 1

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

// The scene: the ground's blocks, topmost first, each with its bands, its roads and its vehicles; the clouds; the jet
// with its paths, its length and its anchor; the fields' textures, built once the context exists
let blocks = []
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

/** Whether a chance of `probability` comes off */
function chance(probability) {
    return between(0, 1) < probability
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
 * A band's cells: every span between a block's roads cut into 2–5 cells, as many as the field widths let it (no
 * narrower than the min, and no more than the max would need), each the min plus a random share of what the span has
 * over that, with a field color unlike its left neighbour's, a texture at random and a seed for what the texture
 * varies by cell. A cell's width includes the edge to its right, so a span's last cell ends an edge short of the road.
 */
function cutCells(spans) {
    const cells = []
    let previous = null
    for (const [from, to] of spans) {
        const span = to - from
        const count = Math.min(
            Math.max(
                integer(SPAN_FIELDS.min, SPAN_FIELDS.max),
                Math.ceil(span / CELL_WIDTH.max),
            ),
            Math.floor(span / CELL_WIDTH.min),
        )
        const weights = Array.from({ length: count }, () => between(1, 2))
        const total = weights.reduce((sum, weight) => sum + weight, 0)
        const slack = span - count * CELL_WIDTH.min
        let x = from
        for (const weight of weights) {
            const cellWidth = CELL_WIDTH.min + (slack * weight) / total
            const field = pickField(previous)
            cells.push({
                x,
                width: cellWidth,
                field,
                texture: integer(0, textures.length - 1),
                seed: between(0, TAU),
            })
            x += cellWidth
            previous = field
        }
    }
    return cells
}

/**
 * A block of ground: the road across its top, and under it 2–5 bands of cells cut between the roads down the block,
 * which stand 2–5 fields apart and run from the road across to the block's bottom, where the next block's road across
 * is. The spans between the roads are cut for the current width: the first starts up to a field's width left of the
 * edge, so a road can fall anywhere across the viewport, the edge included, and the last runs past the right edge,
 * where no road is kept. Its bands and its vehicles are placed from its top, which the caller sets.
 */
function makeBlock() {
    const roads = []
    const spans = []
    let x = -between(0, CELL_WIDTH.max)
    while (x < width) {
        let end = x
        const fields = integer(SPAN_FIELDS.min, SPAN_FIELDS.max)
        for (let i = 0; i < fields; i++) {
            end += between(CELL_WIDTH.min, CELL_WIDTH.max)
        }
        spans.push([x, end])
        if (end < width) {
            roads.push(end)
        }
        x = end + ROAD + EDGE
    }

    const bands = []
    const count = integer(BLOCK_BANDS.min, BLOCK_BANDS.max)
    // The first band starts below the road across and its verge
    let top = ROAD + EDGE
    for (let i = 0; i < count; i++) {
        const bottom = top + between(BAND_HEIGHT.min, BAND_HEIGHT.max)
        bands.push({ top, bottom, cells: cutCells(spans) })
        top = bottom
    }

    const block = { top: 0, height: top, bands, roads, vehicles: [] }
    const across = integer(VEHICLES_ACROSS.min, VEHICLES_ACROSS.max)
    for (let i = 0; i < across; i++) {
        block.vehicles.push(makeVehicle(ACROSS, between(0, width)))
    }
    roads.forEach((_, road) => {
        if (chance(VEHICLE_CHANCE)) {
            const at = between(ROAD / 2, block.height + ROAD / 2)
            block.vehicles.push(makeVehicle(road, at))
        }
    })
    return block
}

/** A block's bottom edge, where the block below's road across runs */
function bottomOf(block) {
    return block.top + block.height
}

/**
 * Drops the blocks whose top has passed the bottom edge and keeps the ground covered: blocks are added above the
 * topmost until it covers the top edge (from the bottom edge up when the ground is empty: at init, and after the
 * width grows), and below the lowest when a viewport grown taller has uncovered ground under it.
 */
function recycleBlocks() {
    while (blocks.length > 0 && blocks[blocks.length - 1].top >= height) {
        blocks.pop()
    }
    while (blocks.length === 0 || blocks[0].top > 0) {
        const block = makeBlock()
        const bottom = blocks.length === 0 ? height : blocks[0].top
        block.top = bottom - block.height
        blocks.unshift(block)
    }
    while (bottomOf(blocks[blocks.length - 1]) < height) {
        const block = makeBlock()
        block.top = bottomOf(blocks[blocks.length - 1])
        blocks.push(block)
    }
}

/**
 * The field-edge color under everything, then every block in its frame, the origin at its top: its cells, its roads
 * over them and its vehicles over those
 */
function drawGround() {
    ctx.fillStyle = colors.fieldEdge
    ctx.fillRect(0, 0, width, height)
    for (const block of blocks) {
        placed(0, block.top, () => {
            drawCells(block)
            drawRoads(block)
            drawVehicles(block)
        })
    }
}

/**
 * A block's cells, each leaving the edge to its right and below it: its color, then its texture painted over it from
 * its corner. A band wholly past the top or the bottom edge is skipped, and so is a cell wholly past a side: a block
 * may stand mostly above the viewport, and its last span runs past the right edge.
 */
function drawCells(block) {
    for (const band of block.bands) {
        if (block.top + band.bottom <= 0 || block.top + band.top >= height) {
            continue
        }
        const cellHeight = band.bottom - band.top - EDGE
        for (const cell of band.cells) {
            if (cell.x >= width) {
                break
            }
            if (cell.x + cell.width <= 0) {
                continue
            }
            const cellWidth = cell.width - EDGE
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

/**
 * A block's roads: the one across its top, the width of the viewport, and the ones down it, from that road to the
 * block's bottom, so each meets the next block's road across
 */
function drawRoads(block) {
    ctx.fillStyle = colors.road
    ctx.fillRect(0, 0, width, ROAD)
    for (const x of block.roads) {
        ctx.fillRect(x, 0, ROAD, block.height)
    }
}

/**
 * A vehicle on `road` of a block (ACROSS, or the index of a road down it), `at` along it (an x on the road across, an
 * offset from the block's top on a road down), heading either way at a speed of its own over the ground, light or dark
 */
function makeVehicle(road, at) {
    return {
        road,
        at,
        dir: chance(0.5) ? 1 : -1,
        speed: between(VEHICLE_SPEED.min, VEHICLE_SPEED.max),
        light: chance(0.5),
    }
}

/** Where a vehicle is in its block's frame, keeping to the right of its road: [x, y] */
function vehiclePlace(block, { road, at, dir }) {
    if (road === ACROSS) {
        // Eastbound on the south side, westbound on the north
        return [at, ROAD * (dir === 1 ? 0.75 : 0.25)]
    }
    // Southbound on the west side, northbound on the east
    return [block.roads[road] + ROAD * (dir === 1 ? 0.25 : 0.75), at]
}

/** A block's vehicles: each a rectangle the long way along its road, centred where it is */
function drawVehicles(block) {
    for (const vehicle of block.vehicles) {
        const [x, y] = vehiclePlace(block, vehicle)
        const [w, h] =
            vehicle.road === ACROSS
                ? [VEHICLE.length, VEHICLE.width]
                : [VEHICLE.width, VEHICLE.length]
        ctx.fillStyle = vehicle.light ? colors.vehicleLight : colors.vehicleDark
        ctx.fillRect(x - w / 2, y - h / 2, w, h)
    }
}

/** Whether a step from `from` to `to` along a road passes the junction at `centre` */
function passes(from, to, centre) {
    return from < centre ? to >= centre : from > centre && to <= centre
}

/** Turns a vehicle onto the road across at `x`, either way at random */
function turnAcross(vehicle, x) {
    vehicle.road = ACROSS
    vehicle.at = x
    vehicle.dir = chance(0.5) ? 1 : -1
}

/** Turns a vehicle down `road`, `at` along it, heading `dir` */
function turnDown(vehicle, road, at, dir) {
    vehicle.road = road
    vehicle.at = at
    vehicle.dir = dir
}

/**
 * A vehicle on a road down block `i` after its step: the block it is in, which is the block below once it has turned
 * at the junction at the bottom, or null when it has left the scene, there being no block below, since that junction
 * is then past the bottom edge. At the junction at either end it turns onto the road across there, this block's at
 * the top, the block below's at the bottom.
 */
function driveDown(i, vehicle) {
    const block = blocks[i]
    const x = block.roads[vehicle.road] + ROAD / 2
    if (vehicle.at < ROAD / 2) {
        turnAcross(vehicle, x)
        return block
    }
    if (vehicle.at > block.height + ROAD / 2) {
        if (i === blocks.length - 1) {
            return null
        }
        turnAcross(vehicle, x)
        return blocks[i + 1]
    }
    return block
}

/**
 * A vehicle on block `i`'s road across after its step from `from`: the block it is in, which is the block above once
 * it has turned up one of that block's roads. At a junction it passes it turns, by TURN_CHANCE, down this block's road
 * or up the block above's, which ends at this road; past either side of the viewport it comes round the other, the
 * road running on past both.
 */
function driveAcross(i, vehicle, from) {
    const block = blocks[i]
    const junction = (roads) =>
        roads.findIndex((x) => passes(from, vehicle.at, x + ROAD / 2))
    const down = junction(block.roads)
    if (down !== -1 && chance(TURN_CHANCE)) {
        turnDown(vehicle, down, ROAD / 2, 1)
        return block
    }
    const above = i > 0 ? blocks[i - 1] : null
    const up = above === null ? -1 : junction(above.roads)
    if (up !== -1 && chance(TURN_CHANCE)) {
        turnDown(vehicle, up, above.height + ROAD / 2, -1)
        return above
    }
    if (vehicle.at > width + VEHICLE.length) {
        vehicle.at = -VEHICLE.length
    } else if (vehicle.at < -VEHICLE.length) {
        vehicle.at = width + VEHICLE.length
    }
    return block
}

/**
 * Moves every vehicle on by `dt` seconds along its road, and moves the ones that turned into another block to that
 * block's list once every block has been stepped, so none is stepped twice in a frame
 */
function advanceVehicles(dt) {
    const arrivals = []
    blocks.forEach((block, i) => {
        block.vehicles = block.vehicles.filter((vehicle) => {
            const from = vehicle.at
            vehicle.at += vehicle.dir * vehicle.speed * dt
            const now =
                vehicle.road === ACROSS
                    ? driveAcross(i, vehicle, from)
                    : driveDown(i, vehicle)
            if (now !== block && now !== null) {
                arrivals.push([now, vehicle])
            }
            return now === block
        })
    })
    for (const [block, vehicle] of arrivals) {
        block.vehicles.push(vehicle)
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
 * A cloud centred at (x, y), at one of the scales: circles whose centres fall within the ellipse, each with its
 * gradient built here, so no frame builds one; `outline` holds the circles as one path, for the shadow; `up` is the
 * reach from the centre to the top edge, which tells when the cloud has left the viewport and how far above it a new
 * one spawns.
 */
function makeCloud(x, y) {
    const circles = []
    const outline = new Path2D()
    let up = 0
    const scale = CLOUD_SCALES[integer(0, CLOUD_SCALES.length - 1)]
    const count = integer(CLOUD_CIRCLES.min, CLOUD_CIRCLES.max)
    for (let i = 0; i < count; i++) {
        // A uniform angle and a square-rooted distance spread the centres evenly over the ellipse's area
        const angle = between(0, TAU)
        const distance = Math.sqrt(between(0, 1))
        const dx = Math.cos(angle) * CLOUD_SPREAD.x * distance * scale
        const dy = Math.sin(angle) * CLOUD_SPREAD.y * distance * scale
        const radius = between(CLOUD_RADIUS.min, CLOUD_RADIUS.max) * scale
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

/** `points` of the right side and their mirror on the left as one path of two polygons */
function mirrored(points) {
    const path = polygon(points)
    path.addPath(polygon(points.map(([x, y]) => [-x, y])))
    return path
}

/** A rectangle with corners rounded by `r`, added to `path` */
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
 * ahead of the leading edge on pylons, a tailplane and a fin. The markings (slats, flaps, fairings, pylons, the pods'
 * intake lip, mouth and exhaust, the cockpit glazing, the windows and the doors) sit on the parts.
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
    blocks = []
    recycleBlocks()
    clouds = []
    const count = integer(CLOUD_COUNT.min, CLOUD_COUNT.max)
    for (let i = 0; i < count; i++) {
        clouds.push(makeCloud(between(0, width), between(0, height)))
    }
    jet = buildJet()
    placeJet()
}

/** Moves the scene on by `dt` seconds, replaces what has scrolled off the bottom and drives the vehicles */
function advance(dt) {
    t += dt
    for (const block of blocks) {
        block.top += GROUND_SPEED * dt
    }
    for (const cloud of clouds) {
        cloud.y += CLOUD_SPEED * dt
    }
    recycleBlocks()
    recycleClouds()
    advanceVehicles(dt)
}

/** One frame, in the order the layers stack: field edges, cells, roads, vehicles, cloud shadows, clouds, jet, lights */
function draw() {
    drawGround()
    drawCloudShadows()
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
    // Opaque: the field-edge fill covers the bitmap every frame, so the browser composites it without blending
    ctx = canvas.getContext("2d", { alpha: false })
    width = message.width
    height = message.height
    dpr = message.dpr
    reduced = message.reduced
    hidden = message.hidden
    palette = message.palette
    colors = {
        fieldEdge: rgba(palette.fieldEdge, 1),
        road: rgba(palette.road, 1),
        // The vehicles: light ones in the cloud white, dark ones in the shadow ink, each a little translucent
        vehicleLight: light(0.9),
        vehicleDark: dark(0.7),
        fields: palette.fields.map((field) => rgba(field, 1)),
        cloudCore: rgba(palette.cloud, CLOUD_ALPHA),
        cloudRim: rgba(palette.cloud, 0),
        cloudShadow: rgba(palette.cloudShadow, CLOUD_SHADOW.alpha),
        jet: rgba(palette.jet, 1),
        jetOutline: rgba(palette.jetOutline, 1),
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
 * resizes the bitmap; a new size also refits the scene: the ground is regenerated when the width grows, as its roads
 * and cells are cut for the width, and kept otherwise, with blocks dropped or added as the height asks; a cloud now past an edge
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
            blocks = []
        }
        recycleBlocks()
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
