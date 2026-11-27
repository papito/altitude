package altitude.core

object Const:

  /** CONFIGURATION */
  object Conf:
    val TEST_DIR = "test.dir"
    // The address the server binds, and whether the session cookie is `Secure`
    val SERVER_HOST = "server.host"
    val SERVER_PORT = "server.port"
    val SERVER_SECURE_COOKIES = "server.secure_cookies"
    val FS_DATA_DIR = "fs.data.dir"
    val DEFAULT_STORAGE_ENGINE = "storage.engine.default"
    val DB_ENGINE = "db.engine"
    val POSTGRES_USER = "db.postgres.user"
    val POSTGRES_PASSWORD = "db.postgres.password"
    val POSTGRES_URL = "db.postgres.url"
    // The connection pools and what every connection opens with
    val POSTGRES_POOL_SIZE = "db.postgres.pool_size"
    val POSTGRES_OPTIONS = "db.postgres.options"
    val REL_SQLITE_DB_PATH = "db.sqlite.rel_db_path"
    val SQLITE_URL = "db.sqlite.url"
    val SQLITE_READ_POOL_SIZE = "db.sqlite.read_pool_size"
    val DB_POOL_LEAK_DETECTION_THRESHOLD = "db.pool.leak_detection_threshold"

    // Dumps detection debug images into the project debug/ folder. Tunable values are Settings (`models.Settings`), not config.
    val FACE_DEBUG_ENABLED = "face.debug.enabled"

    // Caps the `import.parallelism` Setting, for an operator who ships a config but never logs in (`util.ImportParallelism`)
    val IMPORT_PARALLELISM_MAX = "import.parallelism.max"

    // The root of the model files; empty is the per-user cache
    val MODELS_DIR = "models.dir"

    // DEV-only: when both are set, unauthenticated requests log in as this user
    val DEV_USER = "dev.user"
    val DEV_PASSWORD = "dev.password"
    // DEV-only: explain each new query once, into sql-debug.log
    val DEV_SQL_EXPLAIN = "dev.sql_explain"
    // DEV-only: a pipeline queue whose stream fails restarts it after a backoff
    val DEV_RESTART_PIPELINE = "dev.restart_pipeline"

  object FaceRecognition:
    val MIN_FACES_THRESHOLD = 3

  object Sequence:
    // The most undated frames an undated import is compared with, per core, newest first. Undated frames have no capture time to
    // window by, so without a cap one import's cost grows with every undated frame in Triage. A candidate is ~6 KB to read (two
    // Scene embeddings), ~30 MB per core per import, and two dot products to compare.
    val UNDATED_CANDIDATES_PER_CORE = 5000

  object Curation:
    // With fewer frames left there is no Curation set, and a Sequence is not in the queue
    val MIN_FRAMES = 2
    // The most IDs a selection opens Curation on: one fragment reads every frame's Faces and memberships
    val MAX_SELECTION = 100
    // The most frames of a Sequence a set reads, first in capture order, for the same cost: Sequences chain transitively, so a
    // static 1 s time-lapse is one Sequence of any length
    val MAX_SEQUENCE_FRAMES = 100

  object Edit:
    // The Display image's longest edge, at most; a smaller edited image keeps its own size
    val DISPLAY_LONG_EDGE = 3840
    // The Display image's WebP quality, 0 to 100
    val DISPLAY_WEBP_QUALITY = 90
    // An Edit's render is queued this long after its last save; each save restarts the wait
    val RENDER_DEBOUNCE_MS = 1500

  object AssetView:
    val PREVIEW_BOX_PIXELS = 200
    // 0 to 100, for the color planes only: libwebp's alpha quality is separate (100 by default), so transparent padding stays
    // transparent
    val PREVIEW_WEBP_QUALITY = 80

  object DbEngineName:
    val SQLITE = "sqlite"
    val POSTGRES = "postgres"

  object StorageEngineName:
    val FS = "fs"

  object Search:
    val DEFAULT_RPP = 50
    // A flat page holds full asset rows, and a grouped one a count per group
    val MAX_RPP = 500

    // A first page's total counts matches up to this many, past which it shows as a lower bound. It also bounds the Describe
    // result limit Setting, so a Describe search's total is always exact
    val TOTAL_CAP = 10000

    // A Search text group with at most this many hits is answered from them; one with more is matched over the whole library
    val TEXT_PROBE_LIMIT = 5000

    // The asset columns a request may sort by, besides the non-column sorts below
    val SORT_FIELDS: Set[String] = Set(
      FieldConst.Asset.ORIGINAL_CREATED_AT,
      FieldConst.CREATED_AT,
      FieldConst.Asset.FILENAME,
      FieldConst.Asset.SIZE_BYTES,
      FieldConst.Asset.AREA_SIZE)

    // How well an asset matches the Search text, best first. The whole `sort` value, with no direction digit.
    val SORT_RELEVANCE = "relevance"

    // How much a still image looks like the description, most alike first: the whole `sort` value, a Describe search's
    // default, and refused without a description
    val SORT_SIMILARITY = "similarity"

    val MAX_DESCRIPTION_LENGTH = 300

    // Up to this many matches, a Describe search on PostgreSQL ranks each embedding exactly; past it, it ranks through the
    // vector index with its filters applied during the scan. An unmeasured estimate for a million embedded assets: an index
    // visit costs ~14 µs (a full scan ~14 s), and filling 1,000 results from filters leaving n assets visits ~10^9 / n rows over
    // a ~0.2 s base; an exact rank costs ~5 µs an embedding. They cross near 75,000, ~0.4 s each. The deciding count, which
    // reads up to one past this many matches, is not measured either.
    val DESCRIBE_EXACT_LIMIT = 75000

    // Quick view shows a folder's first this many assets, newest import first
    val QUICK_VIEW_SIZE = 100

    // The most options an advanced search combobox is offered for the text typed so far
    val MAX_FILTER_OPTIONS = 50

    object Layout:
      val GRID = "grid"
      val MAP = "map"

    object View:
      // Matches `Const.views.repository` in static/js/constants.js; the client sends it verbatim
      val DEFAULT = "repository"
      val TRIAGE = "triage"
      val TRASHBIN = "trashbin"

  // The Import history and an expanded Import's files: how many each shows at first and adds per step, the most each shows,
  // and the IDs of an Import's elements, which the status feed and the page's script address
  object ImportHistory:
    val IMPORTS_STEP = 20
    val MAX_IMPORTS = 500
    val FILES_STEP = 50
    val MAX_FILES = 500

    def statusId(importId: String): String = s"importStatus-$importId"
    def titleId(importId: String): String = s"importTitle-$importId"
    def filesId(importId: String): String = s"importFiles-$importId"
    def fileListId(importId: String): String = s"importFileList-$importId"

  object WatchFolder:
    // How many times in all a file whose import fails is read before it is parked
    val MAX_ATTEMPTS = 3
    // The days a file absent from its directory stays remembered when the Import history is kept forever
    // (`import.history.days` at 0); otherwise it stays as long as an Import does
    val FORGET_AFTER_DAYS = 30
    // A scan reads a file only when an earlier scan, at least this many seconds before, saw the same size and modified time.
    // A shorter rest would take a file a slow copy is still writing.
    val REST_SECONDS = 5

    // Why a Watch folder is paused, as its card shows it
    val DESTINATION_LOST = "destination folder is in the Trash"
    val OWNER_DEACTIVATED = "its owner was deactivated"
    val OWNER_DEMOTED = "its owner is no longer an Admin"
    // Why a Watch folder's directory is unavailable, as its card shows it
    val PATH_MISSING = "the path does not exist"
    val PATH_NOT_DIRECTORY = "the path is not a directory"
    val PATH_UNREADABLE = "the path is not readable"
    val PATH_UNWRITABLE = "the path is not writable, so imported files cannot be deleted"
    val PATH_OVERLAPS_DATA = "the path overlaps the app's data directory"
    val PATH_HOLDS_DATA = "the path holds a directory of the app's own data: %s"
    // The directories a scan could not read and went past: the one, or how many and the first
    val DIRECTORY_UNREADABLE = "a directory could not be read, the rest is scanned: %s"
    val DIRECTORIES_UNREADABLE = "%d directories could not be read, the rest is scanned; the first: %s"
    val PATH_OVERLAPS = "the path overlaps the Watch folder \"%s\""
    // Why a file of a Watch folder that could not be read failed
    val UNREADABLE_FILE = "Cannot read the file"

    def menuButtonId(watchFolderId: String): String = s"watchFolderMenuBtn-$watchFolderId"
    def menuId(watchFolderId: String): String = s"watchFolderMenu-$watchFolderId"

  /** The shared nav's entries, by the key a page marks its own entry with: the main page's three views, then a page each */
  object NavEntry:
    val BROWSE: String = Search.View.DEFAULT
    val TRIAGE: String = Search.View.TRIAGE
    val TRASH: String = Search.View.TRASHBIN
    val IMPORT = "import"
    val SETTINGS = "settings"
    val USERS = "users"
    val STYLE_GUIDE = "style-guide"

  /**
   * The MDC keys of a request's log lines. The logback patterns read them (`%X{REQUEST_ID}`, `%X{USER}`) and the JSON format
   * writes them in its `mdc` object.
   */
  object LogContext:
    /** A random ID per request, which `decorators.requestResponseLogger` sets */
    val REQUEST_ID = "REQUEST_ID"

    /** The ID of the request's account, never its name, username or email address: `UserService.switchContextToUser` sets it */
    val USER = "USER"

  object Security:
    val MEMBER_ME_COOKIE_EXPIRATION_DAYS = 7

  object DataStore:
    val CONTENT = "content"
    val PREVIEW = "preview"
    // An edited asset's Display image, beside its Preview: a content type and a file store directory
    val DISPLAY = "display"
    val FILE = "file"
    val FILES = "files"
    val FACE = "face"
    val FACES = "faces"
    val REPOSITORIES = "repositories"
    val STAGING = "staging"
    val MODELS = "models"

  /** The People tab's buckets; a listing is the union of the buckets asked for */
  object PeopleBucket:
    val MATCHED = "matched"
    val UNMATCHED = "unmatched"
    val HIDDEN = "hidden"
    val ALL: Set[String] = Set(MATCHED, UNMATCHED, HIDDEN)

  /** The People tab's two panes, as the browser names them (`Const.people.panes` in constants.js) */
  object PeoplePane:
    val MAIN = "main"
    val DUPLICATE = "duplicate"
    val ALL: Set[String] = Set(MAIN, DUPLICATE)

  /** MESSAGES */
  object Msg:

    object Err:
      val VALUE_REQUIRED = "This field is required"
      val VALUE_CANNOT_BE_EMPTY = "Cannot be empty"
      val VALUE_TOO_LONG = "Value is longer than %s characters"
      val VALUE_TOO_SHORT = "Value should be at least %s characters long"
      // A name within its length whose name key passes `NameKey.MAX_KEY_BYTES`: NFKC expands compatibility characters
      val NAME_KEY_TOO_LONG = "Value is too long: some of its characters stand for several"
      val VALUE_NOT_AN_EMAIL = "Enter a valid email address"
      val VALUE_NOT_A_UUID = "This is not a valid UUID"
      val VALIDATION_ERROR = "Validation error"
      val VALIDATION_ERRORS = "There are validation errors in: %s"
      val EMPTY_REQUEST_BODY = "Empty request body"
      val DUPLICATE = "Duplicate"
      val INCORRECT_VALUE_TYPE = "Incorrect value type"
      val PASSWORDS_DO_NOT_MATCH = "Passwords do not match"
      // A Setup submission to an instance set up already, refused by the server's flag or by the system row
      val ALREADY_INITIALIZED = "Instance is already initialized"
      // `User.validUsername`; the bounds are Api.Constraints.MIN_/MAX_USERNAME_LENGTH
      val USERNAME_RULE = "Use 3 to 32 letters, digits, dots, underscores or hyphens"
      // More grants than a login token has room for (`RepositoryService.MAX_GRANTS_PER_ACCOUNT`)
      val TOO_MANY_GRANTS = "An account can be given access to at most %s Libraries"
      // UserService's account rules
      val LIBRARY_REQUIRED = "Choose at least one Library"
      val ACCOUNT_TYPE_REQUIRED = "Choose an account type"
      val EMAIL_TAKEN = "Another account has this email address"
      val USERNAME_TAKEN = "Another account has this username"
      val PASSWORD_LENGTH = "Use %s to %s characters"
      val WRONG_PASSWORD = "This is not your current password"
      // Any refused login: it never says whether the login is unknown, the password wrong or the account deactivated
      val LOGIN_FAILED = "Wrong email, username or password"
      // An Admin's edit of an account changed since the form was opened, by another Admin or a new Library's grant
      val ACCOUNT_CHANGED = "This account was changed since you opened it. Reopen it to see the change."
      // A file whose importing account is gone or deactivated by the time the import inserts it
      val IMPORTER_GONE = "The account that imported this file no longer exists"
      val IMPORTER_DEACTIVATED = "The account that imported this file is deactivated"
      // An uploaded file's relative path the upload routes refuse (`RelPath.segments`)
      val PATH_EMPTY_NAME = "The path has an empty name"
      val PATH_PARENT = "The path holds \"..\""
      val PATH_IGNORED = "\"%s\" is a name imports ignore"
      val EMPTY_FILE = "Empty files are not imported"
      // A file sent to, or a close of, an upload's Import by an account other than its opener, or of an Import that is no upload
      val FORBIDDEN_UPLOAD = "an upload takes files from the account that opened it"
      val EXPECTED_FILES_INVALID = "expectedFiles must be a whole number of files, 0 or more"
      // A file dropped as a duplicate of a copy that is no longer in the Library when it settles: the copy's import failed, or
      // the asset was recycled meanwhile
      val COPY_NOT_IN_LIBRARY = "The copy it matched is not in the Library"
      // A restore or a move out of the Trash whose content an import committed meanwhile; tried again, the asset stays a duplicate
      val CONTENT_IMPORTED_MEANWHILE =
        "An asset with the same content was imported meanwhile, so nothing was taken out of the trash: try again"
      val DESTINATION_IN_TRASH = "The destination folder is in the Trash"
      // A file under a folder name the folder rule refuses, with the rule's own words
      val DESTINATION_FOLDER_NAME = "A folder of the path cannot be created: %s"
      // That reason for a folder whose name another folder took by the time it is added
      val FOLDER_NAME_TAKEN = "its name is taken"
      // A Watch folder path the dialog refuses, shown under the path's field
      val WATCH_PATH_INVALID = "This is not a path"
      val WATCH_PATH_RELATIVE = "Enter an absolute path, one that starts at the root of the file system"
      val WATCH_PATH_MISSING = "There is no such directory on the server"
      val WATCH_PATH_NOT_DIRECTORY = "This is not a directory"
      val WATCH_PATH_UNREADABLE = "The server cannot read this directory"
      val WATCH_PATH_UNWRITABLE = "The server cannot write to this directory, so it cannot delete imported files"
      val WATCH_PATH_OVERLAPS_DATA = "This path overlaps the app's data directory"
      // The path against another Watch folder's: the other's name and path, then its Library when it is another one's
      val WATCH_PATH_SAME = "This path is the Watch folder \"%s\"%s"
      val WATCH_PATH_INSIDE = "This path is inside the Watch folder \"%s\" (%s)%s"
      val WATCH_PATH_CONTAINS = "The Watch folder \"%s\" (%s) is inside this path%s"
      val WATCH_PATH_OTHER_LIBRARY = " of the Library \"%s\""
      // Keeping the folder structure with no folder chosen, in the Watch folder dialog and the upload sections
      val DESTINATION_REQUIRED = "Choose the folder to sort into"
      val WATCH_FOLDER_PAUSED = "The Watch folder is paused"
      val OWN_ACCOUNT = "You cannot deactivate, delete or demote your own account"
      val INVALID_CONTENT_TYPE = "Invalid content type"
      val MALFORMED_JSON = "The request body is not a JSON object"
      val PIN_REQUIRED = "Place the pin on the map"
      val SEARCH_TIMED_OUT = "The search took too long"
      // The form of an unnamed Person's name, which no Person may be given
      val RESERVED_PERSON_NAME = "Unknown N names are reserved"
      // A rename to a name another live Person holds, spelled as that Person's name is stored
      val PERSON_NAME_TAKEN = "%s already exists."
      val PERSON_NAME_TAKEN_BY_HIDDEN = "%s already exists and is hidden."
      // A rename a unique index refused with no live holder of the name to offer (merged away meanwhile, or another
      // constraint), and a name for a Face's Person that another live Person holds
      val PERSON_NAME_ALREADY_EXISTS = "Person by that name already exists"
      // `Repository.validName`, shared by setup and the New library dialog; the bounds are
      // Api.Constraints.MIN_/MAX_REPOSITORY_NAME_LENGTH
      val LIBRARY_NAME_REQUIRED = "Enter a name for the library"
      val LIBRARY_NAME_TOO_SHORT = "Use at least %s characters"
      val LIBRARY_NAME_TOO_LONG = "Use at most %s characters"
      val LIBRARY_NAME_CONTROL_CHARACTERS = "Remove the control characters"
      // A Library name whose name key another Library holds (`repository_01`), shown as submitted, once normalized
      val LIBRARY_NAME_TAKEN = "A library named “%s” already exists"
      // `/` for an account that sees no Library
      val NO_LIBRARY = "There is no library to open"
      // One text for a Library that does not exist and one the account cannot see
      val LIBRARY_NOT_FOUND = "Library not found"
      // Why a route refused an account with 403. FORBIDDEN_PERMISSION names the Permissions any one of which would do.
      val FORBIDDEN_PERMISSION = "needs the %s Permission"
      val FORBIDDEN_ADMIN = "Admin only"
      val FORBIDDEN_GUEST = "not for a Guest"
      val FORBIDDEN_LIBRARY_CREATOR = "creating a Library needs the Create Libraries flag"
      val FORBIDDEN_ACCOUNT_CREATOR = "needs the Create Users or Create Guests Permission in a Library"
      // An account deactivated since its login, whose write is refused
      val FORBIDDEN_INACTIVE = "the account is deactivated"
      // A non-Admin creator giving what it may not: the Admin type, the Create Libraries flag or the new-Library grant, a Library
      // where it holds no Create Users (for a User) or Create Guests (for a Guest), or a Permission it does not hold there
      val FORBIDDEN_CEILING = "an account can be given only the Libraries and Permissions its creator may pass on"
      // Assets the account's Permissions do not reach (`AssetRules`): the action and how many
      val FORBIDDEN_ASSETS = "You do not hold the Permission to %s %d of these assets"
      // A Face correction onto the Face's own Person: a move, or a merge of the Face's Person into themselves
      val FACE_ALREADY_THEIRS = "The face is already this person's"
      // A Face correction naming neither a person nor a name, or both
      val FACE_TARGET_REQUIRED = "Name one person, by ID or by name"
      // A Face correction on a trashed asset, whose Faces the panel shows read-only
      val FACE_OF_RECYCLED_ASSET = "The faces of an asset in the trash cannot be changed"
      // Naming or merging a Face's Person, offered only for an unnamed Person, who was named since the panel was shown
      val FACE_PERSON_NAMED_SINCE = "This person has a name now"
      // A merge of a Face's Person into a Person with no name, which the panel never offers
      val MERGE_INTO_NAMELESS = "Merge into a person with a name"
      // A cover that is another Person's Face, or a Face on an asset marked for purging, whose Faces are about to go
      val COVER_FACE_NOT_THEIRS = "The face is not this person's"
      val COVER_FACE_PURGING = "The face is on an asset being purged"
      val EDIT_NOT_STILL = "Only a still image can be edited"
      val EDIT_RECYCLED = "An asset in the trash cannot be edited"
      // The stored original is gone, so its upright size is unknown
      val ORIGINAL_MISSING = "The original file of this asset is missing"
      // A save built on an Edit version that has moved on, answered with the current state
      val EDIT_CONFLICT = "This photo was changed elsewhere"
      // A content URL's `v` (an edited image's revision) that is no whole number, or missing for a Display image
      val REVISION_NOT_A_NUMBER = "The revision must be a whole number"
      val REVISION_REQUIRED = "A Display image is asked for by its revision"
      // A save whose `base` is not a whole number from 0, or with no Edit
      val EDIT_WHOLE_NUMBER = "%s must be a whole number, 0 or more"
      val EDIT_RECIPE_REQUIRED = "recipe must be the Edit"
      // Auto-straighten with every slot taken, and of an original no image reader decodes
      val AUTO_STRAIGHTEN_BUSY = "Auto-straighten is busy with other photos; try again in a moment"
      val ORIGINAL_UNREADABLE = "The original file of this photo cannot be read"
      // A folder added under a parent that is not a folder of the repository
      val PARENT_FOLDER_NOT_FOUND = "The parent folder does not exist"
      // A folder added under a parent in the Trash, from a dialog opened before the delete
      val PARENT_FOLDER_IN_TRASH = "The parent folder is in the Trash"

    // What a Face correction did, for the snackbar
    object FaceCorrection:
      val MOVED = "Moved to %s"
      val REMOVED = "Removed %s"
      val PERSON_REMOVED = "%s had no other faces and was removed"
      // The old name, then the new one
      val NAMED = "%s is now %s"
      // The merged Person's name, then the name of the Person merged into
      val MERGED = "%s merged into %s"

  // Dialog titles. The add dialogs have none (their one field's placeholder says what they do), nor does the view-settings
  // dialog. Triage's scope dialog is titled by the folder, Person or Location it describes.
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
    // The Add to album dialog's last option, which reveals a new album's name field
    val NEW_ALBUM_OPTION = "New album…"
    val RENAME_PERSON_DIALOG_TITLE = "Rename person"
    val MERGE_PEOPLE_DIALOG_TITLE = "Merge people"
    // Offered by the name editor and the rename dialog: merge the renamed Person into the one holding the name
    val MERGE_INTO_PERSON = "Merge into %s…"
    val CHANGE_PERSON_COVER_IMAGE_DIALOG_TITLE = "Change cover image"
    val QUICK_VIEW_DIALOG_TITLE = "Quick view"
    val NEW_LIBRARY_DIALOG_TITLE = "New library"
    // The nav's Library menu's last entry, for an Admin
    val NEW_LIBRARY_OPTION = "New library…"
    // The account dialogs; an edit, a set password and a delete are titled by the account's name
    val ADD_USER_DIALOG_TITLE = "Add User"
    val EDIT_USER_DIALOG_TITLE = "Edit %s"
    val SET_PASSWORD_DIALOG_TITLE = "Set the password of %s"
    val DELETE_USER_DIALOG_TITLE = "Delete %s"
    val CHANGE_PASSWORD_DIALOG_TITLE = "Change password"
    val ACCOUNT_INFO_DIALOG_TITLE = "Your account"
    // An edit and a removal are titled by the Watch folder's name
    val ADD_WATCH_FOLDER_DIALOG_TITLE = "Add Watch folder"
    val EDIT_WATCH_FOLDER_DIALOG_TITLE = "Edit %s"
    val REMOVE_WATCH_FOLDER_DIALOG_TITLE = "Remove %s"
    // The folder, album and Location tabs' warning for an account without Organize: "folders", "albums" or "Locations"
    val CANNOT_ORGANIZE = "You cannot create %s as you do not have the Permission to organize this Library"
