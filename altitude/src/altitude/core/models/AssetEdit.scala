package altitude.core.models

import java.time.LocalDateTime

import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object AssetEdit:
  given JsonCodec.ReadWriter[AssetEdit] = JsonCodec.macroRW

/**
 * An asset's Edit as `asset_edit` stores it: the step shown (`headSeq`, 0 for the Original), the step whose renditions are stored
 * (`renderedSeq`, None before the first render), the highest rendition revision the asset has used (`lastRev`), whether the head
 * changes a pixel, and the head's edited size. An asset never edited has no row. Its steps are [[AssetEditStep]]s.
 */
case class AssetEdit(
    assetId: String,
    headSeq: Int = 0,
    renderedSeq: Option[Int] = None,
    lastRev: Int = 0,
    isEdited: Boolean = false,
    width: Option[Int] = None,
    height: Option[Int] = None,
    updatedByAccountId: Option[String] = None,
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel
  with NoId
