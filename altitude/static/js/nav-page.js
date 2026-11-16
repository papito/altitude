import { Alpine } from "./lib/alpine.esm.min.js"
import { contextMenu } from "./alpine/components/context-menu.js"
import { dateRange } from "./alpine/components/date-range.js"
import { filterCombobox } from "./alpine/components/filter-combobox.js"
import { localTime } from "./alpine/components/local-time.js"
import { searchPanel } from "./alpine/components/search-panel.js"
import { ensureChoiceMenus } from "./common/choice-menus.js"
import { findFragmentRoots } from "./fragments/helpers.js"
import { hydrateModalFragment } from "./fragments/modal.js"
import { registerAccountListeners } from "./listeners/account.js"
import { registerDialogListeners } from "./listeners/dialogs.js"
import { registerHtmxRequestListeners } from "./listeners/htmx-requests.js"
import { bindSearchPanel } from "./search-results/search-panel.js"
import { registerHtmlCommonBindings } from "./stores/html-common.js"

/**
 * Starts a page that carries the nav and `html_common` outside the main app (Import, Settings, Users):
 * Alpine with the nav's menus, the account dialog and the advanced search panel, the shared htmx and
 * dialog listeners, and hydration of what a swap inserts (dialogs and the nav's choice menu host, which
 * a nav reload keeps, `hx-preserve`). Returns the minimal app the listeners dispatch with.
 */
export function startNavPage() {
    registerHtmlCommonBindings()
    // For the menu control `common/context-menu-markup.js` builds
    Alpine.data("contextMenu", contextMenu)
    // For the account info dialog's times
    Alpine.data("localTime", localTime)
    // For the advanced search panel's form
    Alpine.data("searchPanel", searchPanel)
    Alpine.data("filterCombobox", filterCombobox)
    Alpine.data("dateRange", dateRange)

    const app = {
        dispatch(eventName, detail = {}) {
            document.body.dispatchEvent(new CustomEvent(eventName, { detail }))
        },
        hydrateFragments(root) {
            findFragmentRoots(root, "modal").forEach((fragmentEl) => {
                hydrateModalFragment({ fragmentEl })
            })
            ensureChoiceMenus(root)
        },
    }

    registerHtmxRequestListeners(app)
    registerDialogListeners(app)
    registerAccountListeners()

    // Built before Alpine starts, whose first pass then initializes the menu's component
    ensureChoiceMenus(document)
    Alpine.start()
    bindSearchPanel()

    return app
}
