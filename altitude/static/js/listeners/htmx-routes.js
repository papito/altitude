/**
 * Every search, including the pages continuous scroll appends, is a request to this route
 * (see `search-results/search.js`).
 */
export function isSearchRequest(requestPath) {
    return requestPath.startsWith("/htmx/search/r/")
}

export function isTrashPurgeRequest(requestPath) {
    return (
        requestPath.startsWith("/htmx/trash/r/") &&
        requestPath.endsWith("/purge")
    )
}
