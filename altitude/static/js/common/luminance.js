/**
 * The luminance of an image the browser has already loaded: a Histogram of its gray levels and a
 * Clipping mask of its blown highlights and crushed shadows, read from the image's own pixels in
 * one pass, so nothing is fetched and no server column holds them. It knows nothing of the page it
 * serves: `readLuminance` takes an `HTMLImageElement` and answers numbers and a class map, and
 * `clippingMask` paints that map into an `ImageData` for a canvas to draw.
 *
 * The fit: the image is sampled at a size fitted to `fitBox` on its long side and never enlarged,
 * the way the import's measurement fits an image into its box (1280,
 * `ImageMeasurement.MEASUREMENT_BOX_SIZE`) before it counts, so the fractions read here agree with
 * the clipped and crushed fractions stored at import. The sampled size rounds the natural size
 * times the scale, as OpenCV's `resize` does. The decode and the downscale are
 * `createImageBitmap`'s, off the main thread, at `resizeQuality: "high"`, an area average close to
 * OpenCV's `INTER_AREA` but not the same one; and the bitmap is drawn converted to sRGB, so an
 * ICC-tagged original can differ a little more. The agreement is close, not to the pixel.
 *
 * The gray: each pixel's gray is OpenCV's `BGR2GRAY`, `0.299 R + 0.587 G + 0.114 B` rounded to a
 * level of 0 to 255; the source's alpha is ignored, the originals being photos.
 *
 * The bins: 64, a bin four levels wide (the gray over 4, floored), each the fraction of the pixels
 * that fell in it, so the 64 sum to 1 whatever the sampled size.
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
 * `getImageData` throws. The callers give the originals from the content route, which are.
 */

/** The gray levels, 256, over the width of a bin, 4 */
const BIN_COUNT = 64

/** A pixel's class in a read's `classes` */
const NEITHER = 0
const BLOWN = 1
const CRUSHED = 2

/**
 * Reads the Histogram bins and the Clipping classes of `imageEl`, a loaded same-origin image.
 * Answers `{ bins, classes, width, height }`: `bins` the 64 fractions, `classes` a `Uint8Array` of
 * a byte per pixel of the sampled `width` by `height`. Rejects on an image without a natural size
 * (not loaded, or failed) rather than count a blank canvas as black
 */
export async function readLuminance(
    imageEl,
    { fitBox, clippedLevel, crushedLevel },
) {
    if (!(imageEl.naturalWidth > 0 && imageEl.naturalHeight > 0)) {
        throw new Error(
            "readLuminance needs an image loaded with a natural size",
        )
    }

    const { width, height } = sampledSize(imageEl, fitBox)
    const bitmap = await createImageBitmap(imageEl, {
        resizeWidth: width,
        resizeHeight: height,
        resizeQuality: "high",
    })
    const context = createCanvasContext(width, height)
    context.drawImage(bitmap, 0, 0)
    bitmap.close()
    const pixels = context.getImageData(0, 0, width, height).data

    const classes = new Uint8Array(width * height)
    const bins = countLevels(pixels, classes, { clippedLevel, crushedLevel })

    return { bins, classes, width, height }
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

/** The size the image is sampled at: fitted to `fitBox` on its long side, never enlarged, at least a pixel each way */
function sampledSize({ naturalWidth, naturalHeight }, fitBox) {
    const scale = Math.min(1, fitBox / Math.max(naturalWidth, naturalHeight))
    return {
        width: Math.max(1, Math.round(naturalWidth * scale)),
        height: Math.max(1, Math.round(naturalHeight * scale)),
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
 * The one pass over `pixels`, RGBA bytes: counts each pixel's gray into its bin and writes its
 * class into `classes`, a byte per pixel that starts `NEITHER`. Answers the bins as fractions of
 * the pixels. The loop touches typed arrays only, a million pixels being usual
 */
function countLevels(pixels, classes, { clippedLevel, crushedLevel }) {
    const counts = new Uint32Array(BIN_COUNT)

    for (let i = 0, pixel = 0; i < pixels.length; i += 4, pixel++) {
        // OpenCV's BGR2GRAY weights, applied to the canvas's RGB order
        const gray = Math.round(
            0.299 * pixels[i] + 0.587 * pixels[i + 1] + 0.114 * pixels[i + 2],
        )
        counts[gray >> 2]++

        // The measurement counts clipped as `fractionAbove(gray, CLIPPED_LEVEL - 1)` and crushed
        // as `fractionAtMost(gray, CRUSHED_LEVEL)`: at or over 250, at or under 5
        if (gray >= clippedLevel) {
            classes[pixel] = BLOWN
        } else if (gray <= crushedLevel) {
            classes[pixel] = CRUSHED
        }
    }

    const total = pixels.length / 4
    return Array.from(counts, (count) => count / total)
}
