import {
    getRequestPath,
    getResponseHeader,
    getResponseStatus,
    getResponseText,
} from "./htmx-events.js"
import { isUnauthorized } from "./session.js"
import { showErrorSnackBar, showWarningSnackBar } from "./snackbar.js"

/**
 * A plain-text 409 or 403 is a refusal the server words for the user (`BaseController.answering`)
 * and is shown as its text, a conflict as a warning. A 401 is not reported: the window is already
 * on its way to the login.
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
