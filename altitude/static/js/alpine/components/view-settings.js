import { Alpine } from "../../lib/alpine.esm.min.js"
import { Const } from "../../constants.js"
import { context } from "../../context.js"
import { runSearch } from "../../search-results/search.js"

/**
 * The View settings dialog: one switch per grid metadata field, bound to the context's reactive
 * set, which the grid follows, so the dialog never submits anything.
 *
 * "Show triaged items" is different in kind: it flips the `triaged` search parameter of the
 * repository view and runs a search. The View control's host is `hx-preserve`d, so the panel
 * outlives the swap and stays open.
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
            return Alpine.store(Const.state.searchParams).showsTriaged()
        },

        toggleTriaged() {
            // Always explicit, `true` or `false`, so the store remembers the choice
            runSearch({
                params: { triaged: String(!this.showsTriaged()) },
            })
        },
    }
}
