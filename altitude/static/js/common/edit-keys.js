/**
 * The Edit panel's part in `global.js`'s keys, which the Edit panel's coordinator registers as the page that holds Asset
 * detail starts (`edit/edit-panel.js`, `registerEditKeys`), so `global.js`, which every page loads, imports no edit
 * module: whether the warning of changes not saved is open and its close (Keep editing), whether the Crop tool is open,
 * whether the panel holds changes (Enter is Done then), and the panel's Escape (the Level tool off, the changes held
 * cancelled or the warning about them, the Crop tool closed). On a page that registers none, each answers false.
 */
const hooks = {
    isLeaveDialogOpen: () => false,
    closeLeaveDialog: () => false,
    isCropToolOpen: () => false,
    hasHeldEdit: () => false,
    escapeEdit: () => false,
}

/** Sets the hooks given (any of `hooks`' five), keeping the rest */
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

/** Whether the Crop tool is open: O and X are its keys then */
export function isCropToolOpen() {
    return hooks.isCropToolOpen()
}

/** Whether the Edit panel holds changes for Done or Cancel: Enter is Done then */
export function hasHeldEdit() {
    return hooks.hasHeldEdit()
}

/** The Edit panel's Escape: answers whether it took the key */
export function escapeEdit() {
    return hooks.escapeEdit()
}
