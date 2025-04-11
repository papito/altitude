package software.altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import software.altitude.core.Altitude
import software.altitude.core.pipeline.PipelineTypes.TAssetWithContext
import software.altitude.core.pipeline.PipelineUtils.debugInfo
import software.altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

object DeletePersonFilesFlow {
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def apply(app: Altitude): Flow[TAssetWithContext, TAssetWithContext, NotUsed] =
    Flow[TAssetWithContext].map {
      case (asset, ctx) =>
        setThreadLocalRequestContext(ctx)

        debugInfo(s"\tRemoving PERSON files for asset ${asset.fileName}")

        try {
          app.service.person.getPeopleForAsset(asset.persistedId).foreach {
            person =>
              debugInfo(s"\t\tRemoving PERSON face files for ${person.name}")
              app.service.person
                .getPersonFaces(person.persistedId)
                // do not take faces marked as "cover"
                .filterNot(_.persistedId == person.coverFaceId.getOrElse(""))
                .map(
                  face => {
                    debugInfo(s"\t\tRemoving FACE files for ${face.persistedId}")
                    app.service.fileStore.purgeFaceById(face.persistedId)
                  })
          }
        } catch {
          case _: Exception =>
            logger.error(s"Error purging PERSON file data for asset ${asset.persistedId}")
        }
        (asset, ctx)
    }
}
