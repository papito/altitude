package software.altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

import software.altitude.core.Altitude
import software.altitude.core.pipeline.PipelineConstants.parallelism
import software.altitude.core.pipeline.PipelineTypes.TAssetWithContext
import software.altitude.core.pipeline.PipelineUtils.debugInfo
import software.altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

object DeletePurgedFromDBFlow {
  def apply(app: Altitude): Flow[TAssetWithContext, TAssetWithContext, NotUsed] =
    Flow[TAssetWithContext].mapAsync(parallelism) {
      case (asset, ctx) =>
        setThreadLocalRequestContext(ctx)

        app.txManager.withTransaction {
          debugInfo(s"\tDeleting purged asset from the database ${asset.persistedId}")
          app.service.asset.deleteById(asset.persistedId)
        }

        Future.successful(asset, ctx)
    }

}
