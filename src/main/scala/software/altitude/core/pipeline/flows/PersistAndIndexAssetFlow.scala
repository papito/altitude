package software.altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import software.altitude.core.Altitude
import software.altitude.core.DuplicateException
import software.altitude.core.pipeline.PipelineConstants.parallelism
import software.altitude.core.pipeline.PipelineTypes.InvalidAsset
import software.altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import software.altitude.core.pipeline.PipelineUtils.debugInfo
import software.altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

import scala.concurrent.Future

object PersistAndIndexAssetFlow {
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(parallelism) {
      case (Left(dataAsset), ctx) =>
        setThreadLocalRequestContext(ctx)

        app.txManager.withTransaction {
          try {
            debugInfo(s"\tPersisting asset ${dataAsset.asset.fileName}")
            app.service.asset.add(dataAsset.asset)
            debugInfo(s"\tIndexing asset ${dataAsset.asset.fileName}")
            app.service.search.indexAsset(dataAsset.asset)
            debugInfo(s"\tUpdating stats for asset ${dataAsset.asset.fileName}")
            app.service.stats.addAsset(dataAsset.asset)
            Future.successful((Left(dataAsset), ctx))
          } catch {
            case e: DuplicateException =>
              Future.successful(Right(InvalidAsset(dataAsset.asset, Some(e))), ctx)
          }
        }
      case (Right(invalid), ctx) =>
        Future.successful((Right(invalid), ctx))
    }

}
