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
   * What a pipeline element's work runs in: its repository, the account that queued it, which an import always has and the
   * startup's requeue of purge-pending assets does not, and the account's Permissions in the repository when it queued it (none
   * without an account)
   */
  case class PipelineContext(repository: Repository, account: Option[User], permissions: Permissions = Permissions.none)

  /**
   * An asset the pipeline dropped, with its staged file if it still has one, for the pipeline's end to discard, and the Import
   * file it is a file of, if any, for the pipeline's end to settle
   */
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
