# SHA-256 checksums

## Goals

- An asset's checksum is the SHA-256 of its bytes, so two different files in a repository can no longer be reported
  as duplicates of each other, and an original can be verified against its file with any standard tool
  (`sha256sum`).
- A Face's checksum is the SHA-256 of its crop, for one hashing scheme across the schema.
- Nothing about import speed changes for the user: the hash is streamed from the staged file as it is today, and the
  JDK's SHA-256 runs well above any disk or network pipe an import comes through.
- This closes the CRITICAL duplicate-check item in [docs/READINESS.md](../docs/READINESS.md).

## Status

Planned.

## Decisions

| Concern | Decision |
|---|---|
| Algorithm | SHA-256 through `java.security.MessageDigest`, no new dependency. The JDK intrinsic runs at about 2 GB/s on a CPU with the SHA extensions (measured on the dev box) and 400 to 500 MB/s without; a LAN import arrives at 125 MB/s to 1.2 GB/s, and the pipeline reads the file several more times after the hash |
| Representation | Lowercase hex, 64 characters, in the model and in the database: `checksum: String` on `Asset` and `Face`, `CHAR(64) COLLATE "C"` on PostgreSQL and `CHAR(64)` on SQLite, as the ID columns are typed. A string needs no ScalaSql type mapper, no conversion in the DAOs or the `Query` parameter map, serializes as itself in the models' JSON and reads in `psql`. The alternative, `BYTEA`/`BLOB` with a `geny.Bytes` row field, saves 32 bytes per row and index entry and is not worth a second column type |
| Hasher | `altitude.core.util.Sha256` with `hex(bytes: Array[Byte])`, `hex(stream: InputStream)` and `hex(path: Path)`, each the same value over the same bytes; the stream overload feeds `MessageDigest.update` from an 8 KiB buffer. `MurmurHash` keeps `hash32(bytes)` alone, for the ETag in `decorators.revalidated` and `UserMetadataValue`, and loses its stream and path overloads |
| Faces | `FaceDetectionService.extractFaces` hashes the crop PNG with `Sha256.hex`. The crop is a few kilobytes, so the cost is nil, and `face_01` keeps its shape |
| Schema | Both `all.sql` files only, no versioned migration and no `schemaVersion` bump: the database is recreated from scratch. `asset_01` and `face_01` keep their columns and predicates |
| Single-pass hashing | Out of scope. An upload is moved into staging, not copied, so its hash is a read of its own either way; a copied import (`stageCopy`) could hash as it copies, but that is a separate change to `StagingService` and worth doing only once an import is measured to be read-bound |
| Tests | TDD on the server side: the hasher's unit test, the import test that pins the checksum to the file's `sha256sum`, then the type change |

## How it works

`LibraryService.stagedFileToAsset` builds the asset of a staged file with `checksum = Sha256.hex(staged)`: the file is
streamed once through a `MessageDigest`, as `MurmurHash.hash32(path)` streams it today, and the digest is rendered as
64 lowercase hex characters. `AssetService.getByChecksum(checksum: String)` looks the value up as before, through the
`Query` parameter map, and `CheckDuplicateFlow`, `IndexFlow` (the unique index `asset_01`) and
`LibraryService.restoreRecycledAssets` compare nothing else. The upload path (`ImportController`) and the copy path
(`convImportAsset2dataAsset`) both end in `stagedFileToAsset`, so there is one call site.

`FaceDetectionService.extractFaces` sets `checksum = Sha256.hex(imageBytes.toArray)` on each Face, over the raw crop
PNG as today; `face_01` makes it unique within the asset, and `RecognizeFacesFlow` reports the duplicate as before.

The models carry the hex string: `Asset.checksum: String` and `Face.checksum: String`, through `AssetRow.checksum:
T[String]`, `FaceRow.checksum: T[String]`, the `toModel` and `makeModel` readers of both DAOs (`asInstanceOf[String]`),
`AssetDao.add` (the value binds as a string) and the two `FaceDao.add` statements (`setString(11, face.checksum)`).
The columns are `checksum CHAR(64) COLLATE "C" NOT NULL` on PostgreSQL and `checksum CHAR(64) NOT NULL` on SQLite, with
a comment naming SHA-256 in hex; `asset_01` and `face_01` are unchanged.

`Sha256` lives in `altitude/core/util/Sha256.scala`:

```scala
object Sha256:
  def hex(bytes: Array[Byte]): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
  def hex(path: Path): String = ...            // streams the file through hex(stream)
  def hex(stream: InputStream): String = ...   // 8 KiB reads into MessageDigest.update
  private def hex(digest: Array[Byte]): String = HexFormat.of().formatHex(digest)
```

`java.util.HexFormat` renders lowercase by default. `MurmurHash` keeps only `hash32(data, seed)`.

## Tasks

Each group is one reviewable change; its guide and comment updates go in the same change.

### A. The hasher

1. **Red**: `altitude/test/src/altitude/core/unit/Sha256Tests.scala`, registered in `AllUnitTestSuites` in place of
   `MurmurHashTests`: the hex of the empty array is
   `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` and of `abc` is
   `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad` (the FIPS 180-4 vectors); the streamed hash
   equals the array hash for the lengths `MurmurHashTests` covers today (0 to 70, around the 8 KiB read boundaries,
   50,000); a temporary file hashes as its bytes.
2. **Green**: `altitude/src/altitude/core/util/Sha256.scala` as above. Delete `MurmurHashTests` and the stream and
   path overloads of `MurmurHash`, which nothing else uses.

### B. Assets

3. **Red**: in `AssetImportServiceTests`, the JPEG import test asserts `importedAsset.checksum` equals the SHA-256 hex
   of the fixture file, computed in the test with `MessageDigest` over `Files.readAllBytes`, in place of
   `should not be 0`; the asset read back has the same. Compiles only once the type changes, so the group is one
   red-green cycle.
4. **Green**: `Asset.checksum: String`; `AssetRow.checksum: T[String]`; `AssetDao.toModel` and `makeModel`;
   `AssetService.getByChecksum(checksum: String)`; `LibraryService.stagedFileToAsset` calls `Sha256.hex(staged)` and
   drops the `MurmurHash` import; the `asset.checksum` columns in both `all.sql` files.
5. **Tests that build checksums by hand**, to a 64-character hex string:
   - `TestContext.makeAsset`: `Sha256.hex(Random.nextBytes(32))`, or a helper `TestContext.randomChecksum` the face
     helpers share.
   - `PurgePipelineServiceTests` (distinct checksums per recycled asset): `Sha256.hex(checksum.toString.getBytes)` or
     the helper.
   - `CoreModelTests` (`checksum = 1`, `7`): any fixed 64-character string.
   - `SearchPlans.seedCopies`: the copies' own checksums are `1000000 + g.n`; render them as text padded to 64,
     `lpad((1000000 + g.n)::text, 64, '0')` on PostgreSQL and `printf('%064d', 1000000 + g.n)` on SQLite, as the
     copies' IDs already are.
   - `SearchMapTests.seedGeotagged` derives coordinates from `checksum % 160` and `checksum % 360` and picks one copy in
     ten with `checksum % 10`; with a text checksum it derives them from the copy's numeric ID instead
     (`CAST(id AS BIGINT)` on PostgreSQL, `CAST(id AS INTEGER)` on SQLite; the seeded IDs are zero-padded digits), with the
     same predicate on the seeded copies.
   - `SearchServiceTests` (the live-copy lookup binds `asset.checksum`): unchanged, the bind is a string now.
6. **Docs**: [altitude/AGENTS.md](../altitude/AGENTS.md) (the staged-files paragraph: `Sha256.hex(path)`;
   the indexes paragraph if it names the column's type), [docs/READINESS.md](../docs/READINESS.md) (the duplicate-check
   item becomes done, with the new facts), [docs/test-coverage.md](../docs/test-coverage.md) (the import coverage
   line, which now pins the exact checksum).

### C. Faces

7. **Red**: in `FaceRecognitionServiceTests` or `ImportPipelineServiceTests`, a detected Face's checksum is the
   SHA-256 hex of its crop file as written to the face store (the crop is written by `PersonService`), asserted
   over one face of an imported fixture.
8. **Green**: `Face.checksum: String`; `FaceRow.checksum: T[String]`; `FaceDao.toModel` and `makeModel`; `setString`
   in both `FaceDao.add` statements; `FaceDetectionService.extractFaces` calls `Sha256.hex` and drops the
   `MurmurHash` import; the `face.checksum` columns in both `all.sql` files.
9. **Tests that build face checksums by hand** (`TestContext.addTestFace`, `addTestFacesAndAssets`,
   `FaceRecognitionServiceTests`, `PersonServiceTests`): the shared random-checksum helper from task 5.
10. **Docs**: [docs/faces.md](../docs/faces.md) (the crop's checksum is `Sha256.hex` of the raw crop PNG; the column's
    type).

### Verification

- `make test-focused` on the new and changed suites, then `make test` and `make test-psql`: the duplicate import, the
  restore of a recycled copy, the same-crop-twice face test and the search plan suites (`asset_01`, `face_01`) all
  pass on both engines.
- An import through the dev server of a file whose `sha256sum` is known shows that value in the `asset` row.
- `make lint` clean.
