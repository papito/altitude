package altitude.core.models

/**
 * An asset's Edit as a client sees it (`AssetEditService.get`): its edit version, the count of its saves (0 for an asset never
 * edited), which a save names as the one it was built on, and the Edit; the edit version whose edited images are stored (None
 * before the first render); the asset's revision of them (`asset.edit_rev`, 0 for none), their URLs' cache key; whether the Edit
 * changes a pixel; its edited size (None for an asset never edited); and the size of the asset's upright original, the space of
 * the Edit's geometry (None where it is not read: the render status, and an original that is missing). The edit version is the
 * stored Edit's, not the `version` key of the Edit's format.
 */
case class EditState(
    editVersion: Int,
    recipe: EditRecipe,
    renderedVersion: Option[Int],
    rev: Int,
    edited: Boolean,
    width: Option[Int],
    height: Option[Int],
    upright: Option[(Int, Int)] = None)
