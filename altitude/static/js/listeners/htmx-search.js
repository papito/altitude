import { Const } from "../constants.js"

export function registerHtmxAndSearchListeners(app) {
    document.body.addEventListener(Const.events.viewSettingChanged, (event) => {
        app.handleViewSettingChanged(event)
    })

    document.body.addEventListener(Const.events.showNext, () => {
        app.handleShowNext()
    })

    document.body.addEventListener(Const.events.showPrevious, () => {
        app.handleShowPrevious()
    })

    document.body.addEventListener(Const.events.detailShown, (event) => {
        app.Alpine.store(Const.state.shadowResults).currentAssetId =
            event.detail.assetId
    })

    document.body.addEventListener(Const.events.escapeKeyPressed, () => {
        app.handleEscapeKeyPressed()
    })

    document.body.addEventListener("htmx:afterRequest", (event) => {
        app.handleAfterRequest(event)
    })

    document.body.addEventListener("htmx:afterSwap", (event) => {
        app.handleAfterSwap(event)
    })

    document.body.addEventListener("htmx:beforeRequest", (event) => {
        app.handleBeforeRequest(event)
    })

    document.body.addEventListener("htmx:load", (event) => {
        app.handleHtmxLoad(event)
    })
}

