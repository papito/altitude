package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.stage

object FileStoreFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    stage("File storage", app.parallelism) {
      dataAsset =>
        debugInfo(s"\tStoring asset ${dataAsset.asset.fileName}")
        app.service.fileStore.addAsset(dataAsset)
        // The staged file is gone: what follows reads the stored one
        Left(dataAsset.copy(path = app.service.fileStore.assetFile(dataAsset.asset.persistedId)))
    }
