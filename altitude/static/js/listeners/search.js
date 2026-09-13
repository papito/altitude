import { Const } from "../constants.js"

/** Keyboard navigation of the results: previous/next in asset detail, and Escape */
export function registerSearchListeners(app) {
    document.body.addEventListener(Const.events.showNext, () => {
        app.searchDetailCoordinator.handleShowNext()
    })

    document.body.addEventListener(Const.events.showPrevious, () => {
        app.searchDetailCoordinator.handleShowPrevious()
    })

    document.body.addEventListener(Const.events.escapeKeyPressed, () => {
        app.handleEscapeKeyPressed()
    })
}
