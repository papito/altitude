import { Const } from "../constants.js"

/** Keyboard handling of the results: previous/next, Space's play/pause, `m`'s Detail panel, `p`'s Face highlights and `f`'s fullscreen in asset detail, and Escape */
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
