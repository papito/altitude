package software.altitude.core.pipeline.sinks

import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink
import software.altitude.core.Altitude
import software.altitude.core.actors.ImportStatusWsActor
import software.altitude.core.pipeline.PipelineTypes
import software.altitude.core.pipeline.PipelineTypes.TAssetOrInvalid
import software.altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext

import scala.concurrent.Future

object WsAssetProcessedNotificationSink {
  def apply(app: Altitude): Sink[(TAssetOrInvalid, PipelineTypes.PipelineContext), Future[Done]] =
    Sink.foreach[TAssetOrInvalidWithContext] {
      assetOrInvalidWithContext =>
        val (assetOrInvalid, ctx) = assetOrInvalidWithContext
        app.actorSystem ! ImportStatusWsActor.UserWideImportStatus(ctx.account.persistedId, assetOrInvalid)
    }
}
