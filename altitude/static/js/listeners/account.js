import { Const } from "../constants.js"
import { showSuccessSnackBar } from "../common/snackbar.js"

/**
 * The account info dialog's Change password, on every page that has the nav: a password the account changed is
 * confirmed through the snackbar (the answer set the session's new token), and the page's
 * WebSocket feeds are connected again. The change revoked the account's sessions, which closed
 * their sockets with 1008, a code `hx-ws` does not reconnect on (the Import page's status feed);
 * the new cookie is set by now, so a new handshake passes.
 */
export function registerAccountListeners() {
    document.body.addEventListener(Const.events.passwordChanged, (event) => {
        showSuccessSnackBar(event.detail?.message ?? "Your password is changed")
        reconnectSockets()
    })
}

/**
 * Connects every `hx-ws:connect` element of the page again: htmx swaps the element for a copy of
 * itself, which cleans the old one up (its socket closed) and processes the copy, which connects
 */
function reconnectSockets() {
    document.querySelectorAll("[hx-ws\\:connect]").forEach((el) => {
        htmx.swap({
            sourceElement: el,
            target: el,
            swap: "outerHTML",
            text: el.outerHTML,
            transition: false,
        })
    })
}

/**
 * The Users page: an account added, changed or deleted is confirmed through the snackbar with the
 * message the server named it by, and the cards are read again, under the tab and filter shown
 */
export function registerUsersListeners() {
    document.body.addEventListener(Const.events.accountsChanged, (event) => {
        if (event.detail?.message) {
            showSuccessSnackBar(event.detail.message)
        }
        const url = document.querySelector("[data-app-accounts-url]")?.dataset
            .appAccountsUrl
        if (url) {
            htmx.ajax("GET", url, { target: "#accounts", swap: "outerHTML" })
        }
    })
}
