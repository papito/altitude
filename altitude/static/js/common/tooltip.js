/**
 * The tooltip: a short hint above a control on a click, the Alpine UI `$tooltip` magic without
 * tippy or Popper. The message goes into the one `popover="manual"` `#tooltip` host, placed by CSS
 * anchor positioning above the element given an inline `anchor-name`, so any element can call it.
 *
 * One tooltip at a time. It goes after `LIFETIME_MS` or at the next press or key, read in the
 * capture phase: a press that hides the anchor comes a tick before it is gone, and a tooltip with
 * no anchor would sit at the viewport's corner. Screen readers hear it through the snackbar's
 * polite region, since a live region that appears with its text is not reliably read.
 */
import { announcePolitely } from "./snackbar.js"

const HOST_ID = "tooltip"

const ANCHOR_NAME = "--tooltip-anchor"

const LIFETIME_MS = 2000

let anchorEl = null

let hideTimer = null

let onDismissGesture = null

// `$tooltip(message)` anchors to the element the expression is on
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
