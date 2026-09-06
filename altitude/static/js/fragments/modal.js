/**
 * Modal dialog fragments (`data-app-fragment="modal"`): hydration into the shared modal owner, and
 * the modal presentation of the dialog operation lifecycle (`dialog-operations.js`). A validation
 * response that replaces the form in place is re-hydrated, which re-opens the same host.
 */
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

export function hydrateModalFragment({ fragmentEl }) {
    openModal({
        host: ModalHost.general,
        title: fragmentEl.dataset.appModalTitle,
        focusSelector: fragmentEl.dataset.appDialogAutofocusSelector,
        selectOnFocus: fragmentEl.dataset.appDialogSelectOnFocus === "true",
        returnFocusSelector: fragmentEl.dataset.appDialogReturnFocus,
    })
}
