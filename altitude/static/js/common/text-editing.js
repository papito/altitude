/** Focus is in a field that takes the keys typed: a page's keyboard shortcuts leave them to it */
export function isTextEditingTarget(target) {
    return Boolean(
        target?.closest?.("input, textarea, select, [contenteditable]"),
    )
}
