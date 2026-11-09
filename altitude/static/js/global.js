/**
 * Document-level keyboard handling and the nav's theme switch, loaded on every page. The switch's
 * click is delegated, so a nav reload needs no binding and a page without Alpine has the switch too.
 *
 * Escape closes the active modal or popover dialog and any open context menu, wherever focus is,
 * and is consumed by them, so a background inline edit (the person name editor) survives; with
 * neither open, it closes the map view's crowded-pin panel, and with none of those open, Escape is
 * broadcast for such editors to cancel. Consuming it also keeps the browser's own Escape handling
 * for the popover dialog and the menus from running a second time. An open select picker's Escape
 * is left to the picker, which closes itself and nothing else.
 * While asset detail is active and no text is being edited, the arrow keys navigate between assets,
 * except on the video player's timeline and volume slider, which they step, and on the Detail
 * panel's divider, which they move; Space plays or pauses a Video and does nothing on an image,
 * except on a control in the player or in the Detail panel, which it presses; and `m` opens or
 * closes the Detail panel, wherever focus is, ignoring a held key and any modifier.
 */
import { closeOpenContextMenu } from "./alpine/components/context-menu.js"
import { closeModal, getActiveModalHost, ModalHost } from "./common/modal.js"
import { isTextEditingTarget } from "./common/text-editing.js"
import { toggleTheme } from "./common/theme.js"
import { Const } from "./constants.js"
import { closeMapPanel } from "./map/map-panel.js"

document.addEventListener("click", (event) => {
    if (event.target.closest?.("[data-app-theme-switch]")) {
        toggleTheme()
    }
})

document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
        if (isInOpenSelectPicker(event.target)) {
            return
        }

        // A closing modal or popover dialog restores focus itself; only otherwise does the menu's
        // trigger take it back
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

    // The player's timeline and volume slider step with the arrows themselves, and the Detail
    // panel's divider moves with them
    const stepsWithArrows =
        isPlayerSlider(event.target) || isDetailDivider(event.target)

    if (event.key === "ArrowLeft" && !stepsWithArrows) {
        document.body.dispatchEvent(new CustomEvent(Const.events.showPrevious))
    } else if (event.key === "ArrowRight" && !stepsWithArrows) {
        document.body.dispatchEvent(new CustomEvent(Const.events.showNext))
    } else if (
        event.key === " " &&
        !isInPlayer(event.target) &&
        !isInDetailPanel(event.target)
    ) {
        // Plays or pauses a Video, and does nothing on an image, instead of pressing the focused
        // toolbar control (focus is trapped in the detail). Inside the player, media-chrome plays
        // or pauses, or the focused control presses itself, and in the Detail panel the focused
        // control (a group's summary, a link, the divider) presses itself.
        event.preventDefault()
        if (!event.repeat) {
            document.body.dispatchEvent(
                new CustomEvent(Const.events.togglePlayback),
            )
        }
    } else if (isDetailPanelKey(event)) {
        event.preventDefault()
        document.body.dispatchEvent(
            new CustomEvent(Const.events.toggleDetailPanel),
        )
    }
})

/** `m` pressed on its own, once: a held key or any modifier (Ctrl+M, Shift+M) is left to the browser */
function isDetailPanelKey(event) {
    return (
        event.key.toLowerCase() === "m" &&
        !event.repeat &&
        !event.ctrlKey &&
        !event.altKey &&
        !event.metaKey &&
        !event.shiftKey
    )
}

// `matches` throws on `:open` in a browser that does not know the pseudo-class
const supportsOpenPseudoClass = CSS.supports("selector(:open)")

/**
 * Focus is in a customizable select's open picker (`appearance: base-select`), whose Escape bubbles
 * to the document, unlike a native select's: the picker closes itself, and the dialog holding the
 * select stays open.
 */
function isInOpenSelectPicker(target) {
    return Boolean(
        supportsOpenPseudoClass &&
        target?.closest?.("select")?.matches(":open"),
    )
}

/** Focus is in the asset's video player: the <video>, its controller, or one of its controls */
function isInPlayer(target) {
    return Boolean(target?.closest?.("media-controller"))
}

/** Focus is on the player's timeline or volume slider */
function isPlayerSlider(target) {
    return Boolean(target?.closest?.("media-time-range, media-volume-range"))
}

/** Focus is in the Detail panel: on its header's menu, its divider or a control of its sections */
function isInDetailPanel(target) {
    return Boolean(target?.closest?.("#detailPanel, #detailPanelDivider"))
}

/** Focus is on the Detail panel's divider, which the arrow keys move */
function isDetailDivider(target) {
    return Boolean(target?.closest?.("#detailPanelDivider"))
}
