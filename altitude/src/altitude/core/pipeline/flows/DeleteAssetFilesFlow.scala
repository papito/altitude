package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TAssetWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

object DeleteAssetFilesFlow {
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def apply(app: Altitude): Flow[TAssetWithContext, TAssetWithContext, NotUsed] =
    Flow[TAssetWithContext].map {
      case (asset, ctx) =>
        setThreadLocalRequestContext(ctx)

        debugInfo(s"\tRemoving files for ${asset.persistedId}")

        try {
          app.service.fileStore.purgeAssetById(asset.persistedId)
        } catch {
          case _: Exception =>
            logger.error(s"Error purging file data for asset ${asset.persistedId}")
        }
        (asset, ctx)
    }
}
