/**
 * The Edit panel's hooks into `global.js`'s keys, registered by `edit/edit-panel.js`, so `global.js`,
 * which every page loads, imports no edit module. On a page that registers none, each answers false.
 */
const hooks = {
    isLeaveDialogOpen: () => false,
    closeLeaveDialog: () => false,
    isCropToolOpen: () => false,
    hasHeldEdit: () => false,
    escapeEdit: () => false,
}

/** Sets the hooks given, keeping the rest */
export function registerEditKeys(registered) {
    Object.assign(hooks, registered)
}

/** The warning of unsaved changes: Asset detail's keys are left to it */
export function isLeaveDialogOpen() {
    return hooks.isLeaveDialogOpen()
}

/** Closes the warning as Keep editing; answers whether it was open */
export function closeLeaveDialog() {
    return hooks.closeLeaveDialog()
}

/** O and X are the Crop tool's keys then */
export function isCropToolOpen() {
    return hooks.isCropToolOpen()
}

/** Enter is Done then */
export function hasHeldEdit() {
    return hooks.hasHeldEdit()
}

/** Answers whether the Edit panel took the key */
export function escapeEdit() {
    return hooks.escapeEdit()
}
