import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"

/**
 * The single entry point for every search request in the app.
 *
 * A caller supplies only what it knows - the sort widget its sort, a folder its ID, the last cell
 * its page - and the `searchParams` store supplies everything else. That is the point: no widget
 * has to be aware of the other widgets, and the server no longer has to reconstruct the parameter
 * set by merging the request with the browser URL.
 *
 * The store is the only place the current search lives. The friendly URL the server pushes back
 * (`HX-Replace-Url`) is a projection of it for bookmarking, and is never read back.
 *
 * `params`    are merged into the store, subject to its scope rules (see `stores/search-params.js`)
 * `transient` are serialized into this one request only and never stored (continuous scroll)
 * `target`    / `swap` are the htmx swap for this caller
 */
export function runSearch({
    params = {},
    transient = {},
    target = "#content",
    swap = "innerHTML",
} = {}) {
    Alpine.store(Const.state.searchParams).merge(params)

    return htmx.ajax("get", currentSearchUrl(transient), { target, swap })
}

/**
 * The URL the current search parameters would request, with `overrides` layered on top. Leaves the
 * store untouched, so shadow-result paging can fetch another page without moving the visible one.
 */
export function currentSearchUrl(overrides = {}) {
    const query = Alpine.store(Const.state.searchParams).toQueryString(
        overrides,
    )

    return `/htmx/search/r/${window.ctx.getRepoId()}?${query}`
}
