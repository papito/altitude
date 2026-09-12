# Date grouping query experiment

This report preserves the earlier experiment. The
[implementation plan](search-results-date-grouping.md) records the re-verification
done at implementation time on both engines (index shape, null placement, day-count
shape, planner steering) in its Status section; the timings below are historical
evidence, not completion gates.

## Scope and method

Run during design on 2026-09-06 using the project's cached Xerial SQLite JDBC
3.51.2.0 (SQLite reports 3.51.2), Java 21.0.12, and an isolated in-memory database.
No application database was read or modified. Times are medians of three complete
JDBC result reads after one warm-up; database creation, inserts, index creation,
and `ANALYZE` are excluded. Temporary SQL storage was configured in memory.
The experiment used a single connection without concurrent changes.

The synthetic table has UUID-length text IDs, repository and visibility flags,
both date fields, filename, and size. All rows match one repository and are
processed and non-recycled. There are no full-text, folder, album, person, or
metadata joins, no large asset metadata, no HTTP, and no JSON serialization.
Consequently these measurements support a query design choice; they are not
application response-time guarantees or PostgreSQL results.

Pages contain 50 images. A deep page starts at offset 80,000 of 100,000 or 800,000
of 1,000,000. Ordering is day descending, filename ascending, ID ascending.
A cursor represents the row immediately before that deep page. Its lookup cost
is excluded: normal scrolling receives that key from the preceding response.

Date Taken has 10,000 images on its latest day; the remaining rows span 3,650 days.
Date Imported has 20,000 images on its latest day; the rest are batches of 1,000
spread across 365 days. Filenames repeat at the larger size, exercising ID ties.
The experiment does not cover an entire million-image library on a single day.

## Results

| Images | Grouping | Window counts, first (ms) | Window counts, deep (ms) | Combined counts, first (ms) | Combined counts, deep offset (ms) | Combined counts, deep cursor (ms) | Day index, deep offset (ms) | Day index, deep cursor (ms) |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100,000 | `dateTaken` | 169.43 | 221.22 | 4.44 | 7.69 | 3.33 | 51.79 | 3.57 |
| 100,000 | `dateImported` | 163.49 | 226.12 | 5.39 | 8.49 | 3.52 | 67.70 | 3.87 |
| 1,000,000 | `dateTaken` | 2201.01 | 2836.92 | 33.00 | 77.42 | 31.45 | 817.52 | 31.57 |
| 1,000,000 | `dateImported` | 1662.36 | 2168.52 | 37.67 | 74.59 | 31.59 | 697.62 | 32.47 |

“Combined counts” means a narrow, materialized page plus an independent global
count and a batched count for its distinct days, combined in **one SQL statement**.
“Window counts” means global and per-day window counts over all matching rows
before limiting the result.

The first five timing columns use a specialized index matching the complete
ordering: `(repository_id, is_recycled, is_pipeline_processed, date(field) DESC,
filename ASC, id ASC)`. The last two use the smaller index
`(repository_id, is_recycled, is_pipeline_processed, date(field) DESC, id ASC)`.
Both retain the existing visibility index for the global count. There is one
additional index per date source in each experiment.

With the smaller day index, combined first-page times were 7.11/10.67 ms at
100,000 images and 34.12/38.68 ms at 1 million (Date Taken/Date Imported).
At 1 million, page retrieval alone with the specialized index took 42.84/41.07 ms
using an offset and 0.15/0.36 ms using a cursor. Exact counting still accounts for
most of the combined cursor query's roughly 31–32 ms.

The Date Taken first-page window query with only the existing visibility index
was 174.70 ms at 100,000 images and 1,874.80 ms at 1 million. Adding a matching
order index therefore did not remove the broad window-count cost.

## Representative SQL

The following uses Date Taken; substitute `created_at` for Date Imported in this
SQLite fixture. `:limit` is 50 and `:offset` is the starting row. All predicates
must be identical across page and count branches in the application.

```sql
WITH page AS MATERIALIZED (
  SELECT id, date(original_created_at) AS day, filename
  FROM asset
  WHERE repository_id = :repo
    AND is_recycled = 0 AND is_pipeline_processed = 1
  ORDER BY date(original_created_at) DESC, filename ASC, id ASC
  LIMIT :limit OFFSET :offset
), total AS (
  SELECT count(*) AS n FROM asset
  WHERE repository_id = :repo
    AND is_recycled = 0 AND is_pipeline_processed = 1
), days AS (
  SELECT date(original_created_at) AS day, count(*) AS n
  FROM asset
  WHERE repository_id = :repo
    AND is_recycled = 0 AND is_pipeline_processed = 1
    AND date(original_created_at) IN (SELECT DISTINCT day FROM page)
  GROUP BY date(original_created_at)
)
SELECT p.id, p.day, p.filename, t.n AS total, d.n AS day_total
FROM total t
LEFT JOIN page p ON 1 = 1
LEFT JOIN days d ON d.day = p.day
ORDER BY p.day DESC, p.filename ASC, p.id ASC;
```

For cursor continuation, remove the offset and add the following to the **page
branch only**. The leading day bound helps the planner start at the boundary day.
The comparison below is specific to day descending and filename/ID ascending.

```sql
AND date(original_created_at) <= :last_day
AND (
  date(original_created_at) < :last_day
  OR (filename, id) > (:last_filename, :last_id)
)
```

The window-count alternative is:

```sql
SELECT id, date(original_created_at) AS day, filename,
       count(*) OVER () AS total,
       count(*) OVER (PARTITION BY date(original_created_at)) AS day_total
FROM asset
WHERE repository_id = :repo
  AND is_recycled = 0 AND is_pipeline_processed = 1
ORDER BY date(original_created_at) DESC, filename ASC, id ASC
LIMIT :limit OFFSET :offset;
```

## Fixture reproduction

Use the columns described above, with `id TEXT PRIMARY KEY`, then populate each
size using this SQLite statement (`:n` is 100000 or 1000000):

```sql
WITH RECURSIVE seq(i) AS (
  VALUES (0) UNION ALL SELECT i + 1 FROM seq WHERE i + 1 < :n
)
INSERT INTO asset
SELECT printf('%036d', i), 'repo', 0, 1,
       CASE WHEN i < 10000 THEN '2030-01-01 12:00:00'
            ELSE datetime('2020-01-01', '+' || ((i*37)%3650) || ' days',
                          '+' || (i%86400) || ' seconds') END,
       CASE WHEN i < 20000 THEN '2030-01-02 12:00:00'
            ELSE datetime('2025-01-01', '+' || ((i/1000)%365) || ' days',
                          '+' || (i%86400) || ' seconds') END,
       printf('IMG_%06d.jpg', (i*7919)%100003), 1000+(i*31)%10000000
FROM seq;
```

Run the existing-index window baseline before adding date indexes. Create the
appropriate index for each date field, run `ANALYZE`, and then run the indexed
queries. Run the day-index variant in a fresh database without specialized
filename indexes.

## Checks and interpretation

The experiment asserted that offset and cursor pages had identical IDs and
ordering, and that the combined-count and window-count queries returned identical
page rows and counts. It also checked a page beyond the end: the combined query
retained the overall count through a single row with a null page ID. The DAO must
recognize that sentinel and return an empty page, not an image with a null ID.

`EXPLAIN QUERY PLAN` showed a covering visibility-index scan for the global count,
a range scan for the cursor page, and indexed equality lookups for the returned
days. The outer ordering may use a temporary B-tree, but it orders the page-sized
result. This differs from sorting all matches before page selection.

The recommended starting point is the combined-count statement, cursor
continuation, and the smaller day indexes. Broader filters, opposite/mixed sort
directions, very large individual days, PostgreSQL plans, and end-to-end response
times remain implementation validation work. Additional indexes should follow
those measurements. The implemented query will also fetch one lookahead row to
produce `nextCursor`; these experiments measured 50 rows without that extension.
