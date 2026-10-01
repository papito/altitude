package altitude.core.models

/**
 * What a probe of a Video's container reports: its display size (the container's rotation applied, so a portrait phone recording
 * is portrait), its duration, its frame rate, and whether it carries audio
 */
case class VideoInfo(width: Int, height: Int, durationMs: Long, frameRate: Double, hasAudio: Boolean)
