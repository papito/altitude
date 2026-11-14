/**
 * The Edit panel's changes to an Edit (`CONTEXT.md`), as pure functions of the Edit and the upright original's size:
 * what a control of Crop & rotate does to the crop (a quarter turn, a flip, an aspect lock, Straighten and the
 * perspective re-fitting or shrinking it, a section's reset), what the Level tool and a drag around the crop set
 * Straighten to, and the curve editor's rules on its points. DOM-free, so the tests run it under Node
 * (`scripts/test-js/edit-adjust.test.mjs`); `edit-panel.js` and the Crop tool (`crop-tool.js`) call it.
 *
 * Every function takes an Edit as `recipe.js` normalizes it and answers a new one, normalized, leaving its argument as
 * it is. A crop is stored as the server keeps it (`normalizeCrop`), and as `null` when it is the whole frame, so an Edit
 * whose crop covers the frame is the identity again.
 *
 * **Keeping the crop inside** (`keepInside`): the crop's four corners stay inside the image's outline in the frame
 * (`geometry.outline`). A crop never set by hand (`cropFitted`) re-fits to the largest rectangle at its aspect as the
 * geometry changes (`fitMaxCentered`); a crop the user set shrinks around its center only as much as it must
 * (`shrinkToValid`).
 *
 * **A quarter turn and a flip** turn or mirror the edited image as it is shown: the crop, the perspective and
 * Straighten are carried into the new frame, so nothing else of the picture moves. A mirror applied after the turn
 * reverses the turn's sense, so with one flip on, a turn to the right steps `rotate` back by 90.
 */
import {
    aspectRatio,
    clamp,
    fitMaxCentered,
    frameSize,
    isPortrait,
    outline,
    rectWithRatioAround,
    shrinkToValid,
    validRect,
    WHOLE_FRAME,
} from "./geometry.js"
import {
    defaults,
    MAX_CURVE_POINTS,
    MIN_CURVE_POINTS,
    MIN_CURVE_SPACING,
    normalize,
    normalizeCrop,
    SECTIONS,
} from "./recipe.js"

// Steps per unit of a curve coordinate (3 decimals, `recipe.js`)
const CURVE_STEPS = 1000

/** How near in x a click may be to a point of the curve and still add one: nearer, it is that point's */
export const CURVE_ADD_DISTANCE = 0.02

/** How far past the curve's box a point is dragged to be removed on release, in fractions of the box */
export const CURVE_REMOVE_MARGIN = 0.12

/** The Level tool's least line, in screen pixels: a shorter one is a click, which sets nothing */
export const LEVEL_MIN_LENGTH = 12

// A crop rounded outside the image's outline is moved in by this much of the frame on each side before it is rounded
// again, so rounding never leaves a transparent sliver along a straightened edge
const ROUNDING_INSET = 0.0001

/** The frame's size, W × H, of the upright original under the Edit */
function frameOf(recipe, upright) {
    return frameSize(upright.width, upright.height, recipe)
}

/** The image's outline in the frame when the Edit keeps its crop inside, else null: no outline binds the crop */
export function outlineIfKept(recipe, upright) {
    if (!recipe.keepInside) {
        return null
    }
    const { width, height } = frameOf(recipe, upright)
    return outline(width, height, recipe)
}

function isWholeFrame(rect) {
    return rect.x === 0 && rect.y === 0 && rect.w === 1 && rect.h === 1
}

/**
 * The crop rectangle as the Edit stores it: rounded as the server rounds it, moved in a hair when rounding took a
 * corner past the outline `quad`, and null when it is the whole frame
 */
export function storedCrop(rect, quad = null) {
    let crop = normalizeCrop(rect)
    if (quad && !validRect(crop, quad)) {
        crop = normalizeCrop({
            x: rect.x + ROUNDING_INSET,
            y: rect.y + ROUNDING_INSET,
            w: rect.w - 2 * ROUNDING_INSET,
            h: rect.h - 2 * ROUNDING_INSET,
        })
    }
    return isWholeFrame(crop) ? null : crop
}

/** The Edit's crop rectangle, the whole frame when it has none */
export function cropRect(recipe) {
    return recipe.crop ?? WHOLE_FRAME
}

/**
 * The crop kept true to the geometry: a fitted crop re-fits to the largest rectangle at the aspect, centered; a crop
 * the user set shrinks around its center only as much as it must to stay inside the image (when the Edit keeps it
 * inside)
 */
export function refit(recipe, upright) {
    const edit = normalize(recipe)
    const { width, height } = frameOf(edit, upright)
    const quad = outlineIfKept(edit, upright)
    const rect = edit.cropFitted
        ? fitMaxCentered(aspectRatio(edit, width, height), width, height, quad)
        : shrinkToValid(cropRect(edit), quad)
    return normalize({ ...edit, crop: storedCrop(rect, quad) })
}

/**
 * A slider set to `value`: Straighten and the perspective move the image's outline, so the crop is kept true to it
 * (`refit`)
 */
export function withSlider(recipe, key, value, upright) {
    const edit = normalize({ ...normalize(recipe), [key]: value })
    return ["straighten", "perspectiveV", "perspectiveH"].includes(key)
        ? refit(edit, upright)
        : edit
}

/**
 * A drag of a slider (a range's, or the Crop tool's turn of Straighten), as a function of the value reached: each value is
 * applied to the Edit the drag started from, so a crop the user set that shrank on the way grows back as the drag returns,
 * rather than shrinking step by step
 */
export function sliderDrag(start, key, upright) {
    const edit = normalize(start)
    return (value) => withSlider(edit, key, value, upright)
}

/** The tone curve set to `points` */
export function withCurve(recipe, points) {
    return normalize({ ...normalize(recipe), curve: points })
}

/**
 * The aspect lock set to `aspect`: a fitted crop re-fits to it; a crop the user set takes its ratio around its own
 * center with its own area, kept inside; Free leaves a set crop as it is
 */
export function withAspect(recipe, aspect, upright) {
    const edit = normalize({ ...normalize(recipe), aspect })
    return reshapedToAspect(edit, upright)
}

/** A crop the lock's ratio, after the lock changed */
function reshapedToAspect(edit, upright) {
    if (edit.cropFitted) {
        return refit(edit, upright)
    }
    const { width, height } = frameOf(edit, upright)
    const ratio = aspectRatio(edit, width, height)
    if (ratio === null) {
        return edit
    }
    const quad = outlineIfKept(edit, upright)
    const rect = rectWithRatioAround(cropRect(edit), ratio, width, height, quad)
    return normalize({ ...edit, crop: storedCrop(rect, quad) })
}

/**
 * The lock's orientation swapped (X): the ratio turns portrait or landscape. With Free, a set crop's width and height
 * in pixels trade places around its center, kept inside.
 */
export function swappedOrientation(recipe, upright) {
    const base = normalize(recipe)
    const { width, height } = frameOf(base, upright)
    const edit = normalize({
        ...base,
        portrait: !isPortrait(base, width, height),
    })
    if (edit.aspect !== "free" || edit.cropFitted) {
        return reshapedToAspect(edit, upright)
    }

    const crop = cropRect(edit)
    const widthPx = crop.h * height
    const heightPx = crop.w * width
    const quad = outlineIfKept(edit, upright)
    const rect = rectWithRatioAround(
        { ...crop, w: widthPx / width, h: heightPx / height },
        widthPx / heightPx,
        width,
        height,
        quad,
    )
    return normalize({ ...edit, crop: storedCrop(rect, quad) })
}

/**
 * A quarter turn of the edited image as it is shown, right (`direction` 1, clockwise) or left (−1): the crop turns with
 * the frame, the perspective trades axes, Straighten stays, and a set lock orientation flips. One flip on reverses the
 * turn's sense in the frame (a mirror after a turn), so `rotate` steps the other way.
 */
export function rotated(recipe, direction, upright) {
    const edit = normalize(recipe)
    const oneFlip = edit.flipH !== edit.flipV
    const step = direction > 0 !== oneFlip ? 90 : 270
    const c = edit.crop
    const crop =
        c === null
            ? null
            : direction > 0
              ? { x: 1 - c.y - c.h, y: c.x, w: c.h, h: c.w }
              : { x: c.y, y: 1 - c.x - c.w, w: c.h, h: c.w }
    // The keystone in the turned frame: a clockwise turn sends the vertical's to the horizontal's, reversed
    const perspective =
        direction > 0
            ? {
                  perspectiveH: -edit.perspectiveV,
                  perspectiveV: edit.perspectiveH,
              }
            : {
                  perspectiveH: edit.perspectiveV,
                  perspectiveV: -edit.perspectiveH,
              }
    return refit(
        {
            ...edit,
            ...perspective,
            rotate: (edit.rotate + step) % 360,
            crop,
            portrait: edit.portrait === null ? null : !edit.portrait,
        },
        upright,
    )
}

/**
 * The edited image mirrored as it is shown, left to right (`axis` "h") or top to bottom ("v"): the crop mirrors with it,
 * and Straighten and that axis's keystone change sign
 */
export function flipped(recipe, axis, upright) {
    const edit = normalize(recipe)
    const c = edit.crop
    const changes =
        axis === "h"
            ? {
                  flipH: !edit.flipH,
                  perspectiveH: -edit.perspectiveH,
                  crop: c && { ...c, x: 1 - c.x - c.w },
              }
            : {
                  flipV: !edit.flipV,
                  perspectiveV: -edit.perspectiveV,
                  crop: c && { ...c, y: 1 - c.y - c.h },
              }
    return refit({ ...edit, ...changes, straighten: -edit.straighten }, upright)
}

/** "Keep the crop inside the image" switched: on, the crop is brought inside */
export function withKeepInside(recipe, keepInside, upright) {
    return refit({ ...normalize(recipe), keepInside }, upright)
}

/** A crop the user set (the Crop tool): no longer fitted, as the Edit stores it */
export function withCrop(recipe, rect, upright) {
    const edit = normalize(recipe)
    return normalize({
        ...edit,
        cropFitted: false,
        crop: storedCrop(rect, outlineIfKept(edit, upright)),
    })
}

/**
 * A section back to its defaults: its sliders at 0, Light's curve the diagonal, and for Crop & rotate no turn, flip,
 * Straighten, perspective or crop, the lock back to Original and fitted; whether the crop is kept inside stays, being
 * a preference rather than a change of the picture
 */
export function resetSection(recipe, sectionId, upright) {
    const edit = normalize(recipe)
    const base = defaults()
    const section = SECTIONS.find((s) => s.id === sectionId)
    const reset = { ...edit }
    for (const key of section.keys) {
        reset[key] = base[key]
    }
    reset.keepInside = edit.keepInside
    return sectionId === "cropRotate" ? refit(reset, upright) : normalize(reset)
}

/** Every section back to its defaults (the header's Reset all), the crop kept inside as it was */
export function resetAll(recipe, upright) {
    return SECTIONS.reduce(
        (edit, section) => resetSection(edit, section.id, upright),
        normalize(recipe),
    )
}

/**
 * The Straighten that makes a line drawn over the shown frame (the Level tool) level or plumb, whichever it is nearer:
 * `dx`, `dy` the line's run in screen pixels, y down. Null for a line shorter than `LEVEL_MIN_LENGTH`.
 */
export function levelStraighten(straighten, dx, dy) {
    if (Math.hypot(dx, dy) < LEVEL_MIN_LENGTH) {
        return null
    }
    let angle = (Math.atan2(dy, dx) * 180) / Math.PI
    if (angle > 90) {
        angle -= 180
    } else if (angle <= -90) {
        angle += 180
    }
    if (Math.abs(angle) > 45) {
        angle = angle > 0 ? angle - 90 : angle + 90
    }
    return clamp(straighten - angle, -45, 45)
}

/**
 * The Straighten a drag around the crop's center sets: the one at its start turned by the change of the pointer's
 * angle around the center (both in screen pixels, y down, so clockwise is positive), within the slider's range
 */
export function draggedStraighten(startStraighten, center, from, to) {
    const before = Math.atan2(from.y - center.y, from.x - center.x)
    const after = Math.atan2(to.y - center.y, to.x - center.x)
    let delta = ((after - before) * 180) / Math.PI
    if (delta > 180) {
        delta -= 360
    } else if (delta < -180) {
        delta += 360
    }
    return clamp(startStraighten + delta, -45, 45)
}

function curveRounded(value) {
    return Math.round(value * CURVE_STEPS) / CURVE_STEPS
}

/**
 * A point added to the curve at (x, y), in [0, 1]: the curve with it in x order and its index, or null when the curve
 * holds its most points or a point lies within `CURVE_ADD_DISTANCE` in x (a click there takes that point instead)
 */
export function addCurvePoint(curve, x, y) {
    const px = curveRounded(clamp(x))
    const py = curveRounded(clamp(y))
    if (
        curve.length >= MAX_CURVE_POINTS ||
        curve.some(([cx]) => Math.abs(cx - px) < CURVE_ADD_DISTANCE)
    ) {
        return null
    }
    const points = [...curve.map((p) => [...p]), [px, py]].sort(
        (a, b) => a[0] - b[0],
    )
    return { curve: points, index: points.findIndex(([cx]) => cx === px) }
}

/**
 * The curve with its point `index` dragged to (x, y): kept in its order, at least `MIN_CURVE_SPACING` from each
 * neighbor in x, in [0, 1] and on the curve's steps; the first and the last point keep their x
 */
export function moveCurvePoint(curve, index, x, y) {
    const last = curve.length - 1
    const points = curve.map((p) => [...p])
    const ends = index === 0 || index === last
    const lo = index === 0 ? 0 : points[index - 1][0] + MIN_CURVE_SPACING
    const hi = index === last ? 1 : points[index + 1][0] - MIN_CURVE_SPACING
    points[index] = [
        ends ? points[index][0] : curveRounded(clamp(x, lo, hi)),
        curveRounded(clamp(y)),
    ]
    return points
}

/** Whether the point `index` may be removed: an inner point, while the curve keeps at least its fewest points */
export function isRemovableCurvePoint(curve, index) {
    return (
        index > 0 && index < curve.length - 1 && curve.length > MIN_CURVE_POINTS
    )
}

/** The curve without its point `index`, when it may be removed; the curve as it is otherwise */
export function removeCurvePoint(curve, index) {
    return isRemovableCurvePoint(curve, index)
        ? curve.filter((_, i) => i !== index).map((p) => [...p])
        : curve.map((p) => [...p])
}

/** Whether (x, y), in fractions of the curve's box, lies far enough past it that a point dragged there goes */
export function isOffCurveBox(x, y) {
    return (
        x < -CURVE_REMOVE_MARGIN ||
        x > 1 + CURVE_REMOVE_MARGIN ||
        y < -CURVE_REMOVE_MARGIN ||
        y > 1 + CURVE_REMOVE_MARGIN
    )
}

/** An aspect chip's label, "Original", "Free", "3:2" or "2:3", by the lock's orientation */
export function aspectLabel(aspect, portrait) {
    if (aspect === "original") {
        return "Original"
    }
    if (aspect === "free") {
        return "Free"
    }
    const [long, short] = aspect.split(":")
    return portrait ? `${short}:${long}` : `${long}:${short}`
}
