/**
 * The markup of the ⋯ menus for folders, albums, and Locations, their Add buttons, the inline
 * ⚙ View settings control, and the choice menus (the grid toolbar's Group and Sort).
 *
 * A menu is a native `popover="auto"` panel next to its trigger, built by `buildContextMenuCtrl`
 * for the folder tree (`common/folder-tree.js`), the album list (`common/album-list.js`) and the
 * Location list (`common/location-list.js`), by `buildDialogTriggerCtrl` for ⚙ View and by
 * `buildChoiceMenuCtrl` for a choice menu (`common/choice-menus.js`), and coordinated by the
 * `contextMenu` Alpine component (`alpine/components/context-menu.js`), which also closes a menu
 * from outside its component. The browser owns menu visibility: it is read from `:popover-open`
 * and changed only through the popover API, never through inline styles.
 *
 * The menus' actions and the Add buttons open popover dialogs, placed below the trigger that
 * summoned them (`common/modal.js`); Add location, whose map editor needs the room, opens a modal.
 * A choice menu's options are search triggers (`search-results/search-triggers.js`), or links (the
 * nav's Library menu), and open nothing; its actions after a separator open dialogs as a ⋯ menu's
 * do. Every trigger built here carries `anchor-name: --<its id>` for that placement.
 */
import { ModalHost } from "./modal.js"

/**
 * The cell of an explorer row's ⋯ menu for an account that holds none of its actions (every action
 * of a folder's, an album's or a Location's menu is Organize's): empty, so the row's other cells
 * keep their grid columns
 */
export function buildNoMenuCell() {
    const cellEl = document.createElement("span")
    cellEl.className = "no-menu"
    return cellEl
}

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
 * the click never shows an empty box. The root listens for the load's htmx events, so the
 * component can drop a response the panel no longer waits for. The panel opens right below the button, centered on it
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

    // htmx dispatches the request events on the button, the element that issued the request, and
    // the settle event on the dialog host, so the root hears all of them; the panel, the button's
    // sibling, would hear only the settle
    rootEl.setAttribute("x-on:htmx:before:request", "handleBeforeRequest")
    rootEl.setAttribute("x-on:htmx:after:request", "handleAfterRequest")
    rootEl.setAttribute("x-on:htmx:after:settle", "handleAfterSettle")

    const panelEl = _buildPanel({ panelId })
    panelEl.classList.add("dialog-only")

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

/**
 * Turns a choice menu host into the control, in place. The host (a `[data-choice-menu]` element the
 * server renders, see `includes/search_results.scala.html` and `includes/library_menu.scala.html`)
 * names the menu (`data-choice-menu`), its icon (`data-choice-menu-icon`), whether it is disabled
 * (`data-choice-menu-disabled`) and the trigger's class (`data-choice-menu-trigger-class`, else
 * `action-button small`), and holds one element per option, the current one
 * `aria-checked="true"`: a `button` carrying its own search trigger (Group, Sort), or a link
 * (`a[href]`, the nav's Library menu), which the browser follows. The control is a trigger showing
 * the menu's name and the current choice, with the checked option's `title` (a long name's whole
 * text), and the panel the options move into, a `role="menu"` of `menuitemradio` items. A
 * separator (`hr`) may follow the options, and after it the menu's actions: plain `menuitem`
 * buttons, never checked, each requesting a popover dialog (`hx-get` into `#popoverContent`) that
 * opens below the trigger, as a ⋯ menu's actions do. The host keeps its ID and gets the component
 * root as its only child; the trigger and panel IDs derive from the host's (`fooHost` → `fooBtn`,
 * `foo`), so they are stable across rebuilds. Picking an option or an action closes the menu
 * (`data-menu-kind="choice"`, read by the component); the search an option runs then replaces the
 * whole control with one showing the new choice, and a link loads another page.
 *
 * A compact host (`data-choice-menu-compact`, the explorer's Dock side menu) has no icon of its
 * own: its options each carry a `.glyph`, and the trigger shows the checked option's glyph alone,
 * with no name, choice text or caret; its `aria-label` and `title` name the menu and the choice,
 * "Dock side: Left", so the choice is read without opening it. Such a menu is updated on the
 * client: whoever takes the pick re-checks the options and gives the trigger the new glyph and
 * name (`js/common/explorer-dock.js`).
 *
 * A choice-only host (`data-choice-menu-choice-only`, the nav's Library menu) sits beside a label
 * the page shows on its own, the nav's Browse link: its trigger shows the current choice and the
 * caret, with no icon or name, and its `aria-label` and `title` name the menu and the choice in
 * full, "Library: Personal".
 */
export function buildChoiceMenuCtrl(hostEl) {
    const hostId = hostEl.id
    const baseId = hostId.endsWith("Host") ? hostId.slice(0, -4) : hostId
    const triggerId = `${baseId}Btn`
    const panelId = baseId
    const name = hostEl.dataset.choiceMenu
    const itemEls = Array.from(hostEl.children)
    const separatorEl = itemEls.find((itemEl) => itemEl.matches("hr"))
    const optionEls = separatorEl
        ? itemEls.slice(0, itemEls.indexOf(separatorEl))
        : itemEls
    const actionEls = separatorEl
        ? itemEls.slice(itemEls.indexOf(separatorEl) + 1)
        : []
    const checkedEl = optionEls.find(
        (optionEl) => optionEl.getAttribute("aria-checked") === "true",
    )

    const rootEl = _buildComponentRoot("choice-menu-ctrl")
    rootEl.dataset.menuAlign = "center"
    rootEl.dataset.menuKind = "choice"

    const btnEl = _buildTrigger({ id: triggerId, panelId })
    btnEl.className =
        hostEl.dataset.choiceMenuTriggerClass ?? "action-button small"
    btnEl.setAttribute("aria-haspopup", "menu")
    btnEl.disabled = "choiceMenuDisabled" in hostEl.dataset

    if ("choiceMenuCompact" in hostEl.dataset) {
        // The glyph of the checked option, else of the first, is the trigger's whole content
        btnEl.classList.add("compact")
        const shownEl = checkedEl ?? optionEls[0]
        const label = `${name}: ${shownEl?.textContent.trim() ?? ""}`
        btnEl.setAttribute("aria-label", label)
        btnEl.title = label
        const glyphEl = shownEl?.querySelector(".glyph")
        if (glyphEl) {
            btnEl.append(glyphEl.cloneNode(true))
        }
    } else if ("choiceMenuChoiceOnly" in hostEl.dataset) {
        // The choice and the caret alone, beside a label the page shows on its own (the nav's Browse link); the
        // `aria-label` names the menu, so the trigger reads as more than the choice
        const label = `${name}: ${checkedEl?.title || checkedEl?.textContent.trim() || ""}`
        btnEl.setAttribute("aria-label", label)
        btnEl.title = label
        btnEl.append(_buildChoice(checkedEl), _buildChoiceCaret())
    } else {
        const iconEl = document.createElement("i")
        iconEl.className = hostEl.dataset.choiceMenuIcon
        iconEl.setAttribute("aria-hidden", "true")
        const nameEl = document.createElement("span")
        nameEl.className = "choice-name"
        nameEl.textContent = name
        if (checkedEl?.title) {
            btnEl.title = checkedEl.title
        }
        btnEl.append(
            iconEl,
            nameEl,
            _buildChoice(checkedEl),
            _buildChoiceCaret(),
        )
    }

    const panelEl = _buildPanel({ panelId })
    panelEl.setAttribute("role", "menu")
    panelEl.setAttribute("aria-labelledby", triggerId)
    panelEl.setAttribute("x-on:click", "handleActionClick")

    const actionsEl = document.createElement("div")
    actionsEl.className = "actions"
    optionEls.forEach((optionEl) => {
        optionEl.setAttribute("role", "menuitemradio")
        if (optionEl !== checkedEl) {
            optionEl.setAttribute("aria-checked", "false")
        }
        actionsEl.appendChild(optionEl)
    })
    if (separatorEl) {
        actionsEl.appendChild(separatorEl)
    }
    actionEls.forEach((actionEl) => {
        actionEl.setAttribute("role", "menuitem")
        actionEl.dataset.appDialogAnchor = triggerId
        actionsEl.appendChild(actionEl)
    })
    panelEl.appendChild(actionsEl)

    rootEl.append(btnEl, panelEl)
    hostEl.replaceChildren(rootEl)
    return rootEl
}

/** A choice menu trigger's text of the current choice, the checked option's */
function _buildChoice(checkedEl) {
    const choiceEl = document.createElement("span")
    choiceEl.className = "choice"
    choiceEl.textContent = checkedEl?.textContent.trim() ?? ""
    return choiceEl
}

function _buildChoiceCaret() {
    const caretEl = document.createElement("i")
    caretEl.className = "fas fa-caret-down choice-caret"
    caretEl.setAttribute("aria-hidden", "true")
    return caretEl
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
 * The popover panel shared by action menus, View settings and choice menus. The panel is focusable
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
    panelEl.setAttribute("x-on:keydown", "handleKeydown")
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
