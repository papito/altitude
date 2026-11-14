package altitude.core.models

/**
 * One cell of the map at a zoom: how many plotted points it holds (an asset without a point of its own counts once per Location
 * it is in), their centroid, and the asset that represents it - the newest by capture time, then the lowest ID, so the same
 * thumbnail stands for the cell across pans. A cell of one point carries that point's asset. `editRev` is the revision of that
 * asset's edited images (`asset.edit_rev`, 0 for none), which the pin's Preview URL carries, as the grid's does.
 */
case class MapCell(count: Int, latitude: Double, longitude: Double, assetId: String, editRev: Int)
