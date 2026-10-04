package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.guardedAsync

object MarkAsCompleteFlow:
  def apply(app: Altitude): Flow[TAssetOrInvalidWithContext, TAssetOrInvalidWithContext, NotUsed] =
    given ExecutionContext = app.importDispatcher
    Flow[TAssetOrInvalidWithContext].mapAsync(1) {
      case (Left(asset), ctx) =>
        guardedAsync("Import completion", asset, ctx) {
          debugInfo(s"\tMarking asset as pipeline-complete ${asset.fileName}")
          Left(app.service.library.completeImport(asset))
        }
      case dropped => Future.successful(dropped)
    }
