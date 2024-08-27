package software.altitude.core.models

object Field {
  val ID = "id"
  val REPO_ID = "repository_id"
  val USER_ID = "user_id"
  val VALUE = "value"
  val VALUES = "values"
  val DATA = "data"
  val CREATED_AT = "created_at"
  val UPDATED_AT = "updated_at"

  object SystemMetadata {
    val VERSION = "version"
    val IS_INITIALIZED = "is_initialized"
  }

  object Repository {
    val NAME = "name"
    val OWNER_ACCOUNT_ID = "owner_account_id"
    val ROOT_FOLDER_ID = "root_folder_id"
    val FILE_STORE_TYPE = "file_store_type"
    val FILES_STORE_CONFIG = "file_store_config"
  }

  object User {
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

  object Face {
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
    val CHECKSUM = "checksum"
  }

  object Person {
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

  object Asset {
    val CHECKSUM = "checksum"
    val ASSET_TYPE = "asset_type"
    val FOLDER_ID = "folder_id"
    val SIZE_BYTES = "size_bytes"
    val FILENAME = "filename"
    val METADATA = "metadata"
    val EXTRACTED_METADATA = "extracted_metadata"
    val IS_RECYCLED = "is_recycled"
    val IS_TRIAGED = "is_triaged"
  }

  object AssetType {
    val MIME_TYPE = "mime_type"
    val MEDIA_TYPE = "media_type"
    val MEDIA_SUBTYPE = "media_subtype"
  }

  object Folder {
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

  object Stat {
    val DIMENSION = "dimension"
    val DIM_VAL = "dim_val"
  }

  object MetadataField {
    val NAME = "name"
    val NAME_LC = "name_lc"
    val FIELD_TYPE = "field_type"
    val FIELD = "field"
  }

  object SearchToken {
    val ASSET_ID = "asset_id"
    val FIELD_ID = "field_id"
    val FIELD_VALUE_KW = "field_value_kw"
    val FIELD_VALUE_NUM = "field_value_num"
    val FIELD_VALUE_BOOL = "field_value_bool"
  }
}
