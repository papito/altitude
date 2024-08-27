package software.altitude.core

object Const {
  /**
   * CONFIGURATION
   */
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

  /**
   * MESSAGES
   */
  object Msg {

    object Err {
      val REQUIRED = "This field is required"
      val CANNOT_BE_EMPTY = "Cannot be empty"
      val VALUE_TOO_LONG = "Value is longer than %s characters"
      val VALUE_TOO_SHORT = "Value should be at least %s characters long"
      val VALIDATION_ERROR = "Validation error"
      val VALIDATION_ERRORS = "There are validation errors in: %s"
      val EMPTY_REQUEST_BODY = "Empty request body"
      val DUPLICATE = "Duplicate"
      val INCORRECT_VALUE_TYPE = "Incorrect value type"
      val PASSWORDS_DO_NOT_MATCH = "Passwords do not match"
      val INVALID_CONTENT_TYPE = "Invalid content type"
    }
  }

  /**
   * API
   */
  object Api {
    val USER_ID = "userId"
    val REPO_ID = "repoId"
    val PERSON_ID = "personId"
    val SRC_PERSON_ID = "srcPersonId"
    val DEST_PERSON_ID = "destPersonId"
    val ERROR = "error"
    val STACKTRACE = "stacktrace"
    val VALIDATION_ERROR = "validationError"
    val VALIDATION_ERRORS = "validationErrors"
    val MULTI_VALUE_DELIM = "+"
    val USER_TEST_HEADER_ID = "TEST-user-id"
    val REPO_TEST_HEADER_ID = "TEST-repo-id"

    val TOTAL_RECORDS = "totalRecords"
    val TOTAL_PAGES = "totalPages"
    val CURRENT_PAGE = "currentPage"
    val RESULTS_PER_PAGE = "resultsPerPage"

    val ID = "id"
    val DATA = "data"
    val PATH = "path"
    val DIRECTORIES = "directories"
    val FILES = "files"
    val CURRENT_PATH = "currentPath"
    val CURRENT_PATH_BREADCRUMBS = "pathBreadcrumbs"
    val PATH_POS = "pos"
    val CHILD_DIR = "dir"

    object Asset {
      val ASSET = "asset"
      val FOLDER_ID = "folderId"
      val ASSETS = "assets"
      val METADATA = "metadata"
      val METADATA_FIELD_ID = "metadataFieldId"
      val METADATA_VALUE_ID = "metadataValueId"
    }

    object Search {
      val ASSETS = "assets"
      val QUERY_TEXT = "q"
      val RESULTS_PER_PAGE = "rpp"
      val PAGE = "p"
      val FOLDERS = "folders"
      val SORT = "sort"
      val DEFAULT_RPP = 100
    }

    object Sort {
      val DIRECTION = "direction"
    }

    object SearchSort {
      val DIRECTION = "direction"
      val FIELD = "field"
    }

    object Folder {
      val FOLDER = "folder"
      val HIERARCHY = "hierarchy"
      val FOLDERS = "folders"
      val ASSET_IDS = "assetIds"
      val PATH = "path"
      val NAME = "name"
      val EXISTING_NAME = "existingName"
      val PARENT_ID = "parentId"
      val MOVED_FOLDER_ID = "movedFolderId"
      val NEW_PARENT_ID = "newParentId"
    }

    object Person {
      val NAME = "name"
      val MERGE_SOURCE_ID = "mergeSourceId"
      val MERGE_DEST_ID = "mergeDestId"
      val MERGE_SOURCE_PERSON = "mergeSourcePerson"
      val MERGE_DEST_PERSON = "mergeDestPerson"
    }

    object Trash {
      val ASSET_IDS = "assetIds"
    }

    object Stats {
      val STATS = "stats"
    }

    object Metadata {
      val FIELDS = "fields"
      val VALUE = "value"

      object Field {
        val NAME = "name"
        val TYPE = "type"
      }
    }

    object Setup {
      val ADMIN_EMAIL = "adminEmail"
      val ADMIN_NAME = "adminName"
      val REPOSITORY_NAME = "repositoryName"
      val PASSWORD = "password"
      val PASSWORD2 = "password2"
    }

    object Upload {
      val UPLOAD_ID = "uploadId"
    }

    object Constraints {
      val MAX_EMAIL_LENGTH = 80
      val MIN_EMAIL_LENGTH = 3

      val MAX_NAME_LENGTH = 80
      val MIN_NAME_LENGTH = 2

      val MAX_PASSWORD_LENGTH = 50
      val MIN_PASSWORD_LENGTH = 8

      val MAX_REPOSITORY_NAME_LENGTH = 80
      val MIN_REPOSITORY_NAME_LENGTH = 2

      val MAX_FOLDER_NAME_LENGTH = 250
      val MIN_FOLDER_NAME_LENGTH = 1
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
