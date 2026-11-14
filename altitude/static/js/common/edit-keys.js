/**
 * The Edit panel's part in `global.js`'s keys, which the Edit panel's coordinator registers as the page that holds Asset
 * detail starts (`edit/edit-panel.js`, `registerEditKeys`), so `global.js`, which every page loads, imports no edit
 * module: whether the warning of changes not saved is open and its close (Keep editing), and whether the Crop tool is open
 * and its Escape (Cancel, or the warning). On a page that registers none, each answers false.
 */
const hooks = {
    isLeaveDialogOpen: () => false,
    closeLeaveDialog: () => false,
    isCropToolOpen: () => false,
    escapeOpenCropTool: () => false,
}

/** Sets the hooks given (any of `hooks`' four), keeping the rest */
export function registerEditKeys(registered) {
    Object.assign(hooks, registered)
}

/** Whether the Edit panel's warning is open: Asset detail's keys are left to it */
export function isLeaveDialogOpen() {
    return hooks.isLeaveDialogOpen()
}

/** Closes the Edit panel's warning as Keep editing; answers whether it was open */
export function closeLeaveDialog() {
    return hooks.closeLeaveDialog()
}

/** Whether the Crop tool is open: O, X and Enter are its keys then */
export function isCropToolOpen() {
    return hooks.isCropToolOpen()
}

/** Escape on the open Crop tool: Cancel, or the warning; answers whether it was open */
export function escapeOpenCropTool() {
    return hooks.escapeOpenCropTool()
}
