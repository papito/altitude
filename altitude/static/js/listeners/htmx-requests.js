import {
    getRequestSource,
    getResponseRetarget,
    getResponseStatus,
    isRequestSuccessful,
} from "../common/htmx-events.js"
import { reportRequestFailure } from "../common/request-failure.js"
import { selectTab } from "../common/navigation.js"
import { isDialogOperationRequest } from "../fragments/dialog-operations.js"
import { readSuccessEvent } from "../fragments/helpers.js"
import { isModalOpenRequest } from "../common/modal.js"
import { goToLogin, isUnauthorized } from "../common/session.js"

/**
 * What happens after any htmx request, whichever element issued it:
 *
 * - a 401, a request refused for want of a login, sends the window to the login, whatever issued
 *   it (a poll, a dialog's submit, a modal's open), and nothing else happens: no failure is
 *   reported and no success announced (`common/session.js`; the server's `HX-Redirect` says the
 *   same);
 * - a failed response (HTTP 400 and above; the body is never swapped, see the `noSwap` config)
 *   is reported through the snackbar (`common/request-failure.js`: a refusal worded for the user,
 *   a 403 or 409 in plain text, as its text), so nothing fails silently - not even a page continuous
 *   scroll requested with no visible control behind it. The one exception is a request whose
 *   element declares `data-app-silent-failure`: a poll, whose next tick tries again, and which
 *   would otherwise report a server answering 5xx while it restarts on every tick (a request
 *   that gets no response at all never reaches this listener: htmx fires `htmx:error` instead);
 * - a validation replacement (`HX-Retarget: this`, `BaseController.dialogFormValidationResponse`)
 *   is swapped in and announces nothing: the form came back with its errors;
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
        if (isUnauthorized(getResponseStatus(event))) {
            goToLogin()
            return
        }

        if (isModalOpenRequest(event) || isDialogOperationRequest(event)) {
            return
        }

        const sourceEl = getRequestSource(event)

        if (!isRequestSuccessful(event)) {
            if (!sourceEl?.hasAttribute?.("data-app-silent-failure")) {
                reportRequestFailure(event)
            }
            return
        }

        if (getResponseRetarget(event)) {
            return
        }

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
