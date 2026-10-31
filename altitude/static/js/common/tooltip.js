/**
 * The tooltip: a short hint shown above a control on a click, in the shape of the Alpine UI
 * tooltip's `$tooltip` magic (`docs/reference/alpine-components/tooltip.md`) but with no tippy or
 * Popper. `$tooltip('Message')` in a click handler writes the message into `#tooltip`, the one
 * `popover="manual"` host of `views/includes/html_common.scala.html`, and shows it in the top
 * layer; the `#tooltip` rule of `core.css` places it by CSS anchor positioning, centered above
 * the element the magic names `--tooltip-anchor` (an inline `anchor-name`, so any element can
 * call it with no ID or rule of its own). The magic is registered with everything else
 * `html_common` binds, by `registerHtmlCommonBindings` in `stores/html-common.js`.
 *
 * One tooltip at a time. It goes away after `LIFETIME_MS`, or at the next press or key anywhere,
 * read at the document's capture phase: a press or Enter on a control that removes or hides the
 * anchor (Deselect All hides the batch bar) comes a tick before the anchor is gone, and a
 * tooltip whose anchor is gone would be placed at the viewport's corner. A screen reader hears
 * the message through the snackbar's polite live region, since the host is `display: none`
 * until shown and a live region that appears with its text is not reliably read.
 */
import { announcePolitely } from "./snackbar.js"

const HOST_ID = "tooltip"

const ANCHOR_NAME = "--tooltip-anchor"

const LIFETIME_MS = 2000

// The element the host is anchored to while the tooltip is shown
let anchorEl = null

let hideTimer = null

// The press-or-key handler of the shown tooltip, removed with it
let onDismissGesture = null

// Alpine's magic callback: `$tooltip(message)` on the element the directive is on
export const tooltipMagic = (el) => (message) => showTooltip(el, message)

export function showTooltip(el, message) {
    hideTooltip()

    const host = document.getElementById(HOST_ID)
    if (!host) {
        console.warn(`No #${HOST_ID} host on this page; dropped: ${message}`)
        return
    }

    anchorEl = el
    el.style.anchorName = ANCHOR_NAME
    host.textContent = message

    if (!host.matches(":popover-open")) {
        host.showPopover()
    }

    hideTimer = setTimeout(hideTooltip, LIFETIME_MS)

    onDismissGesture = () => hideTooltip()
    document.addEventListener("pointerdown", onDismissGesture, {
        capture: true,
    })
    document.addEventListener("keydown", onDismissGesture, { capture: true })

    announcePolitely(message)
}

/** Hides the tooltip at once; a no-op when none is shown */
export function hideTooltip() {
    clearTimeout(hideTimer)
    hideTimer = null

    if (onDismissGesture) {
        document.removeEventListener("pointerdown", onDismissGesture, {
            capture: true,
        })
        document.removeEventListener("keydown", onDismissGesture, {
            capture: true,
        })
        onDismissGesture = null
    }

    const host = document.getElementById(HOST_ID)
    if (host?.matches(":popover-open")) {
        host.hidePopover()
    }

    if (anchorEl) {
        anchorEl.style.anchorName = ""
        anchorEl = null
    }
}
