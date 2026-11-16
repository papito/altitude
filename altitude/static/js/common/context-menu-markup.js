/**
 * The markup of the ⋯ menus, the Add buttons, the ⚙ View settings control and the choice menus. A
 * menu is a native `popover="auto"` panel coordinated by the `contextMenu` component; the browser
 * owns its visibility, read from `:popover-open` and changed only through the popover API. Every
 * trigger built here carries `anchor-name: --<its id>`, where its dialog is placed.
 */
import { ModalHost } from "./modal.js"

/** For an account that holds none of the menu's actions: empty, so the row's other cells keep their columns */
export function buildNoMenuCell() {
    const cellEl = document.createElement("span")
    cellEl.className = "no-menu"
    return cellEl
}

/**
 * An action requests its dialog into the popover dialog host (`url`, `vals`), anchored to the
 * trigger, or runs a script (`run`), which closes the menu by opening a popover of its own;
 * `{ separator: true }` is a rule. `triggerId` and `panelId` must be stable across rebuilds: focus
 * is restored by ID, and the dialogs return focus to the trigger. The cell is the component's root,
 * so the panel is never a DOM child of another entity's panel (see `contextMenu`).
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
    actions.forEach(({ label, url, vals, run, separator }) => {
        if (separator) {
            actionsEl.appendChild(document.createElement("hr"))
            return
        }

        const actionEl = document.createElement("button")
        actionEl.type = "button"
        if (run) {
            actionEl.addEventListener("click", run)
        } else {
            _requestPopoverDialogOnClick(actionEl, {
                url,
                vals,
                anchorId: triggerId,
            })
        }
        actionEl.textContent = label
        actionsEl.appendChild(actionEl)
    })
    panelEl.appendChild(actionsEl)

    menuCtrlEl.appendChild(btnEl)
    menuCtrlEl.appendChild(panelEl)
    return menuCtrlEl
}

/**
 * The dialog returns focus to the button: anchored below it (`ModalHost.popover`) or centered
 * (`ModalHost.general`)
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
 * A button whose panel holds one inline dialog. The click both toggles the panel and requests the
 * dialog into it; the `dialog-only` panel stays invisible until the dialog arrives, so the click
 * never shows an empty box.
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

    // On the root, so the component can drop a response the panel no longer waits for: htmx fires the
    // request events on the button and the settle on the dialog host, and the panel would hear only the settle
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
 * Turns a server-rendered `[data-choice-menu]` host into the control, in place: a trigger showing
 * the menu's name and current choice, and a `role="menu"` panel of `menuitemradio` options (search
 * triggers or links). Actions after an `hr` are `menuitem`s requesting popover dialogs. The trigger
 * and panel IDs derive from the host's (`fooHost` → `fooBtn`, `foo`), so they are stable across
 * rebuilds.
 *
 * A compact host shows only the checked option's `.glyph`; its pick is applied on the client by
 * whoever takes it. A choice-only host sits beside a label the page shows, so it shows the choice
 * and caret alone. Both name the menu and the choice in `aria-label` and `title`.
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
        // The `aria-label` names the menu, so the trigger reads as more than the choice
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
 * Named as an anchor after its ID, against which a popover dialog's host is placed; the IDs are
 * stable, so a trigger rendered again anchors an open dialog again
 */
function _buildButton(id) {
    const btnEl = document.createElement("button")
    btnEl.type = "button"
    btnEl.id = id
    btnEl.style.anchorName = `--${id}`
    return btnEl
}

/** Focusable, so a click on non-interactive dialog content keeps focus inside, and a dialog can rest on it */
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
