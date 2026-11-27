package altitude.core

object Api:

  object Constraints:
    val MAX_EMAIL_LENGTH = 80
    val MIN_EMAIL_LENGTH = 3

    val MAX_NAME_LENGTH = 80
    val MIN_NAME_LENGTH = 2

    val MAX_USERNAME_LENGTH = 32
    val MIN_USERNAME_LENGTH = 3

    val MAX_PASSWORD_LENGTH = 50
    val MIN_PASSWORD_LENGTH = 8

    val MAX_REPOSITORY_NAME_LENGTH = 80
    val MIN_REPOSITORY_NAME_LENGTH = 2

    val MAX_FOLDER_NAME_LENGTH = 250
    val MIN_FOLDER_NAME_LENGTH = 1

    val MAX_ALBUM_NAME_LENGTH = 250
    val MIN_ALBUM_NAME_LENGTH = 1

    val MAX_LOCATION_NAME_LENGTH = 250
    val MIN_LOCATION_NAME_LENGTH = 1

    val MAX_METADATA_FIELD_NAME_LENGTH = 250

    // An Import's name is an uploaded folder's, which no file system lets pass 255
    val MAX_IMPORT_NAME_LENGTH = 255
    val MAX_WATCH_FOLDER_NAME_LENGTH = 80

  object Field:
    val USER_ID = "userId"
    val ASSET_ID = "assetId"
    val ASSET_IDS = "assetIds"
    val REPO_ID = "repoId"
    val FOLDER_ID = "folderId"
    val PERSON_ID = "personId"
    val FACE_ID = "faceId"
    val ERROR = "error"
    val STACKTRACE = "stacktrace"
    val VALIDATION_ERROR = "validationError"
    val VALIDATION_ERRORS = "validationErrors"
    val MULTI_VALUE_DELIM = "+"
    val USER_TEST_HEADER_ID = "TEST-user-id"
    val REPO_TEST_HEADER_ID = "TEST-repo-id"
    // JSON a dialog's operation adds to its declared success detail (`BaseController.dialogSuccessResponse`)
    val SUCCESS_DETAIL_HEADER = "App-Success-Detail"

    val ID = "id"
    val DATA = "data"
    val PATH = "path"
    val DIRECTORIES = "directories"
    val FILES = "files"
    val CURRENT_PATH = "currentPath"
    val CURRENT_PATH_BREADCRUMBS = "pathBreadcrumbs"
    val PATH_POS = "pos"
    val CHILD_DIR = "dir"

    object Album:
      val ALBUM_ID = "albumId"
      val NAME = "name"

    object Library:
      val NAME = "name"

    // The account forms. `permissions` holds a "<Library ID>:<Permission>" per Permission given in a Library,
    // `newLibraryPermissions` the Permission names of the new-Library grant.
    object User:
      val ACCOUNT_ID = "accountId"
      val NAME = "name"
      val USERNAME = "username"
      val EMAIL = "email"
      val PASSWORD = "password"
      // The password typed again, which must match it
      val PASSWORD2 = "password2"
      val ACCOUNT_TYPE = "accountType"
      val LIBRARY_IDS = "libraryIds"
      val PERMISSIONS = "permissions"
      val CAN_CREATE_LIBRARIES = "canCreateLibraries"
      val GRANTS_NEW_LIBRARIES = "grantsNewLibraries"
      val NEW_LIBRARY_PERMISSIONS = "newLibraryPermissions"
      val CURRENT_PASSWORD = "currentPassword"
      val NEW_PASSWORD = "newPassword"
      val NEW_PASSWORD2 = "newPassword2"
      // The state an Admin's edit form was opened over (`UserService.editState`), which its save carries back
      val OPENED_STATE = "openedState"

    object Location:
      val LOCATION_ID = "locationId"
      val NAME = "name"
      val CATEGORY_ID = "categoryId"
      val LATITUDE = "latitude"
      val LONGITUDE = "longitude"

    object Asset:
      val ASSET = "asset"
      val FOLDER_ID = "folderId"
      val ASSETS = "assets"
      val METADATA = "metadata"
      val METADATA_FIELD_ID = "metadataFieldId"
      val METADATA_VALUE_ID = "metadataValueId"
      val RESTORED = "restored"
      val DUPLICATES = "duplicates"

    object Curation:
      val KEEP_IDS = "keepIds"
      val RECYCLE_IDS = "recycleIds"
      val SEQUENCE_OF = "sequenceOf"
      val COUNT = "count"

    // The Edit's JSON API: a save's body and the state it answers
    object Edit:
      // The edit version a save was built on
      val BASE = "base"
      val RECIPE = "recipe"
      // The count of the Edit's saves (not its format's `version`), and the one whose edited images are stored
      val EDIT_VERSION = "editVersion"
      val RENDERED_VERSION = "renderedVersion"
      // The revision of the edited images, their URLs' cache key
      val REV = "rev"
      val EDITED = "edited"
      val WIDTH = "width"
      val HEIGHT = "height"
      // The upright original's size: the space of the Edit's geometry and of its Faces' boxes
      val UPRIGHT_WIDTH = "uprightWidth"
      val UPRIGHT_HEIGHT = "uprightHeight"
      // A 409's current state, beside its error
      val STATE = "state"
      // Auto-straighten's answer: the Straighten angle that levels the image's lines
      val ANGLE = "angle"
      // The asset detail's Edit, whether the account may edit the asset, and, for a holder of Edit images, why it cannot
      val EDIT = "edit"
      val EDITABLE = "editable"
      val REFUSAL = "editRefusal"

    object Folder:
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

    object Metadata:
      val FIELDS = "fields"
      val VALUE = "value"

      object Field:
        val NAME = "name"
        val TYPE = "type"

    object Person:
      val PERSON = "person"
      val FACES = "faces"
      val PEOPLE = "people"
      val NAME = "name"
      val NEW_NAME = "newName"
      // The People tab pane a rename dialog was opened from, which its form sends back
      val PANE = "pane"
      val MERGE_SOURCE_ID = "mergeSourceId"
      val MERGE_DEST_ID = "mergeDestId"
      val MERGE_SOURCE_PERSON = "mergeSourcePerson"
      val MERGE_DEST_PERSON = "mergeDestPerson"
      // The Person a Face's Person is merged into
      val DEST_PERSON_ID = "destPersonId"

    object Search:
      val PERSON = "person"
      val QUERY_TEXT = "q"
      // The words of a Describe search
      val DESCRIBE = "describe"
      val RESULTS_PER_PAGE = "rpp"
      val RESULTS = "results"
      val FOLDER_ID = "folderId"
      val PERSON_ID = "personId"
      val ALBUM_ID = "albumId"
      val LOCATION_ID = "locationId"
      // How several people combine: `all` (the default) or `any`
      val PEOPLE_MATCH = "peopleMatch"
      // The Date Taken and Date Imported ranges, each end an ISO date
      val TAKEN_FROM = "takenFrom"
      val TAKEN_TO = "takenTo"
      val IMPORTED_FROM = "importedFrom"
      val IMPORTED_TO = "importedTo"
      val SUBSET = "subset"
      val BBOX = "bbox"
      val LAYOUT = "layout"
      val VIEW = "view"
      val TRIAGED = "triaged"
      val SORT = "sort"
      val IS_CONTINUOUS_SCROLL = "isContinuousScroll"
      val GROUP_BY = "groupBy"
      val GROUP_DIRECTION = "groupDirection"
      val AFTER = "after"

    // The map's own parameters: `viewport` clips cells and pins, unlike the `bbox` search filter, which the map ignores
    object Map:
      val VIEWPORT = "viewport"
      val ZOOM = "zoom"

    object SearchSort:
      val DIRECTION = "direction"
      val FIELD = "field"

      val BY_ASSET_CREATED_AT = "created_at"

    object Setup:
      val ADMIN_EMAIL = "adminEmail"
      val ADMIN_NAME = "adminName"
      val ADMIN_USERNAME = "adminUsername"
      val REPOSITORY_NAME = "repositoryName"
      val PASSWORD = "password"
      val PASSWORD2 = "password2"

    object Sort:
      val DIRECTION = "direction"

    object Import:
      val NAME = "name"
      val DEST_FOLDER_ID = "destFolderId"
      // How many files an upload is about to send to the Import it opens
      val EXPECTED_FILES = "expectedFiles"
      val REL_PATH = "relPath"
      val SIZE_BYTES = "sizeBytes"
      val OUTCOME = "outcome"
      val REASON = "reason"

    object WatchFolder:
      val WATCH_FOLDER_ID = "watchFolderId"
      val NAME = "name"
      val PATH = "path"
      // On, the files are sorted under the destination folder; off, they land in Triage
      val KEEPS_STRUCTURE = "keepsStructure"
      val DEST_FOLDER_ID = "destFolderId"
      val DELETES_IMPORTED = "deletesImported"

    // A message of the import status feed, as `hx-ws` reads it: markup, its target and swap, and the Import it is about; or,
    // alone, that the socket is connected
    object ImportStatus:
      val TARGET = "target"
      val SWAP = "swap"
      val CONTENT = "content"
      val IMPORT_ID = "importId"
      val IS_CLOSED = "closed"
      val CONNECTED = "connected"
      // Alone in a message: the Library's Watch folders changed, and the page reads them again
      val WATCH_FOLDERS = "watchFolders"
