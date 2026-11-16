import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"

/**
 * The single entry point for every search request. A caller supplies only what it knows (a sort, a
 * folder ID, a page cursor) and the `searchParams` store supplies the rest, so no widget needs to know
 * the others. The store is the only place the current search lives: the URL the server pushes back
 * (`HX-Replace-Url`) is a projection for bookmarking, never read back.
 *
 * `params`    are merged into the store, subject to its scope rules
 * `transient` go into this one request only, never stored (a continuation's cursor; the crowded-pin
 *             panel's grid layout while the store says map)
 * `target`    / `swap` are the htmx swap for this caller. Results swap in without settling (`settle:0`
 *             restates the global `defaultSettleDelay: 0`): settling would restore the server's
 *             attributes over what Alpine set meanwhile, and Triage's Curate row would hide again
 * `source`    is the element htmx issues the request from, for a caller that must recognise the
 *             response in the lifecycle events (the map panel)
 *
 * Resolves when the request completes, with the response swapped in.
 */
export function runSearch({
    params = {},
    transient = {},
    target = "#content",
    swap = "innerHTML settle:0",
    source = undefined,
} = {}) {
    Alpine.store(Const.state.searchParams).merge(params)

    return htmx.ajax("get", currentSearchUrl(transient), {
        target,
        swap,
        ...(source ? { source } : {}),
    })
}

/** Leaves the store untouched, so a continuation can ask for another page without moving the visible one */
function currentSearchUrl(overrides = {}) {
    const query = Alpine.store(Const.state.searchParams).toQueryString(
        overrides,
    )

    return `/htmx/search/r/${window.ctx.getRepoId()}?${query}`
}
