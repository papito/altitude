import { Const } from "./constants.js"
import { registerSettingsListeners } from "./listeners/settings.js"
import { startNavPage } from "./nav-page.js"

/**
 * The Settings page. Its one form submits through htmx, so its outcome follows the one rule for every request
 * (`listeners/htmx-requests.js`): a failure goes to the snackbar, a validation replacement swaps the form back in with its
 * errors, a save dispatches the form's declared success event.
 *
 * The page starts as every page with the nav outside the main app does (`startNavPage`): Alpine for what `html_common`
 * renders and for the nav's Browse menu, and the dialog wiring of the New library dialog that menu opens. The form carries no
 * bindings.
 */
startNavPage()
registerSettingsListeners()

// Reset fills a field with its default and clears its error mark; the error text stays until Save answers. Delegated, so it
// survives the form being swapped.
document.body.addEventListener("click", (event) => {
    const button = event.target.closest("[data-app-reset-setting]")
    if (!button) {
        return
    }

    const setting = button.closest(`[${Const.attributes.settingDefault}]`)
    const field = setting.querySelector("input")
    const fallback = setting.dataset.settingDefault

    if (field.type === "checkbox") {
        field.checked = fallback === "true"
    } else {
        field.value = fallback
        field.classList.remove("error")
    }
})
