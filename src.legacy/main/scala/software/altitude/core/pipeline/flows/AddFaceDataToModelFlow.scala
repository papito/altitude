package software.altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import software.altitude.core.Altitude
import software.altitude.core.pipeline.PipelineConstants.parallelism
import software.altitude.core.pipeline.PipelineTypes.TFaceWithContext
import software.altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

object AddFaceDataToModelFlow {
  def apply(app: Altitude): Flow[TFaceWithContext, TFaceWithContext, NotUsed] = {
    Flow[TFaceWithContext]
      .grouped(1000 * parallelism)
      .map {
        batch =>
          val faces = batch.map(_._1)
          val ctx = batch.head._2
          setThreadLocalRequestContext(ctx)
          app.service.faceRecognition.indexFaces(faces)
          batch
      }
      .mapConcat(identity)
  }
}
