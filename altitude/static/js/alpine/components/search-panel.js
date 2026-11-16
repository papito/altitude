/**
 * The advanced search panel's form. Every filter is an input under its search parameter's name, so
 * the form's entries are the search.
 *
 * The "Search in" row's pick (Library, Triage or All) is the search's `view` and `triaged`, held in
 * two hidden inputs. A folder holds no triaged asset, so the Folder field is hidden while Triage is
 * picked, and its folder left out of the search. A Guest's form has no row.
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
    // Not `$root`: the switch calls `toggleMatch` from inside the People field, a component of its own,
    // where `$root` is that field
    let formEl = null

    return {
        // Several people match with all of them; off, with any
        matchesAll: true,
        // Mirror the "Search in" row's hidden inputs; null in a Guest's form, which has no row
        view: null,
        triaged: null,

        init() {
            formEl = this.$el
            this.matchesAll = formEl.elements.peopleMatch.value !== any
            this.view = formEl.elements.view?.value ?? null
            this.triaged = formEl.elements.triaged?.value ?? null
            this.countFilters()
        },

        get searchesTriage() {
            return this.view === Const.views.triage
        },

        searchesIn(view, triaged) {
            return this.view === view && this.triaged === triaged
        },

        searchIn(view, triaged) {
            this.view = formEl.elements.view.value = view
            this.triaged = formEl.elements.triaged.value = triaged
            this.countFilters()
        },

        /** The nav's filter button counts the fields the form fills */
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

        /** Enter in the Describe textarea searches; Shift+Enter breaks the line, and an IME composition keeps its Enter */
        searchOnEnter(event) {
            if (event.shiftKey || event.isComposing) {
                return
            }

            event.preventDefault()
            formEl.requestSubmit()
        },

        /** The "Search in" pick is not a field, so it stays */
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

        /** The people match goes out only as "any" with two people or more: "all" is the server's default */
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
