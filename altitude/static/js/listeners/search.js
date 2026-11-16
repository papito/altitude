import { Const } from "../constants.js"

/** Asset detail's key events from `global.js`, and the Escape broadcast */
export function registerSearchListeners(app) {
    document.body.addEventListener(Const.events.showNext, () => {
        app.searchDetailCoordinator.handleShowNext()
    })

    document.body.addEventListener(Const.events.showPrevious, () => {
        app.searchDetailCoordinator.handleShowPrevious()
    })

    document.body.addEventListener(Const.events.togglePlayback, () => {
        app.searchDetailCoordinator.togglePlayback()
    })

    document.body.addEventListener(Const.events.toggleDetailPanel, () => {
        app.detailPanel.toggle()
    })

    document.body.addEventListener(Const.events.toggleFaceHighlights, () => {
        app.detailPanel.toggleHighlights()
    })

    document.body.addEventListener(Const.events.toggleFullscreen, () => {
        app.detailFullscreen.toggle()
    })

    document.body.addEventListener(Const.events.escapeKeyPressed, () => {
        app.handleEscapeKeyPressed()
    })
}
