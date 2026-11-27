-- The script of the version after the current one, beside no script for the version after it (StartupTests): an upgrade two
-- versions ahead must fail before this runs
CREATE TABLE migrated_ahead (
  id INT PRIMARY KEY
);
