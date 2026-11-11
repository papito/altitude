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
 * recycling and restoring against a triaged or a sorted asset (`Permissions.mayCurate`, `mayRecycle`). A service checks them in
 * the transaction of its write, over the assets it has read scoped to the context Library, so another Library's ID is never
 * refused but not found, as an unknown one is. One refused asset fails the whole operation with `ForbiddenException`, a 403 at
 * the route. The Permissions and the account are the context's (`RequestContext`): a request's, from its login token, or a
 * pipeline stage's, from its `PipelineContext`; no pipeline reaches these rules today.
 */
object AssetRules:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /** Refuses the operation unless every asset may be curated (resolved or reopened in Curation) */
  def requireCurate(assets: Iterable[Asset]): Unit = require(assets, "curate")(_.mayCurate(_, _))

  /** Refuses the operation unless every asset may be recycled; `action` names it, a restore taking what a recycle does */
  def requireRecycle(assets: Iterable[Asset], action: String = "recycle"): Unit = require(assets, action)(_.mayRecycle(_, _))

  /**
   * Whose frames Curation shows the context's account: everyone's (None) with Curate all, and with Curate own alone its own, so a
   * set it is shown holds no frame it may not keep or recycle, and the queue counts only Sequences it can curate
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
