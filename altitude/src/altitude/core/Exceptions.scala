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

/** The account may not do what it asked, by a rule a route's decorators cannot decide: a 403 (`BaseController.answering`) */
case class ForbiddenException(msg: String) extends Exception(msg)

case class StorageException(msg: String) extends Exception(msg)

/** The account an imported file is inserted for is gone or deactivated by then, so nobody can own the asset: the file fails */
case class ImporterUnavailableException(msg: String) extends Exception(msg)

/**
 * A file of an Import cannot be sorted into its folder: the Import's destination is in the Trash, or a folder of the file's path
 * has a name the folder rule refuses. The file fails with this, saying which.
 */
case class ImportDestinationException(msg: String) extends Exception(msg)

/** The external geocoder could not be reached or did not answer with a list of places */
case class GeocoderException(msg: String) extends Exception(msg)

case class SamePersonDetectedTwiceException(msg: String) extends Exception(msg)

/** A video FFmpeg cannot open, that has no video stream, or of which no frame decodes */
case class VideoException(msg: String) extends Exception(msg)

/** An image no image reader can decode */
case class ImageException(msg: String) extends Exception(msg)

/**
 * The Scene embedder is closed: the server is stopping, and an import still under way once its drain ran out asked for a pass. It
 * is no failed pass. The image is not one the models could not embed, so nothing of it may be kept as if it were: the file fails,
 * as the files the stop left pending do at the next start.
 */
case class SceneEmbedderClosedException() extends Exception("The Scene embedder is closed")

/**
 * The model files could not be put in place: too little free space, a failed download, or a size or SHA-256 off its pin. The
 * message names the missing files, their URLs and the directory to put them in by hand; the cause is the download's I/O error.
 */
case class ModelFilesException(msg: String, cause: Option[Throwable] = None) extends Exception(msg, cause.orNull)

/** Work bounded to a few at once has no free slot: a 503, to be asked again */
case class BusyException(msg: String) extends Exception(msg)

/** A queue did not take an element: it was full, closed, or its stream had stopped */
case class QueueRefusedException(msg: String) extends Exception(msg)

/** A save built on an edit version the Edit has moved on from: carries the current state, answered with a 409 to adopt */
case class EditConflictException(state: EditState)
  extends Exception(s"The Edit's edit version is ${state.editVersion}, not the one the save was built on")
