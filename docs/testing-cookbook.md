# Testing cookbook

Recipes for common checks and fixtures. How the tests are organized and run is in
[README-TESTING.md](../README-TESTING.md); the rules for a test are in [altitude/AGENTS.md](../altitude/AGENTS.md),
under **Tests** and **Test Structure**.

Unless a recipe says otherwise, the helpers belong to `IntegrationTestCore`, so an integration test has them. The
fixtures are `TestContext`'s (`testContext` in a test), and the shared utilities `IntegrationTestUtil`'s, in
`altitude/test/src/altitude/test/`.

## Assertions

### Check that an exception is thrown

```scala
intercept[NotFoundException] {
  // your code here
}
```

### Check that a collection is sorted

This checks that the items are sorted by date, newest first:

```scala
val dates = myCollection.map(_.createdAt.get)
dates shouldBe dates.sortWith(_.isAfter(_))
```

On a failure, ScalaTest prints both lists, so the item out of place shows.

### Say which item failed

An assertion in a loop, or one on a long text, gets a clue, which the failure prints before its own message:

```scala
for (path <- List(s"r/$repoId", s"pipeline/r/$repoId", s"settings/r/$repoId")) {
  withClue(s"[$path]: ") {
    requests.get(s"$host/$path", cookies = testContext.cookies, check = false).statusCode shouldBe 200
  }
}
```

```scala
withClue(plan) {
  plan should include("asset_geo")
}
```

### Check that a Library was left alone

`testContext.rowsOf(library)` reads every row of every table that belongs to a Library, as text. Two equal reads mean
nothing of the Library changed in between, which is how a test proves an action on one Library did not touch another:

```scala
val before = testContext.rowsOf(other)
testApp.service.library.recycleAssets(Set(asset.persistedId))
testContext.rowsOf(other) shouldBe before
```

## The database

### Making arbitrary DB queries

`query(sql, values*)` returns the raw JDBC rows, a map of column to value each. It runs on the current transaction's
connection, so it goes inside one:

```scala
val row = testApp.txManager.asReadOnly {
  query("SELECT name FROM person WHERE id = ?", person.persistedId).head
}
row("name") shouldBe "Gemma"
```

### Updating DB records with arbitrary SQL

`update(sql, values*)`, inside a transaction that writes:

```scala
testApp.txManager.withTransaction {
  update("UPDATE account SET is_active = ? WHERE id = ?", false, account.persistedId)
}
```

### Code that differs between the databases

The integration tests run on both engines, so where the engines differ, the test asks which one it is on:

```scala
testApp.dataSourceType match {
  case Const.DbEngineName.POSTGRES => // ...
  case _ => // SQLite
}
```

A date written by hand goes through `getSqlDateTime`, which gives each engine's storage form (SQLite stores text):

```scala
testApp.txManager.withTransaction {
  update("UPDATE asset SET created_at = ? WHERE id = ?", getSqlDateTime(timestamp), asset.persistedId)
}
```

A test that only means something on one engine says so with `assume`, which marks it canceled on the other, not
passed:

```scala
assume(testApp.dataSourceType == Const.DbEngineName.POSTGRES)
```

### Which index a query reads

A suite that mixes in `SearchPlans` (`extends IntegrationTestCore with SearchPlans`) reads the engine's plan:
`planOf(statement)` for a search statement, `lookupPlanOf(sql, values)` for a hand-written one. Over a test's handful of
rows every index costs the same, so the planner's choice means nothing until there are a library's worth of rows.
`atScale(seed)(read)` adds them, analyzes the tables, runs `read` and rolls it all back:

```scala
val plan = atScale(seedCopies(template.persistedId, copies = 5000, folders = 500))(planOf(statement))

withClue(plan) {
  (plan should not).include(if (isPostgres) "Seq Scan on asset" else "SCAN asset")
}
```

A test that seeds at scale is tagged `Slow`.

## Fixtures

### Creating assets

`persistAsset` imports a random image through the whole pipeline: a file, a Preview, face detection, Image quality and
a hash.

```scala
val assets = List.fill(5)(testContext.persistAsset())
```

When the test reads none of that, `insertAsset` is much faster: it persists and indexes an asset with no file, Preview,
faces, Image quality or hash.

```scala
val assets = List.fill(500)(testContext.insertAsset())
```

### Creating assets with custom data

`persistAsset` takes a `repository`, `user`, `folder`, `metadata`, `isTriaged` and `isRecycled`. `makeAsset` builds
an asset without saving it, for `insertAsset` or a service call, and also takes a `filename` and a capture date
(`originalCreatedAt`, with its `originalCreatedAtSource`).

```scala
val triaged = testContext.persistAsset(isTriaged = true)
val inFolder = testContext.persistAsset(folder = Some(folder))
val named = testContext.insertAsset(testContext.makeAsset(filename = "beach.jpg"))
```

### Setting what an import would have found

An asset made by `insertAsset` has none of what the pipeline measures, and one made by `persistAsset` has whatever its
random image gave. A test that depends on a value sets it:

| Helper | What it sets |
|---|---|
| `setAssetDates(assetId, taken, imported)` | The capture time and the import time, in each engine's storage form |
| `setAssetCoordinates(assetId, latitude, longitude)` | The location, as an import of a geotagged file would |
| `setAssetType(assetId, assetType)` | The media type, so an image fixture can stand for a video |
| `setAssetImageQuality(assetId, quality)` | The Image quality metrics, or `None` for unmeasured |
| `setAssetHistogram(assetId, histogram)` | The Histogram, or `None` |
| `setAssetSequence(assets, sequenceId)` | A Sequence for assets still in triage, after every fixture is persisted |

```scala
val asset = testContext.insertAsset()
testContext.setAssetCoordinates(asset.persistedId, 48.8566, 2.3522)
testContext.setAssetImageQuality(asset.persistedId, Some(ImageQuality(sharpness = 120.0, luminance = 0.5,
  clippedHighlights = 0.0, crushedShadows = 0.0, score = 80)))
```

### Creating assets with people and faces

```scala
val people = List.fill(2)(testApp.service.person.addPerson(Person()))
testContext.addTestFacesAndAssets(people, assetCount = totalAssets)
```

This imports `totalAssets` assets and gives each a face of every person. The people must be persisted first.

> [!WARNING]
> The "faces" are not real faces: their boxes, embeddings and images are random, but valid, data.

A recognition test that needs control over how alike two faces are gives them known embeddings.
`TestContext.unitVector(seed)` is a reproducible face embedding, about a cosine distance of 1 from another seed's, and
`TestContext.between(a, b, parts)` mixes two, nearer `a` the more `parts`:

```scala
val face = testContext.addTestFace(person, asset, TestContext.unitVector(1), sharpness = Some(123.5))
```

### Scene embeddings at a chosen similarity

`TestContext.atCosine(embedding, cosine, seed)` is a Scene embedding at exactly `cosine` to another. With it, a search
or Sequence test places an asset just inside or just outside a threshold, with no model run:

```scala
val near = testContext.insertAsset(
  testContext.makeAsset().copy(sceneEmbeddings = SceneEmbeddings(siglip2 = Some(TestContext.atCosine(base, 0.4, seed = 1)))))
```

### Real images and synthesized ones

The files under `altitude/test/resources/import/` are imported as they are: `images/` (`images/exif/` holds the GPS and
orientation cases), `people/` (real faces) and `audio/`.

```scala
val importAsset = IntegrationTestUtil.getImportAsset("images/exif/gps-north-east.jpg")
val asset = testApp.service.library.addAsset(testApp.service.library.convImportAsset2dataAsset(importAsset))
```

A path to a resource comes from `TestResources.importFile(relPath)`, never from the resource URL's `getPath`, which is
wrong on Windows.

Media made at test time, so no binary lives in git:

* `IntegrationTestUtil.generateRandomImagBytesBgr(dimensions)`: a random PNG.
* `TestVideos`: MP4 clips of stills held for a span each, `TestVideos.clip(Seq(TestVideos.person("affleck.jpg", 3), TestVideos.black(2)))`,
  and named ones (`oneFace`, `twoPeopleInSequence`, `blackLeader`, `dated`, `portrait`, `undecodable`), each encoded
  once a JVM.
* `BurstFrames`, a trait for an `IntegrationTestCore` suite: `BurstFrames.frame(n)` is one image shifted and brightened
  a little more each frame, so the checksums differ and the hashes are near; `importFrame(bytes)` imports one with a
  camera and a capture time.

## Accounts and Permissions

### A second account or Library

An integration test starts with one account and one Library, the context's (a controller test makes its own). More are made with `persistUser` and
`persistRepository`; a Library grants nobody until `grant` does. The services act as the context account in the
context Library, so a test switches them to act as another:

```scala
val other = testContext.persistUser()
val otherLibrary = testContext.persistRepository()
testContext.grant(other, otherLibrary, Permissions.of(Permission.Import))

switchContextUser(other)
switchContextRepo(otherLibrary)
```

### Narrowing Permissions

A test thread holds every Permission, as an Admin's request does. A test of a rule that reads them narrows them around
the call:

```scala
RequestContext.permissions.withValue(Permissions.of(Permission.Recycle)) {
  testApp.service.library.recycleAssets(Set(asset.persistedId))
}
```

## Controller tests

A controller test extends `ControllerTestCore`. It builds its fixtures through the suite's own instance, logs in, and
makes its requests to a real server that `withServer(App)` starts:

```scala
testContext.persistRepository()
val repoId = testContext.repository.persistedId
login()

withServer(App) {
  host =>
    testContext.persistAsset()

    val response = requests.get(s"$host/api/folder/r/$repoId/tree", cookies = testContext.cookies)

    response.statusCode shouldBe 200
    val root = ujson.read(response.text())
    root("numOfAssets").num shouldBe 1
}
```

* `login()` logs in the context account; `loginAs(accountType, permissions)` logs in a new account of that type and
  answers its Library's ID.
* A request expected to fail passes `check = false`, or `requests` throws on a status of 400 and up. A redirect is
  checked with `maxRedirects = 0`.
* An HTML answer is checked with `response.text() should include(...)`; an htmx header with
  `response.headers.get("hx-redirect")`.
* `FolderControllerTests` is a short example to copy.

## Settings

An integration test changes a Setting for its own run alone. `withSetting` saves the value, runs the block and saves the
default back, so the next suite sees the seeds:

```scala
withSetting(Settings.FACE_RECOGNITION_HNSW_EF_SEARCH, "150") {
  // ...
}
```

A unit test, with no database, builds the Settings it hands the code with `TestSettings`, the seeds with its own values
over them:

```scala
val settings = TestSettings(Map(Settings.MAP_GEOCODER_ENABLED -> "true", Settings.MAP_GEOCODER_URL -> stub.url))
```

## Time

### Wait for something to happen

Work on another thread (an import, a render, a Watch folder scan) is waited for with ScalaTest's `Eventually`, which
retries the block until it passes or the time is up:

```scala
Eventually.eventually(Eventually.timeout(Span(30, Seconds)), Eventually.interval(Span(100, Millis))) {
  importedCount() shouldBe batchSize
}
```

### Check that something does not happen

Nothing signals that a thing did not happen, so a test that proves it waits a fixed time, long enough for it to have
happened, and says why in a comment:

```scala
// Long enough for the queue to have refused the offers it has no room for, if it refuses any
Thread.sleep(500)
offers.flatMap(_.value).filter(_.isFailure) shouldBe empty
```

A `Thread.sleep` is never a wait for something that will happen: that is `Eventually`.

### The JVM's time zone and locale

Code that reads the JVM's default zone or locale is tested under another with a helper that sets it back afterwards:
`IntegrationTestUtil.withJvmTimeZone("Asia/Tokyo") { ... }` and `withDefaultLocale(Locale.GERMANY) { ... }`.

## Concurrency

### Two writers at once

`contend(first, second)` runs each on a thread of its own, in its own transaction, and holds `first`'s transaction open
until `second` is waiting on it: on a row lock on Postgres, on the one write connection on SQLite. The test then checks
what both left:

```scala
val ensured = new ConcurrentLinkedQueue[String]()
def ensure(): Unit = ensured.add(folders.ensurePath(parent.persistedId, Seq("Wedding")).persistedId): Unit

contend(first = ensure(), second = ensure())

ensured.toArray.distinct.length shouldBe 1
```

`onAnotherThread(f)` starts one thread, which acts as the test's account in its Library but runs outside its
transaction. It answers the thread and what `f` threw, to rethrow after the join:

```scala
val (thread, failure) = onAnotherThread(testApp.service.library.recycleAssets(Set(asset.persistedId)))
thread.join()
failure.get.foreach(throw _)
```

SQLite has one write connection, so writers never truly overlap there. A concurrency test is proven to fail on Postgres
first (`make test-focused-psql`).

## Making things fail

### A database write that fails

`refuse(event, table, rowId)` has the database reject one row's `DELETE` or `UPDATE OF <column>`, as a failing statement
would, and answers the cleanup that lifts it:

```scala
val allowDeleting = refuse("DELETE", "import", expired.persistedId)
try {
  intercept[SQLException](testApp.service.imports.prune())
} finally allowDeleting()
```

### A file store that fails

`breakFileStore(repository)` replaces the Library's `files` directory with a plain file, so storing any asset there
fails; `restoreFileStore(repository)` puts it back.

### An outside service

The tests make no outside calls, and they rarely mock: Mockito is in three unit suites alone. An HTTP service is
replaced by a local stub. `NominatimStub` is the geocoder's: it answers what the test sets and remembers the request.

```scala
val stub = NominatimStub()
stub.response = (200, """[{"place_id": 1, "display_name": "Paris", "lat": "48.85", "lon": "2.35"}]""")
stub.start()
try {
  // the code under test, with Settings.MAP_GEOCODER_URL set to stub.url
  stub.lastQuery.get should include("Paris")
} finally stub.stop()
```

### Something a test JVM cannot do

A crash, an exit, or a start under other environment variables runs in a second JVM with the test's classpath.
`ChildJvm.run(mainClass, arguments, environment)` answers its exit code and its output, and kills it at a timeout:

```scala
val (exitCode, output) = ChildJvm.run(
  "altitude.core.App",
  Seq.empty,
  environment => environment.put("ALTITUDE_ENV", "dve"),
  // The prod logging configuration, in place of the test run's, which silences the application
  jvmOptions = Seq("-Dlogback.configurationFile=logback.xml")
)
exitCode shouldBe 1
output should include("which names no environment")
```

`AppStartTests` gives each such start a data folder of its own, so a start that does not fail writes nowhere shared.

`ChildJvm.start` returns the running JVM, for a test that acts on it while it runs.

## Logs

The tests log nothing of the application's, so a test that checks a log line captures it. `LoggedLine.during` runs a
block and answers the lines written meanwhile, on any thread, with each one's thread, request ID and user:

```scala
val lines = LoggedLine.during(importStatusHandshake(host, guestCookies, guestPath) shouldBe Left(403))
val refusal = lines.filter(_.message.contains("Refused an import status handshake of Guest"))
refusal should have size 1
refusal.head.user shouldBe Some(guest.persistedId)
```

`onRequestThread` tells a line the server's request thread wrote from one of a pipeline, an actor or the test
(`ImportControllerTests`).

`LoggedLine.capturing { lines => ... }` reads the lines as they come, for a test that waits on one.

## JS tests

The browser's modules that need no DOM are tested under Node's built-in runner, in `scripts/test-js/<name>.test.mjs`,
with no dependency installed. `make test-js` runs them.

```js
import { test } from "node:test"
import assert from "node:assert/strict"

import { binTop } from "../../altitude/static/js/common/histogram.js"

test("A bin reaches the box's top at the tallest byte and its baseline at 0", () => {
    assert.equal(binTop(255), 0)
    assert.equal(binTop(0), 16)
})
```
