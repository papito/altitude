/**
 * The dimmed "(n)" asset count cell shared by the folder tree, the album list and the Location
 * list, placed directly after the row's name.
 *
 * A zero renders empty, never "(0)", so a row without assets ends at its name and needs no column
 * for the count.
 */

// The cell keeps a stable ID so counts can be patched in place without a rebuild
export function buildAssetCountEl(id, numOfAssets) {
    const el = document.createElement("span")
    el.id = id
    el.className = "asset-count"
    setAssetCount(el, numOfAssets)
    return el
}

export function setAssetCount(el, numOfAssets) {
    el.textContent = numOfAssets > 0 ? `(${numOfAssets})` : ""
}
