package software.altitude.core.pipeline.sinks

import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.Future

object AssetSeqOutputSink {
  def apply[T](): Sink[T, Future[Seq[T]]] = Sink.seq[T]
}
