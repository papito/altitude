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

    // Quick view (htmx/quick_view_modal) shows a folder's first this many assets, by newest import
    val QUICK_VIEW_SIZE = 100

    // A combobox of the advanced search panel is offered at most this many options for the text typed so far
    val MAX_FILTER_OPTIONS = 50

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
    val USERS = "users"
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

  /** The two panes of the People tab, as the browser names them (`Const.people.panes` in constants.js): Main and Duplicate */
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
      // A name within its length whose name key passes NameKey.MAX_KEY_BYTES: compatibility characters NFKC spells out at length
      val NAME_KEY_TOO_LONG = "Value is too long: some of its characters stand for several"
      val VALUE_NOT_AN_EMAIL = "Enter a valid email address"
      val VALUE_NOT_A_UUID = "This is not a valid UUID"
      val VALIDATION_ERROR = "Validation error"
      val VALIDATION_ERRORS = "There are validation errors in: %s"
      val EMPTY_REQUEST_BODY = "Empty request body"
      val DUPLICATE = "Duplicate"
      val INCORRECT_VALUE_TYPE = "Incorrect value type"
      val PASSWORDS_DO_NOT_MATCH = "Passwords do not match"
      // The username rule (User.validUsername); the bounds are Api.Constraints.MIN_/MAX_USERNAME_LENGTH
      val USERNAME_RULE = "Use 3 to 32 letters, digits, dots, underscores or hyphens"
      // A grant past the number of Libraries a login token has room for (RepositoryService.MAX_GRANTS_PER_ACCOUNT)
      val TOO_MANY_GRANTS = "An account can be given access to at most %s Libraries"
      // The account rules of UserService's writes: a User or a Guest sees at least one Library; an address or a username another
      // account holds; a password outside Api.Constraints.MIN_/MAX_PASSWORD_LENGTH; the current password of a change, wrong
      val LIBRARY_REQUIRED = "Choose at least one Library"
      val ACCOUNT_TYPE_REQUIRED = "Choose an account type"
      val EMAIL_TAKEN = "Another account has this email address"
      val USERNAME_TAKEN = "Another account has this username"
      val PASSWORD_LENGTH = "Use %s to %s characters"
      val WRONG_PASSWORD = "This is not your current password"
      // A refused login, whatever refused it (an unknown login, a wrong password, a deactivated account), which it never says
      val LOGIN_FAILED = "Wrong email, username or password"
      // An Admin's edit saved over an account changed since its form was opened (UserService.update): another Admin's edit, a
      // grant a new Library gave it
      val ACCOUNT_CHANGED = "This account was changed since you opened it. Reopen it to see the change."
      // A file whose importing account is gone or deactivated when the import inserts it (LibraryService.persistAndIndex)
      val IMPORTER_GONE = "The account that imported this file no longer exists"
      val IMPORTER_DEACTIVATED = "The account that imported this file is deactivated"
      // An uploaded file's relative path the upload routes refuse (RelPath.segments)
      val PATH_EMPTY_NAME = "The path has an empty name"
      val PATH_PARENT = "The path holds \"..\""
      val PATH_IGNORED = "\"%s\" is a name imports ignore"
      // An uploaded file with no content, which imports ignore
      val EMPTY_FILE = "Empty files are not imported"
      // A file sent to, or a close of, an upload's Import by another account than the one that opened it, or of an Import
      // that is no upload (ImportService.ownUpload)
      val FORBIDDEN_UPLOAD = "an upload takes files from the account that opened it"
      // A destination folder an Import cannot sort its files into (ImportService.open, FolderService.ensurePath)
      val DESTINATION_IN_TRASH = "The destination folder is in the Trash"
      // A file of an Import below a folder name the folder rule refuses, with the rule's own words
      val DESTINATION_FOLDER_NAME = "A folder of the path cannot be created: %s"
      // That reason for a folder whose name another folder there holds by the time it is added
      val FOLDER_NAME_TAKEN = "its name is taken"
      // An Admin's own account, which it cannot deactivate, delete or take the Admin type from
      val OWN_ACCOUNT = "You cannot deactivate, delete or demote your own account"
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
      // A rename a unique index refused with no live holder of the name to offer: merged away meanwhile, or another constraint;
      // and a name for a Face's Person (AssetActionController.nameFacePerson) that another live Person holds
      val PERSON_NAME_ALREADY_EXISTS = "Person by that name already exists"
      // The Library name rule (Repository.validName), shared by setup and the New library dialog; the bounds are
      // Api.Constraints.MIN_/MAX_REPOSITORY_NAME_LENGTH
      val LIBRARY_NAME_REQUIRED = "Enter a name for the library"
      val LIBRARY_NAME_TOO_SHORT = "Use at least %s characters"
      val LIBRARY_NAME_TOO_LONG = "Use at most %s characters"
      val LIBRARY_NAME_CONTROL_CHARACTERS = "Remove the control characters"
      // A Library name whose name key another Library holds (repository_01), named as it was submitted, once normalized
      val LIBRARY_NAME_TAKEN = "A library named “%s” already exists"
      // `/` for an account that can see no Library to land in
      val NO_LIBRARY = "There is no library to open"
      // A page of a Library that does not exist or that the account cannot see (decorators.requireLogin): one text for both
      val LIBRARY_NOT_FOUND = "Library not found"
      // Why a route refused an account with 403 (decorators.forbidden: "Forbidden: " and this in plain text, this alone in
      // JSON): a Permission it does not hold in the Library (requirePermission), naming the Permissions any one of which would do;
      // a Guest, who sees Browse alone (requireMember, decorators.refusedView); an account without the Create Libraries flag
      // (requireLibraryCreator); an account that is no Admin (requireAdmin)
      val FORBIDDEN_PERMISSION = "needs the %s Permission"
      val FORBIDDEN_ADMIN = "Admin only"
      val FORBIDDEN_GUEST = "not for a Guest"
      val FORBIDDEN_LIBRARY_CREATOR = "creating a Library needs the Create Libraries flag"
      // An account that holds neither Create Users nor Create Guests in any Library (requireAccountCreator)
      val FORBIDDEN_ACCOUNT_CREATOR = "needs the Create Users or Create Guests Permission in a Library"
      // An account a creator who is no Admin may not give: an Admin, the Create Libraries flag or the new-Library grant, a
      // Library where it holds no Create Users (for a User) or Create Guests (for a Guest), or a Permission it does not hold there
      // An account deactivated since its login, whose write is refused
      val FORBIDDEN_INACTIVE = "the account is deactivated"
      val FORBIDDEN_CEILING = "an account can be given only the Libraries and Permissions its creator may pass on"
      // An operation refused for assets the account's Permissions do not reach (AssetRules): the action and how many
      val FORBIDDEN_ASSETS = "You do not hold the Permission to %s %d of these assets"
      // A Face correction onto the Face's own Person: a move (PersonService.moveFace), or a merge of the Face's Person into
      // themselves (mergeFacePerson)
      val FACE_ALREADY_THEIRS = "The face is already this person's"
      // A Face correction's body naming neither a person nor a name, or both
      val FACE_TARGET_REQUIRED = "Name one person, by ID or by name"
      // A Face correction of a Face on an asset in the trash, which the panel shows read-only
      val FACE_OF_RECYCLED_ASSET = "The faces of an asset in the trash cannot be changed"
      // A name or a merge of a Face's Person as a whole (PersonService.nameFacePerson, mergeFacePerson), offered only for a
      // Person with no name, who has been named since the panel was shown
      val FACE_PERSON_NAMED_SINCE = "This person has a name now"
      // A merge of a Face's Person into a Person with no name, which the panel never offers
      val MERGE_INTO_NAMELESS = "Merge into a person with a name"
      // A cover (PersonService.setFaceAsCover) that is another Person's Face, or a Face on an asset marked for purging, whose
      // Faces are about to go
      val COVER_FACE_NOT_THEIRS = "The face is not this person's"
      val COVER_FACE_PURGING = "The face is on an asset being purged"
      // A folder added under a parent folder that is no folder of the repository (FolderActionController.htmxAddFolder)
      val PARENT_FOLDER_NOT_FOUND = "The parent folder does not exist"

    // What a Face correction did, named for the snackbar in the People section's data attributes (htmx/detail_panel_people)
    object FaceCorrection:
      val MOVED = "Moved to %s"
      val REMOVED = "Removed %s"
      val PERSON_REMOVED = "%s had no other faces and was removed"
      // The Face's Person named, by the name they had and the new one
      val NAMED = "%s is now %s"
      // The Face's Person merged, by the name they had and the name of the Person they were merged into
      val MERGED = "%s merged into %s"

  // Dialog titles: the folder and album rename/delete dialogs show inline in the entity's menu, the
  // people dialogs in the modal host, whose sizing is owned by CSS (`--modal-content-width` in
  // core.css). The add dialogs have no title: their one field's placeholder says what they do, and
  // the inline view-settings dialog has none either: its checkbox labels are the whole content.
  // The Location dialogs are titled by kind: the controller picks the Category or Location constant. The scope dialog of
  // Triage's explorer is titled by the name of the folder, Person or Location it describes.
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
    val RENAME_PERSON_DIALOG_TITLE = "Rename person"
    val MERGE_PEOPLE_DIALOG_TITLE = "Merge people"
    // The offer of the name editor and of the rename dialog to merge the renamed Person into the one holding the name, which
    // opens the dialog above
    val MERGE_INTO_PERSON = "Merge into %s…"
    val CHANGE_PERSON_COVER_IMAGE_DIALOG_TITLE = "Change cover image"
    val QUICK_VIEW_DIALOG_TITLE = "Quick view"
    val NEW_LIBRARY_DIALOG_TITLE = "New library"
    // The nav's Library menu's last entry, for an Admin, which opens the New library dialog
    val NEW_LIBRARY_OPTION = "New library…"
    // The Users page's dialogs (htmx/account_form, set_password_dialog, delete_account_dialog), and the own password dialog of
    // the account info dialog (htmx/own_password_dialog); an edit and the two account dialogs are titled by the account's name
    val ADD_USER_DIALOG_TITLE = "Add User"
    val EDIT_USER_DIALOG_TITLE = "Edit %s"
    val SET_PASSWORD_DIALOG_TITLE = "Set the password of %s"
    val DELETE_USER_DIALOG_TITLE = "Delete %s"
    val CHANGE_PASSWORD_DIALOG_TITLE = "Change password"
    val ACCOUNT_INFO_DIALOG_TITLE = "Your account"
    // The warning the folder, album and Location tabs open with for an account without Organize, which has nothing to add there:
    // "folders", "albums" or "Locations"
    val CANNOT_ORGANIZE = "You cannot create %s as you do not have the Permission to organize this Library"
