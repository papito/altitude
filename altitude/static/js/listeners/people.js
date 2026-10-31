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
 * shows the merged person), the inline name editor, and the follow-up on the People tab. A
 * person's cell may be in both of the tab's panes, so a rename and a cover face change patch
 * every cell of the person, found by `data-person-id` under `#people`. A merge and a discard
 * move people between the People buckets instead, so they announce `peopleChanged`, which the
 * tab's `peopleTab` component answers by reloading each pane with its own buckets.
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
        app.dispatch(Const.events.peopleChanged, {
            personId: event.detail.mergeSourceId,
        })

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

    // A hidden person's name link carries an aria-label, which the server renders again on the
    // next reload; the patch leaves it alone
    document.body.addEventListener(Const.events.personNameEdited, (event) => {
        const newPersonName = event.detail.newPersonName

        personCellElements(event.detail.personId, ".name a").forEach(
            (personNameEl) => {
                personNameEl.textContent = newPersonName
                personNameEl.classList.remove("unknown")
            },
        )
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

/** The elements matching `selector` inside every cell of the person on the People tab */
function personCellElements(personId, selector) {
    return document.querySelectorAll(
        `#people [data-person-id="${personId}"] ${selector}`,
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
