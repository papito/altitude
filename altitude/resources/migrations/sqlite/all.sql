CREATE TABLE system (
  id INT NOT NULL DEFAULT 1 CHECK (id = 1), -- this is the ONLY ID in the system table
  version INT NOT NULL,
  is_initialized TINYINT NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX system_01 ON system (id);
INSERT INTO system (version, is_initialized) VALUES (0, 0);

CREATE TABLE account (
  id CHAR(36) PRIMARY KEY,
  email TEXT NOT NULL,
  name TEXT NOT NULL,
  account_type TEXT NOT NULL
    CHECK (account_type IN ('Admin', 'User', 'Guest')),
  password_hash TEXT NOT NULL,
  last_active_repo_id CHAR(36),
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL
);

-- One account per email address, whatever its case, and the login lookup, which compares lower(email) too
CREATE UNIQUE INDEX account_01 ON account (lower(email));

CREATE TABLE repository (
  id CHAR(36) PRIMARY KEY,
  name TEXT NOT NULL,
  owner_account_id CHAR(36) REFERENCES account (id) ON DELETE CASCADE,
  description TEXT,
  root_folder_id CHAR(36) NOT NULL,
  file_store_type VARCHAR NOT NULL,
  file_store_config TEXT NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL
);

CREATE TABLE stats (
  repository_id CHAR(36) NOT NULL,
  dimension VARCHAR(60) NOT NULL,
  dim_val BIGINT NOT NULL DEFAULT 0 CHECK (dim_val >= 0),
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX stats_01 ON stats (repository_id, dimension);

CREATE TABLE asset (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  checksum INT NOT NULL,
  media_type VARCHAR(64) NOT NULL,
  media_subtype VARCHAR(64) NOT NULL,
  mime_type VARCHAR(64) NOT NULL,
  width INT NOT NULL DEFAULT 0,
  height INT NOT NULL DEFAULT 0,
  -- area size of the image in pixels (width * height)
  area_size INT NOT NULL,
  folder_id CHAR(36),
  filename TEXT NOT NULL,
  size_bytes BIGINT NOT NULL,
  is_recycled TINYINT NOT NULL DEFAULT 0,
  is_triaged TINYINT NOT NULL DEFAULT 0,
  is_purged TINYINT NOT NULL DEFAULT 0,
  is_pipeline_processed TINYINT NOT NULL DEFAULT 0,
  -- Camera wall-clock time, with no zone. NULL means unknown and forms the native-position "No date" group.
  original_created_at DATETIME,
  -- CaptureDateSource.dbValue: which metadata rung won; NULL when no capture time was resolved.
  original_created_at_source TEXT,
  -- WGS84 decimal degrees, parsed from the file's GPS metadata on import only (GeoLocationResolver). NULL when it carried none.
  latitude REAL,
  longitude REAL,
  -- A Video's length; NULL for an image.
  duration_ms INTEGER,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  -- The metadata JSON is declared last: a row's columns are stored in order, and reading any column after a large value walks
  -- that value's overflow pages
  extracted_metadata TEXT,
  public_metadata TEXT,
  user_metadata TEXT,
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
);

-- One live asset per content in a repository; the recycle bin may hold any number of copies of it. A statement that binds the
-- flag as a number matches the predicate; one that binds it as text does not.
CREATE UNIQUE INDEX asset_01 ON asset (repository_id, checksum) WHERE is_recycled = FALSE;
-- Capture-day grouping for search results: the camera's calendar date followed by the raw timestamp, so a day range or a
-- day-count probe seeks directly and a grouped, date-sorted page reads in index order. Both are camera-local wall-clock text.
-- It carries the ID and the folder, so a pass over the library that tests text membership, a folder or a day reads the index
-- and not the table.
CREATE INDEX asset_search_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed, date(original_created_at), original_created_at, id, folder_id
);
-- The default flat sort, Date Imported, as an ordered read of the page slice that needs nothing but the index: it carries the ID
CREATE INDEX asset_search_created ON asset (repository_id, is_recycled, is_pipeline_processed, created_at, id);
-- The triage view: only the triaged assets, in Date Imported order and by capture day, so a small triage set in a large library
-- is read without passing over the library. They shrink as triage is sorted. A statement that binds the flag as a number
-- matches the predicate; one that binds it as text does not.
CREATE INDEX asset_triage_created ON asset (repository_id, is_recycled, is_pipeline_processed, created_at, id)
  WHERE is_triaged = TRUE;
CREATE INDEX asset_triage_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed, date(original_created_at), original_created_at, id, folder_id
) WHERE is_triaged = TRUE;
-- Folder browsing and the folder source of Search text; it also lets an OR of text sources be planned as a multi-index OR
CREATE INDEX asset_folder ON asset (folder_id);
-- Map viewport queries: a bounding-box range over the located assets of a repository only. It carries the ID and the capture
-- time a map cell is ranked by, so a viewport's own points are read from the index alone.
CREATE INDEX asset_geo ON asset (repository_id, is_recycled, is_pipeline_processed, latitude, longitude, id, original_created_at)
  WHERE latitude IS NOT NULL;

CREATE TABLE person_label (
  id INTEGER PRIMARY KEY AUTOINCREMENT
);

CREATE TABLE person (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  -- this is taken from the person_label table, where its primary key is a sequence
  name TEXT NOT NULL,
  name_for_sort TEXT NOT NULL,
  cover_face_id CHAR(36),
  num_of_faces INT NOT NULL DEFAULT 0,
  is_named TINYINT NOT NULL DEFAULT 0,
  is_hidden TINYINT NOT NULL DEFAULT 0,
  is_bad_match TINYINT NOT NULL DEFAULT 0,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
);

-- The partial person indexes cover the live people, the ones every person query reads; a merged-away person keeps its name and
-- its cover face, which another person may take. The predicates are written as the queries write them: SQLite uses a partial
-- index only for a query whose own terms imply it, and `is_deleted = FALSE` does not imply `is_deleted = 0`.
CREATE UNIQUE INDEX person_01 ON person (repository_id, name)
    WHERE is_deleted = FALSE AND is_bad_match = FALSE;

CREATE UNIQUE INDEX person_02 ON person (cover_face_id)
    WHERE is_deleted = FALSE;

CREATE INDEX person_03 ON person (repository_id, is_bad_match, num_of_faces, is_hidden, is_named, name_for_sort)
    WHERE is_deleted = FALSE;

CREATE TABLE face (
  id CHAR(36) PRIMARY KEY,
  asset_id CHAR(36) NOT NULL,
  x1 INT NOT NULL,
  y1 INT NOT NULL,
  repository_id CHAR(36) NOT NULL,
  person_id CHAR(36) NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  detection_score FLOAT NOT NULL,
  features BLOB NOT NULL,
  checksum INT NOT NULL,
  -- The Frame time of a Face in a Video, where its crop and box were taken from; NULL for a Face in an image.
  frame_time_ms INTEGER,
  -- L2 norm of the raw ArcFace embedding: higher is a more recognizable face. See face.quality.* in reference.conf.
  quality FLOAT NOT NULL,
  -- An enrolled Face can start a Person and is a candidate in the vector search; a match-only Face can only join one.
  is_enrolled BOOLEAN NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY (person_id) REFERENCES person (id) ON DELETE CASCADE,
  FOREIGN KEY (asset_id) REFERENCES asset (id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
);

-- A crop is unique within its asset: two assets may share a byte-identical frame (a trimmed copy of a video, a re-exported photo).
-- Leading with the asset, it serves the purge cascade and the per-asset probes of Search text.
CREATE UNIQUE INDEX face_01 ON face (asset_id, repository_id, checksum);
-- A person's faces, best first; the asset rides along, so the person source of Search text reads the index alone
CREATE INDEX face_02 ON face (person_id, detection_score, asset_id);

CREATE TABLE metadata_field (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  field_type VARCHAR(255) NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX metadata_field_02 ON metadata_field (repository_id, name_lc);

CREATE TABLE folder (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  parent_id CHAR(36) NOT NULL,
  is_recycled TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
);

-- A folder's name is unique among its siblings; it also lists a repository's folders
CREATE UNIQUE INDEX folder_02 ON folder (repository_id, parent_id, name_lc);
-- A folder's children, live or recycled
CREATE INDEX folder_03 ON folder (parent_id, is_recycled);

CREATE TABLE album (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX album_01 ON album (repository_id, name_lc);

CREATE TABLE album_asset (
  repository_id CHAR(36) NOT NULL,
  album_id CHAR(36) NOT NULL,
  asset_id CHAR(36) NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  FOREIGN KEY (album_id) REFERENCES album (id) ON DELETE CASCADE,
  FOREIGN KEY (asset_id) REFERENCES asset (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX album_asset_01 ON album_asset (album_id, asset_id);
-- An asset's albums, read from the index alone: the purge cascade and the per-asset probes of Search text
CREATE INDEX album_asset_02 ON album_asset (asset_id, album_id);

-- A user-defined place. Categories (kind 'category') are pure containers, one level deep; Locations (kind 'location') are a pin
-- and hold assets through location_asset. Both kinds share one name pool per repository.
CREATE TABLE location (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  -- NULL = top level. No cascade: deleting a Category moves its Locations to the top level first (LocationService).
  category_id CHAR(36),
  kind VARCHAR(16) NOT NULL,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  latitude REAL,
  longitude REAL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  CHECK (kind IN ('category', 'location')),
  CHECK (kind <> 'category' OR (category_id IS NULL AND latitude IS NULL AND longitude IS NULL)),
  CHECK (kind <> 'location' OR (latitude IS NOT NULL AND longitude IS NOT NULL)),
  CHECK (latitude BETWEEN -90 AND 90),
  CHECK (longitude BETWEEN -180 AND 180),
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  FOREIGN KEY (category_id) REFERENCES location (id)
);

CREATE UNIQUE INDEX location_01 ON location (repository_id, name_lc);
-- A category's Locations: moving them to the top level, and the check that keeps a deleted category from orphaning one
CREATE INDEX location_02 ON location (category_id, repository_id);

CREATE TABLE location_asset (
  repository_id CHAR(36) NOT NULL,
  location_id CHAR(36) NOT NULL,
  asset_id CHAR(36) NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  FOREIGN KEY (location_id) REFERENCES location (id) ON DELETE CASCADE,
  FOREIGN KEY (asset_id) REFERENCES asset (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX location_asset_01 ON location_asset (location_id, asset_id);
-- An asset's Locations, read from the index alone: the purge cascade and the per-asset probes of Search text
CREATE INDEX location_asset_02 ON location_asset (asset_id, location_id);

CREATE TABLE metadata_parameter (
  repository_id CHAR(36) NOT NULL,
  asset_id CHAR(36) NOT NULL,
  field_id CHAR(36) NOT NULL,
  field_value_kw TEXT NULL,
  field_value_num DECIMAL,
  field_value_bool BOOLEAN,
  field_value_dt DATETIME,
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  FOREIGN KEY (asset_id) REFERENCES asset (id) ON DELETE CASCADE,
  FOREIGN KEY (field_id) REFERENCES metadata_field (id) ON DELETE CASCADE
);

-- The purge cascade and clearing an asset's parameters
CREATE INDEX metadata_parameter_01 ON metadata_parameter (asset_id);

-- id is the declared key the full-text index follows: an implicit rowid may be renumbered by VACUUM
CREATE TABLE search_document (
  id INTEGER PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  asset_id CHAR(36) NOT NULL,
  body TEXT NOT NULL,
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  FOREIGN KEY (asset_id) REFERENCES asset (id) ON DELETE CASCADE
);

-- An asset has one document. Leading with the asset, it serves the purge cascade and the per-asset probes of Search text; the
-- upsert's ON CONFLICT (repository_id, asset_id) still finds it, since a conflict target names a set of columns.
CREATE UNIQUE INDEX search_document_01 ON search_document (asset_id, repository_id);

-- The full-text index over search_document.body. It stores no text of its own (external content) and is kept in step by the
-- triggers below. body holds words already split by the application, so the tokenizer takes them as they are.
CREATE VIRTUAL TABLE search_document_fts USING fts5 (
  body,
  content='search_document',
  content_rowid='id',
  tokenize='unicode61 remove_diacritics 0',
  prefix='2 3 4'
);

CREATE TRIGGER search_document_after_insert AFTER INSERT ON search_document BEGIN
  INSERT INTO search_document_fts (rowid, body) VALUES (new.id, new.body);
END;

-- An external-content index is told what to forget with the text it was given: a 'delete' command carrying the old row
CREATE TRIGGER search_document_after_delete AFTER DELETE ON search_document BEGIN
  INSERT INTO search_document_fts (search_document_fts, rowid, body) VALUES ('delete', old.id, old.body);
END;

CREATE TRIGGER search_document_after_update AFTER UPDATE ON search_document BEGIN
  INSERT INTO search_document_fts (search_document_fts, rowid, body) VALUES ('delete', old.id, old.body);
  INSERT INTO search_document_fts (rowid, body) VALUES (new.id, new.body);
END;
