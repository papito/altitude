package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

import altitude.core.Altitude
import altitude.core.pipeline.PipelineConstants.parallelism
import altitude.core.pipeline.PipelineTypes.TAssetWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

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
