import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { datasetKeySuffixToDetailKey } from "../fragments/helpers.js"
import { idsOf } from "../stores/search-params.js"
import { openSearchPanel, searchByFilters } from "./search-panel.js"

/**
 * The results title of a filtered search (`includes/search_filter_chips`, views/AGENTS.md **Results
 * title**): "Search results" over a chip per filter, each with a × that removes its filter and
 * searches again, and Edit, which opens the advanced search panel on the search in force.
 *
 * The chips are the state, as they are in the panel: each remove button (`data-app-filter`) carries
 * the parameters its chip stands for as `data-app-filter-<param>` literals, and its × searches by
 * what the other chips name, through the panel's own funnel (`searchByFilters`: in Browse the
 * store's `replaceFilters` and `runSearch`, anywhere else Browse with the search as its query
 * string). An ID of the search that names nothing in the Library has no chip, so it goes with the
 * first removal, as it goes with a search from the panel. The people match is the search's; it
 * goes with the second person (`withoutRefusedCombinations`).
 */

// The dataset key of the marker, and the prefix of the literals' keys
const FILTER_KEY = "appFilter"

// The parameters that hold several IDs, separated by commas
const ID_LISTS = ["personId", "locationId"]

/** Binds the remove buttons and Edit of the fragment's results title, when the search is a filtered one */
export function bindFilteredTitle({ fragmentEl }) {
    const removeEls = Array.from(
        fragmentEl.querySelectorAll(`[${Const.attributes.filter}]`),
    )

    removeEls.forEach((removeEl) => {
        removeEl.addEventListener("click", () => {
            const remaining = removeEls.filter(
                (otherEl) => otherEl !== removeEl,
            )
            searchByFilters(filtersOf(remaining))
        })
    })

    fragmentEl
        .querySelector(`[${Const.attributes.editFilters}]`)
        ?.addEventListener("click", () => openSearchPanel())
}

/** The filters the remove buttons `removeEls` name, the people and the Locations each as a list, with the search's people match */
function filtersOf(removeEls) {
    const filters = {
        peopleMatch:
            Alpine.store(Const.state.searchParams)?.peopleMatch ?? null,
    }

    removeEls.forEach((removeEl) => {
        Object.entries(removeEl.dataset).forEach(([key, value]) => {
            if (!key.startsWith(FILTER_KEY) || key === FILTER_KEY) {
                return
            }

            const param = datasetKeySuffixToDetailKey(
                key.slice(FILTER_KEY.length),
            )
            filters[param] = ID_LISTS.includes(param)
                ? [...idsOf(filters[param]), value].join(",")
                : value
        })
    })

    return filters
}
