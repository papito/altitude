import {
    bindPopoverHost,
    settleModalOpenRequest,
    trackModalOpenRequest,
} from "../common/modal.js"
import { showErrorSnackBar } from "../common/snackbar.js"
import {
    finalizeDialogOperationRequest,
    settleDialogOperation,
    trackDialogOperationRequest,
} from "../fragments/dialog-operations.js"

/**
 * HTMX lifecycle wiring for dialogs: requests that open a modal, and operations submitted from any
 * dialog. Also binds the popover dialog host's own dismissals (`common/modal.js`).
 *
 * Registered on `document` rather than `document.body`: htmx dispatches lifecycle events on the
 * document when the element that issued the request has already left the DOM, which is exactly
 * the case for a dialog that was closed or replaced while its operation was in flight.
 */
export function registerDialogListeners(app) {
    bindPopoverHost()

    document.addEventListener(
        "htmx:finally:request",
        finalizeDialogOperationRequest,
    )

    document.addEventListener("htmx:before:request", (event) => {
        if (trackModalOpenRequest(event)) {
            return
        }

        trackDialogOperationRequest(event)
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

        settleDialogOperation(event, { dispatch: app.dispatch.bind(app) })
    })
}
