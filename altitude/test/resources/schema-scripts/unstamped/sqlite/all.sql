-- A schema script whose version stamp fails, since its system table has no version column (StartupTests): what it creates must
-- go with the stamp. An FTS5 table and a trigger, as the real schema has.
CREATE TABLE system (
  id INT PRIMARY KEY
);

INSERT INTO system (id) VALUES (1);

CREATE TABLE note (
  id INTEGER PRIMARY KEY,
  body TEXT NOT NULL
);

CREATE INDEX note_01 ON note (body);

CREATE VIRTUAL TABLE note_fts USING fts5 (
  body,
  content = '',
  tokenize = 'unicode61'
);

CREATE TRIGGER note_after_insert AFTER INSERT ON note BEGIN
  INSERT INTO note_fts (rowid, body) VALUES (new.id, new.body);
END;

INSERT INTO note (id, body) VALUES (1, 'a note');
