import { Const } from "../constants.js"

/**
 * The cells of the results grid, addressed by asset.
 *
 * A grid usually holds an asset once, as `#asset-<id>`, but a Location grouping holds it once under
 * every Location it is in (`#asset-<id>-in-<locationId>`, htmx/results_grid_grouped.scala.html). So
 * nothing looks a cell up by ID: the thumbnail box of every cell carries `data-asset-id`, and these
 * helpers find all of an asset's cells, or name the asset of one cell.
 */

/** Every `.cell` of `assetId` in the displayed grid, in grid order */
export function cellsOf(assetId) {
    return Array.from(thumbnailsOf(assetId), (thumbnailEl) =>
        thumbnailEl.closest(".cell"),
    ).filter(Boolean)
}

/** Every thumbnail box (`.drag-drop`) of `assetId` in the displayed grid */
export function thumbnailsOf(assetId) {
    return document.querySelectorAll(
        `#assets .cell > .drag-drop[${Const.attributes.assetId}="${CSS.escape(assetId)}"]`,
    )
}

/** The asset a cell shows */
export function assetIdOf(cellEl) {
    return (
        cellEl?.querySelector(`.drag-drop[${Const.attributes.assetId}]`)
            ?.dataset.assetId ?? null
    )
}
