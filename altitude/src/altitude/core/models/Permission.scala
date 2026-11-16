package altitude.core.models

/**
 * What an account may do in a Library beyond looking, held per account and Library in a [[Permissions]] bitmask. The mask is
 * stored and carried in the login token, so a bit is never reused or renumbered; a new Permission takes the next free one. The
 * cases are in the order the UI lists them.
 */
enum Permission(val bit: Int, val label: String):
  case Import extends Permission(0, "Import")
  case CurateOwn extends Permission(1, "Curate own")
  case CurateAll extends Permission(2, "Curate all")
  case Organize extends Permission(3, "Organize")
  case EditMetadata extends Permission(4, "Edit metadata")
  case CreateUsers extends Permission(5, "Create Users")
  case CreateGuests extends Permission(6, "Create Guests")
  case Recycle extends Permission(7, "Recycle")
  case Purge extends Permission(8, "Purge")
  // Saving an asset's Edit, Revert to original and auto-straighten
  case EditImages extends Permission(9, "Edit images")

  def mask: Int = 1 << bit
