import { Const } from "../constants.js"

/**
 * The results grid's cells, addressed by asset. A Location grouping holds an asset once per Location
 * (`#asset-<id>-in-<locationId>`), so nothing looks a cell up by ID: every cell's thumbnail box carries
 * `data-asset-id`.
 */

/** In grid order */
export function cellsOf(assetId) {
    return Array.from(thumbnailsOf(assetId), (thumbnailEl) =>
        thumbnailEl.closest(".cell"),
    ).filter(Boolean)
}

export function thumbnailsOf(assetId) {
    return document.querySelectorAll(
        `#assets .cell > .drag-drop[${Const.attributes.assetId}="${CSS.escape(assetId)}"]`,
    )
}

export function assetIdOf(cellEl) {
    return (
        cellEl?.querySelector(`.drag-drop[${Const.attributes.assetId}]`)
            ?.dataset.assetId ?? null
    )
}
