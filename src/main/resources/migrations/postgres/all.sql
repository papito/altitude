DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;

CREATE TABLE _core (
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'utc'),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NULL
);

CREATE TABLE system (
  id INT NOT NULL DEFAULT 1 CHECK (id = 1), -- this is the ONLY ID in the system table
  version INT NOT NULL,
  is_initialized BOOL NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX system_01 ON system(id);
INSERT INTO system(version, is_initialized) VALUES(1, False);

CREATE TABLE account(
  id CHAR(36) PRIMARY KEY,
  email TEXT NOT NULL,
  name TEXT NOT NULL,
  account_type TEXT NOT NULL
               CHECK(account_type IN ('ADMIN','USER','GUEST')),
  password_hash TEXT NOT NULL,
  last_active_repo_id CHAR(36)
) INHERITS (_core);

CREATE TABLE user_token (
  account_id CHAR(36) REFERENCES account(id) ON DELETE CASCADE,
  token TEXT NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE repository(
  id CHAR(36) PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT,
  owner_account_id CHAR(36) REFERENCES account(id) ON DELETE CASCADE,
  root_folder_id CHAR(36) NOT NULL,
  file_store_type VARCHAR NOT NULL,
  file_store_config jsonb
) INHERITS (_core);

CREATE TABLE stats (
  repository_id CHAR(36) REFERENCES repository(id) ON DELETE CASCADE,
  dimension VARCHAR(60),
  dim_val INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX stats_01 ON stats(repository_id, dimension);

CREATE TABLE asset (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) REFERENCES repository(id) ON DELETE CASCADE,
  user_id CHAR(36) REFERENCES account(id) ON DELETE CASCADE,
  checksum INT NOT NULL,
  media_type VARCHAR(64) NOT NULL,
  media_subtype VARCHAR(64) NOT NULL,
  mime_type VARCHAR(64) NOT NULL,
  user_metadata jsonb,
  public_metadata jsonb,
  extracted_metadata jsonb,
  folder_id CHAR(36),
  filename TEXT NOT NULL,
  size_bytes INT NOT NULL,
  is_triaged BOOLEAN NOT NULL DEFAULT FALSE,
  is_recycled BOOLEAN NOT NULL DEFAULT FALSE,
  is_pipeline_processed BOOLEAN NOT NULL DEFAULT FALSE,
  is_in_face_rec_model BOOLEAN NOT NULL DEFAULT FALSE

) INHERITS (_core);
CREATE UNIQUE INDEX asset_01 ON asset(repository_id, checksum, is_recycled);
CREATE INDEX asset_02 on asset(is_pipeline_processed);
CREATE INDEX asset_03 on asset(is_in_face_rec_model);


CREATE SEQUENCE person_label;

-- The first 10 machine learning labels are reserved for the system:
--   1-2: Initial dummy image data because the minimal training set it 2
SELECT nextval('person_label');
SELECT nextval('person_label');
SELECT nextval('person_label');
SELECT nextval('person_label');
SELECT nextval('person_label');
SELECT nextval('person_label');
SELECT nextval('person_label');
SELECT nextval('person_label');
SELECT nextval('person_label');
SELECT nextval('person_label');

CREATE TABLE person (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) REFERENCES repository(id) ON DELETE CASCADE,
  -- this is taken from the person_label table, where its primary key is a sequence
  label BIGINT NOT NULL,
  name TEXT NOT NULL,
  cover_face_id CHAR(36),
  merged_with_ids TEXT,
  merged_into_id CHAR(36) DEFAULT NULL REFERENCES person(id) ON DELETE CASCADE,
  merged_into_label BIGINT DEFAULT NULL,
  num_of_faces INT NOT NULL DEFAULT 0,
  is_hidden BOOLEAN NOT NULL DEFAULT FALSE
) INHERITS (_core);
CREATE UNIQUE INDEX person_01 ON person(repository_id, name);
CREATE UNIQUE INDEX person_02 ON person(cover_face_id);


CREATE TABLE face (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
  asset_id CHAR(36) NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
  person_id CHAR(36) NOT NULL REFERENCES person(id) ON DELETE CASCADE,
  person_label BIGINT NOT NULL,
  x1 INT NOT NULL,
  y1 INT NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  detection_score FLOAT NOT NULL,
  embeddings TEXT NOT NULL,
  features TEXT NOT NULL,
  checksum INT NOT NULL
) INHERITS (_core);
CREATE UNIQUE INDEX face_01 ON face(person_id, asset_id);
CREATE UNIQUE INDEX face_02 ON face(repository_id, checksum);


CREATE TABLE metadata_field (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) REFERENCES repository(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  field_type VARCHAR(255) NOT NULL
) INHERITS (_core);
CREATE INDEX metadata_field_01 ON metadata_field(repository_id);
CREATE UNIQUE INDEX metadata_field_02 ON metadata_field(repository_id, name_lc);

CREATE TABLE folder (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) REFERENCES repository(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  parent_id CHAR(36) NOT NULL,
    -- non-recursively calculated
  num_of_assets INTEGER NOT NULL DEFAULT 0 CHECK (num_of_assets >= 0),
    -- non-recursively calculated
  num_of_children INTEGER NOT NULL DEFAULT 0 CHECK(num_of_children >= 0),
  is_recycled BOOLEAN NOT NULL DEFAULT FALSE
) INHERITS (_core);
CREATE INDEX folder_01 ON folder(repository_id, parent_id);
CREATE UNIQUE INDEX folder_02 ON folder(repository_id, parent_id, name_lc);


CREATE TABLE search_parameter (
  repository_id CHAR(36) REFERENCES repository(id),
  asset_id CHAR(36) REFERENCES asset(id) ON DELETE CASCADE,
  field_id CHAR(36) REFERENCES metadata_field(id) ON DELETE CASCADE,
  field_value_kw TEXT NULL,
  field_value_num DECIMAL,
  field_value_bool BOOLEAN,
  field_value_dt TIMESTAMP WITH TIME ZONE
);

CREATE TABLE search_document (
  repository_id CHAR(36) REFERENCES repository(id) ON DELETE CASCADE,
  asset_id CHAR(36) REFERENCES asset(id) ON DELETE CASCADE,
  metadata_values TEXT NOT NULL,
  body TEXT NOT NULL,
  tsv TSVECTOR NOT NULL
);
CREATE UNIQUE INDEX search_document_01 ON search_document(repository_id, asset_id);
CREATE INDEX search_document_02 ON search_document USING gin(tsv);

CREATE FUNCTION update_search_document_rank() RETURNS trigger AS $$
begin
new.tsv :=
setweight(to_tsvector('pg_catalog.english', new.metadata_values), 'A') ||
setweight(to_tsvector('pg_catalog.english', new.body), 'B');
return new;
end
$$ LANGUAGE plpgsql;

CREATE TRIGGER search_document_trigger BEFORE INSERT OR UPDATE
ON search_document
FOR EACH ROW
  EXECUTE PROCEDURE update_search_document_rank();
