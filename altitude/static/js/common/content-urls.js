/**
 * The URLs of an asset's content (`ContentViewController`, `/content/r/:repoId/:dataType/:id`), built in
 * one place, and the repointing of an image at a new Preview. DOM-free, so the tests run it under
 * Node (`scripts/test-js/`).
 *
 * An edited asset's edited images carry their revision (`?v=<rev>`, `asset.edit_rev`) as a cache
 * key: each render replaces the images under a revision never used before for the asset, so a page
 * never builds a URL whose bytes it has seen change: the edited Preview at a revision above 0 and
 * the original's Preview at 0 (`Util.previewUrl`), and the Display image, which exists only at a
 * revision above 0.
 */

function contentUrl(repoId, dataType, assetId) {
    return `/content/r/${repoId}/${dataType}/${assetId}`
}

/** The asset's Preview as the grid, Quick view and the map show it: the edited Preview of `rev`, or the original's at 0 */
export function previewUrl(repoId, assetId, rev = 0) {
    const url = contentUrl(repoId, "preview", assetId)
    return rev > 0 ? `${url}?v=${rev}` : url
}

/** An edited asset's Display image of revision `rev`, which is above 0 */
export function displayUrl(repoId, assetId, rev) {
    return `${contentUrl(repoId, "display", assetId)}?v=${rev}`
}

/** The stored original */
export function originalUrl(repoId, assetId) {
    return contentUrl(repoId, "file", assetId)
}

/**
 * Points a cell's image at `url`: its `data-src`, which the lazy loader reads
 * (`search-results/lazy-images.js`), and its `src` when it shows its image now rather than the
 * released placeholder. Answers whether anything changed.
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
