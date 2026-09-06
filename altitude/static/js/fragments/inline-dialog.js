/**
 * Inline dialog fragments (`data-app-fragment="inline-dialog"`): the three folder dialogs, shown
 * inside the open folder menu panel in place of its actions, with no backdrop and no focus trap.
 *
 * The `folderMenu` component (`alpine/components/folder-menu.js`) owns the panel: placement, the
 * switch between actions and dialog, dismissal, and cleanup. This module only gives the dialog
 * its initial focus and registers the inline presentation with the dialog operation tracker
 * (`dialog-operations.js`): an operation belongs to the dialog it was submitted from, which is
 * active while that same fragment is still in an open panel, and closing it closes the panel,
 * handing focus to the control the dialog declares (the folder's ⋯ button, or its parent's after
 * a deletion).
 */
import { closeFolderMenu } from "../common/folder-menu.js"
import { registerDialogKind } from "./dialog-operations.js"
import { focusFragmentElement } from "./helpers.js"

const OPEN_PANEL_SELECTOR = ".folder-menu:popover-open"

registerDialogKind("inline-dialog", {
    createHandle: (fragmentEl) => {
        const panel = fragmentEl.closest(".folder-menu")

        return {
            isActive: () =>
                fragmentEl.isConnected && panel.matches(":popover-open"),
            close: () => {
                const returnFocusSelector =
                    fragmentEl.dataset.appDialogReturnFocus
                const declared =
                    returnFocusSelector &&
                    document.querySelector(returnFocusSelector)

                closeFolderMenu(panel, {
                    reason: "dialog operation completed",
                    returnFocus: true,
                    focusTarget: declared || undefined,
                })
            },
        }
    },
})

/**
 * Initial focus goes to the declared autofocus selector (optionally selecting the field's text)
 * or else to the panel itself, so a held or repeated Enter from the menu cannot activate a
 * destructive control; one Tab reaches it.
 */
export function hydrateInlineDialogFragment({ fragmentEl }) {
    const panel = fragmentEl.closest(OPEN_PANEL_SELECTOR)

    if (!panel) {
        console.warn("Inline dialog outside an open folder menu; ignoring it")
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
