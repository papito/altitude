/**
 * The Face highlights of Asset detail: each Face's box drawn on the media, its Person's name in a
 * badge under it. The layer shares the media's box, so a highlight is placed by fractions of the shown
 * media. Detector boxes and shown pixels are one space, since the detector decodes an image upright by
 * its EXIF orientation as a browser does. The layer and the badges take no pointer events, so the
 * player's control bar under them stays usable; each box is a toggle button of its row's selection.
 */
import { Const } from "../constants.js"

export const LAYER_ID = "faceHighlights"

// A Face in a Video counts as current while the player's time is within this of its Frame time
export const FRAME_TIME_TOLERANCE_MS = 100

export function createFaceHighlights() {
    function layerEl() {
        return document.getElementById(LAYER_ID)
    }

    /** Draws `faces`, boxes as fractions of the shown media; rebuilt whole, a handful of boxes at most */
    function show({ faces, selectedFaceId }) {
        layerEl()?.replaceChildren(
            ...faces.flatMap((face, index) =>
                highlightOf({ face, index, selectedFaceId }),
            ),
        )
    }

    function clear() {
        layerEl()?.replaceChildren()
    }

    return { show, clear }
}

function highlightOf({ face, index, selectedFaceId }) {
    const anchorName = `--face-highlight-${index}`
    const selected = face.faceId === selectedFaceId
    const name = face.hidden ? `${face.name}, hidden` : face.name

    const boxEl = document.createElement("button")
    boxEl.type = "button"
    boxEl.className = "face-highlight"
    boxEl.setAttribute(Const.attributes.faceId, face.faceId)
    boxEl.setAttribute("aria-pressed", String(selected))
    boxEl.setAttribute("aria-label", name)
    boxEl.style.setProperty("--face-x", face.box.x)
    boxEl.style.setProperty("--face-y", face.box.y)
    boxEl.style.setProperty("--face-width", face.box.width)
    boxEl.style.setProperty("--face-height", face.box.height)
    boxEl.style.setProperty("anchor-name", anchorName)

    const badgeEl = document.createElement("span")
    badgeEl.className = "face-highlight-badge"
    badgeEl.style.setProperty("position-anchor", anchorName)
    badgeEl.append(face.name)
    if (face.hidden) {
        const markEl = document.createElement("span")
        markEl.className = "hidden-mark"
        markEl.textContent = "Hidden"
        badgeEl.append(" ", markEl)
    }

    return [boxEl, badgeEl]
}
