CREATE TABLE system (
  id INT NOT NULL DEFAULT 1,
  version INT NOT NULL,
  is_initialized TINYINT NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX system_01 ON system(id);
INSERT INTO system(version, is_initialized) VALUES(0, 0);

CREATE TABLE account(
  id CHAR(36) PRIMARY KEY,
  email TEXT NOT NULL,
  name TEXT NOT NULL,
  account_type TEXT NOT NULL
               CHECK(account_type IN ('ADMIN','USER','GUEST')),
  password_hash TEXT NOT NULL,
  last_active_repo_id CHAR(36),
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL
);

CREATE TABLE user_token (
  account_id CHAR(36) REFERENCES account(id) ON DELETE CASCADE,
  token TEXT NOT NULL,
  expires_at DATETIME
);

CREATE TABLE repository (
  id CHAR(36) PRIMARY KEY,
  name TEXT NOT NULL,
  owner_account_id CHAR(36) REFERENCES account(id) ON DELETE CASCADE,
  description TEXT,
  root_folder_id CHAR(36) NOT NULL,
  file_store_type VARCHAR NOT NULL,
  file_store_config TEXT NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL
);

CREATE TABLE stats (
  repository_id CHAR(36) NOT NULL,
  dimension VARCHAR(60),
  dim_val INT NOT NULL DEFAULT 0,
  FOREIGN KEY(repository_id) REFERENCES repository(id) ON DELETE CASCADE
);
CREATE INDEX stats_01 ON stats(repository_id);
CREATE UNIQUE INDEX stats_02 ON stats(repository_id, dimension);

CREATE TABLE asset  (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  checksum INT NOT NULL,
  media_type VARCHAR(64) NOT NULL,
  media_subtype VARCHAR(64) NOT NULL,
  mime_type VARCHAR(64) NOT NULL,
  extracted_metadata TEXT,
  raw_metadata TEXT,
  metadata TEXT,
  folder_id CHAR(36),
  filename TEXT NOT NULL,
  size_bytes INT NOT NULL,
  is_recycled TINYINT NOT NULL DEFAULT 0,
  is_triaged TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY(repository_id) REFERENCES repository(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX asset_01 ON asset(repository_id, checksum, is_recycled);

CREATE TABLE person_label (
  id INTEGER PRIMARY KEY AUTOINCREMENT
);

-- See the postgres version for the explanation of the following 10 inserts
INSERT INTO person_label DEFAULT VALUES;
INSERT INTO person_label DEFAULT VALUES;
INSERT INTO person_label DEFAULT VALUES;
INSERT INTO person_label DEFAULT VALUES;
INSERT INTO person_label DEFAULT VALUES;
INSERT INTO person_label DEFAULT VALUES;
INSERT INTO person_label DEFAULT VALUES;
INSERT INTO person_label DEFAULT VALUES;
INSERT INTO person_label DEFAULT VALUES;
INSERT INTO person_label DEFAULT VALUES;

CREATE TABLE person (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  -- this is taken from the person_label table, where its primary key is a sequence
  label INT NOT NULL,
  name TEXT NOT NULL,
  cover_face_id CHAR(36),
  merged_with_ids TEXT,
  merged_into_id CHAR(36) DEFAULT NULL,
  merged_into_label INT DEFAULT NULL,
  num_of_faces INT NOT NULL DEFAULT 0,
  is_hidden TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY(merged_into_id) REFERENCES person(id) ON DELETE CASCADE,
  FOREIGN KEY(repository_id) REFERENCES repository(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX person_01 ON person(repository_id, name);
CREATE UNIQUE INDEX person_02 ON person(cover_face_id);

CREATE TABLE face (
  id CHAR(36) PRIMARY KEY,
  asset_id CHAR(36) NOT NULL,
  x1 INT NOT NULL,
  y1 INT NOT NULL,
  repository_id CHAR(36) NOT NULL,
  person_id CHAR(36) NOT NULL,
  person_label INT NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  detection_score FLOAT NOT NULL,
  embeddings TEXT NOT NULL,
  features TEXT NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  image BLOB NOT NULL,
  display_image BLOB NOT NULL,
  aligned_image BLOB NOT NULL,
  aligned_image_gs BLOB NOT NULL,
  checksum INT NOT NULL,
  FOREIGN KEY(person_id) REFERENCES person(id) ON DELETE CASCADE,
  FOREIGN KEY(asset_id) REFERENCES asset(id) ON DELETE CASCADE,
  FOREIGN KEY(repository_id) REFERENCES repository(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX face_01 ON face(person_id, asset_id);
CREATE UNIQUE INDEX face_02 ON face(repository_id, checksum);

CREATE TABLE metadata_field (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  field_type VARCHAR(255) NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY(repository_id) REFERENCES repository(id) ON DELETE CASCADE
);
CREATE INDEX metadata_field_01 ON metadata_field(repository_id);
CREATE UNIQUE INDEX metadata_field_02 ON metadata_field(repository_id, name_lc);


CREATE TABLE folder (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  parent_id CHAR(36) NOT NULL,
    -- non-recursively calculated
  num_of_assets INTEGER NOT NULL DEFAULT 0 CHECK (num_of_assets >= 0),
    -- non-recursively calculated
  num_of_children INTEGER NOT NULL DEFAULT 0 CHECK(num_of_children >= 0),
  is_recycled TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY(repository_id) REFERENCES repository(id) ON DELETE CASCADE
);
CREATE INDEX folder_01 ON folder(repository_id, parent_id);
CREATE UNIQUE INDEX folder_02 ON folder(repository_id, parent_id, name_lc);

CREATE TABLE search_parameter (
  repository_id CHAR(36) NOT NULL,
  asset_id CHAR(36) NOT NULL,
  field_id CHAR(36) NOT NULL,
  field_value_kw TEXT NULL,
  field_value_num DECIMAL,
  field_value_bool BOOLEAN,
  field_value_dt DATEN,
  FOREIGN KEY(repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  FOREIGN KEY(asset_id) REFERENCES asset(id) ON DELETE CASCADE,
  FOREIGN KEY(field_id) REFERENCES metadata_field(id) ON DELETE CASCADE
);

CREATE VIRTUAL TABLE search_document USING fts4(repository_id, asset_id, body);
