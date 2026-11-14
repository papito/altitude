/**
 * Document-level keyboard handling and the nav's theme switch, loaded on every page. The switch's
 * click is delegated, so a nav reload needs no binding and a page without Alpine has the switch too.
 *
 * Escape closes one thing at a time, wherever focus is, and is consumed by it, so a background
 * inline edit (the person name editor) survives: in fullscreen it is the browser's, which leaves
 * fullscreen (and in Chrome never delivers the key), and Asset detail stays open; else the open
 * listbox of a People row's Change combobox in the Detail panel closes alone; else an open context
 * menu, with focus back on its trigger (the Detail panel's Dock side menu is the one menu that
 * opens inside a dialog, and outside a dialog no menu and dialog are open together); else the nav's
 * advanced search panel, with focus back on its filter button (an open listbox or calendar inside it
 * has consumed the key before it gets here: `filter-combobox.js`, `date-range.js`); else, in
 * Asset detail, the selected People row, which clears; else the active modal or popover dialog; else the map view's crowded-pin panel; and with none of those
 * open, Escape is broadcast for such editors to cancel. Consuming it also keeps the browser's own
 * Escape handling for the popover dialog and the menus from running a second time. An open select
 * picker's Escape is left to the picker, which closes itself and nothing else.
 * While asset detail is active and no text is being edited, the arrow keys navigate between assets,
 * except on the video player's timeline and volume slider, which they step, and on the Detail
 * panel's divider, which they move; Space plays or pauses a Video and does nothing on an image,
 * except on a control in the player or in the Detail panel, which it presses; `m` opens or
 * closes the Detail panel, `p` shows or hides the Face highlights and `f` puts Asset detail in
 * fullscreen or leaves it, wherever focus is, each ignoring a held key and any modifier. `\` shows
 * an edited asset's original for as long as it is held (Show original): its press and its release
 * are announced, the key's repeats swallowed, and a release is announced wherever focus has gone.
 *
 * The Edit panel's keys (`listeners/edit.js`), in Asset detail and never in a text field: `e` opens
 * or closes the panel, `c` opens its Crop tool or, in it, is Done; in the Crop tool `o` cycles the
 * guide, `x` swaps the aspect's orientation and Enter is Done (on a control, Enter presses it);
 * Ctrl/Cmd+Z is Undo and Ctrl/Cmd+Shift+Z or Ctrl+Y Redo. Escape closes the Edit panel's warning of
 * changes not saved (Keep editing), then leaves the Crop tool (Cancel, or the warning when its
 * session changed the Edit), ahead of the selected People row and the dialog, whose close the
 * warning may hold; while the warning is open, Asset detail's keys are left to it. The arrows and Space are
 * left to focus in the Edit panel, on its divider and in the Crop tool, as in the Detail panel. The warning's and the Crop
 * tool's part is read through `common/edit-keys.js`, which the Edit panel registers on the page that holds Asset detail, so
 * this module, on every page, imports no edit module.
 */
import { closeOpenContextMenu } from "./alpine/components/context-menu.js"
import {
    clearSelectedFaceRow,
    closeOpenFaceCombobox,
} from "./alpine/components/face-combobox.js"
import {
    closeLeaveDialog,
    escapeOpenCropTool,
    isCropToolOpen,
    isLeaveDialogOpen,
} from "./common/edit-keys.js"
import { closeModal, getActiveModalHost, ModalHost } from "./common/modal.js"
import { isTextEditingTarget } from "./common/text-editing.js"
import { toggleTheme } from "./common/theme.js"
import { Const } from "./constants.js"
import { closeMapPanel } from "./map/map-panel.js"
import { closeOpenSearchPanel } from "./search-results/search-panel.js"

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

        // The browser leaves fullscreen (Asset detail's `f`) on its own; the dialog stays for a
        // second Escape
        if (document.fullscreenElement) {
            return
        }

        // The Change combobox's listbox closes alone, before `closeModal` could take Asset detail
        // with it; focus is in its input already
        if (closeOpenFaceCombobox()) {
            event.preventDefault()
            return
        }

        // A menu closes alone, with focus back on its trigger, so the Detail panel's Dock side menu
        // leaves Asset detail open; the advanced search panel closes the same way, the browser
        // returning focus to its filter button; so does a selected Face's row in its People section,
        // and a closing modal or popover dialog restores focus itself
        // In Asset detail, the Edit panel's warning closes alone (Keep editing), then the Crop tool is
        // left (Cancel, or the warning), before the selected Face and the dialog
        if (
            closeOpenContextMenu({ reason: "Escape", returnFocus: true }) ||
            closeOpenSearchPanel() ||
            (getActiveModalHost() === ModalHost.assetDetail &&
                (closeLeaveDialog() ||
                    escapeOpenCropTool() ||
                    clearSelectedFaceRow())) ||
            closeModal() ||
            closeMapPanel()
        ) {
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
        isTextEditingTarget(event.target) ||
        isLeaveDialogOpen()
    ) {
        return
    }

    // The player's timeline and volume slider step with the arrows themselves, the Detail panel's
    // divider moves with them, and so do the Edit panel's controls, its divider and the Crop tool
    const stepsWithArrows =
        isPlayerSlider(event.target) ||
        isDetailDivider(event.target) ||
        isInEditTools(event.target)

    if (event.key === "ArrowLeft" && !stepsWithArrows) {
        document.body.dispatchEvent(new CustomEvent(Const.events.showPrevious))
    } else if (event.key === "ArrowRight" && !stepsWithArrows) {
        document.body.dispatchEvent(new CustomEvent(Const.events.showNext))
    } else if (
        event.key === " " &&
        !isInPlayer(event.target) &&
        !isInDetailPanel(event.target) &&
        !isInEditTools(event.target)
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
    } else if (isPlainKey(event, "m")) {
        event.preventDefault()
        document.body.dispatchEvent(
            new CustomEvent(Const.events.toggleDetailPanel),
        )
    } else if (isPlainKey(event, "p")) {
        event.preventDefault()
        document.body.dispatchEvent(
            new CustomEvent(Const.events.toggleFaceHighlights),
        )
    } else if (isPlainKey(event, "f")) {
        event.preventDefault()
        document.body.dispatchEvent(
            new CustomEvent(Const.events.toggleFullscreen),
        )
    } else if (isShowOriginalPress(event)) {
        event.preventDefault()
        if (!event.repeat) {
            announceShowOriginal(true)
        }
    } else if (isPlainKey(event, "e")) {
        event.preventDefault()
        announce(Const.events.toggleEditPanel)
    } else if (isPlainKey(event, "c")) {
        event.preventDefault()
        announce(Const.events.toggleCropTool)
    } else if (isModifiedKey(event, "z")) {
        event.preventDefault()
        announce(Const.events.undoEdit)
    } else if (
        isModifiedKey(event, "z", { shift: true }) ||
        (isModifiedKey(event, "y") && event.ctrlKey)
    ) {
        event.preventDefault()
        announce(Const.events.redoEdit)
    } else if (isCropToolOpen()) {
        handleCropToolKey(event)
    }
})

/** The Crop tool's own keys: `o` the guide, `x` the aspect's orientation, Enter Done unless a control takes it */
function handleCropToolKey(event) {
    if (isPlainKey(event, "o")) {
        event.preventDefault()
        announce(Const.events.cycleCropGuide)
    } else if (isPlainKey(event, "x")) {
        event.preventDefault()
        announce(Const.events.swapCropAspect)
    } else if (
        event.key === "Enter" &&
        !event.repeat &&
        !event.target?.closest?.("button, a, summary, [role='button']")
    ) {
        event.preventDefault()
        announce(Const.events.cropDone)
    }
}

function announce(eventName) {
    document.body.dispatchEvent(new CustomEvent(eventName))
}

// The release of `\` ends Show original wherever focus is and whatever modifier is held by then, so a
// key held into a field, or let go after Ctrl was pressed, never sticks
document.addEventListener("keyup", (event) => {
    if (event.key === "\\") {
        announceShowOriginal(false)
    }
})

/**
 * A press of the key that types `\`, however the layout reaches it: with Shift, Option or AltGr
 * (which a browser on Windows reports as Ctrl and Alt) where it needs one, never with Cmd or a
 * plain Ctrl, which are shortcuts. A held key's repeats are the caller's to swallow.
 */
function isShowOriginalPress(event) {
    const altGraph = event.getModifierState?.("AltGraph") ?? false
    return event.key === "\\" && !event.metaKey && (!event.ctrlKey || altGraph)
}

function announceShowOriginal(held) {
    document.body.dispatchEvent(
        new CustomEvent(Const.events.showOriginalHeld, { detail: { held } }),
    )
}

/**
 * `key` pressed once with Ctrl or Cmd, and Shift exactly when `shift` says (Ctrl+Z, Ctrl+Shift+Z), never Alt: a held
 * key repeats nothing
 */
function isModifiedKey(event, key, { shift = false } = {}) {
    return (
        event.key.toLowerCase() === key &&
        !event.repeat &&
        (event.ctrlKey || event.metaKey) &&
        !event.altKey &&
        event.shiftKey === shift
    )
}

/** `key` pressed on its own, once: a held key or any modifier (Ctrl+P, Shift+M) is left to the browser */
function isPlainKey(event, key) {
    return (
        event.key.toLowerCase() === key &&
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

/**
 * Focus is in the Edit panel (a slider, a tab), on its divider, on Before/after's divider or in the Crop tool (the
 * crop, which the arrows nudge, and its bar), which take the arrows and Space themselves
 */
function isInEditTools(target) {
    return Boolean(
        target?.closest?.(
            "#editPanel, #editPanelDivider, #editSplitHandle, #cropLayer",
        ),
    )
}
