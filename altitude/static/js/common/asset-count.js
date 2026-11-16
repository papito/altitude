/**
 * The "(n)" asset count after a row's name in the explorer lists. A zero renders empty, so a row
 * without assets ends at its name and needs no column for the count.
 */

// A stable ID, so counts can be patched in place without a rebuild
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
