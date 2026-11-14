package altitude.core.models

import java.time.LocalDateTime

import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object AssetEdit:
  given JsonCodec.ReadWriter[AssetEdit] = JsonCodec.macroRW

/**
 * An asset's Edit as `asset_edit` stores it: the Edit itself (the identity once reverted to the original), its edit version (1 at
 * the first save, one more at each), the edit version whose edited images are stored (`renderedVersion`, None before the first
 * render), the highest revision of edited images the asset has used (`lastRev`), whether the Edit changes a pixel, and its edited
 * size. An asset never edited has no row.
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
