package software.altitude.core.controllers.web
import org.scalatra.NotFound
import software.altitude.core.controllers.BaseWebController
import software.altitude.core.models.MimedPreviewData
import software.altitude.core.{Const => C}

class ContentViewController extends BaseWebController {

  before() {
    requireLogin()
  }

  notFound {
    val pathSegments = request.pathInfo.split("/").toList
    // the first elements (we are not interested in are "", "r")
    require(pathSegments.length == 5, "Content path must include repoId, dataType, and assetId")
    val repoId = pathSegments(2)
    val dataType = pathSegments(3)
    val itemId = pathSegments(4)

    app.service.repository.setContextFromRequest(Some(repoId))
    if (dataType == C.DataStore.PREVIEW) {

      val preview: MimedPreviewData = app.service.fileStore.getPreviewById(itemId)
      contentType =preview.mimeType
      response.getOutputStream.write(preview.data)
      halt(200)
    }

    if (dataType == C.DataStore.FILE) {
      val data = app.service.fileStore.getAssetById(itemId)
      contentType = data.mimeType
      response.getOutputStream.write(data.data)
      halt(200)
    }

    // face data we get straight from the DB
    if (dataType == C.DataStore.FACE) {
      val faceData = app.service.fileStore.getDisplayFaceById(itemId)
      contentType = faceData.mimeType
      response.getOutputStream.write(faceData.data)
      halt(200)
    }

    halt(NotFound("Not Found"))
  }
}
