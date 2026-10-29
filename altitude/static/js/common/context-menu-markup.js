/**
 * The markup of the ⋯ menus for folders, albums, and Locations, their Add buttons, and the inline
 * ⚙ View settings control.
 *
 * A menu is a native `popover="auto"` panel next to its ⋯ trigger, built by `buildContextMenuCtrl`
 * for the folder tree (`common/folder-tree.js`), the album list (`common/album-list.js`) and the
 * Location list (`common/location-list.js`), and coordinated by the `contextMenu` Alpine component
 * (`alpine/components/context-menu.js`), which also closes a menu from outside its component. The
 * browser owns menu visibility: it is read from `:popover-open` and changed only through the
 * popover API, never through inline styles.
 *
 * The menus' actions and the Add buttons open popover dialogs, placed below the trigger that
 * summoned them (`common/modal.js`); Add location, whose map editor needs the room, opens a modal.
 * Every trigger built here carries `anchor-name: --<its id>` for that placement.
 */
import { ModalHost } from "./modal.js"

/**
 * Builds a menu cell: the ⋯ trigger button and the native popover panel it opens, holding the
 * action buttons. Each action requests its dialog into the popover dialog host; opening the dialog
 * closes the menu through `common/modal.js`. The stable trigger is also the dialog's anchor.
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
        _requestPopoverDialogOnClick(actionEl, {
            url,
            vals,
            anchorId: triggerId,
        })
        actionEl.textContent = label
        actionsEl.appendChild(actionEl)
    })
    panelEl.appendChild(actionsEl)

    menuCtrlEl.appendChild(btnEl)
    menuCtrlEl.appendChild(panelEl)
    return menuCtrlEl
}

/**
 * Builds an Add button whose dialog takes focus back to it on close: a popover dialog anchored
 * below the button (`host` `ModalHost.popover`, the default), or a centered modal (`host`
 * `ModalHost.general`). Any other host is an error.
 */
export function buildModalTriggerCtrl({
    triggerId,
    label,
    iconClass,
    url,
    vals = {},
    buttonClass,
    host = ModalHost.popover,
}) {
    const btnEl = _buildLabeledButton({
        triggerId,
        label,
        iconClass,
        buttonClass,
    })
    if (host === ModalHost.popover) {
        _requestPopoverDialogOnClick(btnEl, { url, vals, anchorId: triggerId })
    } else if (host === ModalHost.general) {
        _requestDialogOnClick(btnEl, { url, target: "#modalContent", vals })
    } else {
        throw new Error(`Unsupported dialog host for ${triggerId}: ${host}`)
    }
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
    const btnEl = _buildButton(triggerId)
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
    const btnEl = _buildButton(id)
    btnEl.setAttribute("popovertarget", panelId)
    btnEl.setAttribute("x-ref", "trigger")
    return btnEl
}

/**
 * A trigger button, named as an anchor after its ID: a popover dialog's request names the ID
 * (`data-app-dialog-anchor`) and the host is placed against `--<id>`. The IDs are stable across
 * rebuilds, so a trigger rendered again anchors an open dialog again.
 */
function _buildButton(id) {
    const btnEl = document.createElement("button")
    btnEl.type = "button"
    btnEl.id = id
    btnEl.style.anchorName = `--${id}`
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

// Every presentation uses the same HTMX request attributes; only the target host differs.
function _requestDialogOnClick(el, { url, target, vals = {} }) {
    el.setAttribute("hx-get", url)
    el.setAttribute("hx-target", target)
    el.setAttribute("hx-swap", "innerHTML")
    el.setAttribute("hx-trigger", "click")
    el.setAttribute("hx-vals", JSON.stringify(vals))
}

// A popover dialog's request also names the trigger the dialog is placed below
function _requestPopoverDialogOnClick(el, { url, vals, anchorId }) {
    _requestDialogOnClick(el, { url, target: "#popoverContent", vals })
    el.dataset.appDialogAnchor = anchorId
}
