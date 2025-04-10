package software.altitude.core.pipeline.sinks

import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.Future

import software.altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext

object VoidAssetSink {
  def apply[T](): Sink[T, Future[Seq[T]]] =
    Sink.fold(Seq.empty[T])((acc, _) => acc)
}
