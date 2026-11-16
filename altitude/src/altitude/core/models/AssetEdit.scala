package altitude.core.models

import java.time.LocalDateTime

import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object AssetEdit:
  given JsonCodec.ReadWriter[AssetEdit] = JsonCodec.macroRW

/**
 * An asset's Edit as `asset_edit` stores it; an asset never edited has no row. `editVersion` is 1 at the first save and one more
 * at each, `renderedVersion` the edit version whose edited images are stored (None before the first render), `lastRev` the
 * highest revision of edited images the asset has used, and `isEdited` whether the Edit changes a pixel.
 */
case class AssetEdit(
    assetId: String,
    recipe: EditRecipe,
    editVersion: Int,
    renderedVersion: Option[Int] = None,
    lastRev: Int = 0,
    isEdited: Boolean,
    width: Int,
    height: Int,
    updatedByAccountId: Option[String] = None,
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel
  with NoId
