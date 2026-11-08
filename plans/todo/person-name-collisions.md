# Person name collisions

## Context

A live Person's name is unique within a repository: `person_01` is a unique index on `(repository_id, name)` over the people
that are neither merged away nor a bad match, in both `all.sql` files. Renaming a Person to a name another live Person holds
(`PeopleActionController.editPersonName` → `PersonService.updateName`) fails on that index; `BaseService` turns the SQL error into
a `DuplicateException`, and the editor (`htmx/edit_person_name`) comes back with "Person by that name already exists". Nothing
else happens: the user who typed the name almost always meant the same person, and has to find the other Person and drag one
onto the other to merge them.

The index compares names exactly, so "tessa" and "TESSA" are taken alongside "Tessa", and the People list and Search show
people who look alike. The controller treats a rename that differs from the Person's own name only in case as "Name has not
changed", so a Person's capitalization cannot be corrected.

`name_for_sort` holds `name.toLowerCase` (default locale) for a named Person and `unknown 0042` (zero-padded `person_label`
sequence) for an unnamed one; `person_03` and the People list order by it.

`PersonService.merge(dest, source)` gives the destination the source's name when only the source is named, but writes only
`name`: `is_named` stays false and `name_for_sort` stays `unknown 00NN`. Such a merged Person sorts among the Unknowns, falls in
Unmatched below three faces, and is left out of the Search names (`SearchDao.searchNames` takes named people only). The
decision reads `dest.isNamed` and `source.isNamed` from the models the caller passed, not from the rows it has just read.

## Goals

- A live Person's name is unique regardless of case and Unicode form, on SQLite and Postgres alike.
- A rename to a name another live Person holds offers to merge the renamed Person into that one, from the editor, through the
  existing Merge people modal.
- A Person's own name can be corrected in case alone.
- A merge that gives the destination the source's name leaves it named in full: `is_named`, `name_for_sort`, the bucket, the
  sort and the Search names all follow.

Out of scope: automatic merging, people who really share a name (a disambiguator such as "Tessa (2)"), drag-and-drop merge
direction, renaming from the People tab cells, and whitespace inside a name beyond the existing trim.

## Language

No new glossary term. **Name key**: the folded form of a name that uniqueness compares, kept in `name_for_sort`; a code term
only, never shown.

## Design

### The name key

`PersonService.nameKey(name)` is `Normalizer.normalize(name, NFC).toLowerCase(Locale.ROOT)`. The stored `name` is NFC
normalized too, by `updateName` and `PersonDao.add`, so a name typed with a combining accent and one typed precomposed are the
same name. `Locale.ROOT` keeps "I" folding to "i" whatever the JVM's default locale (a Turkish default would give a dotless ı).
The fold happens in Scala, not in SQL: SQLite's `lower()` and `NOCASE` fold ASCII only, while Postgres folds by its collation,
so a database-side fold would make "Élodie" and "élodie" one name on Postgres and two on SQLite.

`name_for_sort` of a named Person is its name key, written by `PersonDao.add` (`getPersonSortName`), `updateName`, and `merge`
when the destination takes a name. An unnamed Person's sort name keeps its zero-padded form.

### Uniqueness

`person_01` moves from `name` to `name_for_sort`, keeping its predicate, in both `all.sql` files (no new migration; the
database is recreated):

```sql
CREATE UNIQUE INDEX person_01 ON person (repository_id, name_for_sort)
    WHERE is_deleted = FALSE AND is_bad_match = FALSE;
```

An unnamed Person's sort name (`unknown 0042`) cannot equal a named Person's key unless the name itself has that form, so names
of the form `Unknown <digits>`, in any case and spacing, are refused by `updateName` with a validation error ("Unknown N names
are reserved"), the editor showing it under the field like any other. Without it, a Person named "Unknown 42" would sit beside
the unnamed Unknown 42 under two different keys.

The comment above the person indexes in each `all.sql` names `person_01` as the name-key index.

### The conflict lookup

`PersonService.getLiveByNameKey(name): Option[Person]` reads the live Person whose `name_for_sort` equals `nameKey(name)`,
with the predicate written as `person_01`'s (`is_deleted = FALSE AND is_bad_match = FALSE`) so SQLite uses the partial index:
through ScalaSql, which binds a flag as a number, or, in hand-written JDBC, with the flags as literals, since
`SqliteOverrides.nativeBool` binds `"1"`/`"0"` as text, which SQLite does not match to the predicate. A
hidden Person is live, so it is found. A merged-away or bad-match Person is not, and does not block the name either.

### The rename

`editPersonName`:

- **No change**: the trimmed, NFC-normalized name equals the Person's stored name exactly. A difference in case alone is a
  rename, and saves (the row keeps its own key).
- **Taken**: `updateName` raises `DuplicateException`. The controller looks the holder up with `getLiveByNameKey` and renders
  the editor with it as `conflictingPerson`. If the lookup finds no one (a concurrent merge, or a constraint other than the
  name), the editor shows the plain "Person by that name already exists" with no button. A lookup that finds the Person being
  renamed cannot happen, since the exact-match check above already returned.

`htmx/edit_person_name` takes `conflictingPerson: Option[Person]`. With one, the error under the field reads "Tessa already
exists." (the holder's stored name, whatever case was typed), or "Tessa already exists and is hidden." for a hidden holder,
followed by a `type="button"` `.action-button small` "Merge into Tessa…". The button dispatches the existing
`confirmPersonMerge` event through Alpine:

```html
@click="$dispatch(window.Const.events.confirmPersonMerge,
                  { mergeSourceId: '<renamed person id>', mergeDestId: '<holder id>', keepDirection: true })"
```

The response still holds the editor, so `js/listeners/people.js`'s `htmx:after:request` hook announces no rename, and the
editor stays open behind the modal. Escape restores the name as before; closing the modal leaves the editor and its error.

### The merge modal

`confirmPersonMerge`'s listener already passes the event detail as the modal request's values, so `keepDirection` reaches
`showMergePeopleModal` as a query parameter with no change in `people.js`. With `keepDirection=true` the modal keeps the
requested source and destination; without it, the face-count swap stays, so drag-and-drop is unchanged. The existing holder
stays the destination, so its id, the one in URLs and in later share links, survives.

The modal's Merge submits to the existing `mergePeople` route, and `PERSON_MERGED_EVENT` reloads the People panes and runs a
search for the destination, as today. A hidden holder stays hidden: a merge does not change visibility.

### Merge naming

`merge` decides the merged name from the persisted rows it reads, not from the arguments. When the destination takes the
source's name it writes `name`, `name_for_sort` (the name key) and `is_named = true`, and returns the model with `isNamed` set.
The source is soft-deleted before the destination takes the name, as now, so `person_01` never sees both at once.

## Tasks

Each task is one reviewable change, in dependency order. Server tasks are red before green, as integration tests.

1. **The name key and case-insensitive uniqueness.** `PersonService.nameKey`, NFC on the stored name, `PersonDao.add` and
   `updateName` writing the key, `person_01` on `name_for_sort` in both `all.sql` files with the comment, and the reserved
   `Unknown N` names. `PersonServiceTests`, on both engines: "tessa" and "TESSA" refused beside "Tessa"; "Élodie" refused beside
   "élodie", and a decomposed "Élodie" beside a precomposed one; a Person renamed to its own name in another case saves, with the
   new name and key; a merged-away or bad-match "Tessa" does not block the name; "unknown 42" and "Unknown  42" refused; and
   `addPerson` writing the key for a named Person. Rationale: the rule lives in the database, so both engines prove it.

2. **Merge naming.** `merge` reading the persisted rows and writing `name_for_sort` and `is_named`. `PersonServiceTests`: in
   "Known person keeps the same when merged into Unknown" the merged Person is also named, has the name key as sort name, and is
   in the Matched bucket with one face; a named destination keeps its own name, key and flag. Rationale: the offered merge can
   put the name on an unnamed destination; without this the merged Person drops out of Matched and Search.

3. **The conflict lookup and the rename route.** `getLiveByNameKey` with `PersonServiceTests` (finds a hidden holder; ignores
   merged-away and bad-match people; matches across case and Unicode form). `editPersonName`'s exact no-change check and the
   conflict rendering, and `showMergePeopleModal`'s `keepDirection`. `PeopleActionControllerTests`: a taken name answers 200 with
   the editor, the holder's stored name in the error and a button carrying both ids and `keepDirection`; a hidden holder's message
   says so; a case-only rename answers the name view with the new name; `keepDirection=true` keeps a source with more faces as
   the source, and its absence still swaps. Rationale: the server decides what is offered.

4. **The editor's offer.** `htmx/edit_person_name` (the message, the button), `static/css/htmx/person.css` for the error row
   under the display-size inline field, following the style guide's error and small-button usage, and the style guide entry if
   the row is a new pattern. Rationale: front-end only, checked in the browser.

5. **Documentation.** `docs/faces.md` (**People**: Name, Merge, the name key), `views/AGENTS.md` (the person name editor's
   paragraph under **Event Flow** and the People events: the offer, `keepDirection`), `altitude/AGENTS.md` (the index paragraph of the
   search section names `person_01`: it is now on `name_for_sort`, and why the fold is in Scala while `account_01` folds
   `lower(email)`), and `docs/test-coverage.md`. Rationale: the Do section's no-drift rule.

## Verification

- `make lint`, `make test-unit`, `make test-controllers`, `make test-sqlite` and `make test-psql` pass.
- In the browser, after `make db` and `make watch`, with a named Person "Tessa" and an unnamed Person:
  - Renaming the unnamed Person "tessa" shows "Tessa already exists." and the button; the button opens the modal with the
    renamed Person on the left and Tessa on the right, whichever has more faces; Merge shows Tessa's results, the People tab no
    longer lists the renamed Person, and Tessa's count includes its faces.
  - Closing the modal leaves the editor with its error; Escape restores the old name.
  - With Tessa hidden, the message says so, and after the merge Tessa is still in the Hidden bucket.
  - Renaming Tessa to "TESSA" saves and shows "TESSA" in the title and the People list.
  - Dragging one Person onto another still opens the modal with the face-count order.
  - A merge of a named Person into an unnamed one leaves the result in Matched, sorted by its name, and found by Search.
