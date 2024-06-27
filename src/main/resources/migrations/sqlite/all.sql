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
  account_type TEXT NOT NULL
               CHECK(account_type IN ('ADMIN','USER','GUEST')),
  password_hash TEXT NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL
);

CREATE TABLE repository(
  id CHAR(36) PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  owner_account_id CHAR(36) REFERENCES account(id) NOT NULL,
  description TEXT,
  root_folder_id CHAR(36) NOT NULL,
  triage_folder_id CHAR(36) NOT NULL,
  file_store_type VARCHAR NOT NULL,
  file_store_config TEXT NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL
);

CREATE TABLE stats (
  repository_id CHAR(36) NOT NULL,
  dimension VARCHAR(60),
  dim_val INT NOT NULL DEFAULT 0,
  FOREIGN KEY(repository_id) REFERENCES repository(id)
);
CREATE INDEX stats_01 ON stats(repository_id);
CREATE UNIQUE INDEX stats_02 ON stats(repository_id, dimension);

CREATE TABLE asset  (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  checksum VARCHAR(64) NOT NULL,
  media_type VARCHAR(64) NOT NULL,
  media_subtype VARCHAR(64) NOT NULL,
  mime_type VARCHAR(64) NOT NULL,
  extracted_metadata TEXT,
  raw_metadata TEXT,
  metadata TEXT,
  folder_id CHAR(36) NOT NULL,
  filename TEXT NOT NULL,
  size_bytes INT NOT NULL,
  is_recycled TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY(repository_id) REFERENCES repository(id),
  FOREIGN KEY(user_id) REFERENCES account(id)
);
CREATE UNIQUE INDEX asset_01 ON asset(repository_id, checksum, is_recycled);
CREATE UNIQUE INDEX asset_02 ON asset(repository_id, folder_id, filename, is_recycled);

CREATE TABLE metadata_field (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  field_type VARCHAR(255) NOT NULL,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY(repository_id) REFERENCES repository(id)
);
CREATE INDEX metadata_field_01 ON metadata_field(repository_id);
CREATE UNIQUE INDEX metadata_field_02 ON metadata_field(repository_id, name_lc);


CREATE TABLE folder (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  parent_id CHAR(36) NOT NULL,
  num_of_assets INTEGER NOT NULL DEFAULT 0,
  is_recycled TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT (datetime('now', 'utc')),
  updated_at DATETIME DEFAULT NULL,
  FOREIGN KEY(repository_id) REFERENCES repository(id)
);
CREATE INDEX folder_01 ON folder(repository_id, parent_id);
CREATE UNIQUE INDEX folder_02 ON folder(repository_id, parent_id, name_lc);
CREATE UNIQUE INDEX folder_03 ON folder(repository_id, parent_id, name_lc);

CREATE TABLE search_parameter (
  repository_id CHAR(36) NOT NULL,
  asset_id CHAR(36) NOT NULL,
  field_id CHAR(36) NOT NULL,
  field_value_kw TEXT NULL,
  field_value_num DECIMAL,
  field_value_bool BOOLEAN,
  field_value_dt DATEN,
  FOREIGN KEY(repository_id) REFERENCES repository(id),
  FOREIGN KEY(asset_id) REFERENCES asset(id),
  FOREIGN KEY(field_id) REFERENCES metadata_field(id)
);
CREATE INDEX search_parameter_01 ON search_parameter(repository_id, field_id, field_value_kw);
CREATE INDEX search_parameter_02 ON search_parameter(repository_id, field_id, field_value_num);
CREATE INDEX search_parameter_03 ON search_parameter(repository_id, field_id, field_value_bool);
CREATE INDEX search_parameter_04 ON search_parameter(repository_id, field_id, field_value_dt);

CREATE VIRTUAL TABLE search_document USING fts4(repository_id, asset_id, body);
