/**
 * Document-level keyboard handling, loaded on every page.
 *
 * Escape closes the active modal and is consumed by it, so a background inline edit (the person
 * name editor) survives; with no modal open, Escape is broadcast for such editors to cancel.
 * Arrow keys navigate between assets only while asset detail is active and no text is being edited.
 */
import { closeModal, getActiveModalHost, ModalHost } from "./common/modal.js"
import { Const } from "./constants.js"

document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
        if (closeModal()) {
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
