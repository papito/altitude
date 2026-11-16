import {
    bindPopoverHost,
    settleModalOpenRequest,
    trackModalOpenRequest,
} from "../common/modal.js"
import { reportRequestFailure } from "../common/request-failure.js"
import {
    finalizeDialogOperationRequest,
    settleDialogOperation,
    trackDialogOperationRequest,
} from "../fragments/dialog-operations.js"
import { runTriggerSearch } from "../search-results/search-triggers.js"

/**
 * htmx lifecycle wiring for requests that open a modal and for operations submitted from a dialog.
 * Registered on `document`, not `document.body`: htmx dispatches on the document once the issuing
 * element has left the DOM, as a dialog closed or replaced mid-operation has.
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
            // A refusal the server words (a 403 for a Permission not held) shows as its text
            onFailure: () => {
                reportRequestFailure(event)
                // Asset detail's URL exists on the main page alone
                app.detailUrl?.openFailed()
            },
            // The Triage scope route's answer for a Person or a Location with triaged assets: the
            // trigger searches instead
            onNoContent: (ctx) => runTriggerSearch(ctx.sourceElement),
        })

        if (handledAsOpen) {
            return
        }

        settleDialogOperation(event, { dispatch: app.dispatch.bind(app) })
    })
}
