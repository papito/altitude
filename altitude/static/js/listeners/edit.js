import { Const } from "../constants.js"

/**
 * The Edit's events in Asset detail (`global.js`'s keys): `\` held or released shows an edited asset's original for as
 * long as it is held (`edit/edit-stage.js`); `e` opens or closes the Edit panel, `c` opens or closes its Crop tool, `o`
 * and `x` are the Crop tool's (the guide, the aspect's orientation), Enter is Done of the changes held, and Ctrl/Cmd+Z and
 * Ctrl/Cmd+Shift+Z (or Ctrl+Y) are Undo and Redo while the panel is open (`edit/edit-panel.js`).
 */
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
