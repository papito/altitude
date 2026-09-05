import { Const } from "../constants.js"
import { handleViewSettingChanged } from "../fragments/search-results.js"

export function registerHtmxAndSearchListeners(app) {
    document.body.addEventListener(Const.events.viewSettingChanged, (event) => {
        handleViewSettingChanged({ event, context: app.context })
    })

    document.body.addEventListener(Const.events.showNext, () => {
        app.searchDetailCoordinator.handleShowNext()
    })

    document.body.addEventListener(Const.events.showPrevious, () => {
        app.searchDetailCoordinator.handleShowPrevious()
    })

    document.body.addEventListener(Const.events.detailShown, (event) => {
        app.Alpine.store(Const.state.shadowResults).currentAssetId =
            event.detail.assetId
    })

    document.body.addEventListener(Const.events.escapeKeyPressed, () => {
        app.handleEscapeKeyPressed()
    })

    document.body.addEventListener("htmx:after:request", (event) => {
        app.handleAfterRequest(event)
    })

    document.body.addEventListener("htmx:after:settle", (event) => {
        app.handleAfterSettle(event)
    })
}
