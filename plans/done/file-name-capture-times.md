# File-name capture times

## Context

A photo saved out of Messages, AirDrop, WhatsApp, Telegram or a screenshot tool carries no EXIF: no capture time and no
camera model. What it does carry is a time in its name, to the second. `CaptureDateResolver` reads two name shapes
today, the compact camera form (`IMG_20240704_080910`) and the dashed timestamp (`2024-07-04 08.09.10`), and a time it
reads from a name is the last rung of the ladder, stored with the source `filename`. The Sequence rule then ignores it:
only the EXIF and XMP rungs count as the camera's clock, and a frame with no camera model is never eligible. So a batch
of such frames shows "Created: N/A" on the Triage cards and never groups, whatever the Scene models say of it.

Decisions taken: more name shapes resolve, each anchored at the start of the name and spelling a time to the second; a
file-name time counts for Sequences; two frames both without a camera model pass the camera test; a name that spells a
date and no time (WhatsApp mobile's `IMG-20240704-WA0001`) resolves nothing, since a midnight time would put a whole
day's frames inside one gap; the fraction of a second is never read from a name.

## Goals

- These names resolve to a capture time, with the source `filename`, when no metadata rung does:
  - Pixel names with milliseconds after the seconds, `PXL_20240704_080910123.jpg`, the fraction dropped.
  - Apple's export names, `Photo Dec 24 2023, 2 55 06 PM.jpg`, also `Image` and `Video`, 12-hour clock.
  - macOS screenshots, `Screenshot 2024-07-04 at 08.09.10.png` and `Screen Shot 2024-07-04 at 8.09.10 AM.png`.
  - Android screenshots in the dashed form, `Screenshot_2024-07-04-08-09-10.png`.
  - Telegram, `photo_2024-07-04_08-09-10.jpg`, and WhatsApp desktop, `WhatsApp Image 2024-07-04 at 08.09.10.jpeg`.
  - Facebook's epoch names, `FB_IMG_1703429706123.jpg` (milliseconds) and the ten-digit seconds form, read as the UTC
    wall clock, as the container and GPS rungs are.
- A still image whose capture time came from its name joins a Sequence under the same rule as one with an EXIF time:
  within the gap, at one size, looking alike by a Scene model.
- Two frames neither of which names a camera pass the camera test; a frame that names one and a frame that does not
  never pair, so an original and its metadata-stripped copy are not a burst.

Out of scope: a day-precision capture date from a date-only name (a later option: its own source, shown as the capture
date and treated by the Sequence rule as no clock); converting a UTC name (Pixel, Facebook) to local time, since no
source's offset is ever converted; a time read from a name when a metadata rung is plausible.

## Language

`CONTEXT.md`, **Sequence**: within a minute of each other by the camera's clock or, failing that, by the time in the
file name, on the same camera or both naming none. No new term.

## Design

### The name shapes (`CaptureDateResolver`)

`filenamePatterns` becomes a list of shapes, each a regex anchored at the start and a function from its groups to the
ISO string `WallClockParser.parse` reads, so the strict calendar check and the plausibility rules (earliest plausible,
future tolerance, sentinels) apply to every shape as they do today. A boundary must follow the seconds, as today, so an
incidental digit run never reads as a time; the one exception is a Pixel name, whose `PXL_` prefix allows exactly three
digits after the seconds, which are dropped (`IMG_20240704_08091099` stays rejected).

| Shape | Example | Notes |
|---|---|---|
| Compact camera | `IMG_20240704_080910.jpg`, `Screenshot_20240704-080910.png` | As today: an optional prefix (`IMG`, `VID`, `PXL`, `MVIMG`, `Screenshot`, `signal`, `photo`), `YYYYMMDD`, a separator, `HHMMSS`; `PXL_` may carry three more digits |
| Dashed timestamp | `2024-07-04 08.09.10.png`, `Screenshot 2024-07-04 at 08.09.10.png`, `Screenshot_2024-07-04-08-09-10.png`, `photo_2024-07-04_08-09-10.jpg`, `WhatsApp Image 2024-07-04 at 08.09.10.jpeg` | An optional prefix (`Screenshot`, `Screen Shot`, `WhatsApp Image`, `photo`, `Image`) and separator, `YYYY-MM-DD`, then a separator or ` at `, then `HH`, `MM`, `SS` separated by `-`, `.` or `:`, with an optional ` AM`/` PM` making the hour a 12-hour one |
| Apple export | `Photo Dec 24 2023, 2 55 06 PM.jpg`, `Photo Dec 24 2023, 11 37 24 AM.jpg` | `Photo`, `Image` or `Video`, an English month abbreviation, the day, the year, a comma, then `H MM SS` and `AM`/`PM` |
| Epoch | `FB_IMG_1703429706123.jpg` | `FB_IMG_` and 13 digits (milliseconds) or 10 (seconds), the UTC wall clock of that instant |

A 12-hour clock reads 12 AM as 00 and 12 PM as 12, hours 1 to 12 only. A name that matches no shape resolves nothing,
as today. The rung stays last: a plausible metadata date always wins over the name.

### Sequences

`SequenceRule.CameraClockSources` gains `FileName`, and its comment changes with it: the gap test needs whole seconds,
which a name gives; the fraction of a second a camera writes orders the frames inside one second, and frames with none
order by ID, as `CurationRule` already does. The set keeps its name, since the file-name time is the camera's clock as
the phone's app wrote it into the name.

`isEligible` no longer requires a camera model: a still image in triage with a capture time from one of those sources
and a hash. `matches` tests the camera as: both name the same model, or neither names one. The candidates statement
derives its source list from `CameraClockSources`, so it follows; the device model is not in SQL and stays in Scala.

No Setting changes.

### Tests

- `CaptureDateResolverTests`: each shape of the table resolves to its time with the source `filename`; the Pixel
  milliseconds are dropped and the fraction is zero; the 12-hour clock at 12:00:00 AM, 12:00:00 PM and 1:00:00 PM; the
  epoch forms read as UTC whatever the JVM's zone; rejected names: `IMG_20240704_08091099` (two trailing digits),
  `IMG-20240704-WA0001` and `Photo Dec 24 2023.jpg` (no time), `Photo Feb 30 2023, 1 00 00 PM.jpg` (February 30th),
  `FB_IMG_0.jpg` (the epoch sentinel), a 13-hour `PM`; a plausible EXIF date still wins over every shape.
- `SequenceRuleTests`: a frame with a file-name time is eligible; two frames with no camera model match; a frame with a
  model and a frame without do not, either way round; two different models do not.
- `SequenceServiceTests`: two frames imported with no metadata and Apple export names a second apart share a Sequence
  (the test's `importFrame` gains a variant that seeds no metadata and sets the file name); the far picture named a
  second later does not; a frame of the same scene with EXIF and a camera model, imported between them, joins neither.
  The existing test that a file-name date never joins becomes this one.

## Tasks

1. **The plan in the project.** This plan saved as `plans/todo/file-name-capture-times.md`.
2. **The name shapes.** The shape list and its conversion in `CaptureDateResolver`, test first in
   `CaptureDateResolverTests` (the table's names in, the rejects out, the 12-hour edges, the epoch's zone independence).
3. **The rule.** `CameraClockSources` with `FileName`, `isEligible` without the model, the camera test in `matches`,
   test first in `SequenceRuleTests`.
4. **Assignment.** `SequenceServiceTests` on both engines: the name-dated burst pairs, the far picture and the
   EXIF-dated frame stay out, with the `importFrame` variant.
5. **Documentation.** `CONTEXT.md` (Sequence); `altitude/AGENTS.md` (**Import Pipeline**'s `CaptureDateResolver` paragraph: the
   file-name rung and its shapes; **Assignment**: the clock sources, the camera test and the eligibility, the tests); `docs/curation-batch-detection.md`
   (the rule); `docs/test-coverage.md`; the comments in `SequenceRule` and `sequenceCandidatesStatement`.

## Verification

- `make test-unit`, `make test-sqlite` and `make test-psql`, then `make lint` and a compile.
- On the dev server after a restart, with the eight `Photo Dec 24 2023, …` frames imported again: each card shows its
  capture time from its name, and the pairs at 2 54 46 and 2 54 56, 2 55 03 and 2 55 06, and 11 37 24 and 11 37 30 are
  three Sequences in Curation's queue; the 2 54 00 frame, alone in its minute, is in none.
