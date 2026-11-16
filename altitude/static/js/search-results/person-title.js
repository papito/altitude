/**
 * Reloads a Person's results title (`#person`) when it shows `personId`: the cover, the name and the
 * links, which follow the name and the count. With no title, or another Person's, nothing is
 * requested. A filtered search's chip of the Person is the search's to render instead.
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
