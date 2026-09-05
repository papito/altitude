import { Const } from "../constants.js"
import { closeModal, showModal } from "../common/modal.js"
import { isRequestSuccessful } from "../common/htmx-events.js"
import {
    focusFragmentElement,
    parseFragmentDetail,
    parseFragmentTargetDetail,
} from "./helpers.js"

export function hydrateModalFragment({
    fragmentEl,
    context,
    dispatch,
    closeFolderContextMenu,
}) {
    showModal({
        minWidthPx: fragmentEl.dataset.appModalMinWidth,
        title: fragmentEl.dataset.appModalTitle,
    })

    initializeModalFragment({ fragmentEl, context, dispatch })
    focusFragmentElement(
        fragmentEl,
        fragmentEl.dataset.appModalAutofocusSelector,
        fragmentEl.dataset.appModalSelectOnFocus === "true",
    )
    bindModalFragment({
        fragmentEl,
        dispatch,
        closeFolderContextMenu,
    })
}

function initializeModalFragment({ fragmentEl, context, dispatch }) {
    if (fragmentEl.dataset.appModalKind === "view-settings") {
        initializeViewSettingsModalFragment({ fragmentEl, context, dispatch })
    }
}

function initializeViewSettingsModalFragment({
    fragmentEl,
    context,
    dispatch,
}) {
    const showFields = context.getGridMetadataFields()

    fragmentEl
        .querySelectorAll('input[type="checkbox"]')
        .forEach((checkboxEl) => {
            checkboxEl.checked = showFields.has(checkboxEl.value)
        })

    if (fragmentEl.dataset.appViewSettingsBound === "true") {
        return
    }

    fragmentEl.dataset.appViewSettingsBound = "true"

    fragmentEl.addEventListener("change", (event) => {
        const checkboxEl = event.target
        if (
            !(checkboxEl instanceof HTMLInputElement) ||
            checkboxEl.type !== "checkbox"
        ) {
            return
        }

        const fieldName = checkboxEl.value
        const checked = checkboxEl.checked

        if (checked) {
            context.addGridMetadataField(fieldName)
        } else {
            context.removeGridMetadataField(fieldName)
        }

        dispatch(Const.events.viewSettingChanged, {
            fieldName,
            checked,
        })
    })
}

function bindModalFragment({ fragmentEl, dispatch, closeFolderContextMenu }) {
    if (fragmentEl.dataset.appModalBound === "true") {
        return
    }

    fragmentEl.dataset.appModalBound = "true"

    fragmentEl.addEventListener("htmx:after:request", (event) => {
        if (!isRequestSuccessful(event)) {
            return
        }

        handleModalFragmentSuccess({
            fragmentEl,
            event,
            dispatch,
            closeFolderContextMenu,
        })
    })
}

function handleModalFragmentSuccess({
    fragmentEl,
    event,
    dispatch,
    closeFolderContextMenu,
}) {
    dispatchModalFragmentSuccessEvent({ fragmentEl, event, dispatch })
    runModalFragmentSuccessAction({ fragmentEl, closeFolderContextMenu })

    if (fragmentEl.dataset.appModalCloseOnSuccess !== "false") {
        closeModal()
    }
}

function dispatchModalFragmentSuccessEvent({ fragmentEl, event, dispatch }) {
    const eventKey = fragmentEl.dataset.appModalSuccessEvent
    if (!eventKey) {
        return
    }

    const eventName = Const.events[eventKey]
    if (!eventName) {
        console.warn(`Unknown modal success event key: ${eventKey}`)
        return
    }

    dispatch(eventName, buildModalFragmentSuccessDetail({ fragmentEl, event }))
}

function buildModalFragmentSuccessDetail({ fragmentEl, event }) {
    return {
        ...parseFragmentDetail(fragmentEl.dataset.appModalSuccessDetail),
        ...parseFragmentTargetDetail(fragmentEl, event),
    }
}

function runModalFragmentSuccessAction({ fragmentEl, closeFolderContextMenu }) {
    const action = fragmentEl.dataset.appModalSuccessAction
    if (!action) {
        return
    }

    if (action === "close-folder-context-menu") {
        closeFolderContextMenu(fragmentEl.dataset.appModalFolderId)
        return
    }

    console.warn(`Unknown modal success action: ${action}`)
}
