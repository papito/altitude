/**
 * The form of the advanced search panel (`htmx/search_panel`, views/AGENTS.md **Advanced search
 * panel**), which the nav's panel loads on opening (`search-results/search-panel.js`). Every filter
 * is an input of the form under its search parameter's name, kept by the field's own component
 * (`filterCombobox`, `dateRange`), so the form's entries are the search.
 *
 * The form's first row, "Search in", picks the assets the search covers: Library, Triage or All.
 * A pick is the search's view and its triage filter, held in the row's two hidden inputs (`view`,
 * `triaged`), which the component mirrors so the row's segments and the Folder field follow them:
 * a folder holds no triaged asset, so the field is hidden while Triage is picked
 * (`searchesTriage`) and its folder left out of the search (`searchOfForm`). The People field asks
 * for its options with the two inputs as they stand, so they count what the search will find. A
 * Guest's form has no row: its search carries no pick and stays the repository view's.
 *
 * **Search**, the form's submit and so Enter in the Search text's field too, runs the search of
 * those entries (`searchByFilters`): the text trimmed, the description as the store keeps it,
 * without the white space around it, and the people match only when it says something, two people
 * or more matched by any of them, since all of them is the server's rule with none given. The Describe
 * field is a textarea, where Enter alone would break the line: Enter searches there as well
 * (`searchOnEnter`), and Shift+Enter breaks the line. **Clear** empties every field and searches
 * nothing: the description, the text, the match back to all, and each field's component through
 * `search-panel-clear`, dispatched on its root. The pick is no field, and stays.
 *
 * The nav's filter button counts the fields the form fills while the panel is open
 * (`showFormFilterCount`): as the form loads, and on every `input` and `change` that reaches the
 * form, which the Describe field and the Search text's fire as they are typed in and each field's
 * component as it writes its hidden inputs.
 *
 * The people match is the form's own state: a switch that is on for all of the people (`matchesAll`)
 * and off for any of them, its value in the hidden `peopleMatch` input. The People field shows it
 * once two people are chosen (`filterCombobox`'s `hasSeveral`).
 */
import { Const } from "../../constants.js"
import {
    searchByFilters,
    searchOfForm,
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
        // The pick of the "Search in" row, as its hidden inputs hold it: the view searched, and the triage filter of the
        // repository view, none in the Triage view. A Guest's form has no row, and both stay null
        view: null,
        triaged: null,

        init() {
            formEl = this.$el
            this.matchesAll = formEl.elements.peopleMatch.value !== any
            this.view = formEl.elements.view?.value ?? null
            this.triaged = formEl.elements.triaged?.value ?? null
            this.countFilters()
        },

        /** The form searches the Triage view, the triaged assets alone, where no folder is */
        get searchesTriage() {
            return this.view === Const.views.triage
        },

        /** Whether the pick is the one of `view` and `triaged`: its segment of the row is the pressed one */
        searchesIn(view, triaged) {
            return this.view === view && this.triaged === triaged
        },

        /** Picks the assets to search: the row's inputs take the view and the triage filter, and the button's count follows */
        searchIn(view, triaged) {
            this.view = formEl.elements.view.value = view
            this.triaged = formEl.elements.triaged.value = triaged
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

        /**
         * Enter in the Describe field searches, as it does in a one-line field; with Shift it
         * breaks the line, and while an input method is composing it is the composition's
         */
        searchOnEnter(event) {
            if (event.shiftKey || event.isComposing) {
                return
            }

            event.preventDefault()
            formEl.requestSubmit()
        },

        /** Clear: every field empty, nothing searched, the caret in the first field, Describe */
        clear() {
            const describeEl = formEl.elements.describe

            describeEl.value = ""
            formEl.elements.q.value = ""
            this.setMatch(true)
            formEl
                .querySelectorAll("[x-data]")
                .forEach((fieldEl) =>
                    fieldEl.dispatchEvent(
                        new CustomEvent("search-panel-clear"),
                    ),
                )
            this.countFilters()
            describeEl.focus()
        },

        /** Search: the form's entries are the filters of the search, and its pick of the assets to search */
        search() {
            const filters = searchOfForm(formEl)

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
