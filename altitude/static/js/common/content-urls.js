/**
 * The URLs of an asset's content (`ContentViewController`). DOM-free, so the tests run it under Node.
 *
 * Edited images carry their revision (`?v=<rev>`, `asset.edit_rev`) as a cache key: each render
 * writes a revision never used before for the asset, so a page never builds a URL whose bytes it
 * has seen change. The original's Preview is at revision 0, as in `Util.previewUrl`.
 */

function contentUrl(repoId, dataType, assetId) {
    return `/content/r/${repoId}/${dataType}/${assetId}`
}

export function previewUrl(repoId, assetId, rev = 0) {
    const url = contentUrl(repoId, "preview", assetId)
    return rev > 0 ? `${url}?v=${rev}` : url
}

/** The Display image exists only for an edited asset, so `rev` is above 0 */
export function displayUrl(repoId, assetId, rev) {
    return `${contentUrl(repoId, "display", assetId)}?v=${rev}`
}

export function originalUrl(repoId, assetId) {
    return contentUrl(repoId, "file", assetId)
}

/**
 * Sets the `data-src` the lazy loader reads, and `src` only when the image is loaded rather than
 * released to the placeholder. Answers whether anything changed.
 */
export function repointImage(imgEl, url) {
    if (imgEl.dataset.src === url) {
        return false
    }
    const loaded = imgEl.getAttribute("src") === imgEl.dataset.src
    imgEl.dataset.src = url
    if (loaded) {
        imgEl.src = url
    }
    return true
}
