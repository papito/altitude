-- A schema script whose version stamp fails, since its system table has no version column (StartupTests): what it creates must
-- go with the stamp. The vector extension and a column of its type, as the real schema has.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE system (
  id INT PRIMARY KEY
);

INSERT INTO system (id) VALUES (1);

CREATE TABLE note (
  id INT PRIMARY KEY,
  body TEXT NOT NULL,
  embedding vector(3)
);

CREATE INDEX note_01 ON note (body);

INSERT INTO note (id, body, embedding) VALUES (1, 'a note', '[1, 0, 0]');
