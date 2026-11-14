import { Const } from "../constants.js"

/**
 * The Edit's events in Asset detail (`edit/edit-stage.js`): `\` held or released
 * (`global.js`) shows an edited asset's original for as long as it is held.
 */
export function registerEditListeners(app) {
    document.body.addEventListener(Const.events.showOriginalHeld, (event) => {
        app.editStage.holdOriginal(event.detail.held)
    })
}
