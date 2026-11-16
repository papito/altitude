package altitude.core.models

/** What a probe of a Video's container reports; the size has the rotation applied, so a portrait phone recording is portrait */
case class VideoInfo(width: Int, height: Int, durationMs: Long, frameRate: Double, hasAudio: Boolean)
