package software.altitude.core

object Const {

  /** CONFIGURATION */
  object Conf {
    val TEST_DIR = "test.dir"
    val FS_DATA_DIR = "fs.data.dir"
    val DEFAULT_STORAGE_ENGINE = "storage.engine.default"
    val PREVIEW_BOX_PIXELS = "preview.box.pixels"
    val DB_ENGINE = "db.engine"
    val POSTGRES_USER = "db.postgres.user"
    val POSTGRES_PASSWORD = "db.postgres.password"
    val POSTGRES_URL = "db.postgres.url"
    val REL_SQLITE_DB_PATH = "db.sqlite.rel_db_path"
    val SQLITE_URL = "db.sqlite.url"
  }

  object DbEngineName extends Enumeration {
    type DbEngineName = String
    val SQLITE = "sqlite"
    val POSTGRES = "postgres"
  }

  object StorageEngineName extends Enumeration {
    type StorageEngineName = String
    val FS = "fs"
  }

  object Search {
    val DEFAULT_RPP = 50
  }

  object Security {
    val MEMBER_ME_COOKIE_EXPIRATION_DAYS = 7
  }

  object DataStore {
    val CONTENT = "content"
    val PREVIEW = "preview"
    val FILE = "file"
    val FILES = "files"
    val FACE = "face"
    val FACES = "faces"
    val REPOSITORIES = "repositories"
    val MODELS = "models"
  }

  /** MESSAGES */
  object Msg {

    object Err {
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
    }
  }

  object UI {
    private val DEFAULT_MODAL_WIDTH = 400

    val ADD_FOLDER_MODAL_TITLE = "Add a folder"
    val ADD_FOLDER_MODAL_MIN_WIDTH: Int = DEFAULT_MODAL_WIDTH

    val RENAME_FOLDER_MODAL_TITLE = "Rename folder"
    val RENAME_FOLDER_MODAL_MIN_WIDTH: Int = DEFAULT_MODAL_WIDTH

    val DELETE_FOLDER_MODAL_TITLE = "Delete folder"
    val DELETE_FOLDER_MODAL_MIN_WIDTH: Int = DEFAULT_MODAL_WIDTH

    val MERGE_PEOPLE_MODAL_TITLE = "Merge people"
    val MERGE_PEOPLE_MODAL_MIN_WIDTH: Int = DEFAULT_MODAL_WIDTH
  }
}
