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
    p: 1,
}

/**
 * What changing one parameter does to the others.
 *
 * Choosing a folder, a person, an album, or a Location are all "look somewhere else", so each clears
 * the other three; a view is a different place again and clears all four. The map area (`bbox`, the
 * crowded-pin panel's scope) belongs to the search it was drawn on, so looking somewhere else clears
 * it too. Everything else narrows or reorders what is already in scope - the layout, the sort and
 * the grouping survive all of them. This table is what the server's old `newSearch=true` flag used
 * to express, and it is the whole reason a widget can send just the one parameter it knows about.
 */
const CLEARS = {
    view: ["folderId", "personId", "albumId", "locationId", "bbox"],
    folderId: ["personId", "albumId", "locationId", "bbox"],
    personId: ["folderId", "albumId", "locationId", "bbox"],
    albumId: ["folderId", "personId", "locationId", "bbox"],
    locationId: ["folderId", "personId", "albumId", "bbox"],
    q: ["bbox"],
}

const NUMERIC = new Set(["p", "rpp"])

const PARAM_NAMES = Object.keys(DEFAULTS)

/**
 * Search parameters read out of a browser URL's query string. Unknown parameters are ignored. The
 * layout is the one exception to "the URL, then the defaults": a URL that says nothing about it gets
 * the layout last chosen in this browser (`localStorage`), so a map user opens on the map.
 */
export function seedSearchParams(search) {
    const urlParams = new URLSearchParams(search || "")
    const seeded = { ...DEFAULTS, layout: rememberedLayout() }

    PARAM_NAMES.forEach((name) => {
        if (urlParams.has(name)) {
            seeded[name] = normalize(name, urlParams.get(name))
        }
    })

    return seeded
}

function rememberedLayout() {
    const layout = localStorage.getItem(Const.localStore.resultsLayout)

    return Object.values(Const.search.layout).includes(layout)
        ? layout
        : DEFAULTS.layout
}

/**
 * `current` with `changes` applied under the scope rules above. Any change other than paging itself
 * returns to the first page - a new sort or a new folder has no page 3 to stay on.
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

    if (!changed.includes("p")) {
        next.p = DEFAULTS.p
    }

    return next
}

/**
 * The query string for `params`, with `overrides` layered on top. Parameters still at their default
 * are left out, and the order is fixed, so the same search always produces the same URL.
 *
 * An override that is not a search parameter (`isContinuousScroll`, the `after` cursor) is appended
 * as-is: those are per-request flags that must never end up in the store.
 *
 * A grouped search has no page number - it is continued by cursor - so `p` is left out whenever
 * `groupBy` is set: the server rejects the pair, and a `p` seeded from a hand-edited URL would
 * otherwise turn the whole search into a 400.
 */
export function serializeSearchParams(params, overrides = {}) {
    const query = new URLSearchParams()
    const effective = (name) =>
        name in overrides ? overrides[name] : params[name]
    const isGrouped = !isOmitted("groupBy", effective("groupBy"))

    PARAM_NAMES.forEach((name) => {
        const value = effective(name)

        if (isOmitted(name, value) || (name === "p" && isGrouped)) {
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

function normalize(name, value) {
    if (value === null || value === undefined || value === "") {
        return DEFAULTS[name]
    }

    if (NUMERIC.has(name)) {
        const num = Number(value)
        return Number.isFinite(num) ? num : DEFAULTS[name]
    }

    return String(value)
}
