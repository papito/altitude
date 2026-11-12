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
 * The last stage that works: a dropped asset that is a file of an Import has its Import file settled by the cause it was dropped
 * for (`ImportService.settleDropped`), once what its import wrote is undone. An imported one was settled by its completion, in
 * that transaction, and an asset with no origin has no Import file.
 *
 * A Watch folder's file (an origin with a source) is then told to its Watch folder, imported or dropped, with the transaction
 * that settled it committed: the Watch folder remembers what became of the file and, when it deletes imported files, deletes the
 * source of one that was imported or is a duplicate (`WatchFolderService.settled`).
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

  // Tells a Watch folder what became of its file. A failure leaves what the Watch folder remembers of the file as it was, so a
  // later scan reads the file again, and deletes nothing
  private def tellWatchFolder(app: Altitude, origin: ImportOrigin, asset: Asset, cause: Option[Throwable]): Unit =
    if origin.source.isDefined then
      try app.service.watchFolder.settled(origin, asset, cause)
      catch case NonFatal(e) => logger.error(s"Telling its Watch folder what became of ${asset.fileName} failed", e)
