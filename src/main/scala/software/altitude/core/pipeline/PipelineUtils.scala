package software.altitude.core.pipeline

import software.altitude.core.RequestContext
import software.altitude.core.pipeline.PipelineConstants.DEBUG
import software.altitude.core.pipeline.PipelineTypes.PipelineContext

object PipelineUtils {
  def setThreadLocalRequestContext(ctx: PipelineContext): Unit = {
    RequestContext.repository.value = Some(ctx.repository)
    RequestContext.account.value = Some(ctx.account)
  }

  def debugInfo(msg: String): Unit = {
    if (DEBUG) {
      println(s"(${Thread.currentThread().getName}) $msg")
    }
  }
}
