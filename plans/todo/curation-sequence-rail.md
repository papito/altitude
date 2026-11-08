# Curation: the Sequence rail

## Goals

Better navigation through the Sequences of Curation's queue:

- Skip goes away, replaced by **N** for the next Sequence and **P** for the previous one.
- A right column, the counterpart of the Bundle rail on the left, lists every Sequence of the queue vertically with the current one
  highlighted. The user moves with N, with P, or by clicking a Sequence.
- When a Sequence is resolved, Curation moves to the next one.

Out of scope: the Sequence rail on a selection or on a Sequence opened from its group header; reopening a resolved Sequence from
its row (Undo stays one entry, Z); a live refresh of the list while Curation is open.

## Context

The `curation` Alpine component (`altitude/static/js/alpine/components/curation.js`) lives on the shell (`htmx/curation.scala.html`)
and is opened on the queue, one Sequence or a selection. On the queue it holds `queueIds` and `position`, reads one set's fragment
at a time into `#curationSet`, and goes to `position + 1` after a Resolve, a Keep all or a Skip. `readSet` passes over a Sequence
that answers 204 by increasing `position`, so it only ever walks forward.

After a Resolve the Picks leave the Sequence and the rest are in the trash, so the Sequence answers 204 and has nothing to show. A
Sequence over the 100-frame window keeps its other frames and stays in the queue.

`GET /api/curation/r/:repoId/queue` answers the Sequence IDs alone. `CurationService.queue()` already reads each Sequence's `members`
and `firstCapturedAt` (`SequenceMembers`, `AssetDao.sequencesToCurateStatement`, which `countSequencesToCurateStatement` shares).

Keys in use: arrows, `1`, `2`, `Shift+1`, `Shift+2`, `Enter`, `A`, `R`, `H`, `P` (Pin), `S` (Skip), `Z`, `Esc`. `exclusively` runs
one action at a time and drops whatever arrives during one, reads included.

## Behavior

**Where it applies.** The queue alone, opened by Triage's "Curate all N sequences". A Sequence opened from its group header and a
selection have no Sequence rail, no N and no P. Neither has Skip any longer: Exit closes them.

**The Sequence rail** is a column of 11rem at the right of the shell, listing the queue in its order, oldest first capture first.
Its header reads "Sequences" and the position, "4 of 12". A row holds the Sequence's cover Preview (its first frame in capture
order, at `--curation-preview-size`), its frame count ("8 frames") and its first capture's date ("12 Mar 2026", or "No date" for an
undated Sequence). The current row wears the accent edge the Bundle rail's focused frame does and stays scrolled into view.

The Sequence rail is switched by **Q** and by a `Sequences Q` button in the shell's header (`aria-pressed`, the queue only), and the
choice is kept for the next open, as the Histogram's is. N and P work with it hidden.

**A row is open or done.** It is done once its set is resolved or kept (Resolve or Keep all, a Keep all that changed nothing
included), or once its Sequence answers 204 because too few frames are left. A done row stays in place, dimmed with a check. N and P
pass over it, and a click on it does nothing. A Sequence over the window is done for this walk; Start again offers it again. Undo of
an outcome puts its row back to open and the walk back on it.

**Moves.**

| Move | Goes to |
|---|---|
| N | The nearest open row below. With none, nothing: the button is disabled |
| P | The nearest open row above. With none, nothing: the button is disabled |
| Click on an open row | That row. The current row does nothing |
| After a Resolve, a Keep all, or a recycling that leaves too few frames | The nearest open row below, else the first open row from the top, else the end state |

A row that answers 204 when reached becomes done and the move goes on in its direction. A move with nothing further stays on the set
on show and on its row. The end state appears only when every row is done: "All done", or the count with Start again, which reads
the queue afresh, for Sequences imported meanwhile or one still holding frames past the window.

**Unsaved work.** Leaving a set by a move remembers its Picks, pinned and focused frames and zoom (`currentView`), restored when the
walk returns to it, for as long as Curation stays open. A Resolve, a Keep all or a Sequence ending forgets it.

**Latest move wins.** N, P and a row click move the highlight at once, and a read that has been overtaken is ignored, so the last
move is the set that opens. Resolve, Keep all, Recycle, Undo, Try again and Start again stay one at a time: none starts while a move
is reading, and no move starts during one of them. N and P do not repeat while held.

A failed read of a move shows the unread state as any failed read does, and the Sequence rail, N and P still work there, so a Sequence
that will not read cannot block the rest.

**Keys.** `N` next, `P` previous, `Q` the Sequence rail, `Space` Pin. `S` does nothing. `Space`, like `Enter`, is left to a button
that has keyboard focus. The footer's actions read `Keep all A`, `Previous P`, `Next N`, the last two on the queue alone. The Pin
button reads `Pin Space`.

## Language

**Sequence rail** is in the glossary. A row's "open" and "done" are code and guide words only and are not shown to the user.

## Changes

### A. The queue's rows (server, test-first)

1. **A queued Sequence carries its cover.** Add `QueuedSequence(sequenceId, members, firstCapturedAt, coverId)`, returned by
   `AssetDao.getSequencesToCurate` and `CurationService.queue()`; `SequenceMembers` stays for `countSequenceMembers`. The list statement
   adds the cover as a correlated subquery over the Sequence's live frames in `captureOrder`'s sort (capture time with NULLs last,
   `created_at`, `id`). `countSequencesToCurateStatement` stays free of it, since the toolbar reads the count after every mutation.
   Tests in `CurationServiceTests`, both engines: the cover is the earliest frame, skips a recycled frame, and for an undated
   Sequence is the first imported. The plan test keeps naming `asset_sequence`.
2. **The queue's fragment.** Add `GET /htmx/curation/r/:repoId/queue` (`CurationActionController`) rendering
   `htmx/curation_queue.scala.html`: one row per Sequence, each carrying `data-rail-sequence-id` (`Const.attributes.railSequenceId`,
   since `data-app-sequence-id` finds a group header's Curate). Add a `d MMM yyyy` formatter to `Util` for its date, read as the
   camera's wall-clock date. Controller tests: rows in queue order with cover, count, date and "No date", and the login redirect.
3. **Remove the JSON queue.** Nothing else reads `GET /api/curation/r/:repoId/queue`: remove the route and `fields.SEQUENCE_IDS`, and
   point `CurationControllerTests.queueOf` and the two tests listing that route at the fragment. Update `altitude/AGENTS.md`
   (**Curation**) in this group.

### B. The rail on the shell (frontend)

4. **Shell layout.** In `htmx/curation.scala.html`, put `#curationSet` and the no-set states in a main column beside
   `aside#curationSequenceRail` (`x-show` while on the queue and shown), so the rail outlives each set's fragment and stays through
   the no-set states. Add `--curation-sequence-rail-width: 11rem` to `core.css`, and the column and row styles to
   `static/css/htmx/curation.css` and a new `static/css/htmx/curation_queue.css`, listed in `app.css`.
5. **Loading and painting rows.** `startQueue` reads the fragment through the axios client into the list, as a set's is, and takes
   `queueIds` from `data-rail-sequence-id`. The component paints the rows directly (`.current` with `aria-current`, `.done`), with no
   Alpine binding per row and one delegated click, so a long queue costs nothing per move. The current row scrolls into view
   (`block: "nearest"`).
6. **The show/hide switch.** `showsSequenceRail`, `toggleSequenceRail`, the `Q` key and the header button, remembered under
   `Const.localStore.curationSequenceRail` as the Histogram's choice is. `views/AGENTS.md` (**Curation**, the attribute list, the
   `localStorage` paragraph) and the style guide's Curation section, with a row specimen, change in this group.

### C. The walk (frontend)

7. **Open and done rows.** Add the set of done IDs, cleared by Start again. `settle` marks a row done, and so does a 204 on reading
   one. Undo clears the mark.
8. **Moves by direction.** Replace `nextSet`'s `position++` and `readSet`'s forward loop with one target rule per move in the table
   above: nearest open row in a direction, wrap for the moves after an outcome, pass-over of a 204 in the move's direction, and a stay
   on the current set when a move finds nothing. The end state runs only when no row is open. `preloadNextSet` preloads the next open
   row in the direction of the last move. Add `hasPreviousSequence` and `hasNextSequence`.
9. **Latest move wins.** A move takes a token and updates `position` at once; a read whose token is no longer the latest is dropped
   before it shows or fails anything. Writes and moves exclude each other through `exclusively`'s `isBusy` and a move-in-flight flag.
   Keep a `shownSequenceId`, since `position` runs ahead of the set on show during a move.
10. **Remembered views.** A closure map of `currentView()` by `shownSequenceId`, saved as a move leaves a set, passed to `showSet` on
    return, dropped when the row goes done and cleared by Start again.
11. **Undo after Start again.** `returnToSequence` no longer splices an ID in, as a row has data of its own: when the Sequence is not in
    the list, the fragment is read afresh after the undo and the walk stands on it.

### D. Keys, footer and Skip's removal (frontend and docs)

12. **Remove Skip.** Delete `skip()`, the `s` key and the footer's Skip Sequence button with its comment, and reword what refers to skips
    in `curation.js`'s header and `settle` comments and in `curation.scala.html`'s opening comment.
13. **N, P, Q and Space.** Bind `n` and `p` to the moves on the queue alone, in `once`, not repeating. Move Pin to `" "`, and extend the
    guard that leaves `Enter` to a focused button to `Space`. Let N and P act with no set on show, so the unread state can be left.
14. **Footer and labels.** Add `Previous P` and `Next N` beside `Keep all A` (`x-show="isQueue"`, `:disabled` by the two getters),
    change the Pin button to `Pin <kbd>Space</kbd>`, and update the `handleKey` comment.
15. **Docs.** `views/AGENTS.md` (**Curation**: the walk, the keys, Pin, "closing loses only…") and `docs/curation-batch-detection.md`
    (the keys table, §4 The walk of the queue, §5's Skip row, the closing lines): they say the list is never seen and name Skip and
    `P` for Pin.

## Verification

- `make test-focused` while working on group A, then `make test-sqlite`, `make test-psql`, `make test-controllers` and `make lint`.
- In the browser, on the queue: N and P pass over done rows and stop at the ends; a row click opens it; a Resolve goes to the next open
  row and wraps from the last; the end state appears only when all rows are done; Picks survive N then P; Z brings a done row back;
  three fast Ns open the third Sequence; a recycling that leaves one frame moves on and marks the row done; Q hides and remembers the
  rail; Space pins and P no longer does.
- A group header's Curate and a selection show no rail, no N or P and no Skip, and their reports and Exit are unchanged.
