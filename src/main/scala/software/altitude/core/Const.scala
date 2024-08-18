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

  /**
   * MODELS
   */
  object SystemMetadata {
    val VERSION = "version"
    val IS_INITIALIZED = "is_initialized"
  }

  trait Common {
    val ID = "id"
    val REPO_ID = "repository_id"
    val USER_ID = "user_id"
    val VALUE = "value"
    val VALUES = "values"
    val CHECKSUM = "checksum"
    val DATA = "data"
    val CREATED_AT = "created_at"
    val UPDATED_AT = "updated_at"
  }

  object Base extends Common

  object Repository extends Common {
    val NAME = "name"
    val OWNER_ACCOUNT_ID = "owner_account_id"
    val ROOT_FOLDER_ID = "root_folder_id"
    val FILE_STORE_TYPE = "file_store_type"
    val FILES_STORE_CONFIG = "file_store_config"

    object Config {
    }
  }

  object User extends Common {
    val EMAIL = "email"
    val NAME = "name"
    val PASSWORD_HASH = "password_hash"
    val ACCOUNT_TYPE = "account_type"
    val LAST_ACTIVE_REPO_ID = "last_active_repo_id"
  }

  object UserToken {
    val ACCOUNT_ID = "account_id"
    val TOKEN = "token"
    val EXPIRES_AT = "expires_at"
  }

  object Face extends Common {
    val X1 = "x1"
    val Y1 = "y1"
    val WIDTH = "width"
    val HEIGHT = "height"
    val ASSET_ID = "asset_id"
    val PERSON_ID = "person_id"
    val PERSON_LABEL = "person_label"
    val DETECTION_SCORE = "detection_score"
    val EMBEDDINGS = "embeddings"
    val FEATURES = "features"
    val IMAGE = "image"
    val DISPLAY_IMAGE = "display_image"
    val ALIGNED_IMAGE = "aligned_image"
    val ALIGNED_IMAGE_GS = "aligned_image_gs"
  }

  object Person extends Common {
    val LABEL = "label"
    val NAME = "name"
    val COVER_FACE_ID = "cover_face_id"
    val MERGED_WITH_IDS = "merged_with_ids"
    val NUM_OF_FACES = "num_of_faces"
    val MERGED_INTO_ID = "merged_into_id"
    val MERGED_INTO_LABEL = "merged_into_label"
    val IS_HIDDEN = "is_hidden"
    val FACES = "faces"
  }

  object Asset extends Common {
    val ASSET_TYPE = "asset_type"
    val FOLDER_ID = "folder_id"
    val SIZE_BYTES = "size_bytes"
    val FILENAME = "filename"
    val METADATA = "metadata"
    val EXTRACTED_METADATA = "extracted_metadata"
    val IS_RECYCLED = "is_recycled"
    val IS_TRIAGED = "is_triaged"
  }

  object AssetType extends Common {
    val MIME_TYPE = "mime_type"
    val MEDIA_TYPE = "media_type"
    val MEDIA_SUBTYPE = "media_subtype"
  }

  object Folder extends Common {
    val NAME = "name"
    val NAME_LC = "name_lc"
    val PARENT_ID = "parent_id"
    val NUM_OF_ASSETS = "num_of_assets"
    val NUM_OF_CHILDREN = "num_of_children"
    val CHILDREN = "children"
    val IS_RECYCLED = "is_recycled"

    object Name {
      val ROOT = "root"
    }
    object Alias {
      val ROOT = "root"
    }
  }

  object MimedData {
    val MIME_TYPE = "mime_type"
    val DATA = "data"
    val ASSET_ID = "asset_id"
  }

  object Stat extends Common {
    val DIMENSION = "dimension"
    val DIM_VAL = "dim_val"
  }

  object MetadataField extends Common {
    val NAME = "name"
    val NAME_LC = "name_lc"
    val FIELD_TYPE = "field_type"
    val FIELD = "field"
  }

  object SearchToken extends Common {
    val ASSET_ID = "asset_id"
    val FIELD_ID = "field_id"
    val FIELD_VALUE_KW = "field_value_kw"
    val FIELD_VALUE_NUM = "field_value_num"
    val FIELD_VALUE_BOOL = "field_value_bool"
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
    val USER_ID = "user_id"
    val ERROR = "error"
    val STACKTRACE = "stacktrace"
    val VALIDATION_ERROR = "validation_error"
    val VALIDATION_ERRORS = "validation_errors"
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
    val CURRENT_PATH = "current_path"
    val CURRENT_PATH_BREADCRUMBS = "path_breadcrumbs"
    val PATH_POS = "pos"
    val CHILD_DIR = "dir"

    object Asset {
      val ASSET = "asset"
      val FOLDER_ID = "folder_id"
      val ASSETS = "assets"
      val METADATA = "metadata"
      val METADATA_FIELD_ID = "metadata_field_id"
      val METADATA_VALUE_ID = "metadata_value_id"
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
      val ASSET_IDS = "asset_ids"
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
