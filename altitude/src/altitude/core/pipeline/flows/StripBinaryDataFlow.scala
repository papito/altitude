package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext

/** Where the file leaves the pipeline: a stored asset goes on without it, and a dropped asset keeps its staged file to discard */
object StripBinaryDataFlow:
  def apply(): Flow[TDataAssetOrInvalidWithContext, TAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].map {
      case (assetWithDataOrInvalid, ctx) => (assetWithDataOrInvalid.left.map(_.asset), ctx)
    }
