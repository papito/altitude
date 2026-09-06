/**
 * The ⋯ context menu of a folder or an album: how it is built, and how it is closed from outside
 * its component.
 *
 * A menu is a native `popover="auto"` panel next to its ⋯ trigger, built by `buildContextMenuCtrl`
 * for the folder tree (`common/folder-tree.js`) and the album list (`common/album-list.js`), and
 * coordinated by the `contextMenu` Alpine component (`alpine/components/context-menu.js`). The
 * browser owns menu visibility: it is read from `:popover-open` and changed only through the
 * popover API, never through inline styles.
 *
 * Callers outside the component that must close a menu (the document-level Escape handler in
 * `global.js`, the folder model when an ancestor collapses, the modal owner) go through
 * `closeOpenContextMenu()`; an inline dialog that completed its operation
 * (`fragments/inline-dialog.js`) closes its own panel through `closeContextMenu()`. Because at
 * most one auto popover of this kind is open at a time, "the open menu" is a single panel.
 */

const OPEN_MENU_SELECTOR = ".context-menu:popover-open"

/** The ⋯ control that opens `panel`: the button in the same menu cell that targets it. */
export function getContextMenuTrigger(panel) {
    return panel.parentElement?.querySelector(`[popovertarget="${panel.id}"]`)
}

/**
 * Hides `panel` if it is open, optionally moving focus to `focusTarget`, by default the panel's
 * trigger (a completed dialog operation names the control that survives it instead). Returns
 * whether a menu was closed. `reason` is logged so a surprising dismissal can be traced.
 */
export function closeContextMenu(
    panel,
    { reason, returnFocus = false, focusTarget = null },
) {
    if (!panel?.matches(":popover-open")) {
        return false
    }

    console.debug(`Closing context menu ${panel.id}: ${reason}`)

    panel.hidePopover()

    if (returnFocus) {
        const target = focusTarget ?? getContextMenuTrigger(panel)
        target?.focus()
    }

    return true
}

/**
 * Closes the open context menu, if any, looking only inside `within`. Returns whether one was
 * closed.
 */
export function closeOpenContextMenu({
    reason,
    returnFocus = false,
    within = document,
} = {}) {
    return closeContextMenu(within.querySelector(OPEN_MENU_SELECTOR), {
        reason,
        returnFocus,
    })
}

/**
 * Builds a menu cell: the ⋯ trigger button and the native popover panel it opens, holding the
 * action buttons and an empty dialog host. Each action is an HTMX request for its dialog into
 * that host; the panel then shows the dialog in place of the actions until it closes.
 *
 * `triggerId`, `panelId`, and `dialogId` must be stable across rebuilds: rebuilds restore focus by
 * ID, the actions target the dialog host, and the dialogs declare the trigger as their
 * return-focus control. `entityAttr`/`entityId` (an `alt-*` attribute) tag the trigger and panel
 * with the folder or album they belong to. The panel is focusable (`tabindex="-1"`) so a click on
 * non-interactive dialog content keeps focus inside it, and so a dialog can rest focus on the
 * panel itself.
 *
 * The cell is the `contextMenu` component's root, so the panel is a DOM child of the cell and
 * never of another entity's panel; see the component for why that matters.
 */
export function buildContextMenuCtrl({
    triggerId,
    panelId,
    dialogId,
    ariaLabel,
    entityAttr,
    entityId,
    actions,
}) {
    const menuCtrlEl = document.createElement("div")
    menuCtrlEl.className = "menu-ctrl"
    menuCtrlEl.setAttribute("x-data", "contextMenu")
    menuCtrlEl.setAttribute("x-on:focusout", "handleFocusOut")

    const btnEl = document.createElement("button")
    btnEl.type = "button"
    btnEl.id = triggerId
    btnEl.setAttribute(entityAttr, entityId)
    btnEl.setAttribute("popovertarget", panelId)
    btnEl.setAttribute("aria-label", ariaLabel)
    btnEl.setAttribute("x-ref", "trigger")
    btnEl.textContent = "⋯"

    const panelEl = document.createElement("div")
    panelEl.className = "context-menu"
    panelEl.id = panelId
    panelEl.setAttribute("popover", "auto")
    panelEl.setAttribute("tabindex", "-1")
    panelEl.setAttribute(entityAttr, entityId)
    panelEl.setAttribute("x-ref", "panel")
    panelEl.setAttribute("x-on:beforetoggle", "handleBeforeToggle")
    panelEl.setAttribute("x-on:toggle", "handleToggle")
    panelEl.setAttribute("x-on:htmx:before:request", "handleBeforeRequest")
    panelEl.setAttribute("x-on:htmx:after:request", "handleAfterRequest")
    panelEl.setAttribute("x-on:htmx:after:settle", "handleAfterSettle")

    const actionsEl = document.createElement("div")
    actionsEl.className = "actions"
    actionsEl.setAttribute("x-ref", "actions")
    actions.forEach(({ label, url, vals }) => {
        const actionEl = document.createElement("button")
        actionEl.type = "button"
        actionEl.setAttribute("hx-get", url)
        actionEl.setAttribute("hx-target", `#${dialogId}`)
        actionEl.setAttribute("hx-swap", "innerHTML")
        actionEl.setAttribute("hx-trigger", "click")
        actionEl.setAttribute("hx-vals", JSON.stringify(vals))
        actionEl.textContent = label
        actionsEl.appendChild(actionEl)
    })

    const dialogEl = document.createElement("div")
    dialogEl.className = "dialog"
    dialogEl.id = dialogId
    dialogEl.setAttribute("x-ref", "dialog")
    dialogEl.hidden = true

    panelEl.appendChild(actionsEl)
    panelEl.appendChild(dialogEl)

    menuCtrlEl.appendChild(btnEl)
    menuCtrlEl.appendChild(panelEl)
    return menuCtrlEl
}
