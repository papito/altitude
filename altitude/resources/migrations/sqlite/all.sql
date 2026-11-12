-- A row of a repository's table holds its repository (repository_id NOT NULL), and a reference from one such row to another is
-- a foreign key over (repository_id, <ref>_id), so the database refuses a reference into another repository. Each table such a
-- key names has UNIQUE (id, repository_id) for it, led by the ID so that it never competes with the indexes that lead with the
-- repository. A NULL reference is not checked. A person's cover and an Import file's asset are the references that are not such
-- keys: a single column each, at the person and import_file tables.

CREATE TABLE system (
  id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1), -- the one and only row: the key rejects a second, the check pins its id
  version INT NOT NULL,
  is_initialized TINYINT NOT NULL DEFAULT FALSE,
  -- The HMAC-SHA256 key of signed content URLs, the Base64 of 32 random bytes, generated on first start
  -- (SystemService.loadOrCreateSigningKey) so a signed URL survives a restart
  signing_key CHAR(44)
);

INSERT INTO system (version, is_initialized) VALUES (0, 0);

-- The Settings an Admin tunes on the Settings screen, seeded with their defaults; the registry is models/Settings.scala
CREATE TABLE setting (
  name TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL,
  default_value TEXT NOT NULL
);

INSERT INTO setting (name, value, default_value) VALUES
  ('face.yunet.confidence_threshold', '0.85', '0.85'),
  ('face.yunet.nms_threshold', '0.3', '0.3'),
  ('face.detection.bounding_box_size', '1280', '1280'),
  ('face.detection.min_face_size', '50', '50'),
  ('face.recognition.cosine_distance_threshold', '0.55', '0.55'),
  ('face.recognition.match_count', '5', '5'),
  ('face.recognition.hnsw_ef_search', '100', '100'),
  ('face.quality.enroll_threshold', '18.0', '18.0'),
  ('face.quality.keep_threshold', '15.0', '15.0'),
  ('video.faces.sample_interval_ms', '1000', '1000'),
  ('video.faces.max_sampled_frames', '120', '120'),
  ('video.faces.min_cluster_frames', '2', '2'),
  ('video.preview.min_luminance', '40', '40'),
  ('sequence.max_gap_ms', '60000', '60000'),
  ('sequence.dinov2.min_similarity', '0.80', '0.80'),
  ('sequence.clip.min_similarity', '0.90', '0.90'),
  ('sequence.max_hash_distance', '10', '10'),
  ('curation.blur_ratio', '0.5', '0.5'),
  ('curation.face_quality_gap', '2.0', '2.0'),
  ('curation.clipped_fraction', '0.05', '0.05'),
  ('curation.crushed_fraction', '0.05', '0.05'),
  ('curation.dark_luminance', '0.15', '0.15'),
  ('curation.bright_luminance', '0.75', '0.75'),
  ('curation.luminance_gap', '0.1', '0.1'),
  ('map.tile.url', 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'),
  ('map.tile.attribution', '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors', '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'),
  ('map.geocoder.enabled', 'false', 'false'),
  ('map.geocoder.url', 'https://nominatim.openstreetmap.org/search', 'https://nominatim.openstreetmap.org/search'),
  ('db.postgres.read_statement_timeout', '30', '30'),
  ('pipeline.shutdown_timeout', '30', '30'),
  ('import.parallelism', '0', '0'),
  ('import.history.days', '90', '90');

-- email_key and username_key are the name keys of the email address and the username (User.emailKey and User.usernameKey:
-- NameKey.of, folded in Scala, so both engines fold alike, where SQLite's lower() folds ASCII only)
CREATE TABLE account (
  id CHAR(36) PRIMARY KEY,
  email TEXT NOT NULL,
  email_key TEXT NOT NULL,
  username TEXT NOT NULL,
  username_key TEXT NOT NULL,
  name TEXT NOT NULL,
  account_type TEXT NOT NULL
    CHECK (account_type IN ('Admin', 'User', 'Guest')),
  password_hash TEXT NOT NULL,
  -- A deactivated account can do nothing until it is reactivated
  is_active TINYINT NOT NULL DEFAULT 1,
  -- The Create Libraries flag, which only an Admin sets
  can_create_libraries TINYINT NOT NULL DEFAULT 0,
  -- The new-Library grant: with the switch on, every Library created later grants the account these Permissions (a bitmask of
  -- Permission bits)
  grants_new_libraries TINYINT NOT NULL DEFAULT 0,
  new_library_permissions INT NOT NULL DEFAULT 0 CHECK (new_library_permissions >= 0),
  -- The account that created this one; NULL for the setup Admin, and once the creator is deleted
  created_by_account_id CHAR(36) REFERENCES account (id) ON DELETE SET NULL,
  last_login_at DATETIME,
  last_active_repo_id CHAR(36),
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL
);

-- One account per email address and one per username, whatever the case or Unicode form either is typed in; a login compares
-- the key column of what it names
CREATE UNIQUE INDEX account_01 ON account (email_key);
CREATE UNIQUE INDEX account_02 ON account (username_key);

CREATE TABLE repository (
  id CHAR(36) PRIMARY KEY,
  name TEXT NOT NULL,
  name_key TEXT NOT NULL,
  description TEXT,
  root_folder_id CHAR(36) NOT NULL,
  file_store_type VARCHAR NOT NULL,
  file_store_config TEXT NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL
);

-- repository_01: a Library's name is unique instance-wide under its name key (Repository.nameKey: NameKey.of of the normalized
-- name, folded in Scala, so both engines fold alike, where SQLite's lower() folds ASCII only)
CREATE UNIQUE INDEX repository_01 ON repository (name_key);

-- Who may see a Library, and do what in it: one row per account and Library, its Permissions a bitmask of Permission bits (0
-- for a Guest, who only looks). An Admin reaches every Library with no row. A row goes with its Library or its account.
CREATE TABLE repository_grant (
  account_id CHAR(36) NOT NULL REFERENCES account (id) ON DELETE CASCADE,
  repository_id CHAR(36) NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  permissions INT NOT NULL DEFAULT 0 CHECK (permissions >= 0),
  -- An account's grants, the lookup of a login and of the gate, and one row per pair
  PRIMARY KEY (account_id, repository_id)
);

-- A Library's grants, and the cascade from a deleted Library
CREATE INDEX repository_grant_01 ON repository_grant (repository_id);

-- An append-only record of account events (AuditEventType), written from the service layer. The actor and the target are
-- snapshots of the accounts as they were (id, name, username, email), since accounts are deleted; the actor is NULL for a failed
-- login, which names no account.
CREATE TABLE audit_event (
  id CHAR(36) PRIMARY KEY,
  event TEXT NOT NULL,
  actor TEXT,
  target TEXT,
  detail TEXT,
  client_ip TEXT,
  created_at DATETIME NOT NULL DEFAULT (datetime('now', 'utc'))
);

CREATE INDEX audit_event_01 ON audit_event (created_at);

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
  -- The account that owns the asset: its importer, until the importer is deleted and the asset moves to the deleting Admin. No
  -- delete action, so an account with assets cannot be deleted without that move.
  owner_user_id CHAR(36) NOT NULL REFERENCES account (id),
  -- SHA-256 of the file, 64 lowercase hex characters (Sha256.hex)
  checksum CHAR(64) NOT NULL,
  media_type VARCHAR(64) NOT NULL,
  media_subtype VARCHAR(64) NOT NULL,
  mime_type VARCHAR(64) NOT NULL,
  width INT NOT NULL DEFAULT 0,
  height INT NOT NULL DEFAULT 0,
  -- area size of the image in pixels (width * height)
  area_size INT NOT NULL,
  -- NULL for an asset in no folder: a triaged one
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
  -- How long a Video or an animated GIF plays; NULL for a still image.
  duration_ms INTEGER,
  -- Image quality (ImageQuality): the metrics and the 0 to 100 score of a still image, measured at import. The five are NULL
  -- together for a Video, an animated image, or a still image the pipeline did not measure.
  sharpness REAL,
  luminance REAL,
  clipped_highlights REAL,
  crushed_shadows REAL,
  image_quality INTEGER,
  -- The DCT perceptual hash of a still image, 64 bits as a signed integer, compared by Hamming distance; NULL as the five are.
  perceptual_hash BIGINT,
  -- The Sequence the image is in while in triage: the id its members share. A Sequence has no table; it exists while some
  -- asset carries its id. NULL for a sorted asset, a Video or an animated image.
  sequence_id CHAR(36),
  -- The importer record (AccountSnapshot): id, name, username and email of the account that imported the asset, as JSON,
  -- written at import, which outlives the account
  original_owner TEXT NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  -- The bulky columns come last, the most read of them first: a row's columns are stored in order, a row past the page size
  -- (4 KB) spills into overflow pages, and reading a column walks the overflow pages of every large value before it. The two
  -- metadata JSON columns every general read selects (public_metadata also decides the Sequence candidates' device model) come
  -- before the Histogram, 1 KB a row that the read of a Curation set's Histograms alone selects, then the Scene embeddings,
  -- about 5 KB a row that the Sequence candidates' statement alone selects, and the raw extracted metadata, the largest and the
  -- least read, comes after everything.
  public_metadata TEXT,
  user_metadata TEXT,
  -- The Histogram of a still image (Histogram): its luminance, red, green and blue channels, each 64 bins of the fraction of
  -- the pixels in the bin, as 1,024 bytes of little-endian float32. NULL for a Video, an animated image, or a still image the
  -- pipeline did not measure, as the Image quality is.
  histogram BLOB,
  -- The Scene embeddings of a still image, one per Scene model, each a unit vector the Sequence rule compares by cosine
  -- similarity, stored as its raw little-endian float32 values: DINOv2 ViT-B/14's pooled CLS token (768 of them) and CLIP
  -- ViT-B/16's projected image embedding (512). NULL for a Video, an animated image, or a still image the model could not
  -- embed. Selected by the Sequence candidates' statement alone and transferred by no other read.
  dinov2_embedding BLOB,
  clip_embedding BLOB,
  extracted_metadata TEXT,
  UNIQUE (id, repository_id),
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  -- No delete action, as no folder is ever deleted on its own (a deleted folder is recycled)
  FOREIGN KEY (repository_id, folder_id) REFERENCES folder (repository_id, id)
);

-- One live asset per content in a repository; the recycle bin may hold any number of copies of it. A statement that binds the
-- flag as a number matches the predicate; one that binds it as text does not.
CREATE UNIQUE INDEX asset_01 ON asset (repository_id, checksum) WHERE is_recycled = FALSE;
-- Capture-day grouping for search results: the camera's calendar date followed by the raw timestamp, so a day range or a
-- day-count probe seeks directly and a grouped, date-sorted page reads in index order. Both are camera-local wall-clock text.
-- It carries the ID and the folder, so a pass over the library that tests text membership, a folder or a day reads the index
-- and not the table, the triage flag for the repository view, which leaves triaged assets out by default, and the import time,
-- so a page sorted by Date Taken within a Date Imported range is read in index order with the range tested on the index and
-- not the table. What it carries is its trailing columns.
CREATE INDEX asset_search_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed, date(original_created_at), original_created_at, id, folder_id, is_triaged,
  created_at
);
-- The default flat sort, Date Imported, as an ordered read of the page slice that needs nothing but the index: it carries, as
-- its trailing columns, the ID, the triage flag and the capture time, so a page within a Date Taken range tests the range on
-- the index and not the table
CREATE INDEX asset_search_created ON asset (
  repository_id, is_recycled, is_pipeline_processed, created_at, id, is_triaged, original_created_at
);
-- The triage view: only the triaged assets, in Date Imported order and by capture day, so a small triage set in a large library
-- is read without passing over the library. They shrink as triage is sorted. Neither carries the other date: a search reads them
-- in the triage view alone, where a date range tests the rows of a triage set, not of the library. A statement that binds the
-- flag as a number matches the predicate; one that binds it as text does not.
CREATE INDEX asset_triage_created ON asset (repository_id, is_recycled, is_pipeline_processed, created_at, id)
  WHERE is_triaged = TRUE;
CREATE INDEX asset_triage_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed, date(original_created_at), original_created_at, id, folder_id
) WHERE is_triaged = TRUE;
-- The undated Sequence candidates: the triaged assets with no capture time, by repository and size in Date Imported order, so an
-- undated import walks its size backwards from the newest and stops at its cap, whatever else triage holds
CREATE INDEX asset_triage_undated ON asset (repository_id, width, height, created_at, id)
  WHERE is_triaged = TRUE AND original_created_at IS NULL;
-- Folder browsing and the folder source of Search text; it also lets an OR of text sources be planned as a multi-index OR
CREATE INDEX asset_folder ON asset (folder_id);
-- Map viewport queries: a bounding-box range over the located assets of a repository only. It carries the ID and the capture
-- time a map cell is ranked by, and the triage flag, so a viewport's own points are read from the index alone.
CREATE INDEX asset_geo ON asset (repository_id, is_recycled, is_pipeline_processed, latitude, longitude, id, original_created_at, is_triaged)
  WHERE latitude IS NOT NULL;
-- The members of a Sequence: their count, their repointing when Sequences merge, and a Curate view's listing
CREATE INDEX asset_sequence ON asset (sequence_id) WHERE sequence_id IS NOT NULL;
-- An account's assets, in every Library: the key from the account, which a delete of an account checks
CREATE INDEX asset_owner ON asset (owner_user_id);
-- The assets an account imported, in every Library, by the ID of their importer record: the rewrite of the records when the
-- account changes (AssetDao.rewriteImporterRecords)
CREATE INDEX asset_importer ON asset (json_extract(original_owner, '$.id'));

CREATE TABLE person_label (
  id INTEGER PRIMARY KEY AUTOINCREMENT
);

CREATE TABLE person (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  -- this is taken from the person_label table, where its primary key is a sequence
  name TEXT NOT NULL,
  name_for_sort TEXT NOT NULL,
  -- One of the person's own Faces, which the code checks (PersonService.setFaceAsCover); the key refuses one that is no Face.
  -- A single column: a composite key's SET NULL would clear the person's repository_id too. Every path that deletes a cover
  -- Face picks another cover first (PersonService.moveCoversOff), so the delete action only backs that up. NULL for a person
  -- with no Face.
  cover_face_id CHAR(36) REFERENCES face (id) ON DELETE SET NULL,
  -- The assets outside the trash on which the person has a face, triaged ones included (what a search for the person finds with triaged items shown), not the face rows
  num_of_faces INT NOT NULL DEFAULT 0,
  is_named TINYINT NOT NULL DEFAULT 0,
  is_hidden TINYINT NOT NULL DEFAULT 0,
  is_bad_match TINYINT NOT NULL DEFAULT 0,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  UNIQUE (id, repository_id),
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
);

-- The partial person indexes cover the live people, the ones every person query reads; a merged-away person keeps its name and
-- its cover face, which another person may take. The predicates are written as the queries write them: SQLite uses a partial
-- index only for a query whose own terms imply it, and `is_deleted = FALSE` does not imply `is_deleted = 0`.
-- person_01 is the name-key index: a named person's name_for_sort is its name key (PersonService.nameKey: NameKey.of of the
-- name, folded in Scala so both engines fold alike), and an unnamed person's is "unknown 0042", so a live person's name is
-- unique under that fold: case in any script, canonical and compatibility forms
CREATE UNIQUE INDEX person_01 ON person (repository_id, name_for_sort)
    WHERE is_deleted = FALSE AND is_bad_match = FALSE;

CREATE UNIQUE INDEX person_02 ON person (cover_face_id)
    WHERE is_deleted = FALSE;

CREATE INDEX person_03 ON person (repository_id, is_bad_match, num_of_faces, is_hidden, is_named, name_for_sort)
    WHERE is_deleted = FALSE;
-- Every person whose cover is a Face, a merged-away one included, for the cover's key when the Face is deleted
CREATE INDEX person_04 ON person (cover_face_id);

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
  -- SHA-256 of the raw crop PNG, 64 lowercase hex characters (Sha256.hex)
  checksum CHAR(64) NOT NULL,
  -- The Frame time of a Face in a Video, where its crop and box were taken from; NULL for a Face in an image.
  frame_time_ms INTEGER,
  -- L2 norm of the raw ArcFace embedding: higher is a more recognizable face. See face.quality.* in reference.conf.
  quality FLOAT NOT NULL,
  -- An enrolled Face can start a Person and is a candidate in the vector search; a match-only Face can only join one.
  is_enrolled BOOLEAN NOT NULL,
  -- How much fine detail the box holds at the measurement scale (ImageMeasurement), comparable only between Faces of one Person
  -- at a similar size; NULL for a Face in a Video, a box too small to say, or an image that could not be measured
  sharpness REAL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY (repository_id, person_id) REFERENCES person (repository_id, id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, asset_id) REFERENCES asset (repository_id, id) ON DELETE CASCADE,
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
  name_key TEXT NOT NULL,
  field_type VARCHAR(255) NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  UNIQUE (id, repository_id),
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX metadata_field_02 ON metadata_field (repository_id, name_key);

CREATE TABLE folder (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  name_key TEXT NOT NULL,
  -- The root folder is its own parent, which its insert satisfies: the key is checked once the row is in. No delete action, as
  -- no folder is ever deleted on its own (a deleted folder is recycled).
  parent_id CHAR(36) NOT NULL,
  is_recycled TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  UNIQUE (id, repository_id),
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, parent_id) REFERENCES folder (repository_id, id)
);

-- A folder's name is unique among its siblings; it also lists a repository's folders
CREATE UNIQUE INDEX folder_02 ON folder (repository_id, parent_id, name_key);
-- A folder's children, live or recycled
CREATE INDEX folder_03 ON folder (parent_id, is_recycled);

CREATE TABLE album (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  name_key TEXT NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  UNIQUE (id, repository_id),
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX album_01 ON album (repository_id, name_key);

CREATE TABLE album_asset (
  repository_id CHAR(36) NOT NULL,
  album_id CHAR(36) NOT NULL,
  asset_id CHAR(36) NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, album_id) REFERENCES album (repository_id, id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, asset_id) REFERENCES asset (repository_id, id) ON DELETE CASCADE
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
  name_key TEXT NOT NULL,
  latitude REAL,
  longitude REAL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  CHECK (kind IN ('category', 'location')),
  CHECK (kind <> 'category' OR (category_id IS NULL AND latitude IS NULL AND longitude IS NULL)),
  CHECK (kind <> 'location' OR (latitude IS NOT NULL AND longitude IS NOT NULL)),
  CHECK (latitude BETWEEN -90 AND 90),
  CHECK (longitude BETWEEN -180 AND 180),
  UNIQUE (id, repository_id),
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, category_id) REFERENCES location (repository_id, id)
);

CREATE UNIQUE INDEX location_01 ON location (repository_id, name_key);
-- A category's Locations: moving them to the top level, and the check that keeps a deleted category from orphaning one
CREATE INDEX location_02 ON location (category_id, repository_id);

CREATE TABLE location_asset (
  repository_id CHAR(36) NOT NULL,
  location_id CHAR(36) NOT NULL,
  asset_id CHAR(36) NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, location_id) REFERENCES location (repository_id, id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, asset_id) REFERENCES asset (repository_id, id) ON DELETE CASCADE
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
  FOREIGN KEY (repository_id, asset_id) REFERENCES asset (repository_id, id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, field_id) REFERENCES metadata_field (repository_id, id) ON DELETE CASCADE
);

-- The purge cascade and clearing an asset's parameters
CREATE INDEX metadata_parameter_01 ON metadata_parameter (asset_id);
-- The cascade from a deleted metadata field
CREATE INDEX metadata_parameter_02 ON metadata_parameter (field_id);

-- id is the declared key the full-text index follows: an implicit rowid may be renumbered by VACUUM
CREATE TABLE search_document (
  id INTEGER PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  asset_id CHAR(36) NOT NULL,
  body TEXT NOT NULL,
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, asset_id) REFERENCES asset (repository_id, id) ON DELETE CASCADE
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

-- An Import: the stored record of one delivery of files to a Library, an upload from a browser or one scan of a Watch folder
-- that found files (ImportService). The five counters are its Import files by outcome, kept by the write that sets a file's
-- outcome, so a listing reads no file.
CREATE TABLE import (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  source TEXT NOT NULL
    CHECK (source IN ('upload', 'watch_folder')),
  -- The uploaded folder's or the Watch folder's name; NULL for an upload of files
  name TEXT,
  -- The Watch folder whose scan this is; NULL for an upload. A plain column until the watch_folder table arrives, which adds
  -- its reference (ON DELETE SET NULL)
  watch_folder_id CHAR(36),
  -- The account that delivered it; NULL once that account is deleted
  account_id CHAR(36) REFERENCES account (id) ON DELETE SET NULL,
  -- The folder its files are sorted into; NULL = Triage
  dest_folder_id CHAR(36),
  -- No more files will be added: the upload closed it, or the scan offered its last file
  is_complete TINYINT NOT NULL DEFAULT 0,
  -- Set once the Import is complete and none of its files is pending; NULL while it is in progress
  closed_at DATETIME,
  pending INT NOT NULL DEFAULT 0 CHECK (pending >= 0),
  imported INT NOT NULL DEFAULT 0 CHECK (imported >= 0),
  duplicate INT NOT NULL DEFAULT 0 CHECK (duplicate >= 0),
  unsupported INT NOT NULL DEFAULT 0 CHECK (unsupported >= 0),
  failed INT NOT NULL DEFAULT 0 CHECK (failed >= 0),
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  UNIQUE (id, repository_id),
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  -- No delete action, as no folder is ever deleted on its own (a deleted folder is recycled)
  FOREIGN KEY (repository_id, dest_folder_id) REFERENCES folder (repository_id, id)
);

-- A Library's Import history by age: its listing, and the prune of the Imports past import.history.days
CREATE INDEX import_01 ON import (repository_id, created_at);

-- An Import file: one file of an Import with its outcome. rel_path is the file's path as it was delivered, its name for an
-- upload of files.
CREATE TABLE import_file (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  import_id CHAR(36) NOT NULL,
  rel_path TEXT NOT NULL,
  outcome TEXT NOT NULL DEFAULT 'pending'
    CHECK (outcome IN ('pending', 'imported', 'duplicate', 'unsupported', 'failed')),
  -- Why the file has its outcome, where there is something to say: where the asset a duplicate matches is, why a file failed
  reason TEXT,
  -- The asset an imported file became, or the asset of the Library a duplicate matches, until that asset is purged; the row
  -- keeps its path after. A single column: a composite key's SET NULL would clear repository_id too, so the code checks that
  -- the asset is the Library's (ImportService.settle).
  asset_id CHAR(36) REFERENCES asset (id) ON DELETE SET NULL,
  size_bytes BIGINT NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, import_id) REFERENCES import (repository_id, id) ON DELETE CASCADE
);

-- An Import's files by outcome, the pending ones a restart fails among them, and the cascade from a pruned Import
CREATE INDEX import_file_01 ON import_file (import_id, outcome);
-- The files that point at an asset, for the key's delete action when the asset is purged
CREATE INDEX import_file_02 ON import_file (asset_id) WHERE asset_id IS NOT NULL;
