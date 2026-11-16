import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import {
    getRequestSource,
    getResponseText,
    isRequestSuccessful,
} from "../common/htmx-events.js"
import { showSuccessSnackBar } from "../common/snackbar.js"
import { searchAgainForPersonChip } from "../search-results/filtered-title.js"
import { reloadPersonTitle } from "../search-results/person-title.js"
import { runSearch } from "../search-results/search.js"

const NAME_EDITOR_SELECTOR = '[data-app-fragment="person-name-editor"]'

/**
 * A person's cell may be in both of the People tab's panes, so a cover face change patches every cell
 * of the person. A merge, a discard or a rename can move people between buckets (a rename makes an
 * Unmatched person Matched), so those announce `peopleChanged` and the panes reload instead.
 */
export function registerPeopleListeners(app) {
    // The whole detail goes out as the modal's request values
    document.body.addEventListener(Const.events.confirmPersonMerge, (event) => {
        const mergeSourceId = event.detail.mergeSourceId
        const mergeDestId = event.detail.mergeDestId

        if (mergeSourceId === mergeDestId) {
            return
        }

        htmx.ajax(
            "GET",
            `/htmx/people/r/${app.context.getRepoId()}/modals/merge`,
            {
                swap: "innerHTML",
                target: "#modalContent",
                values: { ...event.detail },
            },
        )
    })

    document.body.addEventListener(Const.events.personMerged, (event) => {
        const { mergeSourceId, mergeDestId } = event.detail

        app.dispatch(Const.events.peopleChanged, { personId: mergeSourceId })

        showSuccessSnackBar("Person merged successfully")

        // A merge runs no search of its own; only results naming one of the two are stale. The source
        // is gone, so the destination takes its place in the search
        const searchParams = Alpine.store(Const.state.searchParams)

        if (searchParams.names("personId", mergeSourceId)) {
            searchParams.replaceId("personId", mergeSourceId, mergeDestId)
            runSearch()
        } else if (searchParams.names("personId", mergeDestId)) {
            runSearch()
        }
    })

    /**
     * The name editor swaps in the server's response: the saved name, or the editor again with its
     * errors. Both arrive as a plain 200, so only parsing the response tells them apart. The response
     * itself shows the new name in the results title (`titleShowsName`).
     */
    document.body.addEventListener("htmx:after:request", (event) => {
        const editorEl =
            getRequestSource(event)?.closest?.(NAME_EDITOR_SELECTOR)

        if (!editorEl || !isRequestSuccessful(event)) {
            return
        }

        const responseDoc = new DOMParser().parseFromString(
            getResponseText(event),
            "text/html",
        )

        if (responseDoc.querySelector(NAME_EDITOR_SELECTOR)) {
            return
        }

        app.dispatch(Const.events.personNameEdited, {
            personId: editorEl.dataset.appPersonId,
            titleShowsName: true,
        })
    })

    /**
     * The People tab reloads rather than patching the cell, since a rename can move the person from
     * Unmatched to Matched. A results title showing the person is read again unless the response of
     * its own editor already shows the name; a filtered search's person chip comes from the search,
     * which runs again.
     */
    document.body.addEventListener(Const.events.personNameEdited, (event) => {
        const { personId, titleShowsName } = event.detail

        app.dispatch(Const.events.peopleChanged, { personId })

        if (!titleShowsName) {
            reloadPersonTitle(app.context.getRepoId(), personId)
        }
        searchAgainForPersonChip(personId)
    })

    document.body.addEventListener(Const.events.personCoverFaceSet, (event) => {
        const src = `/content/r/${app.context.getRepoId()}/face/${event.detail.faceId}`

        personCellElements(event.detail.personId, ".image img").forEach(
            (imageEl) => {
                imageEl.src = src
            },
        )
    })

    document.body.addEventListener(
        Const.events.personMarkedAsBadMatch,
        (event) => {
            app.dispatch(Const.events.peopleChanged, {
                personId: event.detail.personId,
            })
        },
    )
}

function personCellElements(personId, selector) {
    return document.querySelectorAll(
        `#people [data-person-id="${personId}"] ${selector}`,
    )
}

/** Escape while the name editor is open restores the saved name; returns whether it did */
export function handlePeopleEscapeKeyPressed() {
    const editorEl = document.querySelector(NAME_EDITOR_SELECTOR)
    if (!editorEl) {
        return false
    }

    htmx.ajax("GET", editorEl.dataset.appRestoreUrl, {
        swap: "innerHTML",
        target: "#personName",
    })

    return true
}
