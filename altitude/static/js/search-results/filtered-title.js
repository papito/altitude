import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { literalsOf } from "../fragments/helpers.js"
import { ID_LISTS, idsOf } from "../stores/search-params.js"
import { openSearchPanel, searchByFilters } from "./search-panel.js"
import { runSearch } from "./search.js"

/**
 * The results title of a filtered search: a chip per filter, each with a × that removes it and
 * searches again, and Edit, which opens the advanced search panel.
 *
 * The chips are the state: each carries the parameters it stands for as `data-app-filter-<param>`
 * literals, and a × searches by what the remaining chips name, so a second × before the first search
 * answers searches without both. An ID that names nothing in the Library has no chip, so it goes with
 * the first removal.
 */

// The dataset key of the marker, and the prefix of the literals' keys
const FILTER_KEY = "appFilter"

/** Binds the chips' remove buttons and Edit, when the results title is a filtered one */
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
 * Runs the search again when the filtered title has a chip of the Person, which shows their name as
 * the search found it, so a rename shows; returns whether it did.
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

/** The filters the chips name, with the search's people match; the store drops that under two people */
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
