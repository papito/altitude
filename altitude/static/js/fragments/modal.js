/**
 * Modal dialog fragments (`data-app-fragment="modal"`): hydration into the shared modal owner, and
 * the modal presentation of the dialog operation lifecycle (`dialog-operations.js`). A validation
 * response that replaces the form in place is re-hydrated, which re-opens the same host.
 */
import { Const } from "../constants.js"
import {
    closeModal,
    getModalOpenId,
    isModalOpenActive,
    ModalHost,
    openModal,
} from "../common/modal.js"
import { registerDialogKind } from "./dialog-operations.js"

// An operation issued from a modal dialog belongs to the open displayed at that moment; only that
// open may be closed or have its form replaced by the response.
registerDialogKind("modal", {
    createHandle: () => {
        const openId = getModalOpenId()

        return {
            isActive: () => isModalOpenActive(openId),
            close: () => closeModal(),
        }
    },
})

export function hydrateModalFragment({ fragmentEl, context, dispatch }) {
    openModal({
        host: ModalHost.general,
        title: fragmentEl.dataset.appModalTitle,
        focusSelector: fragmentEl.dataset.appDialogAutofocusSelector,
        selectOnFocus: fragmentEl.dataset.appDialogSelectOnFocus === "true",
        returnFocusSelector: fragmentEl.dataset.appDialogReturnFocus,
    })

    if (fragmentEl.dataset.appModalKind === "view-settings") {
        initializeViewSettingsModalFragment({ fragmentEl, context, dispatch })
    }
}

function initializeViewSettingsModalFragment({
    fragmentEl,
    context,
    dispatch,
}) {
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
