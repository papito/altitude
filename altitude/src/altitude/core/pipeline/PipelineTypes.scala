package altitude.core.pipeline

import java.nio.file.Path

import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.Repository
import altitude.core.models.User

object PipelineTypes:
  /**
   * What a pipeline element's work runs in: its repository, and the account that queued it, which an import always has and the
   * startup's requeue of purge-pending assets does not
   */
  case class PipelineContext(repository: Repository, account: Option[User])

  /** An asset the pipeline dropped, with its staged file if it still has one, for the pipeline's end to discard */
  case class InvalidAsset(payload: Asset, cause: Option[Throwable], stagedFile: Option[Path] = None)

  object InvalidAsset:
    def apply(dataAsset: AssetWithData, cause: Throwable): InvalidAsset =
      InvalidAsset(dataAsset.asset, Some(cause), Some(dataAsset.path))

  type TAssetOrInvalid = Either[Asset, InvalidAsset]
  type TDataAssetOrInvalid = Either[AssetWithData, InvalidAsset]
  type TDataAssetOrInvalidWithContext = (TDataAssetOrInvalid, PipelineContext)
  type TDataAssetWithContext = (AssetWithData, PipelineContext)
  type TAssetOrInvalidWithContext = (TAssetOrInvalid, PipelineContext)
  type TAssetWithContext = (Asset, PipelineContext)
