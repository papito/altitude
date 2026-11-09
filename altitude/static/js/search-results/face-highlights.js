/**
 * The Face highlights of Asset detail (the glossary's term): a Face's box drawn on the media, with
 * its Person's name in a badge under it. The layer (`#faceHighlights`, `htmx/view_image_detail_modal`)
 * shares the media's box in the stage, so a highlight is placed by fractions of the shown element's
 * natural size: the box the detector found in the upright image's pixels over the size the browser
 * shows (`naturalWidth`, `videoWidth`), the same space, since OpenCV decodes an image upright by its
 * EXIF orientation as a browser does, and a Video's frames are turned as its player turns them.
 *
 * The layer takes no pointer events; each highlight's box does, as a toggle button of its People
 * row's selection (`aria-pressed`). Its badge, anchored to it (CSS anchor positioning, each highlight
 * named by its index), sits under the box and is kept inside the media: above the box when there is
 * no room below, and inside the box's bottom edge when there is none above either; it is cut with
 * an ellipsis at the layer's width and takes no pointer events either, so the player's control bar
 * under it stays usable.
 *
 * `detail-panel.js` owns which Faces show (the `p` setting, the selection, a Video's Frame time) and
 * calls `show` with them; `clear` empties the layer, as soon as another asset starts loading.
 */
import { Const } from "../constants.js"

export const LAYER_ID = "faceHighlights"

// A Face in a Video counts as current while the player's time is within this of its Frame time
export const FRAME_TIME_TOLERANCE_MS = 100

export function createFaceHighlights() {
    function layerEl() {
        return document.getElementById(LAYER_ID)
    }

    /**
     * Draws `faces` (`{ faceId, name, hidden, box: { x, y, width, height } }` in the shown element's
     * pixels) over a shown element of `size`, the selected one emphasised. The layer is rebuilt
     * whole: a handful of boxes at most.
     */
    function show({ faces, selectedFaceId, size }) {
        layerEl()?.replaceChildren(
            ...faces.flatMap((face, index) =>
                highlightOf({ face, index, selectedFaceId, size }),
            ),
        )
    }

    function clear() {
        layerEl()?.replaceChildren()
    }

    return { show, clear }
}

/** The box, then its badge, which anchors to the box by the name the index gives it */
function highlightOf({ face, index, selectedFaceId, size }) {
    const anchorName = `--face-highlight-${index}`
    const selected = face.faceId === selectedFaceId
    const name = face.hidden ? `${face.name}, hidden` : face.name

    const boxEl = document.createElement("button")
    boxEl.type = "button"
    boxEl.className = "face-highlight"
    boxEl.setAttribute(Const.attributes.faceId, face.faceId)
    boxEl.setAttribute("aria-pressed", String(selected))
    boxEl.setAttribute("aria-label", name)
    boxEl.style.setProperty("--face-x", face.box.x / size.width)
    boxEl.style.setProperty("--face-y", face.box.y / size.height)
    boxEl.style.setProperty("--face-width", face.box.width / size.width)
    boxEl.style.setProperty("--face-height", face.box.height / size.height)
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
