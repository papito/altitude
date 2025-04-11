package software.altitude.core.pipeline

object PipelineConstants {
  val parallelism: Int = Runtime.getRuntime.availableProcessors

  /**
   * Set to "true" to enable debugging output to console.
   *
   * Useful for understanding the flow of the pipeline and its use of threads when messing with all the knobs.
   */
  val DEBUG = false
}
