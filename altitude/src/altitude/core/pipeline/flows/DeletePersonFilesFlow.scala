package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TAssetWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

object DeletePersonFilesFlow {
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def apply(app: Altitude): Flow[TAssetWithContext, TAssetWithContext, NotUsed] =
    Flow[TAssetWithContext].map {
      case (asset, ctx) =>
        setThreadLocalRequestContext(ctx)

        debugInfo(s"\tRemoving PERSON files for asset ${asset.persistedId}")

        try {
          val peopleInAsset = app.service.person.getPeopleForAsset(asset.persistedId)
          val personLookup = peopleInAsset.map(person => person.persistedId -> person).toMap
          val assetFaces = app.service.person.getAssetFaces(asset.persistedId)

          assetFaces
            .foreach {
              face =>
                val person = personLookup(face.personId.get)
                if (!person.coverFaceId.contains(face.persistedId)) {
                  debugInfo(s"\t\tRemoving FACE files for ${face.persistedId}")
                  app.service.fileStore.purgeFaceById(face.persistedId)
                }
            }
        } catch {
          case _: Exception =>
            logger.error(s"Error purging PERSON file data for asset ${asset.persistedId}")
        }
        (asset, ctx)
    }
}
