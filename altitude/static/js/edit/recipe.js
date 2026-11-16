/**
 * The Edit as the client holds it: a plain object of every control's value, the shape
 * `models/EditRecipe.scala` parses. `normalize` rounds the way the server does, so what the client
 * sends is what the server keeps. DOM-free.
 */
import { clamp, MIN_CROP } from "./geometry.js"

export const VERSION = 1

/** The Crop tool's aspect locks, as the Edit stores them; a ratio is written landscape, `portrait` turns it */
export const ASPECTS = ["original", "free", "1:1", "5:4", "7:5", "3:2", "16:9"]

export const IDENTITY_CURVE = Object.freeze([
    Object.freeze([0, 0]),
    Object.freeze([1, 1]),
])

// The curve's point count bounds, and the closest two points may be in x (one step of a curve coordinate)
export const MIN_CURVE_POINTS = 2
export const MAX_CURVE_POINTS = 16
export const MIN_CURVE_SPACING = 0.001

const ROTATIONS = [0, 90, 180, 270]
// Steps per unit of a crop edge (4 decimals) and of a curve coordinate (3 decimals)
const CROP_STEPS = 10000
const CURVE_STEPS = 1000
// Floating-point slack below a step when comparing coordinates (the server's)
const EPSILON = 1e-9

/**
 * The sliders. Ranges are the server's (`EditRecipe.SLIDERS`); `track` names a gradient track's
 * `--edit-track-<track>` token in `core.css`.
 */
export const SLIDERS = Object.freeze(
    Object.fromEntries(
        [
            slider("straighten", "Straighten", -45, 45, "cropRotate", {
                stepsPerUnit: 10,
                decimals: 1,
            }),
            slider("exposure", "Exposure", -5, 5, "light", {
                stepsPerUnit: 20,
                decimals: 2,
                track: "exposure",
            }),
            slider("contrast", "Contrast", -100, 100, "light"),
            slider("highlights", "Highlights", -100, 100, "light"),
            slider("shadows", "Shadows", -100, 100, "light"),
            slider("whites", "Whites", -100, 100, "light"),
            slider("blacks", "Blacks", -100, 100, "light"),
            slider("gamma", "Gamma", -100, 100, "light"),
            slider("temperature", "Temperature", -100, 100, "color", {
                track: "temperature",
            }),
            slider("tint", "Tint", -100, 100, "color", { track: "tint" }),
            slider("vibrance", "Vibrance", -100, 100, "color", {
                track: "vibrance",
            }),
            slider("saturation", "Saturation", -100, 100, "color", {
                track: "saturation",
            }),
            slider("sharpen", "Sharpen", 0, 150, "effects"),
            slider("clarity", "Clarity", -100, 100, "effects"),
            slider("vignette", "Vignette", -100, 100, "effects"),
            slider("grain", "Grain", 0, 100, "effects"),
        ].map((spec) => [spec.key, Object.freeze(spec)]),
    ),
)

function slider(
    key,
    label,
    min,
    max,
    section,
    { stepsPerUnit = 1, decimals = 0, track = null } = {},
) {
    return {
        key,
        label,
        min,
        max,
        section,
        stepsPerUnit,
        step: 1 / stepsPerUnit,
        decimals,
        track,
    }
}

/** The Edit panel's sections in order, each with the Edit's keys it sets; Crop & rotate holds the Crop tool's settings too */
export const SECTIONS = Object.freeze([
    Object.freeze({
        id: "cropRotate",
        label: "Crop & rotate",
        keys: Object.freeze([
            "rotate",
            "flipH",
            "flipV",
            "straighten",
            "crop",
            "aspect",
            "portrait",
            "keepInside",
            "cropFitted",
        ]),
    }),
    Object.freeze({
        id: "light",
        label: "Light",
        keys: Object.freeze([
            "exposure",
            "contrast",
            "highlights",
            "shadows",
            "whites",
            "blacks",
            "gamma",
            "curve",
        ]),
    }),
    Object.freeze({
        id: "color",
        label: "Color",
        keys: Object.freeze(["temperature", "tint", "vibrance", "saturation"]),
    }),
    Object.freeze({
        id: "effects",
        label: "Effects",
        keys: Object.freeze(["sharpen", "clarity", "vignette", "grain"]),
    }),
])

/** A slider's value to its decimals, the one formatter of the panel's readouts; a value that rounds to zero has no sign */
export function formatSlider(key, value) {
    const text = value.toFixed(SLIDERS[key].decimals)
    return /^-0(\.0+)?$/.test(text) ? text.slice(1) : text
}

// The Crop tool's settings, which change no pixel, so `sectionChanged` leaves them out
const CROP_TOOL_SETTINGS = ["aspect", "portrait", "keepInside", "cropFitted"]

/** The Edit that changes nothing, with the Crop tool's default settings */
export function defaults() {
    return {
        version: VERSION,
        rotate: 0,
        flipH: false,
        flipV: false,
        straighten: 0,
        crop: null,
        aspect: "original",
        portrait: null,
        keepInside: true,
        cropFitted: true,
        exposure: 0,
        contrast: 0,
        highlights: 0,
        shadows: 0,
        whites: 0,
        blacks: 0,
        gamma: 0,
        curve: IDENTITY_CURVE.map((point) => [...point]),
        temperature: 0,
        tint: 0,
        vibrance: 0,
        saturation: 0,
        sharpen: 0,
        clarity: 0,
        vignette: 0,
        grain: 0,
    }
}

// Every key of a stored Edit, in the order its JSON is written
const KEYS = Object.keys(defaults())

/** The nearest step; Math.round takes a half up, as the server's does */
function rounded(value, stepsPerUnit) {
    return Math.round(value * stepsPerUnit) / stepsPerUnit
}

function numberOr(value, fallback) {
    return typeof value === "number" && !Number.isNaN(value) ? value : fallback
}

/**
 * The Edit as the server keeps it: missing keys defaulted and unknown ones dropped, sliders clamped
 * and rounded to their step, the crop and curve rounded as below. What the server would refuse is
 * brought into range, so a normalized Edit always parses.
 */
export function normalize(recipe) {
    const base = defaults()
    const source = recipe ?? {}
    const result = { ...base }

    for (const spec of Object.values(SLIDERS)) {
        const value = numberOr(source[spec.key], 0)
        result[spec.key] = rounded(
            clamp(value, spec.min, spec.max),
            spec.stepsPerUnit,
        )
    }

    result.rotate = ROTATIONS.includes(source.rotate) ? source.rotate : 0
    for (const key of ["flipH", "flipV", "keepInside", "cropFitted"]) {
        result[key] = typeof source[key] === "boolean" ? source[key] : base[key]
    }
    result.aspect = ASPECTS.includes(source.aspect)
        ? source.aspect
        : base.aspect
    result.portrait =
        typeof source.portrait === "boolean" ? source.portrait : null
    result.crop = normalizeCrop(source.crop)
    result.curve = normalizeCurve(source.curve)

    // The stored key order
    return Object.fromEntries(KEYS.map((key) => [key, result[key]]))
}

/** A crop rectangle as the server rounds it, or null for none (the whole frame) */
export function normalizeCrop(crop) {
    if (!crop || typeof crop !== "object") {
        return null
    }

    const raw = {
        x: numberOr(crop.x, 0),
        y: numberOr(crop.y, 0),
        w: numberOr(crop.w, 1),
        h: numberOr(crop.h, 1),
    }
    // Inside the frame and at least MIN_CROP, before rounding
    raw.w = clamp(raw.w, MIN_CROP, 1)
    raw.h = clamp(raw.h, MIN_CROP, 1)
    raw.x = clamp(raw.x, 0, 1 - raw.w)
    raw.y = clamp(raw.y, 0, 1 - raw.h)

    const [x, w] = roundedSpan(raw.x, raw.w)
    const [y, h] = roundedSpan(raw.y, raw.h)
    return { x, y, w, h }
}

/**
 * A crop's start and length on one axis: both edges rounded to 4 decimals and the length their
 * difference, so a crop reaching the frame's edge keeps reaching it exactly; at least `MIN_CROP`
 */
function roundedSpan(start, length) {
    let first = rounded(start, CROP_STEPS)
    let last = Math.min(1, rounded(start + length, CROP_STEPS))
    if (rounded(last - first, CROP_STEPS) < MIN_CROP) {
        if (first + MIN_CROP <= 1) {
            last = rounded(first + MIN_CROP, CROP_STEPS)
        } else {
            first = rounded(last - MIN_CROP, CROP_STEPS)
        }
    }
    return [first, rounded(last - first, CROP_STEPS)]
}

/**
 * The curve's points as the server keeps them: sorted by x, in [0, 1], 3 decimals, a point closer
 * than `MIN_CURVE_SPACING` to the one before dropped, at most 16; the diagonal when fewer than 2 are left
 */
export function normalizeCurve(curve) {
    if (!Array.isArray(curve)) {
        return IDENTITY_CURVE.map((point) => [...point])
    }

    const sorted = curve
        .filter(
            (point) =>
                Array.isArray(point) &&
                point.length === 2 &&
                point.every((v) => typeof v === "number" && !Number.isNaN(v)),
        )
        .map(([x, y]) => [clamp(x, 0, 1), clamp(y, 0, 1)])
        .sort((a, b) => a[0] - b[0])

    const points = []
    for (const [x, y] of sorted) {
        const last = points.at(-1)
        // Spaced as the server checks them, before rounding, so no two round to one x
        if (last && x - last[0] < MIN_CURVE_SPACING - EPSILON) {
            continue
        }
        points.push([x, y])
    }

    const kept = points.slice(0, MAX_CURVE_POINTS)
    if (kept.length < MIN_CURVE_POINTS) {
        return IDENTITY_CURVE.map((point) => [...point])
    }
    return kept.map(([x, y]) => [
        rounded(x, CURVE_STEPS),
        rounded(y, CURVE_STEPS),
    ])
}

/** Whether the curve is the diagonal, which changes nothing */
export function isIdentityCurve(curve) {
    return (
        curve.length === 2 &&
        curve[0][0] === 0 &&
        curve[0][1] === 0 &&
        curve[1][0] === 1 &&
        curve[1][1] === 1
    )
}

/** Whether the Edit changes no pixel; the Crop tool's settings do not count (`EditRecipe.isIdentity`) */
export function isIdentity(recipe) {
    const edit = normalize(recipe)
    return (
        edit.rotate === 0 &&
        !edit.flipH &&
        !edit.flipV &&
        edit.crop === null &&
        Object.keys(SLIDERS).every((key) => edit[key] === 0) &&
        isIdentityCurve(edit.curve)
    )
}

/** Whether two Edits are the same once normalized, as the server compares a save with the Edit it keeps */
export function sameRecipe(a, b) {
    return JSON.stringify(normalize(a)) === JSON.stringify(normalize(b))
}

/** Whether the section's controls change a pixel: a key other than the Crop tool's settings differs from the default */
export function sectionChanged(recipe, sectionId) {
    const section = SECTIONS.find((s) => s.id === sectionId)
    const edit = normalize(recipe)
    const base = defaults()
    return section.keys
        .filter((key) => !CROP_TOOL_SETTINGS.includes(key))
        .some((key) => JSON.stringify(edit[key]) !== JSON.stringify(base[key]))
}
