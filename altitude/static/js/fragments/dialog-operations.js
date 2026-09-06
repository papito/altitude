/**
 * Lifecycle of the operations that dialogs submit.
 *
 * A dialog is a server-rendered form completing one user action, hydrated from its
 * `data-app-fragment` kind. Its presentation (a modal host, or some other container) is the kind's
 * concern; what every kind shares is declared with `data-app-dialog-*` attributes on the fragment
 * root and handled here: the success event and detail, and whether success closes the dialog.
 *
 * Each kind registers with `registerDialogKind`, supplying a handle for the dialog a request was
 * issued from: `isActive()` tells whether that same dialog is still shown, and `close()` dismisses
 * it after a successful operation.
 *
 * An operation is tracked from `htmx:before:request` so that everything it needs later (the handle
 * of the dialog it belongs to, its success event and detail) is captured while the dialog is still
 * in the DOM. Its response is handled from `htmx:after:request` regardless of whether the dialog
 * has since been closed or replaced: page updates and success events always happen, but only the
 * still active initiating dialog may be closed or have its form replaced by the response.
 */
import { Const } from "../constants.js"
import {
    getRequestPath,
    getResponseRetarget,
    getResponseStatus,
    getResponseText,
    isRequestSuccessful,
} from "../common/htmx-events.js"
import { showErrorSnackBar, showWarningSnackBar } from "../common/snackbar.js"
import { parseFragmentDetail, parseFragmentTargetDetail } from "./helpers.js"

// fragment kind -> `createHandle(fragmentEl)` returning that dialog's `{ isActive, close }`
const dialogKinds = new Map()

// htmx request context -> operation captured when the request was issued
const pendingOperations = new Map()

export function registerDialogKind(fragmentName, { createHandle }) {
    dialogKinds.set(fragmentName, createHandle)
}

/**
 * `htmx:before:request` hook: captures an operation issued from a dialog fragment, or drops the
 * request when that fragment already has one pending. Returns whether the request came from a
 * dialog fragment.
 */
export function trackDialogOperationRequest(event) {
    const ctx = event.detail.ctx
    const fragmentEl = findDialogFragment(ctx.sourceElement)

    if (!fragmentEl) {
        return false
    }

    if (hasPendingOperation(fragmentEl)) {
        console.debug("Dropping repeated submission while one is pending")
        event.preventDefault()
        return true
    }

    const createHandle = dialogKinds.get(fragmentEl.dataset.appFragment)

    pendingOperations.set(ctx, {
        dialog: createHandle(fragmentEl),
        fragmentEl,
        successEventKey: fragmentEl.dataset.appDialogSuccessEvent,
        successDetail: {
            ...parseFragmentDetail(fragmentEl.dataset.appDialogSuccessDetail),
            ...parseFragmentTargetDetail(fragmentEl, ctx.sourceElement),
        },
        closeOnSuccess: fragmentEl.dataset.appDialogCloseOnSuccess !== "false",
    })

    return true
}

/** Clear unfinished operations even when a network failure skipped `htmx:after:request`. */
export function finalizeDialogOperationRequest(event) {
    if (pendingOperations.delete(event.detail.ctx)) {
        showErrorSnackBar("The request did not complete. Please try again.")
    }
}

export function isDialogOperationRequest(event) {
    return pendingOperations.has(event.detail.ctx)
}

/**
 * `htmx:after:request` hook: completes a tracked operation. Returns whether the event belonged to
 * one. Runs before htmx swaps the response, so cancelling the event here is what keeps a stale
 * response from touching a newer dialog.
 */
export function settleDialogOperation(event, { dispatch }) {
    const ctx = event.detail.ctx
    const operation = pendingOperations.get(ctx)

    if (!operation) {
        return false
    }

    pendingOperations.delete(ctx)
    const stillActive = operation.dialog.isActive()

    if (!isRequestSuccessful(event)) {
        // Error bodies are never swapped (htmx `noSwap` config), so feedback comes from here
        showErrorSnackBar(
            `Error for request to ${getRequestPath(event)}. HTTP ${getResponseStatus(event)}`,
        )
        return true
    }

    // A response retargeted at the submitting form is a validation replacement, not a completed
    // operation: it swaps into the active dialog and is re-hydrated, or is reported and dropped.
    if (getResponseRetarget(event)) {
        if (!stillActive) {
            event.preventDefault()
            showWarningSnackBar(
                extractValidationMessages(getResponseText(event)) ||
                    "The request was rejected",
            )
        }
        return true
    }

    dispatchSuccessEvent({ operation, dispatch })

    if (stillActive && operation.closeOnSuccess) {
        operation.dialog.close()
    }

    return true
}

/** The fragment of a registered dialog kind enclosing `sourceEl`, if any. */
function findDialogFragment(sourceEl) {
    const selector = [...dialogKinds.keys()]
        .map((fragmentName) => `[data-app-fragment="${fragmentName}"]`)
        .join(", ")

    return (selector && sourceEl?.closest?.(selector)) || null
}

function hasPendingOperation(fragmentEl) {
    return [...pendingOperations.values()].some(
        (operation) => operation.fragmentEl === fragmentEl,
    )
}

function dispatchSuccessEvent({ operation, dispatch }) {
    if (!operation.successEventKey) {
        return
    }

    const eventName = Const.events[operation.successEventKey]
    if (!eventName) {
        console.warn(
            `Unknown dialog success event key: ${operation.successEventKey}`,
        )
        return
    }

    dispatch(eventName, operation.successDetail)
}

function extractValidationMessages(responseText) {
    const doc = new DOMParser().parseFromString(responseText, "text/html")

    return [...doc.querySelectorAll("div.error")]
        .map((el) => el.textContent.trim())
        .filter(Boolean)
        .join(" ")
}
