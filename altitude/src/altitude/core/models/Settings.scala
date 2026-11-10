package altitude.core.models

import java.net.URI

import scala.util.Try

import altitude.core.Const
import altitude.core.util.ImageMeasurement

/** The sections of the Settings screen */
enum SettingGroup(val label: String):
  case FaceDetection extends SettingGroup("Face detection")
  case FaceRecognition extends SettingGroup("Face recognition and quality")
  case Video extends SettingGroup("Video")
  case Triage extends SettingGroup("Triage")
  case Maps extends SettingGroup("Map")
  case Operations extends SettingGroup("Operations")

/** A Live setting is used from the next operation that reads it; a Restart setting from the next server start */
enum SettingKind:
  case Live, Restart

/** What a Setting's text must be. `error` is why a text is not one, or nothing when it is. */
enum SettingType:
  /** A number between the bounds, the lower one excluded when `minExclusive` */
  case Decimal(min: Double, max: Double = Double.MaxValue, minExclusive: Boolean = false)

  /** A whole number between the bounds */
  case Integer(min: Int, max: Int = Int.MaxValue)
  case Flag
  case Text

  /** A tile URL template, with the three placeholders the map fills in */
  case TileUrl
  case HttpUrl

  /** The text as a number, when this is a numeric type and the text is one; the one parse every reading of a number goes through */
  def number(text: String): Option[Double] = this match
    case Decimal(_, _, _) => text.toDoubleOption.filter(_.isFinite)
    case Integer(_, _) => text.toIntOption.map(_.toDouble)
    case _ => None

  def error(text: String): Option[String] = this match
    case Decimal(min, max, minExclusive) =>
      number(text) match
        case None => Some("Must be a number")
        case Some(value) if value < min || (minExclusive && value == min) =>
          Some(if minExclusive then s"Must be above ${SettingType.bound(min)}" else s"Must be at least ${SettingType.bound(min)}")
        case Some(value) if value > max => Some(s"Must be at most ${SettingType.bound(max)}")
        case _ => None
    case Integer(min, max) =>
      number(text) match
        case None => Some("Must be a whole number")
        case Some(value) if value < min => Some(s"Must be at least $min")
        case Some(value) if value > max => Some(s"Must be at most $max")
        case _ => None
    case Flag => Option.unless(text == "true" || text == "false")("Must be true or false")
    case Text => None
    case TileUrl =>
      Option.unless(List("{z}", "{x}", "{y}").forall(text.contains))("Must contain {z}, {x} and {y}")
    case HttpUrl =>
      val uri = Try(URI.create(text)).toOption
      val scheme = uri.flatMap(uri => Option(uri.getScheme)).map(_.toLowerCase)
      Option.unless((scheme.contains("http") || scheme.contains("https")) && uri.exists(_.getHost != null))(
        "Must be an http or https URL")

  /** Whether two valid texts mean the same value: a number by its value, anything else by its text */
  def same(a: String, b: String): Boolean = this match
    case Decimal(_, _, _) | Integer(_, _) => number(a) == number(b)
    case _ => a == b

object SettingType:
  // A whole bound reads as one: "255", not "255.0"
  private def bound(value: Double): String = if value == value.toLong then value.toLong.toString else value.toString

/**
 * One Setting as the screen and the validation know it. `engine` names the one database engine it applies to, when it does not
 * apply to both. Its default is the `default_value` of its row.
 */
case class SettingDef(
    name: String,
    group: SettingGroup,
    label: String,
    help: String,
    valueType: SettingType,
    kind: SettingKind = SettingKind.Live,
    engine: Option[String] = None)

/**
 * A rule between two numeric Settings: `holds(value of field, value of other)`. Judged only when both pass their own rules, and
 * reported against `field`.
 */
case class CrossRule(field: String, other: String, holds: (Double, Double) => Boolean, message: String)

/**
 * The Settings an Admin tunes on the Settings screen, read from the `setting` table into one immutable typed snapshot that is
 * replaced whole on every load and save. The companion is the registry: every Setting's name, group, label, help, type and rules.
 * Both maps are keyed by name and hold valid text for every Setting of the registry.
 */
case class Settings(private val text: Map[String, String], private val defaults: Map[String, String]):
  import Settings.*

  /** The saved value of a Setting, as text */
  def value(name: String): String = text(name)

  /** The default of a Setting, as text */
  def default(name: String): String = defaults(name)

  private def number(name: String): Double = byName(name).valueType.number(text(name)).get

  val faceYunetConfidenceThreshold: Double = number(FACE_YUNET_CONFIDENCE_THRESHOLD)
  val faceYunetNmsThreshold: Double = number(FACE_YUNET_NMS_THRESHOLD)
  val faceDetectionBoundingBoxSize: Int = number(FACE_DETECTION_BOUNDING_BOX_SIZE).toInt
  val faceDetectionMinFaceSize: Int = number(FACE_DETECTION_MIN_FACE_SIZE).toInt
  val faceRecognitionCosineDistanceThreshold: Double = number(FACE_RECOGNITION_COSINE_DISTANCE_THRESHOLD)
  val faceRecognitionMatchCount: Int = number(FACE_RECOGNITION_MATCH_COUNT).toInt
  val faceRecognitionHnswEfSearch: Int = number(FACE_RECOGNITION_HNSW_EF_SEARCH).toInt
  val faceQualityEnrollThreshold: Double = number(FACE_QUALITY_ENROLL_THRESHOLD)
  val faceQualityKeepThreshold: Double = number(FACE_QUALITY_KEEP_THRESHOLD)
  val videoFacesSampleIntervalMs: Int = number(VIDEO_FACES_SAMPLE_INTERVAL_MS).toInt
  val videoFacesMaxSampledFrames: Int = number(VIDEO_FACES_MAX_SAMPLED_FRAMES).toInt
  val videoFacesMinClusterFrames: Int = number(VIDEO_FACES_MIN_CLUSTER_FRAMES).toInt
  val videoPreviewMinLuminance: Double = number(VIDEO_PREVIEW_MIN_LUMINANCE)
  val sequenceMaxGapMs: Int = number(SEQUENCE_MAX_GAP_MS).toInt
  val sequenceDinov2MinSimilarity: Double = number(SEQUENCE_DINOV2_MIN_SIMILARITY)
  val sequenceClipMinSimilarity: Double = number(SEQUENCE_CLIP_MIN_SIMILARITY)
  val sequenceMaxHashDistance: Int = number(SEQUENCE_MAX_HASH_DISTANCE).toInt

  /** The least cosine similarity of two frames' embeddings of `model` to look alike */
  def sequenceMinSimilarity(model: SceneModel): Double = model match
    case SceneModel.Dinov2 => sequenceDinov2MinSimilarity
    case SceneModel.Clip => sequenceClipMinSimilarity
  val curationBlurRatio: Double = number(CURATION_BLUR_RATIO)
  val curationFaceQualityGap: Double = number(CURATION_FACE_QUALITY_GAP)
  val curationClippedFraction: Double = number(CURATION_CLIPPED_FRACTION)
  val curationCrushedFraction: Double = number(CURATION_CRUSHED_FRACTION)
  val curationDarkLuminance: Double = number(CURATION_DARK_LUMINANCE)
  val curationBrightLuminance: Double = number(CURATION_BRIGHT_LUMINANCE)
  val curationLuminanceGap: Double = number(CURATION_LUMINANCE_GAP)
  val mapTileUrl: String = text(MAP_TILE_URL)
  val mapTileAttribution: String = text(MAP_TILE_ATTRIBUTION)
  val mapGeocoderEnabled: Boolean = text(MAP_GEOCODER_ENABLED) == "true"
  val mapGeocoderUrl: String = text(MAP_GEOCODER_URL)

  /** Seconds */
  val dbPostgresReadStatementTimeout: Int = number(DB_POSTGRES_READ_STATEMENT_TIMEOUT).toInt

  /** Seconds */
  val pipelineShutdownTimeout: Int = number(PIPELINE_SHUTDOWN_TIMEOUT).toInt

  /** 0 means what the machine affords, see `util.ImportParallelism` */
  val importParallelism: Int = number(IMPORT_PARALLELISM).toInt

object Settings:
  val FACE_YUNET_CONFIDENCE_THRESHOLD = "face.yunet.confidence_threshold"
  val FACE_YUNET_NMS_THRESHOLD = "face.yunet.nms_threshold"
  val FACE_DETECTION_BOUNDING_BOX_SIZE = "face.detection.bounding_box_size"
  val FACE_DETECTION_MIN_FACE_SIZE = "face.detection.min_face_size"
  val FACE_RECOGNITION_COSINE_DISTANCE_THRESHOLD = "face.recognition.cosine_distance_threshold"
  val FACE_RECOGNITION_MATCH_COUNT = "face.recognition.match_count"
  val FACE_RECOGNITION_HNSW_EF_SEARCH = "face.recognition.hnsw_ef_search"
  val FACE_QUALITY_ENROLL_THRESHOLD = "face.quality.enroll_threshold"
  val FACE_QUALITY_KEEP_THRESHOLD = "face.quality.keep_threshold"
  val VIDEO_FACES_SAMPLE_INTERVAL_MS = "video.faces.sample_interval_ms"
  val VIDEO_FACES_MAX_SAMPLED_FRAMES = "video.faces.max_sampled_frames"
  val VIDEO_FACES_MIN_CLUSTER_FRAMES = "video.faces.min_cluster_frames"
  val VIDEO_PREVIEW_MIN_LUMINANCE = "video.preview.min_luminance"
  val SEQUENCE_MAX_GAP_MS = "sequence.max_gap_ms"
  val SEQUENCE_DINOV2_MIN_SIMILARITY = "sequence.dinov2.min_similarity"
  val SEQUENCE_CLIP_MIN_SIMILARITY = "sequence.clip.min_similarity"
  val SEQUENCE_MAX_HASH_DISTANCE = "sequence.max_hash_distance"
  val CURATION_BLUR_RATIO = "curation.blur_ratio"
  val CURATION_FACE_QUALITY_GAP = "curation.face_quality_gap"
  val CURATION_CLIPPED_FRACTION = "curation.clipped_fraction"
  val CURATION_CRUSHED_FRACTION = "curation.crushed_fraction"
  val CURATION_DARK_LUMINANCE = "curation.dark_luminance"
  val CURATION_BRIGHT_LUMINANCE = "curation.bright_luminance"
  val CURATION_LUMINANCE_GAP = "curation.luminance_gap"
  val MAP_TILE_URL = "map.tile.url"
  val MAP_TILE_ATTRIBUTION = "map.tile.attribution"
  val MAP_GEOCODER_ENABLED = "map.geocoder.enabled"
  val MAP_GEOCODER_URL = "map.geocoder.url"
  val DB_POSTGRES_READ_STATEMENT_TIMEOUT = "db.postgres.read_statement_timeout"
  val PIPELINE_SHUTDOWN_TIMEOUT = "pipeline.shutdown_timeout"
  val IMPORT_PARALLELISM = "import.parallelism"

  import SettingGroup.*
  import SettingType.*

  private val postgresOnly = Some(Const.DbEngineName.POSTGRES)

  // The four absolute exposure Settings say the same thing about a problem no Pick can fix
  private val everyFrameNotice = "When every frame of a Curation set is, that is a notice on the set and not a Bundle."

  val all: List[SettingDef] = List(
    SettingDef(
      FACE_YUNET_CONFIDENCE_THRESHOLD,
      FaceDetection,
      "Detector confidence threshold",
      "The detector's score floor. A non-face rarely scores 0.85, a sharp face 0.91 to 0.95, a blurred one 0.80 to 0.90.",
      Decimal(0, 1, minExclusive = true)
    ),
    SettingDef(
      FACE_YUNET_NMS_THRESHOLD,
      FaceDetection,
      "Detector NMS threshold",
      "Non-maximum suppression, OpenCV's YuNet default. Tighter, and one of two cheek-to-cheek faces is suppressed as a " +
        "duplicate.",
      Decimal(0, 1, minExclusive = true)
    ),
    SettingDef(
      FACE_DETECTION_BOUNDING_BOX_SIZE,
      FaceDetection,
      "Detection box size",
      "Detection runs on the image shrunk to fit this square, so it decides how small a face in a 12 to 48 MP photo can be " +
        "found. At least the smallest face.",
      Integer(1)
    ),
    SettingDef(
      FACE_DETECTION_MIN_FACE_SIZE,
      FaceDetection,
      "Smallest face",
      "The smallest face box, in original pixels.",
      Integer(1)),
    SettingDef(
      FACE_RECOGNITION_COSINE_DISTANCE_THRESHOLD,
      FaceRecognition,
      "Cosine distance threshold",
      "Two detections closer than this are the same person, both in the vector index and within one Video.",
      Decimal(0, 1, minExclusive = true)
    ),
    SettingDef(
      FACE_RECOGNITION_MATCH_COUNT,
      FaceRecognition,
      "Match count",
      "The nearest candidates that vote; a tie goes to the closest.",
      Integer(1)),
    SettingDef(
      FACE_RECOGNITION_HNSW_EF_SEARCH,
      FaceRecognition,
      "Vector index search width",
      "How many candidates a search of the vector index keeps as it goes (pgvector's hnsw.ef_search). Higher finds more of " +
        "the true nearest Faces and reads more of the index. At least four times the match count.",
      Integer(4),
      engine = postgresOnly
    ),
    SettingDef(
      FACE_QUALITY_ENROLL_THRESHOLD,
      FaceRecognition,
      "Enroll threshold",
      "Face quality is the L2 norm of the raw ArcFace embedding (higher is more recognizable). A Face at or above this is " +
        "enrolled: it can start a new Person and is a match candidate. Calibrated on the test portraits: sharp faces 18.8 " +
        "to 22.4, the same faces blurred at 5% of their width 15.1 to 19.6, at 8% 14.3 to 17.3, eyes covered 15.9 to 19.5. " +
        "At least the keep threshold. Faces already stored keep their tier.",
      Decimal(0, minExclusive = true)
    ),
    SettingDef(
      FACE_QUALITY_KEEP_THRESHOLD,
      FaceRecognition,
      "Keep threshold",
      "A Face between this and the enroll threshold is match-only: it may join a known Person but never starts one and is " +
        "not a candidate. Below it, the Face is dropped. Faces already stored keep their tier.",
      Decimal(0, minExclusive = true)
    ),
    SettingDef(
      VIDEO_FACES_SAMPLE_INTERVAL_MS,
      Video,
      "Sample interval (milliseconds)",
      "The Faces and the Preview of a Video come from its Sampled frames: evenly spaced from the start at whichever is " +
        "longer, this interval or the duration divided by the maximum count, so a short clip is sampled every second and a " +
        "two-hour recording about every minute.",
      Integer(1)
    ),
    SettingDef(
      VIDEO_FACES_MAX_SAMPLED_FRAMES,
      Video,
      "Maximum sampled frames",
      "The most Sampled frames a Video has. At least the minimum cluster frames.",
      Integer(1)
    ),
    SettingDef(
      VIDEO_FACES_MIN_CLUSTER_FRAMES,
      Video,
      "Minimum cluster frames",
      "A person seen in fewer Sampled frames than this is match-only in that Video: the Face may join a known Person but " +
        "cannot start one.",
      Integer(1)
    ),
    SettingDef(
      VIDEO_PREVIEW_MIN_LUMINANCE,
      Video,
      "Preview minimum brightness",
      "The Preview is the first Sampled frame whose mean brightness (0 to 255) clears this floor, which skips a black leader " +
        "or a fade-in; when no frame does, the frame at a tenth of the duration.",
      Decimal(0, 255)
    ),
    SettingDef(
      SEQUENCE_MAX_GAP_MS,
      Triage,
      "Sequence gap (milliseconds)",
      "A Sequence is frames of one scene shot within this long of each other on one camera, looking alike: a minute, so a " +
        "scene with its pauses to reframe is one Sequence, not only a burst of a few seconds.",
      Integer(1)
    ),
    SettingDef(
      SEQUENCE_DINOV2_MIN_SIMILARITY,
      Triage,
      "Sequence DINOv2 similarity",
      "The least cosine similarity, -1 to 1, of two frames' DINOv2 Scene embeddings to look alike. Higher groups more " +
        "strictly. Two frames are one Sequence when either Scene model finds them alike, so lowering one threshold groups more " +
        "freely whatever the other says. DINOv2's similarities spread lower than CLIP's, so the two thresholds are on " +
        "different scales. A starting point to calibrate on real files.",
      Decimal(-1, 1)
    ),
    SettingDef(
      SEQUENCE_CLIP_MIN_SIMILARITY,
      Triage,
      "Sequence CLIP similarity",
      "The least cosine similarity, -1 to 1, of two frames' CLIP Scene embeddings to look alike. Higher groups more strictly. " +
        "Two frames are one Sequence when either Scene model finds them alike, so lowering one threshold groups more freely " +
        "whatever the other says. CLIP's similarities sit closer to 1 than DINOv2's, so the two thresholds are on different " +
        "scales. A starting point to calibrate on real files.",
      Decimal(-1, 1)
    ),
    SettingDef(
      SEQUENCE_MAX_HASH_DISTANCE,
      Triage,
      "Sequence hash distance (bits)",
      s"How far apart two frames' perceptual hashes may be, 0 to ${PerceptualHash.BITS} bits, to still look alike. Decides " +
        "only two frames no Scene model embedded both of (a still image the models could not embed); otherwise the " +
        "similarities do. Higher groups more freely.",
      Integer(0, PerceptualHash.BITS)
    ),
    SettingDef(
      CURATION_BLUR_RATIO,
      Triage,
      "Curation blur ratio",
      "A frame is Blurry when a Person it shares with another frame of the Curation set, at a similar size, has under this " +
        "fraction of that Person's best Face sharpness. A frame with no such Person, in a Sequence, is compared on its whole " +
        "frame's sharpness against the Sequence's sharpest frame.",
      Decimal(0, 1)
    ),
    SettingDef(
      CURATION_FACE_QUALITY_GAP,
      Triage,
      "Curation face quality gap",
      "A frame has Poor faces when one of its Faces' quality is at least this far below the same Person's best in the " +
        "Curation set. On the scale of the enroll threshold.",
      Decimal(0)
    ),
    SettingDef(
      CURATION_CLIPPED_FRACTION,
      Triage,
      "Curation clipped highlights",
      s"A frame is Too bright when at least this fraction of its pixels are clipped highlights. $everyFrameNotice",
      Decimal(0, 1)
    ),
    SettingDef(
      CURATION_CRUSHED_FRACTION,
      Triage,
      "Curation crushed shadows",
      s"A frame is Too dark when at least this fraction of its pixels are crushed shadows. $everyFrameNotice",
      Decimal(0, 1)
    ),
    SettingDef(
      CURATION_DARK_LUMINANCE,
      Triage,
      "Curation dark limit",
      s"A frame is Too dark when its mean brightness, 0 to 1, is under this. $everyFrameNotice",
      Decimal(0, 1)
    ),
    SettingDef(
      CURATION_BRIGHT_LUMINANCE,
      Triage,
      "Curation bright limit",
      s"A frame is Too bright when its mean brightness, 0 to 1, is over this. $everyFrameNotice",
      Decimal(0, 1)
    ),
    SettingDef(
      CURATION_LUMINANCE_GAP,
      Triage,
      "Curation brightness gap",
      "In a Sequence, a frame is Too dark or Too bright when its mean brightness, 0 to 1, is at least this much farther from " +
        s"the target of ${ImageMeasurement.TARGET_LUMINANCE} than the Sequence's frame nearest the target.",
      Decimal(0, 1)
    ),
    SettingDef(
      MAP_TILE_URL,
      Maps,
      "Tile URL",
      "Every tile the browser loads tells the tile host which part of the world you are looking at; the default is the " +
        "public OpenStreetMap raster service (see https://operations.osmfoundation.org/policies/tiles/). Point the template " +
        "at a self-hosted tile server to keep that traffic on your own network. {z}, {x} and {y} are filled in by the map.",
      TileUrl
    ),
    SettingDef(MAP_TILE_ATTRIBUTION, Maps, "Tile attribution", "Shown on the map, as HTML. May be empty.", Text),
    SettingDef(
      MAP_GEOCODER_ENABLED,
      Maps,
      "Place-name search",
      "Place-name search in the Add Location dialog. Off by default: every query is sent, through this server, to the " +
        "geocoder below.",
      Flag
    ),
    SettingDef(
      MAP_GEOCODER_URL,
      Maps,
      "Geocoder URL",
      "A Nominatim-compatible endpoint (see https://operations.osmfoundation.org/policies/nominatim/ for the public one).",
      HttpUrl
    ),
    SettingDef(
      DB_POSTGRES_READ_STATEMENT_TIMEOUT,
      Operations,
      "Read statement timeout (seconds)",
      "A read statement running longer is cancelled, and the search answers 503 instead of holding a backend.",
      Integer(1),
      engine = postgresOnly
    ),
    SettingDef(
      PIPELINE_SHUTDOWN_TIMEOUT,
      Operations,
      "Pipeline shutdown timeout (seconds)",
      "At shutdown, the import and the purge queues are each given this long to finish what they have already accepted, " +
        "before the database closes. Whatever the limit cuts off is handled at the next startup: an unfinished import is " +
        "discarded (its upload is lost), and a purge is queued again.",
      Integer(1)
    ),
    SettingDef(
      IMPORT_PARALLELISM,
      Operations,
      "Import parallelism",
      "How many threads do the import's work: metadata, face detection and recognition, image measurement, previews and the " +
        "database writes, for every library at once. Each thread takes up to about 512 MB of native memory at its worst, " +
        "for its own face detector and the decode of a large image in flight, and the threads share one face recognition " +
        "network of about 370 MB and the two Scene models of about 1 GB together, so this bounds memory as well as CPU. 0 " +
        "means half the cores or as many threads as " +
        "half the machine's memory affords, whichever is fewer, and never fewer than 2. import.parallelism.max in the server " +
        "config caps this, whatever is set here.",
      Integer(0),
      kind = SettingKind.Restart
    )
  )

  val byName: Map[String, SettingDef] = all.map(d => d.name -> d).toMap

  private val crossRules: List[CrossRule] = List(
    CrossRule(FACE_DETECTION_BOUNDING_BOX_SIZE, FACE_DETECTION_MIN_FACE_SIZE, _ >= _, "Must be at least the smallest face"),
    CrossRule(
      FACE_RECOGNITION_HNSW_EF_SEARCH,
      FACE_RECOGNITION_MATCH_COUNT,
      (width, count) => width >= 4 * count,
      "Must be at least four times the match count"),
    CrossRule(FACE_QUALITY_ENROLL_THRESHOLD, FACE_QUALITY_KEEP_THRESHOLD, _ >= _, "Must be at least the keep threshold"),
    CrossRule(
      VIDEO_FACES_MAX_SAMPLED_FRAMES,
      VIDEO_FACES_MIN_CLUSTER_FRAMES,
      _ >= _,
      "Must be at least the minimum cluster frames")
  )

  /**
   * Why each of `values` is not valid, by name; empty when the whole set is. A name the registry does not know is an error, and a
   * cross-field rule is judged only when both of its fields pass their own and its field applies to `engine`: a Setting of the
   * other engine keeps a stored value nobody can see or change.
   */
  def validate(values: Map[String, String], engine: String): Map[String, String] =
    val own: Map[String, String] = values.flatMap {
      (name, text) =>
        byName.get(name) match
          case None => Some(name -> "Unknown setting")
          case Some(definition) => definition.valueType.error(text).map(name -> _)
    }
    val cross: Map[String, String] = crossRules
      .filter(rule => byName(rule.field).engine.forall(_ == engine))
      .flatMap {
        rule =>
          (values.get(rule.field), values.get(rule.other)) match
            case (Some(field), Some(other)) if !own.contains(rule.field) && !own.contains(rule.other) =>
              val holds =
                rule.holds(byName(rule.field).valueType.number(field).get, byName(rule.other).valueType.number(other).get)
              Option.unless(holds)(rule.field -> rule.message)
            case _ => None
      }
      .toMap
    own ++ cross
