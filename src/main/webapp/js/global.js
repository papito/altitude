/**
 * Default action of the ESC key is to close the modal
 */
import { closeModal } from "./common/modal.js"

document.onkeydown = function (evt) {
    evt = evt || window.event
    let isEscape
    if ("key" in evt) {
        isEscape = evt.key === "Escape" || evt.key === "Esc"
    } else {
        isEscape = evt.keyCode === 27
    }
    if (isEscape) {
        closeModal()
    }
}
