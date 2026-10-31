import { Alpine } from "../../lib/alpine.esm.min.js"
import { Const } from "../../constants.js"
import { context } from "../../context.js"
import { runSearch } from "../../search-results/search.js"

/**
 * The View settings dialog (`htmx/view_settings_dialog.scala.html`): one switch per grid metadata
 * field, bound to the context's reactive `gridMetadataFields` set. A switch's `aria-checked` reads
 * the set and a click writes it back, and the grid follows the set
 * (`search-results/metadata-visibility.js`), so the dialog never submits anything and the panel
 * stays open for as long as the user keeps it there.
 *
 * The scope switch is different in kind: "Show triaged items" reads the `triaged` search parameter
 * from the `searchParams` store, and flipping it runs a search through the one funnel, so the
 * results are rendered again; the View control's host is `hx-preserve`d, so this panel outlives the
 * swap and stays open. It is offered in the repository view alone, the view the parameter belongs
 * to (`stores/search-params.js`).
 *
 * The server knows nothing of the settings: the fragment arrives unbound and reads the set and the
 * store as Alpine initializes it, so the hydrator (`fragments/inline-dialog.js`) only places focus.
 */
export function viewSettings() {
    return {
        isShown(field) {
            return context.getGridMetadataFields().has(field)
        },

        toggle(field) {
            if (this.isShown(field)) {
                context.removeGridMetadataField(field)
            } else {
                context.addGridMetadataField(field)
            }
        },

        hasTriageOption() {
            return Alpine.store(Const.state.currentView).isDefaultViewView()
        },

        showsTriaged() {
            return Alpine.store(Const.state.searchParams).triaged !== "false"
        },

        toggleTriaged() {
            // An empty literal clears the parameter, so the view's own rule applies again
            runSearch({
                params: { triaged: this.showsTriaged() ? "false" : "" },
            })
        },
    }
}
