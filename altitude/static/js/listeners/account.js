import { Const } from "../constants.js"
import { showSuccessSnackBar } from "../common/snackbar.js"

/**
 * A password change revoked the account's sessions, closing their sockets with 1008, which `hx-ws` does
 * not reconnect on; the new cookie is set by now, so the page's sockets reconnect here.
 */
export function registerAccountListeners() {
    document.body.addEventListener(Const.events.passwordChanged, (event) => {
        showSuccessSnackBar(event.detail?.message ?? "Your password is changed")
        reconnectSockets()
    })
}

/** Swapping an `hx-ws:connect` element for a copy of itself closes the old socket and connects the copy */
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

/** The Users page's cards are read again under the tab and filter shown */
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
