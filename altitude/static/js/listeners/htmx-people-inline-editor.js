import { Const } from "../constants.js"
import {
    getRequestPath,
    getResponseText,
    isRequestSuccessful,
} from "../common/htmx-events.js"

export function handlePeopleAfterRequest({ app, event }) {
    const requestPath = getRequestPath(event)
    const discardPersonElement = getDiscardPersonElement(event)
    const personNameEditorElement = getPersonNameEditorElement(event)

    if (
        isPersonNameEditRequest({ app, requestPath, personNameEditorElement })
    ) {
        handlePersonNameEditAfterRequest({
            app,
            event,
            personNameEditorElement,
        })
        return true
    }

    if (isDiscardPersonRequest({ app, requestPath, discardPersonElement })) {
        if (!isRequestSuccessful(event)) {
            return true
        }

        app.dispatch(Const.events.personMarkedAsBadMatch, {
            personId: discardPersonElement.getAttribute(
                Const.attributes.personId,
            ),
        })
        return true
    }

    return false
}

export function handlePeopleEscapeKeyPressed() {
    const personNameEditorElement = document.querySelector(
        '[data-app-fragment="person-name-editor"]',
    )
    if (!personNameEditorElement) {
        return false
    }

    htmx.ajax("GET", personNameEditorElement.dataset.appRestoreUrl, {
        swap: "innerHTML",
        target: "#personName",
    })

    return true
}

function getDiscardPersonElement(event) {
    return event.target?.closest?.("#markAsBadMatch") ?? null
}

function isDiscardPersonRequest({ app, requestPath, discardPersonElement }) {
    return (
        requestPath.startsWith(
            `/htmx/people/r/${app.context.getRepoId()}/p/`,
        ) && discardPersonElement !== null
    )
}

function getPersonNameEditorElement(event) {
    return event.target?.closest?.("#editPersonName") ?? null
}

function isPersonNameEditRequest({
    app,
    requestPath,
    personNameEditorElement,
}) {
    return (
        requestPath.startsWith(
            `/htmx/people/r/${app.context.getRepoId()}/p/`,
        ) &&
        requestPath.endsWith("/name/edit") &&
        personNameEditorElement !== null
    )
}

function handlePersonNameEditAfterRequest({
    app,
    event,
    personNameEditorElement,
}) {
    if (!isRequestSuccessful(event)) {
        return
    }

    const responseText = getResponseText(event)

    if (responseText.includes('id="editPersonName"')) {
        return
    }

    const responseEl = document.createElement("div")
    responseEl.innerHTML = responseText
    const newPersonName = responseEl.textContent?.trim() || ""

    app.dispatch(Const.events.personNameEdited, {
        personId: personNameEditorElement.dataset.appPersonId,
        newPersonName,
    })
}
