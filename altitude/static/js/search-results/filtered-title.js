import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { literalsOf } from "../fragments/helpers.js"
import { ID_LISTS, idsOf } from "../stores/search-params.js"
import { openSearchPanel, searchByFilters } from "./search-panel.js"
import { runSearch } from "./search.js"

/**
 * The results title of a filtered search (`includes/search_filter_chips`, views/AGENTS.md **Results
 * title**): "Search results" over a chip per filter, each with a × that removes its filter and
 * searches again, and Edit, which opens the advanced search panel on the search in force.
 *
 * The chips are the state, as they are in the panel: each chip (`data-app-filter`) carries the
 * parameters it stands for as `data-app-filter-<param>` literals, and its × (`data-chip-remove`)
 * takes the chip off the page and searches by what the chips left on it name, through the panel's
 * own funnel (`searchByFilters`: in Browse the store's `replaceFilters` and `runSearch`, anywhere
 * else Browse with the search as its query string). A second × before the first search has answered
 * therefore searches without both. An ID of the search that names nothing in the Library has no
 * chip, so it goes with the first removal, as it goes with a search from the panel. The people match
 * is the search's; it goes with the second person (`withoutRefusedCombinations`).
 */

// The dataset key of the marker, and the prefix of the literals' keys
const FILTER_KEY = "appFilter"

/** Binds the chips' remove buttons and Edit of the fragment's results title, when the search is a filtered one */
export function bindFilteredTitle({ fragmentEl }) {
    const chipEls = () =>
        Array.from(fragmentEl.querySelectorAll(`[${Const.attributes.filter}]`))

    chipEls().forEach((chipEl) => {
        chipEl
            .querySelector(`[${Const.attributes.chipRemove}]`)
            .addEventListener("click", () => {
                chipEl.remove()
                searchByFilters(filtersOf(chipEls()))
            })
    })

    fragmentEl
        .querySelector(`[${Const.attributes.editFilters}]`)
        ?.addEventListener("click", () => openSearchPanel())
}

/**
 * The search runs again when the filtered title on the page has a chip of the Person, which shows
 * their name and cover as the search found them: after a rename, the new search renders the chip
 * with the name as it is now. Whether it did.
 */
export function searchAgainForPersonChip(personId) {
    if (!showsPersonChip(personId)) {
        return false
    }

    runSearch()
    return true
}

/** Whether a filtered title on the page has a chip of the Person */
export function showsPersonChip(personId) {
    return Array.from(
        document.querySelectorAll(`[${Const.attributes.filter}]`),
    ).some((chipEl) => literalsOf(chipEl, FILTER_KEY).personId === personId)
}

/** The filters the chips name, the people and the Locations each as a list, with the search's people match */
function filtersOf(chipEls) {
    const filters = {
        peopleMatch:
            Alpine.store(Const.state.searchParams)?.peopleMatch ?? null,
    }

    chipEls.forEach((chipEl) => {
        Object.entries(literalsOf(chipEl, FILTER_KEY)).forEach(
            ([param, value]) => {
                filters[param] = ID_LISTS.includes(param)
                    ? [...idsOf(filters[param]), value].join(",")
                    : value
            },
        )
    })

    return filters
}
