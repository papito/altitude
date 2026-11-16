package altitude.core.models

/**
 * An asset's Edit as a client sees it. `editVersion` counts the saves (0 for an asset never edited) and is what a save names as
 * the one it was built on; it is not the `version` key of the Edit's format. `rev` is the asset's revision of its edited images
 * (`asset.edit_rev`, 0 for none), their URLs' cache key. `upright` is the size of the upright original, the space of the Edit's
 * geometry, None where it is not read (the render status, a missing original).
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
