package altitude.core.models

/**
 * What an account may do in a Library beyond looking (`CONTEXT.md`), held per account and Library in a [[Permissions]] bitmask.
 * Each has a stable bit: the bitmask is stored (`repository_grant.permissions`, `account.new_library_permissions`) and carried in
 * the login token, so a bit is never reused or renumbered, and a new Permission takes the next free one. The cases are in the
 * order the UI lists them, and `label` is the name the UI gives each.
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

  /** This Permission's bit in a [[Permissions]] mask */
  def mask: Int = 1 << bit
