import { Const } from "../constants.js"
import { showSuccessSnackBar } from "../common/snackbar.js"

/**
 * The nav's account menu, on every page that has the nav: a password the account changed is
 * confirmed through the snackbar (the answer set the session's new token)
 */
export function registerAccountListeners() {
    document.body.addEventListener(Const.events.passwordChanged, (event) => {
        showSuccessSnackBar(event.detail?.message ?? "Your password is changed")
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
