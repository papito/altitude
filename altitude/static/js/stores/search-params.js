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
    q: null,
    bbox: null,
    layout: Const.search.layout.grid,
    sort: null,
    groupBy: null,
    groupDirection: null,
    rpp: null,
}

/**
 * What changing one parameter does to the others.
 *
 * Choosing a folder, a person, an album, or a Location are all "look somewhere else", so each clears
 * the other three; a view is a different place again and clears all four. The map area (`bbox`, the
 * crowded-pin panel's scope) belongs to the search it was drawn on, so looking somewhere else clears
 * it too.
 *
 * Search text (`q`) is one more place to look: the whole repository outside the trash. So text
 * clears the four and returns to the repository view (a cleared parameter is back at its default),
 * and each of the four, and a view, clears the text.
 *
 * The layout, the grouping and the sort narrow or reorder what is already in scope and survive all
 * of them; the one sort that does not outlive its search is Relevance, which goes with the text
 * (`withoutRefusedCombinations`). This table is what the server's old `newSearch=true` flag used to express, and it is the whole
 * reason a widget can send just the one parameter it knows about.
 */
const PLACES = ["folderId", "personId", "albumId", "locationId"]

/** What looking at `place` (or, with none, at another view) clears */
const elsewhere = (place) => [
    ...PLACES.filter((name) => name !== place),
    "bbox",
    "q",
]

const CLEARS = {
    view: elsewhere(),
    folderId: elsewhere("folderId"),
    personId: elsewhere("personId"),
    albumId: elsewhere("albumId"),
    locationId: elsewhere("locationId"),
    q: ["view", ...PLACES, "bbox"],
}

/** The numbers the server reads, each held to the whole values it accepts: a page size up to its bound */
const NUMBER_RANGES = {
    rpp: { min: 1, max: Const.search.maxRpp },
}

const PARAM_NAMES = Object.keys(DEFAULTS)

/**
 * Search parameters read out of a browser URL's query string. Unknown parameters are ignored, and so
 * is a page size the server would refuse (`normalize`). The layout is the one
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
 * The two combinations the server refuses, settled the way the scope rules would have: text
 * searches the repository view, not the trash, and the Relevance sort goes with the text: a change
 * that clears the text drops it, so the server's default applies, while any other sort stays. A URL
 * can ask for either combination as well.
 */
function withoutRefusedCombinations(params) {
    if (params.q !== null && params.view === Const.views.trashbin) {
        params.view = DEFAULTS.view
    }

    if (params.q === null && params.sort === Const.search.sortRelevance) {
        params.sort = DEFAULTS.sort
    }

    return params
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
 * A parameter's value as the store keeps it. A number the server would refuse is no opinion, like an
 * empty one, so a hand-edited URL cannot turn every search into a 400: the server's default applies.
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

    return String(value)
}
