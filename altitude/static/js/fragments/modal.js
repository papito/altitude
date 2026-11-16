/**
 * Modal dialog fragments: opened in the popover or the general modal host, by where the fragment was
 * swapped in, and registered with the dialog operation lifecycle. A validation response that replaces
 * the form in place is re-hydrated, which re-opens the same host. `data-app-dialog-kind` names a
 * dialog that needs wiring beyond its form.
 */
import {
    closeModal,
    getModalOpenId,
    isModalOpenActive,
    ModalHost,
    openModal,
} from "../common/modal.js"
import { hydrateAddToAlbumFragment } from "./add-to-album.js"
import { hydrateAddToLocationFragment } from "./add-to-location.js"
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
        host: fragmentEl.closest("#popoverContent")
            ? ModalHost.popover
            : ModalHost.general,
        title: fragmentEl.dataset.appModalTitle,
        focusSelector: fragmentEl.dataset.appDialogAutofocusSelector,
        selectOnFocus: fragmentEl.dataset.appDialogSelectOnFocus === "true",
        returnFocusSelector: fragmentEl.dataset.appDialogReturnFocus,
    })

    if (fragmentEl.dataset.appDialogKind === "add-to-location") {
        hydrateAddToLocationFragment({ fragmentEl })
    } else if (fragmentEl.dataset.appDialogKind === "add-to-album") {
        hydrateAddToAlbumFragment({ fragmentEl })
    }
}
