import { Const } from "./constants.js"
import { registerSettingsListeners } from "./listeners/settings.js"
import { startNavPage } from "./nav-page.js"

/**
 * The Settings page. Its one form submits through htmx, so its outcome follows the shared request rules
 * (`listeners/htmx-requests.js`); the form carries no bindings of its own.
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
