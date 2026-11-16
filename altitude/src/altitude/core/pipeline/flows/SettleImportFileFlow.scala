package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import altitude.core.Altitude
import altitude.core.models.Asset
import altitude.core.models.ImportOrigin
import altitude.core.pipeline.PipelineTypes.InvalidAsset
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.withContext

/**
 * The last working stage: a dropped asset's Import file is settled by its drop cause, once what its import wrote is undone (an
 * imported one was settled in its completion's transaction). A Watch folder's file (an origin with a source) is then reported to
 * its Watch folder, imported or dropped, after the settling transaction has committed.
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
            tellWatchFolder(app, origin, asset, cause)
          }
          dropped
        }
      case imported @ (Left(dataAsset), ctx) if dataAsset.origin.exists(_.source.isDefined) =>
        Future {
          withContext(ctx)(tellWatchFolder(app, dataAsset.origin.get, dataAsset.asset, cause = None))
          imported
        }
      case other => Future.successful(other)
    }

  // A failure leaves the Watch folder's memory of the file as it was, so a later scan reads the file again, and deletes nothing
  private def tellWatchFolder(app: Altitude, origin: ImportOrigin, asset: Asset, cause: Option[Throwable]): Unit =
    if origin.source.isDefined then
      try app.service.watchFolder.settled(origin, asset, cause)
      catch case NonFatal(e) => logger.error(s"Telling its Watch folder what became of ${asset.fileName} failed", e)
