package altitude.core.pipeline

import java.nio.file.Path

import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.ImportOrigin
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.User

object PipelineTypes:
  /**
   * What a pipeline element's work runs in. `account` is the one that queued it: an import always has one, the startup's requeue
   * of purge-pending assets does not. `permissions` are that account's when it queued the work.
   */
  case class PipelineContext(repository: Repository, account: Option[User], permissions: Permissions = Permissions.none)

  /** An asset the pipeline dropped, with its staged file for the pipeline's end to discard and its Import file to settle */
  case class InvalidAsset(
      payload: Asset,
      cause: Option[Throwable],
      stagedFile: Option[Path] = None,
      origin: Option[ImportOrigin] = None)

  object InvalidAsset:
    def apply(dataAsset: AssetWithData, cause: Throwable): InvalidAsset =
      InvalidAsset(dataAsset.asset, Some(cause), Some(dataAsset.path), dataAsset.origin)

  type TAssetOrInvalid = Either[Asset, InvalidAsset]
  type TDataAssetOrInvalid = Either[AssetWithData, InvalidAsset]
  type TDataAssetOrInvalidWithContext = (TDataAssetOrInvalid, PipelineContext)
  type TDataAssetWithContext = (AssetWithData, PipelineContext)
  type TAssetOrInvalidWithContext = (TAssetOrInvalid, PipelineContext)
  type TAssetWithContext = (Asset, PipelineContext)
