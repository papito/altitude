/**
 * The Edit's geometry, as the server's renderer has it (`util/EditRenderer.scala`), and the Crop
 * tool's rules on top of it. DOM-free, so the parity tests run it under Node.
 *
 * Coordinates are continuous pixels: (0, 0) top left, y down, a pixel's center at +0.5; angles are
 * positive clockwise. The **upright original** (W₀ × H₀) is the original turned by its EXIF
 * orientation. Step 1 turns it by `rotate` and mirrors it into the **frame** (W × H); step 2 turns
 * the frame by Straighten about its center, keeping its size; step 3 crops it to the **edited
 * size**. A crop is `{x, y, w, h}` in fractions of the frame; the image's outline is a quadrilateral
 * of fractions, clockwise from the top left.
 */

/** The narrowest and shortest crop, as a fraction of the frame (`EditRecipe.MIN_CROP`) */
export const MIN_CROP = 0.01

// How far a crop may stray past the frame's edges in floating point and still count as inside
const EPSILON = 1e-6

export const WHOLE_FRAME = Object.freeze({ x: 0, y: 0, w: 1, h: 1 })

export function clamp(value, min = 0, max = 1) {
    return value < min ? min : value > max ? max : value
}

export function cropOf(recipe) {
    return recipe.crop ?? WHOLE_FRAME
}

/**
 * The region of the output, in output pixels, that the effects (blurs, vignette, grain) are sized from and that
 * `readHistogram` reads: the whole output, or the crop in it when drawing the whole frame (the Crop tool), so the effects
 * inside the crop match the final render
 */
export function effectRegion(recipe, { width, height, wholeFrame = false }) {
    if (!wholeFrame) {
        return { x: 0, y: 0, width, height }
    }
    const crop = cropOf(recipe)
    const x = Math.min(width - 1, Math.round(crop.x * width))
    const y = Math.min(height - 1, Math.round(crop.y * height))
    return {
        x,
        y,
        width: Math.max(1, Math.min(width - x, Math.round(crop.w * width))),
        height: Math.max(1, Math.min(height - y, Math.round(crop.h * height))),
    }
}

/** The frame's size: the upright original's, swapped for a quarter turn */
export function frameSize(uprightWidth, uprightHeight, recipe) {
    return recipe.rotate === 90 || recipe.rotate === 270
        ? { width: uprightHeight, height: uprightWidth }
        : { width: uprightWidth, height: uprightHeight }
}

/** Step 1 for a point of the upright original: turned clockwise by `rotate`, then mirrored in the turned frame */
export function turn(x, y, uprightWidth, uprightHeight, recipe) {
    let tx = x
    let ty = y
    if (recipe.rotate === 90) {
        tx = uprightHeight - y
        ty = x
    } else if (recipe.rotate === 180) {
        tx = uprightWidth - x
        ty = uprightHeight - y
    } else if (recipe.rotate === 270) {
        tx = y
        ty = uprightWidth - x
    }
    const { width, height } = frameSize(uprightWidth, uprightHeight, recipe)
    return [recipe.flipH ? width - tx : tx, recipe.flipV ? height - ty : ty]
}

/** The inverse of `turn`: a point of the frame back in the upright original */
export function turnBack(x, y, uprightWidth, uprightHeight, recipe) {
    const { width, height } = frameSize(uprightWidth, uprightHeight, recipe)
    const tx = recipe.flipH ? width - x : x
    const ty = recipe.flipV ? height - y : y
    if (recipe.rotate === 90) {
        return [ty, uprightHeight - tx]
    }
    if (recipe.rotate === 180) {
        return [uprightWidth - tx, uprightHeight - ty]
    }
    if (recipe.rotate === 270) {
        return [uprightWidth - ty, tx]
    }
    return [tx, ty]
}

/**
 * `turnBack` as an affine map from frame pixels to a texture coordinate of the upright original:
 * `[a, b, c, d, e, f]` maps (x, y) to (a·x + b·y + c, d·x + e·y + f)
 */
export function frameToUpright(uprightWidth, uprightHeight, recipe) {
    const [ox, oy] = turnBack(0, 0, uprightWidth, uprightHeight, recipe)
    const [xx, xy] = turnBack(1, 0, uprightWidth, uprightHeight, recipe)
    const [yx, yy] = turnBack(0, 1, uprightWidth, uprightHeight, recipe)
    return [
        (xx - ox) / uprightWidth,
        (yx - ox) / uprightWidth,
        ox / uprightWidth,
        (xy - oy) / uprightHeight,
        (yy - oy) / uprightHeight,
        oy / uprightHeight,
    ]
}

/**
 * Step 2 on the W × H frame, its constants worked out once: around the center, normalized by half
 * the long side (u = (p − c) / s), the point is turned R(θ)·u and scaled back; `inverse`, which
 * sampling reads, is R(−θ). `covers` says whether a warped point's source lies within the frame,
 * edges included: the rendered image is opaque exactly there.
 */
export function createWarp(width, height, recipe) {
    const s = Math.max(width, height) / 2
    const theta = (recipe.straighten * Math.PI) / 180
    const cos = Math.cos(theta)
    const sin = Math.sin(theta)

    function forward(x, y) {
        const ux = (x - width / 2) / s
        const uy = (y - height / 2) / s
        return [
            (cos * ux - sin * uy) * s + width / 2,
            (sin * ux + cos * uy) * s + height / 2,
        ]
    }

    function inverse(x, y) {
        const qx = (x - width / 2) / s
        const qy = (y - height / 2) / s
        return [
            (cos * qx + sin * qy) * s + width / 2,
            (-sin * qx + cos * qy) * s + height / 2,
        ]
    }

    function covers(x, y) {
        const [sx, sy] = inverse(x, y)
        return sx >= 0 && sx <= width && sy >= 0 && sy <= height
    }

    return {
        moves: recipe.straighten !== 0,
        forward,
        inverse,
        covers,
        cos,
        sin,
    }
}

export function warp(x, y, width, height, recipe) {
    return createWarp(width, height, recipe).forward(x, y)
}

export function unwarp(x, y, width, height, recipe) {
    return createWarp(width, height, recipe).inverse(x, y)
}

/** The image's outline in the frame: the turned image's corners through `warp`, as fractions of the frame */
export function outline(width, height, recipe) {
    const { forward } = createWarp(width, height, recipe)
    return [
        [0, 0],
        [width, 0],
        [width, height],
        [0, height],
    ].map(([x, y]) => {
        const [wx, wy] = forward(x, y)
        return [wx / width, wy / height]
    })
}

/**
 * Whether the point lies inside the convex quadrilateral `quad`, edges included: the cross product
 * has the same sign along every edge. `width` and `height` scale the fractions first; every caller
 * leaves them at 1.
 */
export function insideQuad(quad, x, y, width = 1, height = 1) {
    let sign = 0
    for (let i = 0; i < 4; i++) {
        const [x1, y1] = quad[i]
        const [x2, y2] = quad[(i + 1) % 4]
        const cross =
            (x2 - x1) * width * ((y - y1) * height) -
            (y2 - y1) * height * ((x - x1) * width)
        if (Math.abs(cross) < 1e-9) {
            continue
        }
        const s = Math.sign(cross)
        if (sign === 0) {
            sign = s
        } else if (s !== sign) {
            return false
        }
    }
    return true
}

/**
 * Whether the Edit may hold the crop: at least `MIN_CROP`, within the frame, and, given `quad`, its
 * corners inside the image's outline
 */
export function validRect(rect, quad = null) {
    if (rect.w < MIN_CROP || rect.h < MIN_CROP) {
        return false
    }
    if (
        rect.x < -EPSILON ||
        rect.y < -EPSILON ||
        rect.x + rect.w > 1 + EPSILON ||
        rect.y + rect.h > 1 + EPSILON
    ) {
        return false
    }
    if (!quad) {
        return true
    }
    return (
        insideQuad(quad, rect.x, rect.y) &&
        insideQuad(quad, rect.x + rect.w, rect.y) &&
        insideQuad(quad, rect.x + rect.w, rect.y + rect.h) &&
        insideQuad(quad, rect.x, rect.y + rect.h)
    )
}

function lerpRect(from, to, t) {
    return {
        x: from.x + (to.x - from.x) * t,
        y: from.y + (to.y - from.y) * t,
        w: from.w + (to.w - from.w) * t,
        h: from.h + (to.h - from.h) * t,
    }
}

/** How far a move or resize towards `to` may go: `to` when valid, else the furthest valid rectangle on the way (by bisection) */
export function limitTransition(from, to, quad = null) {
    if (validRect(to, quad)) {
        return to
    }
    if (!validRect(from, quad)) {
        return from
    }
    let lo = 0
    let hi = 1
    for (let i = 0; i < 14; i++) {
        const mid = (lo + hi) / 2
        if (validRect(lerpRect(from, to, mid), quad)) {
            lo = mid
        } else {
            hi = mid
        }
    }
    return lerpRect(from, to, lo)
}

function scaleAbout(rect, cx, cy, scale) {
    return {
        x: cx - (cx - rect.x) * scale,
        y: cy - (cy - rect.y) * scale,
        w: rect.w * scale,
        h: rect.h * scale,
    }
}

/**
 * The rectangle shrunk around its center only as much as it must be to be valid; around the
 * frame's center when its own center lies outside the image
 */
export function shrinkToValid(rect, quad = null) {
    if (validRect(rect, quad)) {
        return rect
    }
    let cx = rect.x + rect.w / 2
    let cy = rect.y + rect.h / 2
    if (!validRect(scaleAbout(rect, cx, cy, 0.02), quad)) {
        cx = 0.5
        cy = 0.5
    }
    const base = {
        x: cx - rect.w / 2,
        y: cy - rect.h / 2,
        w: rect.w,
        h: rect.h,
    }
    let lo = 0.01
    let hi = 1
    for (let i = 0; i < 18; i++) {
        const mid = (lo + hi) / 2
        if (validRect(scaleAbout(base, cx, cy, mid), quad)) {
            lo = mid
        } else {
            hi = mid
        }
    }
    return scaleAbout(base, cx, cy, lo)
}

/** Whether the Crop tool's lock stands portrait: its `portrait`, or the frame's own orientation when that is null */
export function isPortrait(recipe, width, height) {
    return recipe.portrait ?? height > width
}

/**
 * The crop's pixel width over height that the aspect lock asks for, or null when Free; Original is
 * the frame's own, inverted when the lock's orientation is not the frame's
 */
export function aspectRatio(recipe, width, height) {
    if (recipe.aspect === "free") {
        return null
    }
    const portrait = isPortrait(recipe, width, height)
    if (recipe.aspect === "original") {
        const ratio = width / height
        return portrait !== height > width ? 1 / ratio : ratio
    }
    const [long, short] = recipe.aspect.split(":").map(Number)
    return portrait ? short / long : long / short
}

/** A pixel ratio as a ratio of frame fractions */
function fractionRatio(ratio, width, height) {
    return (ratio * height) / width
}

/** The largest valid crop of the pixel `ratio` (the frame's own when null) centered on the frame */
export function fitMaxCentered(ratio, width, height, quad = null) {
    const k = fractionRatio(ratio ?? width / height, width, height)
    let w = 1
    let h = 1 / k
    if (h > 1) {
        h = 1
        w = k
    }
    return shrinkToValid({ x: 0.5 - w / 2, y: 0.5 - h / 2, w, h }, quad)
}

/** A crop of the pixel `ratio` with the area of `rect` around its center, kept in the frame and valid */
export function rectWithRatioAround(rect, ratio, width, height, quad = null) {
    const cx = rect.x + rect.w / 2
    const cy = rect.y + rect.h / 2
    const area = rect.w * rect.h
    const k = fractionRatio(ratio, width, height)
    let w = Math.sqrt(area * k)
    let h = Math.sqrt(area / k)
    if (w > 1) {
        w = 1
        h = w / k
    }
    if (h > 1) {
        h = 1
        w = h * k
    }
    return shrinkToValid(
        {
            x: clamp(cx - w / 2, 0, 1 - w),
            y: clamp(cy - h / 2, 0, 1 - h),
            w,
            h,
        },
        quad,
    )
}

/** The edited size: the crop's share of the frame, rounded half up, at least one pixel (`EditRecipe.editedSize`) */
export function editedSize(uprightWidth, uprightHeight, recipe) {
    const frame = frameSize(uprightWidth, uprightHeight, recipe)
    if (!recipe.crop) {
        return frame
    }
    return {
        width: Math.max(1, Math.round(recipe.crop.w * frame.width)),
        height: Math.max(1, Math.round(recipe.crop.h * frame.height)),
    }
}

/** A render's size: the edited size, its long edge brought down to `longEdge` when that is shorter */
export function outputSize(editedWidth, editedHeight, longEdge = null) {
    const long = Math.max(editedWidth, editedHeight)
    if (longEdge === null || longEdge >= long) {
        return { width: editedWidth, height: editedHeight }
    }
    const short = Math.max(
        1,
        Math.round((Math.min(editedWidth, editedHeight) * longEdge) / long),
    )
    return editedWidth >= editedHeight
        ? { width: longEdge, height: short }
        : { width: short, height: longEdge }
}

/**
 * A box of the upright original (a Face's, in pixels) mapped into the edited image: the bounding box
 * of its mapped corners, in fractions of the edited image and cut to it, or null when the box's
 * center falls outside the crop
 */
export function mapBox(box, recipe, uprightWidth, uprightHeight) {
    const { width, height } = frameSize(uprightWidth, uprightHeight, recipe)
    const { forward } = createWarp(width, height, recipe)
    const crop = cropOf(recipe)

    function toEdited(x, y) {
        const [tx, ty] = turn(x, y, uprightWidth, uprightHeight, recipe)
        const [wx, wy] = forward(tx, ty)
        return [(wx / width - crop.x) / crop.w, (wy / height - crop.y) / crop.h]
    }

    const center = toEdited(box.x + box.width / 2, box.y + box.height / 2)
    if (center[0] < 0 || center[0] > 1 || center[1] < 0 || center[1] > 1) {
        return null
    }

    const corners = [
        toEdited(box.x, box.y),
        toEdited(box.x + box.width, box.y),
        toEdited(box.x + box.width, box.y + box.height),
        toEdited(box.x, box.y + box.height),
    ]
    const xs = corners.map(([x]) => clamp(x, 0, 1))
    const ys = corners.map(([, y]) => clamp(y, 0, 1))
    const left = Math.min(...xs)
    const top = Math.min(...ys)
    return {
        x: left,
        y: top,
        width: Math.max(...xs) - left,
        height: Math.max(...ys) - top,
    }
}
