package software.altitude.core.pipeline

import software.altitude.core.models.Asset
import software.altitude.core.models.AssetWithData
import software.altitude.core.models.Face
import software.altitude.core.models.Repository
import software.altitude.core.models.User

object PipelineTypes {
  case class PipelineContext(repository: Repository, account: User)
  case class InvalidAsset(payload: Asset, cause: Option[Throwable])

  type TAssetOrInvalid = Either[Asset, InvalidAsset]
  private type TDataAssetOrInvalid = Either[AssetWithData, InvalidAsset]
  type TDataAssetOrInvalidWithContext = (TDataAssetOrInvalid, PipelineContext)
  type TDataAssetWithContext = (AssetWithData, PipelineContext)
  type TAssetOrInvalidWithContext = (TAssetOrInvalid, PipelineContext)

  type TFaceWithContext = (Face, PipelineContext)
}
