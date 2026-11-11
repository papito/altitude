package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TAssetWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.withContext

object DeletePersonFilesFlow:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def apply(app: Altitude): Flow[TAssetWithContext, TAssetWithContext, NotUsed] =
    Flow[TAssetWithContext].map {
      case (asset, ctx) =>
        withContext(ctx) {
          debugInfo(s"\tRemoving PERSON files for asset ${asset.persistedId}")

          // Every cover is moved off the asset's Faces first, so all of their files can go; a failure to move one keeps them
          try {
            app.service.person.moveCoversOff(Set(asset.persistedId))
            app.service.person.purgeFaceFiles(app.service.person.getAllAssetFaces(asset.persistedId))
          } catch {
            case ex: Exception =>
              logger.error(s"Error purging PERSON file data for asset ${asset.persistedId}", ex)
          }
        }
        (asset, ctx)
    }
