DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;

CREATE EXTENSION IF NOT EXISTS vector;

-- Every ID column (id and every *_id, the foreign keys included) is CHAR(36) COLLATE "C": IDs are compared, sorted and grouped
-- byte-wise, which is about twice as fast as under the database's default collation and orders them the same on both engines.

CREATE TABLE _core (
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'utc'),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NULL
);

CREATE TABLE system (
  id INT NOT NULL DEFAULT 1 CHECK (id = 1), -- this is the ONLY ID in the system table
  version INT NOT NULL,
  is_initialized BOOL NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX system_01 ON system (id);
INSERT INTO system (version, is_initialized) VALUES (1, False);

CREATE TABLE account (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  email TEXT NOT NULL,
  name TEXT NOT NULL,
  account_type TEXT NOT NULL
    CHECK (account_type IN ('Admin', 'User', 'Guest')),
  password_hash TEXT NOT NULL,
  last_active_repo_id CHAR(36) COLLATE "C"
) INHERITS (_core);

-- One account per email address, whatever its case, and the login lookup, which compares lower(email) too
CREATE UNIQUE INDEX account_01 ON account (lower(email));

CREATE TABLE repository (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT,
  owner_account_id CHAR(36) COLLATE "C" REFERENCES account (id) ON DELETE CASCADE,
  root_folder_id CHAR(36) COLLATE "C" NOT NULL,
  file_store_type VARCHAR NOT NULL,
  file_store_config jsonb
) INHERITS (_core);

CREATE TABLE stats (
  repository_id CHAR(36) COLLATE "C" REFERENCES repository (id) ON DELETE CASCADE,
  dimension VARCHAR(60),
  dim_val BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX stats_01 ON stats (repository_id, dimension);

-- toast_tuple_target: the jsonb metadata columns are moved out of line once a row passes 128 bytes, so the heap row a search
-- reads stays narrow
CREATE TABLE asset (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" REFERENCES repository (id) ON DELETE CASCADE,
  user_id CHAR(36) COLLATE "C",
  checksum INT NOT NULL,
  media_type VARCHAR(64) NOT NULL,
  media_subtype VARCHAR(64) NOT NULL,
  mime_type VARCHAR(64) NOT NULL,
  width INT NOT NULL DEFAULT 0,
  height INT NOT NULL DEFAULT 0,
  -- area size of the image in pixels (width * height)
  area_size INT NOT NULL,
  user_metadata jsonb,
  public_metadata jsonb,
  extracted_metadata jsonb,
  folder_id CHAR(36) COLLATE "C",
  filename TEXT NOT NULL,
  size_bytes BIGINT NOT NULL,
  is_triaged BOOLEAN NOT NULL DEFAULT FALSE,
  is_recycled BOOLEAN NOT NULL DEFAULT FALSE,
  is_purged BOOLEAN NOT NULL DEFAULT FALSE,
  is_pipeline_processed BOOLEAN NOT NULL DEFAULT FALSE,
  -- Camera wall-clock time, with no zone. NULL means unknown and forms the native-position "No date" group.
  original_created_at TIMESTAMP WITHOUT TIME ZONE,
  -- CaptureDateSource.dbValue: which metadata rung won; NULL when no capture time was resolved.
  original_created_at_source VARCHAR(32),
  -- WGS84 decimal degrees, parsed from the file's GPS metadata on import only (GeoLocationResolver). NULL when it carried none.
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  -- A Video's length; NULL for an image.
  duration_ms BIGINT
) INHERITS (_core) WITH (toast_tuple_target = 128);

-- One live asset per content in a repository; the recycle bin may hold any number of copies of it
CREATE UNIQUE INDEX asset_01 ON asset (repository_id, checksum) WHERE NOT is_recycled;
-- Capture-day grouping for search results: the camera's calendar date followed by the raw timestamp, so a day range or a
-- day-count probe seeks directly and a grouped, date-sorted page reads in index order. It carries the ID and the folder, so a
-- pass over the library that tests text membership, a folder or a day reads the index and not the table.
CREATE INDEX asset_search_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed, (original_created_at::date), original_created_at
) INCLUDE (id, folder_id);
-- The default flat sort, Date Imported, as an ordered read of the page slice that needs nothing but the index: it carries the ID
CREATE INDEX asset_search_created ON asset (repository_id, is_recycled, is_pipeline_processed, created_at) INCLUDE (id);
-- The triage view: only the triaged assets, in Date Imported order and by capture day, so a small triage set in a large library
-- is read without passing over the library. They shrink as triage is sorted.
CREATE INDEX asset_triage_created ON asset (repository_id, is_recycled, is_pipeline_processed, created_at) INCLUDE (id)
  WHERE is_triaged;
CREATE INDEX asset_triage_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed, (original_created_at::date), original_created_at
) INCLUDE (id, folder_id) WHERE is_triaged;
-- Folder browsing and the folder source of Search text
CREATE INDEX asset_folder ON asset (folder_id);
-- Map viewport queries: a bounding-box range over the located assets of a repository only. It carries the ID and the capture
-- time a map cell is ranked by, so a viewport's own points are read from the index alone.
CREATE INDEX asset_geo ON asset (repository_id, is_recycled, is_pipeline_processed, latitude, longitude)
  INCLUDE (id, original_created_at) WHERE latitude IS NOT NULL;

CREATE SEQUENCE person_label;

CREATE TABLE person (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" REFERENCES repository (id) ON DELETE CASCADE,
  -- this is taken from the person_label table, where its primary key is a sequence
  name TEXT NOT NULL,
  name_for_sort TEXT NOT NULL,
  cover_face_id CHAR(36) COLLATE "C",
  num_of_faces INT NOT NULL DEFAULT 0,
  is_named BOOLEAN NOT NULL DEFAULT FALSE,
  is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  is_bad_match BOOLEAN NOT NULL DEFAULT FALSE
) INHERITS (_core);

-- The partial person indexes cover the live people, the ones every person query reads; a merged-away person keeps its name and
-- its cover face, which another person may take
CREATE UNIQUE INDEX person_01 ON person (repository_id, name)
    WHERE is_deleted = FALSE AND is_bad_match = FALSE;

CREATE UNIQUE INDEX person_02 ON person (cover_face_id)
    WHERE is_deleted = FALSE;

CREATE INDEX person_03 ON person (repository_id, is_bad_match, num_of_faces, is_hidden, is_named, name_for_sort)
    WHERE is_deleted = FALSE;

CREATE TABLE face (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  asset_id CHAR(36) COLLATE "C" NOT NULL REFERENCES asset (id) ON DELETE CASCADE,
  person_id CHAR(36) COLLATE "C" NOT NULL REFERENCES person (id) ON DELETE CASCADE,
  x1 INT NOT NULL,
  y1 INT NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  detection_score FLOAT NOT NULL,
  -- The L2-normalized ArcFace embedding (FaceDetectionService.EMBEDDING_DIMENSIONS)
  features vector(512) NOT NULL,
  checksum INT NOT NULL,
  -- The Frame time of a Face in a Video, where its crop and box were taken from; NULL for a Face in an image.
  frame_time_ms BIGINT,
  -- L2 norm of the raw ArcFace embedding: higher is a more recognizable face. See face.quality.* in reference.conf.
  quality FLOAT NOT NULL,
  -- An enrolled Face can start a Person and is a candidate in the vector search; a match-only Face can only join one.
  is_enrolled BOOLEAN NOT NULL
) INHERITS (_core);

-- A crop is unique within its asset: two assets may share a byte-identical frame (a trimmed copy of a video, a re-exported photo).
-- Leading with the asset, it serves the purge cascade and the per-asset probes of Search text, which read the person from it.
CREATE UNIQUE INDEX face_01 ON face (asset_id, repository_id, checksum) INCLUDE (person_id);
-- A person's faces, best first; the asset rides along, so the person source of Search text reads the index alone
CREATE INDEX face_02 ON face (person_id, detection_score) INCLUDE (asset_id);
-- The nearest enrolled Faces of a vector, for recognition; a match-only Face is never a candidate. The index holds the vectors
-- at half precision, which halves it, and the statement that reads it (FaceDao.CLOSEST_MATCHES_SQL) writes the same cast.
CREATE INDEX face_03 ON face USING hnsw ((features::halfvec(512)) halfvec_cosine_ops) WHERE is_enrolled;

CREATE TABLE metadata_field (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" REFERENCES repository (id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  field_type VARCHAR(255) NOT NULL
) INHERITS (_core);

CREATE UNIQUE INDEX metadata_field_02 ON metadata_field (repository_id, name_lc);

CREATE TABLE folder (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" REFERENCES repository (id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  parent_id CHAR(36) COLLATE "C" NOT NULL,
  is_recycled BOOLEAN NOT NULL DEFAULT FALSE
) INHERITS (_core);

-- A folder's name is unique among its siblings; it also lists a repository's folders
CREATE UNIQUE INDEX folder_02 ON folder (repository_id, parent_id, name_lc);
-- A folder's children, live or recycled
CREATE INDEX folder_03 ON folder (parent_id, is_recycled);

CREATE TABLE album (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" REFERENCES repository (id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL
) INHERITS (_core);

CREATE UNIQUE INDEX album_01 ON album (repository_id, name_lc);

CREATE TABLE album_asset (
  repository_id CHAR(36) COLLATE "C" REFERENCES repository (id) ON DELETE CASCADE,
  album_id CHAR(36) COLLATE "C" NOT NULL REFERENCES album (id) ON DELETE CASCADE,
  asset_id CHAR(36) COLLATE "C" NOT NULL REFERENCES asset (id) ON DELETE CASCADE
) INHERITS (_core);

CREATE UNIQUE INDEX album_asset_01 ON album_asset (album_id, asset_id);
-- An asset's albums, read from the index alone: the purge cascade and the per-asset probes of Search text
CREATE INDEX album_asset_02 ON album_asset (asset_id, album_id);

-- A user-defined place. Categories (kind 'category') are pure containers, one level deep; Locations (kind 'location') are a pin
-- and hold assets through location_asset. Both kinds share one name pool per repository.
CREATE TABLE location (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" REFERENCES repository (id) ON DELETE CASCADE,
  -- NULL = top level. No cascade: deleting a Category moves its Locations to the top level first (LocationService).
  category_id CHAR(36) COLLATE "C" REFERENCES location (id),
  kind VARCHAR(16) NOT NULL,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  CHECK (kind IN ('category', 'location')),
  CHECK (kind <> 'category' OR (category_id IS NULL AND latitude IS NULL AND longitude IS NULL)),
  CHECK (kind <> 'location' OR (latitude IS NOT NULL AND longitude IS NOT NULL)),
  CHECK (latitude BETWEEN -90 AND 90),
  CHECK (longitude BETWEEN -180 AND 180)
) INHERITS (_core);

CREATE UNIQUE INDEX location_01 ON location (repository_id, name_lc);
-- A category's Locations: moving them to the top level, and the check that keeps a deleted category from orphaning one
CREATE INDEX location_02 ON location (category_id, repository_id);

CREATE TABLE location_asset (
  repository_id CHAR(36) COLLATE "C" REFERENCES repository (id) ON DELETE CASCADE,
  location_id CHAR(36) COLLATE "C" NOT NULL REFERENCES location (id) ON DELETE CASCADE,
  asset_id CHAR(36) COLLATE "C" NOT NULL REFERENCES asset (id) ON DELETE CASCADE
) INHERITS (_core);

CREATE UNIQUE INDEX location_asset_01 ON location_asset (location_id, asset_id);
-- An asset's Locations, read from the index alone: the purge cascade and the per-asset probes of Search text
CREATE INDEX location_asset_02 ON location_asset (asset_id, location_id);

CREATE TABLE metadata_parameter (
  repository_id CHAR(36) COLLATE "C" REFERENCES repository (id) ON DELETE CASCADE,
  asset_id CHAR(36) COLLATE "C" REFERENCES asset (id) ON DELETE CASCADE,
  field_id CHAR(36) COLLATE "C" REFERENCES metadata_field (id) ON DELETE CASCADE,
  field_value_kw TEXT NULL,
  field_value_num DECIMAL,
  field_value_bool BOOLEAN,
  field_value_dt TIMESTAMP WITH TIME ZONE
);

-- The purge cascade and clearing an asset's parameters
CREATE INDEX metadata_parameter_01 ON metadata_parameter (asset_id);

-- body holds words already split by the application, so the vector takes them as they are: no stemming, no stop words
CREATE TABLE search_document (
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  asset_id CHAR(36) COLLATE "C" NOT NULL REFERENCES asset (id) ON DELETE CASCADE,
  body TEXT NOT NULL,
  tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', body)) STORED
);

-- An asset has one document. Leading with the asset, it serves the purge cascade and the per-asset probes of Search text; the
-- upsert's ON CONFLICT (repository_id, asset_id) still finds it, since a conflict target names a set of columns.
CREATE UNIQUE INDEX search_document_01 ON search_document (asset_id, repository_id);
CREATE INDEX search_document_02 ON search_document USING gin (tsv);
