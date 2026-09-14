/**
 * View settings is the only inline dialog: it stays in its popover and applies each checkbox
 * immediately. The contextMenu component owns placement, dismissal, and content cleanup; this
 * hydrator places focus and binds the settings controls. Entity actions use modal fragments.
 */
import { focusFragmentElement } from "./helpers.js"

const OPEN_PANEL_SELECTOR = ".context-menu:popover-open"

export function hydrateInlineDialogFragment({ fragmentEl, context }) {
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
        initializeViewSettingsFragment({ fragmentEl, context })
    }
}

/**
 * The view settings dialog: each checkbox is one grid metadata field, seeded from the fields the
 * context holds and written back the moment it changes (the grid follows the set reactively,
 * js/search-results/metadata-visibility.js), so the dialog never submits anything and the panel
 * stays open for as long as the user keeps it there.
 */
function initializeViewSettingsFragment({ fragmentEl, context }) {
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

        if (checkboxEl.checked) {
            context.addGridMetadataField(checkboxEl.value)
        } else {
            context.removeGridMetadataField(checkboxEl.value)
        }
    })
}
