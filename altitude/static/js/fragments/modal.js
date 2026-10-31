/**
 * Modal dialog fragments (`data-app-fragment="modal"`): hydration into the shared modal owner, and
 * the modal presentation of the dialog operation lifecycle (`dialog-operations.js`). The host the
 * fragment was swapped into decides how it is presented: the popover dialog or the general modal.
 * A validation response that replaces the form in place is re-hydrated, which re-opens the same
 * host.
 *
 * A dialog that needs wiring beyond its form names it with `data-app-dialog-kind`: the batch ops'
 * Add to album (`add-to-album.js`) and Add to location (`add-to-location.js`) dialogs.
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
