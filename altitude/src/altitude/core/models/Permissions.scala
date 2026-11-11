package altitude.core.models

import altitude.core.util.JsonCodec

object Permissions:
  /** Every bit a [[Permission]] holds; a mask with any other bit set names no Permission */
  private val KNOWN_BITS: Int = Permission.values.map(_.mask).foldLeft(0)(_ | _)

  val none: Permissions = Permissions(0)

  val all: Permissions = Permissions(KNOWN_BITS)

  def of(permissions: Permission*): Permissions = Permissions(permissions.map(_.mask).foldLeft(0)(_ | _))

  // Stored and sent as the bare mask
  given JsonCodec.ReadWriter[Permissions] = JsonCodec.readwriter[Int].bimap(_.mask, Permissions(_))

/**
 * A set of [[Permission]]s as the bitmask that is stored and carried in the token: bit `Permission.bit` is set for each one held.
 * A mask with a bit no Permission has is refused, so a stored or forged value never passes for a Permission that does not exist.
 */
final case class Permissions(mask: Int):
  require((mask & ~Permissions.KNOWN_BITS) == 0, s"Unknown Permission bits in [$mask]")

  def has(permission: Permission): Boolean = (mask & permission.mask) != 0

  def isEmpty: Boolean = mask == 0

  /** Whether these Permissions curate the asset for the account `accountId`: Curate all, or Curate own on an asset it owns */
  def mayCurate(asset: Asset, accountId: String): Boolean =
    has(Permission.CurateAll) || (has(Permission.CurateOwn) && asset.ownerUserId == accountId)

  /**
   * Whether these Permissions recycle the asset for the account `accountId`, and so restore it: a triaged asset, as one recycled
   * from Triage still is, takes a Curate Permission ([[mayCurate]]), a sorted one Recycle
   */
  def mayRecycle(asset: Asset, accountId: String): Boolean =
    if asset.isTriaged then mayCurate(asset, accountId) else has(Permission.Recycle)

  /** The Permissions held, in the order of [[Permission]] */
  def toList: List[Permission] = Permission.values.toList.filter(has)

  override def toString: String = toList.mkString("Permissions(", ", ", ")")
