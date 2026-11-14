package altitude.core.models

import java.time.Instant

object EditState:
  /** The label of step 0, the Original, which has no row of its own */
  val ORIGINAL = "Original"

  /**
   * A step as the History lists it: its seq and label, the account that made it (its ID and name, None once it is deleted) and
   * when; none of them for the Original
   */
  case class Step(seq: Int, label: String, accountId: Option[String], author: Option[String], createdAt: Option[Instant])

/**
 * An asset's Edit as a client sees it (`AssetEditService.get`): the head step (0 for the Original) with its Edit and label, the
 * step whose renditions are stored (None before the first render), the asset's rendition revision (`asset.edit_rev`, 0 for none),
 * whether the head changes a pixel, the head's edited size (None until the first step), the steps, newest first, with the
 * Original last, and the size of the asset's upright original, the space of the Edit's geometry (None where it is not read: the
 * render status, and an original that is missing).
 */
case class EditState(
    head: Int,
    recipe: EditRecipe,
    label: String,
    rendered: Option[Int],
    rev: Int,
    edited: Boolean,
    width: Option[Int],
    height: Option[Int],
    steps: List[EditState.Step],
    upright: Option[(Int, Int)] = None)
