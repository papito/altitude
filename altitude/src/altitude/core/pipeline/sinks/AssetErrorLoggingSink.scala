package altitude.core.pipeline.sinks

import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Future

import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.isExpectedDrop

/**
 * Logs each dropped asset whose cause was expected, in one line. Any other cause was logged with its stack trace by the stage
 * that threw it (`PipelineUtils.guarded`), so it is not logged again.
 */
object AssetErrorLoggingSink:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def apply(): Sink[TAssetOrInvalidWithContext, Future[Done]] = Sink.foreach[TAssetOrInvalidWithContext] {
    case (Right(invalid), ctx) =>
      invalid.cause.filter(isExpectedDrop).foreach {
        cause => logger.info(s"Dropped ${invalid.payload.fileName} [repo: ${ctx.repository.persistedId}]: $cause")
      }
    case (Left(_), _) =>
  }
