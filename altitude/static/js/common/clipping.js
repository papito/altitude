/**
 * The clipping of an image the browser has already loaded: the class of each of its pixels, a blown
 * highlight, a crushed shadow or neither, read from the image's own pixels, and a Clipping mask
 * painted from them. It knows nothing of the page it serves: `readClipping` takes any
 * `CanvasImageSource` with a size (a loaded `<img>`: Curation's stage panes; a canvas: the Edit
 * panel's live render, `edit/edit-panel.js`) and answers a class map with the fractions of each
 * class, and `clippingMask` paints that map into an `ImageData` for a canvas to draw.
 *
 * The fit: the image is sampled at a size fitted to `fitBox` on its long side and never enlarged,
 * the way the import's measurement fits an image into its box (1280,
 * `ImageMeasurement.MEASUREMENT_BOX_SIZE`) before it counts, so the pixels marked here are close to
 * the ones the clipped and crushed fractions stored at import counted. The sampled size rounds the
 * natural size times the scale, as OpenCV's `resize` does. The decode and the downscale are
 * `createImageBitmap`'s, off the main thread, at `resizeQuality: "high"`, an area average close to
 * OpenCV's `INTER_AREA` but not the same one; and the bitmap is drawn converted to sRGB, so an
 * ICC-tagged original can differ a little more. The agreement is close, not to the pixel.
 *
 * The gray: each pixel's gray is OpenCV's `BGR2GRAY`, `0.299 R + 0.587 G + 0.114 B` rounded to a
 * level of 0 to 255. A fully transparent pixel is no pixel of the image (the Edit's render leaves
 * what the image does not cover transparent): it is neither class and is not counted; an original,
 * being a photo, has none.
 *
 * The classes: a byte per sampled pixel, `BLOWN` at or over `clippedLevel`, `CRUSHED` at or under
 * `crushedLevel`, else `NEITHER`, so a read keeps a byte per pixel and the mask is painted only
 * when it is drawn. The mask is an `ImageData` of the sampled size, a blown pixel in `blownColor`,
 * a crushed one in `crushedColor` and every other fully transparent, so it lies over the image at
 * the same size and marks what the measurement counted. The two colors are CSS color strings as
 * `getComputedStyle` answers them (`rgb()`, `rgba()` or `color(srgb ...)`): each is painted onto a
 * one-pixel canvas and read back as bytes, so every syntax the browser parses is taken, at the
 * color's own alpha.
 *
 * The image must be same-origin: drawing a cross-origin image taints the canvas and
 * `getImageData` throws. The callers give the originals from the content route, which are, and the
 * Edit's canvas, drawn from them.
 */

/** A pixel's class in a read's `classes` */
const NEITHER = 0
const BLOWN = 1
const CRUSHED = 2

/**
 * Reads the Clipping classes of `source`, a same-origin `CanvasImageSource`: a loaded image, or a
 * canvas drawn. Answers `{ classes, width, height, blown, crushed }`: `classes` a `Uint8Array` of a
 * byte per pixel of the sampled `width` by `height`, and `blown` and `crushed` the fractions of the
 * image's pixels (the ones not fully transparent) in each class, 0 for an image with none. Rejects
 * on a source without a size (an image not loaded, or failed) rather than class a blank canvas as
 * crushed
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

/**
 * The Clipping mask of a read, an `ImageData` of its `width` by `height` with its blown pixels in
 * `blownColor`, its crushed ones in `crushedColor` and every other transparent
 */
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

/** The source's own size: an image's natural size, a video's, or a canvas's or bitmap's */
function sourceSize(source) {
    if ("naturalWidth" in source) {
        return { width: source.naturalWidth, height: source.naturalHeight }
    }
    if ("videoWidth" in source) {
        return { width: source.videoWidth, height: source.videoHeight }
    }
    return { width: source.width, height: source.height }
}

/** The size the source is sampled at: fitted to `fitBox` on its long side, never enlarged, at least a pixel each way */
function sampledSize({ width, height }, fitBox) {
    const scale = Math.min(1, fitBox / Math.max(width, height))
    return {
        width: Math.max(1, Math.round(width * scale)),
        height: Math.max(1, Math.round(height * scale)),
    }
}

/** A 2D context of a fresh canvas of `width` by `height`, set up for reading its pixels back */
function createCanvasContext(width, height) {
    const canvas = document.createElement("canvas")
    canvas.width = width
    canvas.height = height
    return canvas.getContext("2d", { willReadFrequently: true })
}

/**
 * `color`, in any CSS color syntax, as its `[r, g, b, a]` bytes: painted onto a one-pixel canvas
 * and read back, so the browser does the parsing
 */
function colorBytes(color) {
    const context = createCanvasContext(1, 1)
    context.fillStyle = color
    context.fillRect(0, 0, 1, 1)
    return context.getImageData(0, 0, 1, 1).data
}

/**
 * The one pass over `pixels`, RGBA bytes: each pixel's class by its gray, a byte per pixel that
 * starts `NEITHER`, and the fractions of the counted pixels, the ones not fully transparent, that
 * are blown and crushed. The loop touches typed arrays only, a million pixels being usual
 */
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

        // The measurement counts clipped as `fractionAbove(gray, CLIPPED_LEVEL - 1)` and crushed
        // as `fractionAtMost(gray, CRUSHED_LEVEL)`: at or over 250, at or under 5
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
