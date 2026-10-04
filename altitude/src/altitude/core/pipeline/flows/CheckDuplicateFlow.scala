package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.pipeline.PipelineTypes.InvalidAsset
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.stage

object CheckDuplicateFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    stage("Duplicate check", app.parallelism) {
      dataAsset =>
        debugInfo(s"\tChecking for duplicate for ${dataAsset.asset.fileName}")

        if app.service.asset.getByChecksum(dataAsset.asset.checksum).isDefined then
          Right(InvalidAsset(dataAsset, DuplicateException()))
        else Left(dataAsset)
    }
