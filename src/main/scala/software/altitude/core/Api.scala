package software.altitude.core

object Api {

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

  object Modal {
    val MIN_WIDTH = "minWidth"
    val TITLE = "title"
    val FIELD_ERRORS = "fieldErrors"
    val FORM_JSON = "formJson"
  }

  object Field {
    val USER_ID = "userId"
    val ASSET_ID = "assetId"
    val REPO_ID = "repoId"
    val FOLDER_ID = "folderId"
    val PERSON_ID = "personId"
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

    object ALBUM {
      val ALBUMS = "albums"
    }

    object Asset {
      val ASSET = "asset"
      val FOLDER_ID = "folderId"
      val ASSETS = "assets"
      val METADATA = "metadata"
      val METADATA_FIELD_ID = "metadataFieldId"
      val METADATA_VALUE_ID = "metadataValueId"
    }

    object Folder {
      val FOLDER = "folder"
      val FOLDERS = "folders"
      val HIERARCHY = "hierarchy"
      val ASSET_IDS = "assetIds"
      val PATH = "path"
      val NAME = "name"
      val EXISTING_NAME = "existingName"
      val PARENT_ID = "parentId"
      val MOVED_FOLDER_ID = "movedFolderId"
      val NEW_PARENT_ID = "newParentId"
      val ROOT_FOLDER = "rootFolder"
    }

    object Metadata {
      val FIELDS = "fields"
      val VALUE = "value"

      object Field {
        val NAME = "name"
        val TYPE = "type"
      }
    }

    object Person {
      val PERSON  = "person"
      val PEOPLE  = "people"
      val NAME = "name"
      val NEW_NAME = "newName"
      val MERGE_SOURCE_ID = "mergeSourceId"
      val MERGE_DEST_ID = "mergeDestId"
      val MERGE_SOURCE_PERSON = "mergeSourcePerson"
      val MERGE_DEST_PERSON = "mergeDestPerson"
      val SRC_PERSON_ID = "srcPersonId"
      val DEST_PERSON_ID = "destPersonId"
    }

    object Search {
      val ASSETS = "assets"
      val QUERY_TEXT = "q"
      val RESULTS_PER_PAGE = "rpp"
      val RESULTS = "results"
      val PAGE = "p"
      val FOLDERS = "folders"
      val SORT = "sort"
    }

    object SearchSort {
      val DIRECTION = "direction"
      val FIELD = "field"
    }

    object Setup {
      val ADMIN_EMAIL = "adminEmail"
      val ADMIN_NAME = "adminName"
      val REPOSITORY_NAME = "repositoryName"
      val PASSWORD = "password"
      val PASSWORD2 = "password2"
    }

    object Sort {
      val DIRECTION = "direction"
    }

    object Stats {
      val STATS = "stats"
    }

    object Trash {
      val ASSET_IDS = "assetIds"
    }

    object Upload {
      val UPLOAD_ID = "uploadId"
    }
  }
}
