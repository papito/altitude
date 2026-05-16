package altitude.core.pipeline

import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.Face
import altitude.core.models.Repository
import altitude.core.models.User

object PipelineTypes:
  case class PipelineContext(repository: Repository, account: User)
  case class InvalidAsset(payload: Asset, cause: Option[Throwable])

  type TAssetOrInvalid = Either[Asset, InvalidAsset]
  private type TDataAssetOrInvalid = Either[AssetWithData, InvalidAsset]
  type TDataAssetOrInvalidWithContext = (TDataAssetOrInvalid, PipelineContext)
  type TDataAssetWithContext = (AssetWithData, PipelineContext)
  type TAssetOrInvalidWithContext = (TAssetOrInvalid, PipelineContext)
  type TAssetWithContext = (Asset, PipelineContext)

  type TFaceWithContext = (Face, PipelineContext)
