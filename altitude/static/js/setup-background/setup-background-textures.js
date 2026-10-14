/**
 * The textures of the farmland's fields in the setup page's background scene, as seen from high altitude: ten ways a
 * field is worked, each painted over a cell's color in translucent dark and light ink, so the field's color shows
 * through. Most are tiles of TILE × TILE px repeated by a pattern anchored to the cell's corner, so a texture scrolls
 * with its cell; the strip crops' headland and the centre pivot are drawn to the cell, since they are edged or centred
 * on it. The Worker (setup-background-worker.js) builds them once and paints one per cell.
 */

const TAU = 2 * Math.PI
// A tile's side in CSS px: every period below divides it, so a tile repeats without a seam
const TILE = 128

/** A tile `draw`n at CSS px, as a repeating pattern of `ctx` */
function tilePattern(ctx, draw) {
    const tile = new OffscreenCanvas(TILE, TILE)
    draw(tile.getContext("2d"))
    return ctx.createPattern(tile, "repeat")
}

/** Fills the cell of w × h px at the origin with `pattern`; the origin is the cell's corner, so the tile starts there */
function fillCell(c, pattern, w, h) {
    c.fillStyle = pattern
    c.fillRect(0, 0, w, h)
}

/** Straight lines over a tile every `step` px from `offset`: across it, or down it when `down` */
function rules(c, step, offset, color, width, down = false) {
    c.strokeStyle = color
    c.lineWidth = width
    c.beginPath()
    for (let at = offset + 0.5; at < TILE; at += step) {
        if (down) {
            c.moveTo(at, 0)
            c.lineTo(at, TILE)
        } else {
            c.moveTo(0, at)
            c.lineTo(TILE, at)
        }
    }
    c.stroke()
}

/** Lines at 45° over a tile, `step` px apart along x: the lines x − y = k · step, so the tile repeats */
function diagonals(c, step, color, width) {
    c.strokeStyle = color
    c.lineWidth = width
    c.beginPath()
    for (let k = -TILE; k < TILE; k += step) {
        c.moveTo(k, 0)
        c.lineTo(k + TILE, TILE)
    }
    c.stroke()
}

/** A dot of `radius` at (x, y) */
function dot(c, x, y, radius, color) {
    c.fillStyle = color
    c.beginPath()
    c.arc(x, y, radius, 0, TAU)
    c.fill()
}

/** Strip crops: bands of two crops 16 px wide across the field, inside a lighter headland 6 px wide round its edge */
function stripCrops(ctx, ink) {
    const bands = tilePattern(ctx, (c) => {
        c.fillStyle = ink.dark(0.1)
        for (let y = 0; y < TILE; y += 32) {
            c.fillRect(0, y, TILE, 16)
        }
    })
    const headland = ink.light(0.1)
    return {
        name: "strip crops",
        paint(c, w, h) {
            fillCell(c, bands, w, h)
            c.strokeStyle = headland
            c.lineWidth = 6
            c.strokeRect(3, 3, w - 6, h - 6)
        },
    }
}

/**
 * A centre pivot: the circle its arm sweeps, inscribed in the cell and lighter than the dry corners, ringed by the
 * wheel tracks every 8 px, the arm pointing where the cell's `seed` says
 */
function centrePivot(ink) {
    const corners = ink.dark(0.12)
    const circle = ink.light(0.05)
    const rings = ink.dark(0.1)
    const arm = ink.dark(0.3)
    return {
        name: "centre pivot",
        paint(c, w, h, seed) {
            const cx = w / 2
            const cy = h / 2
            const radius = Math.min(w, h) * 0.47
            // The corners the arm never reaches: the cell less the circle, by the even-odd rule
            c.fillStyle = corners
            c.beginPath()
            c.rect(0, 0, w, h)
            c.moveTo(cx + radius, cy)
            c.arc(cx, cy, radius, 0, TAU)
            c.fill("evenodd")
            c.fillStyle = circle
            c.beginPath()
            c.arc(cx, cy, radius, 0, TAU)
            c.fill()
            c.strokeStyle = rings
            c.lineWidth = 1
            c.beginPath()
            for (let r = 8; r < radius; r += 8) {
                c.moveTo(cx + r, cy)
                c.arc(cx, cy, r, 0, TAU)
            }
            c.stroke()
            const angle = seed % TAU
            c.strokeStyle = arm
            c.lineWidth = 2
            c.beginPath()
            c.moveTo(cx, cy)
            c.lineTo(
                cx + radius * Math.cos(angle),
                cy + radius * Math.sin(angle),
            )
            c.stroke()
        },
    }
}

/**
 * The ten textures in the order of their numbers, each a `name` and a `paint(c, w, h, seed)` that paints a cell of
 * w × h px at the origin of `c`, `seed` a number constant for the cell. `ink.dark(alpha)` and `ink.light(alpha)` are
 * the scene's shadow and cloud colors at an alpha. The patterns are made on `ctx`, the context they are painted with.
 */
export function buildFieldTextures(ctx, ink) {
    const tiled = (name, draw) => {
        const pattern = tilePattern(ctx, draw)
        return { name, paint: (c, w, h) => fillCell(c, pattern, w, h) }
    }
    return [
        // 1: furrows running across the field, a dark furrow and a lighter ridge every 8 px
        tiled("furrows across", (c) => {
            rules(c, 8, 0, ink.dark(0.16), 1)
            rules(c, 8, 4, ink.light(0.08), 1)
        }),
        // 2: the same furrows running down the field
        tiled("furrows down", (c) => {
            rules(c, 8, 0, ink.dark(0.16), 1, true)
            rules(c, 8, 4, ink.light(0.08), 1, true)
        }),
        // 3: furrows on the diagonal
        tiled("furrows diagonal", (c) => diagonals(c, 8, ink.dark(0.14), 1)),
        // 4: crop rows, a plant every 4 px in rows 8 px apart
        tiled("crop rows", (c) => {
            for (let y = 4; y < TILE; y += 8) {
                for (let x = 2; x < TILE; x += 4) {
                    dot(c, x, y, 1, ink.dark(0.2))
                }
            }
        }),
        // 5: tramlines, fine drill rows down the field and the sprayer's twin tracks every 64 px
        tiled("tramlines", (c) => {
            rules(c, 4, 0, ink.dark(0.06), 1, true)
            rules(c, 64, 20, ink.dark(0.22), 1, true)
            rules(c, 64, 23, ink.dark(0.22), 1, true)
        }),
        // 6: strip crops inside a headland
        stripCrops(ctx, ink),
        // 7: an orchard, trees 16 px apart in rows, each with its shadow to the lower left
        tiled("orchard", (c) => {
            for (let y = 8; y < TILE; y += 16) {
                for (let x = 8; x < TILE; x += 16) {
                    dot(c, x - 2, y + 2, 3, ink.dark(0.15))
                    dot(c, x, y, 3, ink.dark(0.3))
                }
            }
        }),
        // 8: pasture, soft blotches of wetter and drier grass; each blotch is drawn at the nine offsets of a tile
        // round itself, so one crossing the tile's edge continues on the other side when the tile repeats
        tiled("pasture", (c) => {
            for (let i = 0; i < 24; i++) {
                const x = Math.random() * TILE
                const y = Math.random() * TILE
                const rx = 10 + Math.random() * 18
                const ry = 10 + Math.random() * 18
                c.fillStyle = i % 2 ? ink.dark(0.07) : ink.light(0.07)
                for (const dx of [-TILE, 0, TILE]) {
                    for (const dy of [-TILE, 0, TILE]) {
                        c.beginPath()
                        c.ellipse(x + dx, y + dy, rx, ry, 0, 0, TAU)
                        c.fill()
                    }
                }
            }
        }),
        // 9: contour strips, rows following the lie of the land: waves 64 px long, 16 px apart
        tiled("contours", (c) => {
            c.strokeStyle = ink.dark(0.14)
            c.lineWidth = 1.5
            c.beginPath()
            for (let y = 0; y < TILE; y += 16) {
                c.moveTo(0, y)
                for (let x = 1; x <= TILE; x++) {
                    c.lineTo(x, y + 3 * Math.sin((TAU * x) / 64))
                }
            }
            c.stroke()
        }),
        // 10: a centre pivot
        centrePivot(ink),
    ]
}
