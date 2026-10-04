package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.withContext

/**
 * The last stage: a dropped asset leaves nothing behind. Its staged file is deleted and whatever its import wrote is undone
 * (`LibraryService.discardImport`), so the same file can be uploaded again at once.
 */
object DiscardDroppedFlow:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def apply(app: Altitude): Flow[TAssetOrInvalidWithContext, TAssetOrInvalidWithContext, NotUsed] =
    given ExecutionContext = app.importDispatcher
    Flow[TAssetOrInvalidWithContext].mapAsync(1) {
      // Not under the guard, which would report the discard's failure in place of the drop's cause
      case dropped @ (Right(invalid), ctx) =>
        Future {
          withContext(ctx) {
            debugInfo(s"\tDiscarding dropped asset ${invalid.payload.fileName}")
            try
              invalid.stagedFile.foreach(app.service.staging.discard)
              app.service.library.discardImport(invalid.payload)
            catch
              // The drop is still reported with its own cause, and the next startup's prune discards what is left
              case NonFatal(e) => logger.error(s"Discarding the dropped import of ${invalid.payload.fileName} failed", e)
          }
          dropped
        }
      case imported => Future.successful(imported)
    }
