package altitude.core.pipeline.flows

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

object StripBinaryDataFlow {
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].map {

      case (assetWithDataOrInvalid, ctx) =>
        val assetOrInvalid = assetWithDataOrInvalid match {
          case Left(assetWithData) => Left(assetWithData.asset)
          case Right(invalid) => Right(invalid)
        }

        (assetOrInvalid, ctx)
    }
}
