/**
 * Document-level keys and the nav's theme switch, on every page. The switch's click is delegated, so a
 * nav reload needs no rebinding and a page without Alpine has the switch too.
 *
 * Escape closes one thing per press, wherever focus is, and is consumed, so a background inline edit
 * survives and the browser's own Escape for popovers and menus never runs a second time. The order: in
 * fullscreen the key is the browser's; an open People combobox listbox; an open context menu (focus
 * back on its trigger); the advanced search panel; in Asset detail, the Edit panel's unsaved-changes
 * warning, then the Edit panel's own Escape, then the selected People row; the active modal or popover
 * dialog; the map's crowded-pin panel. With none open, Escape is broadcast for inline edits to cancel.
 * An open select picker keeps its Escape.
 *
 * In Asset detail, outside text fields and while the Edit panel's warning is closed: the arrows step
 * between assets and Space plays or pauses a Video, except where focus takes them itself (the player's
 * sliders, the Detail and Edit panels, the Crop tool); `m` Detail panel, `p` Face highlights, `f`
 * fullscreen, `e` Edit panel, `c` Crop tool, and in the Crop tool `o` cycles the guide and `x` swaps
 * the aspect; Ctrl/Cmd+Z Undo, Ctrl/Cmd+Shift+Z or Ctrl+Y Redo; Enter is Done while changes are held,
 * unless on a control. The plain letter keys ignore repeats and any modifier. Holding `\` shows an
 * edited asset's original. The Edit panel's state is read through `common/edit-keys.js`, so this
 * module imports no edit module.
 */
import { closeOpenContextMenu } from "./alpine/components/context-menu.js"
import {
    clearSelectedFaceRow,
    closeOpenFaceCombobox,
} from "./alpine/components/face-combobox.js"
import {
    closeLeaveDialog,
    escapeEdit,
    hasHeldEdit,
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

        // The browser leaves fullscreen on its own (Chrome never delivers the key); Asset detail
        // stays for a second Escape
        if (document.fullscreenElement) {
            return
        }

        // Ahead of `closeModal`, which would take Asset detail with it
        if (closeOpenFaceCombobox()) {
            event.preventDefault()
            return
        }

        if (
            closeOpenContextMenu({ reason: "Escape", returnFocus: true }) ||
            closeOpenSearchPanel() ||
            (getActiveModalHost() === ModalHost.assetDetail &&
                (closeLeaveDialog() ||
                    escapeEdit() ||
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
        // Keeps Space from pressing the focused toolbar control (focus is trapped in the detail);
        // on an image it does nothing
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
    } else if (isCropToolOpen() && isPlainKey(event, "o")) {
        event.preventDefault()
        announce(Const.events.cycleCropGuide)
    } else if (isCropToolOpen() && isPlainKey(event, "x")) {
        event.preventDefault()
        announce(Const.events.swapCropAspect)
    } else if (
        hasHeldEdit() &&
        event.key === "Enter" &&
        !event.repeat &&
        !event.target?.closest?.("button, a, summary, [role='button']")
    ) {
        // Done, unless a control takes the key
        event.preventDefault()
        announce(Const.events.editDone)
    }
})

function announce(eventName) {
    document.body.dispatchEvent(new CustomEvent(eventName))
}

// The release ends Show original wherever focus is and whatever modifier is held by then, so a key
// held into a field, or let go after Ctrl was pressed, never sticks
document.addEventListener("keyup", (event) => {
    if (event.key === "\\") {
        announceShowOriginal(false)
    }
})

/**
 * The key that types `\` on any layout: Shift, Option or AltGr (reported on Windows as Ctrl+Alt) are
 * allowed, Cmd or a plain Ctrl are not, since those are shortcuts. Repeats are the caller's to swallow.
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

function isModifiedKey(event, key, { shift = false } = {}) {
    return (
        event.key.toLowerCase() === key &&
        !event.repeat &&
        (event.ctrlKey || event.metaKey) &&
        !event.altKey &&
        event.shiftKey === shift
    )
}

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
 * A customizable select's open picker (`appearance: base-select`) lets Escape bubble to the document,
 * unlike a native select; the picker closes itself and the dialog holding it must stay open.
 */
function isInOpenSelectPicker(target) {
    return Boolean(
        supportsOpenPseudoClass &&
        target?.closest?.("select")?.matches(":open"),
    )
}

function isInPlayer(target) {
    return Boolean(target?.closest?.("media-controller"))
}

function isPlayerSlider(target) {
    return Boolean(target?.closest?.("media-time-range, media-volume-range"))
}

function isInDetailPanel(target) {
    return Boolean(target?.closest?.("#detailPanel, #detailPanelDivider"))
}

function isDetailDivider(target) {
    return Boolean(target?.closest?.("#detailPanelDivider"))
}

/** Edit controls that take the arrows and Space themselves (`#editSplitHandle` is Before/after's divider) */
function isInEditTools(target) {
    return Boolean(
        target?.closest?.(
            "#editPanel, #editPanelDivider, #editSplitHandle, #cropLayer, #editBar",
        ),
    )
}
