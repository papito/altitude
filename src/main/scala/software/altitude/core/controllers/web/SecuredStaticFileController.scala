package software.altitude.core.controllers.web

import org.scalatra.NotFound
import software.altitude.core.controllers.BaseWebController
import software.altitude.core.{RequestContext, Const => C}


class SecuredStaticFileController extends BaseWebController {

  before() {
    requireLogin()
  }

  notFound {
    val pathSegments = request.pathInfo.split("/").toList

    if (pathSegments(1) == C.DataStore.PREVIEW) {
      val assetId = pathSegments.last

      val preview = app.service.fileStore.getPreviewById(assetId)
      contentType = "image/png"
      response.getOutputStream.write(preview.data)
      halt(200)
    }

    if (pathSegments(1) == C.DataStore.FILE) {
      val assetId = pathSegments.last

      val data = app.service.fileStore.getById(assetId)
      contentType = data.mimeType
      response.getOutputStream.write(data.data)
      halt(200)
    }

    halt(NotFound("Not Found"))
  }

}
