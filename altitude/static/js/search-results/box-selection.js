import SelectionArea from "../lib/viselect.esm.js"
import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import {
    suppressClickAfterRelease,
    suppressNextClick,
} from "./click-suppression.js"

/**
 * BOX SELECTION
 *
 * Dragging a rectangle over the results grid selects the thumbnails it touches. Viselect owns the
 * gesture: it draws the rectangle, autoscrolls `#content` near its vertical edges, and keeps the
 * list of thumbnails currently inside the rectangle. It changes no selection state of its own: it
 * adds no classes, and this module reads its list once, on release. Only then does the box go
 * through the same code a Shift-click uses - the `selectedAssets` store's `reset()` for a
 * replacement box, then each thumbnail's `selectable` component's `toggle()` - so the checkmarks
 * and the store update exactly as they always did.
 *
 * Gesture rules:
 * - A plain drag from empty grid space (padding, gaps, a cell's metadata) replaces the selection.
 * - A Shift-drag from anywhere in the grid, thumbnails included, adds to it. Shift is read when
 *   the button goes down; releasing it mid-drag changes nothing. `dragon-drop.js` declines to
 *   start an asset drag while Shift is held, which is what leaves that gesture to the box.
 * - A thumbnail counts as soon as the rectangle touches any part of its image box.
 * - Release commits. Escape, the window losing focus, the page being hidden, or the grid being
 *   replaced discards the box without touching the selection.
 * - The click the browser fires on release is swallowed so it cannot toggle a thumbnail or open
 *   its detail view.
 *
 * One controller exists per displayed grid. `bindBoxSelection` is called from the search-results
 * fragment hydrator, the one place that knows a grid was just replaced.
 */

const AUTOSCROLL_EDGE_PX = 32
const START_THRESHOLD_PX = 10

let current = null

export function bindBoxSelection({ assetsElement, contentElement }) {
    current?.destroy()
    current = null

    if (assetsElement && contentElement) {
        current = createBoxSelection({ assetsElement, contentElement })
    }
}

function createBoxSelection({ assetsElement, contentElement }) {
    // The thumbnail box is the `.drag-drop` div carrying the asset ID. Its `<img>` carries the same
    // attribute, so the selector names the div explicitly.
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

    selection.on("move", ({ event }) => {
        rememberPointer(event)
        resumeAutoscrollAtEdge()
    })

    selection.on("stop", ({ event, store }) => {
        if (!gesture) {
            return
        }

        const { additive } = gesture
        // What was inside the rectangle when the button came up. A card that left the grid
        // in the meantime is dropped.
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

        // The button is down: block native text selection until it is released
        listen(document, "selectstart", preventDefault)

        // A press that never travels past the threshold is a click, for which Viselect emits
        // nothing, so the gesture ends here. This runs before Viselect's own `mouseup` handler,
        // which is registered after `beforestart`, so a real drag is left to the `stop` handler.
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

        // Panel resizing and metadata visibility changes move the thumbnails. A ResizeObserver
        // reports the current sizes once as soon as it starts observing; only later notifications
        // mean something changed.
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
     * Makes Viselect re-evaluate the rectangle against the current layout while the pointer is
     * still.
     *
     * Viselect 3.9.0 recomputes intersections, and the autoscroll speed, only in a frame it
     * schedules from a `mousemove`. So a page appended under a resting pointer, or a thumbnail
     * that loads and takes its real size, would go unnoticed. Replaying the last pointer position
     * as a synthetic `mousemove` on `document`, where Viselect binds its move handler for the
     * duration of the drag, schedules that frame. Only DOM events and public behaviour are used,
     * so the vendored library stays unchanged. Revisit when upgrading it.
     */
    function refreshGeometry() {
        replayPointer()
    }

    /**
     * Keeps autoscroll going for a pointer resting in the edge band.
     *
     * Viselect starts its scroll loop only from a `mousemove` that arrives after a frame computed
     * a non-zero speed, and the loop ends for good once the container cannot scroll further. A
     * pointer whose last movement lands in the band, or that rests there while continuous scroll
     * appends a page, would therefore stay still. Each frame reports through `move`; when it
     * finds the pointer in the band with room left to scroll, one more replay in the next frame
     * starts the loop. Once the loop runs, the replay coincides with its own frames, and at the
     * end of the container the replays stop.
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
        document.dispatchEvent(
            new MouseEvent("mousemove", {
                clientX: x,
                clientY: y,
                bubbles: true,
                cancelable: true,
            }),
        )
    }

    function commit({ candidates, additive }) {
        const store = Alpine.store(Const.state.selectedAssets)

        // Synchronous: `reset()` dispatches `deselectAll`, which is handled at once
        if (!additive) {
            store.reset()
        }

        let added = 0

        for (const el of candidates) {
            const component = Alpine.$data(el)

            // `toggle()` would deselect an already selected thumbnail
            if (component && !component.selected) {
                component.toggle()
                added += 1
            }
        }

        console.debug(
            "Box selection: %d thumbnails in the box, %d newly selected, additive: %s, selected now: %d",
            candidates.length,
            added,
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
