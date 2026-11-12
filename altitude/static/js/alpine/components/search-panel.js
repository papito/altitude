/**
 * The form of the advanced search panel (`htmx/search_panel`, views/AGENTS.md **Advanced search
 * panel**), which the nav's panel loads on opening (`search-results/search-panel.js`). Every filter
 * is an input of the form under its search parameter's name, kept by the field's own component
 * (`filterCombobox`, `dateRange`), so the form's entries are the search.
 *
 * **Search**, the form's submit and so Enter in the text field too, runs the search of those entries
 * (`searchByFilters`): the text trimmed, and the people match only when it says something, two
 * people or more matched by any of them, since all of them is the server's rule with none given.
 * **Clear** empties every field and searches nothing: the text, the match back to all, and each
 * field's component through `search-panel-clear`, dispatched on its root.
 *
 * The nav's filter button counts the fields the form fills while the panel is open
 * (`showFormFilterCount`): as the form loads, and on every `input` and `change` that reaches the
 * form, which the text field fires as it is typed in and each field's component as it writes its
 * hidden inputs.
 *
 * The people match is the form's own state: a switch that is on for all of the people (`matchesAll`)
 * and off for any of them, its value in the hidden `peopleMatch` input. The People field shows it
 * once two people are chosen (`filterCombobox`'s `hasSeveral`).
 */
import { Const } from "../../constants.js"
import {
    searchByFilters,
    showFormFilterCount,
} from "../../search-results/search-panel.js"
import { idsOf } from "../../stores/search-params.js"

const { all, any } = Const.search.peopleMatch

export function searchPanel() {
    // The form. Closure state rather than `$root`: the switch calls `toggleMatch` from inside the People field, a
    // component of its own, where `$root` is that field
    let formEl = null

    return {
        // Several people match the assets with all of them; off, with any of them
        matchesAll: true,

        init() {
            formEl = this.$el
            this.matchesAll = formEl.elements.peopleMatch.value !== any
            this.countFilters()
        },

        /** The filter button counts the fields the form fills now */
        countFilters() {
            showFormFilterCount(formEl)
        },

        toggleMatch() {
            this.setMatch(!this.matchesAll)
        },

        setMatch(matchesAll) {
            this.matchesAll = matchesAll
            formEl.elements.peopleMatch.value = matchesAll ? all : any
        },

        /** Clear: every field empty, nothing searched, the caret in the text field */
        clear() {
            const textEl = formEl.elements.q

            textEl.value = ""
            this.setMatch(true)
            formEl
                .querySelectorAll("[x-data]")
                .forEach((fieldEl) =>
                    fieldEl.dispatchEvent(
                        new CustomEvent("search-panel-clear"),
                    ),
                )
            this.countFilters()
            textEl.focus()
        },

        /** Search: the form's entries are the filters of the search */
        search() {
            const filters = Object.fromEntries(new FormData(formEl))

            filters.q = filters.q.trim()
            if (
                filters.peopleMatch === all ||
                idsOf(filters.personId).length < 2
            ) {
                filters.peopleMatch = null
            }

            searchByFilters(filters)
        },
    }
}
