# Asset ID storage on PostgreSQL

## Original goals

- A read of `asset` that takes its IDs from the table (`id`, `repository_id`, `owner_user_id`, `checksum`,
  `folder_id`, `sequence_id`) costs no TOAST lookup: those columns are stored in the heap row.
- The metadata, the Histogram and the Scene embeddings stay out of line, so the heap row a search reads stays narrow.
- At a million assets the searches under **Verification** answer within their targets.

Out of scope: SQLite (it has no TOAST), any index change, any change to a statement, and the storage of tables other
than `asset`.

## Status

Not started.

## Confirmed decisions

| Decision | Choice |
|---|---|
| Where the change is made | `../../../altitude/resources/migrations/postgres/all.sql` alone. No versioned migration, no schema version bump; a database is recreated from scratch. |
| Engine | PostgreSQL only. |

Defaults taken, to change on request:

- The six ID and checksum columns get `STORAGE PLAIN`: never compressed, never moved out of line. They are fixed-length
  ASCII, so compression gains nothing.
- `toast_tuple_target = 128` stays, since it is what moves the metadata out of line.
- Every other short column a general read selects (`filename`, the three media type columns,
  `original_created_at_source`) is audited in task A.1 and gets `STORAGE MAIN` if the audit finds it out of line.

## Current behavior

- `asset` is created `WITH (toast_tuple_target = 128)`, so that the `jsonb` metadata, the Histogram and the Scene
  embeddings leave the heap row. `original_owner` is `STORAGE MAIN` for the reason this plan generalizes: every general
  read selects it.
- The fixed-width columns of a row alone exceed 128 bytes, so the target is never met and PostgreSQL moves every
  remaining value it can out of line, the `CHAR(36)` IDs and the `CHAR(64)` checksum included. In a library of 1.03M
  assets every row has `id`, `repository_id`, `owner_user_id` and `checksum` stored externally, and the TOAST table
  holds 7.3M chunks. Rows imported through the app are stored the same way.
- A plan that reads those columns from the table therefore pays a TOAST lookup per row, about four buffer touches to
  read `id` and test `repository_id`. The planner does not cost detoasting, so it keeps choosing such plans.
- Measured at 1M assets (PostgreSQL in its container defaults, `shared_buffers` 128 MB, warm cache):

  | Read | As it is | With the six columns in line |
  |---|---|---|
  | Full scan of `asset` | 2,398 ms | 158 ms |
  | Search of two people, all, each on 60,000 assets: the capped total (a parallel scan of `asset` semi-joined to `face_02`) | 847 ms statement, 937 ms request | 143 ms statement, 125 ms request |
  | Search of two people, any | 202 ms statement | 36 ms |
  | A Date Taken range of half the library within a folder subtree that holds none of it | 1,457 ms statement | 445 ms |
  | A Date Imported range of 72% of the library, sorted by Date Taken | 498 ms statement | 233 ms |
  | A broad Search text (79k hits) alone | 4,671 ms statement, 5.0 s request | 373 ms statement, 0.36 s request |
  | Browse, no filter | 31 ms statement, 34 ms request | 2.5 ms statement, 7.6 ms request |

- Size at 1M assets: the heap is 447 MB and the TOAST table 2,012 MB; with the six columns in line the heap is 608 MB
  and the TOAST table 1,610 MB.

## Design

In `postgres/all.sql`, the `asset` columns `id`, `repository_id`, `owner_user_id`, `checksum`, `folder_id` and
`sequence_id` are declared `STORAGE PLAIN`, in the column definition, as `original_owner` declares `STORAGE MAIN`. The
table comment says which columns stay in the row and why: a search's joins, filters and page reads take them from the
heap. `toast_tuple_target = 128` and the storage of the bulky columns are unchanged.

`asset` inherits from `_core`; a column declared in `_core` and read by a general search (`created_at`, `updated_at`)
is fixed-width and is never moved out of line, so `_core` is unchanged.

No statement, index or DAO changes. SQLite's `all.sql` is untouched.

## Tasks

The server task is red-green-refactor with an integration test.

### A. Storage

1. Audit: in a database with imported assets, list for each `asset` column whether its values are stored externally
   (`pg_column_toast_chunk_id`), and from the DAOs which of the externally stored columns a general read selects.
   The six columns above are the known ones; any other short column the audit finds gets `STORAGE MAIN`.
2. Test (PostgreSQL only, cancelled on SQLite as the other engine-specific tests are): after an import, no value of
   the in-row columns of `asset` has a TOAST chunk, and the metadata of an asset with a large metadata document does.
3. `postgres/all.sql`: the storage clauses and the table comment.
4. Docs: `../../../altitude/AGENTS.md` where it describes `asset`'s storage and `toast_tuple_target`; `docs/test-coverage.md`.

## Verification

- `make test-psql` red before A.3 and green after; `make test-unit`, `make test-sqlite` and `make test-controllers`
  green; then `make lint`.
- A one-off check on a scratch PostgreSQL database of 1M assets, seeded and analyzed, removed afterwards. Medians, warm
  cache, first page with its total:

  | Request | Target |
  |---|---|
  | Two people, all, each on about 60k assets | ≤ 300 ms |
  | Two people, any, each on about 60k assets | ≤ 300 ms |
  | A Date Taken range of half the library within a folder subtree that holds none of it | ≤ 1 s |
  | A broad Search text alone (about 8% of the library) | ≤ 1 s |
  | Browse, no filter | ≤ 50 ms |

- The size of the heap and of the TOAST table before and after, reported with the timings.
