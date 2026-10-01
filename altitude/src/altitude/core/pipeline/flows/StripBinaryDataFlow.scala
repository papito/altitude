package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext

/** Where the file leaves the pipeline: a stored asset goes on without it, and a dropped asset's staged file is deleted */
object StripBinaryDataFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].map {

      case (assetWithDataOrInvalid, ctx) =>
        val assetOrInvalid = assetWithDataOrInvalid match {
          case Left(assetWithData) => Left(assetWithData.asset)
          case Right(invalid) =>
            invalid.stagedFile.foreach(app.service.staging.discard)
            Right(invalid)
        }

        (assetOrInvalid, ctx)
    }
