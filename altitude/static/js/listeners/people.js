import { Const } from "../constants.js"
import {
    getRequestSource,
    getResponseText,
    isRequestSuccessful,
} from "../common/htmx-events.js"
import { showSuccessSnackBar } from "../common/snackbar.js"
import { runSearch } from "../search-results/search.js"

const NAME_EDITOR_SELECTOR = '[data-app-fragment="person-name-editor"]'

/**
 * People events: merging (a drop of one person onto another confirms in a modal, whose success
 * shows the merged person), the inline name editor, cover face and discard follow-up on the
 * people list.
 */
export function registerPeopleListeners(app) {
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
        const mergeSourceId = event.detail.mergeSourceId
        const sourcePersonEl = htmx.find(`#person-${mergeSourceId}`)

        if (sourcePersonEl) {
            sourcePersonEl.remove()
        }

        showSuccessSnackBar("Person merged successfully")

        // The merge itself returns no content - showing the destination person is a search like any other
        runSearch({ params: { personId: event.detail.mergeDestId } })
    })

    /**
     * The name editor (`htmx/edit_person_name.scala.html`) replaces itself with the server's
     * response: the saved name, or the editor again with its validation errors. The two arrive
     * alike (HTTP 200, no header), so the one way to tell them apart is the response itself: it is
     * parsed, and only a response holding no editor is a saved name, which the people list is
     * then told about.
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
            newPersonName: responseDoc.body.textContent.trim(),
        })
    })

    document.body.addEventListener(Const.events.personNameEdited, (event) => {
        const personId = event.detail.personId
        const newPersonName = event.detail.newPersonName
        const personNameEl = htmx.find(`#person-${personId} .name a`)

        if (!personNameEl) {
            return
        }

        personNameEl.textContent = newPersonName
        personNameEl.classList.remove("unknown")
    })

    document.body.addEventListener(Const.events.personCoverFaceSet, (event) => {
        const personId = event.detail.personId
        const faceId = event.detail.faceId
        const imageEl = htmx.find(`#person-${personId} .image img`)

        if (!imageEl) {
            return
        }

        imageEl.src = `/content/r/${app.context.getRepoId()}/face/${faceId}`
    })

    document.body.addEventListener(
        Const.events.personMarkedAsBadMatch,
        (event) => {
            htmx.find(`#person-${event.detail.personId}`)?.remove()
        },
    )
}

/** Escape while the name editor is open restores the name as it was */
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
