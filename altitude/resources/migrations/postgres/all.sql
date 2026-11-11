DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;

CREATE EXTENSION IF NOT EXISTS vector;

-- Every ID column (id and every *_id, the foreign keys included) is CHAR(36) COLLATE "C": IDs are compared, sorted and grouped
-- byte-wise, which is about twice as fast as under the database's default collation and orders them the same on both engines.
--
-- A row of a repository's table holds its repository (repository_id NOT NULL), and a reference from one such row to another is
-- a foreign key over (repository_id, <ref>_id), so the database refuses a reference into another repository. Each table such a
-- key names has UNIQUE (id, repository_id) for it, led by the ID so that it never competes with the indexes that lead with the
-- repository. A NULL reference is not checked. A person's cover is the one reference that is not such a key: a single column,
-- at the person table.

CREATE TABLE _core (
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'utc'),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NULL
);

CREATE TABLE system (
  id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1), -- the one and only row: the key rejects a second, the check pins its id
  version INT NOT NULL,
  is_initialized BOOL NOT NULL DEFAULT FALSE,
  -- The HMAC-SHA256 key of signed content URLs, the Base64 of 32 random bytes, generated on first start
  -- (SystemService.loadOrCreateSigningKey) so a signed URL survives a restart
  signing_key CHAR(44)
);

INSERT INTO system (version, is_initialized) VALUES (1, False);

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
  ('import.parallelism', '0', '0');

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
  name_key TEXT NOT NULL,
  description TEXT,
  owner_account_id CHAR(36) COLLATE "C" REFERENCES account (id) ON DELETE CASCADE,
  root_folder_id CHAR(36) COLLATE "C" NOT NULL,
  file_store_type VARCHAR NOT NULL,
  file_store_config jsonb
) INHERITS (_core);

-- repository_01: a Library's name is unique instance-wide, across owners, under its name key (Repository.nameKey: NameKey.of
-- of the normalized name, folded in Scala, so both engines fold alike, where SQLite's lower() folds ASCII only)
CREATE UNIQUE INDEX repository_01 ON repository (name_key);

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
  -- The root folder is its own parent, which its insert satisfies: the key is checked once the row is in. No delete action, as
  -- no folder is ever deleted on its own (a deleted folder is recycled).
  parent_id CHAR(36) COLLATE "C" NOT NULL,
  is_recycled BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (id, repository_id),
  FOREIGN KEY (repository_id, parent_id) REFERENCES folder (repository_id, id)
) INHERITS (_core);

-- A folder's name is unique among its siblings; it also lists a repository's folders
CREATE UNIQUE INDEX folder_02 ON folder (repository_id, parent_id, name_key);
-- A folder's children, live or recycled
CREATE INDEX folder_03 ON folder (parent_id, is_recycled);

-- toast_tuple_target: the jsonb metadata columns are moved out of line once a row passes 128 bytes, so the heap row a search
-- reads stays narrow
CREATE TABLE asset (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  user_id CHAR(36) COLLATE "C",
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
  -- The Histogram of a still image (Histogram): its luminance, red, green and blue channels, each 64 bins of the fraction of
  -- the pixels in the bin, as 1,024 bytes of little-endian float32; NULL as the five are. Bulky, which toast_tuple_target
  -- (below) moves out of line; selected by the read of a Curation set's Histograms alone and transferred by no other read.
  histogram BYTEA,
  -- The Scene embeddings of a still image, one per Scene model, each a unit vector the Sequence rule compares by cosine
  -- similarity: DINOv2 ViT-B/14's pooled CLS token and CLIP ViT-B/16's projected image embedding. NULL for a Video, an animated
  -- image, or a still image the model could not embed. Bulky, which toast_tuple_target (below) moves out of line; selected by
  -- the Sequence candidates' statement alone and transferred by no other read.
  dinov2_embedding vector(768),
  clip_embedding vector(512),
  -- The Sequence the image is in while in triage: the id its members share. A Sequence has no table; it exists while some
  -- asset carries its id. NULL for a sorted asset, a Video or an animated image.
  sequence_id CHAR(36) COLLATE "C",
  UNIQUE (id, repository_id),
  -- No delete action, as no folder is ever deleted on its own (a deleted folder is recycled)
  FOREIGN KEY (repository_id, folder_id) REFERENCES folder (repository_id, id)
) INHERITS (_core) WITH (toast_tuple_target = 128);

-- One live asset per content in a repository; the recycle bin may hold any number of copies of it
CREATE UNIQUE INDEX asset_01 ON asset (repository_id, checksum) WHERE NOT is_recycled;
-- Capture-day grouping for search results: the camera's calendar date followed by the raw timestamp, so a day range or a
-- day-count probe seeks directly and a grouped, date-sorted page reads in index order. It carries the ID and the folder, so a
-- pass over the library that tests text membership, a folder or a day reads the index and not the table. It carries the triage
-- flag for the repository view, which leaves triaged assets out by default.
CREATE INDEX asset_search_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed, (original_created_at::date), original_created_at
) INCLUDE (id, folder_id, is_triaged);
-- The default flat sort, Date Imported, as an ordered read of the page slice that needs nothing but the index: it carries the ID
-- and the triage flag
CREATE INDEX asset_search_created ON asset (repository_id, is_recycled, is_pipeline_processed, created_at) INCLUDE (id, is_triaged);
-- The triage view: only the triaged assets, in Date Imported order and by capture day, so a small triage set in a large library
-- is read without passing over the library. They shrink as triage is sorted.
CREATE INDEX asset_triage_created ON asset (repository_id, is_recycled, is_pipeline_processed, created_at) INCLUDE (id)
  WHERE is_triaged;
CREATE INDEX asset_triage_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed, (original_created_at::date), original_created_at
) INCLUDE (id, folder_id) WHERE is_triaged;
-- The undated Sequence candidates: the triaged assets with no capture time, by repository and size in Date Imported order, so an
-- undated import walks its size backwards from the newest and stops at its cap, whatever else triage holds
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

CREATE SEQUENCE person_label;

CREATE TABLE person (
  id CHAR(36) COLLATE "C" PRIMARY KEY,
  repository_id CHAR(36) COLLATE "C" NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  -- this is taken from the person_label table, where its primary key is a sequence
  name TEXT NOT NULL,
  name_for_sort TEXT NOT NULL,
  -- One of the person's own Faces, which the code checks (PersonService.setFaceAsCover); its key (person_cover_face, below the
  -- face table) refuses one that is no Face. NULL for a person with no Face.
  cover_face_id CHAR(36) COLLATE "C",
  -- The assets outside the trash on which the person has a face, triaged ones included (what a search for the person finds with triaged items shown), not the face rows
  num_of_faces INT NOT NULL DEFAULT 0,
  is_named BOOLEAN NOT NULL DEFAULT FALSE,
  is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  is_bad_match BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (id, repository_id)
) INHERITS (_core);

-- The partial person indexes cover the live people, the ones every person query reads; a merged-away person keeps its name and
-- its cover face, which another person may take.
-- person_01 is the name-key index: a named person's name_for_sort is its name key (PersonService.nameKey, folded in Scala
-- so both engines fold alike), and an unnamed person's is "unknown 0042", so a live person's name is unique under that fold:
-- lowercased NFC, which is not a full Unicode case fold
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
-- would clear the person's repository_id too. Every path that deletes a cover Face picks another cover first
-- (PersonService.moveCoversOff), so the delete action only backs that up.
ALTER TABLE person ADD CONSTRAINT person_cover_face FOREIGN KEY (cover_face_id) REFERENCES face (id) ON DELETE SET NULL;

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
