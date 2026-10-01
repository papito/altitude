/**
 * Document-level keyboard handling, loaded on every page.
 *
 * Escape closes the active modal and any open context menu, wherever focus is, and is consumed by
 * them, so a background inline edit (the person name editor) survives; with neither open, it
 * closes the map view's crowded-pin panel, and with none of those open, Escape is broadcast for
 * such editors to cancel. Consuming it also keeps the browser's own Escape handling for the
 * popover from running a second time.
 * While asset detail is active and no text is being edited, the arrow keys navigate between assets,
 * except on the video player's timeline and volume slider, which they step, and Space plays or
 * pauses a Video and does nothing on an image.
 */
import { closeOpenContextMenu } from "./alpine/components/context-menu.js"
import { closeModal, getActiveModalHost, ModalHost } from "./common/modal.js"
import { Const } from "./constants.js"
import { closeMapPanel } from "./map/map-panel.js"

document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
        // A closing modal restores focus itself; only otherwise does the menu's trigger take it back
        const modalClosed = closeModal()
        const menuClosed = closeOpenContextMenu({
            reason: "Escape",
            returnFocus: !modalClosed,
        })

        if (modalClosed || menuClosed || closeMapPanel()) {
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

    // The player's timeline and volume slider step with the arrows themselves
    if (event.key === "ArrowLeft" && !isPlayerSlider(event.target)) {
        document.body.dispatchEvent(new CustomEvent(Const.events.showPrevious))
    } else if (event.key === "ArrowRight" && !isPlayerSlider(event.target)) {
        document.body.dispatchEvent(new CustomEvent(Const.events.showNext))
    } else if (event.key === " " && !isInPlayer(event.target)) {
        // Plays or pauses a Video, and does nothing on an image, instead of pressing the focused ×
        // (focus is trapped in the detail, and the × is its only control outside the player).
        // Inside the player, media-chrome plays or pauses, or the focused control presses itself.
        event.preventDefault()
        if (!event.repeat) {
            document.body.dispatchEvent(
                new CustomEvent(Const.events.togglePlayback),
            )
        }
    }
})

/** Focus is in the asset's video player: the <video>, its controller, or one of its controls */
function isInPlayer(target) {
    return Boolean(target?.closest?.("media-controller"))
}

/** Focus is on the player's timeline or volume slider */
function isPlayerSlider(target) {
    return Boolean(target?.closest?.("media-time-range, media-volume-range"))
}

function isTextEditingTarget(target) {
    return Boolean(
        target?.closest?.("input, textarea, select, [contenteditable]"),
    )
}
