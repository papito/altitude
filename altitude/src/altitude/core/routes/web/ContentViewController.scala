package altitude.core.routes.web

import altitude.core.App
import altitude.core.models.MimedPreviewData
import altitude.core.{Const => C}
import cask._

class ContentViewController extends cask.Routes:

  @cask.get("/content/r/:repoId/:dataType/:itemId")
  def getContent(repoId: String, dataType: String, itemId: String, request: cask.Request): cask.Response[Array[Byte]] = {
    // TODO: Add authentication check equivalent to requireLogin()

    App.altitude.service.repository.setContextFromRequest(Some(repoId))

    dataType match {
      case C.DataStore.PREVIEW =>
        val preview: MimedPreviewData = App.altitude.service.fileStore.getPreviewById(itemId)
        cask.Response(preview.data, headers = Seq("Content-Type" -> preview.mimeType))

      case C.DataStore.FILE =>
        val data = App.altitude.service.fileStore.getAssetById(itemId)
        cask.Response(data.data, headers = Seq("Content-Type" -> data.mimeType))

      case C.DataStore.FACE =>
        val faceData = App.altitude.service.fileStore.getDisplayFaceById(itemId)
        cask.Response(faceData.data, headers = Seq("Content-Type" -> faceData.mimeType))

      case _ =>
        cask.Response(Array.empty[Byte], statusCode = 404)

    }
  }

  initialize()

