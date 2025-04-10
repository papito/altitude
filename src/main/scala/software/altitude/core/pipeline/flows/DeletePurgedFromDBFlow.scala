package software.altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import software.altitude.core.Altitude
import software.altitude.core.pipeline.PipelineConstants.parallelism
import software.altitude.core.pipeline.PipelineTypes.TAssetWithContext
import software.altitude.core.pipeline.PipelineUtils.debugInfo
import software.altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

import scala.concurrent.Future

object DeletePurgedFromDBFlow {
  def apply(app: Altitude): Flow[TAssetWithContext, TAssetWithContext, NotUsed] =
    Flow[TAssetWithContext].mapAsync(parallelism) {
      case (asset, ctx) =>
        setThreadLocalRequestContext(ctx)

        app.txManager.withTransaction {
          debugInfo(s"\tDeleting purged asset from the database ${asset.fileName}")
          app.service.asset.deleteById(asset.persistedId)
        }

        Future.successful(asset, ctx)
    }

}
