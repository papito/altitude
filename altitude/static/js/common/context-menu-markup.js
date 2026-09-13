/**
 * The markup of the ⋯ menus for folders, albums, and Locations, their modal-opening Add buttons,
 * and the inline ⚙ View settings control.
 *
 * A menu is a native `popover="auto"` panel next to its ⋯ trigger, built by `buildContextMenuCtrl`
 * for the folder tree (`common/folder-tree.js`) and the album list (`common/album-list.js`), and
 * coordinated by the `contextMenu` Alpine component (`alpine/components/context-menu.js`), which
 * also closes a menu from outside its component. The browser owns menu visibility: it is read from
 * `:popover-open` and changed only through the popover API, never through inline styles.
 */

/**
 * Builds a menu cell: the ⋯ trigger button and the native popover panel it opens, holding the
 * action buttons. Each action requests its dialog into the shared modal host; opening the modal
 * closes the menu through `common/modal.js`.
 *
 * `triggerId` and `panelId` must be stable across rebuilds: rebuilds restore focus by
 * ID, and the dialogs declare the trigger as their
 * return-focus control. `entityAttr`/`entityId` (a `data-*` attribute) tag the trigger and panel
 * with the folder or album they belong to.
 *
 * The cell is the `contextMenu` component's root, so the panel is a DOM child of the cell and
 * never of another entity's panel; see the component for why that matters.
 */
export function buildContextMenuCtrl({
    triggerId,
    panelId,
    ariaLabel,
    entityAttr,
    entityId,
    actions,
}) {
    const menuCtrlEl = _buildComponentRoot("menu-ctrl")

    const btnEl = _buildTrigger({ id: triggerId, panelId })
    btnEl.className = "menu-trigger"
    btnEl.setAttribute(entityAttr, entityId)
    btnEl.setAttribute("aria-label", ariaLabel)
    btnEl.textContent = "⋯"

    const panelEl = _buildPanel({ panelId })
    panelEl.setAttribute(entityAttr, entityId)

    const actionsEl = document.createElement("div")
    actionsEl.className = "actions"
    actions.forEach(({ label, url, vals }) => {
        const actionEl = document.createElement("button")
        actionEl.type = "button"
        _requestDialogOnClick(actionEl, { url, target: "#modalContent", vals })
        actionEl.textContent = label
        actionsEl.appendChild(actionEl)
    })
    panelEl.appendChild(actionsEl)

    menuCtrlEl.appendChild(btnEl)
    menuCtrlEl.appendChild(panelEl)
    return menuCtrlEl
}

/** Builds an Add button that loads a separate modal and remains available for focus on close. */
export function buildModalTriggerCtrl({
    triggerId,
    label,
    iconClass,
    url,
    vals = {},
    buttonClass,
}) {
    const btnEl = _buildLabeledButton({
        triggerId,
        label,
        iconClass,
        buttonClass,
    })
    _requestDialogOnClick(btnEl, { url, target: "#modalContent", vals })
    return btnEl
}

/**
 * Builds a control whose button opens a panel holding one inline dialog and nothing else (the
 * ⚙ View settings button). The click both toggles the panel (`popovertarget`) and requests the dialog
 * into the panel's host; the `dialog-only` panel stays invisible until the dialog has arrived, so
 * the click never shows an empty box. The panel opens right below the button, centered on it
 * (`data-menu-align="center"`), and the dialog returns focus to the button.
 */
export function buildDialogTriggerCtrl({
    triggerId,
    panelId,
    dialogId,
    label,
    iconClass,
    url,
    vals = {},
    buttonClass,
}) {
    const rootEl = _buildComponentRoot("dialog-trigger-ctrl")
    rootEl.dataset.menuAlign = "center"

    const btnEl = _buildLabeledButton({
        triggerId,
        label,
        iconClass,
        buttonClass,
    })
    btnEl.setAttribute("popovertarget", panelId)
    btnEl.setAttribute("x-ref", "trigger")
    _requestDialogOnClick(btnEl, { url, target: `#${dialogId}`, vals })

    const panelEl = _buildPanel({ panelId })
    panelEl.classList.add("dialog-only")
    panelEl.setAttribute("x-on:htmx:before:request", "handleBeforeRequest")
    panelEl.setAttribute("x-on:htmx:after:request", "handleAfterRequest")
    panelEl.setAttribute("x-on:htmx:after:settle", "handleAfterSettle")

    const dialogEl = document.createElement("div")
    dialogEl.className = "dialog"
    dialogEl.id = dialogId
    dialogEl.setAttribute("x-ref", "dialog")
    dialogEl.hidden = true
    panelEl.appendChild(dialogEl)

    rootEl.appendChild(btnEl)
    rootEl.appendChild(panelEl)
    return rootEl
}

function _buildLabeledButton({ triggerId, label, iconClass, buttonClass }) {
    const btnEl = document.createElement("button")
    btnEl.type = "button"
    btnEl.id = triggerId
    btnEl.className = buttonClass

    const iconEl = document.createElement("i")
    iconEl.className = iconClass
    iconEl.setAttribute("aria-hidden", "true")
    const labelEl = document.createElement("span")
    labelEl.textContent = label
    btnEl.appendChild(iconEl)
    btnEl.appendChild(labelEl)
    return btnEl
}

function _buildComponentRoot(className) {
    const rootEl = document.createElement("div")
    rootEl.className = className
    rootEl.setAttribute("x-data", "contextMenu")
    rootEl.setAttribute("x-on:focusout", "handleFocusOut")
    return rootEl
}

function _buildTrigger({ id, panelId }) {
    const btnEl = document.createElement("button")
    btnEl.type = "button"
    btnEl.id = id
    btnEl.setAttribute("popovertarget", panelId)
    btnEl.setAttribute("x-ref", "trigger")
    return btnEl
}

/**
 * The popover panel shared by action menus and View settings. The panel is focusable
 * (`tabindex="-1"`) so a click on non-interactive dialog content keeps focus inside it, and so a
 * dialog can rest focus on the panel itself.
 */
function _buildPanel({ panelId }) {
    const panelEl = document.createElement("div")
    panelEl.className = "context-menu"
    panelEl.id = panelId
    panelEl.setAttribute("popover", "auto")
    panelEl.setAttribute("tabindex", "-1")
    panelEl.setAttribute("x-ref", "panel")
    panelEl.setAttribute("x-on:beforetoggle", "handleBeforeToggle")
    panelEl.setAttribute("x-on:toggle", "handleToggle")
    return panelEl
}

// Both presentations use the same HTMX request attributes; only the target host differs.
function _requestDialogOnClick(el, { url, target, vals = {} }) {
    el.setAttribute("hx-get", url)
    el.setAttribute("hx-target", target)
    el.setAttribute("hx-swap", "innerHTML")
    el.setAttribute("hx-trigger", "click")
    el.setAttribute("hx-vals", JSON.stringify(vals))
}
