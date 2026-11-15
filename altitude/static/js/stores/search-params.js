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
    describe: null,
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
 * Search text (`q`) is one more place to look: the whole of the view it is typed in, Browse or
 * Triage. So text clears the five and keeps the view, but for the trash, which text does not search
 * and so leaves for the repository view (`withoutRefusedCombinations`), and each of the five, and a
 * view, clears the text.
 *
 * A place set to nothing still clears the others, so a trigger with one empty literal (the Results
 * title's ×, `data-app-search-person-id=""`) goes back to the whole
 * view, from a folder, an album, a Location or a subset alike. Only the Triage summary offers a
 * subset, but the server takes one in every view. An empty value is never sent (`isOmitted`).
 *
 * The layout, the grouping and the sort narrow or reorder what is already in scope and survive all
 * of them; the one sort that does not outlive its search is Relevance, which goes with the text
 * (`withoutRefusedCombinations`). The triage filter (`triaged`, the View settings dialog's "Show
 * triaged items" in the repository view) survives them too, but belongs to that view: a change of
 * view clears it, and the store drops it from any other view. It is a choice as the layout is one:
 * remembered in this browser, and what a page load opens Browse with (`seedSearchParams`). This
 * table is what the server's old `newSearch=true` flag used to express, and it is the whole reason
 * a widget can send just the one parameter it knows about.
 *
 * The advanced search panel is the one widget that combines them: it sets a description, the text,
 * a folder, people and Locations together (`personId` and `locationId` then hold several IDs,
 * separated by commas), with how several people combine (`peopleMatch`) and the Date Taken and Date
 * Imported ranges, through `applySearchFilters`, which goes around this table. The panel's own
 * parameters (`PANEL_FILTERS`) are what looking somewhere else clears as well: a place, a view or
 * new text drops them, and with a place's single ID the several the panel chose.
 *
 * The panel also says which assets its search covers, in its "Search in" row: Library, Triage or
 * All. A pick is no parameter of its own but two the search has, the view and the triage filter:
 * the repository view without the triaged assets, the Triage view, or the repository view with
 * them. It lasts for the search it was made for and is not remembered: once that search is
 * replaced, by looking somewhere else or by the removal of its last filter, the triage filter is
 * the remembered choice again (`withRememberedTriaged`).
 *
 * A description (`describe`, the words of a Describe search) ranks what the other filters match on
 * the grid, in its one order, in Browse or in Triage. A search that holds one drops what the server
 * refuses beside it and no later search wants back, an album, a Triage subset, the map area and
 * the trash (`withoutRefusedCombinations`), and is sent without the sort, the grouping and
 * the layout (`DESCRIBED_UNSENT`), which the store keeps: they are the choices of the search after
 * it, and go out again once the description is gone.
 */
const PLACES = ["folderId", "personId", "albumId", "locationId", "subset"]

/** The parameters only the advanced search panel sets: the description, how several people combine, and the two date ranges */
const PANEL_FILTERS = [
    "describe",
    "peopleMatch",
    "takenFrom",
    "takenTo",
    "importedFrom",
    "importedTo",
]

/** Every filter of the advanced search panel, which a search from it sets together (`applySearchFilters`) */
const FILTERS = ["q", "folderId", "personId", "locationId", ...PANEL_FILTERS]

/** What a search from the panel returns to its default: it searches its view by its filters alone */
const FILTER_RESETS = ["albumId", "subset", "bbox"]

/**
 * What a search with a description is sent without, though the store holds it: the server refuses a
 * sort, a grouping and the map layout beside a description, whose results are a flat grid in the
 * order of their likeness
 */
const DESCRIBED_UNSENT = ["layout", "sort", "groupBy", "groupDirection"]

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
    q: [...PLACES, ...PANEL_FILTERS, "bbox"],
}

/** The numbers the server reads, each held to the whole values it accepts: a page size up to its bound */
const NUMBER_RANGES = {
    rpp: { min: 1, max: Const.search.maxRpp },
}

/**
 * The triage filter's two values, as the server reads them: the repository view with its triaged
 * assets beside the sorted ones, or without them
 */
const TRIAGED = { shown: "true", hidden: "false" }

/**
 * The words the server reads, each held to the values it accepts: a subset is one of the Triage
 * subsets, and the triage filter one of its two values
 */
const ALLOWED_VALUES = {
    subset: Object.values(Const.search.subsets),
    peopleMatch: Object.values(Const.search.peopleMatch),
    triaged: Object.values(TRIAGED),
}

/** The texts the server reads, each held to the length it accepts once the white space around it is dropped */
const TEXT_LENGTHS = {
    describe: Const.search.maxDescriptionLength,
}

/** The days the server reads, each an ISO date (`2024-06-01`) */
const DATE_PARAMS = ["takenFrom", "takenTo", "importedFrom", "importedTo"]

/** The two ends of each date range, the first no later than the last */
const DATE_RANGES = [
    ["takenFrom", "takenTo"],
    ["importedFrom", "importedTo"],
]

/** The parameters that hold one ID or several, separated by commas, at most `Const.search.maxIds` of them */
export const ID_LISTS = ["personId", "locationId"]

const PARAM_NAMES = Object.keys(DEFAULTS)

/**
 * Search parameters read out of a browser URL's query string. Unknown parameters are ignored, and so
 * is a page size, a subset, a people match, a triage filter, a day or a description the server
 * would refuse, and a list of people or Locations is held to the IDs the server takes (`normalize`).
 *
 * Two parameters are the exceptions to "the URL, then the defaults", each a choice remembered in
 * this browser (`localStorage`). A URL that says nothing about the layout gets the layout last
 * chosen, so a map user opens on the map. And a URL that says nothing the server reads about the
 * triage filter gets the choice last made with "Show triaged items" (`rememberedTriaged`), shown
 * when none was made, so the store of an account that sees triaged items (`seesTriaged`: a member,
 * as the page says) always holds one of the filter's two values in the repository view and its
 * requests always say which. A Guest sees no triaged item and is refused the filter by the server,
 * so its search is seeded none; without `seesTriaged` nothing is read from `localStorage`.
 *
 * A URL that names either wins and is not remembered: only a change is (`merge`). The layout it
 * names is the page's; the triage filter it names is its search's, the remembered choice's again
 * once that search is replaced (`withRememberedTriaged`).
 */
export function seedSearchParams(search, { seesTriaged = false } = {}) {
    const urlParams = new URLSearchParams(search || "")
    const seeded = { ...DEFAULTS, layout: rememberedLayout() }

    PARAM_NAMES.forEach((name) => {
        if (urlParams.has(name)) {
            seeded[name] = normalize(name, urlParams.get(name))
        }
    })

    if (seesTriaged) {
        seeded.triaged ??= rememberedTriaged()
    }

    return withoutRefusedCombinations(seeded)
}

/**
 * The combinations the server refuses, settled the way the scope rules would have: text, a date
 * range and several people or Locations search no trash, and leave it for the repository view; a date range
 * whose first day is after its last is no range; the Relevance sort goes with the text: a change
 * that clears the text drops it, so the server's default applies, while any other sort stays; and
 * the triage filter is the repository view's, so another view drops it (the server refuses it with
 * the triage view, and the View settings dialog offers it nowhere else), the remembered choice
 * among them. A URL can ask for any of them as well.
 * A people match says nothing of fewer than two people, so it goes with the second of them. What
 * only the advanced search panel combines searches by the panel's filters alone, so it drops an
 * album and a Triage subset (`combinesFilters`), which the server refuses beside it. A description
 * is one of those filters, and searches the repository view or the Triage view, on the grid: it
 * leaves the trash as the others do, and drops the map area. The sort, the grouping and the
 * layout it takes none of stay in the store and are left out of its requests instead
 * (`serializeSearchParams`), so they are in force again once it is gone.
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

    if (combinesFilters(params)) {
        params.albumId = DEFAULTS.albumId
        params.subset = DEFAULTS.subset
    }

    if (searchesOutsideTrash(params) && params.view === Const.views.trashbin) {
        params.view = DEFAULTS.view
    }

    if (params.describe !== null) {
        params.bbox = DEFAULTS.bbox
    }

    if (params.q === null && params.sort === Const.search.sortRelevance) {
        params.sort = DEFAULTS.sort
    }

    if (params.view !== Const.views.repository) {
        params.triaged = DEFAULTS.triaged
    }

    return params
}

/** Text, a description, a date range, or several people or Locations: what the server searches Browse or Triage for, never the trash */
function searchesOutsideTrash(params) {
    return params.q !== null || hasPanelFilter(params)
}

/** A description, a date range, or several people or Locations: what only the advanced search panel sets */
function hasPanelFilter(params) {
    return (
        params.describe !== null ||
        DATE_PARAMS.some((name) => params[name] !== null) ||
        ID_LISTS.some((name) => idsOf(params[name]).length > 1)
    )
}

/**
 * What only the advanced search panel combines, as the server tells it (`SearchRequestParser`,
 * `SearchResultsController.isFiltered`): a description, a date range, several people or Locations,
 * or more than one of the text, a folder, a person and a Location
 */
function combinesFilters(params) {
    const places = [
        params.q,
        params.folderId,
        params.personId,
        params.locationId,
    ].filter((value) => value !== null)

    return hasPanelFilter(params) || places.length > 1
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
 * The triage filter last chosen in this browser with "Show triaged items", for an account that sees
 * triaged items: shown until the switch is turned off, so with no choice made, or with anything
 * else under the key, Browse shows the triaged items too
 */
export function rememberedTriaged() {
    const triaged = localStorage.getItem(Const.localStore.showTriaged)

    return ALLOWED_VALUES.triaged.includes(triaged) ? triaged : TRIAGED.shown
}

/**
 * `params` with the triage filter remembered in this browser in place of the one a search brought:
 * the panel's pick of Library or All, or a URL's `triaged`, which last for their search alone. A
 * search that holds no triage filter, a Guest's or another view's, gets none.
 */
function withRememberedTriaged(params) {
    return params.triaged === null
        ? params
        : { ...params, triaged: rememberedTriaged() }
}

/**
 * `current` with `changes` applied under the scope rules above. The store holds no position: a
 * search starts at its first page, and a later page is one request's `after` cursor.
 *
 * A change that looks somewhere else (a place, a view or new text, the parameters of `CLEARS`)
 * replaces the search, and with it the triage filter that search brought (`withRememberedTriaged`),
 * unless the change names the filter itself.
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

    const settled = withoutRefusedCombinations(next)
    const looksElsewhere =
        !changed.includes("triaged") && changed.some((name) => name in CLEARS)

    return looksElsewhere ? withRememberedTriaged(settled) : settled
}

/**
 * `current` as a search from the advanced search panel leaves it: every filter of the panel
 * (`FILTERS`) is set as `filters` gives it, one left out being no filter, and the album, the Triage
 * subset and the map area return to their defaults, so the search is its view's by the panel's
 * filters alone.
 *
 * `filters` that name a `view` carry the panel's pick of the assets to search, the view and the
 * triage filter together: the repository view without the triaged assets or with them, or the
 * Triage view, which holds no filter. Without one (a Guest's panel, a chip removed from a filtered
 * search's title) the view and the triage filter stay, but for the trash, which a search from the
 * panel never searches; and when no filter is left the search is over, and the triage filter it
 * brought goes with it (`withRememberedTriaged`).
 *
 * The layout, the grouping, the sort and the page size stay, under the rules every change is held
 * to (`withoutRefusedCombinations`); a search with a description keeps the first three and sends
 * none of them (`DESCRIBED_UNSENT`).
 */
export function applySearchFilters(current, filters) {
    const next = { ...current }
    const picks = "view" in filters

    FILTERS.forEach((name) => {
        next[name] = normalize(name, filters[name])
    })
    FILTER_RESETS.forEach((name) => {
        next[name] = DEFAULTS[name]
    })

    if (picks) {
        next.view = normalize("view", filters.view)
        next.triaged = normalize("triaged", filters.triaged)
    } else if (next.view === Const.views.trashbin) {
        next.view = DEFAULTS.view
    }

    const settled = withoutRefusedCombinations(next)

    return !picks && countSearchFilters(settled) === 0
        ? withRememberedTriaged(settled)
        : settled
}

/** The advanced search panel's filters among `params`, each as the store holds it (null for none) */
export function searchFiltersOf(params) {
    return Object.fromEntries(FILTERS.map((name) => [name, params[name]]))
}

/**
 * How many of the advanced search panel's fields `params` fill: the description, the text, the
 * folder, the people, the Locations and each date range count once, however many people or
 * Locations are chosen.
 */
export function countSearchFilters(params) {
    return [
        params.describe,
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
 *
 * A search with a description is sent without its sort, grouping and layout (`DESCRIBED_UNSENT`),
 * whatever `params` and `overrides` hold of them.
 */
export function serializeSearchParams(params, overrides = {}) {
    const query = new URLSearchParams()
    const effective = (name) =>
        name in overrides ? overrides[name] : params[name]
    const isDescribed = !isOmitted("describe", effective("describe"))
    PARAM_NAMES.forEach((name) => {
        const value = effective(name)

        if (
            isOmitted(name, value) ||
            (isDescribed && DESCRIBED_UNSENT.includes(name))
        ) {
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

        /**
         * The browser URL is authoritative exactly once, on page load; after that the store is.
         * `seesTriaged` says whether the account sees triaged items, which seeds the triage filter
         * (`seedSearchParams`).
         */
        seedFromLocation(
            search = window.location.search,
            { seesTriaged = false } = {},
        ) {
            Object.assign(this, seedSearchParams(search, { seesTriaged }))
        },

        /**
         * A change of the layout or of the triage filter is a choice, remembered for the page
         * loads to come. The triage filter is remembered when the change names it, the "Show
         * triaged items" switch's, as one of its two values alone: the none a change of view
         * leaves is no choice, and neither is the remembered one a change of place puts back.
         */
        merge(changes) {
            const next = applySearchParamChanges(this.snapshot(), changes)

            if (next.layout !== this.layout) {
                localStorage.setItem(
                    Const.localStore.resultsLayout,
                    next.layout,
                )
            }

            if (
                "triaged" in changes &&
                next.triaged !== this.triaged &&
                next.triaged !== null
            ) {
                localStorage.setItem(Const.localStore.showTriaged, next.triaged)
            }

            Object.assign(this, next)
        },

        /**
         * A search from the advanced search panel: its filters replace the ones in force, and its
         * pick of the assets to search, when it carries one, the view and the triage filter
         * (`applySearchFilters`), which is not remembered. A filtered search's results title
         * removes one filter the same way, with the filters its other chips name and no pick.
         */
        replaceFilters(filters) {
            Object.assign(this, applySearchFilters(this.snapshot(), filters))
        },

        /** Whether the search names `id` in `param` (`personId`, `locationId`), alone or among several */
        names(param, id) {
            return idsOf(this[param]).includes(id)
        },

        /**
         * The search with `toId` in place of `fromId` among the IDs of `param` (`personId`,
         * `locationId`), or without `fromId` when `toId` is null: what a merge or a deletion of a
         * searched Person, or a deletion of a searched Location, leaves of it. The view and every
         * other filter stay; the map area goes, as it goes when the place changes, since it was
         * drawn on the search before.
         */
        replaceId(param, fromId, toId) {
            const ids = idsOf(this[param])
                .map((id) => (id === fromId ? toId : id))
                .filter(Boolean)

            Object.assign(
                this,
                withoutRefusedCombinations({
                    ...this.snapshot(),
                    [param]: [...new Set(ids)].join(",") || null,
                    bbox: DEFAULTS.bbox,
                }),
            )
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

        /**
         * Whether the results are laid out on the map. A search with a description is on the grid,
         * whatever layout the store keeps for the search after it.
         */
        showsMap() {
            return (
                this.describe === null &&
                this.layout === Const.search.layout.map
            )
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
            return this.triaged === TRIAGED.shown
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
 * A parameter's value as the store keeps it. A number, a word, a day or a text the server would
 * refuse is no opinion, like an empty one, so a hand-edited URL cannot turn every search into a 400:
 * the server's default applies, or for the triage filter the remembered choice (`seedSearchParams`). A description is kept without the white space around it, as the
 * server reads it, and is none when nothing is left of it or it is longer than the server takes. A
 * list of people or Locations is its IDs, each once, without the empty segments and the IDs past
 * the most a search names, which the server would refuse the whole list for.
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

    if (name in TEXT_LENGTHS) {
        const text = String(value).trim()
        return text !== "" && text.length <= TEXT_LENGTHS[name]
            ? text
            : DEFAULTS[name]
    }

    if (ID_LISTS.includes(name)) {
        const ids = [...new Set(idsOf(value))].slice(0, Const.search.maxIds)
        return ids.join(",") || DEFAULTS[name]
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
