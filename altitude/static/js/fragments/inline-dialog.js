/**
 * View settings is the only inline dialog: it stays in its popover and applies each switch
 * immediately through its own Alpine component (`alpine/components/view-settings.js`). The
 * contextMenu component owns placement, dismissal, and content cleanup; this hydrator only places
 * focus. Entity actions use modal fragments.
 */
import { focusFragmentElement } from "./helpers.js"

const OPEN_PANEL_SELECTOR = ".context-menu:popover-open"

export function hydrateInlineDialogFragment({ fragmentEl }) {
    const panel = fragmentEl.closest(OPEN_PANEL_SELECTOR)

    if (!panel) {
        console.warn("Inline dialog outside an open context menu; ignoring it")
        return
    }

    const focusSelector = fragmentEl.dataset.appDialogAutofocusSelector

    if (focusSelector) {
        focusFragmentElement(
            fragmentEl,
            focusSelector,
            fragmentEl.dataset.appDialogSelectOnFocus === "true",
        )
    } else {
        panel.focus()
    }
}
