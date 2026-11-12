/**
 * A Person's results title (`htmx/person`, `#person`), read again when it shows `personId`: the
 * title route (`GET /htmx/people/r/:repoId/p/:personId/title`, `htmx/person_inner`) renders its
 * whole inside, the cover, the name and the Hide / Show / Discard links, which follow the name (a
 * named Person is Matched) and the count. A rename from a People cell's pencil and a Face
 * correction in the Detail panel both read it this way; with no title, or another Person's,
 * nothing is requested. A filtered search has no such title: its chip of the Person is rendered by
 * the search, which runs again after a rename (`searchAgainForPersonChip` in `filtered-title.js`).
 */
export function reloadPersonTitle(repoId, personId) {
    if (document.getElementById("person")?.dataset.personId !== personId) {
        return
    }

    htmx.ajax("GET", `/htmx/people/r/${repoId}/p/${personId}/title`, {
        target: "#person",
        swap: "innerHTML",
    })
}
