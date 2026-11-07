# Undated Sequences

## Context

A still image with no capture time never joins a Sequence: the rule's gap test has nothing to measure, and the
candidates read is a window of capture time. Once `plans/todo/file-name-capture-times.md` is done, the frames left
without a capture time are the ones whose metadata was stripped and whose name carries no time: WhatsApp mobile's
`IMG-20240704-WA0001`, a `received_…` download, a renamed file. Both Scene models embed every one of them at import,
so their likeness is known; only the time is missing. This plan groups them by likeness alone, among the recent
imports of their size.

Decisions taken: an undated frame is compared with the undated, triaged still images of its repository at exactly its
width and height; the candidates are the most recently imported ones, up to a cap of 5,000 per CPU core; the two
similarity thresholds are the Sequence thresholds, with no Settings of their own; undated frames pair only with undated
frames; the comparison streams through the rows, holding only the matches; the read takes no row locks, and the matches
are re-read locked before they are stamped.

## Goals

- An undated, triaged still image joins a Sequence with the undated, triaged still images of its repository of its
  width and height that either Scene model finds alike, or whose hash is near where no model embedded both.
- Each assignment reads at most `UNDATED_CANDIDATES_PER_CORE` (5,000) times the machine's cores, newest imports first,
  so the cost of one import is bounded whatever triage holds.
- Memory stays flat during the read: a candidate's embeddings are dropped as soon as it is judged.
- Dated frames are assigned exactly as today, and a dated frame and an undated one never share a Sequence.
- Curation's queue lists undated Sequences after the dated ones on both engines, and shows their frames in import order.

Out of scope: a vector index over the embeddings (the path that makes the comparison a nearest-neighbour query once
triage holds far more undated frames than the cap); a Setting for the cap; a capture-time window on import time; pairing
an undated frame with a dated one.

## Language

`CONTEXT.md`, **Sequence**: still images in triage that the import found shot as one scene: within a minute of each
other on the same camera, looking alike, or, for images with no capture time, looking alike among the recently imported
images of their size. No new term.

## Design

### The rule (`SequenceRule`)

Eligibility splits in two, both pure and both tested on their own:

- `isDated(asset)`: a still image in triage with a capture time from one of `CameraClockSources` and a hash: today's
  `isEligible`.
- `isUndated(asset)`: a still image in triage with no capture time and a hash.

`isEligible` is either. `matches(asset, candidate, settings)` holds for two dated frames as today, and for two undated
frames when they have the same width and height and `lookAlike` (the same two thresholds and the same hash fallback).
A dated frame and an undated one never match. The check stays symmetric.

### The candidates (`AssetDao`)

A second hand-written statement, `undatedSequenceCandidatesStatement(asset, limit)`: the context repository's
completed, triaged still images, in the trash or not but not purge-pending, with a hash and no capture time, at the
asset's width and height, the asset itself left out, `ORDER BY created_at DESC, id DESC LIMIT ?`, with no `FOR UPDATE`.
The flags are literals as in the dated statement, and the partial index `asset_triage_undated` (`repository_id, width,
height, created_at, id` over the triaged rows with no capture time, in both `all.sql` files beside the two triage
indexes) answers it on both engines: the repository's undated triage rows of the size are one run of the index in the
statement's order, which the engine walks backwards from the newest and stops at the limit, and the other predicates
are heap checks over those rows alone. The statement does not probe `is_recycled`, which the index does not hold. The
predicates are a derived copy of `isUndated` plus the size test, as the dated statement's are of `isDated`, and the
Scala rule stays the authority; the two statements share their common `WHERE` (`sequenceCandidatesWhere`).

It selects `candidateColumns` (the embeddings included), but it does not materialize its rows. `BaseDao` gains a
streaming read, `collectBySqlQuery[T](sql, values)(f: Map[String, AnyRef] => Option[T]): List[T]`, which maps each row
as it is fetched and keeps what `f` returns, with a fetch size set so the driver does not buffer the whole result on
PostgreSQL. `getUndatedSequenceMatches(asset, limit)(keep: Asset => Boolean): List[Asset]` is the DAO method: it builds
each candidate with `makeModel`, keeps it when `keep` holds, and lets the rest go, so at most the matches' embeddings
are alive at once. It also returns how many rows it scanned, so the service can log when the cap was reached.

No row locks on the read: the Sequence writers already take turns through `lockSequences`, and the matches, a few rows,
are re-read locked through `AssetDao.lockTriaged` (the library operations' locking select over triaged rows, in ID
order, `FOR UPDATE` on PostgreSQL) before they are stamped, so a candidate a concurrent move is sorting, which the
unlocked read still returns on PostgreSQL, waits for the move and is left out once it commits, and the asset is never
stamped alone.

### The cap

`Const.Sequence.UNDATED_CANDIDATES_PER_CORE` is 5,000, with its reasoning in the comment: a candidate costs about 6 KB
to read, so a core's share is about 30 MB per undated import, and the compare itself is two dot products a candidate.
`Altitude.undatedSequenceCandidates` is that times `Runtime.getRuntime.availableProcessors`, computed once at startup
and logged at INFO with the core count. `SequenceService` takes it as a constructor parameter, so a test can build the
service with a cap of its own.

### Assignment (`SequenceService.assign`)

A dated asset is assigned as today. An undated asset takes `lockSequences`, reads its matches through
`getUndatedSequenceMatches(asset, cap)(SequenceRule.matches(asset, _, settings))`, re-reads them locked through
`lockTriaged`, keeping those still in triage with the Sequence each carries now, and goes on as the dated path does:
no match, no Sequence; matches with none, a new one; matches in one, it joins; matches in several, they merge through
`sequenceOf`, whose tie on `firstCapturedAt` is a tie of `None`s among undated Sequences and falls to the ID. When the
read scanned as many rows as the cap, the assignment logs at DEBUG that older undated frames were not compared.

### Curation

- `sequencesToCurateStatement` orders by `first_captured_at IS NULL, first_captured_at, sequence_id`: an undated
  Sequence has no first capture, and NULL sorts first on SQLite and last on PostgreSQL, so the explicit test puts it
  last on both.
- `sequenceFramesStatement` orders by `original_created_at IS NULL, original_created_at, created_at, id`, and
  `CurationRule`'s frame order takes the import time as the same tie-break, so an undated Sequence's frames show in the
  order they were uploaded rather than by their random IDs. A dated Sequence's order is unchanged, since two frames of
  one capture time differ in import time only by their upload order.
- `CurationSet`, the rail, the Bundles and the outcomes need nothing else: every relative test compares frames of one
  Sequence, and an undated Sequence's frames carry their metrics and embeddings as any other's do.

### Cost

| Undated same-size frames in triage | Rows read per undated import | Bytes moved |
|---|---|---|
| 100 | 100 | ~0.6 MB |
| 1,000 | 1,000 | ~6 MB |
| 10,000 | 10,000 | ~60 MB |
| at the cap (5,000 × cores) | the cap | ~30 MB × cores |

The compare is under a millisecond per thousand candidates. The read walks `asset_triage_undated` backwards from the
newest undated triage row of the size and stops at the cap, so an import reads no index page of the dated or the
other-sized triage rows, and the cap bounds what the engine reads as well as what is compared. A batch of N undated
same-size frames costs N × (N − 1) / 2 candidate rows in total up to the cap, so a batch of 5,000 reads about 12.5
million rows, or 75 GB, over its import; that is the bound the cap exists for.

### Tests

- `SequenceRuleTests`: an undated frame is eligible and a dated one still is; two undated frames at one size match by
  either model and not by neither, and the hash decides where no model embedded both; another width or height parts
  them; a dated and an undated frame never match, either way round.
- `SequenceServiceTests` (both engines): two undated frames imported with no metadata and names with no time share a
  Sequence; a different undated picture at their size does not, nor a half-size copy; a dated frame of the same scene
  joins neither; the candidates' plan names `asset_triage_undated` and sorts nothing on both engines
  (`SearchPlans.lookupPlanOf` over seeded rows); a service built with a cap of 1 pairs a new frame with the newest match
  only, and the older frame of the same scene stays out; the DAO read returns only the matches; a concurrent move of a
  candidate (`contend`) leaves both frames without a Sequence, as on the dated path.
- `TestContext.persistAsset` fails a test whose fixture the import paired with a Sequence `setAssetSequence` stamped,
  since clearing the import's Sequence would wipe the stamp off every member; fixtures are persisted before the stamps.
- `CurationServiceTests` (both engines): an undated Sequence is in the queue after a dated one on both engines, its
  frames in import order, and its set resolves and reopens as any other.

## Tasks

1. **The plan in the project.** This plan saved as `plans/todo/undated-sequences.md`.
2. **The rule.** `isDated`, `isUndated`, the undated `matches`, test first in `SequenceRuleTests`.
3. **The streaming read.** `collectBySqlQuery` in `BaseDao` with the fetch size, test first in the DAO's unit or
   integration coverage (a read whose mapper keeps every other row returns those rows alone).
4. **The candidates.** `undatedSequenceCandidatesStatement` and `getUndatedSequenceMatches` on the trait and the JDBC
   DAO, test first in `SequenceServiceTests` (the plan and the matches-only read).
5. **The cap and assignment.** `Const.Sequence`, `Altitude.undatedSequenceCandidates`, the `SequenceService`
   parameter, the undated branch of `assign` and its DEBUG line, test first in `SequenceServiceTests`.
6. **Curation's order.** The two `ORDER BY`s and `CurationRule`'s tie-break, test first in `CurationServiceTests`.
7. **Documentation.** `CONTEXT.md` (Sequence); `altitude/AGENTS.md` (**Assignment**: the two eligibilities, the
   undated read, the cap and its reasoning, the lock, the tests; **Curation**: the queue's and the frames' order);
   `docs/curation-batch-detection.md` (the rule, assignment, the Settings table's hash-distance note); `docs/test-coverage.md`;
   the comments of `SequenceRule`, both candidates statements and `Const.Sequence`.

## Verification

- `make test-unit`, `make test-sqlite` and `make test-psql`, then `make lint` and a compile.
- On the dev server after a restart: a handful of metadata-stripped frames of one scene, renamed so no name carries a
  time, imported together, form one Sequence in Curation's queue after the dated ones, with their frames in upload
  order; a different picture imported with them stays out; the startup log names the cap and the core count.
