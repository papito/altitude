DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;

CREATE EXTENSION IF NOT EXISTS vector;

-- Every ID column (id and every *_id) is CHAR(36) COLLATE "C": byte-wise comparison is about twice as fast as the default
-- collation, and orders IDs as SQLite does.
--
-- A repository's table holds repository_id NOT NULL, and a reference between its rows is a foreign key over
-- (repository_id, <ref>_id), so the database refuses a reference into another repository. Each referenced table has
-- UNIQUE (id, repository_id), led by the ID so it never competes with the indexes that lead with the repository. The exceptions
-- are single columns: a person's cover, an Import file's asset and an Import's Watch folder.

CREATE TABLE _core (
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NULL
);

CREATE TABLE system (
  id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1), -- the one and only row: the key rejects a second, the check pins its id
  version INT NOT NULL,
  is_initialized BOOL NOT NULL DEFAULT FALSE,
  -- The HMAC-SHA256 key of signed content URLs (Base64 of 32 random bytes), stored so a signed URL survives a restart
  signing_key CHAR(44)
);

INSERT INTO system (version, is_initialized) VALUES (1, False);

-- The Settings an Admin tunes, seeded with their defaults; the registry is models/Settings.scala
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
  ('sequence.siglip2.min_similarity', '0.90', '0.90'),
  ('sequence.max_hash_distance', '10', '10'),
  ('curation.blur_ratio', '0.5', '0.5'),
  ('curation.face_quality_gap', '2.0', '2.0'),
  ('curation.clipped_fraction', '0.05', '0.05'),
  ('curation.crushed_fraction', '0.05', '0.05'),
  ('curation.dark_luminance', '0.15', '0.15'),
  ('curation.bright_luminance', '0.75', '0.75'),
  ('curation.luminance_gap', '0.1', '0.1'),
  ('search.describe.min_probability', '0.00009', '0.00009'),
  ('search.describe.max_results', '1000', '1000'),
  ('search.describe.weaker_match_ratio', '0.75', '0.75'),
  ('map.tile.url', 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'),
  ('map.tile.attribution', '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors', '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'),
  ('map.geocoder.enabled', 'false', 'false'),
  ('map.geocoder.url', 'https://nominatim.openstreetmap.org/search', 'https://nominatim.openstreetmap.org/search'),
  ('db.postgres.read_statement_timeout', '30', '30'),
  ('pipeline.shutdown_timeout', '30', '30'),
  ('import.parallelism', '0', '0'),
  ('import.history.days', '90', '90'),
  ('watch_folder.scan_interval', '10', '10');

-- email_key and username_key are name keys (NameKey.of), folded in Scala so both engines fold alike: SQLite's lower() folds
-- ASCII only
CREATE TABLE account (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  email TEXT NOT NULL,
  email_key TEXT NOT NULL,
  username TEXT NOT NULL,
  username_key TEXT NOT NULL,
  name TEXT NOT NULL,
  account_type TEXT NOT NULL
    CHECK (account_type IN ('Admin', 'User', 'Guest')),
  password_hash TEXT NOT NULL,
  -- A deactivated account can do nothing until it is reactivated
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  -- Only an Admin sets it
  can_create_libraries BOOLEAN NOT NULL DEFAULT FALSE,
  -- When on, every Library created later grants the account new_library_permissions (a Permission bitmask)
  grants_new_libraries BOOLEAN NOT NULL DEFAULT FALSE,
  new_library_permissions INT NOT NULL DEFAULT 0 CHECK (new_library_permissions >= 0),
  -- The account that created this one; NULL for the setup Admin, and once the creator is deleted
  created_by_account_id CHAR(36) COLLATE "C" REFERENCES account (id) ON DELETE SET NULL,
  last_login_at TIMESTAMP WITH TIME ZONE,
  last_active_repo_id CHAR(36) COLLATE "C"
) INHERITS (_core);

-- One account per email address and per username, in any case or Unicode form; a login compares the key column
CREATE UNIQUE INDEX account_01 ON account (email_key);
CREATE UNIQUE INDEX account_02 ON account (username_key);

CREATE TABLE repository (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  name TEXT NOT NULL,
  name_key TEXT NOT NULL,
  description TEXT,
  root_folder_id CHAR(36) COLLATE "C" NOT NULL,
  file_store_type VARCHAR NOT NULL,
  file_store_config jsonb
) INHERITS (_core);

-- A Library's name is unique instance-wide under its name key (Repository.nameKey, folded in Scala as account's keys are)
CREATE UNIQUE INDEX repository_01 ON repository (name_key);

-- An account's Permissions in a Library, a Permission bitmask (0 for a Guest, who only looks). An Admin reaches every Library
-- with no row.
CREATE TABLE repository_grant (
  account_id CHAR(36) COLLATE "C" NOT NULL REFERENCES account (id) ON DELETE CASCADE,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  permissions INT NOT NULL DEFAULT 0 CHECK (permissions >= 0),
  -- An account's grants, the lookup of a login and of the gate, and one row per pair
  PRIMARY KEY (account_id, repository_id)
);

-- A Library's grants, and the cascade from a deleted Library
CREATE INDEX repository_grant_01 ON repository_grant (repository_id);

-- Append-only account events (AuditEventType). The actor and target are snapshots of the accounts (id, name, username, email),
-- since accounts are deleted; the actor is NULL for a failed login.
CREATE TABLE audit_event (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  event TEXT NOT NULL,
  actor jsonb,
  target jsonb,
  detail jsonb,
  client_ip TEXT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX audit_event_01 ON audit_event (created_at);

CREATE TABLE stats (
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  dimension VARCHAR(60) NOT NULL,
  dim_val BIGINT NOT NULL DEFAULT 0 CHECK (dim_val >= 0)
);

CREATE UNIQUE INDEX stats_01 ON stats (repository_id, dimension);

CREATE TABLE folder (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  name_key TEXT NOT NULL,
  -- The root folder is its own parent, which its insert satisfies: the key is checked once the row is in. No delete action: a
  -- folder is recycled, never deleted on its own.
  parent_id CHAR(36) COLLATE "C" NOT NULL,
  is_recycled BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (id, repository_id),
  FOREIGN KEY (repository_id, parent_id) REFERENCES folder (repository_id, id)
) INHERITS (_core);

-- A folder's name is unique among its siblings; it also lists a repository's folders
CREATE UNIQUE INDEX folder_02 ON folder (repository_id, parent_id, name_key);
-- A folder's children, live or recycled
CREATE INDEX folder_03 ON folder (parent_id, is_recycled);

-- toast_tuple_target: the jsonb metadata columns move out of line once a row passes 128 bytes, so the heap row a search reads
-- stays narrow
CREATE TABLE asset (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  -- The importer, until deleted, then the deleting Admin. No delete action, so an account with assets cannot be deleted before
  -- that move.
  owner_user_id CHAR(36) COLLATE "C" NOT NULL REFERENCES account (id),
  -- The importer record (AccountSnapshot), written at import, which outlives the account. Every general read selects it, so it
  -- stays in the heap row (STORAGE MAIN) rather than cost each row read a TOAST lookup.
  original_owner jsonb STORAGE MAIN NOT NULL,
  -- SHA-256 of the file, 64 lowercase hex characters (Sha256.hex), compared byte-wise as the IDs are
  checksum CHAR(64) COLLATE "C" NOT NULL,
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
  -- NULL for an asset in no folder: a triaged one
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
  -- How long a Video or an animated GIF plays; NULL for a still image.
  duration_ms BIGINT,
  -- Image quality (ImageQuality): the metrics and the 0 to 100 score of a still image, measured at import. The five are NULL
  -- together for a Video, an animated image, or a still image the pipeline did not measure.
  sharpness DOUBLE PRECISION,
  luminance DOUBLE PRECISION,
  clipped_highlights DOUBLE PRECISION,
  crushed_shadows DOUBLE PRECISION,
  image_quality INT,
  -- The DCT perceptual hash of a still image, 64 bits as a signed integer, compared by Hamming distance; NULL as the five are.
  perceptual_hash BIGINT,
  -- The Histogram of a still image: luminance, red, green and blue, 64 bins each of the pixel fraction, as 1,024 bytes of
  -- little-endian float32; NULL as the five are. Moved out of line; only the read of a Curation set's Histograms selects it.
  histogram BYTEA,
  -- The Scene embeddings of a still image, unit vectors the Sequence rule compares by cosine: DINOv2 ViT-B/14's pooled CLS
  -- token and SigLIP 2 ViT-B/16's pooled image output. NULL for a Video, an animated image, or a still image the model could
  -- not embed. Moved out of line; only the Sequence candidates' statement selects them. A Describe search also ranks by
  -- SigLIP 2's in the database (asset_describe).
  dinov2_embedding vector(768),
  siglip2_embedding vector(768),
  -- The Sequence the image is in while in triage: the id its members share. A Sequence has no table; it exists while some
  -- asset carries its id. NULL for a sorted asset, a Video or an animated image.
  sequence_id CHAR(36) COLLATE "C",
  -- The revision of the edited Preview and Display image, carried in their URLs as a cache key; 0 when there are none, so the
  -- original's are shown
  edit_rev INT NOT NULL DEFAULT 0,
  UNIQUE (id, repository_id),
  -- No delete action: a folder is recycled, never deleted on its own
  FOREIGN KEY (repository_id, folder_id) REFERENCES folder (repository_id, id)
) INHERITS (_core) WITH (toast_tuple_target = 128);

-- One live asset per content in a repository; the recycle bin may hold any number of copies of it
CREATE UNIQUE INDEX asset_01 ON asset (repository_id, checksum) WHERE NOT is_recycled;
-- Capture-day grouping: the camera date, then the raw timestamp, so a day range or day-count probe seeks directly and a
-- grouped, date-sorted page reads in index order. It carries the ID and the folder (a pass testing text membership, a folder or
-- a day reads the index, not the table), the triage flag (the repository view leaves triaged assets out) and the import time
-- (a Date Taken page within a Date Imported range tests the range on the index).
CREATE INDEX asset_search_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed, (original_created_at::date), original_created_at
) INCLUDE (id, folder_id, is_triaged, created_at);
-- The default flat sort, Date Imported, read from the index alone: it carries the ID, the triage flag and the capture time, so
-- a page within a Date Taken range tests the range on the index
CREATE INDEX asset_search_created ON asset (repository_id, is_recycled, is_pipeline_processed, created_at)
  INCLUDE (id, is_triaged, original_created_at);
-- The triage view: only triaged assets, by Date Imported and by capture day, so a small triage set in a large library is read
-- without passing over the library. Neither carries the other date: only the triage view reads them, where a date range tests
-- a triage set, not the library.
CREATE INDEX asset_triage_created ON asset (repository_id, is_recycled, is_pipeline_processed, created_at) INCLUDE (id)
  WHERE is_triaged;
CREATE INDEX asset_triage_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed, (original_created_at::date), original_created_at
) INCLUDE (id, folder_id) WHERE is_triaged;
-- The undated Sequence candidates: triaged assets with no capture time, by size in Date Imported order, so an undated import
-- walks its size backwards from the newest and stops at its cap, whatever else triage holds
CREATE INDEX asset_triage_undated ON asset (repository_id, width, height, created_at, id)
  WHERE is_triaged AND original_created_at IS NULL;
-- Folder browsing and the folder source of Search text
CREATE INDEX asset_folder ON asset (folder_id);
-- Map viewport queries: a bounding-box range over the located assets of a repository only. It carries the ID and the capture
-- time a map cell is ranked by, and the triage flag, so a viewport's own points are read from the index alone.
CREATE INDEX asset_geo ON asset (repository_id, is_recycled, is_pipeline_processed, latitude, longitude)
  INCLUDE (id, original_created_at, is_triaged) WHERE latitude IS NOT NULL;
-- The members of a Sequence: their count, their repointing when Sequences merge, and a Curate view's listing
CREATE INDEX asset_sequence ON asset (sequence_id) WHERE sequence_id IS NOT NULL;
-- An account's assets in every Library, for the account key's check on delete
CREATE INDEX asset_owner ON asset (owner_user_id);
-- The assets an account imported, in every Library, by importer record ID: rewriting the records when the account changes
CREATE INDEX asset_importer ON asset ((original_owner->>'id'));
-- The still images nearest a description, for a Describe search: SigLIP 2 embeddings at half precision, which halves the index;
-- PostgresSearchDialect.rankedByIndex writes the same cast. An asset with no embedding is not in it.
CREATE INDEX asset_describe ON asset USING hnsw ((siglip2_embedding::halfvec(768)) halfvec_cosine_ops);

-- An asset's Edit, its edit version and the edit version its edited images were rendered from. No row until the first save;
-- the original is never written.
CREATE TABLE asset_edit (
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  asset_id CHAR(36) COLLATE "C" NOT NULL PRIMARY KEY,
  -- The Edit (EditRecipe's JSON), the identity once reverted to the original
  recipe jsonb NOT NULL,
  -- 1 at the first save, one more at each save; a save names the one it was built on. Not the recipe's format `version`
  edit_version INT NOT NULL,
  -- The edit version whose edited images are stored; NULL before the first render
  rendered_version INT,
  -- The highest revision of edited images the asset has used, so none is ever used twice (asset.edit_rev)
  last_rev INT NOT NULL DEFAULT 0,
  -- The Edit is not the identity: it changes a pixel
  is_edited BOOLEAN NOT NULL,
  -- The edited size (EditRecipe.editedSize)
  width INT NOT NULL,
  height INT NOT NULL,
  updated_by_account_id CHAR(36) COLLATE "C" REFERENCES account (id) ON DELETE SET NULL,
  FOREIGN KEY (repository_id, asset_id) REFERENCES asset (repository_id, id) ON DELETE CASCADE
) INHERITS (_core);

-- The startup requeue of the renders: the Edits whose edit version is not rendered
CREATE INDEX asset_edit_01 ON asset_edit (repository_id) WHERE rendered_version IS DISTINCT FROM edit_version;
-- The Edits an account saved last, for the key's delete action when the account is deleted
CREATE INDEX asset_edit_02 ON asset_edit (updated_by_account_id) WHERE updated_by_account_id IS NOT NULL;

CREATE SEQUENCE person_label;

CREATE TABLE person (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  -- An unnamed person's is "Unknown <n>", n drawn from person_label
  name TEXT NOT NULL,
  name_for_sort TEXT NOT NULL,
  -- One of the person's own Faces, which the code checks; its key (person_cover_face, after the face table) refuses a non-Face.
  -- NULL for a person with no Face.
  cover_face_id CHAR(36) COLLATE "C",
  -- The assets outside the trash the person has a face on, triaged ones included (what a search for the person finds with
  -- triaged items shown); not the face rows
  num_of_faces INT NOT NULL DEFAULT 0,
  is_named BOOLEAN NOT NULL DEFAULT FALSE,
  is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  is_bad_match BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (id, repository_id)
) INHERITS (_core);

-- The partial person indexes cover the live people, which every person query reads; a merged-away person keeps its name and
-- cover face, which another person may take.
-- person_01: name_for_sort is a named person's name key (PersonService.nameKey, folded in Scala) or "unknown 0042", so a live
-- name is unique under that fold: case in any script, canonical and compatibility forms
CREATE UNIQUE INDEX person_01 ON person (repository_id, name_for_sort)
    WHERE is_deleted = FALSE AND is_bad_match = FALSE;

CREATE UNIQUE INDEX person_02 ON person (cover_face_id)
    WHERE is_deleted = FALSE;

CREATE INDEX person_03 ON person (repository_id, is_bad_match, num_of_faces, is_hidden, is_named, name_for_sort)
    WHERE is_deleted = FALSE;
-- Every person whose cover is a Face, a merged-away one included, for the cover's key when the Face is deleted
CREATE INDEX person_04 ON person (cover_face_id);

CREATE TABLE face (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  asset_id CHAR(36) COLLATE "C" NOT NULL,
  person_id CHAR(36) COLLATE "C" NOT NULL,
  x1 INT NOT NULL,
  y1 INT NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  detection_score FLOAT NOT NULL,
  -- The L2-normalized ArcFace embedding (FaceDetectionService.EMBEDDING_DIMENSIONS)
  features vector(512) NOT NULL,
  -- SHA-256 of the raw crop PNG, 64 lowercase hex characters (Sha256.hex), compared byte-wise as the IDs are
  checksum CHAR(64) COLLATE "C" NOT NULL,
  -- The Frame time of a Face in a Video, where its crop and box were taken from; NULL for a Face in an image.
  frame_time_ms BIGINT,
  -- L2 norm of the raw ArcFace embedding: higher is a more recognizable face. See face.quality.* in reference.conf.
  quality FLOAT NOT NULL,
  -- An enrolled Face can start a Person and is a candidate in the vector search; a match-only Face can only join one.
  is_enrolled BOOLEAN NOT NULL,
  -- How much fine detail the box holds at the measurement scale (ImageMeasurement), comparable only between Faces of one Person
  -- at a similar size; NULL for a Face in a Video, a box too small to say, or an image that could not be measured
  sharpness DOUBLE PRECISION,
  FOREIGN KEY (repository_id, asset_id) REFERENCES asset (repository_id, id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, person_id) REFERENCES person (repository_id, id) ON DELETE CASCADE
) INHERITS (_core);

-- A person's cover, declared here because the face table names the person table. A single column: a composite key's SET NULL
-- would clear the person's repository_id too. Every path that deletes a cover Face picks another cover first, so the delete
-- action only backs that up.
ALTER TABLE person ADD CONSTRAINT person_cover_face FOREIGN KEY (cover_face_id) REFERENCES face (id) ON DELETE SET NULL;

-- A crop is unique within its asset: two assets may share a byte-identical frame (a trimmed video copy, a re-exported photo).
-- Leading with the asset, it serves the purge cascade and the per-asset probes of Search text, which read the person from it.
CREATE UNIQUE INDEX face_01 ON face (asset_id, repository_id, checksum) INCLUDE (person_id);
-- A person's faces, best first; the asset rides along, so the person source of Search text reads the index alone
CREATE INDEX face_02 ON face (person_id, detection_score) INCLUDE (asset_id);
-- The nearest enrolled Faces of a vector, for recognition; a match-only Face is never a candidate. Half precision halves the
-- index; the statements that read it (postgres.FaceDao) write the same cast.
CREATE INDEX face_03 ON face USING hnsw ((features::halfvec(512)) halfvec_cosine_ops) WHERE is_enrolled;

CREATE TABLE metadata_field (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  name_key TEXT NOT NULL,
  field_type VARCHAR(255) NOT NULL,
  UNIQUE (id, repository_id)
) INHERITS (_core);

CREATE UNIQUE INDEX metadata_field_02 ON metadata_field (repository_id, name_key);

CREATE TABLE album (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  name_key TEXT NOT NULL,
  UNIQUE (id, repository_id)
) INHERITS (_core);

CREATE UNIQUE INDEX album_01 ON album (repository_id, name_key);

CREATE TABLE album_asset (
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  album_id CHAR(36) COLLATE "C" NOT NULL,
  asset_id CHAR(36) COLLATE "C" NOT NULL,
  FOREIGN KEY (repository_id, album_id) REFERENCES album (repository_id, id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, asset_id) REFERENCES asset (repository_id, id) ON DELETE CASCADE
) INHERITS (_core);

CREATE UNIQUE INDEX album_asset_01 ON album_asset (album_id, asset_id);
-- An asset's albums, read from the index alone: the purge cascade and the per-asset probes of Search text
CREATE INDEX album_asset_02 ON album_asset (asset_id, album_id);

-- A user-defined place. Categories (kind 'category') are pure containers, one level deep; Locations (kind 'location') are a pin
-- and hold assets through location_asset. Both kinds share one name pool per repository.
CREATE TABLE location (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  -- NULL = top level. No cascade: deleting a Category moves its Locations to the top level first (LocationService).
  category_id CHAR(36) COLLATE "C",
  kind VARCHAR(16) NOT NULL,
  name VARCHAR(255) NOT NULL,
  name_key TEXT NOT NULL,
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  CHECK (kind IN ('category', 'location')),
  CHECK (kind <> 'category' OR (category_id IS NULL AND latitude IS NULL AND longitude IS NULL)),
  CHECK (kind <> 'location' OR (latitude IS NOT NULL AND longitude IS NOT NULL)),
  CHECK (latitude BETWEEN -90 AND 90),
  CHECK (longitude BETWEEN -180 AND 180),
  UNIQUE (id, repository_id),
  FOREIGN KEY (repository_id, category_id) REFERENCES location (repository_id, id)
) INHERITS (_core);

CREATE UNIQUE INDEX location_01 ON location (repository_id, name_key);
-- A category's Locations: moving them to the top level, and the check that keeps a deleted category from orphaning one
CREATE INDEX location_02 ON location (category_id, repository_id);

CREATE TABLE location_asset (
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  location_id CHAR(36) COLLATE "C" NOT NULL,
  asset_id CHAR(36) COLLATE "C" NOT NULL,
  FOREIGN KEY (repository_id, location_id) REFERENCES location (repository_id, id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, asset_id) REFERENCES asset (repository_id, id) ON DELETE CASCADE
) INHERITS (_core);

CREATE UNIQUE INDEX location_asset_01 ON location_asset (location_id, asset_id);
-- An asset's Locations, read from the index alone: the purge cascade and the per-asset probes of Search text
CREATE INDEX location_asset_02 ON location_asset (asset_id, location_id);

CREATE TABLE metadata_parameter (
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  asset_id CHAR(36) COLLATE "C",
  field_id CHAR(36) COLLATE "C",
  field_value_kw TEXT NULL,
  field_value_num DECIMAL,
  field_value_bool BOOLEAN,
  field_value_dt TIMESTAMP WITH TIME ZONE,
  FOREIGN KEY (repository_id, asset_id) REFERENCES asset (repository_id, id) ON DELETE CASCADE,
  FOREIGN KEY (repository_id, field_id) REFERENCES metadata_field (repository_id, id) ON DELETE CASCADE
);

-- The purge cascade and clearing an asset's parameters
CREATE INDEX metadata_parameter_01 ON metadata_parameter (asset_id);
-- The cascade from a deleted metadata field
CREATE INDEX metadata_parameter_02 ON metadata_parameter (field_id);

-- body holds words already split by the application, so the vector takes them as they are: no stemming, no stop words
CREATE TABLE search_document (
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  asset_id CHAR(36) COLLATE "C" NOT NULL,
  body TEXT NOT NULL,
  tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', body)) STORED,
  FOREIGN KEY (repository_id, asset_id) REFERENCES asset (repository_id, id) ON DELETE CASCADE
);

-- An asset has one document. Leading with the asset, it serves the purge cascade and the per-asset probes of Search text; the
-- upsert's ON CONFLICT (repository_id, asset_id) still finds it, since a conflict target names a set of columns.
CREATE UNIQUE INDEX search_document_01 ON search_document (asset_id, repository_id);
CREATE INDEX search_document_02 ON search_document USING gin (tsv);

-- A directory on the server scanned for one Library, importing the files that arrive in it. Files are only copied out of it,
-- and deleted from it when it deletes imported files.
CREATE TABLE watch_folder (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  -- The absolute path as entered. Unique in the instance; the code also refuses an overlap by real path, in any Library
  path TEXT NOT NULL UNIQUE,
  -- The folder its files go into, the directory's tree mirrored under it; NULL = Triage
  dest_folder_id CHAR(36) COLLATE "C",
  -- Whether a file is deleted from the directory once it is imported, or is a duplicate of an asset of the Library
  deletes_imported BOOLEAN NOT NULL DEFAULT FALSE,
  -- Paused by an Admin, or because its owner was deactivated or is no Admin any more
  is_paused BOOLEAN NOT NULL DEFAULT FALSE,
  -- The Admin who added or last edited it, who owns what it imports. No delete action: a deleted Admin's Watch folders pass
  -- to the deleting Admin first, as its assets do
  account_id CHAR(36) COLLATE "C" NOT NULL REFERENCES account (id),
  -- Why it is paused, when its owner is why; otherwise why its directory is unavailable, as its last scan found it
  status_reason TEXT,
  -- When its directory was last walked; NULL until the first scan that finds it available
  last_scan_at TIMESTAMP WITH TIME ZONE,
  UNIQUE (id, repository_id),
  -- No delete action: a folder is recycled, never deleted on its own
  FOREIGN KEY (repository_id, dest_folder_id) REFERENCES folder (repository_id, id)
) INHERITS (_core);

-- A Library's Watch folders: its list, and the cascade from a deleted Library
CREATE INDEX watch_folder_01 ON watch_folder (repository_id);

-- A Watch folder's memory of a file, by its path: what the last scan saw and what became of it. A file is read once a scan
-- finds the size and modified time a scan saw at least 5 seconds before, and not again while they stay.
CREATE TABLE watch_folder_file (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  watch_folder_id CHAR(36) COLLATE "C" NOT NULL,
  -- The file's path under the watched directory, its names separated by "/"
  rel_path TEXT NOT NULL,
  size_bytes BIGINT NOT NULL,
  -- The file's modified time in milliseconds since the epoch: compared for equality, never shown
  modified_at BIGINT NOT NULL,
  -- seen: not read yet, or changed since; handled: imported, or a duplicate of an asset of the Library; final: unsupported or
  -- undecodable, until the file changes; retry: failed, to be read again; parked: failed `attempts` times, left alone
  state TEXT NOT NULL DEFAULT 'seen'
    CHECK (state IN ('seen', 'handled', 'final', 'retry', 'parked')),
  attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  -- When a scan last found the file, to the day (a scan moves it once it is a day old); an absent file is forgotten once this
  -- passes the retention period. For a file not read yet, when it was first seen as it is, from which its rest is counted
  last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
  FOREIGN KEY (repository_id, watch_folder_id) REFERENCES watch_folder (repository_id, id) ON DELETE CASCADE
) INHERITS (_core);

-- A Watch folder's memory, which a scan reads whole, and a file of it by its path; the cascade from a removed Watch folder
CREATE UNIQUE INDEX watch_folder_file_01 ON watch_folder_file (watch_folder_id, rel_path);

-- One delivery of files to a Library: a browser upload, or a Watch folder scan that found files. The five counters count its
-- files by outcome, kept by the write that sets an outcome, so a listing reads no file.
CREATE TABLE import (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  source TEXT NOT NULL
    CHECK (source IN ('upload', 'watch_folder')),
  -- The uploaded folder's or the Watch folder's name; NULL for an upload of files
  name TEXT,
  -- NULL for an upload, and once the Watch folder is removed (its Imports stay in the history). A single column: a composite
  -- key's SET NULL would clear repository_id too, so the code checks that the Watch folder is the Library's.
  watch_folder_id CHAR(36) COLLATE "C" REFERENCES watch_folder (id) ON DELETE SET NULL,
  -- The account that delivered it; NULL once that account is deleted
  account_id CHAR(36) COLLATE "C" REFERENCES account (id) ON DELETE SET NULL,
  -- The folder its files are sorted into; NULL = Triage
  dest_folder_id CHAR(36) COLLATE "C",
  -- The files the upload or scan announced; NULL when not told. Read only while it takes files, to show those yet to come
  expected_files INT CHECK (expected_files >= 0),
  -- No more files will be added: the upload closed it, or the scan offered its last file
  is_complete BOOLEAN NOT NULL DEFAULT FALSE,
  -- Set once the Import is complete and none of its files is pending; NULL while it is in progress
  closed_at TIMESTAMP WITH TIME ZONE,
  pending INT NOT NULL DEFAULT 0 CHECK (pending >= 0),
  imported INT NOT NULL DEFAULT 0 CHECK (imported >= 0),
  duplicate INT NOT NULL DEFAULT 0 CHECK (duplicate >= 0),
  unsupported INT NOT NULL DEFAULT 0 CHECK (unsupported >= 0),
  failed INT NOT NULL DEFAULT 0 CHECK (failed >= 0),
  UNIQUE (id, repository_id),
  -- No delete action: a folder is recycled, never deleted on its own
  FOREIGN KEY (repository_id, dest_folder_id) REFERENCES folder (repository_id, id)
) INHERITS (_core);

-- A Library's Import history by age: the closed Imports of its listing, its count, and the prune past import.history.days
CREATE INDEX import_01 ON import (repository_id, created_at);
-- A Watch folder's Imports: the counts its card shows, and the key's delete action when it is removed
CREATE INDEX import_02 ON import (watch_folder_id) WHERE watch_folder_id IS NOT NULL;
-- A Library's Imports in progress, read without the history: few, they head the listing, and a start closes them
-- (ImportDao.closeInterrupted)
CREATE INDEX import_03 ON import (repository_id, created_at) WHERE closed_at IS NULL;

-- An Import file: one file of an Import with its outcome. rel_path is the file's path as it was delivered, its name for an
-- upload of files.
CREATE TABLE import_file (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  import_id CHAR(36) COLLATE "C" NOT NULL,
  rel_path TEXT NOT NULL,
  outcome TEXT NOT NULL DEFAULT 'pending'
    CHECK (outcome IN ('pending', 'imported', 'duplicate', 'unsupported', 'failed')),
  -- Why the file has its outcome, where there is something to say: where the asset a duplicate matches is, why a file failed
  reason TEXT,
  -- The asset an imported file became, or a duplicate matches, until that asset is purged. A single column: a composite key's
  -- SET NULL would clear repository_id too, so the code checks that the asset is the Library's.
  asset_id CHAR(36) COLLATE "C" REFERENCES asset (id) ON DELETE SET NULL,
  size_bytes BIGINT NOT NULL,
  FOREIGN KEY (repository_id, import_id) REFERENCES import (repository_id, id) ON DELETE CASCADE
) INHERITS (_core);

-- An Import's files by outcome, the pending ones a restart fails among them, and the cascade from a pruned Import
CREATE INDEX import_file_01 ON import_file (import_id, outcome);
-- The files that point at an asset, for the key's delete action when the asset is purged
CREATE INDEX import_file_02 ON import_file (asset_id) WHERE asset_id IS NOT NULL;
