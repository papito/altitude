package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.ExecutionContext

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.stage

object IndexFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    given ExecutionContext = app.importDispatcher
    stage("Indexing", parallelism = 1) {
      dataAsset =>
        debugInfo(s"\tPersisting and indexing asset ${dataAsset.asset.fileName}")
        // The same content imported twice at once is a DuplicateException here, from the checksum's unique index
        Left(dataAsset.copy(asset = app.service.library.persistAndIndex(dataAsset.asset)))
    }
