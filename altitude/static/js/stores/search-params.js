import { Const } from "../constants.js"

/**
 * Every parameter the search endpoint understands, in serialization order. `null` means "no opinion":
 * the parameter is left out and the server's default applies, so a default is never spelled out on
 * both sides. `view` is always sent, so `Const.Search.View.DEFAULT` server-side is the same string as
 * `Const.views.repository`.
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
 * What changing one parameter does to the others, so a widget can send just the one it knows.
 *
 * A folder, a person, an album, a Location or a Triage subset each clears the other four; a view
 * clears all five. The map area (`bbox`) belongs to the search it was drawn on, so looking somewhere
 * else clears it too. Search text (`q`) is one more place, the whole of its view: it clears the five
 * and keeps the view (but leaves the trash, which text does not search), and each place, or a view,
 * clears the text. A place set to nothing still clears the others, so an empty trigger (the Results
 * title's ×) goes back to the whole view.
 *
 * The layout, the grouping and the sort survive all of them, but Relevance goes with the text and
 * Similarity with the description. The triage filter (`triaged`) survives them too, but belongs to
 * the repository view: a change of view clears it. Like the layout, it is remembered in this browser.
 *
 * The advanced search panel combines them through `applySearchFilters`, around this table; looking
 * somewhere else clears its own parameters (`PANEL_FILTERS`) as well. Its "Search in" pick is a view
 * and a triage filter that last for its search alone: once that search is replaced, the triage filter
 * is the remembered choice again.
 *
 * A description (`describe`) searches what the other filters match, on the grid. It drops what the
 * server refuses beside it (an album, a Triage subset, the map area, the trash) and is sent without
 * the layout, which the store keeps for the search after it. A new or changed description takes the
 * Similarity sort, so it opens most alike first.
 */
const PLACES = ["folderId", "personId", "albumId", "locationId", "subset"]

/** The parameters only the advanced search panel sets */
const PANEL_FILTERS = [
    "describe",
    "peopleMatch",
    "takenFrom",
    "takenTo",
    "importedFrom",
    "importedTo",
]

/** Every filter of the advanced search panel, set together by a search from it */
const FILTERS = ["q", "folderId", "personId", "locationId", ...PANEL_FILTERS]

/** Reset by a search from the panel, which searches its view by its filters alone */
const FILTER_RESETS = ["albumId", "subset", "bbox"]

/** The server refuses the map layout beside a description; the store keeps it for the search after */
const DESCRIBED_UNSENT = ["layout"]

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

/** The numbers the server reads, each held to the whole values it accepts */
const NUMBER_RANGES = {
    rpp: { min: 1, max: Const.search.maxRpp },
}

/** The repository view with its triaged assets beside the sorted ones, or without them */
const TRIAGED = { shown: "true", hidden: "false" }

/** The words the server reads, each held to the values it accepts */
const ALLOWED_VALUES = {
    subset: Object.values(Const.search.subsets),
    peopleMatch: Object.values(Const.search.peopleMatch),
    triaged: Object.values(TRIAGED),
}

/** The texts the server reads, each held to the length it accepts once trimmed */
const TEXT_LENGTHS = {
    describe: Const.search.maxDescriptionLength,
}

/** ISO dates (`2024-06-01`) */
const DATE_PARAMS = ["takenFrom", "takenTo", "importedFrom", "importedTo"]

/** Each range's first day is no later than its last */
const DATE_RANGES = [
    ["takenFrom", "takenTo"],
    ["importedFrom", "importedTo"],
]

/** One ID or several, separated by commas, at most `Const.search.maxIds` of them */
export const ID_LISTS = ["personId", "locationId"]

const PARAM_NAMES = Object.keys(DEFAULTS)

/**
 * Search parameters read out of a browser URL's query string; what the server would refuse is
 * ignored (`normalize`). A URL that says nothing of the layout gets the one last chosen in this
 * browser. For an account that sees triaged items, one that says nothing of the triage filter gets
 * the remembered choice (shown when none was made), so its repository view's requests always say
 * which; a Guest's is seeded none, without reading `localStorage`. A URL that names either wins but is
 * not remembered: only a change is (`merge`).
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
 * The combinations the server refuses, settled as the scope rules would: text and the panel's
 * filters search no trash, so they leave it for the repository view; a reversed date range is none;
 * Relevance goes with the text and Similarity with the description; the triage filter is the
 * repository view's alone; a people match goes with the second person; what only the panel combines
 * drops an album and a Triage subset; a description drops the map area. A URL can ask for any of them.
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

    if (
        (params.q === null && params.sort === Const.search.sortRelevance) ||
        (params.describe === null &&
            params.sort === Const.search.sortSimilarity)
    ) {
        params.sort = DEFAULTS.sort
    }

    if (params.view !== Const.views.repository) {
        params.triaged = DEFAULTS.triaged
    }

    return params
}

/** What the server searches Browse or Triage for, never the trash */
function searchesOutsideTrash(params) {
    return params.q !== null || hasPanelFilter(params)
}

/** What only the advanced search panel sets */
function hasPanelFilter(params) {
    return (
        params.describe !== null ||
        DATE_PARAMS.some((name) => params[name] !== null) ||
        ID_LISTS.some((name) => idsOf(params[name]).length > 1)
    )
}

/**
 * What only the advanced search panel combines, as the server tells it (`SearchRequestParser`,
 * `SearchResultsController.isFiltered`)
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

export function idsOf(value) {
    return value ? String(value).split(",").filter(Boolean) : []
}

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
 * Shown until the "Show triaged items" switch is turned off: with no choice made, or anything else
 * under the key, Browse shows the triaged items too
 */
export function rememberedTriaged() {
    const triaged = localStorage.getItem(Const.localStore.showTriaged)

    return ALLOWED_VALUES.triaged.includes(triaged) ? triaged : TRIAGED.shown
}

/**
 * The remembered triage filter in place of the one a search brought (the panel's pick, a URL's), which
 * lasts for its search alone. A search with none, a Guest's or another view's, gets none.
 */
function withRememberedTriaged(params) {
    return params.triaged === null
        ? params
        : { ...params, triaged: rememberedTriaged() }
}

/**
 * The store holds no position: a search starts at its first page, and a later page is one request's
 * `after` cursor. A change that looks somewhere else replaces the search, and with it the triage
 * filter it brought, unless the change names the filter itself.
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
 * Every panel filter is set as `filters` gives it (one left out is none), and the album, the subset
 * and the map area are reset. A `view` in `filters` is the panel's pick, with its triage filter.
 * Without one the view and the triage filter stay, but a search from the panel never searches the
 * trash, and with no filter left the search's triage filter goes. A new or changed description takes
 * the Similarity sort; the filters around an unchanged one leave its sort.
 */
export function applySearchFilters(current, filters) {
    const next = { ...current }
    const picks = "view" in filters

    FILTERS.forEach((name) => {
        next[name] = normalize(name, filters[name])
    })
    if (next.describe !== null && next.describe !== current.describe) {
        next.sort = Const.search.sortSimilarity
    }
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

export function searchFiltersOf(params) {
    return Object.fromEntries(FILTERS.map((name) => [name, params[name]]))
}

/** Each date range counts once, and so do the people and the Locations however many are chosen */
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
 * Defaults are left out and the order is fixed, so the same search always produces the same URL. An
 * override that is not a search parameter (`isContinuousScroll`, the `after` cursor) is appended
 * as-is, so a per-request flag never ends up in the store. A described search goes without its layout.
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

        /** The browser URL is authoritative exactly once, on page load; after that the store is */
        seedFromLocation(
            search = window.location.search,
            { seesTriaged = false } = {},
        ) {
            Object.assign(this, seedSearchParams(search, { seesTriaged }))
        },

        /**
         * A change of the layout or of the triage filter is remembered for the page loads to come; the
         * triage filter only when the change names it, since neither the none a change of view leaves
         * nor the remembered one a change of place puts back is a choice.
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
         * A search from the advanced search panel, its pick not remembered. A results title's chip
         * removes one filter the same way, with no pick.
         */
        replaceFilters(filters) {
            Object.assign(this, applySearchFilters(this.snapshot(), filters))
        },

        names(param, id) {
            return idsOf(this[param]).includes(id)
        },

        /**
         * What a merge or a deletion of a searched Person, or a deletion of a searched Location,
         * leaves of the search (`toId` null drops `fromId`). The map area goes, drawn on the search before.
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

        filters() {
            return searchFiltersOf(this.snapshot())
        },

        /** None for a search of several people, whose assets a correction of one Face does not sort */
        viewedPersonId() {
            return oneIdOf(this.personId)
        },

        /** None for a search of several Locations, which the batch ops can remove a selection from none of */
        viewedLocationId() {
            return oneIdOf(this.locationId)
        },

        /** A described search is on the grid, whatever layout the store keeps for the search after it */
        showsMap() {
            return (
                this.describe === null &&
                this.layout === Const.search.layout.map
            )
        },

        /** The number on the nav's filter button */
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
 * A value the server would refuse is no opinion, like an empty one, so a hand-edited URL cannot turn
 * every search into a 400. A description is trimmed, and none when empty or too long. An ID list keeps
 * each ID once, up to the most a search names, since the server refuses the whole list past it.
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

/** Whether `value` is a real calendar day, written as the server reads one */
function isIsoDate(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
        return false
    }

    const day = new Date(`${value}T00:00:00Z`)
    return !Number.isNaN(day.getTime()) && day.toISOString().startsWith(value)
}
