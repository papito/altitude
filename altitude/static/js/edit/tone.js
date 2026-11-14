/**
 * The Edit's per-pixel math (`docs/image-edits.md`, **Per-pixel math**), as the
 * server's renderer has it (`util/EditRenderer.scala`): the tables of steps 1 to 6 (to linear, white
 * balance, exposure, back to sRGB, the tone steps and the curve), each channel's 4096 entries over
 * the input value read with linear interpolation; step 7's saturation and vibrance; and what steps 8
 * to 10 are made of (the Gaussian kernel of Clarity's and Sharpen's blurs, the vignette's weight, the
 * grain's hash and value noise). The WebGL renderer (`renderer.js`) bakes the tables into a texture
 * and runs the rest in its shaders. DOM-free, so the parity tests run it unchanged under Node
 * (`scripts/test-js/edit.test.mjs`, against `test/resources/edit/vectors.json`).
 */
import { clamp } from "./geometry.js"

/** Entries of each channel's table of the tone steps (1 to 6), over the input value 0 to 1 */
export const TABLE_SIZE = 4096

/** The output long edge at which the sizes of Sharpen's blur and of Grain's cells are given; they scale with the output */
export const REFERENCE_LONG_EDGE = 3840

/** The weights of red, green and blue in the luminance the Edit's steps 2, 7 and 8 read (Rec. 709's) */
export const LUMA = Object.freeze([0.2126, 0.7152, 0.0722])

/** The sRGB EOTF: an sRGB-encoded value to linear light */
export function srgbToLinear(v) {
    return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)
}

/** The sRGB OETF: linear light to an sRGB-encoded value, unclamped */
export function linearToSrgb(l) {
    return l <= 0.0031308 ? 12.92 * l : 1.055 * Math.pow(l, 1 / 2.4) - 0.055
}

/** The white balance's gains of red, green and blue (step 2), divided by their luminance so a neutral gray keeps its own */
export function whiteBalanceGains(recipe) {
    const t = recipe.temperature / 100
    const tint = recipe.tint / 100
    const r = (1 + 0.28 * t) * (1 + 0.08 * tint)
    const g = 1 - 0.2 * tint
    const b = (1 - 0.28 * t) * (1 + 0.08 * tint)
    const luminance = LUMA[0] * r + LUMA[1] * g + LUMA[2] * b
    return [r / luminance, g / luminance, b / luminance]
}

/** The smoothstep of t clamped to [0, 1]: t²(3 − 2t) */
export function smoothstep(t) {
    const x = clamp(t)
    return x * x * (3 - 2 * x)
}

/** Step 5, the tone of one sRGB-encoded value, in [0, 1] */
export function tone(value, recipe) {
    const blackPoint = (-0.1 * recipe.blacks) / 100
    const whitePoint = 1 - (0.15 * recipe.whites) / 100
    let v = (value - blackPoint) / (whitePoint - blackPoint)

    const x = clamp(v)
    v += 0.95 * (recipe.shadows / 100) * x * (1 - x) * (1 - x)

    if (recipe.highlights < 0) {
        // Towards a shoulder above 0.45 that no brighter tone overtakes
        const u = Math.max(0, v - 0.45)
        const shoulder = v - u + u / (1 + 1.6 * u)
        v += (shoulder - v) * (-recipe.highlights / 100)
    } else if (recipe.highlights > 0) {
        v += 0.3 * (recipe.highlights / 100) * smoothstep((v - 0.35) / 0.65)
    }

    if (recipe.gamma !== 0 && v > 0) {
        v = Math.pow(v, 1 / Math.pow(2, recipe.gamma / 100))
    }

    if (recipe.contrast !== 0) {
        const c = recipe.contrast / 100
        const cx = clamp(v)
        const s =
            c > 0
                ? 0.5 - 0.5 * Math.cos(Math.PI * cx)
                : Math.acos(1 - 2 * cx) / Math.PI
        v = cx + 0.85 * Math.abs(c) * (s - cx)
    }

    return clamp(v)
}

/**
 * Step 6: the monotone cubic Hermite through the points (Fritsch–Carlson), held flat outside the
 * first and last x and clamped to [0, 1], as a function of x. The tangents are Fritsch–Carlson's as
 * `docs/image-edits.md` gives them (step 6): the end ones the end secants (each secant over at least 1e-6 of x), an inner one the mean of its
 * two secants (0 where they differ in sign), then, segment by segment in order, both zero on a flat
 * segment, or scaled down by 3 / √(α² + β²) when α² + β² is over 9.
 */
export function createCurve(points) {
    const xs = points.map((p) => p[0])
    const ys = points.map((p) => p[1])
    const n = xs.length
    const secants = []
    for (let i = 0; i < n - 1; i++) {
        secants.push((ys[i + 1] - ys[i]) / Math.max(1e-6, xs[i + 1] - xs[i]))
    }
    const m = new Array(n)
    m[0] = secants[0]
    m[n - 1] = secants[n - 2]
    for (let i = 1; i < n - 1; i++) {
        m[i] =
            secants[i - 1] * secants[i] <= 0
                ? 0
                : (secants[i - 1] + secants[i]) / 2
    }
    for (let i = 0; i < n - 1; i++) {
        if (secants[i] === 0) {
            m[i] = 0
            m[i + 1] = 0
            continue
        }
        const alpha = m[i] / secants[i]
        const beta = m[i + 1] / secants[i]
        const s = alpha * alpha + beta * beta
        if (s > 9) {
            const t = 3 / Math.sqrt(s)
            m[i] = t * alpha * secants[i]
            m[i + 1] = t * beta * secants[i]
        }
    }

    return (x) => {
        if (x <= xs[0]) {
            return ys[0]
        }
        if (x >= xs[n - 1]) {
            return ys[n - 1]
        }
        // The segment whose end is the first at or past x
        let seg = 0
        while (seg < n - 2 && x > xs[seg + 1]) {
            seg++
        }
        const h = xs[seg + 1] - xs[seg]
        const t = (x - xs[seg]) / h
        const t2 = t * t
        const t3 = t2 * t
        return clamp(
            (2 * t3 - 3 * t2 + 1) * ys[seg] +
                (t3 - 2 * t2 + t) * h * m[seg] +
                (-2 * t3 + 3 * t2) * ys[seg + 1] +
                (t3 - t2) * h * m[seg + 1],
        )
    }
}

/** The curve sampled at `size` evenly spaced x from 0 to 1, for drawing it (the Edit panel's curve editor) */
export function curveLut(points, size = 256) {
    const curve = createCurve(points)
    const lut = new Float32Array(size)
    for (let i = 0; i < size; i++) {
        lut[i] = curve(i / (size - 1))
    }
    return lut
}

/** Steps 1 to 6 on one channel's sRGB-encoded value: `channel` 0 is red, 1 green, 2 blue */
export function channelValue(v, channel, recipe, curve = null) {
    const gain = whiteBalanceGains(recipe)[channel]
    const toned = tone(
        linearToSrgb(srgbToLinear(v) * gain * Math.pow(2, recipe.exposure)),
        recipe,
    )
    return (curve ?? createCurve(recipe.curve))(toned)
}

/**
 * The tables of steps 1 to 6, red, green and blue: entry i of a channel is its value for the input
 * i / 4095, held as a 32-bit float, as the server's are
 */
export function tables(recipe) {
    const curve = createCurve(recipe.curve)
    const gains = whiteBalanceGains(recipe)
    const exposure = Math.pow(2, recipe.exposure)
    return gains.map((gain) => {
        const table = new Float32Array(TABLE_SIZE)
        for (let i = 0; i < TABLE_SIZE; i++) {
            const linear = srgbToLinear(i / (TABLE_SIZE - 1)) * gain * exposure
            table[i] = curve(tone(linearToSrgb(linear), recipe))
        }
        return table
    })
}

/** The three tables interleaved as RGBA texels, entry by entry, alpha 1: the renderer lays them out in rows of 64 */
export function tableTexels(recipe) {
    const [red, green, blue] = tables(recipe)
    const texels = new Float32Array(TABLE_SIZE * 4)
    for (let i = 0; i < TABLE_SIZE; i++) {
        texels[i * 4] = red[i]
        texels[i * 4 + 1] = green[i]
        texels[i * 4 + 2] = blue[i]
        texels[i * 4 + 3] = 1
    }
    return texels
}

/** A table read at the value clamped to [0, 1], linearly between its two nearest entries */
export function lookup(table, v) {
    const f = clamp(v) * (TABLE_SIZE - 1)
    const i = Math.floor(f)
    return i >= TABLE_SIZE - 1
        ? table[TABLE_SIZE - 1]
        : table[i] + (table[i + 1] - table[i]) * (f - i)
}

/** Step 7: each channel moved from the luminance by the saturation's and the vibrance's factor */
export function saturate(r, g, b, recipe) {
    const y = LUMA[0] * r + LUMA[1] * g + LUMA[2] * b
    const chroma = Math.max(r, g, b) - Math.min(r, g, b)
    const vib = recipe.vibrance / 100
    const k =
        (1 + recipe.saturation / 100) *
        (1 + vib * (vib > 0 ? 1 - Math.min(1, 1.6 * chroma) : 1))
    return [y + (r - y) * k, y + (g - y) * k, y + (b - y) * k]
}

/** Clarity's blur, in pixels: 1.5 % of the output's short side */
export function claritySigma(width, height) {
    return 0.015 * Math.min(width, height)
}

/** Sharpen's blur, in pixels: 0.8 px at the reference long edge, scaled with the output's, never below 0.4 */
export function sharpenSigma(width, height) {
    return Math.max(0.4, (0.8 * Math.max(width, height)) / REFERENCE_LONG_EDGE)
}

/**
 * Grain's cell, in pixels: 1.75 px at the reference long edge, scaled with the output's, and never
 * under a pixel (`grainScale` makes up for the floor)
 */
export function grainCell(width, height) {
    return Math.max(1, rawGrainCell(width, height))
}

/**
 * What Grain's strength is multiplied by: 1 while its cell is a pixel or more, and below that the
 * cell's size in pixels. A Display image shown at a smaller size, as on the stage, is averaged over
 * about 1/c² of its cells per pixel shown (c its cell in the pixels shown), which takes the noise's
 * spread down by c; the canvas, drawn at that size with its cell floored at a pixel, draws
 * independent cells, so its noise is scaled by c to look as strong.
 */
export function grainScale(width, height) {
    return Math.min(1, rawGrainCell(width, height))
}

function rawGrainCell(width, height) {
    return (1.75 * Math.max(width, height)) / REFERENCE_LONG_EDGE
}

/**
 * The half of OpenCV's Gaussian kernel for `sigma` that the blur of a float image takes
 * (`GaussianBlur` with no size given): `round(8σ + 1) | 1` taps, each exp(−x²/2σ²), normalized.
 * Answers the weights from the center out: entry k weighs the taps k from the center.
 */
export function gaussianHalfKernel(sigma) {
    const size = Math.round(sigma * 8 + 1) | 1
    const radius = (size - 1) / 2
    const weights = []
    let sum = 0
    for (let i = 0; i < size; i++) {
        const x = i - radius
        const t = Math.exp((-0.5 * x * x) / (sigma * sigma))
        weights.push(t)
        sum += t
    }
    return Float32Array.from(weights.slice(radius), (w) => w / sum)
}

/**
 * Step 9's weight at the pixel (x, y), integer columns and rows from the top left, of a
 * `width` × `height` output: the smoothstep of the distance d from the center, the offsets being
 * fractions of the half width and half height, starting at d = 0.39375 and reaching 1 over 0.65625
 */
export function vignetteWeight(x, y, width, height) {
    const dx = (x - width / 2) / (width / 2)
    const dy = (y - height / 2) / (height / 2)
    return smoothstep((Math.sqrt((dx * dx + dy * dy) / 2) - 0.39375) / 0.65625)
}

/** The grain's integer hash of a cell (`docs/image-edits.md`, step 10), in [0, 1]: 32-bit integer arithmetic, each product wrapping */
export function hash(x, y) {
    let h = (Math.imul(x, 374761393) + Math.imul(y, 668265263)) | 0
    h = Math.imul(h ^ (h >>> 13), 1274126177)
    return ((h ^ (h >>> 16)) >>> 0) / 4294967295
}

/** Step 10's value noise at the pixel (x, y) over cells of `cell` pixels: the hash bilinearly between the cell's corners, −½ */
export function grainNoise(x, y, cell) {
    const gx = x / cell
    const gy = y / cell
    const x0 = Math.floor(gx)
    const y0 = Math.floor(gy)
    const fx = gx - x0
    const fy = gy - y0
    return (
        (hash(x0, y0) * (1 - fx) + hash(x0 + 1, y0) * fx) * (1 - fy) +
        (hash(x0, y0 + 1) * (1 - fx) + hash(x0 + 1, y0 + 1) * fx) * fy -
        0.5
    )
}
