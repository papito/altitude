package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext

object StripBinaryDataFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].map {

      case (assetWithDataOrInvalid, ctx) =>
        val assetOrInvalid = assetWithDataOrInvalid match {
          case Left(assetWithData) => Left(assetWithData.asset)
          case Right(invalid) => Right(invalid)
        }

        (assetOrInvalid, ctx)
    }
