package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.{ Const => C }
import altitude.core.ForbiddenException
import altitude.core.RequestContext
import altitude.core.models.Asset
import altitude.core.models.Permission
import altitude.core.models.Permissions

/**
 * The Permission rules that depend on the asset, which no route decorator can decide: Curate own against the asset's owner, and
 * recycling and restoring against a triaged or a sorted asset. A service checks them in its write's transaction over assets read
 * scoped to the Library, so another Library's ID is not found rather than refused. One refused asset fails the whole operation.
 */
object AssetRules:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /** Curating is resolving or reopening in Curation */
  def requireCurate(assets: Iterable[Asset]): Unit = require(assets, "curate")(_.mayCurate(_, _))

  /** A restore takes what a recycle does; `action` names it */
  def requireRecycle(assets: Iterable[Asset], action: String = "recycle"): Unit = require(assets, action)(_.mayRecycle(_, _))

  /**
   * Whose frames Curation shows the account: everyone's (None) with Curate all, else its own, so a set holds no frame it may not
   * keep or recycle
   */
  def curatedOwner: Option[String] = Option.unless(RequestContext.permissions.value.has(Permission.CurateAll))(accountId)

  // With no account in the context, Curate own reaches no asset: no asset's owner is the empty ID
  private def accountId: String = RequestContext.account.value.map(_.persistedId).getOrElse("")

  private def require(assets: Iterable[Asset], action: String)(allowed: (Permissions, Asset, String) => Boolean): Unit =
    val permissions = RequestContext.permissions.value
    val refused = assets.filterNot(allowed(permissions, _, accountId))
    if refused.nonEmpty then
      logger.warn(
        s"Account [$accountId] holding $permissions refused to $action ${refused.size} assets, " +
          s"[${refused.head.persistedId}] among them")
      throw ForbiddenException(C.Msg.Err.FORBIDDEN_ASSETS.format(action, refused.size))
