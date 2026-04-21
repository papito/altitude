/**
 * Default action of the ESC key is to close the modal
 */
import { closeModal } from "./common/modal.js"
import { Const } from "./constants.js"

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

        const escapeKeyPressed = new CustomEvent(
            Const.events.escapeKeyPressed,
            {
                bubbles: true,
            },
        )
        document.body.dispatchEvent(escapeKeyPressed)
    }

    if (evt.key === "ArrowLeft") {
        const showPreviousEvent = new CustomEvent(Const.events.showPrevious)
        document.body.dispatchEvent(showPreviousEvent)
    } else if (evt.key === "ArrowRight") {
        const showNextEvent = new CustomEvent(Const.events.showNext)
        document.body.dispatchEvent(showNextEvent)
    }
}
