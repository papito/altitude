import { Const } from "../constants.js"

/**
 * Every parameter the search endpoint understands, in the order they are serialized.
 *
 * `null` means "no opinion": the parameter is left out of the request and the server's own default
 * applies, so a default is never spelled out on both sides. `view` is the exception - it is always
 * sent, which is why `Const.Search.View.DEFAULT` server-side is the same string as
 * `Const.views.repository` here.
 */
const DEFAULTS = {
    view: Const.views.repository,
    folderId: null,
    personId: null,
    albumId: null,
    locationId: null,
    peopleMatch: null,
    takenFrom: null,
    takenTo: null,
    importedFrom: null,
    importedTo: null,
    subset: null,
    q: null,
    bbox: null,
    triaged: null,
    layout: Const.search.layout.grid,
    sort: null,
    groupBy: null,
    groupDirection: null,
    rpp: null,
}

/**
 * What changing one parameter does to the others.
 *
 * Choosing a folder, a person, an album, a Location or a Triage subset (`subset`, the assets one of
 * the Triage summary's counts shows: those with Unknown people, or in no Location) are all "look somewhere
 * else", so each clears the other four; a view is a different place again and clears all five. The
 * map area (`bbox`, the crowded-pin panel's scope) belongs to the search it was drawn on, so looking
 * somewhere else clears it too.
 *
 * Search text (`q`) is one more place to look: the whole repository outside the trash. So text
 * clears the five and returns to the repository view (a cleared parameter is back at its default),
 * and each of the five, and a view, clears the text.
 *
 * A place set to nothing still clears the others, so a trigger with one empty literal (the Results
 * title's ×, `data-app-search-person-id=""`) goes back to the whole
 * view, from a folder, an album, a Location or a subset alike. Only the Triage summary offers a
 * subset, but the server takes one in every view. An empty value is never sent (`isOmitted`).
 *
 * The layout, the grouping and the sort narrow or reorder what is already in scope and survive all
 * of them; the one sort that does not outlive its search is Relevance, which goes with the text
 * (`withoutRefusedCombinations`). The triage filter (`triaged`, the toolbar's switch of the
 * repository view, off by default) survives them too, but belongs to that view: a change of view
 * clears it, and the store drops it from any other view. This table is what the server's old `newSearch=true` flag used to express, and it is the whole
 * reason a widget can send just the one parameter it knows about.
 *
 * The advanced search panel is the one widget that combines them: it sets the text, a folder,
 * people and Locations together (`personId` and `locationId` then hold several IDs, separated by
 * commas), with how several people combine (`peopleMatch`) and the Date Taken and Date Imported
 * ranges, through `applySearchFilters`, which goes around this table. The panel's own parameters
 * (`PANEL_FILTERS`) are what looking somewhere else clears as well: a place, a view or new text
 * drops them, and with a place's single ID the several the panel chose.
 */
const PLACES = ["folderId", "personId", "albumId", "locationId", "subset"]

/** The parameters only the advanced search panel sets: how several people combine, and the two date ranges */
const PANEL_FILTERS = [
    "peopleMatch",
    "takenFrom",
    "takenTo",
    "importedFrom",
    "importedTo",
]

/** Every filter of the advanced search panel, which a search from it sets together (`applySearchFilters`) */
const FILTERS = ["q", "folderId", "personId", "locationId", ...PANEL_FILTERS]

/** What a search from the panel returns to its default: it searches the repository view by its filters alone */
const FILTER_RESETS = ["view", "albumId", "subset", "bbox"]

/** What looking at `place` (or, with none, at another view) clears */
const elsewhere = (place) => [
    ...PLACES.filter((name) => name !== place),
    ...PANEL_FILTERS,
    "bbox",
    "q",
]

const CLEARS = {
    view: [...elsewhere(), "triaged"],
    folderId: elsewhere("folderId"),
    personId: elsewhere("personId"),
    albumId: elsewhere("albumId"),
    locationId: elsewhere("locationId"),
    subset: elsewhere("subset"),
    q: ["view", ...PLACES, ...PANEL_FILTERS, "bbox"],
}

/** The numbers the server reads, each held to the whole values it accepts: a page size up to its bound */
const NUMBER_RANGES = {
    rpp: { min: 1, max: Const.search.maxRpp },
}

/** The words the server reads, each held to the values it accepts: a subset is one of the Triage subsets */
const ALLOWED_VALUES = {
    subset: Object.values(Const.search.subsets),
    peopleMatch: Object.values(Const.search.peopleMatch),
}

/** The days the server reads, each an ISO date (`2024-06-01`) */
const DATE_PARAMS = ["takenFrom", "takenTo", "importedFrom", "importedTo"]

/** The two ends of each date range, the first no later than the last */
const DATE_RANGES = [
    ["takenFrom", "takenTo"],
    ["importedFrom", "importedTo"],
]

/** The parameters that hold one ID or several, separated by commas */
const ID_LISTS = ["personId", "locationId"]

const PARAM_NAMES = Object.keys(DEFAULTS)

/**
 * Search parameters read out of a browser URL's query string. Unknown parameters are ignored, and so
 * is a page size, a subset, a people match or a day the server would refuse (`normalize`). The layout is the one
 * exception to "the URL, then the defaults": a URL that says nothing about it gets the layout last
 * chosen in this browser (`localStorage`), so a map user opens on the map.
 */
export function seedSearchParams(search) {
    const urlParams = new URLSearchParams(search || "")
    const seeded = { ...DEFAULTS, layout: rememberedLayout() }

    PARAM_NAMES.forEach((name) => {
        if (urlParams.has(name)) {
            seeded[name] = normalize(name, urlParams.get(name))
        }
    })

    return withoutRefusedCombinations(seeded)
}

/**
 * The combinations the server refuses, settled the way the scope rules would have: text, a date
 * range and several people or Locations search the repository view, not the trash; a date range
 * whose first day is after its last is no range; the Relevance sort goes with the text: a change
 * that clears the text drops it, so the server's default applies, while any other sort stays; and
 * the triage filter is the repository view's, so another view drops it (the server refuses it with
 * the triage view, and the toolbar offers it nowhere else). A URL can ask for any of them as well.
 * A people match says nothing of fewer than two people, so it goes with the second of them.
 */
function withoutRefusedCombinations(params) {
    DATE_RANGES.forEach(([from, to]) => {
        if (
            params[from] !== null &&
            params[to] !== null &&
            params[from] > params[to]
        ) {
            params[from] = DEFAULTS[from]
            params[to] = DEFAULTS[to]
        }
    })

    if (idsOf(params.personId).length < 2) {
        params.peopleMatch = DEFAULTS.peopleMatch
    }

    if (searchesOutsideTrash(params) && params.view === Const.views.trashbin) {
        params.view = DEFAULTS.view
    }

    if (params.q === null && params.sort === Const.search.sortRelevance) {
        params.sort = DEFAULTS.sort
    }

    if (params.view !== Const.views.repository) {
        params.triaged = DEFAULTS.triaged
    }

    return params
}

/** Text, a date range, or several people or Locations: what the server searches the repository view for, never the trash */
function searchesOutsideTrash(params) {
    return (
        params.q !== null ||
        DATE_PARAMS.some((name) => params[name] !== null) ||
        ID_LISTS.some((name) => idsOf(params[name]).length > 1)
    )
}

/** The IDs a parameter holds: none, one, or several separated by commas */
export function idsOf(value) {
    return value ? String(value).split(",").filter(Boolean) : []
}

/** The ID a parameter holds when it holds exactly one, else null */
function oneIdOf(value) {
    const ids = idsOf(value)
    return ids.length === 1 ? ids[0] : null
}

function rememberedLayout() {
    const layout = localStorage.getItem(Const.localStore.resultsLayout)

    return Object.values(Const.search.layout).includes(layout)
        ? layout
        : DEFAULTS.layout
}

/**
 * `current` with `changes` applied under the scope rules above. The store holds no position: a
 * search starts at its first page, and a later page is one request's `after` cursor.
 */
export function applySearchParamChanges(current, changes) {
    const changed = Object.keys(changes).filter((name) =>
        PARAM_NAMES.includes(name),
    )

    if (changed.length === 0) {
        return { ...current }
    }

    const next = { ...current }

    changed.forEach((name) => {
        next[name] = normalize(name, changes[name])
        ;(CLEARS[name] || []).forEach((cleared) => {
            next[cleared] = DEFAULTS[cleared]
        })
    })

    return withoutRefusedCombinations(next)
}

/**
 * `current` as a search from the advanced search panel leaves it: every filter of the panel
 * (`FILTERS`) is set as `filters` gives it, one left out being no filter, and the view, the album,
 * the Triage subset and the map area return to their defaults, so the search is the repository
 * view's by the panel's filters alone. The layout, the grouping, the sort, the page size and the
 * triage filter stay, under the rules every change is held to (`withoutRefusedCombinations`).
 */
export function applySearchFilters(current, filters) {
    const next = { ...current }

    FILTERS.forEach((name) => {
        next[name] = normalize(name, filters[name])
    })
    FILTER_RESETS.forEach((name) => {
        next[name] = DEFAULTS[name]
    })

    return withoutRefusedCombinations(next)
}

/** The advanced search panel's filters among `params`, each as the store holds it (null for none) */
export function searchFiltersOf(params) {
    return Object.fromEntries(FILTERS.map((name) => [name, params[name]]))
}

/**
 * How many of the advanced search panel's fields `params` fill: the text, the folder, the people,
 * the Locations and each date range count once, however many people or Locations are chosen.
 */
export function countSearchFilters(params) {
    return [
        params.q,
        params.folderId,
        params.personId,
        params.locationId,
        ...DATE_RANGES.map(([from, to]) => params[from] ?? params[to]),
    ].filter((value) => value !== null).length
}

/**
 * The query string for `params`, with `overrides` layered on top. Parameters still at their default
 * are left out, and the order is fixed, so the same search always produces the same URL.
 *
 * An override that is not a search parameter (`isContinuousScroll`, the `after` cursor) is appended
 * as-is: those are per-request flags that must never end up in the store.
 *
 * An `rpp` that is not a whole number in range never gets this far (`normalize`).
 */
export function serializeSearchParams(params, overrides = {}) {
    const query = new URLSearchParams()
    const effective = (name) =>
        name in overrides ? overrides[name] : params[name]
    PARAM_NAMES.forEach((name) => {
        const value = effective(name)

        if (isOmitted(name, value)) {
            return
        }

        query.set(name, String(value))
    })

    Object.entries(overrides).forEach(([name, value]) => {
        if (
            PARAM_NAMES.includes(name) ||
            value === null ||
            value === undefined ||
            value === false
        ) {
            return
        }

        query.set(name, String(value))
    })

    return query.toString()
}

export function createSearchParamsStore() {
    return {
        ...DEFAULTS,

        /** The browser URL is authoritative exactly once, on page load; after that the store is. */
        seedFromLocation(search = window.location.search) {
            Object.assign(this, seedSearchParams(search))
        },

        merge(changes) {
            const next = applySearchParamChanges(this.snapshot(), changes)

            if (next.layout !== this.layout) {
                localStorage.setItem(
                    Const.localStore.resultsLayout,
                    next.layout,
                )
            }

            Object.assign(this, next)
        },

        /**
         * A search from the advanced search panel: its filters replace the ones in force and the
         * scope returns to the repository view (`applySearchFilters`). Removing one filter is the
         * current ones (`filters()`) with that one set to null.
         */
        replaceFilters(filters) {
            Object.assign(this, applySearchFilters(this.snapshot(), filters))
        },

        /** The advanced search panel's filters as they are in force */
        filters() {
            return searchFiltersOf(this.snapshot())
        },

        /**
         * The Person whose results are displayed, when the search names exactly one: none for a
         * search of several people, whose assets a correction of one Face does not sort.
         */
        viewedPersonId() {
            return oneIdOf(this.personId)
        },

        /**
         * The Location whose results are displayed, when the search names exactly one: none for a
         * search of several Locations, which the batch ops can remove a selection from none of.
         */
        viewedLocationId() {
            return oneIdOf(this.locationId)
        },

        /** How many of the panel's fields the search in force fills: the number on the nav's filter button */
        filterCount() {
            return countSearchFilters(this.snapshot())
        },

        snapshot() {
            const params = {}
            PARAM_NAMES.forEach((name) => {
                params[name] = this[name]
            })
            return params
        },

        toQueryString(overrides = {}) {
            return serializeSearchParams(this.snapshot(), overrides)
        },

        /** Whether triaged items are shown: only `triaged=true` shows them, the server's rule as well */
        showsTriaged() {
            return this.triaged === "true"
        },
    }
}

function isOmitted(name, value) {
    if (value === null || value === undefined || value === "") {
        return true
    }

    // `view` is always sent so the server never has to guess which of its views we mean
    return name !== "view" && value === DEFAULTS[name]
}

/**
 * A parameter's value as the store keeps it. A number, a word or a day the server would refuse is no
 * opinion, like an empty one, so a hand-edited URL cannot turn every search into a 400: the server's
 * default applies.
 */
function normalize(name, value) {
    if (value === null || value === undefined || value === "") {
        return DEFAULTS[name]
    }

    if (name in NUMBER_RANGES) {
        const num = Number(value)
        const { min, max } = NUMBER_RANGES[name]
        return Number.isInteger(num) && num >= min && num <= max
            ? num
            : DEFAULTS[name]
    }

    if (name in ALLOWED_VALUES) {
        return ALLOWED_VALUES[name].includes(String(value))
            ? String(value)
            : DEFAULTS[name]
    }

    if (DATE_PARAMS.includes(name)) {
        return isIsoDate(String(value)) ? String(value) : DEFAULTS[name]
    }

    return String(value)
}

/** Whether `value` is a calendar day written as the server reads one, `2024-06-01` */
function isIsoDate(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
        return false
    }

    const day = new Date(`${value}T00:00:00Z`)
    return !Number.isNaN(day.getTime()) && day.toISOString().startsWith(value)
}
