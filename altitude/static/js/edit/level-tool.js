/**
 * The Level tool's line (`#levelLine`, `includes/edit_stage_tools`), drawn over the Crop tool's layer while a drag
 * draws it: from where the drag started to the pointer, in the layer's pixels. What the line sets is the Crop tool's
 * (`crop-tool.js`, through `adjust.levelStraighten`): Straighten turns so the line becomes level or plumb, whichever is
 * nearer.
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
    svgEl.hidden = false
}

export function hideLevelLine() {
    const svgEl = document.getElementById(LINE_ID)
    if (svgEl) {
        svgEl.hidden = true
    }
}

/**
 * The Straighten the line from `from` to `to` (client pixels over the shown frame) asks for, from `straighten`; null
 * for a line too short to be one (a click)
 */
export function straightenFromLine(straighten, from, to) {
    return levelStraighten(straighten, to.x - from.x, to.y - from.y)
}
