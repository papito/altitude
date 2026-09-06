/**
 * Swallows the click the browser fires after a pointer gesture ends.
 *
 * Releasing the mouse after a drag (an asset drag or a box selection) still produces a `click` on
 * the element under the pointer, which would toggle a thumbnail or open its detail view. The
 * gesture that ended calls `suppressNextClick()`, and the one capture-phase listener below then
 * drops the next click before HTMX or Alpine listeners see it. The flag clears itself shortly
 * afterwards so a click that never comes cannot swallow a later, genuine one.
 */
const SUPPRESSION_WINDOW_MS = 300

let suppressPending = false
let suppressTimer = null

document.addEventListener(
    "click",
    (event) => {
        if (!suppressPending) {
            return
        }

        event.stopImmediatePropagation()
        event.preventDefault()
        clearSuppression()
    },
    true,
)

export function suppressNextClick() {
    clearSuppression()
    suppressPending = true
    suppressTimer = setTimeout(clearSuppression, SUPPRESSION_WINDOW_MS)
}

/**
 * For a gesture cancelled while the button is still down: swallows the click that its eventual
 * release produces. If the button is released outside the page instead, no `mouseup` arrives, so
 * the next press withdraws the request rather than swallowing an unrelated click later.
 */
export function suppressClickAfterRelease() {
    const onRelease = () => {
        document.removeEventListener("mousedown", onNewPress, true)
        suppressNextClick()
    }
    const onNewPress = () => {
        document.removeEventListener("mouseup", onRelease, true)
    }

    document.addEventListener("mouseup", onRelease, {
        once: true,
        capture: true,
    })
    document.addEventListener("mousedown", onNewPress, {
        once: true,
        capture: true,
    })
}

function clearSuppression() {
    suppressPending = false
    clearTimeout(suppressTimer)
    suppressTimer = null
}
