package altitude.test

import altitude.core.models.Settings

/** A Settings snapshot for a test that has no database: the seeds of the `setting` table, with a test's own values over them */
object TestSettings {

  /** The seeds the plan names, which the table's default_value column carries */
  val seeds: Map[String, String] = Map(
    Settings.FACE_YUNET_CONFIDENCE_THRESHOLD -> "0.85",
    Settings.FACE_YUNET_NMS_THRESHOLD -> "0.3",
    Settings.FACE_DETECTION_BOUNDING_BOX_SIZE -> "1280",
    Settings.FACE_DETECTION_MIN_FACE_SIZE -> "50",
    Settings.FACE_RECOGNITION_COSINE_DISTANCE_THRESHOLD -> "0.55",
    Settings.FACE_RECOGNITION_MATCH_COUNT -> "5",
    Settings.FACE_RECOGNITION_HNSW_EF_SEARCH -> "100",
    Settings.FACE_QUALITY_ENROLL_THRESHOLD -> "18.0",
    Settings.FACE_QUALITY_KEEP_THRESHOLD -> "15.0",
    Settings.VIDEO_FACES_SAMPLE_INTERVAL_MS -> "1000",
    Settings.VIDEO_FACES_MAX_SAMPLED_FRAMES -> "120",
    Settings.VIDEO_FACES_MIN_CLUSTER_FRAMES -> "2",
    Settings.VIDEO_PREVIEW_MIN_LUMINANCE -> "40",
    Settings.SEQUENCE_MAX_GAP_MS -> "3000",
    Settings.SEQUENCE_MAX_HASH_DISTANCE -> "10",
    Settings.MAP_TILE_URL -> "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    Settings.MAP_TILE_ATTRIBUTION -> "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors",
    Settings.MAP_GEOCODER_ENABLED -> "false",
    Settings.MAP_GEOCODER_URL -> "https://nominatim.openstreetmap.org/search",
    Settings.DB_POSTGRES_READ_STATEMENT_TIMEOUT -> "30",
    Settings.PIPELINE_SHUTDOWN_TIMEOUT -> "30",
    Settings.IMPORT_PARALLELISM -> "0"
  )

  def apply(values: Map[String, String] = Map.empty): Settings = Settings(seeds ++ values, seeds)
}
