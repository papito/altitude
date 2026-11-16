package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext

/** After the last working stage, an imported asset goes on to the sinks without its file, detected faces and origin */
object StripBinaryDataFlow:
  def apply(): Flow[TDataAssetOrInvalidWithContext, TAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].map {
      case (assetWithDataOrInvalid, ctx) => (assetWithDataOrInvalid.left.map(_.asset), ctx)
    }
