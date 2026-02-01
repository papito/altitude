package altitude.core.pipeline.sinks

import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.Future

import altitude.core.Altitude
import altitude.core.actors.ImportStatusWsActor
import altitude.core.pipeline.PipelineTypes
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalid
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext

object WsAssetProcessedNotificationSink {
  def apply(app: Altitude): Sink[(TAssetOrInvalid, PipelineTypes.PipelineContext), Future[Done]] =
    Sink.foreach[TAssetOrInvalidWithContext] {
      assetOrInvalidWithContext =>
        val (assetOrInvalid, ctx) = assetOrInvalidWithContext
        app.actorSystem ! ImportStatusWsActor.UserWideImportStatus(ctx.account.persistedId, assetOrInvalid)
    }
}
