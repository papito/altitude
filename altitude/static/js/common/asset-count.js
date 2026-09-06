/**
 * The dimmed "(n)" asset count cell shared by the folder tree and the album list.
 *
 * A zero renders empty, never "(0)". Every row is its own grid, so the caller sizes the count
 * column of the whole list to the widest count with `sizeCountColumn` after each render.
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

/**
 * Sets `cssVariable` on `container` to the width of the widest count it holds. Collapsed rows are
 * `display: none` and would measure as zero, so each distinct count text is measured in a probe
 * appended to the container instead, which also picks up the cell's own font size.
 */
export function sizeCountColumn(container, cssVariable) {
    const texts = new Set(
        [...container.querySelectorAll(".asset-count")]
            .map((el) => el.textContent)
            .filter(Boolean),
    )

    const probe = document.createElement("span")
    probe.className = "asset-count"
    probe.style.position = "absolute"
    probe.style.visibility = "hidden"
    container.appendChild(probe)

    let width = 0
    for (const text of texts) {
        probe.textContent = text
        width = Math.max(width, probe.getBoundingClientRect().width)
    }
    probe.remove()

    container.style.setProperty(cssVariable, `${width}px`)
}
