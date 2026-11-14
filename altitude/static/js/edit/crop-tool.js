/**
 * The Crop tool (`CONTEXT.md`) over Asset detail's stage (`includes/edit_stage_tools`): while it is open the canvas draws
 * the whole warped frame (`edit-stage.js`, `wholeFrame`), `#cropFrame` lies over it, and `#cropRect` is the crop in it,
 * placed by fractions of the frame. Its gestures are interact.js's, as vendored (`lib/interact.min.js`, a plain script):
 *
 * - `draggable` on `#cropRect` moves the crop;
 * - `resizable` on it, by its eight handles (`.crop-handle` with the classes of the edges it moves), resizes it, with the
 *   `aspectRatio` modifier at the lock's ratio (none when it is Free) and `restrictSize` at the narrowest crop;
 * - `draggable` on `#cropLayer`, which covers the stage, outside the crop and the bar, turns the image (Straighten) by the
 *   change of the pointer's angle around the crop's center, or, with the Level tool on, draws the Level line
 *   (`level-tool.js`), which sets Straighten so it becomes level or plumb, whichever is nearer.
 *
 * interact.js restricts to upright rectangles alone, so each listener turns the rectangle it proposes into frame
 * fractions and, when the Edit keeps the crop inside, stops it at the image's outline (`geometry.limitTransition`)
 * before the Edit takes it. `styleCursor(false)` leaves the cursors to the stylesheet. `#cropRect` is focusable, and the
 * arrows nudge it by a pixel of the frame (ten with Shift).
 *
 * The tool changes the Edit through its host (`edit-panel.js`): `preview` shows a change on the stage without saving it,
 * and the host saves the whole session as one step on Done (`done`) or puts back the Edit the tool opened on (`cancel`).
 * The floating bar holds the guide (O cycles it: Thirds, Grid, Golden ratio, Diagonals, None), the crop's size in the
 * original's pixels, Cancel (Escape, `cancelOpenCropTool`, ahead of closing Asset detail) and Done (Enter or C).
 */
import { Const } from "../constants.js"
import {
    cropRect,
    draggedStraighten,
    outlineIfKept,
    sliderDrag,
    withCrop,
    withSlider,
} from "./adjust.js"
import {
    aspectRatio,
    clamp,
    editedSize,
    frameSize,
    limitTransition,
    MIN_CROP,
} from "./geometry.js"
import {
    drawLevelLine,
    hideLevelLine,
    straightenFromLine,
} from "./level-tool.js"

/** The guides, in the order O cycles them; Thirds is shown until another is picked */
export const GUIDES = Object.freeze([
    { id: "thirds", label: "Thirds" },
    { id: "grid", label: "Grid" },
    { id: "golden", label: "Golden ratio" },
    { id: "diagonals", label: "Diagonals" },
    { id: "none", label: "None" },
])

// A nudge of the arrows, in pixels of the frame, and with Shift
const NUDGE = 1
const NUDGE_LARGE = 10

// The tool of the page, which Escape cancels (`global.js`)
let activeTool = null

/** Cancels the open Crop tool, Escape's job ahead of closing Asset detail; answers whether one was open */
export function cancelOpenCropTool() {
    if (!activeTool?.isOpen()) {
        return false
    }
    activeTool.cancel()
    return true
}

/** Whether the Crop tool is open: O, X and Enter are its keys then (`global.js`) */
export function isCropToolOpen() {
    return Boolean(activeTool?.isOpen())
}

/** The lines of a guide over the crop, in the guide's 100 × 100 box */
function guideLines(id) {
    const vertical = (x) => `<line x1="${x}" y1="0" x2="${x}" y2="100"/>`
    const horizontal = (y) => `<line x1="0" y1="${y}" x2="100" y2="${y}"/>`
    const both = (at) => at.map((v) => vertical(v) + horizontal(v)).join("")
    switch (id) {
        case "thirds":
            return both([100 / 3, 200 / 3])
        case "grid":
            return both([12.5, 25, 37.5, 50, 62.5, 75, 87.5])
        case "golden":
            return both([38.2, 61.8])
        case "diagonals":
            return '<line x1="0" y1="0" x2="100" y2="100"/><line x1="100" y1="0" x2="0" y2="100"/>'
        default:
            return ""
    }
}

/**
 * The Crop tool, driven by `host`: `recipe()` the Edit shown, `upright()` the upright original's size, `preview(recipe)`
 * a change shown and not saved, `done()` and `cancel()` the session's end, and `changed()` after the tool's own state
 * (the Level tool, the guide) changes
 */
export function createCropTool(host) {
    // The open session: the Edit it opened on, and the element focus returns to
    let session = null
    let guide = 0
    let level = false
    // The gesture in progress
    let gesture = null
    // The elements of the fragment the gestures are bound to, and the ratio the resize was set up with
    let bound = { rect: null, layer: null }
    let boundRatio

    function viewEl() {
        return document.getElementById("imageDetailView")
    }

    function layerEl() {
        return document.getElementById("cropLayer")
    }

    function frameEl() {
        return document.getElementById("cropFrame")
    }

    function rectEl() {
        return document.getElementById("cropRect")
    }

    /** Binds the bar's buttons, once */
    function start() {
        activeTool = api
        document.addEventListener("click", (event) => {
            const target = event.target
            if (!(target instanceof Element) || !session) {
                return
            }
            if (target.closest(`[${Const.attributes.cropDone}]`)) {
                host.done()
            } else if (target.closest(`[${Const.attributes.cropCancel}]`)) {
                host.cancel()
            } else if (target.closest(`[${Const.attributes.cropGuide}]`)) {
                cycleGuide()
            }
        })
    }

    /**
     * A new Asset detail fragment: the gestures of the last one's elements go with them, and the new ones are bound;
     * nothing of a session stays open
     */
    function attach(fragmentEl) {
        for (const el of [bound.rect, bound.layer]) {
            if (el && window.interact) {
                window.interact(el).unset()
            }
        }
        session = null
        level = false
        gesture = null
        boundRatio = undefined
        const rect = fragmentEl.querySelector("#cropRect")
        const layer = fragmentEl.querySelector("#cropLayer")
        if (!rect || !layer || !window.interact) {
            bound = { rect: null, layer: null }
            return
        }
        bound = { rect, layer }

        window
            .interact(rect)
            .styleCursor(false)
            .draggable({
                listeners: { start: moveStart, move: moveOn, end: gestureEnd },
            })
        configureResize()
        window
            .interact(layer)
            .styleCursor(false)
            .draggable({
                ignoreFrom: "#cropRect, #cropBar",
                listeners: { start: turnStart, move: turnOn, end: turnEnd },
            })
        rect.addEventListener("keydown", nudge)
    }

    /** The crop's ratio in screen pixels (the frame is drawn at its own aspect): the lock's, or null when it is Free */
    function lockRatio() {
        const recipe = host.recipe()
        const { width, height } = frame(recipe)
        return aspectRatio(recipe, width, height)
    }

    /** The resize's modifiers for the lock's ratio, set up again when the ratio changes between gestures */
    function configureResize() {
        if (!bound.rect || gesture) {
            return
        }
        const ratio = session ? lockRatio() : null
        if (ratio === boundRatio) {
            return
        }
        boundRatio = ratio
        const { modifiers } = window.interact
        window.interact(bound.rect).resizable({
            edges: {
                left: ".crop-handle.w",
                right: ".crop-handle.e",
                top: ".crop-handle.n",
                bottom: ".crop-handle.s",
            },
            modifiers: [
                ...(ratio ? [modifiers.aspectRatio({ ratio })] : []),
                modifiers.restrictSize({ min: minimumSize }),
            ],
            listeners: { start: resizeStart, move: resizeOn, end: gestureEnd },
        })
    }

    /** The narrowest crop in screen pixels, for `restrictSize` */
    function minimumSize() {
        const box = frameEl()?.getBoundingClientRect()
        return box
            ? { width: box.width * MIN_CROP, height: box.height * MIN_CROP }
            : { width: 0, height: 0 }
    }

    /** The frame's size in the original's pixels under `recipe` */
    function frame(recipe) {
        const { width, height } = host.upright()
        return frameSize(width, height, recipe)
    }

    /**
     * The crop moved or resized to `proposed` (frame fractions), stopped at the image's outline when the Edit keeps it
     * inside, and shown
     */
    function propose(proposed) {
        const recipe = host.recipe()
        const next = limitTransition(
            cropRect(recipe),
            proposed,
            outlineIfKept(recipe, host.upright()),
        )
        host.preview(withCrop(recipe, next, host.upright()))
    }

    function markDragging(on) {
        bound.layer?.toggleAttribute("data-dragging", on)
    }

    function moveStart(event) {
        if (!session || level) {
            return
        }
        gesture = {
            kind: "move",
            start: cropRect(host.recipe()),
            x0: event.clientX,
            y0: event.clientY,
            box: frameEl().getBoundingClientRect(),
        }
        markDragging(true)
    }

    function moveOn(event) {
        if (gesture?.kind !== "move") {
            return
        }
        const { start, x0, y0, box } = gesture
        propose({
            ...start,
            x: clamp(
                start.x + (event.clientX - x0) / box.width,
                0,
                1 - start.w,
            ),
            y: clamp(
                start.y + (event.clientY - y0) / box.height,
                0,
                1 - start.h,
            ),
        })
    }

    function resizeStart() {
        if (!session || level) {
            return
        }
        gesture = { kind: "resize", box: frameEl().getBoundingClientRect() }
        markDragging(true)
    }

    /** interact.js's rectangle, in client pixels, as fractions of the frame */
    function resizeOn(event) {
        if (gesture?.kind !== "resize") {
            return
        }
        const { box } = gesture
        const { left, top, width, height } = event.rect
        propose({
            x: (left - box.left) / box.width,
            y: (top - box.top) / box.height,
            w: width / box.width,
            h: height / box.height,
        })
    }

    function gestureEnd() {
        gesture = null
        markDragging(false)
        configureResize()
    }

    /** A drag outside the crop: turns the image around the crop's center, or draws the Level line */
    function turnStart(event) {
        if (!session) {
            return
        }
        if (level) {
            gesture = {
                kind: "level",
                from: { x: event.clientX, y: event.clientY },
            }
            drawLevelLine(gesture.from, gesture.from)
            return
        }
        const box = rectEl().getBoundingClientRect()
        gesture = {
            kind: "turn",
            center: {
                x: box.left + box.width / 2,
                y: box.top + box.height / 2,
            },
            from: { x: event.clientX, y: event.clientY },
            straighten: host.recipe().straighten,
            // Each move from the Edit the turn started from, so a crop it shrank grows back as the turn returns
            turnTo: sliderDrag(host.recipe(), "straighten", host.upright()),
        }
        markDragging(true)
    }

    function turnOn(event) {
        if (gesture?.kind === "level") {
            drawLevelLine(gesture.from, { x: event.clientX, y: event.clientY })
            return
        }
        if (gesture?.kind !== "turn") {
            return
        }
        const straighten = draggedStraighten(
            gesture.straighten,
            gesture.center,
            gesture.from,
            { x: event.clientX, y: event.clientY },
        )
        host.preview(gesture.turnTo(straighten))
    }

    function turnEnd(event) {
        if (gesture?.kind === "level") {
            const straighten = straightenFromLine(
                host.recipe().straighten,
                gesture.from,
                { x: event.clientX, y: event.clientY },
            )
            hideLevelLine()
            if (straighten !== null) {
                level = false
                host.preview(
                    withSlider(
                        host.recipe(),
                        "straighten",
                        straighten,
                        host.upright(),
                    ),
                )
                sync()
                host.changed()
            }
        }
        gestureEnd()
    }

    /** The arrows nudge the crop by a pixel of the frame, ten with Shift, stopped at the outline */
    function nudge(event) {
        const steps = {
            ArrowLeft: [-1, 0],
            ArrowRight: [1, 0],
            ArrowUp: [0, -1],
            ArrowDown: [0, 1],
        }[event.key]
        if (
            !session ||
            !steps ||
            event.ctrlKey ||
            event.metaKey ||
            event.altKey
        ) {
            return
        }
        event.preventDefault()
        const recipe = host.recipe()
        const { width, height } = frame(recipe)
        const pixels = event.shiftKey ? NUDGE_LARGE : NUDGE
        const start = cropRect(recipe)
        propose({
            ...start,
            x: clamp(start.x + (steps[0] * pixels) / width, 0, 1 - start.w),
            y: clamp(start.y + (steps[1] * pixels) / height, 0, 1 - start.h),
        })
    }

    /** Opens the tool on the Edit shown: Thirds or the guide last picked, the crop focused so the arrows nudge it */
    function open() {
        const layer = layerEl()
        if (!layer || session) {
            return
        }
        session = {
            start: host.recipe(),
            returnFocus: document.activeElement,
        }
        level = false
        layer.hidden = false
        viewEl()?.setAttribute(Const.attributes.cropToolOpen, "")
        sync()
        rectEl()?.focus({ preventScroll: true })
    }

    /** Closes the tool, focus back where it was when it opened, if focus is still in the tool */
    function close() {
        if (!session) {
            return
        }
        const { returnFocus } = session
        session = null
        level = false
        gesture = null
        hideLevelLine()
        markDragging(false)
        const layer = layerEl()
        const hadFocus = layer?.contains(document.activeElement)
        if (layer) {
            layer.hidden = true
        }
        viewEl()?.removeAttribute(Const.attributes.cropToolOpen)
        if (hadFocus && returnFocus?.isConnected) {
            returnFocus.focus({ preventScroll: true })
        }
    }

    /** The crop's place, the guide, the size and the lock follow the Edit shown */
    function sync() {
        const rect = rectEl()
        if (!session || !rect) {
            return
        }
        const recipe = host.recipe()
        const crop = cropRect(recipe)
        rect.style.left = `${crop.x * 100}%`
        rect.style.top = `${crop.y * 100}%`
        rect.style.width = `${crop.w * 100}%`
        rect.style.height = `${crop.h * 100}%`
        bound.layer?.toggleAttribute("data-level", level)

        const { id, label } = GUIDES[guide]
        const guideEl = rect.querySelector(".crop-guide")
        if (guideEl && guideEl.dataset.guide !== id) {
            guideEl.innerHTML = guideLines(id)
            guideEl.dataset.guide = id
        }
        document
            .querySelectorAll(`[${Const.attributes.cropGuideName}]`)
            .forEach((el) => (el.textContent = label))
        const { width, height } = host.upright()
        const size = editedSize(width, height, recipe)
        document
            .querySelectorAll(`[${Const.attributes.cropSize}]`)
            .forEach(
                (el) => (el.textContent = `${size.width} × ${size.height} px`),
            )
        configureResize()
    }

    /** O: the next guide */
    function cycleGuide() {
        guide = (guide + 1) % GUIDES.length
        sync()
    }

    /** The Level tool on or off: on, a drag over the stage draws its line and the crop takes no pointer */
    function setLevel(on) {
        level = Boolean(session) && on
        sync()
        host.changed()
    }

    const api = {
        start,
        attach,
        open,
        close,
        sync,
        cycleGuide,
        setLevel,
        isOpen: () => session !== null,
        levelOn: () => level,
        /** The Edit the open session started from, which Cancel puts back */
        startRecipe: () => session?.start ?? null,
        cancel: () => host.cancel(),
    }
    return api
}
