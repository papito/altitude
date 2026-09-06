/**
 * Inline dialog fragments (`data-app-fragment="inline-dialog"`): the folder dialogs and the album
 * rename/delete dialogs, shown inside the open context menu panel in place of its actions, with
 * no backdrop and no focus trap.
 *
 * The `contextMenu` component (`alpine/components/context-menu.js`) owns the panel: placement,
 * the switch between actions and dialog, dismissal, and cleanup. This module only gives the dialog
 * its initial focus and registers the inline presentation with the dialog operation tracker
 * (`dialog-operations.js`): an operation belongs to the dialog it was submitted from, which is
 * active while that same fragment is still in an open panel, and closing it closes the panel,
 * handing focus to the control the dialog declares (the entity's ⋯ button, or another control
 * that survives a deletion).
 *
 * A dialog that needs wiring beyond the form itself names it with `data-app-dialog-kind`; the view
 * settings dialog is the only one, and it submits nothing at all.
 */
import { Const } from "../constants.js"
import { closeContextMenu } from "../common/context-menu.js"
import { registerDialogKind } from "./dialog-operations.js"
import { focusFragmentElement } from "./helpers.js"

const OPEN_PANEL_SELECTOR = ".context-menu:popover-open"

registerDialogKind("inline-dialog", {
    createHandle: (fragmentEl) => {
        const panel = fragmentEl.closest(".context-menu")

        return {
            isActive: () =>
                fragmentEl.isConnected && panel.matches(":popover-open"),
            close: () => {
                const returnFocusSelector =
                    fragmentEl.dataset.appDialogReturnFocus
                const declared =
                    returnFocusSelector &&
                    document.querySelector(returnFocusSelector)

                closeContextMenu(panel, {
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
export function hydrateInlineDialogFragment({ fragmentEl, context, dispatch }) {
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

    if (fragmentEl.dataset.appDialogKind === "view-settings") {
        initializeViewSettingsFragment({ fragmentEl, context, dispatch })
    }
}

/**
 * The view settings dialog: each checkbox is one grid metadata field, seeded from the fields the
 * context holds and applied the moment it changes, so the dialog never submits anything and the
 * panel stays open for as long as the user keeps it there.
 */
function initializeViewSettingsFragment({ fragmentEl, context, dispatch }) {
    const showFields = context.getGridMetadataFields()

    fragmentEl
        .querySelectorAll('input[type="checkbox"]')
        .forEach((checkboxEl) => {
            checkboxEl.checked = showFields.has(checkboxEl.value)
        })

    if (fragmentEl.dataset.appViewSettingsBound === "true") {
        return
    }

    fragmentEl.dataset.appViewSettingsBound = "true"

    fragmentEl.addEventListener("change", (event) => {
        const checkboxEl = event.target
        if (
            !(checkboxEl instanceof HTMLInputElement) ||
            checkboxEl.type !== "checkbox"
        ) {
            return
        }

        const fieldName = checkboxEl.value
        const checked = checkboxEl.checked

        if (checked) {
            context.addGridMetadataField(fieldName)
        } else {
            context.removeGridMetadataField(fieldName)
        }

        dispatch(Const.events.viewSettingChanged, {
            fieldName,
            checked,
        })
    })
}
