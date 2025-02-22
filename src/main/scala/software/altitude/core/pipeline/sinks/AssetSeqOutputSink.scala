package software.altitude.core.pipeline.sinks

import org.apache.pekko.stream.scaladsl.Sink
import software.altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext

import scala.concurrent.Future

object AssetSeqOutputSink {
  def apply(): Sink[TAssetOrInvalidWithContext, Future[Seq[TAssetOrInvalidWithContext]]] = Sink.seq[TAssetOrInvalidWithContext]
}
