import { Alpine } from "./lib/alpine.esm.min.js"
import { Const } from "./constants.js"
import { registerHtmlCommonBindings } from "./stores/html-common.js"
import { registerHtmxRequestListeners } from "./listeners/htmx-requests.js"
import { registerSettingsListeners } from "./listeners/settings.js"

/**
 * The Settings page. Its one form submits through htmx, so its outcome follows the one rule for every request
 * (`listeners/htmx-requests.js`): a failure goes to the snackbar, a validation replacement swaps the form back in with its
 * errors, a save dispatches the form's declared success event. The page has no fragments to hydrate, so the app handed to
 * that rule is only what it reads.
 *
 * The page starts Alpine for what `html_common` renders (`registerHtmlCommonBindings`: the modal hosts, asset detail's
 * spinner, the snackbar's stacks and `x-trap`), not for the nav or the form, which carry no bindings.
 */
registerHtmlCommonBindings()
Alpine.start()

const app = {
    dispatch(eventName, detail = {}) {
        document.body.dispatchEvent(new CustomEvent(eventName, { detail }))
    },
    hydrateFragments() {},
}

registerHtmxRequestListeners(app)
registerSettingsListeners()

// Reset fills a field with its default and clears its error mark; the error text stays until Save answers. Delegated, so it
// survives the form being swapped.
document.body.addEventListener("click", (event) => {
    const button = event.target.closest("[data-app-reset-setting]")
    if (!button) {
        return
    }

    const setting = button.closest(`[${Const.attributes.settingDefault}]`)
    const input = setting.querySelector("input")
    const fallback = setting.dataset.settingDefault

    if (input.type === "checkbox") {
        input.checked = fallback === "true"
    } else {
        input.value = fallback
        input.classList.remove("error")
    }
})
