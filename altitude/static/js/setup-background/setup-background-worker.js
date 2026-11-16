/**
 * The setup page's background scene, drawn by this module Worker on the transferred canvas: a top-down view of a jet over
 * clouds and farmland that scroll down the screen. Sizes are CSS px; the context is scaled by the pixel ratio, capped at 2,
 * so a 3× phone does not triple the fill rate. The scene is random and never repeats: what scrolls off the bottom is
 * dropped, and new ground and clouds are generated above the top edge.
 */
import { buildFieldTextures } from "./setup-background-textures.js"

const TAU = 2 * Math.PI

const MAX_DPR = 2
// In ms: a resumed tab or a stalled frame moves the scene one short step
const MAX_STEP = 100

// The ground's speed in px/s. EDGE is the line of field-edge color between cells and bands and along each side of a road
const GROUND_SPEED = 50
const BAND_HEIGHT = { min: 60, max: 220 }
const CELL_WIDTH = { min: 60, max: 320 }
const EDGE = 3
const ROAD = 10

// A block of ground, from one road across to the next, is BLOCK_BANDS bands tall; the roads down it stand SPAN_FIELDS
// fields apart
const BLOCK_BANDS = { min: 2, max: 5 }
const SPAN_FIELDS = { min: 2, max: 5 }

// Speeds in px/s over the ground. VEHICLE_CHANCE is that of a road down starting with a vehicle, TURN_CHANCE that of
// turning at a junction passed. A vehicle's road is ACROSS, the block's road across, or the index of a road down it
const VEHICLE = { length: 5, width: 3 }
const VEHICLE_SPEED = { min: 15, max: 35 }
const VEHICLES_ACROSS = { min: 0, max: 2 }
const VEHICLE_CHANCE = 0.5
const TURN_CHANCE = 0.35
const ACROSS = -1

// The clouds move at 1.6 × the ground's speed, so they read as nearer. CLOUD_SPREAD is the half-axes of the ellipse a
// cloud's circle centres fall in; each cloud is drawn at one of CLOUD_SCALES. A new cloud's top spawns up to CLOUD_GAP
// above the top edge
const CLOUD_SPEED = 80
const CLOUD_COUNT = { min: 4, max: 8 }
const CLOUD_CIRCLES = { min: 4, max: 8 }
const CLOUD_RADIUS = { min: 30, max: 90 }
const CLOUD_SPREAD = { x: 110, y: 60 }
const CLOUD_SCALES = [1, 2]
const CLOUD_ALPHA = 0.6
const CLOUD_GAP = 400
const CLOUD_SHADOW = { x: -40, y: 40, alpha: 0.28 }

// The jet's length in px and its anchor's x as a fraction of the width, smaller under NARROW_VIEWPORT. It holds still,
// the scenery moving under it. The bottom edge cuts it TAIL_CUT of its length behind its centre, so the nose, the wings
// and the engines show and the tailplane and the fin stay below
const JET_WIDE = { length: 420, x: 0.18 }
const JET_NARROW = { length: 270, x: 0.14 }
const NARROW_VIEWPORT = 600
const TAIL_CUT = 0.28
const OUTLINE = 1

// In seconds: the navigation lights are on for `on` of every `period`; the strobe flashes twice every `period`, the
// second `gap` after the first. LIGHT_RADIUS is a fraction of the jet's length; the glow reaches HALO times it
const NAV_LIGHT = { period: 1, on: 0.5 }
const STROBE = { period: 1.2, flash: 0.06, gap: 0.12 }
const LIGHT_RADIUS = 0.014
const HALO = 3

let canvas = null
let ctx = null
let width = 0
let height = 0
let dpr = 1
let reduced = false
let hidden = false
let palette = null
let colors = null

// `frame` is the pending frame's handle (0 when none); `last` is null after a clock reset, so the next frame measures no
// gap; `t` is the scene's time in seconds, which the lights read
let frame = 0
let last = null
let t = 0

// The ground's blocks, topmost first
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

function chance(probability) {
    return between(0, 1) < probability
}

/** In the comma form every browser parses */
function rgba([r, g, b], alpha) {
    return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

/** The channels halfway between two colors: an unlit lamp, its color toward the ink */
function halfway(a, b) {
    return a.map((channel, i) => Math.round((channel + b[i]) / 2))
}

function dark(alpha) {
    return rgba(palette.cloudShadow, alpha)
}

function light(alpha) {
    return rgba(palette.cloud, alpha)
}

function placed(x, y, paint) {
    ctx.save()
    ctx.translate(x, y)
    paint()
    ctx.restore()
}

/** Sizing the bitmap clears it and resets the context's state, so the scale and the joins are set after */
function sizeBitmap() {
    const scale = Math.min(dpr, MAX_DPR)
    canvas.width = Math.round(width * scale)
    canvas.height = Math.round(height * scale)
    ctx.setTransform(scale, 0, 0, scale, 0, 0)
    // The jet's wingtips are sharp: a mitre there would spike
    ctx.lineJoin = "round"
}

/** The index of a field color other than `previous` (null for a band's first cell) */
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
 * Every span between a block's roads cut into 2–5 cells within CELL_WIDTH, each the min plus a random share of the slack.
 * A cell's width includes the edge to its right, so a span's last cell ends an edge short of the road.
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
 * A block of ground: the road across its top, then bands of cells cut between the roads down it, for the current width.
 * The first span starts up to a field's width left of the edge, so a road can fall anywhere across the viewport; the last
 * runs past the right edge, where no road is kept. The caller sets its top.
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

function bottomOf(block) {
    return block.top + block.height
}

/**
 * Drops the blocks past the bottom edge and keeps the ground covered: above the topmost (from the bottom edge up when the
 * ground is empty, at init and after the width grows), and below the lowest when a taller viewport uncovers ground.
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

/** The field-edge color under everything, then each block in its own frame */
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
 * Each cell leaves the edge to its right and below it. Bands and cells wholly outside the viewport are skipped: a block
 * may stand mostly above it, and its last span runs past the right edge.
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

/** The road across the top, and the roads down to the block's bottom, where each meets the next block's road across */
function drawRoads(block) {
    ctx.fillStyle = colors.road
    ctx.fillRect(0, 0, width, ROAD)
    for (const x of block.roads) {
        ctx.fillRect(x, 0, ROAD, block.height)
    }
}

/** `at` is an x on the road across, or an offset from the block's top on a road down */
function makeVehicle(road, at) {
    return {
        road,
        at,
        dir: chance(0.5) ? 1 : -1,
        speed: between(VEHICLE_SPEED.min, VEHICLE_SPEED.max),
        light: chance(0.5),
    }
}

/** Where a vehicle is in its block's frame, keeping to the right of its road */
function vehiclePlace(block, { road, at, dir }) {
    if (road === ACROSS) {
        // Eastbound on the south side, westbound on the north
        return [at, ROAD * (dir === 1 ? 0.75 : 0.25)]
    }
    // Southbound on the west side, northbound on the east
    return [block.roads[road] + ROAD * (dir === 1 ? 0.25 : 0.75), at]
}

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

function turnAcross(vehicle, x) {
    vehicle.road = ACROSS
    vehicle.at = x
    vehicle.dir = chance(0.5) ? 1 : -1
}

function turnDown(vehicle, road, at, dir) {
    vehicle.road = road
    vehicle.at = at
    vehicle.dir = dir
}

/**
 * After a step down block `i`, the block the vehicle is in: at either end it turns onto the road across there, the block
 * below's at the bottom. Null when it has left the scene (no block below: that junction is past the bottom edge).
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
 * After a step across block `i`, the block the vehicle is in: at a junction passed it turns, by TURN_CHANCE, down this
 * block's road or up the block above's; past either side of the viewport it comes round the other.
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

/** A vehicle that turned into another block joins its list once every block has been stepped, so none steps twice */
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

/** Fading from CLOUD_ALPHA at the centre to nothing at the rim */
function cloudGradient(radius) {
    const gradient = ctx.createRadialGradient(0, 0, 0, 0, 0, radius)
    gradient.addColorStop(0, colors.cloudCore)
    gradient.addColorStop(1, colors.cloudRim)
    return gradient
}

/**
 * Each circle's gradient is built here, so no frame builds one. `outline` is the circles as one path, for the shadow;
 * `up` is the reach from the centre to the cloud's top.
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

/** A cloud whose top is up to CLOUD_GAP above the viewport */
function spawnCloud() {
    const cloud = makeCloud(between(0, width), 0)
    cloud.y = -between(0, CLOUD_GAP) - cloud.up
    return cloud
}

/** Replaces each cloud whose top has passed the bottom edge */
function recycleClouds() {
    for (let i = 0; i < clouds.length; i++) {
        if (clouds[i].y - clouds[i].up >= height) {
            clouds[i] = spawnCloud()
        }
    }
}

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

function drawCloudShadows() {
    ctx.fillStyle = colors.cloudShadow
    for (const cloud of clouds) {
        placed(cloud.x + CLOUD_SHADOW.x, cloud.y + CLOUD_SHADOW.y, () => {
            ctx.fill(cloud.outline)
        })
    }
}

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

/** The right side's `points` and their mirror on the left, as one path */
function mirrored(points) {
    const path = polygon(points)
    path.addPath(polygon(points.map(([x, y]) => [-x, y])))
    return path
}

function roundedRect(path, x, y, w, h, r) {
    path.moveTo(x + r, y)
    path.arcTo(x + w, y, x + w, y + h, r)
    path.arcTo(x + w, y + h, x, y + h, r)
    path.arcTo(x, y + h, x, y, r)
    path.arcTo(x, y, x + w, y, r)
    path.closePath()
}

/** Shading that makes a tube from x0 to x1 read as round: dark along both sides, light along the crown */
function tubeShade(x0, x1, strength) {
    const gradient = ctx.createLinearGradient(x0, 0, x1, 0)
    gradient.addColorStop(0, dark(strength))
    gradient.addColorStop(0.25, dark(0))
    gradient.addColorStop(0.5, light(strength))
    gradient.addColorStop(0.75, light(0))
    gradient.addColorStop(1, dark(strength))
    return gradient
}

/** The solid dot out to the lamp's radius, then a glow fading to nothing at HALO times it */
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
 * The jet's paths and shading in units of its length (nose at y = -0.5, tail at 0.5), drawn under a scale of the length,
 * so they are built once for both sizes. A twin-engine wide-body from above: a tube a tenth of the length wide, wings
 * spanning the length and swept 32°, their trailing edge kinked inboard.
 */
function buildJet() {
    // The right wing's edges as functions of x
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

    // The pods well ahead of the leading edge, each shaded as a tube; the pylons from inside a pod's tail back over the
    // wing; the intake lip, the mouth and the exhaust as bands across every pod
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

    // The fuselage, the cockpit glazing, and along each side the doors and a line of windows that gives way at them
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

    // The fin seen from above is a thin strip on the centreline
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

function placeJet() {
    const size = width < NARROW_VIEWPORT ? JET_NARROW : JET_WIDE
    jet.length = size.length
    jet.x = width * size.x
    jet.y = height - TAIL_CUT * size.length
}

/** Runs `paint` in the jet's frame, (dx, dy) px from its anchor and scaled to its length */
function inJetFrame(dx, dy, paint) {
    placed(jet.x + dx, jet.y + dy, () => {
        ctx.scale(jet.length, jet.length)
        paint()
    })
}

function skin(path, shade = null) {
    ctx.fillStyle = colors.jet
    ctx.fill(path)
    if (shade) {
        ctx.fillStyle = shade
        ctx.fill(path)
    }
    ctx.stroke(path)
}

function marking(path, color) {
    ctx.fillStyle = color
    ctx.fill(path)
}

function line(path, color) {
    ctx.save()
    ctx.strokeStyle = color
    ctx.stroke(path)
    ctx.restore()
}

/** Part over part, so each covers what is under it; the pods' bands are clipped to their rounded corners */
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

function litLights() {
    const nav = t % NAV_LIGHT.period < NAV_LIGHT.on
    const phase = t % STROBE.period
    const flash = (start) => phase >= start && phase < start + STROBE.flash
    return { nav, strobe: flash(0) || flash(STROBE.gap) }
}

/** Each tip keeps a dimmed lamp between blinks, so the tips keep their shape; a lit lamp glows over it */
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

function drawLamp([x, y], color) {
    ctx.fillStyle = color
    ctx.beginPath()
    ctx.arc(x, y, LIGHT_RADIUS, 0, TAU)
    ctx.fill()
    ctx.stroke()
}

function drawGlow([x, y], halo) {
    const reach = LIGHT_RADIUS * HALO
    placed(x, y, () => {
        ctx.fillStyle = halo
        ctx.fillRect(-reach, -reach, 2 * reach, 2 * reach)
    })
}

/** The scene from scratch for the current size, the clouds spread over the viewport */
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

function draw() {
    drawGround()
    drawCloudShadows()
    drawClouds()
    drawJet()
}

/** A reduced-motion user gets a still, a hidden tab no frames */
function animating() {
    return !reduced && !hidden
}

/**
 * Runs only while the loop is on (a stop cancels the pending frame, and no message runs while a frame does), so it
 * always continues the loop.
 */
function onFrame(timestamp) {
    const dt = last === null ? 0 : Math.min(timestamp - last, MAX_STEP)
    last = timestamp
    advance(dt / 1000)
    draw()
    frame = requestAnimationFrame(onFrame)
}

/** A fresh clock, so the first frame measures no gap; nothing when a frame is already pending */
function startLoop() {
    if (frame !== 0) {
        return
    }
    last = null
    frame = requestAnimationFrame(onFrame)
}

/** The frame drawn last stays as the still */
function stopLoop() {
    if (frame !== 0) {
        cancelAnimationFrame(frame)
        frame = 0
    }
}

function syncLoop() {
    if (animating()) {
        startLoop()
    } else {
        stopLoop()
    }
}

/**
 * Draws the first frame at once rather than in a frame callback: the page fades the canvas in on `ready`, and a hidden
 * document gets no frame callbacks.
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
 * The observer's first notification repeats init's size: a no-op. The ground is regenerated when the width grows, as its
 * roads and cells are cut for the width. A frame is drawn at once: sizing the bitmap clears it, which would flash blank
 * before the next frame, or stay blank in a still.
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

/** Shown again, the loop resumes with a fresh clock, so nothing jumps */
function onVisible(message) {
    hidden = message.hidden
    syncLoop()
}

function onMotion(message) {
    reduced = message.reduced
    syncLoop()
}

const handlers = {
    init: onInit,
    resize: onResize,
    visible: onVisible,
    motion: onMotion,
}

self.addEventListener("message", ({ data }) => {
    handlers[data.type]?.(data)
})
