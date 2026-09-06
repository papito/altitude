/**
 * Document-level keyboard handling, loaded on every page.
 *
 * Escape closes the active modal and any open folder menu, wherever focus is, and is consumed by
 * them, so a background inline edit (the person name editor) survives; with neither open, Escape
 * is broadcast for such editors to cancel. Consuming it also keeps the browser's own Escape
 * handling for the popover from running a second time.
 * Arrow keys navigate between assets only while asset detail is active and no text is being edited.
 */
import { closeOpenFolderMenu } from "./common/folder-menu.js"
import { closeModal, getActiveModalHost, ModalHost } from "./common/modal.js"
import { Const } from "./constants.js"

document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
        // A closing modal restores focus itself; only otherwise does the menu's trigger take it back
        const modalClosed = closeModal()
        const menuClosed = closeOpenFolderMenu({
            reason: "Escape",
            returnFocus: !modalClosed,
        })

        if (modalClosed || menuClosed) {
            event.preventDefault()
            return
        }

        document.body.dispatchEvent(
            new CustomEvent(Const.events.escapeKeyPressed, { bubbles: true }),
        )
        return
    }

    if (
        getActiveModalHost() !== ModalHost.assetDetail ||
        isTextEditingTarget(event.target)
    ) {
        return
    }

    if (event.key === "ArrowLeft") {
        document.body.dispatchEvent(new CustomEvent(Const.events.showPrevious))
    } else if (event.key === "ArrowRight") {
        document.body.dispatchEvent(new CustomEvent(Const.events.showNext))
    }
})

function isTextEditingTarget(target) {
    return Boolean(
        target?.closest?.("input, textarea, select, [contenteditable]"),
    )
}
