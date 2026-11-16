/**
 * The Clipping of a loaded, same-origin image (a cross-origin one taints the canvas): each pixel's
 * class, blown, crushed or neither, and a mask painted from them.
 *
 * The sampling mirrors the import's measurement (`ImageMeasurement`), so the pixels marked are close
 * to the ones it counted: fitted into `fitBox` (1280 there) on the long side, never enlarged, gray by
 * OpenCV's `BGR2GRAY` weights. The downscale is `createImageBitmap`'s "high" quality, close to
 * OpenCV's `INTER_AREA` but not the same, and the bitmap is drawn converted to sRGB, so the agreement
 * is close, not to the pixel.
 *
 * A fully transparent pixel (what the Edit's render does not cover) is neither class and not counted.
 */

const NEITHER = 0
const BLOWN = 1
const CRUSHED = 2

/**
 * Answers `{ classes, width, height, blown, crushed }`: a class byte per sampled pixel, and the
 * fractions of the counted pixels in each class. Rejects a source without a size (an image not
 * loaded, or failed) rather than class a blank canvas as crushed.
 */
export async function readClipping(
    source,
    { fitBox, clippedLevel, crushedLevel },
) {
    const size = sourceSize(source)
    if (!(size.width > 0 && size.height > 0)) {
        throw new Error("readClipping needs a source with a size")
    }

    const { width, height } = sampledSize(size, fitBox)
    const bitmap = await createImageBitmap(source, {
        resizeWidth: width,
        resizeHeight: height,
        resizeQuality: "high",
    })
    const context = createCanvasContext(width, height)
    context.drawImage(bitmap, 0, 0)
    bitmap.close()
    const pixels = context.getImageData(0, 0, width, height).data

    return {
        ...classesOf(pixels, { clippedLevel, crushedLevel }),
        width,
        height,
    }
}

/** Every other pixel transparent, so the mask lies over the image at the sampled size */
export function clippingMask(
    { classes, width, height },
    { blownColor, crushedColor },
) {
    const mask = new ImageData(width, height)
    const bytesOf = {
        [BLOWN]: colorBytes(blownColor),
        [CRUSHED]: colorBytes(crushedColor),
    }

    for (let pixel = 0; pixel < classes.length; pixel++) {
        if (classes[pixel] !== NEITHER) {
            mask.data.set(bytesOf[classes[pixel]], pixel * 4)
        }
    }
    return mask
}

function sourceSize(source) {
    if ("naturalWidth" in source) {
        return { width: source.naturalWidth, height: source.naturalHeight }
    }
    if ("videoWidth" in source) {
        return { width: source.videoWidth, height: source.videoHeight }
    }
    return { width: source.width, height: source.height }
}

/** Rounds the size times the scale, as OpenCV's `resize` does */
function sampledSize({ width, height }, fitBox) {
    const scale = Math.min(1, fitBox / Math.max(width, height))
    return {
        width: Math.max(1, Math.round(width * scale)),
        height: Math.max(1, Math.round(height * scale)),
    }
}

function createCanvasContext(width, height) {
    const canvas = document.createElement("canvas")
    canvas.width = width
    canvas.height = height
    return canvas.getContext("2d", { willReadFrequently: true })
}

/** Painted onto a one-pixel canvas and read back, so the browser parses any CSS color syntax */
function colorBytes(color) {
    const context = createCanvasContext(1, 1)
    context.fillStyle = color
    context.fillRect(0, 0, 1, 1)
    return context.getImageData(0, 0, 1, 1).data
}

/** The loop touches typed arrays only: a million pixels is usual */
function classesOf(pixels, { clippedLevel, crushedLevel }) {
    const classes = new Uint8Array(pixels.length / 4)
    let counted = 0
    let blown = 0
    let crushed = 0

    for (let i = 0, pixel = 0; i < pixels.length; i += 4, pixel++) {
        if (pixels[i + 3] === 0) {
            continue
        }
        counted++
        // OpenCV's BGR2GRAY weights, applied to the canvas's RGB order
        const gray = Math.round(
            0.299 * pixels[i] + 0.587 * pixels[i + 1] + 0.114 * pixels[i + 2],
        )

        // As the measurement counts: clipped at or over 250, crushed at or under 5
        if (gray >= clippedLevel) {
            classes[pixel] = BLOWN
            blown++
        } else if (gray <= crushedLevel) {
            classes[pixel] = CRUSHED
            crushed++
        }
    }
    return {
        classes,
        blown: counted > 0 ? blown / counted : 0,
        crushed: counted > 0 ? crushed / counted : 0,
    }
}
