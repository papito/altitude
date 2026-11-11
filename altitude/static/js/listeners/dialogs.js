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
 * HTMX lifecycle wiring for dialogs: requests that open a modal, and operations submitted from any
 * dialog. Also binds the popover dialog host's own dismissals (`common/modal.js`). An open answered
 * with no content leaves the next step to the element that issued it: a search trigger searches
 * (`search-results/search-triggers.js`).
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
            // A refusal the server words (a 403 for a Permission not held) is shown as its text
            onFailure: () => {
                reportRequestFailure(event)
                // Asset detail's URL, on the main page alone (`nav-page.js` has none)
                app.detailUrl?.openFailed()
            },
            // The Triage scope route's answer for a Person or a Location with triaged assets: no
            // dialog, the trigger searches
            onNoContent: (ctx) => runTriggerSearch(ctx.sourceElement),
        })

        if (handledAsOpen) {
            return
        }

        settleDialogOperation(event, { dispatch: app.dispatch.bind(app) })
    })
}
