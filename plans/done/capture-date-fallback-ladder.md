# Capture date: a metadata ladder, and an honest "No date"

## Status

**Revised 2026-09-07** after review against `feature/grouping` @ `c084d086`, before any implementation.
Every file and line the first draft cited was re-read; the factual claims held, and the corrections
found are folded in below (three fabricated-fallback call sites, not two; the group header lives in
`views/htmx/results_grid_grouped.scala.html`; the column is `NOT NULL` today on both engines; a test
ordering hazard between Units 3 and 4; the `CaptureDateSource` codec is not a copy of `AccountType`'s;
`DSCF1160.JPG` also carries XMP; fixture paths take an `images/` prefix).

Two design decisions changed on review:

1. **Null ordering defers to the engine.** The first draft handled a null capture date with two partial
   indexes, a section-aware cursor and two-phase "never straddle" paging. That is replaced by *native
   null placement*: plain `ORDER BY`, nulls land where each engine puts them, index shape unchanged.
   When grouping by Date Taken the undated rows form a **"No date" group** at the engine's native
   position (Unit 3). The alternative of ordering undated rows by import time through `COALESCE` was
   considered and rejected (see *Alternatives considered*).
2. **A "no date" badge** on result cells, like the triage badge, shown when the sort is by capture
   date (new Unit 7).

Nothing is implemented yet. Units are ordered so each is independently verifiable and the schema
change (Unit 3) lands before the fallback is deleted (Unit 4).

## Context

`asset.original_created_at` is the "Date Taken" the whole grouping feature is built on
(`GroupBy.DateTaken`, the `asset_search_date_taken` indexes on both engines, the sort
dropdown, the search cursor). Today the entire derivation is one string lookup, one
format, one fallback:

1. `MetadataExtractionService.extract` flattens every drewnoakes directory/tag into
   `Map[dirName, Map[tagName, String]]` using `tag.getDescription`.
2. `Asset.getPublicMetadata` (`models/Asset.scala:27`) reads exactly one key:
   `getFieldValues("Exif SubIFD").get("Date/Time Original")`.
3. The date itself is derived **inside the JDBC insert**, `dao/jdbc/AssetDao.scala:95-106`:
   parse with `DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")`, and on a parse failure, any
   other exception, or `None`, `LocalDateTime.now()` (three call sites: `:102`, `:105`, `:106`).

Consequences today:

| # | Defect | Effect |
|---|---|---|
| 1 | Derivation lives in the DAO | Untestable without a live DB on both engines; `add` returns `asset.copy(id = ...)` so the computed date is **discarded** and every caller must `getById` (both capture-date tests in `AssetImportServiceTests` do exactly that) |
| 2 | One tag only | A PNG, a screenshot, a messenger re-encode, a scan, an edited file whose date sits in IFD0/XMP/IPTC — all silently get *the upload moment* as their capture date |
| 3 | Strict single format | Subseconds, dash separators, missing seconds, date-only all fail. The EXIF all-zero sentinel and dead-clock epochs (1970-01-01) are not guarded and would poison date grouping |
| 4 | **The import-time fallback itself** | When the ladder finds nothing there is *zero information* about capture time. Import time is not a weak estimate of the same quantity — it is a different quantity. Storing it collapses Date Taken into Date Imported for exactly the assets where the distinction matters, dumps a whole archive import into one false day group, and renders as a confident precise timestamp in the grid |
| 5 | No provenance | A real EXIF date and a speculative guess are indistinguishable, forever |
| 6 | `publicMetadata` is an input channel | Any caller constructing an `Asset` can set `dateTimeOriginal` and change what is stored |

The earlier grouping work (`plans/done/search-results-date-grouping.md:60`) deliberately
deferred all of this: *"Do not reconstruct missing capture dates or introduce an
unknown-capture-date feature."* This plan revisits both halves of that deferral.

**Intended outcome:** capture dates come from an ordered ladder of metadata sources
resolved by a pure, unit-testable function outside the persistence layer; when the ladder
finds nothing the column is **NULL**; an undated asset sorts wherever the engine natively
places a null, forms a **"No date"** group when results are grouped by Date Taken, and wears a
**"no date"** badge when results are sorted by capture date; and every date that exists
records which rung produced it.

## Two blockers found in the extractor (verified against metadata-extractor 2.19.0 sources)

`metadata-extractor` is pinned at 2.19.0 in `build.mill:73`.

**A. XMP is not in `extracted_metadata` at all.** `XmpDirectory`'s `_tagNameMap` holds
exactly one entry, `TAG_XMP_VALUE_COUNT -> "XMP Value Count"`. The real properties live
in `getXmpProperties(): Map[String, String]` (keys like `xmp:CreateDate`), which
`directory.getTags` never yields. Today the stored JSON contains
`{"XMP": {"XMP Value Count": "42"}}` and nothing more.

**B. PNG textual chunks collide.** `PngMetadataReader` creates a **separate**
`PngDirectory` per `tEXt`/`zTXt`/`iTXt` chunk — all named `"PNG-tEXt"` etc. — each
holding one `KeyValuePair` under the field name `"Textual Data"`.
`ExtractedMetadata.addValue` merges by directory name but **overwrites by field name**
(`directory + (fieldName -> value)`, `models/ExtractedMetadata.scala:48-51`), so only the last
text chunk survives. A `Creation Time` chunk is lost whenever another text chunk follows it.

Both must be fixed before their ladder rungs can exist (Unit 2).

**Do not switch to drewnoakes' typed date accessors.** `ExifSubIFDDirectory.getDateOriginal(TimeZone)`
returns a `java.util.Date` — an *instant*, resolved against GMT when the file carries no
zone tag. `original_created_at` is deliberately a zone-less wall clock; the typed accessor
would reintroduce exactly the instant round-trip the grouping work removed. Verified:
`ExifDescriptorBase.getDescription` has no case for `TAG_DATETIME_ORIGINAL`, so it falls
through to `Directory.getString` and the stored description **is** the raw
`yyyy:MM:dd HH:mm:ss`. Parsing the string is right; only the parser is wrong.

## Decisions taken

- Ladder covers other EXIF tags, XMP, IPTC, PNG text, GPS, and filename patterns.
- **No fallback.** `original_created_at` becomes **nullable**; NULL means "we have no
  information".
- **Native null placement, everywhere `original_created_at` orders results.** Plain `ORDER BY`, no
  `NULLS FIRST/LAST`, no `COALESCE`, no partial index, index shape unchanged. PostgreSQL treats NULL as
  larger than every value (first on DESC, last on ASC); SQLite treats it as smaller (first on ASC, last
  on DESC). The cross-engine difference is accepted and documented. This is the rule the branch
  already applies to SQLite's legacy null `created_at`, and `cursorPredicate` already honours it
  through `nullsFirst`.
- **A "No date" group when grouping by Date Taken.** Rows with a NULL capture date are one group, with
  a "No date" header and a full count, placed wherever the engine natively sorts a null day. They are
  not excluded and not moved.
- **Date Imported grouping is byte-for-byte unchanged.** SQLite's legacy null `created_at` rows stay
  excluded (today's `dayPresent` clause): that column is never NULL by design, only by legacy.
- **A "no date" badge** on each undated result cell, rendered server-side exactly like the triage
  badge, shown only when the effective sort field is `original_created_at`. Under Date Taken grouping
  the "No date" header already labels those cells; a sort by capture date under any other grouping, or
  none, is where an undated asset is otherwise unexplained. Widening it to Date Taken grouping is one
  boolean in two grid templates if wanted later.
- Provenance goes in a new **nullable** `original_created_at_source` column, with the
  invariant `originalCreatedAt.isEmpty <=> originalCreatedAtSource.isEmpty`. It is not the
  unknown-signal (the timestamp is) — its job is *which rung won*.
- Resolution **moves out of the DAO** into a pure resolver called from the pipeline.
- **No filesystem / client `lastModified` rung.** Import is browser multipart upload only
  today; the server never sees the original file, and browsers expose only
  `File.lastModified` (mtime, not birth time), which for downloads and cloud re-syncs is the
  *download* moment. A planned filesystem importer will be able to supply real file dates;
  see *Future: filesystem import* for the seams this plan leaves for it.
- **No backfill pass now.** `original_created_at IS NULL` makes it possible at any later
  date without a re-import.

---

## Unit 1 — The resolver (pure, no DB, no `app`)

New: `../../altitude/src/altitude/core/models/CaptureDateSource.scala`

```scala
/** Which rung of the ladder produced an asset's capture time. The stored value is `dbValue`, never the enum name. */
enum CaptureDateSource(val dbValue: String):
  case ExifOriginal    extends CaptureDateSource("exif_original")
  case ExifDigitized   extends CaptureDateSource("exif_digitized")
  case ExifFileChange  extends CaptureDateSource("exif_file_change")
  case XmpCreateDate   extends CaptureDateSource("xmp_create_date")
  case IptcCreated     extends CaptureDateSource("iptc_created")
  case PngCreationTime extends CaptureDateSource("png_creation_time")
  case PngModified     extends CaptureDateSource("png_modified")
  case GpsTimestamp    extends CaptureDateSource("gps_timestamp")
  case FileName        extends CaptureDateSource("filename")

object CaptureDateSource:
  def fromDbValue(value: String): Option[CaptureDateSource] = values.find(_.dbValue == value)

  // Not a copy of models/AccountType.scala: that codec round-trips the enum *name* through `valueOf`. This one carries
  // `dbValue`, and the reverse map must be total, so an unknown value is a decoding error rather than a silent default.
  // (The DAO reads the column through `fromDbValue` and degrades an unknown stored value to None; only JSON is strict.)
  given JsonCodec.ReadWriter[CaptureDateSource] = JsonCodec
    .readwriter[String]
    .bimap(_.dbValue, value => fromDbValue(value).getOrElse(throw IllegalArgumentException(s"Unknown capture date source: $value")))
```

There is deliberately **no `ImportTime` case**. "We found nothing" is `None`, which becomes
a NULL column, which becomes the "No date" group.

New: `../../altitude/src/altitude/core/util/CaptureDateResolver.scala`

```scala
case class CaptureDate(at: LocalDateTime, source: CaptureDateSource)

/**
 * Everything the ladder is allowed to look at. A case class rather than a positional argument list so a new
 * source (a filesystem import's file dates, say) is an added field, not a change to every call site.
 */
case class CaptureDateInputs(extractedMetadata: ExtractedMetadata, fileName: String)

object CaptureDateResolver:
  /** The camera's wall-clock capture time, or None when nothing plausible was found. */
  def resolve(inputs: CaptureDateInputs, notLaterThan: LocalDateTime): Option[CaptureDate]

  /** Every candidate the ladder produced, best first, before plausibility filtering. Diagnostics and tests. */
  private[core] def candidates(inputs: CaptureDateInputs): List[CaptureDate]
```

`resolve` takes **no clock** beyond the `notLaterThan` ceiling, so it is fully
deterministic and replayable.

**Every rung reads only from the persisted `extracted_metadata` map or the `filename`
column.** This is a hard rule, not an incidental property: a source the ladder consumes but
does not persist can never be replayed, so a later ladder improvement could not fix old
rows without re-reading every original file. A future source that is not already in the
image bytes (see *Future: filesystem import*) must therefore be written **into**
`extracted_metadata` under a synthetic directory before the resolver runs, not handed to
the resolver as a side channel.

### The ladder

Tag names below were verified in the 2.19.0 sources; none are guessed.

| # | Directory → tag | Zone rule |
|---|---|---|
| 1 | `Exif SubIFD` → `Date/Time Original` | camera wall clock verbatim; **ignore** `Time Zone Original` / `Offset Time` — the column *is* the camera's local clock |
| 2 | `Exif SubIFD` → `Date/Time Digitized` | same |
| 3 | `Exif IFD0` → `Date/Time` (0x0132) | same (file-change time, still camera-local) |
| 4 | `XMP` → `xmp:CreateDate`, `photoshop:DateCreated`, `exif:DateTimeOriginal`, `xmp:ModifyDate` | ISO 8601, may carry an offset → **keep the local part, discard the offset** (it was the creator's local time) |
| 5 | `IPTC` → `Date Created` + `Time Created`, then `Digital Date Created` + `Digital Time Created` | join with a space; time may carry `±HHMM` → discard the offset. The IPTC descriptor already renders `YYYYMMDD`→`YYYY:MM:DD` and `HHMMSS`→`HH:MM:SS` |
| 6 | `PNG-tEXt` / `PNG-iTXt` / `PNG-zTXt` → `Creation Time` | free-form; PNG spec recommends RFC 1123 → keep the local part |
| 7 | `PNG-tIME` → `Last Modification Time` (`"%04d:%02d:%02d %02d:%02d:%02d"`) | **UTC** per spec. No camera-local counterpart exists, so store the UTC wall clock and accept a possible off-by-one day — documented and deterministic, no zone guessing |
| 8 | `GPS` → `GPS Date Stamp` (`uuuu:MM:dd`) + `GPS Time-Stamp` (descriptor-formatted `"%02d:%02d:%s UTC"`) | join, strip the trailing ` UTC` and `.SSS`; same UTC rule as 7 |
| 9 | filename patterns (`asset.fileName`) | assumed local wall clock |
| — | *nothing found* | `None` → NULL → the "No date" group |

Rungs 7 and 8 rank **below** every local source precisely because their zone is not the
camera's. Deriving a zone from GPS lat/long is out of scope.

### Parsing

A `WallClockParser` in the same file:

```scala
/** The local wall-clock time a metadata string spells. Any offset or zone it carries is parsed and discarded. */
def parse(raw: String): Option[LocalDateTime]
```

An **ordered list** of `DateTimeFormatter`s, each built with `DateTimeFormatterBuilder`,
`.parseCaseInsensitive`, `appendValue(ChronoField.YEAR, 4)` (i.e. `uuuu`, not `yyyy`) and
`.withResolverStyle(ResolverStyle.STRICT)`:

1. `uuuu:MM:dd HH:mm:ss` `[.SSSSSSSSS]` `[XXX]`
2. `uuuu-MM-dd` `['T'][' ']` `HH:mm[:ss][.f][XXX|'Z']`
3. `uuuu:MM:dd HH:mm` (missing seconds)
4. `uuuu:MM:dd` / `uuuu-MM-dd` (date-only → `atStartOfDay`)
5. `DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH)` (PNG `Creation Time`)

Apply as `formatter.parse(trimmed)` then `LocalDateTime.from(temporalAccessor)` — that
discards any parsed offset for free, no `parseBest` needed. Trim and collapse internal
whitespace first; leave separators to the formatter list (pre-normalising `-`→`:` would
corrupt offsets).

Plausibility bounds, applied in `resolve` against the injected `notLaterThan`:

```scala
/** The first surviving photograph. Anything older is a sentinel or a dead clock battery. */
val EarliestPlausible = LocalDateTime.of(1826, 1, 1, 0, 0)
/** A UTC+14 camera's wall clock can legitimately read ahead of a UTC import moment; two days covers that plus drift. */
val FutureTolerance = Duration.ofDays(2)
/** Midnight on an epoch is written by cameras and encoders with no clock, never by a photographer. */
val Sentinels = Set(LocalDateTime.of(1970, 1, 1, 0, 0), LocalDateTime.of(1904, 1, 1, 0, 0), LocalDateTime.of(1980, 1, 1, 0, 0))
```

A candidate that fails the guard is discarded and the ladder continues to the next rung; if
every rung fails, the answer is `None`. `0000:00:00 00:00:00` and `2024:02:30` need no
special case — `ResolverStyle.STRICT` with `uuuu` rejects both — but both get a test.

### Filename patterns (rung 9)

Narrow only, and required to have either a separator or a known prefix so a bare 8-digit
run is not read as a date:

- `(IMG|VID|PXL|MVIMG|Screenshot|signal|photo)?[_ -]?(\d{4})(\d{2})(\d{2})[_T -](\d{2})(\d{2})(\d{2})`
- `(\d{4})-(\d{2})-(\d{2})[ _T-](\d{2})[-.:](\d{2})[-.:](\d{2})`

Highest false-positive risk of any rung, so it lands last in the ladder, behind the
plausibility guard, in its own commit (Unit 6) — trivially revertible.

**Tests** (`../../altitude/test/src/altitude/core/unit/CaptureDateResolverTests.scala`, registered
in `suites/AllUnitTestSuites.scala` — an import at `:5-15` plus an entry in the `Suites(...)` list at
`:19-29` — run by `make test-unit`): the resolver takes a plain map, so every rung and parse shape is
testable by hand-building `ExtractedMetadata`. Cover ladder ordering (SubIFD Original beats IFD0 beats
XMP…), each rung alone, offset discarded, GPS/IPTC composition, `0000:00:00 00:00:00`, `2024:02:30`,
year 1899, the exact 1970 epoch, `notLaterThan + 3 days`, date-only, missing seconds, subseconds, `T`
separator, RFC 1123, filename hits and near-miss non-dates, **empty metadata returning `None`**, and a
rung whose only candidate fails the guard falling through to the next rung.

Nothing calls the resolver yet — Unit 1 is a pure addition with zero behaviour change.

---

## Unit 2 — Extraction fidelity (prerequisite for rungs 4 and 6)

`service/MetadataExtractionService.scala:29-33` — special-case two directory kinds inside
the existing loop:

- `XmpDirectory` → also write every entry of `getXmpProperties()` into the `"XMP"`
  directory, keyed by its property path (`xmp:CreateDate`, `photoshop:DateCreated`, …).
- `PngDirectory` for the textual chunk types → read `getObject(TAG_TEXTUAL_DATA)` as
  `java.util.List[com.drew.lang.KeyValuePair]` and write **one entry per pair** keyed by
  the pair's key, rather than one `"Textual Data"` entry per directory. Do not split the
  formatted description string.

Ladder unchanged in this unit. Grows the stored JSON and is independently valuable — XMP
becomes visible in the asset metadata view for the first time. Verify with
`test/src/altitude/core/integration/MetadataParserTests.scala`; `images/exif/DSCF1160.JPG` carries
`<exif:DateTimeOriginal>2008:04:17 11:12:02</exif:DateTimeOriginal>` in its XMP packet (byte offset
9371) and is the natural assertion that XMP properties now land in the map.

---

## Unit 3 — Nullable capture date, native null placement, and the "No date" group

The largest unit. Split into a **schema commit** and a **search commit**; both are inert until Unit 4
makes nulls actually occur, so the whole unit is safe to land ahead of the producer — and the schema
commit **must** land before Unit 4, because `AssetDateStorageTests` "Import timestamp is written in
UTC regardless of the JVM time zone" (`:72-90`) inserts an asset with no capture metadata and would
hit a NOT NULL violation the moment the fallback is gone.

The SQL below was checked with `EXPLAIN` on both engines while designing it (in-memory SQLite 3.51.2
through the project's JDBC jar; PostgreSQL 18 in the test container; 200k rows, 30% undated). Those
numbers are design evidence, not completion gates: *Verifying this unit* is still required.

### The fact that shapes everything

A btree range predicate on the day column **never reaches the null block**, on either engine:
`day <= ?` with the cursor on the last dated day returns zero undated rows, because both engines stop
the scan at the first NULL rather than skip it. And bolting `OR day IS NULL` onto that bound drops both
planners to a prefix-only scan with the day as a *filter*: PostgreSQL 148 ms vs 0.14 ms, SQLite 6.2 ms
vs 0.28 ms at 200k — the same "OR-only shape" cliff already recorded in
`search-results-date-grouping.md`. Meanwhile `day IS NULL` on its own is a seek on both
engines (and an index-only count on PostgreSQL once the table is vacuumed), and a first page with no
day term at all reads the dated days and the null block in one ordered index pass.

So of the four cursor cases, three are one ordered slice exactly as today. The fourth needs a second,
*guarded* slice in the same statement. Pages stay full, the cursor is the same, the client sees nothing.

### Schema

Both `all.sql` files **only** — no versioned `<n>.sql`, no `schemaVersion` bump, no `ALTER`, per the
root `../../AGENTS.md` convention; the database is recreated from scratch.

```sql
-- postgres/all.sql, inside CREATE TABLE asset (today's last column, before `) INHERITS (_core);` — move the comma)
  -- The camera's wall-clock capture time (no zone). NULL when no metadata rung produced one: such an asset sorts
  -- where the engine natively places a null and forms the "No date" group when results are grouped by Date Taken.
  original_created_at TIMESTAMP WITHOUT TIME ZONE,
  -- Which ladder rung produced original_created_at (CaptureDateSource.dbValue). NULL exactly when it is NULL.
  original_created_at_source VARCHAR(32)
```

```sql
-- sqlite/all.sql
  original_created_at DATETIME,
  original_created_at_source TEXT,
```

Today both columns read `NOT NULL` with a comment saying the value "defaults to the local import time"
(`postgres/all.sql:76-77`, `sqlite/all.sql:72-73`); this is a constraint drop and a comment rewrite.

**The two indexes are unchanged** (`asset_search_date_taken`, `asset_search_date_imported`, and the
comment block above them). Both engines index NULL entries, which is precisely what makes `IS NULL` a
seek and lets the prefix scan cross into the block. No partial index, no null ordering anywhere.

### Null policy lives on the date source

`isNullableTimestamp` currently conflates *can be null* with *drop the nulls*: `buildGroupedSearchSql`
(`dao/jdbc/querybuilder/SearchQueryBuilder.scala:112-114`) appends `<col> IS NOT NULL` whenever it is
true, which is how SQLite's legacy null `created_at` is excluded today. Separate them:

```scala
// core/util/SearchGrouping.scala
/** What a grouped search does with a row whose grouping timestamp is null */
enum NullDays:
  /** The row has no day: it is left out of the pages and of every count (a legacy SQLite import time) */
  case Excluded
  /** The rows form a "No date" group, placed where the engine natively orders a null day */
  case OwnGroup

enum GroupBy(val apiValue: String, val field: String, val nullDays: NullDays):
  case DateTaken    extends GroupBy("dateTaken",    FieldConst.Asset.ORIGINAL_CREATED_AT, NullDays.OwnGroup)
  case DateImported extends GroupBy("dateImported", FieldConst.CREATED_AT,                NullDays.Excluded)
```

`isNullableTimestamp` keeps its one meaning, "the schema lets it be null":

- PostgreSQL (`dao/postgres/querybuilder/AssetSearchQueryBuilder.scala:29`): today a flat `false`;
  becomes `field == FieldConst.Asset.ORIGINAL_CREATED_AT`.
- SQLite (`dao/sqlite/querybuilder/AssetSearchQueryBuilder.scala:30`): `field == CREATED_AT` becomes
  `field == CREATED_AT || field == ORIGINAL_CREATED_AT`.

In `buildGroupedSearchSql`:

```scala
val nullable = isNullableTimestamp(grouping.by.field)
val ownGroup = nullable && grouping.by.nullDays == NullDays.OwnGroup
// A row without the grouping timestamp either has no day and is left out, with its counts, or belongs to the "No date" group
val dayPresent =
  if nullable && grouping.by.nullDays == NullDays.Excluded then ClauseComponents(List(s"$tableName.${grouping.by.field} IS NOT NULL"))
  else ClauseComponents()
```

Outcomes: DateImported on SQLite still appends `asset.created_at IS NOT NULL` (text unchanged);
DateImported on PostgreSQL is not nullable, so nothing (unchanged); DateTaken on both engines gets no
`dayPresent`, so `matchWhere`, and with it `total` and every day count, now include the undated rows.
Every further gate in this unit is on `ownGroup`, so for `NullDays.Excluded` the emitted statement is
**byte-for-byte today's** — pinned by a unit test (below).

The `nullsAfter` term of `cursorPredicate` (`:187`) is untouched. With PostgreSQL now reporting
`original_created_at` nullable it starts emitting `OR asset.original_created_at IS NULL` for a
DateImported grouping sorted by capture time ascending — which is exactly PostgreSQL's placement
(nulls last on ASC). Within a dated Date Taken day the sort column is never null, so the term is inert
there. The `SortValue.Null` anchor branches (`:182-185`) already cover an undated anchor inside an
import day in both directions.

### Cursor

`util/SearchCursor.scala`: `day: Option[LocalDate]`, `VERSION` 2 → 3; encode `"d"` as the ISO string or
JSON `null`, decode with `strOpt` (a missing key still lands in "Malformed cursor"). `scopeFingerprint`
is unchanged — the null day is a position, not part of the search's identity.
`LibraryService.searchGrouped` rejects a null-day cursor for an `Excluded` grouping next to
`requireScope` (`:99-104`): such rows are never anchors there.

### The cursor predicate, four cases

Everything is keyed on the existing `nullsFirst(direction)` hook. The "No date" group **leads** the
order when `nullsFirst(grouping.direction)` (PostgreSQL DESC, SQLite ASC) and **trails** it otherwise
(PostgreSQL ASC, SQLite DESC). UX consequence, accepted: with the default `groupDirection=desc` the
"No date" group opens the grid on PostgreSQL and closes it on SQLite.

Inside the null group a sort by the grouping column has nothing to compare (every sort value there is
null), so `afterBySort` collapses to `id > ?`. Replacement for `SearchQueryBuilder.scala:175-195`:

```scala
private def cursorPredicate(cursor: SearchCursor, grouping: SearchGrouping, sort: SearchSort, day: String): ClauseComponents =
  val dayOp = if grouping.direction == SortDirection.DESC then "<" else ">"
  val sortOp = if sort.direction == SortDirection.DESC then "<" else ">"
  val sortColumn = s"$tableName.${sort.field}"
  val id = s"$tableName.${FieldConst.ID}"

  // Inside the "No date" group a sort by the grouping column has nothing to compare: every sort value there is null
  val afterBySort: ClauseComponents =
    if cursor.day.isEmpty && sort.field == grouping.by.field then ClauseComponents(List(s"$id > ?"), List(cursor.id))
    else cursor.sortValue match
      ... // the three existing cases, unchanged

  cursor.day match
    // A day: the redundant inclusive bound seeks the day index to the boundary. A null day never satisfies it, which is
    // right whether the "No date" group precedes this day (already served) or follows it (fetched by the undated slice)
    case Some(cursorDay) =>
      val dayValue = dayBindValue(cursorDay)
      ClauseComponents(List(s"$day $dayOp= ?", s"($day $dayOp ? OR ${afterBySort.elements.head})"), List(dayValue, dayValue) ++ afterBySort.bindVals)
    // Inside the group that leads the order: skip what it already served; every day follows. No bound: the scan starts here
    case None if nullsFirst(grouping.direction) =>
      ClauseComponents(List(s"($day IS NOT NULL OR ${afterBySort.elements.head})"), afterBySort.bindVals)
    // Inside the group that closes the order: only its remainder is left, and IS NULL seeks straight into it
    case None =>
      ClauseComponents(List(s"$day IS NULL", afterBySort.elements.head), afterBySort.bindVals)
```

With `sort = filename ASC`:

**1. Cursor in a dated day, group leads** (PostgreSQL DESC, SQLite ASC) — today's predicate, unchanged.
The block sits before the cursor day in scan order and is never reached.

```sql
-- postgres, group DESC
AND asset.original_created_at::date <= ?
AND (asset.original_created_at::date < ? OR (asset.filename > ? OR (asset.filename = ? AND asset.id > ?)))
-- sqlite, group ASC
AND date(asset.original_created_at) >= ?
AND (date(asset.original_created_at) > ? OR (asset.filename > ? OR (asset.filename = ? AND asset.id > ?)))
```

**2. Cursor in a dated day, group trails** (PostgreSQL ASC, SQLite DESC) — the same predicate drives a
`dated` slice, and the statement gains a **guarded `undated` slice** (full statement below):

```sql
-- postgres, group ASC: dated slice
AND asset.original_created_at::date >= ?
AND (asset.original_created_at::date > ? OR (asset.filename > ? OR (asset.filename = ? AND asset.id > ?)))
-- undated slice (no cursor terms: the whole group follows every day)
AND asset.original_created_at::date IS NULL
```

**3. Cursor inside the null group, group leads** (PostgreSQL DESC, SQLite ASC) — no day bound: the scan
starts in the block (it leads) and the filter drops the rows already served. Verified: SQLite
`SEARCH ... (repository_id=? AND is_recycled=? AND is_pipeline_processed=?)` with no `MULTI-INDEX OR`;
PostgreSQL `Index Scan Backward` with the OR as `Filter:`, no `BitmapOr`.

```sql
AND (asset.original_created_at::date IS NOT NULL OR (asset.filename > ? OR (asset.filename = ? AND asset.id > ?)))
-- same-field sort collapses to:
AND (asset.original_created_at::date IS NOT NULL OR asset.id > ?)
```

**4. Cursor inside the null group, group trails** (PostgreSQL ASC, SQLite DESC) — `IS NULL` seeks,
nothing follows the group.

```sql
AND asset.original_created_at::date IS NULL
AND (asset.filename > ? OR (asset.filename = ? AND asset.id > ?))
```

**First page** (no cursor): today's text minus `dayPresent`. The null block crosses naturally — it
leads the scan or trails it, in index order, on either plan.

The `candidates` ORDER BY stays `$candidatesOrder` in every slice, including the `undated` one, whose
day term is a constant NULL (PostgreSQL still reports `Presorted Key` and a bounded top-N; SQLite a
block sort). The `page` re-sort and the final ORDER BY are untouched, and the engines' native null
placement in those re-sorts matches the placement in the slices, so no `NULLS` clause is needed to
merge them.

### The guarded undated slice (case 2 only)

Three guard shapes were measured (SQLite 200k rows / 60k nulls, guard false, i.e. the dated slice was
full):

| Guard | SQLite | PostgreSQL |
|---|---|---|
| `WHERE ... AND (SELECT count(*) FROM dated) <= 50` | **3.6 ms** — not hoisted, the null block is iterated and rejected row by row | `One-Time Filter`, `never executed` |
| `LIMIT CASE WHEN (SELECT count(*) FROM dated) > 50 THEN 0 ELSE 51 END` | **0.45 ms** — the limit register short-circuits the loop | `never executed` |
| `FROM (SELECT 1 WHERE (SELECT count(*) FROM dated) <= 50) AS g CROSS JOIN asset` | 0.29 ms | not run; fallback if a future engine mishandles a non-constant LIMIT |

**Use the `LIMIT` guard.** With the guard true (dated exhausted) the undated slice runs once at its
normal cost (11 ms / 34 ms at 60k nulls) — on the one transition page.

Builder sketch:

```scala
val undatedFollows = ownGroup && !nullsFirst(grouping.direction)
val slice = s"${matching(narrow, matchWhere + continuation)} ORDER BY $candidatesOrder LIMIT ${query.rpp + 1}"
val candidatesCte =
  if undatedFollows && query.cursor.exists(_.day.isDefined) then
    s"""dated AS MATERIALIZED ($slice), undated AS MATERIALIZED (
         ${matching(narrow, matchWhere + ClauseComponents(List(s"$day IS NULL")))} ORDER BY $candidatesOrder
         LIMIT CASE WHEN (SELECT count(*) FROM dated) > ${query.rpp} THEN 0 ELSE ${query.rpp + 1} END
       ), candidates AS MATERIALIZED (SELECT id, day, sort_value FROM dated UNION ALL SELECT id, day, sort_value FROM undated)"""
  else s"candidates AS MATERIALIZED ($slice)"
```

`candidate_count > rpp` keeps meaning "more remains": `candidates` is always a prefix of the remaining
order (51 dated rows, or all remaining dated rows plus the first 51 undated), so `hasMore` is unchanged.
Bind order becomes: dated slice (match + cursor), undated slice (`matchWhere`, case 2 only), the dated
count arm, the null count arm (`ownGroup` only), `total` (first page only).

### Null-safe day counts

`day_counts` correlates on `<day expr> = p.day`, which never matches a null day. The obvious CASE
(`(p.day IS NOT NULL AND day = p.day) OR (p.day IS NULL AND day IS NULL)`) plans as `MULTI-INDEX OR`
on SQLite — the rejected 15-170 ms shape — and would need a `BitmapOr` on PostgreSQL, which does not
use a btree for `IS NOT DISTINCT FROM` either. Instead, **two `UNION ALL` arms**, each with its own
equality shape; the null arm is uncorrelated, so it runs at most once, and only when the page has a
null day (its driver yields one row or none — `DISTINCT` treats nulls as equal on both engines):

```sql
day_counts AS MATERIALIZED (
  SELECT p.day AS day,
         (SELECT count(*) FROM (SELECT asset.id FROM asset WHERE <match> AND asset.original_created_at::date = p.day) AS m) AS n
    FROM (SELECT DISTINCT day FROM page WHERE day IS NOT NULL) AS p
  UNION ALL
  SELECT p.day AS day,
         (SELECT count(*) FROM (SELECT asset.id FROM asset WHERE <match> AND asset.original_created_at::date IS NULL) AS m) AS n
    FROM (SELECT DISTINCT day FROM page WHERE day IS NULL) AS p
)
```

PostgreSQL shows the null arm as `never executed` on a page without the group; SQLite codes the
subquery inside the loop over `p`, which runs zero times — flagged for a timing check rather than
assumed. The null count itself is an index-only range count: 2.1 ms (SQLite) / 3.0 ms (PostgreSQL,
vacuumed) at 60k nulls, paid only by pages that show the group.

The final join, over the page-sized CTEs only (so index use is irrelevant and the portable form beats
`IS NOT DISTINCT FROM` / `IS`):

```sql
LEFT JOIN day_counts AS d ON d.day = p.day OR (d.day IS NULL AND p.day IS NULL)
```

### The full statement — PostgreSQL, group ASC by Date Taken, sort filename ASC, rpp 50, cursor in a dated day

```sql
WITH dated AS MATERIALIZED (
  SELECT asset.id AS id, asset.original_created_at::date AS day, asset.filename AS sort_value
    FROM asset
   WHERE asset.repository_id = ? AND asset.is_recycled = ? AND asset.is_pipeline_processed = ?
     AND asset.original_created_at::date >= ?
     AND (asset.original_created_at::date > ? OR (asset.filename > ? OR (asset.filename = ? AND asset.id > ?)))
   ORDER BY asset.original_created_at::date ASC, asset.filename ASC, asset.id ASC
   LIMIT 51
), undated AS MATERIALIZED (
  -- The "No date" group follows every day on this engine and direction; it is read only if the days ran out on this page
  SELECT asset.id AS id, asset.original_created_at::date AS day, asset.filename AS sort_value
    FROM asset
   WHERE asset.repository_id = ? AND asset.is_recycled = ? AND asset.is_pipeline_processed = ?
     AND asset.original_created_at::date IS NULL
   ORDER BY asset.original_created_at::date ASC, asset.filename ASC, asset.id ASC
   LIMIT CASE WHEN (SELECT count(*) FROM dated) > 50 THEN 0 ELSE 51 END
), candidates AS MATERIALIZED (
  SELECT id, day, sort_value FROM dated UNION ALL SELECT id, day, sort_value FROM undated
), page AS MATERIALIZED (
  SELECT id, day, sort_value FROM candidates ORDER BY day ASC, sort_value ASC, id ASC LIMIT 50
), day_counts AS MATERIALIZED (
  SELECT p.day AS day,
         (SELECT count(*) FROM (SELECT asset.id FROM asset WHERE asset.repository_id = ? AND asset.is_recycled = ? AND asset.is_pipeline_processed = ?
                                   AND asset.original_created_at::date = p.day) AS m) AS n
    FROM (SELECT DISTINCT day FROM page WHERE day IS NOT NULL) AS p
  UNION ALL
  SELECT p.day AS day,
         (SELECT count(*) FROM (SELECT asset.id FROM asset WHERE asset.repository_id = ? AND asset.is_recycled = ? AND asset.is_pipeline_processed = ?
                                   AND asset.original_created_at::date IS NULL) AS m) AS n
    FROM (SELECT DISTINCT day FROM page WHERE day IS NULL) AS p
)
SELECT asset.*, p.day AS day, p.sort_value AS sort_value, d.n AS day_total,
       (SELECT count(*) FROM candidates) AS candidate_count
  FROM page AS p
       JOIN asset ON asset.id = p.id
       LEFT JOIN day_counts AS d ON d.day = p.day OR (d.day IS NULL AND p.day IS NULL)
 ORDER BY p.day ASC, p.sort_value ASC, p.id ASC
```

The SQLite twin (group DESC) differs only in `date(asset.original_created_at)`, `<=` / `<`, and
`+asset.filename` in the three slice ORDER BYs. This exact statement ran on PostgreSQL at 0.24 ms on a
non-transition page, `undated` `never executed`.

### Row reading and models

`Option[LocalDate]` everywhere the day flows — it mirrors the nullable column, and `groupsOf` folds
`None == None` with no text change — plus a boolean on the result rather than a nested option:

- `util/GroupedSearchResult.scala`: `GroupedSearchRow(asset, day: Option[LocalDate], sortValue, dayTotal)`;
  `AssetDateGroup(date: Option[LocalDate], total, assets)` (`None` = the "No date" group);
  `GroupedSearchResult(..., continuesGroup: Boolean)` replacing `continuesDay`, documented as "the first
  group continues the group the previous page ended in".
- `service/SearchService.scala:50-61`: `nextCursor` is unchanged in text (`day = last.day` is now an
  `Option`); `continuesDay = query.cursor.map(_.day)` becomes
  `continuesGroup = query.cursor.exists(cursor => groups.headOption.exists(_.date == cursor.day))` with
  `groups` computed once.
- `dao/jdbc/BaseDao.scala:226`: `protected def getDateField(value: AnyRef): Option[LocalDate]`.
  `dao/postgres/PostgresOverrides.scala:57`: `Option(value.asInstanceOf[LocalDate])` (the row processor
  already yields `null` for SQL NULL). `dao/sqlite/SqliteOverrides.scala:47`:
  `Option(value).map(text => LocalDate.parse(text.asInstanceOf[String]))` — today's `LocalDate.parse(null)`
  would NPE.
- `dao/jdbc/SearchDao.scala` `searchGrouped`: no text change; `getSortValueField` already maps NULL to
  `SortValue.Null`, and `day_total` is never null because both join arms match.

### Template and client

`views/htmx/results_grid_grouped.scala.html` (the header is rendered **here**, not in
`includes/search_results.scala.html`, which only carries its CSS):

```html
@if(!(groupIdx == 0 && results.continuesGroup)) {
@group.date match {
case Some(date) => {
<div class="date-group" data-group-date="@{date}">
  <time datetime="@{date}">@{Util.humanReadableDate(date)}</time>
  <span class="count">@{group.total}</span>
</div>
}
case None => {
<div class="date-group" data-group-date="">
  <span>No date</span>
  <span class="count">@{group.total}</span>
</div>
}
}
}
```

`data-group-date=""` keeps the attribute present (a header stays selectable as `[data-group-date]`, a
day selector still matches only days) with an empty value meaning "no day"; no `<time>`, since
`<time datetime="">` is invalid HTML. `Util.humanReadableDate` keeps its `LocalDate` signature.

**No client change.** `static/js/search-results/date-groups.js` finds a header by
`classList.contains("date-group")` and edits `.count` only; `detail-navigator.js` skips `.date-group` by
class; nothing under `static/js` reads `data-group-date`, `<time>` or `datetime` (only the controller
test does). The sticky header CSS (`includes/search_results.scala.html:119-137`) targets `.date-group`
and `.count`, so the null header inherits the look.

### The cost to write down

A page **inside** the null group is O(size of the block) on both engines: case 3 filters from the
block's start, and case 4 seeks but the within-block sort cannot come from the index, which carries no
`id`. Measured 11-14 ms on SQLite and 20-73 ms on PostgreSQL at 60k undated rows; expect roughly 5x at
300k. It is the same cost class as one very large day today. On PostgreSQL with the default
`groupDirection=desc` the "No date" group **opens** the grid, so this is the cost of scrolling the first
group there.

If the benchmark finds that unacceptable, the remedy is **not** a null-specific index: append `id` to
`asset_search_date_taken` and give case 3 the same guarded two-slice shape as case 2 (`day IS NULL AND
id > ?` as a seek, then a guarded `IS NOT NULL` prefix scan), so a same-field sort inside any day — and
inside the block — reads in index order. That is a separate benchmark question, deliberately outside
this unit.

### The ungrouped sort by `original_created_at`

`buildSelectSqlAsSubquery` and `orderBy` (`SearchQueryBuilder.scala:67-92`, `:310-316`) emit
`... FROM (SELECT asset.* ...) AS asset ORDER BY asset.original_created_at DESC LIMIT ? OFFSET ?` with
`count(*) OVER() AS total`. **No text change.** Undated rows now appear at native placement (PostgreSQL
first on "Newest", SQLite last), offset paging is a sort of the full matching set plus offset exactly
as before, and the window total counts them as before. The "no date" badge (Unit 7) is what explains
them to the user.

One optional one-liner to decide explicitly at implementation time: this ORDER BY has no `id`
tiebreaker, and the null block is one large tie group, so appending `, asset.id ASC` would make its
order deterministic at no plan cost (this path sorts everything anyway).

### Tests

- `test/TestContext.scala:177` `setAssetDates`: `taken: Option[LocalDateTime]`, bind `orNull`, and also
  write `original_created_at_source = ?` (`'exif_original'` when defined, NULL otherwise) so fixtures
  respect the invariant. Confirm dbutils binds a `null` parameter on SQLite (it falls back to
  `Types.VARCHAR` when `getParameterMetaData` is unsupported). Callers wrap their value in `Some`:
  `SearchGroupingTests:28, 133, 137, 212, 224, 244`, `SearchCursorTests:30`,
  `SearchResultsControllerTests:20`.
- `integration/SearchGroupingTests.scala`, mechanical: `day(iso)` returns `Some(LocalDate.parse(iso))`
  so every `summary` tuple compiles; `continuesDay shouldBe ...` becomes `continuesGroup shouldBe
  true/false` in "Pages are grouped by capture day with full-day totals and continued by cursor to the
  end" (`:82, :88`) and "A day larger than the page spans pages with the same total" (`:166-167`).
  New:
  - "Assets without a capture date form a No date group where the engine places a null day": two
    undated, three dated across two days; for both `groupDirection`s assert `summary` has `(None, 2,
    ids)` first or last per an engine-aware `nullDaysFirst(direction)` helper (PostgreSQL: DESC;
    SQLite: ASC), `total` includes them, and a folder filter bounds the group's count.
  - "The No date group larger than the page spans pages with the same total": five undated, rpp 2,
    walk to the end; `continuesGroup` true on continuations, each page's group `(None, 5, n)`.
  - "Undated assets keep their import day": DateImported grouping, sorted by `original_created_at`
    both directions; the undated row sits inside its import day at native null placement;
    DateImported totals unchanged.
- `integration/SearchCursorTests.scala`: `Dated.taken: Option[LocalDateTime]`; the fixture adds four
  undated assets (filename ties, an explicit import instant); `expectedOrder` computes the day key and
  the capture sort key as `Option[String]` and ranks nulls from `nullDaysFirst` / `nullsFirst` per
  engine. "Cursor traversal matches the complete order for every grouping, sort field and direction"
  then covers the boundary in all 2×2×5×2 combinations; add `rpp = 1` traversals for DateTaken in both
  directions so every position — the boundary and the inside of the null group — is an anchor. In
  `traverse`, `page.continuesDay shouldBe Some(cursor.day)` becomes
  `page.continuesGroup shouldBe page.groups.head.date == cursor.day`. "The cursor points at the last
  returned image and ends with the results" and "Deleting the anchor or inserting before it does not
  skip or repeat images": totals and slices shift by the four new rows. "A cursor continues correctly
  through legacy null import times": `undated.taken.toLocalDate` becomes `.get`. Add to "A cursor is
  rejected for a different scope or ordering, and when malformed": a hand-built v2 token
  (`ujson.Obj("v" -> 2, "d" -> "2026-09-06", ...)`, base64url) is rejected with "Unsupported cursor
  version", and `SearchCursor(day = None, SortValue.Null, id, scope)` round-trips through
  `encode` / `decode`.
- `unit/SearchSqlQueryTests.scala` (already sets `RequestContext.repository`): pin
  `buildGroupedSearchSql` for `GroupBy.DateImported` on **both** builders to today's
  `sqlAsStringCompact` — capture the strings before the change — so "byte-for-byte unchanged" is a
  test; assert the DateTaken shapes: `undated` / `UNION ALL` present only for a dated cursor when the
  group trails (PostgreSQL ASC, SQLite DESC), the `IS NULL` cursor forms, bind counts.
- `controller/SearchResultsControllerTests.scala` (PostgreSQL suite, so placement can be hard-coded):
  "The No date group opens with its own header and continues across pages": two dated, two undated,
  `groupDirection=asc`, rpp 3; page 1 has the two day headers with `<time>`, then
  `<div class="date-group" data-group-date="">` with `No date` and `<span class="count">2</span>` before
  the first undated cell, and a cursor on the last cell; the continuation has the remaining undated
  cell with no header and no cursor. With `desc`, the "No date" header precedes the first cell. The
  existing "Grouped HTML pages open each day with a header..." test stays as is.

### Verifying this unit

The design numbers above are evidence for the shape, not proof at scale. Re-run the harness from
`search-results-date-grouping-benchmark.md` at 100k and 1M with
`CASE WHEN i % 10 < 3 THEN NULL ELSE ... END` for `original_created_at`; on PostgreSQL `VACUUM ANALYZE`
the fixture first (unvacuumed, the `IS NULL` count plans as a Seq Scan). Medians of five, rpp 50,
`groupBy=dateTaken`, both directions, sorts `original_created_at` and `filename`, and DateImported as
the control. With `EXPLAIN ANALYZE` / `EXPLAIN QUERY PLAN`:

1. **First page**, both directions: same plans and numbers as today — SQLite
   `SEARCH ... USING INDEX asset_search_date_taken (repository_id=? AND is_recycled=? AND is_pipeline_processed=?)`,
   at most `USE TEMP B-TREE FOR LAST TERM`; PostgreSQL `Index Scan [Backward]` with the three-column
   `Index Cond` and `Incremental Sort`, no `Sort` (0.19 / 0.12 ms at 200k).
2. **Deep cursor in the dated section** (80%), both directions: same plans plus the day range; in the
   trailing direction `undated` must show `never executed` (PostgreSQL) and the SQLite time must match
   the leading direction (0.28-0.45 ms at 200k). A regression to ~4 ms per page means the guard slipped
   into the `WHERE`.
3. **The transition page** (cursor on the last dated day, trailing direction): `undated` executes once
   (`Index Cond ... IS NULL` plus a bounded top-N over the block; 11 / 34 ms at 60k nulls, expect ~5x
   at 300k); `day_counts` shows both arms, the null arm as an index-only range count.
4. **A page inside the null group**, both directions and both sorts: leading direction is the prefix
   scan with the `IS NOT NULL OR ...` filter; trailing is the `IS NULL` seek. Record the O(block) cost.
5. **DateImported** first page and deep cursor: identical statements and numbers to today (the
   golden-text unit test guards the text; this guards the plan).
6. **First-page `total`**: unchanged cost, now counting the undated rows.
7. Two things the design run could not settle, to check in the same run: SQLite's laziness of the
   `day_counts` null arm on a page without the group (compare `day_counts` time to today's), and
   PostgreSQL's plan choice for the null-cursor filter at 1M (at 200k it kept the ordered scan; a
   `BitmapOr` over the primary key for the same-field `id > ?` arm would show as a `Sort` over the whole
   table).

Correctness tests are listed above. Run `make test-unit`, `make test-sqlite`, `make test-psql` and
`make test-controllers` (the cursor version bumps and the grouped HTML gains a header shape).

---

## Unit 4 — Resolution out of the DAO; delete the fabricated fallback

Closes defects **1, 3, 4, 6**. Activates Unit 3: nulls start occurring. **Requires Unit 3's schema
commit** (see the test hazard at the top of Unit 3).

`pipeline/flows/ExtractMetadataFlow.scala:22-32` already has both inputs (`dataAsset.data` for the
metadata, `dataAsset.asset.fileName` in scope at `:20`):

```scala
// Merge, never replace: an upstream stage may have seeded a synthetic directory the image bytes cannot carry
val extractedMetadata = dataAsset.asset.extractedMetadata.merge(app.service.metadataExtractor.extract(dataAsset.data))
val inputs = CaptureDateInputs(extractedMetadata, dataAsset.asset.fileName)
val capture = CaptureDateResolver.resolve(inputs, LocalDateTime.now(ZoneOffset.UTC))
val asset = dataAsset.asset.copy(
  extractedMetadata = extractedMetadata,
  publicMetadata = publicMetadata,
  originalCreatedAt = capture.map(_.at),
  originalCreatedAtSource = capture.map(_.source),
  width = width, height = height)
```

The flow currently **replaces** `asset.extractedMetadata` outright (`:27`). Change it to
merge, and add `ExtractedMetadata.merge(other): ExtractedMetadata` (a per-directory field
map union, `other` winning on collision) in `models/ExtractedMetadata.scala`. Today nothing
seeds metadata upstream so the merge is a no-op — but it is the seam a filesystem importer
needs, and it costs one method now versus a pipeline change later. `FieldValuesType` and
`MetadataType` are `private type` aliases in the companion, so the public `merge` signature is fine but
no helper may expose the map type.

`dao/jdbc/AssetDao.scala:95-106` — the parse, the two catch branches and all **three**
`LocalDateTime.now()` calls (`:102`, `:105`, `:106`) are **deleted**. What remains is a bind:

```scala
// No metadata rung produced a capture time: the column stays NULL and the asset joins the "No date" group
val capture: Any = asset.originalCreatedAt.map(nativeLocalDateTime).orNull
val captureSource: Any = asset.originalCreatedAtSource.map(_.dbValue).orNull
```

`nativeLocalDateTime(value: LocalDateTime): Any` is non-`Option` (`BaseDao.scala:63`), so the `.map(...)
.orNull` form is right. `created_at` continues to be bound explicitly in UTC (`:121-122`).
`exifDateTimeFormatterPattern` in `dao/jdbc/BaseDao.scala:58` has this one consumer and becomes dead;
delete it, and the then-unused `LocalDateTime` / `DateTimeParseException` imports in `AssetDao` and
`DateTimeFormatter` in `BaseDao`.

`add` returns `asset.copy(id = Some(id))` (`:131-132`) — with resolution upstream, the model already
carries the right `originalCreatedAt` and `originalCreatedAtSource`, so nothing needs
patching back in.

`Asset.originalCreatedAt` **stays `Option`**, and now genuinely means "unknown" rather than
"not read back yet".

Sites to touch (complete list):

- `models/ExtractedMetadata.scala` — add `merge(other): ExtractedMetadata`.
- `models/Asset.scala:47` — add `originalCreatedAtSource: Option[CaptureDateSource] = None` after
  `originalCreatedAt`. The model's codec is `JsonCodec.macroRW`, so Unit 1's `given ReadWriter` must be
  in implicit scope for the macro.
- `FieldConst.scala:74` — `val ORIGINAL_CREATED_AT_SOURCE = "original_created_at_source"` after it.
- `dao/jdbc/AssetDao.scala` — `makeModel` (`:26-52`, capture read at `:49`): read the source via
  `CaptureDateSource.fromDbValue`; the INSERT column list at `:84` and its placeholder list at `:88`
  (19 today; both lines change together).
- `service/LibraryService.scala:48` (`convImportAsset2dataAsset`) — no change; defaults `None`.
- `pipeline/flows/IndexFlow.scala:24` and `pipeline/flows/IndexAndFaceRecFlow.scala:24` — identical text,
  both currently **discard** the return of `app.service.asset.add(...)`. Thread it:
  `val persisted = app.service.asset.add(dataAsset.asset)`, then
  `app.service.search.indexAsset(persisted)` and emit `dataAsset.copy(asset = persisted)`. In
  `IndexAndFaceRecFlow` build that copy **before** the `faceRecognition.processAsset` call at `:27`, so
  face recognition sees the persisted asset. `app.service.asset.add` resolves to `BaseService.add`
  (`service/BaseService.scala:32-39`), which already returns `dao.add`'s result: no service change.
- `test/TestContext.scala:100-135` (`makeAsset`) — add optional `originalCreatedAt` /
  `originalCreatedAtSource` params so date fixtures stop going through `publicMetadata`.
- `integration/AssetDateStorageTests.scala:17-23` — its helper drives storage through
  `publicMetadata` and `testApp.DAO.asset.add` directly; rewrite it to
  `.copy(originalCreatedAt = ..., originalCreatedAtSource = ...)` so the storage-semantics tests keep
  testing storage only. Its *"Missing or invalid capture metadata falls back to the local import time"*
  test (`:58-70`) **inverts**: missing metadata must now store NULL in both columns.

Defect 6 closes for free: nothing reads `publicMetadata.dateTimeOriginal` any more (its only other
consumers are `Asset.getPublicMetadata`, the producer, and two tests). Keep the field (it is a display
bag; only `AssetImportServiceTests:47` asserts it) but it stops being an input.

`AssetImportServiceTests:74-79` (*"Imported asset without metadata has media creation date
set"*) inverts too — `images/1.jpg` has no EXIF, so it must now come back with `originalCreatedAt`
empty until Unit 5 gives the ladder more rungs to try.

---

## Unit 5 — The core ask: ladder rungs 2–8

Wire rungs 2–8 into `CaptureDateResolver`. Unit tests come from Unit 1; add integration
coverage in `integration/AssetDateStorageTests.scala` going through the real import
(`IntegrationTestUtil.getImportAsset` takes a path relative to `test/resources/import/`):

- **`images/cactus.jpg` is a perfect ordering fixture** — verified byte-level: IFD0 `Date/Time`
  `2011:07:06 22:51:53` at offset 240, SubIFD Original + Digitized `2011:05:16 17:46:24` at 786/806.
  Assert we store `2011:05:16 17:46:24` with source `exif_original`, **not** `2011:07:06`.
- `images/3.png` (no date strings at all) → both columns NULL, and the asset appears in the
  "No date" group when grouping by Date Taken.
- `images/exif/DSCF1160.JPG` — resolved: IFD0 `2024:07:04 08:09:00` (offset 242), SubIFD
  Original/Digitized `2008:04:17 11:12:02` (750/770), and XMP `exif:DateTimeOriginal`
  `2008:04:17 11:12:02` (9371). Assert `2008:04:17 11:12:02` / `exif_original`; it is a second
  rung-1-beats-rung-3 case and, after Unit 2, an XMP-rung case if the EXIF rungs are masked in a unit
  test.
- A generated PNG with a spliced `tEXt Creation Time` chunk → `png_creation_time`.

**No new binary fixtures are required.** Add
`IntegrationTestUtil.pngWithTextChunk(bytes, key, value)` splicing a `tEXt` chunk (length,
type, data, CRC32) into the output of the existing `generateRandomImagBytesBgr`
(`test/IntegrationTestUtil.scala:55-78`), which already writes PNG via `ImageIO`. Optional real-world
extras only you can supply: a phone screenshot PNG, a Lightroom export with embedded XMP, a WhatsApp
re-encode, an IPTC-tagged press photo.

---

## Unit 6 — Filename rung

Rung 9 and its patterns, in its own commit for easy reversal. Unit tests only.

---

## Unit 7 — The "no date" badge

An undated asset sorted by capture date sits wherever the engine put the null, with no header to say
why. The badge says why. Pattern to copy exactly: the triage badge.

**How the triage badge works today**

- `views/htmx/result_cell.scala.html:27-29` renders `<div class="triage-marker">triage</div>` inside
  `.drag-drop` (the `position: relative` box, `includes/search_results.scala.html:61-66`) when
  `asset.isTriaged`; the outer cell mirrors the state as `alt-is-triaged="true"` (`:15`).
- Its CSS is the inline `<style>` block of `views/includes/search_results.scala.html:181-205`:
  `#assets .cell .drag-drop .triage-marker` at bottom-right (`bottom: 8px; right: 8px`), plus rules that
  hide it in the triage view and while the cell is `.dragging` or `.move-pending`. There is no Tailwind
  and no CSS build; partial-scoped styles belong in that block (`views/AGENTS.md:24-26`).
- The drag stand-in clones the cell onto `<body>`, outside `#assets`, so it strips `.triage-marker`
  (`static/js/search-results/dragon-drop.js:36-41`).
- The only other overlay on a cell is the selection checkmark at top-right (`:75-89`).

**Changes**

- `result_cell.scala.html` gains `showNoDate: Boolean = false`. When
  `showNoDate && asset.originalCreatedAt.isEmpty`, render `<div class="no-date-marker">no date</div>`
  beside the triage marker, and mirror it as `alt-has-no-date="true"` on the outer cell for parity with
  `alt-is-triaged`.
- `views/htmx/results_grid.scala.html` passes
  `showNoDate = results.sort.headOption.exists(_.field == FieldConst.Asset.ORIGINAL_CREATED_AT)`
  (`SearchResult.sort: List[SearchSort]`); `views/htmx/results_grid_grouped.scala.html` passes
  `showNoDate = results.sort.field == FieldConst.Asset.ORIGINAL_CREATED_AT`
  (`GroupedSearchResult.sort: SearchSort`). Both result objects already carry the effective sort, so
  **no controller change**; a continuous-scroll page re-renders the same partial from a fresh result,
  so the badge is right on every page with no client state.
- CSS, in the same block as the triage rules: `.no-date-marker` at **bottom-left**
  (`bottom: 8px; left: 8px`) so it never collides with the triage marker — a triaged, undated asset is
  possible — with `background-color: var(--warning-background-color)` (`core.css:43`), white uppercase
  text, and the triage marker's font, padding, radius and letter-spacing; hidden under the same
  `.dragging` / `.move-pending` rules.
- `dragon-drop.js:36-41`: also `clone.querySelector(".no-date-marker")?.remove()`.
- Nothing else client-side: no JS reads the sort for cells (per-cell toggling is driven only by the
  view-settings metadata fields, `static/js/fragments/search-results.js:337-362`), and
  `removeTriageStyling` (`static/js/assets/asset-actions.js:26-39`) is triage-specific.
- Tests, `controller/SearchResultsControllerTests.scala`, reusing the `persistDated` / `search` / `cell`
  helpers (`:16-43`) and Unit 3's `setAssetDates(None)`: an undated asset under `sort=original_created_at1`
  has `<div class="no-date-marker">no date</div>` inside its cell and `alt-has-no-date="true"` on it; the
  same asset under `sort=created_at1` has neither; a `groupBy=dateTaken` request with `sort=filename0`
  has neither; a dated asset never has it. Assert on the **full element string**, never on the class
  name alone — the inline `<style>` block ships in the same body and would match (see the comment at
  `:226`).

---

## Documentation (final commit)

- `../../altitude/AGENTS.md` § *Search results and date grouping* — the *Date storage behind this*
  paragraph (`:110`) states that missing capture metadata "still falls back to the local import time"
  and that a null timestamp is excluded from grouping. Both change. Document the ladder, the nullable
  column and its provenance column, native null placement and the per-engine positions, `NullDays`,
  the "No date" group and the guarded undated slice, the cursor version, and the badge.
- Resolve a real doc contradiction found during exploration: root `AGENTS.md:47-49` says
  "DO NOT add new migrations or bump the version number", while `altitude/AGENTS.md:112-114`
  § *Schema migrations* says a schema change means bumping `schemaVersion` and adding both
  `<version>.sql` files. The contradiction is also internal to `../../altitude/AGENTS.md` (`:110` already
  states the no-migration rule as fact), and `migrations/` contains exactly two files, both `all.sql`.
  This plan follows the root convention; correct `:112-114`.
- `../../altitude/views/AGENTS.md` — *Date headers* (~`:419-430`): a header carries a `<time>` for a day and a
  plain "No date" label with `data-group-date=""` otherwise; add a *Cell badges* paragraph beside it
  naming both markers, their conditions, their corners, and the stand-in stripping; update the
  `result_cell.scala.html` row of the file table (`:531`).
- `../../docs/Result-Grouping.md` — *What "a day" is*: the column is nullable and what NULL means; replace the
  *Nullable import times* bullet (`:165-168`) with the `NullDays` split; extend *No explicit NULLS
  FIRST/LAST* (`:182-185`) with where the "No date" group lands per engine; add the guarded undated
  slice and the two-arm `day_counts` under *One statement per page*; *The cursor* (`:194-218`): v3,
  optional day; *Date headers* (`:292-306`): the "No date" header; *Where things live*: the badge.
- `../../CONTEXT.md` — add "No date" (the group) and the "no date" badge alongside the existing Date Taken /
  Date Imported / Date Group terminology.

---

## Future: filesystem import

A filesystem importer is planned. It is the one consumer that *can* supply real file dates,
so this plan is shaped to make that a drop-in rather than a redesign.

| Need | Prepared by |
|---|---|
| A new source value | `CaptureDateSource` is an enum of `dbValue` strings and `fromDbValue` returns `Option`, so adding `FileSystemCreated`/`FileSystemModified` is additive and an unknown stored value degrades rather than crashes in the DAO |
| A place to put file dates the image bytes cannot carry | `ExtractMetadataFlow` **merges** instead of replacing (Unit 4), so an upstream stage can seed a synthetic `"Altitude Import"` directory with `"File System Created"` / `"File System Modified"` |
| Those dates to survive for a later backfill | They land in `extracted_metadata` like any other rung's input, so the ladder replays offline with no file re-reads |
| A resolver signature that does not churn | `CaptureDateInputs` is a case class; the new rung reads the synthetic directory, so the signature does not change at all |
| A column to hold the provenance | `original_created_at_source` already exists, nullable, with no enum constraint in SQL |

So: **no schema change, no resolver signature change, no pipeline change** when the importer
lands — only `ImportAsset` gaining the source path/attributes,
`LibraryService.convImportAsset2dataAsset` seeding the synthetic directory, and two new
rungs plus two new enum values.

Two decisions deliberately left to that work, because they are properties of filesystem
import and not of this one:

- **Ladder position.** File dates should rank **below** the filename rung, i.e. as the last
  rungs before `None`. A filename like `IMG_20230101_120000.jpg` is a date a camera or app
  wrote deliberately; an mtime is whatever the last copy left behind. Use
  `min(creationTime, lastModifiedTime)` — a plain `cp` resets mtime while birth time is the
  copy moment, so neither alone is reliable and the earlier of the two is the better guess.
- **Zone.** `BasicFileAttributes` gives `FileTime`, i.e. an *instant*, but the column is a
  wall clock. For a local filesystem import the honest conversion is the **server's own
  zone** (it is the machine holding the files) — which differs from the UTC rule rungs 7 and
  8 use, and so needs stating explicitly in the code comment and in `../../AGENTS.md`. Note also
  that `Files.readAttributes(...).creationTime()` is not true birth time on every platform
  and filesystem; on many it silently returns mtime.

## Out of scope (enabled, not built)

- **Backfill / re-resolve.** A later `AssetService.reresolveMissingCaptureDates` can select
  `WHERE original_created_at IS NULL` — an `IS NULL` seek on the existing day index — and re-run the
  ladder against the already-stored `extracted_metadata` and `filename`, with no file
  re-reads. `search_document` is `fts4(repository_id, asset_id, body)` so it holds no dates
  and needs no reindex.
- **Editing a capture date by hand.** The natural follow-on once "No date" is visible, and the reason
  `original_created_at_source` is worth storing: a user-set date would be its own source value.
- **Client `File.lastModified` / filesystem dates.** Dropped, per the reasoning above.
- **Sub-second EXIF refinement.** Pointless — the column has second resolution.
- **`id` in `asset_search_date_taken`** and the symmetric guarded slice for case 3: only if the Unit 3
  benchmark rejects the in-group paging cost.

## Alternatives considered

- **`COALESCE(original_created_at, created_at)` as the sort and day expression**, with a matching
  expression index on both engines. Undated rows would order and group by their import time — the
  app's default sort — identically on both engines, with no null handling in the query builder and a
  v2 cursor. Rejected by the user in favour of honest native placement: an asset with no capture date
  should not be filed under an import day when grouping by Date Taken. Recorded so it is not
  re-proposed.
- **Two partial indexes and two-phase paging** (the first draft): dodged the `NULLS LAST` cliff
  structurally but at the price of a second index, a section-aware cursor, a short page at every
  boundary and a dedicated ungrouped treatment. Superseded by the guarded-slice design, which needs no
  new index and keeps every page full.
- **`NULLS LAST` with direction-matched indexes**: would need an index per direction and still leave
  the mixed-direction case; rejected on the earlier benchmark evidence.

---

## Verification

Per unit:

1. `make test-unit` — the resolver suite, no DB needed (Units 1, 6); the pinned DateImported SQL and the
   DateTaken shapes (Unit 3).
2. `make test-sqlite` and `make test-psql` (the latter against the `altitude-core-postgres-test`
   container on 5433) after Units 2, 3, 4, 5.
3. `make test-controllers` after Units 3, 4 and 7 — the asset JSON shape changes, the cursor
   version bumps, the grouped HTML gains the "No date" header, the cells gain the badge.
4. The benchmark re-run described in *Verifying this unit* (Unit 3), before considering that unit done.

End to end, after Units 5 and 7:

1. Recreate the dev database from scratch so `all.sql` applies (Postgres
   `altitude-core-postgres-dev` on 5432; restart the dev server to run migrations).
2. `mill altitude.resources` after editing anything under `static/`, then restart the dev
   server on :8080.
3. Upload through the real import form: `cactus.jpg` (expect `2011:05:16 17:46:24` /
   `exif_original`), `3.png` (expect both columns NULL), and a PNG carrying a
   `tEXt Creation Time`.
4. `docker exec` into the dev Postgres and check
   `SELECT filename, original_created_at, original_created_at_source FROM asset ORDER BY created_at DESC LIMIT 10;`
5. In the UI, group by Date Taken and confirm: dated assets land in the expected day groups,
   `3.png` appears under **"No date"**, that group sits **first** for "Newest first" and **last** for
   "Oldest first" on the PostgreSQL dev database (the reverse on SQLite), the header shows its count,
   and scrolling across the boundary in either direction neither duplicates nor drops rows.
6. Sort by Date Created (Newest) with no grouping: `3.png` sits first on PostgreSQL and wears the
   **"no date"** badge; switch to Date Imported and the badge is gone; group by Date Taken with a
   filename sort and the badge is gone while the header labels the group.
7. Group by Date Imported and confirm it is unchanged — every asset still has an import day, and
   `3.png` sits inside its import day.
