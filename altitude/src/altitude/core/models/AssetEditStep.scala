package altitude.core.models

import java.time.LocalDateTime

import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object AssetEditStep:
  given JsonCodec.ReadWriter[AssetEditStep] = JsonCodec.macroRW

/**
 * A step of an asset's Edit, as `asset_edit_step` stores it: the Edit after one change, the label derived from that change
 * (`EditRecipe.describeChange`) and the account that made it, None once that account is deleted. Read and written by
 * `AssetEditDao`'s own statements alone, never through the generic ones of `BaseDao`, so it is no `BaseModel`.
 */
case class AssetEditStep(
    assetId: String,
    seq: Int,
    recipe: EditRecipe,
    label: String,
    accountId: Option[String] = None,
    createdAt: Option[LocalDateTime] = None)
