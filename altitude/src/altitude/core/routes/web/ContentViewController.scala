package altitude.core.routes.web

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.App
import altitude.core.NotFoundException
import altitude.core.Validators
import altitude.core.models.MimedPreviewData
import altitude.core.routes.RangeStreaming
import altitude.core.routes.decorators.requireLogin

class ContentViewController(using logger: Logger) extends cask.Routes:

  /**
   * A preview, a face or a stored original, served to the owner of the repository only. The file store builds paths from the item
   * ID, so an ID that is not a UUID is a 400, and anything the ID does not name, in the database or on disk, is a 404.
   */
  @requireLogin()
  @cask.get("/content/r/:repoId/:dataType/:itemId")
  def getContent(repoId: String, dataType: String, itemId: String)(using request: Request): Response.Raw =
    if !Validators.isUuid(itemId) then
      logger.debug(s"Content [$dataType] requested with an item ID that is not a UUID: [$itemId]")
      return plainText(C.Msg.Err.VALUE_NOT_A_UUID, 400)

    try content(dataType, itemId)
    catch
      case ex: NotFoundException =>
        logger.debug(s"Content [$dataType] [$itemId] not found: ${ex.getMessage}")
        notFound

  private def content(dataType: String, itemId: String)(using request: Request): Response.Raw =
    dataType match {
      case C.DataStore.PREVIEW =>
        val preview: MimedPreviewData = App.altitude.service.fileStore.getPreviewById(itemId)
        image(preview.data, preview.mimeType)

      case C.DataStore.FILE =>
        // The stored original, streamed with byte ranges so a Video plays and seeks, typed as the asset was detected and named
        // after its file
        val asset = App.altitude.service.asset.getById(itemId)
        val file = App.altitude.service.fileStore.assetFile(itemId)
        val range = Option(request.exchange.getRequestHeaders.getFirst("Range"))
        withContentHeaders(RangeStreaming.respond(file, asset.assetType.mime, asset.fileName, range))

      case C.DataStore.FACE =>
        val faceData = App.altitude.service.fileStore.getDisplayFaceById(itemId)
        image(faceData.data, faceData.mimeType)

      case _ =>
        logger.debug(s"Content of an unknown data type [$dataType] requested: [$itemId]")
        notFound
    }

  // An error is never cached: the item may yet exist under the same URL
  private def plainText(message: String, status: Int): Response.Raw =
    cask.Response(message, status, Seq("Content-Type" -> "text/plain"))

  private def notFound: Response.Raw = plainText("Not found", 404)

  private def image(data: Array[Byte], mimeType: String): Response.Raw =
    withContentHeaders(cask.Response(data, headers = Seq("Content-Type" -> mimeType)))

  /**
   * The headers of every response that serves content, an original's 206 and 416 included. The bytes under a content URL never
   * change (a preview is written once per asset, a face image once per face, and an original is unique by checksum), so the
   * browser keeps them for as long as it keeps anything, privately, since they are a user's. An original is a user's upload
   * served on the application's origin, with its cookie: `nosniff` and a sandbox CSP stop a browser rendering one as a document
   * that acts as the user, whatever its bytes contain.
   */
  private def withContentHeaders(response: Response.Raw): Response.Raw =
    response.copy(headers = response.headers ++ Seq(
      "Cache-Control" -> "private, max-age=31536000, immutable",
      "X-Content-Type-Options" -> "nosniff",
      "Content-Security-Policy" -> "sandbox"))

  initialize()
