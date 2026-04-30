export function isTrashPurgeRequest(requestPath) {
    return (
        requestPath.startsWith("/htmx/trash/r/") &&
        requestPath.endsWith("/purge")
    )
}
