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
 * People events: merging (a drop of one person onto another confirms in a modal, whose success
 * searches nothing unless the results show one of the two), renaming (the results
 * title's inline name editor and the rename dialog of a cell's pencil, one outcome for both), and
 * the follow-up on the People tab. A person's cell may be in both of the tab's panes, so a cover
 * face change patches every cell of the person, found by `data-person-id` under `#people`. A
 * merge, a discard and a rename move people between the People buckets instead (a rename names an
 * Unmatched person, who is Matched from then on), so they announce `peopleChanged`, which the
 * tab's `peopleTab` component answers by reloading each pane with its own buckets, and the Triage
 * summary by counting Unknown people again (`listeners/triage.js`). An asset
 * mutation announces it as well (`refreshCounts` in `assets/asset-actions.js`), since a person's
 * number follows their assets.
 */
export function registerPeopleListeners(app) {
    // The whole detail goes out as the modal's request values: the two people, and from a rename
    // form's offer `keepDirection`, with the rename dialog's `pane`, whose pencil the modal returns
    // focus to (`htmx/merge_people_modal.scala.html`)
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

        // A merge runs no search of its own, in Browse and Triage alike: results that are neither
        // person's stay as they are. Results of a search naming one of the two are stale, though.
        // The source is gone, so the destination takes their place in the search, in the same view
        // and with every other filter of a filtered search; results naming the destination are
        // read again with the assets the merge gave it
        const searchParams = Alpine.store(Const.state.searchParams)

        if (searchParams.names("personId", mergeSourceId)) {
            searchParams.replaceId("personId", mergeSourceId, mergeDestId)
            runSearch()
        } else if (searchParams.names("personId", mergeDestId)) {
            runSearch()
        }
    })

    /**
     * The name editor (`htmx/edit_person_name.scala.html`) replaces itself with the server's
     * response: the saved name, or the editor again with its validation errors. The two arrive
     * alike (HTTP 200, no header), so the one way to tell them apart is the response itself: it is
     * parsed, and only a response holding no editor is a saved name, which is then announced as
     * the rename dialog's success is (`htmx/rename_person_dialog.scala.html` declares the event).
     * The response is about to show the name in the results title, which `titleShowsName` says.
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
     * A person (`detail.personId`) was renamed, from the results title's editor or from a cell's
     * pencil. The People tab reloads rather than patching the cell's text: a rename can move the
     * person from Unmatched to Matched, and the list the server renders has them in the right
     * bucket, with the cell's number and a hidden person's ", hidden" text intact. A results title
     * showing that person is read again (`reloadPersonTitle`), the name with the links a name
     * changes, unless the rename came from its own editor, whose response is the new name
     * (`detail.titleShowsName`). A filtered search's title has a chip for them instead, which the
     * search renders: the search runs again (`searchAgainForPersonChip`).
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
