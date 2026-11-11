package altitude.core.models

/**
 * What an account may do in a Library beyond looking (`CONTEXT.md`), held per account and Library in a [[Permissions]] bitmask.
 * Each has a stable bit: the bitmask is stored (`repository_grant.permissions`, `account.new_library_permissions`) and carried in
 * the login token, so a bit is never reused or renumbered, and a new Permission takes the next free one. The cases are in the
 * order the UI lists them.
 */
enum Permission(val bit: Int):
  case Import extends Permission(0)
  case CurateOwn extends Permission(1)
  case CurateAll extends Permission(2)
  case Organize extends Permission(3)
  case EditMetadata extends Permission(4)
  case CreateUsers extends Permission(5)
  case CreateGuests extends Permission(6)
  case Recycle extends Permission(7)
  case Purge extends Permission(8)

  /** This Permission's bit in a [[Permissions]] mask */
  def mask: Int = 1 << bit
