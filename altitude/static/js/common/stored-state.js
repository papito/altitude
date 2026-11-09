/**
 * A remembered object under one localStorage key that several modules may share: the Detail panel's
 * view (`Const.localStore.assetDetailView`) is written by `search-results/detail-panel.js` for its own
 * fields and by `common/dock.js` for the dock's. A read gives the whole object; a write sets some of
 * its fields and leaves the rest as they are. Storage that is missing, unreadable or refused (a
 * private window, cleared site data) reads as empty and is written best-effort, so a page never
 * fails on it.
 */

/** The object stored under `key`, or an empty one where it is missing or unreadable */
export function readStoredObject(key) {
    let saved = null
    try {
        saved = JSON.parse(localStorage.getItem(key) ?? "null")
    } catch {
        saved = null
    }
    return typeof saved === "object" && saved !== null && !Array.isArray(saved)
        ? saved
        : {}
}

/** Writes the fields of `patch` into the object stored under `key`, keeping its other fields */
export function writeStoredFields(key, patch) {
    try {
        localStorage.setItem(
            key,
            JSON.stringify({ ...readStoredObject(key), ...patch }),
        )
    } catch {
        // Storage refused the write: the page goes on with what it holds
    }
}
