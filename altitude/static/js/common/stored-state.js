/**
 * An object under one localStorage key that several modules share, each writing only its own
 * fields. Storage that is missing, unreadable or refused (a private window) reads as empty and is
 * written best-effort, so a page never fails on it.
 */

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
