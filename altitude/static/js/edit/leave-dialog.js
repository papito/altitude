/**
 * The Edit panel's warning of changes not saved (`includes/edit_panel`, `dialog#editLeaveDialog`): asked before Asset
 * detail closes or steps to another asset, the Edit panel closes, or Escape leaves the Crop tool, while a change is
 * pending (`edit-panel.js`). It is a native modal `<dialog>` inside Asset detail's fragment, so it stacks over Asset
 * detail, the one dialog host that is active, rather than replacing it as an open of another host would (`modal.js`):
 * the browser keeps focus in it, makes the rest inert and gives focus back on closing. Its choices are Save (the
 * initial focus: a safe answer), Discard (the error button) and Keep editing; Escape (`global.js`, `closeLeaveDialog`,
 * and the dialog's own `cancel`) is Keep editing.
 */
import { Const } from "../constants.js"

const DIALOG_ID = "editLeaveDialog"

/** The warning's answers */
export const LeaveChoice = Object.freeze({
    save: "save",
    discard: "discard",
    stay: "stay",
})

// The open warning's answer, and its promise, which a second ask while it is open shares
let resolveOpen = null
let openAnswer = null
let bound = false

function dialogEl() {
    return document.getElementById(DIALOG_ID)
}

/** Binds the warning's buttons and its `cancel` once per page: the dialog comes with each Asset detail fragment */
function bind() {
    if (bound) {
        return
    }
    bound = true
    document.addEventListener("click", (event) => {
        const choice = event.target
            ?.closest?.(`[${Const.attributes.editLeave}]`)
            ?.getAttribute(Const.attributes.editLeave)
        if (choice) {
            finish(choice)
        }
    })
    // `cancel` does not bubble: it is caught on its way down
    document.addEventListener(
        "cancel",
        (event) => {
            if (event.target?.id === DIALOG_ID) {
                event.preventDefault()
                finish(LeaveChoice.stay)
            }
        },
        true,
    )
}

function finish(choice) {
    const resolve = resolveOpen
    resolveOpen = null
    openAnswer = null
    const el = dialogEl()
    if (el?.open) {
        el.close()
    }
    resolve?.(choice)
}

/**
 * Asks what to do with the pending changes, saying `message`; resolves to a `LeaveChoice`. Without the dialog (a page
 * without the Edit panel) the changes are saved.
 */
export function askToLeave({ message }) {
    bind()
    const el = dialogEl()
    if (!el) {
        return Promise.resolve(LeaveChoice.save)
    }
    if (openAnswer && el.open) {
        return openAnswer
    }
    // An ask whose dialog left with its fragment is over
    if (openAnswer) {
        finish(LeaveChoice.stay)
    }
    el.querySelector(`[${Const.attributes.editLeaveMessage}]`).textContent =
        message
    openAnswer = new Promise((resolve) => (resolveOpen = resolve))
    el.showModal()
    return openAnswer
}

/** Closes the open warning as Keep editing, Escape's job ahead of anything else in Asset detail; answers whether it was open */
export function closeLeaveDialog() {
    if (!dialogEl()?.open) {
        return false
    }
    finish(LeaveChoice.stay)
    return true
}

/** Whether the warning is open: Asset detail's keys are its buttons' then (`global.js`) */
export function isLeaveDialogOpen() {
    return Boolean(dialogEl()?.open)
}
