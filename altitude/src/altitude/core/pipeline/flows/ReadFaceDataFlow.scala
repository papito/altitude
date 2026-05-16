package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TFaceWithContext
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

/** This scoops up aligned grayscale face images from the file store, and moves them downstream. */
object ReadFaceDataFlow:
  def apply(app: Altitude): Flow[TFaceWithContext, TFaceWithContext, NotUsed] =
    Flow[TFaceWithContext].map {
      case (face, ctx) =>
        setThreadLocalRequestContext(ctx)
        (face, ctx)
    }
