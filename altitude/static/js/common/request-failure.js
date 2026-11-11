import {
    getRequestPath,
    getResponseHeader,
    getResponseStatus,
    getResponseText,
} from "./htmx-events.js"
import { showErrorSnackBar, showWarningSnackBar } from "./snackbar.js"

/**
 * Reports a failed htmx request through the snackbar. A refusal the server words for the user
 * (`BaseController.answering`'s plain text: a 409 conflict, such as deactivating one's own account,
 * or a 403 for a Permission not held) is shown as its text, a conflict as a warning, as a folder
 * move's is; any other failure names the request and its status.
 */
export function reportRequestFailure(event) {
    const status = getResponseStatus(event)
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
