package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.InvalidAsset
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.withContext

/**
 * The last stage that works: a dropped asset that is a file of an Import has its Import file settled by the cause it was dropped
 * for (`ImportService.settleDropped`), once what its import wrote is undone. An imported one was settled by its completion, in
 * that transaction, and an asset with no origin has no Import file.
 */
object SettleImportFileFlow:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    given ExecutionContext = app.importDispatcher
    Flow[TDataAssetOrInvalidWithContext].mapAsync(1) {
      // Not under the guard, as the discard is not: the drop is reported with its own cause
      case dropped @ (Right(InvalidAsset(asset, cause, _, Some(origin))), ctx) =>
        Future {
          withContext(ctx) {
            debugInfo(s"\tSettling the Import file of dropped asset ${asset.fileName}")
            try app.service.imports.settleDropped(origin.importFileId, asset, cause)
            catch
              // The file stays pending, and its Import in progress, until the next startup fails and closes them
              case NonFatal(e) => logger.error(s"Settling the Import file of the dropped ${asset.fileName} failed", e)
          }
          dropped
        }
      case other => Future.successful(other)
    }
