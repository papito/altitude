package altitude.core.routes.web

import cask.Request
import cask.model.Response
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.App
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.Validators
import altitude.core.routes.RangeStreaming
import altitude.core.routes.decorators.requireLoginOrSignature
import altitude.core.routes.decorators.visibleOnly
import altitude.core.service.AssetEditService
import altitude.core.util.Util

class ContentViewController(using logger: Logger) extends cask.Routes:

  /**
   * A preview, an edited asset's Display image, a face or a stored original. The file store builds paths from the item ID, so a
   * non-UUID is a 400. A Guest is answered a triaged or recycled asset's images as an unknown one; a Face's crop is served, since
   * it may be the cover of a Person the Guest sees.
   *
   * `v` is the revision of an edited asset's edited images, their URLs' cache key: a Preview with `v` is the edited one, and a
   * Display image needs `v`. An earlier revision, held only by a page built before the last render, is redirected to the current
   * one, so the browser never keeps new bytes under an old revision.
   *
   * `exp` and `sig` are declared only because cask answers an undeclared query parameter with a 400.
   */
  @requireLoginOrSignature()
  @cask.get("/content/r/:repoId/:dataType/:itemId")
  def getContent(
      repoId: String,
      dataType: String,
      itemId: String,
      v: Option[String] = None,
      exp: Option[String] = None,
      sig: Option[String] = None)(using request: Request): Response.Raw =
    if !Validators.isUuid(itemId) then
      logger.debug(s"Content [$dataType] requested with an item ID that is not a UUID: [$itemId]")
      return plainText(C.Msg.Err.VALUE_NOT_A_UUID, 400)

    // Digits alone, at most 9 of them, so it is an Int
    val revision = v.map(text => Option.when(text.matches("[0-9]{1,9}"))(text.toInt))
    if revision.contains(None) then
      logger.debug(s"Content [$dataType] [$itemId] requested with a revision that is no whole number: [${v.get}]")
      return plainText(C.Msg.Err.REVISION_NOT_A_NUMBER, 400)

    try content(dataType, itemId, revision.flatten)
    catch
      case ex: NotFoundException =>
        logger.debug(s"Content [$dataType] [$itemId] not found: ${ex.getMessage}")
        notFound

  private def content(dataType: String, itemId: String, revision: Option[Int])(using request: Request): Response.Raw =
    dataType match {
      case C.DataStore.PREVIEW =>
        // Only a Guest's request reads the asset, to refuse one Browse does not show
        if RequestContext.isGuest then visibleOnly(App.altitude.service.asset.getById(itemId)): Unit
        revision match
          case None =>
            val preview = App.altitude.service.fileStore.getPreviewById(itemId)
            image(preview.data, preview.mimeType)
          case Some(rev) => editedImage(C.DataStore.PREVIEW, itemId, rev)

      case C.DataStore.DISPLAY =>
        revision match
          case None => plainText(C.Msg.Err.REVISION_REQUIRED, 400)
          case Some(rev) =>
            if RequestContext.isGuest then visibleOnly(App.altitude.service.asset.getById(itemId)): Unit
            editedImage(C.DataStore.DISPLAY, itemId, rev)

      case C.DataStore.FILE =>
        val asset = visibleOnly(App.altitude.service.asset.getById(itemId))
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

  /**
   * An earlier revision is a `no-store` 302 to the current one, since that moves on with the next render. A signed request's
   * `exp` and `sig` go with it: they sign the path alone.
   */
  private def editedImage(kind: String, itemId: String, rev: Int)(using request: Request): Response.Raw =
    App.altitude.service.assetEdit.editedImage(itemId, rev, kind) match
      case AssetEditService.EditedImage.Current(edited) => image(edited.data, edited.mimeType)
      case AssetEditService.EditedImage.Moved(current) =>
        val signature = for {
          name <- List("exp", "sig")
          value <- request.queryParams.get(name).flatMap(_.headOption)
        } yield s"&$name=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        cask.Response(
          "",
          302,
          Seq("Location" -> s"${Util.contentUrl(kind, itemId)}?v=$current${signature.mkString}", "Cache-Control" -> "no-store"))

  // An error is never cached: the item may yet exist under the same URL
  private def plainText(message: String, status: Int): Response.Raw =
    cask.Response(message, status, Seq("Content-Type" -> "text/plain"))

  private def notFound: Response.Raw = plainText("Not found", 404)

  private def image(data: Array[Byte], mimeType: String): Response.Raw =
    withContentHeaders(cask.Response(data, headers = Seq("Content-Type" -> mimeType)))

  /**
   * The bytes under a content URL never change for a page that builds it (previews and faces are written once, an edited image is
   * named by its revision, an original is unique by checksum), so the browser keeps them privately for good. An original is a
   * user's upload served on the app's origin with its cookie: `nosniff` and a sandbox CSP stop it being rendered as a document
   * that acts as the user.
   */
  private def withContentHeaders(response: Response.Raw): Response.Raw =
    response.copy(headers = response.headers ++ Seq(
      "Cache-Control" -> "private, max-age=31536000, immutable",
      "X-Content-Type-Options" -> "nosniff",
      "Content-Security-Policy" -> "sandbox"))

  initialize()
