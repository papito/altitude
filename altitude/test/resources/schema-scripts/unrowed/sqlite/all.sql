-- A schema script that seeds no system row (StartupTests): the version stamp writes no row, which fails the migration, so what
-- the script creates goes with the stamp.
CREATE TABLE system (
  id INT PRIMARY KEY,
  version INT NOT NULL
);

CREATE TABLE note (
  id INT PRIMARY KEY,
  body TEXT NOT NULL
);
