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

  /**
   * Moves every cover off the asset's Faces, then deletes their files. An asset whose covers cannot be moved goes no further down
   * the purge: its row, its files and its Faces stay, still marked for purging, for the startup job to queue again, since the
   * row's delete would drop a cover through the cover's key without a repick, and the cover Face's files would be lost.
   */
  def apply(app: Altitude): Flow[TAssetWithContext, TAssetWithContext, NotUsed] =
    Flow[TAssetWithContext].mapConcat {
      case (asset, ctx) =>
        withContext(ctx) {
          debugInfo(s"\tRemoving PERSON files for asset ${asset.persistedId}")

          val coversMoved =
            try
              app.service.person.moveCoversOff(Set(asset.persistedId))
              true
            catch
              case ex: Exception =>
                logger.error(s"Error moving the covers off the faces of asset ${asset.persistedId}; it stays purge-pending", ex)
                false

          if coversMoved then
            try app.service.person.purgeFaceFiles(app.service.person.getAllAssetFaces(asset.persistedId))
            catch
              case ex: Exception =>
                logger.error(s"Error purging PERSON file data for asset ${asset.persistedId}", ex)
            List((asset, ctx))
          else Nil
        }
    }
