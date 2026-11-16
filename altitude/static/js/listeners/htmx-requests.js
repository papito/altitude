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
 * Settles every htmx request but modal opens and dialog operations, which `listeners/dialogs.js`
 * settles, so a failure is reported once. A 401 goes to the login and nothing else happens. A failure
 * (never swapped) is reported, so nothing fails silently, unless its element declares
 * `data-app-silent-failure`: a poll, whose next tick retries and which would otherwise report every
 * tick while the server restarts. A request with no response at all fires `htmx:error` instead and
 * never gets here. A validation replacement (`HX-Retarget`) announces nothing.
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
