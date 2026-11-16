/**
 * Lifecycle of the operations dialogs submit. An operation is captured at `htmx:before:request`,
 * while its dialog is still in the DOM, and settled at `htmx:after:request` even when the dialog has
 * since closed or been replaced: the success event always fires, its declared detail merged with the
 * server's `App-Success-Detail` header, but only the still active initiating dialog is closed or has
 * its form replaced by the response.
 */
import { Const } from "../constants.js"
import {
    getResponseHeader,
    getResponseRetarget,
    getResponseText,
    isRequestSuccessful,
} from "../common/htmx-events.js"
import { reportRequestFailure } from "../common/request-failure.js"
import { showErrorSnackBar, showWarningSnackBar } from "../common/snackbar.js"
import { parseFragmentDetail, readSuccessEvent } from "./helpers.js"

// fragment kind -> `createHandle(fragmentEl)` returning that dialog's `{ isActive, close }`
const dialogKinds = new Map()

// htmx request context -> operation captured when the request was issued
const pendingOperations = new Map()

export function registerDialogKind(fragmentName, { createHandle }) {
    dialogKinds.set(fragmentName, createHandle)
}

/**
 * `htmx:before:request` hook: captures an operation issued from a dialog fragment, or drops the
 * request when that fragment already has one pending. Returns whether it came from a dialog.
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
        successEvent: readSuccessEvent(fragmentEl, ctx.sourceElement),
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
 * `htmx:after:request` hook: completes a tracked operation; returns whether the event belonged to
 * one. Runs before htmx swaps, so cancelling the event keeps a stale response from a newer dialog.
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
        reportRequestFailure(event)
        return true
    }

    // A response retargeted at the submitting form is a validation failure, not a completed
    // operation: it swaps into the active dialog, or is reported and dropped.
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

    if (operation.successEvent) {
        dispatch(operation.successEvent.name, {
            ...operation.successEvent.detail,
            ...parseFragmentDetail(
                getResponseHeader(event, Const.http.successDetailHeader),
            ),
        })
    }

    if (stillActive) {
        operation.dialog.close()
    }

    return true
}

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

function extractValidationMessages(responseText) {
    const doc = new DOMParser().parseFromString(responseText, "text/html")

    return [...doc.querySelectorAll("div.error")]
        .map((el) => el.textContent.trim())
        .filter(Boolean)
        .join(" ")
}
