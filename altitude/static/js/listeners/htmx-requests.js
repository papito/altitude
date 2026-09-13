import { showErrorSnackBar } from "../common/snackbar.js"
import {
    getRequestPath,
    getRequestSource,
    getResponseStatus,
    isRequestSuccessful,
} from "../common/htmx-events.js"
import { selectTab } from "../common/navigation.js"
import { isDialogOperationRequest } from "../fragments/dialog-operations.js"
import { readSuccessEvent } from "../fragments/helpers.js"
import { isModalOpenRequest } from "../common/modal.js"

/**
 * What happens after any htmx request, whichever element issued it:
 *
 * - a failed response (HTTP 400 and above; the body is never swapped, see the `noSwap` config)
 *   is reported through the snackbar, so nothing fails silently - not even a page continuous
 *   scroll requested with no visible control behind it;
 * - a successful response dispatches the success event the issuing element declares
 *   (`data-app-success-event`, see `readSuccessEvent`), which is how a plain request element
 *   announces what it did without a router keyed on its URL;
 * - a successful tab load marks its tab as selected;
 * - every swap hydrates the `data-app-fragment` roots it inserted.
 *
 * Requests that open a modal and operations submitted from dialogs are settled by
 * `listeners/dialogs.js` instead, on `document`, which runs after this listener on `body`:
 * they are skipped here so a failure is reported once.
 */
export function registerHtmxRequestListeners(app) {
    document.body.addEventListener("htmx:after:request", (event) => {
        if (isModalOpenRequest(event) || isDialogOperationRequest(event)) {
            return
        }

        if (!isRequestSuccessful(event)) {
            showErrorSnackBar(
                `Error for request to ${getRequestPath(event)}. HTTP ${getResponseStatus(event)}`,
            )
            return
        }

        const sourceEl = getRequestSource(event)

        if (sourceEl?.matches?.('[role="tab"]')) {
            selectTab(sourceEl)
        }

        const successEvent = readSuccessEvent(sourceEl, sourceEl)
        if (successEvent) {
            app.dispatch(successEvent.name, successEvent.detail)
        }
    })

    // Runs once per swap with the nodes htmx just inserted
    document.body.addEventListener("htmx:after:settle", (event) => {
        event.detail.newContent.forEach((node) => {
            if (node instanceof Element) {
                app.hydrateFragments(node)
            }
        })
    })
}
