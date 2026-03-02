package altitude.core.routes.web.partial

import altitude.core.App
import altitude.core.models.Asset
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import cask.Request
import cask.model.Response
import org.slf4j.Logger

class AssetActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/asset"

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/modals/asset-detail/:assetId")
  def showAssetDetailModal(repoId: String, assetId: String)(using request: Request): Response[String] =
    val asset: Asset = App.altitude.service.asset.getById(assetId)
    val payload = "<!doctype html>" + htmx.html.view_image_detail_modal(asset)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
