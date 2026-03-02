package altitude.core.pipeline.flows

import altitude.core.Altitude
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

object AssignIdFlow {
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].map {
      case (Left(dataAsset), ctx) =>
        val asset: Asset = dataAsset.asset.copy(
          id = Some(BaseDao.genId)
        )
        debugInfo(s"\tAssigning ID to asset: ${asset.id.get}")

        (Left(dataAsset.copy(asset = asset)), ctx)
      case (Right(invalid), ctx) => (Right(invalid), ctx)
    }
}
