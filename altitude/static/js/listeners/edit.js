import { Const } from "../constants.js"

/** Hands the Edit keys of `global.js` to the Edit stage and panel */
export function registerEditListeners(app) {
    const on = (event, handler) =>
        document.body.addEventListener(Const.events[event], handler)

    on("showOriginalHeld", (event) => {
        app.editStage.holdOriginal(event.detail.held)
    })
    on("toggleEditPanel", () => app.editPanel.toggle({ fromKey: true }))
    on("toggleCropTool", () => app.editPanel.toggleCropTool())
    on("cycleCropGuide", () => app.editPanel.cycleCropGuide())
    on("swapCropAspect", () => app.editPanel.swapCropAspect())
    on("editDone", () => app.editPanel.done())
    on("undoEdit", () => app.editPanel.undo())
    on("redoEdit", () => app.editPanel.redo())
}
