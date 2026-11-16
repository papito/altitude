import { Const } from "../constants.js"

/**
 * The Loupe: a lens that follows the pointer over an `object-fit: contain` image and shows the spot
 * under it at the original's pixels or a multiple, so sharpness and noise are judged on the file.
 * The pointer's pixel comes from the image's bounding rect, which includes any CSS transform, so a
 * zoomed pane changes nothing and a level is always relative to the original. The lens paints the
 * already decoded `currentSrc` as its background, pixelated above 1.
 *
 * A level at or below the pane's own scale shows the next rung above it (or the top rung): a lens
 * that shows what the pane shows is no lens. Only the pane under the pointer shows its lens.
 */

/**
 * `panes` are `{ paneEl, imageEl, lensEl }`; the stylesheet sizes the lens. `refresh` re-places the
 * lens from the last pointer position, for when the pixels under a resting pointer change.
 */
export function attachLoupe({ panes, levels, readLevel, saveLevel }) {
    let level = validLevel(levels, readLevel())
    // The last pointer position and its pane; null while over none
    let pointer = null
    const bindings = []

    const bind = (el, type, listener, options) => {
        el.addEventListener(type, listener, options)
        bindings.push(() => el.removeEventListener(type, listener, options))
    }

    const hideAll = () => panes.forEach(({ lensEl }) => hideLens(lensEl))

    // Measures before writing, so the browser lays the stage out once
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
                // A wheel with a modifier is the browser's (Ctrl zooms the page)
                if (
                    event.ctrlKey ||
                    event.metaKey ||
                    event.altKey ||
                    event.shiftKey
                ) {
                    return
                }

                // The pane does not scroll. Stepping from the level shown, not the one chosen,
                // makes every step change what the lens shows
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

function validLevel(levels, chosen) {
    return levels.includes(chosen) ? chosen : levels[0]
}

/** Up on a negative `deltaY`; the ends of the ladder stay */
function steppedLevel(levels, level, deltaY) {
    if (deltaY === 0) {
        return level
    }
    const index = levels.indexOf(level) + (deltaY < 0 ? 1 : -1)
    return levels[Math.min(levels.length - 1, Math.max(0, index))]
}

function effectiveLevel(levels, chosen, fit) {
    if (chosen > fit) {
        return chosen
    }
    return levels.find((rung) => rung > fit) ?? levels[levels.length - 1]
}

/** The pane hides the image while a frame loads; a failed image is complete at no natural size */
function isShown(imageEl) {
    return (
        imageEl.isConnected &&
        imageEl.clientWidth > 0 &&
        imageEl.naturalWidth > 0 &&
        imageEl.complete &&
        getComputedStyle(imageEl).visibility !== "hidden"
    )
}

/** The painted box under `contain` in viewport coordinates, and `fit`: screen pixels per original pixel */
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

/** Null when the image is not shown or the pointer is over a letterbox bar */
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
 * The lens is sized by the stylesheet, so it is shown before it is measured. Its origin is the
 * offset parent's padding box, and its border lies outside its background, so the pixel is centered
 * in the lens's padding box.
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

/** The image is set only when it changed */
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
