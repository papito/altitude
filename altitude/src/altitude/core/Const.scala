package altitude.core

object Const:

  /** CONFIGURATION */
  object Conf:
    val TEST_DIR = "test.dir"
    val FS_DATA_DIR = "fs.data.dir"
    val DEFAULT_STORAGE_ENGINE = "storage.engine.default"
    val DB_ENGINE = "db.engine"
    val POSTGRES_USER = "db.postgres.user"
    val POSTGRES_PASSWORD = "db.postgres.password"
    val POSTGRES_URL = "db.postgres.url"
    // The connection pools and what every connection opens with, see reference.conf
    val POSTGRES_POOL_SIZE = "db.postgres.pool_size"
    val POSTGRES_OPTIONS = "db.postgres.options"
    val POSTGRES_READ_STATEMENT_TIMEOUT = "db.postgres.read_statement_timeout"
    val REL_SQLITE_DB_PATH = "db.sqlite.rel_db_path"
    val SQLITE_URL = "db.sqlite.url"
    val SQLITE_READ_POOL_SIZE = "db.sqlite.read_pool_size"

    val FACE_YUNET_CONFIDENCE_THRESHOLD = "face.yunet.confidence_threshold"
    val FACE_YUNET_NMS_THRESHOLD = "face.yunet.nms_threshold"
    val FACE_DETECTION_BOUNDING_BOX_SIZE = "face.detection.bounding_box_size"
    val FACE_DETECTION_MIN_FACE_SIZE = "face.detection.min_face_size"
    val FACE_RECOGNITION_COSINE_DISTANCE_THRESHOLD = "face.recognition.cosine_distance_threshold"
    val FACE_RECOGNITION_MATCH_COUNT = "face.recognition.match_count"
    // The two tiers of Face quality, see reference.conf
    val FACE_QUALITY_ENROLL_THRESHOLD = "face.quality.enroll_threshold"
    val FACE_QUALITY_KEEP_THRESHOLD = "face.quality.keep_threshold"
    val FACE_DEBUG_ENABLED = "face.debug.enabled"

    // How a Video is sampled for its Faces and its Preview, see reference.conf
    val VIDEO_FACES_SAMPLE_INTERVAL_MS = "video.faces.sample_interval_ms"
    val VIDEO_FACES_MAX_SAMPLED_FRAMES = "video.faces.max_sampled_frames"
    val VIDEO_FACES_MIN_CLUSTER_FRAMES = "video.faces.min_cluster_frames"
    val VIDEO_PREVIEW_MIN_LUMINANCE = "video.preview.min_luminance"

    // The map's basemap and the place-name search behind the Add Location dialog; both talk to third parties, see reference.conf
    val MAP_TILE_URL = "map.tile.url"
    val MAP_TILE_ATTRIBUTION = "map.tile.attribution"
    val MAP_GEOCODER_ENABLED = "map.geocoder.enabled"
    val MAP_GEOCODER_URL = "map.geocoder.url"

    // DEV-only convenience: if both are defined, requests requiring auth will auto-login.
    val DEV_USER = "dev.user"
    val DEV_PASSWORD = "dev.password"

  object FaceRecognition:
    val MIN_FACES_THRESHOLD = 3

  object AssetView:
    val PREVIEW_BOX_PIXELS = 200

  object DbEngineName:
    val SQLITE = "sqlite"
    val POSTGRES = "postgres"

  object StorageEngineName:
    val FS = "fs"

  object Search:
    val DEFAULT_RPP = 50
    // Every grid page is bounded: a flat page holds full asset rows, and a grouped one carries a count for each of its groups
    val MAX_RPP = 500

    // A first page's total counts the matches up to this many; one past it reads "10000+"
    val TOTAL_CAP = 10000

    // A group of the Search text with at most this many hits is answered from those hits; one with more is matched over the
    // whole library
    val TEXT_PROBE_LIMIT = 5000

    // The asset columns the results UI offers as a sort; a request may sort by nothing else but Relevance
    val SORT_FIELDS: Set[String] = Set(
      FieldConst.Asset.ORIGINAL_CREATED_AT,
      FieldConst.CREATED_AT,
      FieldConst.Asset.FILENAME,
      FieldConst.Asset.SIZE_BYTES,
      FieldConst.Asset.AREA_SIZE)

    // The sort that is not a column: how well an asset matches the Search text, best first. It is the whole `sort` value,
    // with no direction digit.
    val SORT_RELEVANCE = "relevance"

    object Layout:
      val GRID = "grid"
      val MAP = "map"

    object View:
      // Matches `Const.views.repository` in static/js/constants.js - the client sends this verbatim
      val DEFAULT = "repository"
      val TRIAGE = "triage"
      val TRASHBIN = "trashbin"

  object Security:
    val MEMBER_ME_COOKIE_EXPIRATION_DAYS = 7

  object DataStore:
    val CONTENT = "content"
    val PREVIEW = "preview"
    val FILE = "file"
    val FILES = "files"
    val FACE = "face"
    val FACES = "faces"
    val REPOSITORIES = "repositories"
    val STAGING = "staging"
    val MODELS = "models"

  object PeopleTypeFilter:
    val ALL = "all"
    val HIDDEN = "hidden"
    val COMPLETE = "complete"
    val INCOMPLETE = "incomplete"

  /** MESSAGES */
  object Msg:

    object Err:
      val VALUE_REQUIRED = "This field is required"
      val VALUE_CANNOT_BE_EMPTY = "Cannot be empty"
      val VALUE_TOO_LONG = "Value is longer than %s characters"
      val VALUE_TOO_SHORT = "Value should be at least %s characters long"
      val VALUE_NOT_AN_EMAIL = "This is a clown email"
      val VALUE_NOT_A_UUID = "This is not a valid UUID"
      val VALIDATION_ERROR = "Validation error"
      val VALIDATION_ERRORS = "There are validation errors in: %s"
      val EMPTY_REQUEST_BODY = "Empty request body"
      val DUPLICATE = "Duplicate"
      val INCORRECT_VALUE_TYPE = "Incorrect value type"
      val PASSWORDS_DO_NOT_MATCH = "Passwords do not match"
      val INVALID_CONTENT_TYPE = "Invalid content type"
      val PIN_REQUIRED = "Place the pin on the map"
      val SEARCH_TIMED_OUT = "The search took too long"

  // Dialog titles: the folder and album rename/delete dialogs show inline in the entity's menu, the
  // people dialogs in the modal host, whose sizing is owned by CSS (`--modal-content-width` in
  // core.css). The add dialogs have no title: their one field's placeholder says what they do, and
  // the inline view-settings dialog has none either: its checkbox labels are the whole content.
  // The Location dialogs are titled by kind: the controller picks the Category or Location constant.
  object UI:
    val RENAME_FOLDER_DIALOG_TITLE = "Rename folder"
    val DELETE_FOLDER_DIALOG_TITLE = "Delete folder"
    val RENAME_ALBUM_DIALOG_TITLE = "Rename album"
    val DELETE_ALBUM_DIALOG_TITLE = "Delete album"
    val ADD_LOCATION_DIALOG_TITLE = "Add location"
    val RENAME_LOCATION_DIALOG_TITLE = "Rename location"
    val DELETE_LOCATION_DIALOG_TITLE = "Delete location"
    val RENAME_CATEGORY_DIALOG_TITLE = "Rename category"
    val DELETE_CATEGORY_DIALOG_TITLE = "Delete category"
    val MOVE_LOCATION_DIALOG_TITLE = "Move to category"
    val ADD_TO_LOCATION_DIALOG_TITLE = "Add to location"
    val MERGE_PEOPLE_DIALOG_TITLE = "Merge people"
    val CHANGE_PERSON_COVER_IMAGE_DIALOG_TITLE = "Change cover image"
