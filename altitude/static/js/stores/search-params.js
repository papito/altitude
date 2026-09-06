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
    q: null,
    sort: null,
    rpp: null,
    p: 1,
}

/**
 * What changing one parameter does to the others.
 *
 * Choosing a folder and choosing a person are both "look somewhere else", so each clears the other;
 * a view is a different place again and clears both. Everything else narrows or reorders what is
 * already in scope. This table is what the server's old `newSearch=true` flag used to express, and
 * it is the whole reason a widget can send just the one parameter it knows about.
 */
const CLEARS = {
    view: ["folderId", "personId"],
    folderId: ["personId"],
    personId: ["folderId"],
}

const NUMERIC = new Set(["p", "rpp"])

const PARAM_NAMES = Object.keys(DEFAULTS)

/** Search parameters read out of a browser URL's query string. Unknown parameters are ignored. */
export function seedSearchParams(search) {
    const urlParams = new URLSearchParams(search || "")
    const seeded = { ...DEFAULTS }

    PARAM_NAMES.forEach((name) => {
        if (urlParams.has(name)) {
            seeded[name] = normalize(name, urlParams.get(name))
        }
    })

    return seeded
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
 * An override that is not a search parameter (`isContinuousScroll`) is appended as-is: those are
 * per-request flags that must never end up in the store.
 */
export function serializeSearchParams(params, overrides = {}) {
    const query = new URLSearchParams()

    PARAM_NAMES.forEach((name) => {
        const value = name in overrides ? overrides[name] : params[name]

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
            Object.assign(
                this,
                applySearchParamChanges(this.snapshot(), changes),
            )
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
