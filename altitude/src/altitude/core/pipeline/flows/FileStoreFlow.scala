package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

object FileStoreFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(app.parallelism) {
      case (Left(dataAsset), ctx) =>
        setThreadLocalRequestContext(ctx)

        debugInfo(s"\tStoring asset ${dataAsset.asset.fileName}")
        app.service.fileStore.addAsset(dataAsset)
        // The staged file is gone: what follows reads the stored one
        val stored = dataAsset.copy(path = app.service.fileStore.assetFile(dataAsset.asset.persistedId))
        Future.successful((Left(stored), ctx))
      case (Right(invalid), ctx) => Future.successful((Right(invalid), ctx))
    }
