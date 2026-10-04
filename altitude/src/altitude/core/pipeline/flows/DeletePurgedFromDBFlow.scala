package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Future

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TAssetWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.withContext

object DeletePurgedFromDBFlow:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def apply(app: Altitude): Flow[TAssetWithContext, TAssetWithContext, NotUsed] =
    Flow[TAssetWithContext].mapAsync(app.parallelism) {
      case (asset, ctx) =>
        withContext(ctx) {
          debugInfo(s"\tDeleting purged asset from the database ${asset.persistedId}")

          // A failure would end the queue's stream; the row stays marked for purging, for the startup job to queue again
          try app.service.asset.deleteById(asset.persistedId)
          catch
            case ex: Exception =>
              logger.error(s"Error deleting purged asset ${asset.persistedId} from the database", ex)
        }

        Future.successful(asset, ctx)
    }
