package altitude.core.routes.web

import cask._
import cask.model.Response
import java.nio.file.Files

import altitude.core.{ Const => C }
import altitude.core.App
import altitude.core.NotFoundException
import altitude.core.models.MimedPreviewData
import altitude.core.routes.RangeStreaming

class ContentViewController extends cask.Routes:

  @cask.get("/content/r/:repoId/:dataType/:itemId")
  def getContent(repoId: String, dataType: String, itemId: String, request: cask.Request): Response.Raw =
    // TODO: Add authentication check equivalent to requireLogin()

    App.altitude.service.repository.setContextFromRequest(Some(repoId))

    dataType match {
      case C.DataStore.PREVIEW =>
        val preview: MimedPreviewData = App.altitude.service.fileStore.getPreviewById(itemId)
        cask.Response(preview.data, headers = Seq("Content-Type" -> preview.mimeType))

      case C.DataStore.FILE =>
        // The stored original, streamed with byte ranges so a Video plays and seeks, typed as the asset was detected
        val asset = App.altitude.service.asset.getById(itemId)
        val file = App.altitude.service.fileStore.assetFile(itemId)
        if !Files.isRegularFile(file) then throw NotFoundException(s"Cannot find file $file")
        RangeStreaming.respond(file, asset.assetType.mime, Option(request.exchange.getRequestHeaders.getFirst("Range")))

      case C.DataStore.FACE =>
        val faceData = App.altitude.service.fileStore.getDisplayFaceById(itemId)
        cask.Response(faceData.data, headers = Seq("Content-Type" -> faceData.mimeType))

      case _ =>
        cask.Response(Array.empty[Byte], statusCode = 404)

    }

  initialize()
