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
    val REL_SQLITE_DB_PATH = "db.sqlite.rel_db_path"
    val SQLITE_URL = "db.sqlite.url"
    val SQLITE_READ_POOL_SIZE = "db.sqlite.read_pool_size"
    val DB_POOL_LEAK_DETECTION_THRESHOLD = "db.pool.leak_detection_threshold"

    // Dumps detection debug images into the project debug/ folder. The tunable face, video, map and pipeline values are Settings
    // (`models.Settings`), not config.
    val FACE_DEBUG_ENABLED = "face.debug.enabled"

    // Caps the `import.parallelism` Setting, for an operator who ships a config but never logs in. See `util.ImportParallelism`.
    val IMPORT_PARALLELISM_MAX = "import.parallelism.max"

    // DEV-only convenience: if both are defined, requests requiring auth will auto-login.
    val DEV_USER = "dev.user"
    val DEV_PASSWORD = "dev.password"
    // DEV-only: explain each new query once, into sql-debug.log
    val DEV_SQL_EXPLAIN = "dev.sql_explain"
    // DEV-only: a pipeline queue whose stream fails restarts it after a backoff
    val DEV_RESTART_PIPELINE = "dev.restart_pipeline"

  object FaceRecognition:
    val MIN_FACES_THRESHOLD = 3

  object Sequence:
    // The most undated frames one undated import is compared with, per CPU core, newest imports first (see
    // `Altitude.undatedSequenceCandidates`): a candidate costs about 6 KB to read, its two Scene embeddings, so a core's share is
    // about 30 MB per undated import, and the compare itself is two dot products a candidate. Undated frames have no capture time
    // to window them by, so without the cap the cost of one import would grow with every undated frame triage holds.
    val UNDATED_CANDIDATES_PER_CORE = 5000

  object Curation:
    // A Curation set compares: with fewer frames left there is no set, and a Sequence is not in the queue
    val MIN_FRAMES = 2
    // The most IDs a selection opens Curation on: every frame's Faces and memberships are read for one fragment
    val MAX_SELECTION = 100
    // The most frames of a Sequence a set reads, the first in capture order, for the same cost: Sequences chain transitively, so
    // a static 1 s time-lapse is one Sequence of however many frames it has
    val MAX_SEQUENCE_FRAMES = 100

  object AssetView:
    val PREVIEW_BOX_PIXELS = 200
    // The WebP quality of a Preview, 0 to 100, which governs its color planes only: lib webp keeps the alpha plane exact (its
    // alpha quality is a setting of its own, 100 unless lowered), so transparent padding stays transparent
    val PREVIEW_WEBP_QUALITY = 80

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

  /**
   * The entries of the shared nav (`includes/nav`), by the key a page marks its own entry with (`current`): the main page's three
   * views, then a page each.
   */
  object NavEntry:
    val BROWSE: String = Search.View.DEFAULT
    val TRIAGE: String = Search.View.TRIAGE
    val TRASH: String = Search.View.TRASHBIN
    val IMPORT = "import"
    val SETTINGS = "settings"
    val STYLE_GUIDE = "style-guide"

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

  /** The People buckets of the People tab; a listing is the union of the buckets asked for (see docs/faces.md, People) */
  object PeopleBucket:
    val MATCHED = "matched"
    val UNMATCHED = "unmatched"
    val HIDDEN = "hidden"
    val ALL: Set[String] = Set(MATCHED, UNMATCHED, HIDDEN)

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
      // A JSON route's body that is not JSON, or not a JSON object
      val MALFORMED_JSON = "The request body is not a JSON object"
      val PIN_REQUIRED = "Place the pin on the map"
      val SEARCH_TIMED_OUT = "The search took too long"
      // An unnamed Person's own form of name, which no Person may be given (PersonService.updateName)
      val RESERVED_PERSON_NAME = "Unknown N names are reserved"
      // A rename to a name another live Person holds, named as that Person's name is stored (htmx/edit_person_name)
      val PERSON_NAME_TAKEN = "%s already exists."
      val PERSON_NAME_TAKEN_BY_HIDDEN = "%s already exists and is hidden."
      // A rename a unique index refused with no live holder of the name to offer: merged away meanwhile, or another constraint
      val PERSON_NAME_ALREADY_EXISTS = "Person by that name already exists"
      // A Face correction (PersonService.moveFace) onto the Face's own Person
      val FACE_ALREADY_THEIRS = "The face is already this person's"
      // A Face correction's body naming neither a person nor a name, or both
      val FACE_TARGET_REQUIRED = "Name one person, by ID or by name"
      // A Face correction of a Face on an asset in the trash, which the panel shows read-only
      val FACE_OF_RECYCLED_ASSET = "The faces of an asset in the trash cannot be changed"

    // What a Face correction did, named for the snackbar in the People section's data attributes (htmx/detail_panel_people)
    object FaceCorrection:
      val MOVED = "Moved to %s"
      val REMOVED = "Removed %s"
      val PERSON_REMOVED = "%s had no other faces and was removed"

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
    val ADD_TO_ALBUM_DIALOG_TITLE = "Add to album"
    // The Add to album dialog's last option, which reveals the name field of the album it creates
    val NEW_ALBUM_OPTION = "New album…"
    val MERGE_PEOPLE_DIALOG_TITLE = "Merge people"
    // The name editor's offer to merge the renamed Person into the one holding the name, which opens the dialog above
    val MERGE_INTO_PERSON = "Merge into %s…"
    val CHANGE_PERSON_COVER_IMAGE_DIALOG_TITLE = "Change cover image"
