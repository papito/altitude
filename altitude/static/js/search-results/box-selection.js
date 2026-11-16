import SelectionArea from "../lib/viselect.esm.js"
import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import {
    suppressClickAfterRelease,
    suppressNextClick,
} from "./click-suppression.js"

/**
 * Box selection: dragging a rectangle over the results grid selects the thumbnails it touches.
 * Viselect owns the gesture (the rectangle, autoscroll of `#content`, the list of thumbnails inside)
 * but holds no selection state; on release this module commits its list through the `selectedAssets`
 * store. A plain drag from empty grid space replaces the selection; a Shift-drag from anywhere,
 * thumbnails included, adds to it, Shift being read when the button goes down. Escape, window blur, a
 * hidden page or a replaced grid discard the box. One controller exists per displayed grid.
 */

const AUTOSCROLL_EDGE_PX = 32
const START_THRESHOLD_PX = 10

let current = null

// Set while the controller dispatches a replayed `mousemove` (see `replayPointer`), so
// `trackPointer` tells it from a real move
let replaying = false

export function bindBoxSelection({ assetsElement, contentElement }) {
    current?.destroy()
    current = null

    if (assetsElement && contentElement) {
        current = createBoxSelection({ assetsElement, contentElement })
    }
}

function createBoxSelection({ assetsElement, contentElement }) {
    // The thumbnail box is the `.drag-drop` div; its `<img>` carries the asset ID too, so the selector
    // names the div
    const thumbnailSelector = `#assets div[${Const.attributes.assetId}]`

    // The gesture in progress, from the button going down to release or cancellation
    let gesture = null

    const selection = new SelectionArea({
        selectables: [thumbnailSelector],
        startAreas: ["#assets"],
        // The scroll container: Viselect autoscrolls it and clips the rectangle to it
        boundaries: ["#content"],
        container: "body",
        behaviour: {
            overlap: "drop",
            intersect: "touch",
            startThreshold: START_THRESHOLD_PX,
            scrolling: {
                startScrollMargins: { x: 0, y: AUTOSCROLL_EDGE_PX },
            },
        },
        features: {
            touch: false,
            range: false,
            deselectOnBlur: false,
            singleTap: { allow: false },
        },
    })

    selection.on("beforestart", ({ event }) => {
        if (!(event instanceof MouseEvent) || event.button !== 0) {
            return false
        }

        const target = event.target instanceof Element ? event.target : null
        const overThumbnail = target?.closest(thumbnailSelector)

        // A plain drag over a thumbnail is an asset drag, handled by interact.js
        if (!target || (overThumbnail && !event.shiftKey)) {
            return false
        }

        beginGesture(event)
    })

    selection.on("start", () => startDragging())

    // The event this carries is not read: while Viselect's scroll loop runs, it is the stale one
    // that started the loop (see `trackPointer`)
    selection.on("move", () => resumeAutoscrollAtEdge())

    selection.on("stop", ({ event, store }) => {
        if (!gesture) {
            return
        }

        const { additive } = gesture
        // What was inside the rectangle on release, less cards that have left the grid since
        const candidates = [...new Set(store.selected)].filter((el) =>
            assetsElement.contains(el),
        )

        endGesture()

        // Only a release carries an event; Viselect's other ways of emitting `stop` are not used
        if (!event) {
            return
        }

        suppressNextClick()
        commit({ candidates, additive })
    })

    function beginGesture(event) {
        gesture = {
            additive: event.shiftKey,
            dragging: false,
            pointer: null,
            replayScheduled: false,
            detach: [],
        }
        rememberPointer(event)

        listen(document, "mousemove", trackPointer)

        // The button is down: block native text selection until it is released
        listen(document, "selectstart", preventDefault)

        // A press that never passes the threshold is a click, for which Viselect emits nothing, so the
        // gesture ends here. This runs before Viselect's own `mouseup` handler, registered after
        // `beforestart`, so a real drag is left to `stop`.
        listen(document, "mouseup", () => {
            if (!gesture?.dragging) {
                endGesture()
            }
        })
    }

    function startDragging() {
        gesture.dragging = true
        window.getSelection()?.removeAllRanges()

        listen(window, "keydown", onKeydown, { capture: true })
        listen(window, "blur", () => cancelGesture("window lost focus"))
        listen(document, "visibilitychange", () => {
            if (document.hidden) {
                cancelGesture("page hidden")
            }
        })

        // Cards appended by continuous scroll become candidates as soon as they are in the DOM
        listen(assetsElement, "htmx:after:settle", () => {
            selection.resolveSelectables()
            refreshGeometry()
        })

        // `load` does not bubble; capturing on the grid sees every thumbnail image that finishes
        // loading and takes its real size
        listen(assetsElement, "load", refreshGeometry, { capture: true })

        // Panel resizes and metadata toggles move the thumbnails. A ResizeObserver reports once on
        // starting; only later notifications mean a change.
        let initialSizesReported = false
        const observer = new ResizeObserver(() => {
            if (!initialSizesReported) {
                initialSizesReported = true
                return
            }

            refreshGeometry()
        })
        observer.observe(contentElement)
        observer.observe(assetsElement)
        gesture.detach.push(() => observer.disconnect())
    }

    function onKeydown(event) {
        if (event.key !== "Escape") {
            return
        }

        // Consumed here, so `global.js` does not treat it as closing a modal or menu
        event.preventDefault()
        event.stopPropagation()
        cancelGesture("Escape")
    }

    function rememberPointer(event) {
        if (gesture && event instanceof MouseEvent) {
            gesture.pointer = { x: event.clientX, y: event.clientY }
        }
    }

    /**
     * Keeps the pointer's real position for the replays. Viselect 3.9.0's `move` cannot be the source:
     * while its scroll loop runs it carries the event that started the loop, so a pointer that has
     * left the edge band would still read as in it and keep the pane scrolling. Replays are skipped.
     */
    function trackPointer(event) {
        if (!replaying) {
            rememberPointer(event)
        }
    }

    /**
     * Viselect 3.9.0 recomputes intersections and the autoscroll speed only in a frame it schedules
     * from a `mousemove`, so a page appended or a thumbnail resized under a resting pointer would go
     * unnoticed. Replaying the last position as a synthetic `mousemove` on `document` schedules that
     * frame without patching the vendored library. Revisit when upgrading it.
     */
    function refreshGeometry() {
        replayPointer()
    }

    /**
     * Viselect starts its scroll loop only from a `mousemove` after a frame computed a non-zero speed,
     * and ends it for good at the container's end, so a pointer resting in the edge band would not
     * scroll. When a `move` frame finds the pointer in the band with room to scroll, one replay in the
     * next frame starts the loop.
     */
    function resumeAutoscrollAtEdge() {
        if (!gesture?.dragging || !gesture.pointer || gesture.replayScheduled) {
            return
        }

        const { top, bottom } = contentElement.getBoundingClientRect()
        const { scrollTop, clientHeight, scrollHeight } = contentElement
        const { y } = gesture.pointer
        const canScrollDown = scrollTop + clientHeight < scrollHeight - 1
        const atBottomEdge = y > bottom - AUTOSCROLL_EDGE_PX && canScrollDown
        const atTopEdge = y < top + AUTOSCROLL_EDGE_PX && scrollTop > 0

        if (!atBottomEdge && !atTopEdge) {
            return
        }

        gesture.replayScheduled = true
        requestAnimationFrame(() => {
            if (gesture) {
                gesture.replayScheduled = false
            }

            replayPointer()
        })
    }

    function replayPointer() {
        if (!gesture?.dragging || !gesture.pointer) {
            return
        }

        const { x, y } = gesture.pointer
        replaying = true
        try {
            document.dispatchEvent(
                new MouseEvent("mousemove", {
                    clientX: x,
                    clientY: y,
                    bubbles: true,
                    cancelable: true,
                }),
            )
        } finally {
            replaying = false
        }
    }

    function commit({ candidates, additive }) {
        const store = Alpine.store(Const.state.selectedAssets)

        if (!additive) {
            store.reset()
        }

        candidates.forEach((el) => store.select(el.dataset.assetId))

        console.debug(
            "Box selection: %d thumbnails in the box, additive: %s, selected now: %d",
            candidates.length,
            additive,
            store.size,
        )
    }

    function cancelGesture(reason) {
        if (!gesture) {
            return
        }

        const { dragging } = gesture

        // Silent: no `stop` event, so nothing is committed
        selection.cancel()
        endGesture()

        if (dragging) {
            console.debug("Box selection discarded: %s", reason)
            // The button is still down; the click on its release must not reach the grid
            suppressClickAfterRelease()
        }
    }

    function endGesture() {
        if (!gesture) {
            return
        }

        gesture.detach.forEach((detach) => detach())
        gesture = null

        // Viselect keeps the last box's elements as its own stored selection; the store is the
        // only record of what is selected
        selection.clearSelection(true, true)
    }

    function listen(target, type, handler, options) {
        target.addEventListener(type, handler, options)
        gesture.detach.push(() =>
            target.removeEventListener(type, handler, options),
        )
    }

    function destroy() {
        cancelGesture("grid replaced")
        selection.destroy()
    }

    return { destroy }
}

function preventDefault(event) {
    event.preventDefault()
}
