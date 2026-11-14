package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.ExecutionContext

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.stage

/** Completes the import of an asset, which settles the Import file it is a file of, if any, in the same transaction */
object MarkAsCompleteFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    given ExecutionContext = app.importDispatcher
    stage("Import completion", parallelism = 1) {
      dataAsset =>
        debugInfo(s"\tMarking asset as pipeline-complete ${dataAsset.asset.fileName}")
        Left(dataAsset.copy(asset = app.service.library.completeImport(dataAsset.asset, dataAsset.origin)))
    }
