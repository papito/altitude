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
    val REL_SQLITE_DB_PATH = "db.sqlite.rel_db_path"
    val SQLITE_URL = "db.sqlite.url"

    val FACE_YUNET_CONFIDENCE_THRESHOLD = "face.yunet.confidence_threshold"
    val FACE_YUNET_NMS_THRESHOLD = "face.yunet.nms_threshold"
    val FACE_DETECTION_BOUNDING_BOX_SIZE = "face.detection.bounding_box_size"
    val FACE_DETECTION_MIN_FACE_SIZE = "face.detection.min_face_size"
    val FACE_RECOGNITION_COSINE_DISTANCE_THRESHOLD = "face.recognition.cosine_distance_threshold"
    val FACE_RECOGNITION_MAX_COMPARISONS_PER_PERSON = "face.recognition.max_comparisons_per_person"
    val FACE_RECOGNITION_MATCH_COUNT = "face.recognition.match_count"
    val FACE_DEBUG_ENABLED = "face.debug.enabled"

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

  // Dialog titles: the folder and album rename/delete dialogs show inline in the entity's menu, the
  // people dialogs in the modal host, whose sizing is owned by CSS (`--modal-content-width` in
  // core.css). The add dialogs have no title: their one field's placeholder says what they do, and
  // the inline view-settings dialog has none either: its checkbox labels are the whole content.
  object UI:
    val RENAME_FOLDER_DIALOG_TITLE = "Rename folder"
    val DELETE_FOLDER_DIALOG_TITLE = "Delete folder"
    val RENAME_ALBUM_DIALOG_TITLE = "Rename album"
    val DELETE_ALBUM_DIALOG_TITLE = "Delete album"
    val MERGE_PEOPLE_DIALOG_TITLE = "Merge people"
    val CHANGE_PERSON_COVER_IMAGE_DIALOG_TITLE = "Change cover image"
