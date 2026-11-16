/**
 * Swallows the click the browser fires when a pointer gesture (a drag, a box selection) ends, which
 * would toggle a thumbnail, open its detail or search. A capture-phase listener drops it before htmx
 * or Alpine see it, and the flag clears itself shortly after, so a click that never comes cannot
 * swallow a later, genuine one.
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
 * Swallows the click of the eventual release, for a gesture cancelled with the button down or a drag
 * that arms it at its start (with inertia a flick's end fires after the click, too late for
 * `suppressNextClick`). A release outside the page sends no `mouseup`, so the next press withdraws it.
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
