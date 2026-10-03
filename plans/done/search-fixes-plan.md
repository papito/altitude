# Search fixes

## Goals

- A `TEXT` metadata value is searchable from the transaction that adds it, like a value of any other field type.
- A word written with a camelCase hump and the same word written in one case find each other: `mcdonald` finds
  `McDonald`, and `McDonald` finds `Mcdonald` and `MCDONALD`.

Out of scope: phrases that match across the boundary between two values of a Search document, or between two readings
of one value, and a rebuild operation for Search documents (a database is created from scratch).

## Current behavior

- `SearchService.addMetadataValue` and `addMetadataValues` return before the DAO for a field whose type is in
  `NON_FACETED_FIELD_TYPES` (`TEXT`). The return skips the `metadata_parameter` insert, which is the intent, and also
  `SearchDao.writeDocument`, which is not: after `UserMetadataService.addMetadataValue` on a `TEXT` field the Search
  document lacks the value until the asset's next `reindexAsset` (a value update or delete, or a rename).
- The same rule is not applied on the other path. `SearchDao.indexMetadata` (import and reindex) calls the DAO's
  `addMetadataValues` directly, so a `TEXT` value gets a `metadata_parameter` row whose three value columns are all
  NULL. A `DATETIME` value gets such a row on every path: the insert has no `field_value_dt`. No filter can match these
  rows (`SearchQueries` reads the table only for the metadata filters).
- `addMetadataValues` ends with `writeDocument`, so `indexAsset` and `reindexAsset` write the document once per
  metadata field and once more themselves.
- `SearchWords.of` splits on a camelCase hump (a lower-case letter followed by an upper-case one), so `McDonald` is
  the words `mc donald`. The hump is the only boundary that depends on case: text in one case has none. Typed
  `mcdonald` is one word and matches neither `mc` nor `donald`; typed `McDonald` is `mc donald` and does not match a
  stored `Mcdonald` or `MCDONALD`, which are the one word `mcdonald`. This holds for names (people, Locations,
  Categories, folders, albums) and for the Search document alike.

## Design

### Words: two readings of a hump

`SearchWords` keeps `of(text)` and gains `variants(text): Seq[Seq[String]]`: the word sequences a text can be read
as, none for text without a word. The first is `of(text)`. The second is the same rule without the hump boundary
(`McDonald_beachSunset.jpg` is `mcdonald beachsunset jpg`), present only when it differs from the first, so text
without a hump has one variant. Diacritics, the split on non-letters and non-digits, and the letter/digit boundary are
shared by both.

- **Typed Search text**: `SearchText.parse` reads every typed term as its variants. `SearchTerm` carries them
  (`variants: Seq[Seq[String]]`, never empty, none empty) in place of one word sequence, and a term matches when any of
  its variants does. Typed `McDonald` is `mc donald` or `mcdonald`; typed `mcdonald` is `mcdonald`.
- **Names**: `SearchService.resolveText` computes the variants of each name once, and `SearchTerm.isIn` holds when any
  variant of the term is in any variant of the name. A phrase stays confined to one variant of one name.
- **Search document**: `SearchDao.writeDocument` writes, for the file name and then each user metadata value, every
  variant in order. `McDonald_beachSunset.jpg` has the body
  `mc donald beach sunset jpg mcdonald beachsunset jpg`. A value without a hump contributes its words once, so the
  body grows only for values that have one.
- **Engines**: `SearchDialect.textMatch` binds one query string per term, the OR of its variants: PostgreSQL
  `mc <-> donald:* | mcdonald:*` (`<->` binds tighter than `|`), SQLite `"mc donald" * OR "mcdonald" *`. A term with
  one variant binds the string it binds today.

`mcdonald`, the prefix `mcdon`, `McDonald`, `donald`, `sunset` and `beachsunset` all find
`McDonald_beachSunset.jpg`; `McDonald` also finds `Mcdonald.jpg` and `MCDONALD.jpg`.

The alternative is to drop the hump boundary altogether, which needs no second variant but loses `sunset` finding
`beachSunset.jpg`.

### TEXT values in the Search document

Which field types have `metadata_parameter` rows becomes the DAO's rule, applied on every path:

- `jdbc.SearchDao` has `FACETED_FIELD_TYPES` (`KEYWORD`, `NUMBER`, `BOOL`: the types whose value column the insert
  writes) and a private `addParameters(asset, field, values)` that inserts rows only for a faceted field type.
  `indexMetadata` calls it, so import and reindex stop writing all-NULL rows for `TEXT` and `DATETIME` values.
- `addMetadataValues` is `addParameters` followed by `writeDocument`; `addMetadataValue` delegates to it as today.
  `indexAsset` and `reindexAsset` write the document once, after `indexMetadata`.
- `SearchService.addMetadataValue` and `addMetadataValues` lose their early return and pass every field type to the
  DAO; `NON_FACETED_FIELD_TYPES` goes.

`UserMetadataService.addMetadataValue` reads the asset after storing the value, so the document written in its
transaction contains it.

## Tasks

1. **Word variants.** `SearchWords.variants` with unit tests in `SearchWordsTests` (none without a word, one variant
   without a hump, two with, the letter/digit boundary in both); `SearchTerm.variants` and `SearchText.parse` over
   them, with `SearchTextTests`; `resolveText`, `writeDocument` and both dialects' `textMatch` over variants, with
   `SearchSqlTests` pinning the two-variant query strings. Integration tests first (`SearchServiceTests`, both
   engines): a file named `McDonald_beachSunset.jpg` is found by `mcdonald`, `mcdon`, `McDonald`, `donald`, `sunset`
   and `beachsunset`; files named `Mcdonald.jpg` and `MCDONALD.jpg` are found by `McDonald`; a person, a folder, an
   album, a Location and a Category named with a hump are each found by the one-case word and by the part after the
   hump, and one named in one case is found by the word typed with a hump; a phrase matches within one variant of a
   name; the document of a file name without a hump holds its words once.

2. **TEXT values.** `FACETED_FIELD_TYPES` and `addParameters` in `jdbc.SearchDao`, the single document write in
   `indexAsset` and `reindexAsset`, the early returns removed from `SearchService`. Integration tests first, both
   engines: a `TEXT` value added through `UserMetadataService.addMetadataValue` is found by the next search with no
   reindex in between; an imported or reindexed asset with a `TEXT` and a `DATETIME` value has no
   `metadata_parameter` row for either and is still found by their words; `KEYWORD`, `NUMBER` and `BOOL` filters
   behave as before, with one row per value after a reindex.

3. **Documentation.** `../../altitude/AGENTS.md` (**Text search**: the variants in the word rule, in typed terms and in the
   document body, name matching over variants, the two-variant query strings, `TEXT` values written with every other
   type, the faceted rule in the DAO), root `../../CONTEXT.md` if its definitions of a term or of the Search document no
   longer hold, and `../../docs/test-coverage.md`.
