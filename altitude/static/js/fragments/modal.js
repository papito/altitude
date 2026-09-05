/**
 * General dialog fragments (`data-app-fragment="modal"`): hydration into the shared modal owner,
 * and the lifecycle of the operations those dialogs submit.
 *
 * A fragment may name where its dialog goes with `data-app-modal-anchor-x` and
 * `data-app-modal-anchor-y`: selectors of the elements the box is centered on horizontally and
 * vertically (the folder dialogs use the explorer and the folder's menu control). Without them the
 * dialog takes the host's default position. A validation response that replaces the form in place
 * is re-hydrated, which re-runs placement for the new form height.
 *
 * An operation is tracked from `htmx:before:request` so that everything it needs later (which open
 * it belongs to, its success event and detail) is captured while the dialog is still in the DOM.
 * Its response is handled from `htmx:after:request` regardless of whether the dialog has since
 * been closed or replaced: page updates and success events always happen, but only the still
 * active initiating dialog may be closed or have its form replaced by the response.
 */
import { Const } from "../constants.js"
import {
    closeModal,
    getModalOpenId,
    isModalOpenActive,
    ModalHost,
    openModal,
} from "../common/modal.js"
import {
    getRequestPath,
    getResponseRetarget,
    getResponseStatus,
    getResponseText,
    isRequestSuccessful,
} from "../common/htmx-events.js"
import { showErrorSnackBar, showWarningSnackBar } from "../common/snackbar.js"
import { parseFragmentDetail, parseFragmentTargetDetail } from "./helpers.js"

// htmx request context -> operation captured when the request was issued
const pendingOperations = new Map()

export function hydrateModalFragment({ fragmentEl, context, dispatch }) {
    openModal({
        host: ModalHost.general,
        title: fragmentEl.dataset.appModalTitle,
        focusSelector: fragmentEl.dataset.appModalAutofocusSelector,
        selectOnFocus: fragmentEl.dataset.appModalSelectOnFocus === "true",
        returnFocusSelector: fragmentEl.dataset.appModalReturnFocus,
        anchors: {
            x: fragmentEl.dataset.appModalAnchorX,
            y: fragmentEl.dataset.appModalAnchorY,
        },
    })

    if (fragmentEl.dataset.appModalKind === "view-settings") {
        initializeViewSettingsModalFragment({ fragmentEl, context, dispatch })
    }
}

function initializeViewSettingsModalFragment({
    fragmentEl,
    context,
    dispatch,
}) {
    const showFields = context.getGridMetadataFields()

    fragmentEl
        .querySelectorAll('input[type="checkbox"]')
        .forEach((checkboxEl) => {
            checkboxEl.checked = showFields.has(checkboxEl.value)
        })

    if (fragmentEl.dataset.appViewSettingsBound === "true") {
        return
    }

    fragmentEl.dataset.appViewSettingsBound = "true"

    fragmentEl.addEventListener("change", (event) => {
        const checkboxEl = event.target
        if (
            !(checkboxEl instanceof HTMLInputElement) ||
            checkboxEl.type !== "checkbox"
        ) {
            return
        }

        const fieldName = checkboxEl.value
        const checked = checkboxEl.checked

        if (checked) {
            context.addGridMetadataField(fieldName)
        } else {
            context.removeGridMetadataField(fieldName)
        }

        dispatch(Const.events.viewSettingChanged, {
            fieldName,
            checked,
        })
    })
}

/**
 * `htmx:before:request` hook: captures an operation issued from a modal fragment, or drops the
 * request when that fragment already has one pending. Returns whether the request came from a
 * modal fragment.
 */
export function trackModalOperationRequest(event) {
    const ctx = event.detail.ctx
    const fragmentEl = ctx.sourceElement?.closest?.(
        '[data-app-fragment="modal"]',
    )

    if (!fragmentEl) {
        return false
    }

    if (hasPendingOperation(fragmentEl)) {
        console.debug("Dropping repeated submission while one is pending")
        event.preventDefault()
        return true
    }

    pendingOperations.set(ctx, {
        openId: getModalOpenId(),
        fragmentEl,
        successEventKey: fragmentEl.dataset.appModalSuccessEvent,
        successDetail: {
            ...parseFragmentDetail(fragmentEl.dataset.appModalSuccessDetail),
            ...parseFragmentTargetDetail(fragmentEl, ctx.sourceElement),
        },
        closeOnSuccess: fragmentEl.dataset.appModalCloseOnSuccess !== "false",
    })

    return true
}

/** Clear unfinished operations even when a network failure skipped `htmx:after:request`. */
export function finalizeModalOperationRequest(event) {
    if (pendingOperations.delete(event.detail.ctx)) {
        showErrorSnackBar("The request did not complete. Please try again.")
    }
}

export function isModalOperationRequest(event) {
    return pendingOperations.has(event.detail.ctx)
}

/**
 * `htmx:after:request` hook: completes a tracked operation. Returns whether the event belonged to
 * one. Runs before htmx swaps the response, so cancelling the event here is what keeps a stale
 * response from touching a newer dialog.
 */
export function settleModalOperation(event, { dispatch }) {
    const ctx = event.detail.ctx
    const operation = pendingOperations.get(ctx)

    if (!operation) {
        return false
    }

    pendingOperations.delete(ctx)
    const stillActive = isModalOpenActive(operation.openId)

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
        closeModal()
    }

    return true
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
            `Unknown modal success event key: ${operation.successEventKey}`,
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
