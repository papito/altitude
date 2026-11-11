package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.App
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLibraryCreator
import altitude.core.routes.decorators.requireLogin

/**
 * The New library dialog, an Admin's or that of an account with the Create Libraries flag (`requireLibraryCreator`), opened from
 * the nav's Library menu: it creates a Library, which an Admin reaches by type and any other creator by the grant of every
 * Permission it is given (`RepositoryService.addRepository`), and sends the browser to its Import page. Both routes are scoped to
 * the Library the dialog was opened from, as every partial is.
 */
class LibraryActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/library"

  @requireLogin()
  @requireLibraryCreator()
  @cask.get(f"/$prefix/r/:repoId/dialogs/new")
  def showNewLibraryDialog(repoId: String)(using request: Request): Response[String] =
    htmlResponse(htmx.html.new_library_dialog())

  /**
   * Creates the Library under the submitted name. The name is neither scrubbed nor checked by the request validator: the Library
   * name rule normalizes and checks it in `addRepository`, and a trim here would drop the control characters at its ends that the
   * rule refuses. A refused name, or a creator already holding as many grants as an account may, re-renders the form with the
   * typed name and the error under the field; a created Library answers with `HX-Redirect` to its Import page, whose load records
   * it as the last active one.
   */
  @requireLogin()
  @requireLibraryCreator()
  @cask.post(f"/$prefix/r/:repoId/new")
  def htmxNewLibrary(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = unscrubbedJson.get
    val name = jsonIn.obj.get(Api.Field.Library.NAME).flatMap(_.strOpt).getOrElse("")
    val account = RequestContext.getAccount

    def responseWithValidationError(error: String): Response[String] =
      logger.debug(s"New library refused for account [${account.persistedId}]: $error")
      dialogFormValidationResponse(
        "<!doctype html>" + htmx.html.new_library_dialog(fieldErrors = Map(Api.Field.Library.NAME -> error), formJson = jsonIn))

    try
      val repo = App.altitude.service.repository
        .addRepository(name = name, fileStoreType = C.StorageEngineName.FS, creatorId = Some(account.persistedId))
      logger.info(s"Account [${account.persistedId}] created library [${repo.persistedId}] [${repo.name}]")
      cask.Response("", 200, Seq("Content-Type" -> "text/html", "HX-Redirect" -> s"/pipeline/r/${repo.persistedId}"))
    catch
      case ex: ValidationException => responseWithValidationError(ex.errors.getOrElse(FieldConst.Repository.NAME, ex.message))
      case ex: DuplicateException => responseWithValidationError(ex.message.getOrElse(C.Msg.Err.LIBRARY_NAME_TAKEN.format(name)))
      // A creator who is no Admin already holds as many grants as an account may (`TOO_MANY_GRANTS`): no Library is created
      case ex: IllegalOperationException => responseWithValidationError(ex.getMessage)

  initialize()
