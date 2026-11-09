import { Const } from "../constants.js"

/** Keyboard handling of the results: previous/next, Space's play/pause and `m`'s Detail panel in asset detail, and Escape */
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

    document.body.addEventListener(Const.events.escapeKeyPressed, () => {
        app.handleEscapeKeyPressed()
    })
}
