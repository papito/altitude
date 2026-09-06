/**
 * Closing folder context menus from outside their component.
 *
 * Each folder's menu is a native `popover="auto"` panel, built with the tree by
 * `common/folder-tree.js` and coordinated by the `folderMenu` Alpine component
 * (`alpine/components/folder-menu.js`). The browser owns menu visibility: it is read from
 * `:popover-open` and changed only through the popover API, never through inline styles.
 *
 * Callers outside the component that must close a menu (the document-level Escape handler in
 * `global.js`, the folder model when an ancestor collapses) go through `closeOpenFolderMenu()`;
 * an inline dialog that completed its operation (`fragments/inline-dialog.js`) closes its own
 * panel through `closeFolderMenu()`.
 * Because at most one auto popover of this kind is open at a time, "the open menu" is a single
 * panel.
 */
import { Const } from "../constants.js"

const OPEN_MENU_SELECTOR = ".folder-menu:popover-open"

/**
 * The meatball control that opens `panel`. Dialogs return focus to this control by the same ID
 * convention (`data-app-dialog-return-focus="#folderMenuCtrl-<id>"`).
 */
export function getFolderMenuTrigger(panel) {
    const folderId = panel.getAttribute(Const.attributes.folderId)

    return document.getElementById(`folderMenuCtrl-${folderId}`)
}

/**
 * Hides `panel` if it is open, optionally moving focus to `focusTarget`, by default the panel's
 * trigger (a completed dialog operation names the control that survives it instead). Returns
 * whether a menu was closed. `reason` is logged so a surprising dismissal can be traced.
 */
export function closeFolderMenu(
    panel,
    { reason, returnFocus = false, focusTarget = null },
) {
    if (!panel?.matches(":popover-open")) {
        return false
    }

    const folderId = panel.getAttribute(Const.attributes.folderId)
    console.debug(`Closing folder menu for ${folderId}: ${reason}`)

    panel.hidePopover()

    if (returnFocus) {
        const target = focusTarget ?? getFolderMenuTrigger(panel)
        target?.focus()
    }

    return true
}

/**
 * Closes the open folder menu, if any, looking only inside `within`. Returns whether one was
 * closed.
 */
export function closeOpenFolderMenu({
    reason,
    returnFocus = false,
    within = document,
} = {}) {
    return closeFolderMenu(within.querySelector(OPEN_MENU_SELECTOR), {
        reason,
        returnFocus,
    })
}
