package altitude.core.pipeline

import altitude.core.RequestContext
import altitude.core.pipeline.PipelineConstants.DEBUG
import altitude.core.pipeline.PipelineTypes.PipelineContext

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
