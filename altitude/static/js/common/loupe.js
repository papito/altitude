import { Const } from "../constants.js"

/**
 * The Loupe: a lens that rides with the pointer over an image shown `object-fit: contain` in a
 * pane and shows the spot under it at the original's pixels, or a multiple of them, so sharpness,
 * noise and eyes are judged on the file and not on a pane that shrinks the original. It knows
 * nothing of the page it is on: `attachLoupe` takes panes, each `{ paneEl, imageEl, lensEl }`,
 * where the pane takes the pointer's events and the lens is an element positioned inside it that
 * the stylesheet sizes, and writes the lens's inline styles, its `hidden` attribute and
 * `Const.attributes.loupeLevel`, the level's text ("100%", "200%", "400%") for the stylesheet to
 * show, and nothing else.
 *
 * Pointer to pixel: the image's `getBoundingClientRect()` includes any CSS transform on it, and the
 * painted box under `contain` sits centered in that rect at `fit`, the lesser of the rect's sides
 * over the natural size; the pointer's pixel is its offset from the box's origin over `fit`. So a
 * transform that zooms the image changes nothing, and a level is always the original's: at 1 one
 * original pixel is one screen pixel whatever the pane shows. Over the letterbox bars, where the
 * pixel falls outside the original, every lens hides.
 *
 * The lens draws with `background-image` of the image's `currentSrc`, which the browser has
 * already decoded, so nothing is fetched; `background-size` is the natural size times the level and
 * `background-position` puts the pixel under the pointer at the lens's center. Above 1 it is
 * `image-rendering: pixelated`: each pixel a crisp block, so blur and noise are the file's and not
 * the browser's smoothing.
 *
 * Levels: `levels` is the ladder, ascending, each the screen pixels an original pixel takes, as
 * `fit` is. A frame the pane already shows at or above the chosen level, a small original or a
 * zoomed one, is shown at the first rung above its `fit`, and at the top rung when none is: a lens
 * that shows what the pane shows is no lens. The wheel over a pane steps from the level shown, up
 * on a negative `deltaY`, so every step changes what the lens shows, and `saveLevel` remembers the
 * rung; `readLevel` answers it when the Loupe attaches, or anything not in the ladder for the first
 * rung. A wheel with a modifier held is the browser's (Ctrl zooms the page), and one with nothing
 * under the pointer to magnify steps nothing.
 *
 * One lens at a time: the pane under the pointer shows its lens, and every other pane's stays
 * hidden. A pane whose image is not shown (not connected, laid out at no width, not loaded, or
 * `visibility: hidden`) shows no lens either. `refresh()` re-places the lens from the last pointer
 * position, for the caller's moments the pixels under a resting pointer change: an image that
 * settled, a transform or a resize.
 */

/** Attaches the Loupe to `panes` and answers `{ refresh, detach }` */
export function attachLoupe({ panes, levels, readLevel, saveLevel }) {
    let level = validLevel(levels, readLevel())
    // The last pointer position and the pane it is over, null while the pointer is over none
    let pointer = null
    // The listeners bound, for `detach`
    const bindings = []

    const bind = (el, type, listener, options) => {
        el.addEventListener(type, listener, options)
        bindings.push(() => el.removeEventListener(type, listener, options))
    }

    const hideAll = () => panes.forEach(({ lensEl }) => hideLens(lensEl))

    /**
     * Places the lens of the pane under the pointer, hiding every other, or hides them all when there
     * is nothing to place from. The lens is measured before it is written, so the browser lays the
     * stage out once
     */
    const place = () => {
        const spot = pointer && pixelUnder(pointer)
        if (!spot) {
            hideAll()
            return
        }

        const placement = measureLens(
            pointer.pane,
            spot.pixel,
            effectiveLevel(levels, level, spot.fit),
        )
        for (const pane of panes) {
            if (pane === pointer.pane) {
                drawLens(pane.lensEl, placement)
            } else {
                hideLens(pane.lensEl)
            }
        }
    }

    const track = (pane) => (event) => {
        pointer = { x: event.clientX, y: event.clientY, pane }
        place()
    }

    for (const pane of panes) {
        bind(pane.paneEl, "pointermove", track(pane))
        bind(pane.paneEl, "pointerleave", () => {
            pointer = null
            hideAll()
        })
        bind(
            pane.paneEl,
            "wheel",
            (event) => {
                if (
                    event.ctrlKey ||
                    event.metaKey ||
                    event.altKey ||
                    event.shiftKey
                ) {
                    return
                }

                // The pane does not scroll: the plain wheel is the level's
                event.preventDefault()
                const at = { x: event.clientX, y: event.clientY, pane }
                const spot = pixelUnder(at)
                if (!spot) {
                    return
                }

                const next = steppedLevel(
                    levels,
                    effectiveLevel(levels, level, spot.fit),
                    event.deltaY,
                )
                if (next !== level) {
                    level = next
                    saveLevel(level)
                }
                pointer = at
                place()
            },
            { passive: false },
        )
    }

    return {
        refresh: place,
        // The lenses are left as they were found, for a caller that keeps them past the Loupe
        detach() {
            bindings.splice(0).forEach((unbind) => unbind())
            pointer = null
            for (const { lensEl } of panes) {
                hideLens(lensEl)
                lensEl.removeAttribute("style")
                lensEl.removeAttribute(Const.attributes.loupeLevel)
            }
        },
    }
}

/** `chosen` when it is a rung of the ladder, else the first rung */
function validLevel(levels, chosen) {
    return levels.includes(chosen) ? chosen : levels[0]
}

/** The rung next to `level` the way `deltaY` points, up on a negative one; the ends of the ladder stay */
function steppedLevel(levels, level, deltaY) {
    if (deltaY === 0) {
        return level
    }
    const index = levels.indexOf(level) + (deltaY < 0 ? 1 : -1)
    return levels[Math.min(levels.length - 1, Math.max(0, index))]
}

/** `chosen` when it magnifies past `fit`, else the first rung above `fit`, else the top rung */
function effectiveLevel(levels, chosen, fit) {
    if (chosen > fit) {
        return chosen
    }
    return levels.find((rung) => rung > fit) ?? levels[levels.length - 1]
}

/**
 * Whether `imageEl` paints: in the document, laid out at a width, loaded with a size and not
 * hidden (the pane hides it while a frame loads; a failed image is complete at no natural size)
 */
function isShown(imageEl) {
    return (
        imageEl.isConnected &&
        imageEl.clientWidth > 0 &&
        imageEl.naturalWidth > 0 &&
        imageEl.complete &&
        getComputedStyle(imageEl).visibility !== "hidden"
    )
}

/**
 * The painted box of `imageEl` under `object-fit: contain` in viewport coordinates, with `fit`,
 * the screen pixels an original pixel takes: read from its bounding rect, which includes any
 * transform, centered as `contain` centers it
 */
function paintedBox(imageEl) {
    const rect = imageEl.getBoundingClientRect()
    const { naturalWidth, naturalHeight } = imageEl
    const fit = Math.min(rect.width / naturalWidth, rect.height / naturalHeight)
    return {
        left: rect.left + (rect.width - naturalWidth * fit) / 2,
        top: rect.top + (rect.height - naturalHeight * fit) / 2,
        fit,
    }
}

/**
 * The pixel of the pane's image under the pointer, with the pane's `fit`; null when the image is
 * not shown or the pointer is over a letterbox bar
 */
function pixelUnder({ x, y, pane }) {
    const { imageEl } = pane
    if (!isShown(imageEl)) {
        return null
    }

    const box = paintedBox(imageEl)
    const pixelX = (x - box.left) / box.fit
    const pixelY = (y - box.top) / box.fit
    if (
        pixelX < 0 ||
        pixelY < 0 ||
        pixelX >= imageEl.naturalWidth ||
        pixelY >= imageEl.naturalHeight
    ) {
        return null
    }

    return { fit: box.fit, pixel: { x: pixelX, y: pixelY } }
}

/**
 * Where the pane's lens goes to center on `pixel` of the painted box, and what it draws there: that
 * pixel at its center at `level`. The lens is positioned by its offset parent, whose padding box is
 * the origin, and sized by the stylesheet, so it is shown before it is measured; its border is
 * outside its background, so the pixel is centered in the padding box
 */
function measureLens({ imageEl, lensEl }, { x: pixelX, y: pixelY }, level) {
    const box = paintedBox(imageEl)

    lensEl.hidden = false
    const originEl = lensEl.offsetParent
    const originRect = originEl.getBoundingClientRect()
    const centerX =
        box.left + pixelX * box.fit - originRect.left - originEl.clientLeft
    const centerY =
        box.top + pixelY * box.fit - originRect.top - originEl.clientTop
    const { offsetWidth, offsetHeight, clientWidth, clientHeight } = lensEl

    return {
        left: `${centerX - offsetWidth / 2}px`,
        top: `${centerY - offsetHeight / 2}px`,
        backgroundImage: `url("${imageEl.currentSrc}")`,
        backgroundSize: `${imageEl.naturalWidth * level}px ${imageEl.naturalHeight * level}px`,
        backgroundPosition: `${clientWidth / 2 - pixelX * level}px ${clientHeight / 2 - pixelY * level}px`,
        imageRendering: level > 1 ? "pixelated" : "",
        level: `${Math.round(level * 100)}%`,
    }
}

/** Writes a measured placement to the lens; the image is set only when it changed */
function drawLens(lensEl, placement) {
    const { style } = lensEl
    style.left = placement.left
    style.top = placement.top
    if (style.backgroundImage !== placement.backgroundImage) {
        style.backgroundImage = placement.backgroundImage
    }
    style.backgroundSize = placement.backgroundSize
    style.backgroundPosition = placement.backgroundPosition
    style.imageRendering = placement.imageRendering
    lensEl.setAttribute(Const.attributes.loupeLevel, placement.level)
}

function hideLens(lensEl) {
    lensEl.hidden = true
}
