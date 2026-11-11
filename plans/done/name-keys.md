# Name keys: one locale-independent fold for every name the app keeps unique

## Goals

- Every name the app keeps unique is compared under one key, computed by one function in Scala, the same on SQLite and
  PostgreSQL and whatever the JVM's default locale.
- The key folds what a reader takes for the same name: letter case in any script ("Straße", "STRASSE" and "STRAẞE"; a Greek
  name with a final sigma and with a plain one), canonical forms (a precomposed "é" and "e" with a combining accent, which is
  what macOS file names carry) and compatibility forms (full-width "Ａlbum", the "ﬁ" ligature, "①").
- The stored, displayed name is the name as typed, in NFC, for every named thing, and its length is counted in that form.
- Every key fits the unique index that holds it on PostgreSQL.
- The column and the model field say what they hold: `name_key` / `nameKey`.
- A metadata value is de-duplicated and searched under the same key.

Out of scope: locale-aware sort order (the lists that order by the key order by it), accent-insensitive matching ("Émile" and
"Emile" are two names), the Library name rule's other checks (whitespace, control characters), full-text search folding
(`SearchWords`), any database-side collation or `lower()`.

## How it works

Six names are keyed, each under a unique index on its key:

| Name | Column, index | Key |
|---|---|---|
| Library, instance-wide | `repository.name_key`, `repository_01` | `Repository.nameKey` |
| Folder, among its siblings | `folder.name_key`, `folder_02 (repository_id, parent_id, name_key)` | `Folder.nameKey` |
| Album | `album.name_key`, `album_01 (repository_id, name_key)` | `Album.nameKey` |
| Location or Category, one pool | `location.name_key`, `location_01 (repository_id, name_key)` | `Location.nameKey` |
| Metadata field | `metadata_field.name_key`, `metadata_field_02 (repository_id, name_key)` | `UserMetadataField.nameKey` |
| Live Person | `person.name_for_sort`, `person_01` (partial) | `PersonService.nameKey` |

Each key is `NameKey.of(name)`. A service takes the typed name, holds it to its name rule (`Validators.validName`, or
`Repository.validName` for a Library), which returns it in NFC; the model derives `nameKey`; the DAO's insert and each rename
write the name and its key; the unique index refuses a second holder, and the service surfaces that as a `DuplicateException`.
A metadata value's equality, its keyword search column and a keyword filter go through the same `NameKey.of`, and
`face-combobox.js` mirrors the fold on the client.

## Decisions

| Concern | Decision |
|---|---|
| Where the fold lives | `altitude.core.util.NameKey`: `normalized(name)` (NFC), `of(name)` (the key), `MAX_KEY_BYTES` and `keyFits(name)`. Neither function trims; callers trim. Nothing else in the app folds a name or a metadata value for equality |
| The key | `NFKC(lower(upper(lower(NFKC(name)))))`, each case mapping under `Locale.ROOT`, with `java.text.Normalizer`. No new dependency |
| Why lower, upper, lower | The upper step is what folds: "ß" becomes "SS", every sigma becomes "Σ". The first lower brings "ẞ" (U+1E9E), which has no uppercase mapping of its own, to "ß" first. The last lower gives the key its readable form, a word-final sigma as "ς". The closing NFKC recomposes what the case mappings decompose |
| Compatibility forms | Folded in the key (NFKC). The stored name keeps them: it is normalized to NFC only |
| Known differences from Unicode's `NFKC_Casefold` | A dotless "ı" folds to "i" (its uppercase is "I"); default-ignorable code points are kept. Accepted |
| Idempotence | `of(of(x)) == of(x)` for every code point, so a key can be folded again safely |
| Stored names | NFC for every named thing, through `NameKey.normalized`: `Repository.normalizedName` and `PersonService.normalizedName` delegate to it |
| Name length | Counted in code points after NFC, since NFC can lengthen a name (U+FB2C is three code points): `Validators.validName(name, field, maxLength, minLength)` normalizes, then refuses with a `ValidationException` that is also the field's error (`VALUE_TOO_SHORT`, `VALUE_TOO_LONG`). Controllers keep their own length checks on the typed input; the folder and album add and rename dialogs show the service's `ValidationException` as a field error |
| Key cap | `NameKey.MAX_KEY_BYTES = 2000` UTF-8 bytes, checked by `keyFits` for every keyed name, Library names included (`Repository.validName`); over it is `C.Msg.Err.NAME_KEY_TOO_LONG`. PostgreSQL's btree refuses an index row over 2704 bytes, and `folder_02` spends about 90 on the header and two IDs. Only compatibility characters reach the cap (U+FDFA is a 33-byte key); 250 other characters stay under 1250 bytes |
| Naming | `name_key` in both `all.sql` files; `FieldConst.*.NAME_KEY`; `nameKey` on the row classes and the models. `person.name_for_sort` holds a named person's key under its own name, since it also holds an unnamed person's sort name |
| Key column type | `TEXT NOT NULL` on both engines for all five: a fold can lengthen a name ("ß" to "ss", "ﬃ" to "ffi"), so a 250-character name's key can pass 255 |
| Person names | `PersonService.nameKey` delegates to `NameKey.of`. `PersonService.validName` holds a name to 2 to 80 characters (`Validators.validName`) and refuses an unnamed person's form; `updateName` and `personIdForName` go through it. `isReservedName` tests the NFKC form, so a full-width "Ｕnknown 0042" is refused by validation and not by `person_01`. `PersonDao` builds an unnamed person's sort name from `PersonService.nameKey(UNKNOWN_NAME_PREFIX)` |
| The JavaScript mirror | `face-combobox.js` `nameKey` is `text.trim().normalize("NFKC").toLowerCase().toUpperCase().toLowerCase().normalize("NFKC")`; JavaScript's case mappings take no locale, so it matches the server. Equality (the Create row, the exact match) compares `nameKey`; the substring match compares `partialMatchKey`, `nameKey(x).replaceAll("ς", "σ")`, so a query that ends mid-word on a sigma ("σοφοσ") is part of "Σοφοσάκης". `parseAssignablePeople` computes both once per option. The server stays the authority |
| Metadata values | `UserMetadataValue.checksum` hashes `NameKey.of(value)`. `UserMetadataService`'s value cleaning normalizes every value to NFC, whatever the field type. `field_value_kw` holds `NameKey.of(value)`, and `SearchQueries.valueMatch` folds a `String` filter value the same way. A BOOL value is read by `UserMetadataValue.asBoolean` ("1"/"0", true/false in any case), in validation and in `SearchDao`'s indexing |
| Sorting | The album, Location and folder lists order by `name_key`, `FolderService.assembleTree` sorts by `nameKey`, the search's Location path key concatenates `name_key`, and `UserMetadataService.toJson` orders fields by `(nameKey, id)`. The order follows the fold ("ß" sorts as "ss") |
| Schema | In both `all.sql` files; no versioned migration and no `schemaVersion` bump |

## Constraints

- The fold is never done in SQL, and no query calls `lower()` or relies on a collation for name equality.
- A name's uniqueness is enforced by the unique index, with the service turning the violation into a `DuplicateException`.
- No test depends on the JVM's default locale; a test that sets it restores it in a `finally` (`withDefaultLocale` in the
  integration tests).

## Changes

### A. The fold and the name rule

1. **`NameKeyTests` (unit).** A table of names and the key each must have, and of pairs that share a key or keep different
   ones: "Straße" / "STRASSE" / "STRAẞE" share `strasse`; "σοφος" / "σοφοσ" / "ΣΟΦΟΣ" share one; "Ａlbum" and "album"; "ﬁle"
   and "FILE"; "e" + U+0301 and "É"; "①" and "1"; "Ω" and the ohm sign U+2126; "K" and the kelvin sign U+212A; "ǅ" and "ǆ";
   "Émile" and "Emile" differ. `of("I")` is `i` with the default locale set to `tr`. Idempotence over every code point outside
   the surrogates. `normalized` returns NFC and leaves a full-width letter as it is. Registered in `AllUnitTestSuites`.
2. **`altitude/src/altitude/core/util/NameKey.scala`.** `normalized`, `of`, `MAX_KEY_BYTES` and `keyFits`, with the scaladoc
   carrying the reasoning of the Decisions table: why Scala and not SQL, why the three case mappings, what differs from
   `NFKC_Casefold`, how the cap relates to PostgreSQL's index row limit.
3. **`Validators.validName`.** Normalizes, checks the length in code points and the key cap, and returns the normalized name
   or throws a `ValidationException` carrying the field's error. `Const.Msg.Err.NAME_KEY_TOO_LONG`;
   `Api.Constraints.MAX_METADATA_FIELD_NAME_LENGTH = 250`. `Repository.validName` adds the key cap to the Library rule.

### B. The five name keys

4. **Schema.** In `postgres/all.sql` and `sqlite/all.sql`: `name_key TEXT NOT NULL` on `repository`, `metadata_field`,
   `folder`, `album` and `location`; the five unique indexes name it; the `repository_01` comment describes the key as
   `NameKey.of`.
5. **Constants and rows.** `FieldConst`: `NAME_KEY = "name_key"` for the five. `RepositoryRow`, `MetadataFieldRow`,
   `FolderRow`, `AlbumRow`, `LocationRow`: `nameKey`, with the `RepositoryRow` scaladoc to match.
6. **Models.** `Repository`, `UserMetadataField`, `Folder`, `Album`, `Location`: `val nameKey: String = NameKey.of(name)`.
   `Folder.equalityKey` uses it. `Repository.normalizedName` ends with `NameKey.normalized`.
7. **Writes.** `FolderService.add` and `rename`, `AlbumService.add` and `rename`, `LocationService.addLocation`,
   `addCategory` and `rename`, and `UserMetadataService.addField` pass the name through `Validators.validName` (each service's
   private `validName`). The renames write the name and the model's `nameKey`. `FolderDao`, `AlbumDao`, `LocationDao`,
   `MetadataFieldDao` and `RepositoryDao` insert `nameKey`; `addField` checks for a held key by `NAME_KEY`.
   `FolderActionController` and `AlbumActionController` turn the service's `ValidationException` into the form's field error
   on add and rename.
8. **Reads.** The `ORDER BY` clauses of `AlbumDao`, `LocationDao` and `FolderDao`, `FolderService.assembleTree`'s sort, the
   two trace lines in `SearchDao`, and the Location path key in `SearchQueries` name `name_key` / `nameKey`.
9. **Tests.** `IntegrationTestCore` provides `withDefaultLocale(locale)(f)`, `lengthenedByNfc` (U+FB2C), `longKeyName`
   (U+FDFA) and `refusesName(field, message)(f)`. For each of folder (same parent), album, location and metadata field: adding
   or renaming to a name that differs only by case fold ("Straße" then "STRASSE"), canonical form or compatibility form is a
   duplicate; the stored name reads back in NFC; a name added under a `tr` default locale collides with the same name added
   under the root locale; a name within its length as typed but over it in NFC, and one whose key passes the cap, are refused
   with the field's error. `FolderActionControllerTests` and `AlbumActionControllerTests` see the refusal as a field error.
   `RepositoryServiceTests` reads `NAME_KEY` and `nameKey`, and covers the case-fold duplicate and the key cap.
   `SearchSqlTests` expects `name_key` in the path key.

### C. Person names and metadata values

10. **Person.** `PersonService.nameKey` returns `NameKey.of(name)`, `normalizedName` returns `NameKey.normalized(name)`,
    `validName` holds a name to 2 to 80 characters, the key cap and the reserved-name check, and `isReservedName` matches the
    NFKC form. `PersonDao.getPersonSortName` keys the unnamed prefix with `PersonService.nameKey`. The `person_01` comment in
    both `all.sql` files describes the key as `NameKey.of`. `PersonServiceTests`: naming a person "WEISS" or "σοφοσ" when
    "Weiß" or "σοφος" is live is refused to a rename and joins the holder on a move by name; a full-width or circled-digit
    reserved name is a `ValidationException`; a name over 80 in NFC, under 2 in NFC, or over the key cap is refused, the person
    keeping their name; the Turkish-locale test stays.
11. **`face-combobox.js`.** `nameKey` uses the fold of the Decisions table, with its comment naming `NameKey.of`;
    `partialMatchKey` reads "ς" as "σ" for the substring match; `parseAssignablePeople` stores both on each option, and
    `matches`, the Create row and the exact match read them.
12. **Metadata values.** `UserMetadataValue.checksum` hashes `NameKey.of(value)`; `UserMetadataValue.asBoolean` reads a BOOL
    value. `UserMetadataService`'s value cleaning normalizes each value to NFC, its BOOL validation uses `asBoolean`, and
    `toJson` lists every field ordered by `(nameKey, id)`. `SearchDao.addParameters` writes `NameKey.of(valueStr)` to
    `field_value_kw` and `asBoolean(valueStr)` to the Boolean column, and `SearchQueries.valueMatch` folds a `String` filter
    value with `NameKey.of` before `Columns.equalTo`. Tests: a keyword value added twice in different case folds or forms is
    one value; keyword and TEXT values are stored in NFC; a keyword filter typed in another case, canonical form or
    compatibility form finds the asset, on both engines; BOOL values "1", "TRUE" and "0" match their filters; `toJson` orders
    fields by key.

### D. Docs

13. **`altitude/AGENTS.md`.** A **Names** section: the fold, the keyed names, the length rule (`Validators.validName`, the key
    cap), and the rule for a new named thing: store `NameKey.normalized(name)` through `Validators.validName`, keep
    `NameKey.of(name)` in a `name_key TEXT NOT NULL` column under a unique index, fold nowhere else. The Libraries name rule
    paragraph, the Albums and Locations sections and the search index section name `name_key` and `NameKey.of`.
14. **`docs/faces.md`.** The **Name** action: the name stored through `NameKey.normalized`, `name_for_sort` as `NameKey.of`,
    the length rule and the NFKC reserved-name check.
15. **`CONTEXT.md`.** A glossary entry for **Name key**: the folded form of a name under which two names are the same name.
16. **`altitude/views/AGENTS.md` and `docs/test-coverage.md`.** The combobox's matching (`nameKey`, `partialMatchKey`); the
    coverage entries for the fold, the duplicates, the length and key-cap refusals, BOOL indexing and `toJson` order.

## Verification

- `make test` passes on all four bundles (unit, SQLite, PostgreSQL, controllers).
- `mill mill.scalalib.scalafmt/checkFormatAll` is clean.
- In Node, the JavaScript `nameKey` gives the keys of the item 1 table, and `partialMatchKey` finds "σοφοσ" in "Σοφοσάκης".
- In a browser on a second app instance, typing "WEISS" in the face combobox offers the existing "Weiß" and no Create row.
- The dev database is recreated from `all.sql` before the dev server runs the change.
