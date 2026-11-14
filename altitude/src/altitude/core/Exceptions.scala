package altitude.core

import scala.collection.mutable

import altitude.core.models.Asset
import altitude.core.models.EditState

case class ValidationException(message: String = "") extends Exception:
  final def isEmpty: Boolean = message.isEmpty && errors.isEmpty
  final def nonEmpty: Boolean = !isEmpty

  val errors: mutable.Map[String, String] = mutable.Map()

  def trigger(): Unit =
    if nonEmpty then throw this

// All-purpose event to get out of loops and sticky situations
case class AllDone(success: Boolean) extends Exception

case class DuplicateException(message: Option[String] = None) extends Exception

case class ConstraintException(msg: String) extends Exception(msg)

case class UnsupportedMediaTypeException(asset: Asset)
  extends Exception(s"Unsupported media type \"${asset.assetType.mediaType}\"")

case class IllegalOperationException(msg: String) extends IllegalArgumentException(msg)

/** A read statement ran past the engine's time limit for reads and was cancelled */
case class QueryTimeoutException(msg: String) extends Exception(msg)

/** A grouped-search continuation cursor that is malformed or belongs to a different search */
case class SearchCursorException(msg: String) extends IllegalArgumentException(msg)

case class MetadataExtractorException(asset: Asset, ex: Throwable) extends Exception(ex)

case class NotFoundException(msg: String) extends Exception(msg)

/**
 * The account holds no Permission for what it asked of an asset (a rule a route's decorators cannot decide): a 403 through
 * `BaseController.answering` and `answeringJson`
 */
case class ForbiddenException(msg: String) extends Exception(msg)

case class StorageException(msg: String) extends Exception(msg)

/**
 * The account an imported file is inserted for is gone or deactivated by then (`LibraryService.persistAndIndex`), so nobody can
 * own the asset: the file fails with this, saying which
 */
case class ImporterUnavailableException(msg: String) extends Exception(msg)

/**
 * A file of an Import cannot be sorted into the folder it lands in (`FolderService.ensurePath`): the Import's destination is in
 * the Trash, or a folder of the file's path has a name the folder rule refuses. The file fails with this, saying which.
 */
case class ImportDestinationException(msg: String) extends Exception(msg)

/** The external geocoder could not be reached or did not answer with a list of places */
case class GeocoderException(msg: String) extends Exception(msg)

case class SamePersonDetectedTwiceException(msg: String) extends Exception(msg)

/** A Video FFmpeg could not open, a file it opened that has no video stream, or one no frame of which decodes */
case class VideoException(msg: String) extends Exception(msg)

/** An image no image reader can decode */
case class ImageException(msg: String) extends Exception(msg)

/** A queue did not take an element: it was full, closed, or its stream had stopped */
case class QueueRefusedException(msg: String) extends Exception(msg)

/**
 * A write of an asset's Edit built on a head that is no longer the head (`AssetEditService`): the state the Edit is in now, which
 * `EditController` answers with a 409 for the client to adopt
 */
case class EditConflictException(state: EditState)
  extends Exception(s"The Edit's head is ${state.head}, not the one the change was built on")
