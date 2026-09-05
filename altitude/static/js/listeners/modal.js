import {
    settleModalOpenRequest,
    trackModalOpenRequest,
} from "../common/modal.js"
import { showErrorSnackBar } from "../common/snackbar.js"
import {
    finalizeModalOperationRequest,
    settleModalOperation,
    trackModalOperationRequest,
} from "../fragments/modal.js"

/**
 * HTMX lifecycle wiring for modals: requests that open a modal, and operations submitted from one.
 *
 * Registered on `document` rather than `document.body`: htmx dispatches lifecycle events on the
 * document when the element that issued the request has already left the DOM, which is exactly
 * the case for a dialog that was closed or replaced while its operation was in flight.
 */
export function registerModalListeners(app) {
    document.addEventListener(
        "htmx:finally:request",
        finalizeModalOperationRequest,
    )

    document.addEventListener("htmx:before:request", (event) => {
        if (trackModalOpenRequest(event)) {
            return
        }

        trackModalOperationRequest(event)
    })

    document.addEventListener("htmx:after:request", (event) => {
        const handledAsOpen = settleModalOpenRequest(event, {
            onFailure: (ctx) => {
                showErrorSnackBar(
                    `Could not open dialog: HTTP ${ctx.response.status}`,
                )
            },
        })

        if (handledAsOpen) {
            return
        }

        settleModalOperation(event, { dispatch: app.dispatch.bind(app) })
    })
}
