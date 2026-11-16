/**
 * The Level tool's line, drawn over the Crop tool's layer during a drag, from where it started to the pointer. Straighten
 * turns so the line becomes level or plumb, whichever is nearer.
 */
import { levelStraighten } from "./adjust.js"

const LINE_ID = "levelLine"
const LAYER_ID = "cropLayer"

/** Draws the line from `from` to `to`, both in client pixels */
export function drawLevelLine(from, to) {
    const svgEl = document.getElementById(LINE_ID)
    const layerEl = document.getElementById(LAYER_ID)
    if (!svgEl || !layerEl) {
        return
    }
    const box = layerEl.getBoundingClientRect()
    svgEl.setAttribute("viewBox", `0 0 ${box.width} ${box.height}`)
    const lineEl = svgEl.querySelector("line")
    lineEl.setAttribute("x1", from.x - box.left)
    lineEl.setAttribute("y1", from.y - box.top)
    lineEl.setAttribute("x2", to.x - box.left)
    lineEl.setAttribute("y2", to.y - box.top)
    // An <svg> has no `hidden` property (it is HTMLElement's), so the attribute is set itself
    svgEl.removeAttribute("hidden")
}

export function hideLevelLine() {
    document.getElementById(LINE_ID)?.setAttribute("hidden", "")
}

/** The Straighten the line asks for, from the current `straighten`; null for a line too short to be one (a click) */
export function straightenFromLine(straighten, from, to) {
    return levelStraighten(straighten, to.x - from.x, to.y - from.y)
}
