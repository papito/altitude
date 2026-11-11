import {
    getRequestPath,
    getResponseHeader,
    getResponseStatus,
    getResponseText,
} from "./htmx-events.js"
import { isUnauthorized } from "./session.js"
import { showErrorSnackBar, showWarningSnackBar } from "./snackbar.js"

/**
 * Reports a failed htmx request through the snackbar. A refusal the server words for the user
 * (`BaseController.answering`'s plain text: a 409 conflict, such as deactivating one's own account,
 * or a 403 for a Permission not held) is shown as its text, a conflict as a warning, as a folder
 * move's is; any other failure names the request and its status. A 401 is reported nowhere: the
 * window is on its way to the login (`listeners/htmx-requests.js`).
 */
export function reportRequestFailure(event) {
    const status = getResponseStatus(event)
    if (isUnauthorized(status)) {
        return
    }
    const reason = getResponseText(event)?.trim()
    const plainText = (
        getResponseHeader(event, "Content-Type") ?? ""
    ).startsWith("text/plain")

    if (plainText && reason && status === 409) {
        showWarningSnackBar(reason)
    } else if (plainText && reason && status === 403) {
        showErrorSnackBar(reason)
    } else {
        showErrorSnackBar(
            `Error for request to ${getRequestPath(event)}. HTTP ${status}`,
        )
    }
}
