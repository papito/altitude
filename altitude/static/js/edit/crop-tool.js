/**
 * The Crop tool over Asset detail's stage, a view of the Edit panel's held changes: while it is open the canvas draws
 * the whole warped frame, `#cropFrame` lies over it and `#cropRect` is the crop, placed by fractions of the frame.
 * Opening and closing it saves nothing. With interact.js, dragging `#cropRect` moves the crop, its eight handles resize
 * it at the lock's ratio (no narrower than `MIN_CROP`), and a drag on `#cropLayer` outside the crop turns Straighten
 * around the crop's center or, with the Level tool on, draws the Level line. The arrows nudge the focused crop. The
 * Level tool also works with the tool closed (`data-level-only`).
 *
 * interact.js restricts to upright rectangles only, so each listener turns its proposal into frame fractions and,
 * when the Edit keeps the crop inside, stops it at the image's outline (`limitTransition`).
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

// The page's tool, for `isCropToolOpen`
let activeTool = null

/** Whether the Crop tool is open, so O and X are its keys */
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
 * a change shown during a gesture, `commit(recipe)` a change made, `commitSoon(recipe)` one made with the keys, and
 * `changed()` after the tool's own state (the Level tool, the guide) changes
 */
export function createCropTool(host) {
    // While the tool is open: the element focus returns to as it closes
    let session = null
    let guide = 0
    let level = false
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

    /** Binds the bar's guide button, once */
    function start() {
        activeTool = api
        document.addEventListener("click", (event) => {
            const target = event.target
            if (
                target instanceof Element &&
                session &&
                target.closest(`[${Const.attributes.cropGuide}]`)
            ) {
                cycleGuide()
            }
        })
    }

    /** A new Asset detail fragment: the old gestures are unset and the new elements bound; no session stays open */
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
                ignoreFrom: "#cropRect",
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
     * inside: shown, or with `soon` (the arrows' nudge) a change made once the keys rest
     */
    function propose(proposed, { soon = false } = {}) {
        const recipe = host.recipe()
        const next = limitTransition(
            cropRect(recipe),
            proposed,
            outlineIfKept(recipe, host.upright()),
        )
        const changed = withCrop(recipe, next, host.upright())
        if (soon) {
            host.commitSoon(changed)
        } else {
            host.preview(changed)
        }
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

    /** A move, a resize or a turn ends: what it showed is one change */
    function gestureEnd() {
        const ended = gesture
        gesture = null
        markDragging(false)
        configureResize()
        if (ended && ended.kind !== "level") {
            host.commit(host.recipe())
        }
    }

    /** A drag outside the crop: turns the image around the crop's center, or draws the Level line */
    function turnStart(event) {
        if (level) {
            gesture = {
                kind: "level",
                from: { x: event.clientX, y: event.clientY },
            }
            drawLevelLine(gesture.from, gesture.from)
            return
        }
        if (!session) {
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
            // Each move applies to the Edit the turn started from, so a crop it shrank grows back as the turn returns
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
            gesture = null
            if (straighten !== null) {
                level = false
                showLayer()
                host.commit(
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
            return
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
        propose(
            {
                ...start,
                x: clamp(start.x + (steps[0] * pixels) / width, 0, 1 - start.w),
                y: clamp(
                    start.y + (steps[1] * pixels) / height,
                    0,
                    1 - start.h,
                ),
            },
            { soon: true },
        )
    }

    /** `#cropLayer` shows while the tool is open or the Level tool is on; Level alone shows no frame or crop */
    function showLayer() {
        const layer = layerEl()
        if (!layer) {
            return
        }
        layer.hidden = !session && !level
        layer.toggleAttribute("data-level", level)
        layer.toggleAttribute("data-level-only", level && !session)
    }

    /** Opens the tool on the Edit shown, the guide last picked and the crop focused for the arrows; Level stays on */
    function open() {
        if (!layerEl() || session) {
            return
        }
        session = { returnFocus: document.activeElement }
        showLayer()
        viewEl()?.setAttribute(Const.attributes.cropToolOpen, "")
        sync()
        rectEl()?.focus({ preventScroll: true })
    }

    /**
     * Closes the tool and the Level tool, returning focus to where it was at opening if focus is still in the tool; a
     * gesture in progress is dropped, its change left shown
     */
    function close() {
        const returnFocus = session?.returnFocus
        const layer = layerEl()
        const hadFocus = layer?.contains(document.activeElement)
        const wasOpen = Boolean(session)
        session = null
        level = false
        gesture = null
        hideLevelLine()
        markDragging(false)
        showLayer()
        viewEl()?.removeAttribute(Const.attributes.cropToolOpen)
        if (wasOpen && hadFocus && returnFocus?.isConnected) {
            returnFocus.focus({ preventScroll: true })
        }
    }

    /** The crop's place, the guide, the size and the lock follow the Edit shown */
    function sync() {
        const rect = rectEl()
        if (!session || !rect || !host.recipe()) {
            return
        }
        const recipe = host.recipe()
        const crop = cropRect(recipe)
        rect.style.left = `${crop.x * 100}%`
        rect.style.top = `${crop.y * 100}%`
        rect.style.width = `${crop.w * 100}%`
        rect.style.height = `${crop.h * 100}%`

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
        level = Boolean(on) && Boolean(layerEl())
        if (!level && gesture?.kind === "level") {
            gesture = null
            hideLevelLine()
        }
        showLayer()
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
    }
    return api
}
