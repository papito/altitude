package altitude.core

object FieldConst:
  val ID = "id"
  val REPO_ID = "repository_id"
  val VALUE = "value"
  val VALUES = "values"
  val DATA = "data"
  val CREATED_AT = "created_at"
  val UPDATED_AT = "updated_at"

  object SystemMetadata:
    val VERSION = "version"
    val IS_INITIALIZED = "is_initialized"
    val SIGNING_KEY = "signing_key"

  object Setting:
    val NAME = "name"
    val DEFAULT_VALUE = "default_value"

  object Repository:
    val NAME = "name"
    val NAME_KEY = "name_key"
    val ROOT_FOLDER_ID = "root_folder_id"
    val FILE_STORE_TYPE = "file_store_type"
    val FILES_STORE_CONFIG = "file_store_config"

  object User:
    val EMAIL = "email"
    val NAME = "name"
    val PASSWORD_HASH = "password_hash"
    val ACCOUNT_TYPE = "account_type"
    val LAST_ACTIVE_REPO_ID = "last_active_repo_id"
    val EMAIL_KEY = "email_key"
    val USERNAME = "username"
    val USERNAME_KEY = "username_key"
    val IS_ACTIVE = "is_active"
    val CAN_CREATE_LIBRARIES = "can_create_libraries"
    val GRANTS_NEW_LIBRARIES = "grants_new_libraries"
    val NEW_LIBRARY_PERMISSIONS = "new_library_permissions"
    val CREATED_BY_ACCOUNT_ID = "created_by_account_id"
    val LAST_LOGIN_AT = "last_login_at"

  object RepositoryGrant:
    val ACCOUNT_ID = "account_id"
    val PERMISSIONS = "permissions"

  object AuditEvent:
    val EVENT = "event"
    val ACTOR = "actor"
    val TARGET = "target"
    val DETAIL = "detail"
    val CLIENT_IP = "client_ip"

  object Face:
    val X1 = "x1"
    val Y1 = "y1"
    val WIDTH = "width"
    val HEIGHT = "height"
    val ASSET_ID = "asset_id"
    val PERSON_ID = "person_id"
    val DETECTION_SCORE = "detection_score"
    val FEATURES = "features"
    val CHECKSUM = "checksum"
    val QUALITY = "quality"
    val IS_ENROLLED = "is_enrolled"
    val FRAME_TIME_MS = "frame_time_ms"
    val SHARPNESS = "sharpness"

  object Person:
    val NAME = "name"
    val NAME_FOR_SORT = "name_for_sort"
    val COVER_FACE_ID = "cover_face_id"
    val NUM_OF_FACES = "num_of_faces"
    val IS_HIDDEN = "is_hidden"
    val IS_DELETED = "is_deleted"
    val IS_BAD_MATCH = "is_bad_match"
    val IS_NAMED = "is_named"
    val FACES = "faces"

  object Asset:
    val OWNER_USER_ID = "owner_user_id"
    val ORIGINAL_OWNER = "original_owner"
    val CHECKSUM = "checksum"
    val ASSET_TYPE = "asset_type"
    val FOLDER_ID = "folder_id"
    val WIDTH = "width"
    val HEIGHT = "height"
    val AREA_SIZE = "area_size"
    val SIZE_BYTES = "size_bytes"
    val FILENAME = "filename"
    val EXTRACTED_METADATA = "extracted_metadata"
    val PUBLIC_METADATA = "public_metadata"
    val USER_METADATA = "user_metadata"
    val IS_RECYCLED = "is_recycled"
    val IS_TRIAGED = "is_triaged"
    val IS_PURGED = "is_purged"
    val IS_PIPELINE_PROCESSED = "is_pipeline_processed"
    val ORIGINAL_CREATED_AT = "original_created_at"
    val ORIGINAL_CREATED_AT_SOURCE = "original_created_at_source"
    val LATITUDE = "latitude"
    val LONGITUDE = "longitude"
    val DURATION_MS = "duration_ms"
    val SHARPNESS = "sharpness"
    val LUMINANCE = "luminance"
    val CLIPPED_HIGHLIGHTS = "clipped_highlights"
    val CRUSHED_SHADOWS = "crushed_shadows"
    val IMAGE_QUALITY = "image_quality"
    val PERCEPTUAL_HASH = "perceptual_hash"
    val HISTOGRAM = "histogram"
    // The Scene embeddings, one column per Scene model (`SceneModel.column`)
    val DINOV2_EMBEDDING = "dinov2_embedding"
    val CLIP_EMBEDDING = "clip_embedding"
    val SEQUENCE_ID = "sequence_id"

  object PublicMetadata:
    val DEVICE_MODEL = "device_model"
    val F_NUMBER = "f_number"
    val EXPOSURE_TIME = "exposure_time"
    val ISO = "iso"
    val FOCAL_LENGTH = "focal_length"
    val DATE_TIME_ORIGINAL = "date_time_original"

  object AssetType:
    val MIME_TYPE = "mime_type"
    val MEDIA_TYPE = "media_type"
    val MEDIA_SUBTYPE = "media_subtype"

  object Folder:
    val NAME = "name"
    val NAME_KEY = "name_key"
    val PARENT_ID = "parent_id"
    val NUM_OF_ASSETS = "num_of_assets"
    val NUM_OF_CHILDREN = "num_of_children"
    val CHILDREN = "children"
    val IS_RECYCLED = "is_recycled"

    object Name:
      val ROOT = "root"

    object Alias:
      val ROOT = "root"

  object Album:
    val NAME = "name"
    val NAME_KEY = "name_key"
    val NUM_OF_ASSETS = "num_of_assets"
    val ALBUM_ID = "album_id"
    val ASSET_ID = "asset_id"

  object Location:
    val CATEGORY_ID = "category_id"
    val KIND = "kind"
    val NAME = "name"
    val NAME_KEY = "name_key"
    val LATITUDE = "latitude"
    val LONGITUDE = "longitude"
    val LOCATION_ID = "location_id"
    val ASSET_ID = "asset_id"
    val NUM_OF_ASSETS = "num_of_assets"
    val CATEGORY_NAME = "category_name"

  object MimedData:
    val MIME_TYPE = "mime_type"
    val DATA = "data"
    val ASSET_ID = "asset_id"

  object Stat:
    val DIMENSION = "dimension"
    val DIM_VAL = "dim_val"

  object MetadataField:
    val NAME = "name"
    val NAME_KEY = "name_key"
    val FIELD_TYPE = "field_type"
    val FIELD = "field"

  object SearchToken:
    val ASSET_ID = "asset_id"
    val FIELD_ID = "field_id"
    val FIELD_VALUE_KW = "field_value_kw"
    val FIELD_VALUE_NUM = "field_value_num"
    val FIELD_VALUE_BOOL = "field_value_bool"
