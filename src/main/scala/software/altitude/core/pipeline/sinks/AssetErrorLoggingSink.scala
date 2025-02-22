// src/main/scala/software/altitude/core/pipeline/sinks/ErrorLoggingSink.scala
package software.altitude.core.pipeline.sinks

import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Future

import software.altitude.core.pipeline.PipelineTypes
import software.altitude.core.pipeline.PipelineTypes.TAssetOrInvalid
import software.altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext

object AssetErrorLoggingSink {
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def apply(): Sink[(TAssetOrInvalid, PipelineTypes.PipelineContext), Future[Done]] = Sink.foreach[TAssetOrInvalidWithContext] {
    assetOrInvalidWithContext =>
      val (assetOrInvalid, ctx) = assetOrInvalidWithContext
      assetOrInvalid match {
        case Right(invalid) =>
          logger.error(
            s"Error processing asset [repo: ${ctx.repository.persistedId}] ${invalid.cause.getOrElse("Unknown cause")}")
        case Left(_) =>
      }
  }
}
