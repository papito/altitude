package altitude.core.integration

import java.time.LocalDateTime
import java.time.OffsetDateTime
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe, shouldNot }
import scalasql.core.SqlStr
import scalasql.core.SqlStr.SqlStringSyntax

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.language.reflectiveCalls
import scala.util.Random

import altitude.core.Altitude
import altitude.core.Api
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.search.SearchQueries
import altitude.core.models.*
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.sinks.VoidAssetSink
import altitude.core.service.FaceDetectionService
import altitude.core.service.PersonService
import altitude.core.util.*

@DoNotDiscover class SearchServiceTests(override val testApp: Altitude)
  extends IntegrationTestCore
  with SearchPlans
  with TextSearchPaths {

  test("Index and search by term") {

    /**
     * Setup:
     *
     * Two assets with user metadata in three fields - the KEYWORD "keywords", the TEXT "quotes" holding long movie quotes and the
     * KEYWORD "cast" holding actor names - where only "Teri Hatcher" is in both casts.
     *
     * Assertions:
     *
     * Search text finds an asset through its metadata values: a name in one cast finds one asset, the shared name finds both.
     *
     * Edge cases:
     *
     * A term typed in upper case ("TERI").
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "keywords", fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = "quotes", fieldType = FieldType.TEXT))

    val field3 = testApp.service.metadata.addField(UserMetadataField(name = "cast", fieldType = FieldType.KEYWORD))

    var data = Map[String, Set[String]](
      field1.persistedId -> Set("picture", "man", "office", "monday", "how is this my life?"),
      field2.persistedId -> Set(
        """
          We have blueberry, raspberry, ginseng, sleepy time, green tea,
          green tea with lemon, green tea with lemon and honey, liver disaster,
          ginger with honey, ginger without honey, vanilla almond, white truffel,
          blueberry chamomile, vanilla walnut, constant comment and... earl grey.
          """,
        """
          Ok this next song goes out to the guy who keeps yelling from the balcony.
          It's called "We Hate You, Please Die."
          """,
        """
          I partake not in the meat, nor the breast milk, nor the ovum, of any creature, with a face.
          """
      ),
      field3.persistedId -> Set("Lindsay Lohan", "Conan O'Brien", "Teri Hatcher", "Sam Rockwell")
    )

    testContext.persistAsset(metadata = UserMetadata(data))

    data = Map[String, Set[String]](
      field1.persistedId -> Set("tree", "shoe", "desert", "California"),
      field2.persistedId -> Set(
        """
          “If I ever start referring to these as the best years of my life — remind me to kill myself.”
          """,
        """
          George Washington was in a cult, and that cult was into aliens, man.
          """,
        """
          I’d like to stop thinking of the present as some minor, insignificant preamble to something else.
          """
      ),
      field3.persistedId -> Set("Keanu Reeves", "Sandra Bullock", "Dennis Hopper", "Teri Hatcher")
    )

    testContext.persistAsset(metadata = UserMetadata(data))

    var results: SearchResult = search(new SearchQuery(rpp = PAGE_SIZE, text = Some("keanu")))
    results.nonEmpty shouldBe true
    results.total shouldBe Some(1)

    results = search(new SearchQuery(rpp = PAGE_SIZE, text = Some("TERI")))
    results.nonEmpty shouldBe true
    results.total shouldBe Some(2)
  }

  test("Filter by folder") {

    /**
     * Setup:
     *
     * A folder with a subfolder, and three assets in each with the same keyword metadata.
     *
     * Assertions:
     *
     * A text search scoped to the subfolder counts its three assets, one scoped to the parent folder counts the subfolder's too,
     * and a search with neither a folder nor text counts all six.
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "keywords", fieldType = FieldType.KEYWORD))

    val data = Map[String, Set[String]](
      field1.persistedId -> Set("space", "force", "tactical", "pants")
    )

    val metadata = UserMetadata(data)

    val folder1: Folder = testApp.service.folder.add("folder1")

    val folder1_1: Folder = testApp.service.folder.add(name = "folder1_1", parentId = folder1.id)

    (1 to 3).foreach(_ => testContext.persistAsset(folder = Some(folder1_1), metadata = metadata))
    (1 to 3).foreach(_ => testContext.persistAsset(folder = Some(folder1), metadata = metadata))

    val qFolder1_1 = new SearchQuery(rpp = PAGE_SIZE, text = Some("space"), folderIds = Set(folder1_1.persistedId))
    var results: SearchResult = search(qFolder1_1)
    results.total shouldBe Some(3)

    val qFolder1 = new SearchQuery(rpp = PAGE_SIZE, text = Some("space"), folderIds = Set(folder1.persistedId))
    results = search(qFolder1)
    results.total shouldBe Some(6)

    val qAllFolders = new SearchQuery(rpp = PAGE_SIZE)
    results = search(qAllFolders)
    results.total shouldBe Some(6)

  }

  test("Searching with root folder ID includes triaged assets") {

    /**
     * Setup:
     *
     * Two assets in a folder and one triaged asset, which has no folder.
     *
     * Assertions:
     *
     * A search scoped to the root folder counts all three, the triaged asset included.
     */
    val folder1: Folder = testApp.service.folder.add("folder1")

    // 2 sorted assets in a sub-folder
    (1 to 2).foreach(_ => testContext.persistAsset(folder = Some(folder1)))
    // 1 triaged asset (no folder assigned)
    testContext.persistAsset(isTriaged = true)

    val rootFolderId = testContext.repository.rootFolderId

    val results = search(new SearchQuery(rpp = PAGE_SIZE, folderIds = Set(rootFolderId)))
    results.total shouldBe Some(3)
  }

  def fixtureForPersonFilter: Object { val assetsPerPersonCount: Int; val people: Seq[Person] } = new {
    val peopleCount = 3
    val assetsPerPersonCount = 3

    val people: Seq[Person] = (1 to peopleCount).map {
      _ =>
        val person = testApp.service.person.addPerson(Person())
        (1 to assetsPerPersonCount).foreach(_ => testContext.addTestFacesAndAssets(person))

        person
    }
  }

  test("Filter by one person") {

    /**
     * Setup:
     *
     * Three people, each with a Face in three assets of their own (`fixtureForPersonFilter`).
     *
     * Assertions:
     *
     * Filtering by one person counts that person's three assets only.
     */
    val f = fixtureForPersonFilter
    val q = new SearchQuery(rpp = PAGE_SIZE, personIds = Set(f.people.head.persistedId))
    val results = search(q)
    results.total shouldBe Some(f.assetsPerPersonCount)
  }

  test("Filter by more than one person") {

    /**
     * Setup:
     *
     * Three people, each with a Face in three assets of their own (`fixtureForPersonFilter`).
     *
     * Assertions:
     *
     * Filtering by all three people counts all of their nine assets.
     */
    val f = fixtureForPersonFilter
    val q = new SearchQuery(rpp = PAGE_SIZE, personIds = f.people.map(_.persistedId).toSet)
    val results = search(q)
    results.total shouldBe Some(f.assetsPerPersonCount * f.people.length)
  }

  test("Pagination") {

    /**
     * Setup:
     *
     * Six assets, searched without text in pages of two, six and twenty.
     *
     * Assertions:
     *
     * A page holds at most its page size, only a first page carries the total, and every page says whether another follows it.
     *
     * Edge cases:
     *
     * A page past the last one is empty, a page size equal to or larger than the match count fits everything on the first page,
     * and a query without a page size is refused, as every flat page is bounded.
     */
    (1 to 6).foreach(n => testContext.persistAsset())

    // A first page counts the matches; a page reached by scrolling does not, and says whether another follows it
    val results = search(new SearchQuery(rpp = 2, page = 1))
    results.total shouldBe Some(6)
    results.records.length shouldBe 2
    results.nonEmpty shouldBe true
    results.hasMore shouldBe true

    val results2 = search(new SearchQuery(rpp = 2, page = 2))
    results2.total shouldBe None
    results2.records.length shouldBe 2
    results2.hasMore shouldBe true

    val results3 = search(new SearchQuery(rpp = 2, page = 3))
    results3.total shouldBe None
    results3.records.length shouldBe 2
    results3.hasMore shouldBe false

    // page too far
    val results4 = search(new SearchQuery(rpp = 2, page = 4))
    results4.total shouldBe None
    results4.records.length shouldBe 0
    results4.hasMore shouldBe false

    val results5 = search(new SearchQuery(rpp = 6, page = 1))
    results5.total shouldBe Some(6)
    results5.records.length shouldBe 6
    results5.hasMore shouldBe false

    val results6 = search(new SearchQuery(rpp = 20, page = 1))
    results6.total shouldBe Some(6)
    results6.records.length shouldBe 6
    results6.hasMore shouldBe false

    // Every flat page is bounded
    intercept[IllegalArgumentException](search(new SearchQuery()))
  }

  test("A total counts up to its cap and reads one past it when there are more; an exact count does not stop") {

    /**
     * Setup:
     *
     * Four assets, counted under a total cap of 2 and of 4.
     *
     * Assertions:
     *
     * Under a cap below the match count, the flat page's total, the grouped page's total and the capped count all read one past
     * the cap; within the cap the total is exact, and a plain count ignores the cap.
     */
    (1 to 4).foreach(_ => testContext.persistAsset())
    val view = Map[String, Any](FieldConst.Asset.IS_RECYCLED -> false)
    val byName = List(SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC))

    search(new SearchQuery(params = view, rpp = 3, totalCap = 2)).total shouldBe Some(3)
    testApp.service.library
      .searchGrouped(
        new SearchQuery(
          params = view,
          rpp = 3,
          searchSort = byName,
          grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC)),
          totalCap = 2))
      .total shouldBe Some(3)
    testApp.service.library.cappedCount(new SearchQuery(params = view, totalCap = 2)) shouldBe 3

    // Within the cap a total is exact
    testApp.service.library.cappedCount(new SearchQuery(params = view, totalCap = 4)) shouldBe 4
    count(new SearchQuery(params = view, totalCap = 2)) shouldBe 4
  }

  test("Create assets and search by metadata") {

    /**
     * Setup:
     *
     * A KEYWORD, a NUMBER and a BOOL field, and two assets with several values in each; both assets hold the number 1 and the
     * flag set.
     *
     * Assertions:
     *
     * Search text finds the one asset with a keyword value, and metadata filters on the flag and the number match both assets.
     *
     * Edge cases:
     *
     * A filter value that is one of several values the field holds on an asset.
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "field 1", fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = "field 2", fieldType = FieldType.NUMBER))

    val field3 = testApp.service.metadata.addField(UserMetadataField(name = "field 3", fieldType = FieldType.BOOL))

    var data = Map[String, Set[String]](
      field1.persistedId -> Set("one", "two", "three"),
      field2.persistedId -> Set("1", "2", "3.002", "14.1", "1.25", "123456789"),
      field3.persistedId -> Set("true"))
    testContext.persistAsset(metadata = UserMetadata(data))

    data = Map[String, Set[String]](
      field1.persistedId -> Set("six", "seven"),
      field2.persistedId -> Set("5", "1001", "1"),
      field3.persistedId -> Set("true"))
    testContext.persistAsset(metadata = UserMetadata(data))

    // simple value search
    var results = search(new SearchQuery(rpp = PAGE_SIZE, text = Some("one")))
    results.total shouldBe Some(1)

    results = search(
      new SearchQuery(
        rpp = PAGE_SIZE,
        metadataFilters = Map(field3.persistedId -> Query.EQUALS(true), field2.persistedId -> Query.EQUALS(1)))
    )
    results.total shouldBe Some(2)
  }

  test("Search by wrong field type") {

    /**
     * Setup:
     *
     * A single asset with "one" in a KEYWORD field and "1" in a NUMBER field.
     *
     * Assertions:
     *
     * A metadata filter comparing the KEYWORD field to the integer 1 matches nothing rather than failing.
     *
     * Edge cases:
     *
     * A filter value of the wrong type for its field.
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "field 1", fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = "field 2", fieldType = FieldType.NUMBER))

    val data = Map[String, Set[String]](
      field1.persistedId -> Set("one"),
      field2.persistedId -> Set("1")
    )
    testContext.persistAsset(metadata = UserMetadata(data))

    val results = search(
      new SearchQuery(rpp = PAGE_SIZE, metadataFilters = Map(field1.persistedId -> Query.EQUALS(1)))
    )
    results.total shouldBe Some(0)
  }

  test("Parameterized search") {

    /**
     * Setup:
     *
     * Three assets given values after import in a KEYWORD field ("one", "one", "two") and a NUMBER field (1, 1, 2).
     *
     * Assertions:
     *
     * Metadata filters on both fields match only the two assets that hold both values.
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "field 1", fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = "field 2", fieldType = FieldType.NUMBER))

    val asset1: Asset = testContext.persistAsset()
    val asset2: Asset = testContext.persistAsset()
    val asset3: Asset = testContext.persistAsset()

    testApp.service.metadata.addMetadataValue(asset1.persistedId, fieldId = field1.persistedId, newValue = "one")
    testApp.service.metadata.addMetadataValue(asset2.persistedId, fieldId = field1.persistedId, newValue = "one")
    testApp.service.metadata.addMetadataValue(asset3.persistedId, fieldId = field1.persistedId, newValue = "two")

    testApp.service.metadata.addMetadataValue(asset1.persistedId, fieldId = field2.persistedId, newValue = 1)
    testApp.service.metadata.addMetadataValue(asset2.persistedId, fieldId = field2.persistedId, newValue = 1)
    testApp.service.metadata.addMetadataValue(asset3.persistedId, fieldId = field2.persistedId, newValue = 2)

    val results = search(
      new SearchQuery(
        rpp = PAGE_SIZE,
        metadataFilters = Map(
          field1.persistedId -> Query.EQUALS("one"),
          field2.persistedId -> Query.EQUALS(1)
        )
      )
    )
    results.total shouldBe Some(2)
  }

  test("Updating and removing metadata values updates search index") {

    /**
     * Setup:
     *
     * An asset with the KEYWORD value "one" and the NUMBER value 3.
     *
     * Assertions:
     *
     * Search text and metadata filters follow the keyword value as it is added and then updated to "newone", and the asset is no
     * longer found once the value is removed.
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "field 1", fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = "field 2", fieldType = FieldType.NUMBER))

    val asset1: Asset = testContext.persistAsset()

    testApp.service.metadata.addMetadataValue(asset1.persistedId, fieldId = field1.persistedId, newValue = "one")
    // it's the only value for this field so get it
    val metadata: UserMetadata = testApp.service.metadata.getMetadata(asset1.persistedId)
    val mdVal = metadata(field1.persistedId).head

    // tag a second field for posterity
    testApp.service.metadata.addMetadataValue(asset1.persistedId, fieldId = field2.persistedId, newValue = 3)

    var results = search(new SearchQuery(rpp = PAGE_SIZE, text = Some("one")))
    results.total shouldBe Some(1)

    // parameterized search
    results = search(
      new SearchQuery(
        rpp = PAGE_SIZE,
        metadataFilters = Map(
          field1.persistedId -> "one",
          field2.persistedId -> 3
        )
      )
    )
    results.records.length shouldBe 1
    results.total shouldBe Some(1)

    // update the value and search again
    testApp.service.metadata.updateMetadataValue(asset1.persistedId, mdVal.persistedId, "newone")
    results = search(new SearchQuery(rpp = PAGE_SIZE, text = Some("newone")))
    results.total shouldBe Some(1)

    // parameterized search
    results = search(
      new SearchQuery(
        rpp = PAGE_SIZE,
        metadataFilters = Map(
          field1.persistedId -> "newone",
          field2.persistedId -> 3
        )
      )
    )
    results.records.length shouldBe 1
    results.total shouldBe Some(1)

    // remove the value and search again
    testApp.service.metadata.deleteMetadataValue(assetId = asset1.persistedId, valueId = mdVal.persistedId)

    results = search(new SearchQuery(rpp = PAGE_SIZE, text = Some("newone")))
    results.isEmpty shouldBe true
  }

  test("Can sort in ASC order by created at date") {

    /**
     * Setup:
     *
     * Four assets whose import times are rewritten to one to four hours in the future, an hour apart.
     *
     * Assertions:
     *
     * A search sorted by import time ascending returns them in import order.
     */
    val assets = List.fill(4)(testContext.persistAsset())

    testApp.txManager.withTransaction {
      assets.zipWithIndex.foreach {
        case (asset, index) =>
          val futureTime = new java.sql.Timestamp(System.currentTimeMillis() + (3600000 * (index + 1)))
          this.update("UPDATE asset SET created_at = ? WHERE id = ?", getSqlDateTime(futureTime), asset.persistedId)
      }
    }

    val sort = SearchSort(field = Api.Field.SearchSort.BY_ASSET_CREATED_AT, direction = SortDirection.ASC)
    val results = search(new SearchQuery(rpp = PAGE_SIZE, searchSort = List(sort)))

    results.records.map(_.persistedId) shouldBe assets.map(_.persistedId)
  }

  test("Can sort in DESC order by created at date") {

    /**
     * Setup:
     *
     * Four assets whose import times are rewritten to one to four hours in the future, an hour apart.
     *
     * Assertions:
     *
     * A search sorted by import time descending returns them newest first.
     */
    val assets = List.fill(4)(testContext.persistAsset())

    testApp.txManager.withTransaction {
      assets.zipWithIndex.foreach {
        case (asset, index) =>
          val futureTime = new java.sql.Timestamp(System.currentTimeMillis() + (3600000 * (index + 1)))
          this.update("UPDATE asset SET created_at = ? WHERE id = ?", getSqlDateTime(futureTime), asset.persistedId)
      }
    }

    val sort = SearchSort(field = Api.Field.SearchSort.BY_ASSET_CREATED_AT, direction = SortDirection.DESC)
    val results = search(new SearchQuery(rpp = PAGE_SIZE, searchSort = List(sort)))

    results.records.map(_.persistedId) shouldBe assets.reverse.map(_.persistedId)
  }

  test("Sort info should be returned with query results") {

    /**
     * Setup:
     *
     * Two assets, searched first with no sort and then with an ascending import-time sort.
     *
     * Assertions:
     *
     * A search with no sort reports none, and a sorted one carries the sort it was read with, its field and its direction.
     */
    (1 to 2).foreach {
      idx =>
        val asset: Asset = testContext.persistAsset()
    }

    // try with no sort info at all
    search(new SearchQuery(rpp = PAGE_SIZE)).sort shouldBe Nil

    val sort = SearchSort(field = Api.Field.SearchSort.BY_ASSET_CREATED_AT, direction = SortDirection.ASC)
    val results = search(new SearchQuery(rpp = PAGE_SIZE, searchSort = List(sort)))
    results.sort shouldNot be(empty)
    results.sort.head.direction shouldBe SortDirection.ASC
    results.sort.head.field shouldBe "created_at"
  }

  test("Dangling assets should not be searchable") {

    /**
     * Setup:
     *
     * Three assets, then marked as not processed by the import pipeline, the state an interrupted import leaves behind.
     *
     * Assertions:
     *
     * A plain asset query still sees them, but a search finds none of them.
     */
    val assetCount = 3
    for (_ <- 1 to assetCount)
      testContext.persistAsset()

    val assetQuery = new Query(Map(FieldConst.Asset.FOLDER_ID -> testContext.repository.rootFolderId))
    testApp.service.asset.queryAll(assetQuery).total shouldBe assetCount

    // make all assets "dangling"
    val updateData = Map(
      FieldConst.Asset.IS_PIPELINE_PROCESSED -> false
    )
    testApp.service.asset.updateByQuery(assetQuery, updateData)

    val assetSearchQuery = new SearchQuery(rpp = 3, page = 1)
    search(assetSearchQuery).total shouldBe Some(0)
  }

  test("An imported asset has a Search document of its file name words") {

    /**
     * Setup:
     *
     * An asset imported as "IMG_1234-beach.final.jpg".
     *
     * Assertions:
     *
     * Its Search document body is the file name's words, and a search for one of them finds it.
     */
    val asset = importAsset("IMG_1234-beach.final.jpg")

    documentBody(asset) shouldBe Some("img 1234 beach final jpg")
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some("beach"))).total shouldBe Some(1)
  }

  test("A Search document holds both readings of a value with a camelCase hump, with and without it") {

    /**
     * Setup:
     *
     * An asset imported as "IMG_1234-beachSunset.final.jpg".
     *
     * Assertions:
     *
     * The document body holds the file name split at the hump, followed by its words with the hump joined.
     */
    val asset = importAsset("IMG_1234-beachSunset.final.jpg")

    documentBody(asset) shouldBe Some("img 1234 beach sunset final jpg img 1234 beachsunset final jpg")
  }

  test("Search text: a word with a camelCase hump is found by the word in one case, a prefix of it, and its parts") {

    /**
     * Setup:
     *
     * Assets imported as "McDonald_beachSunset.jpg" and, as a decoy, "donut.jpg".
     *
     * Assertions:
     *
     * Only the humped asset is found, by the whole word in any case, by a prefix of it, by the part after the hump, and by the
     * joined reading of beachSunset.
     */
    val humped = importAsset("McDonald_beachSunset.jpg").persistedId
    importAsset("donut.jpg")

    List("mcdonald", "mcdon", "McDonald", "donald", "sunset", "beachsunset").foreach {
      text => withClue(s"[$text] ")(found(text) shouldBe Set(humped))
    }
  }

  test("Search text: a word typed with a camelCase hump finds the word written in one case") {

    /**
     * Setup:
     *
     * Assets imported as "Mcdonald.jpg", "MCDONALD.jpg" and "Mc Donald.jpg", and a decoy "donald.jpg".
     *
     * Assertions:
     *
     * "McDonald", bare or as a phrase, finds the three spellings but not the decoy.
     *
     * Edge cases:
     *
     * The spelling with a space, which the reading split at the typed hump matches.
     */
    val capitalized = importAsset("Mcdonald.jpg").persistedId
    val upperCase = importAsset("MCDONALD.jpg").persistedId
    val spaced = importAsset("Mc Donald.jpg").persistedId
    importAsset("donald.jpg")

    found("McDonald") shouldBe Set(capitalized, upperCase, spaced)
    found("\"McDonald\"") shouldBe Set(capitalized, upperCase, spaced)
  }

  test("Renaming an asset rewrites its Search document") {

    /**
     * Setup:
     *
     * An asset imported as "beachSunset.jpg", then renamed to "mountain-lake.jpg".
     *
     * Assertions:
     *
     * The document body becomes the new name's words, and the full-text index follows: the old word no longer finds the asset and
     * the new one does.
     */
    val asset = importAsset("beachSunset.jpg")

    testApp.service.asset.rename(asset.persistedId, "mountain-lake.jpg")

    documentBody(asset) shouldBe Some("mountain lake jpg")
    // The full-text index follows the document, not just the stored body
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some("sunset"))).total shouldBe Some(0)
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some("lake"))).total shouldBe Some(1)
  }

  test("Editing metadata rewrites the Search document") {

    /**
     * Setup:
     *
     * An asset "beach.jpg" and a KEYWORD field "place" whose value is added, updated and deleted.
     *
     * Assertions:
     *
     * After each edit the document body is the file name's words followed by the current value's words, or the file name's alone
     * once the value is gone.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = "place", fieldType = FieldType.KEYWORD))
    val asset = importAsset("beach.jpg")

    testApp.service.metadata.addMetadataValue(asset.persistedId, fieldId = field.persistedId, newValue = "Golden-Gate")
    documentBody(asset) shouldBe Some("beach jpg golden gate")

    val value = testApp.service.metadata.getMetadata(asset.persistedId)(field.persistedId).head
    testApp.service.metadata.updateMetadataValue(asset.persistedId, value.persistedId, "Bay Bridge")
    documentBody(asset) shouldBe Some("beach jpg bay bridge")

    testApp.service.metadata.deleteMetadataValue(assetId = asset.persistedId, valueId = value.persistedId)
    documentBody(asset) shouldBe Some("beach jpg")
  }

  test("A TEXT value added to an asset is found by the next search") {

    /**
     * Setup:
     *
     * Two assets and a TEXT field "notes"; one asset gets the value "The tide comes in" after import.
     *
     * Assertions:
     *
     * The next search finds that asset by a word of the value, and the TEXT value writes no metadata parameter.
     */
    val notes = testApp.service.metadata.addField(UserMetadataField(name = "notes", fieldType = FieldType.TEXT))
    val asset = importAsset("one.jpg")
    importAsset("two.jpg")

    testApp.service.metadata.addMetadataValue(asset.persistedId, fieldId = notes.persistedId, newValue = "The tide comes in")

    found("tide") shouldBe Set(asset.persistedId)
    parameterFields(asset) shouldBe List()
  }

  test("An imported or reindexed asset has metadata parameters for its faceted values only, and is found by all of them") {

    /**
     * Setup:
     *
     * An asset imported with values in KEYWORD (two values), NUMBER, BOOL, TEXT and DATETIME fields, a second asset without
     * metadata, and then a rename of the first.
     *
     * Assertions:
     *
     * Only the KEYWORD, NUMBER and BOOL values get metadata parameter rows, one per value, yet every value finds the asset: the
     * TEXT and DATETIME ones through Search text, the faceted ones through metadata filters. The same holds after the rename
     * reindexes the asset.
     */
    def addField(name: String, fieldType: FieldType): String =
      testApp.service.metadata.addField(UserMetadataField(name = name, fieldType = fieldType)).persistedId

    val keywords = addField("keywords", FieldType.KEYWORD)
    val count = addField("count", FieldType.NUMBER)
    val isFlagged = addField("flagged", FieldType.BOOL)
    val notes = addField("notes", FieldType.TEXT)
    val shotAt = addField("shot at", FieldType.DATETIME)
    val metadata = Map(
      keywords -> Set("harbor", "boats"),
      count -> Set("3"),
      isFlagged -> Set("true"),
      notes -> Set("The tide comes in"),
      shotAt -> Set("2024-05-01 10:00"))
    val asset = importAsset("one.jpg", metadata = UserMetadata(metadata))
    importAsset("two.jpg")

    def isIndexed(): Unit = {
      parameterFields(asset) shouldBe List(keywords, keywords, count, isFlagged).sorted
      found("tide") shouldBe Set(asset.persistedId)
      found("2024") shouldBe Set(asset.persistedId)
      found("harbor") shouldBe Set(asset.persistedId)
      val filters = Map[String, Any](keywords -> "boats", count -> 3, isFlagged -> true)
      search(new SearchQuery(rpp = PAGE_SIZE, metadataFilters = filters)).records.map(_.persistedId) shouldBe
        List(asset.persistedId)
    }

    isIndexed()
    // A rename reindexes the asset
    testApp.service.asset.rename(asset.persistedId, "renamed.jpg")
    isIndexed()
  }

  test("Recycling an asset keeps its Search document") {

    /**
     * Setup:
     *
     * An asset "beach.jpg" moved to the trash.
     *
     * Assertions:
     *
     * Its Search document is still there, unchanged.
     */
    val asset = importAsset("beach.jpg")

    testApp.service.library.recycleAssets(Set(asset.persistedId))

    documentBody(asset) shouldBe Some("beach jpg")
  }

  test("Purging an asset removes its Search document") {

    /**
     * Setup:
     *
     * Two assets, one of which is run through the purge pipeline, then a third asset imported after the purge.
     *
     * Assertions:
     *
     * The purged asset's document is gone and the other one's is untouched, and the full-text index has forgotten the purged
     * words: they find nothing once a new document is written, while the other assets are still found.
     *
     * Edge cases:
     *
     * A document written after the purge, which may take the purged document's place in the table.
     */
    val kept = importAsset("lake.jpg")
    val purged = importAsset("beach.jpg")

    val source = Source.single((purged, PipelineContext(testContext.repository, testContext.user)))
    Await.result(testApp.service.purgePipeline.run(source, VoidAssetSink()), Duration.Inf)

    documentBody(purged) shouldBe None
    documentBody(kept) shouldBe Some("lake jpg")

    // A document written after the purge may take the purged one's place in the table: the full-text index must have
    // forgotten the purged words, or they would now lead to this asset
    importAsset("mountain.jpg")
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some("beach"))).total shouldBe Some(0)
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some("mountain"))).total shouldBe Some(1)
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some("lake"))).total shouldBe Some(1)
  }

  test("Rewriting an unchanged Search document updates no row") {

    /**
     * Setup:
     *
     * An imported asset "beach.jpg", reindexed as it is and then with a new file name.
     *
     * Assertions:
     *
     * Reindexing the unchanged asset writes no row of `search_document`, while the new name writes one and updates the body.
     */
    val asset: Asset = testApp.service.asset.getById(importAsset("beach.jpg").persistedId)

    documentRowsWrittenBy(testApp.service.search.reindexAsset(asset)) shouldBe 0
    documentRowsWrittenBy(testApp.service.search.reindexAsset(asset.copy(fileName = "lake.jpg"))) should be > 0L
    documentBody(asset) shouldBe Some("lake jpg")
  }

  test("Folder browsing reads the folder index") {

    /**
     * Setup:
     *
     * An asset in the folder "Trips" seeded into 5,000 copies over 500 folders for the planner, which are rolled back after the
     * plan is read, and a page of 50 of the folder sorted by import time.
     *
     * Assertions:
     *
     * The engine's plan for the page reads the `asset_folder` index.
     */
    val folder: Folder = testApp.service.folder.add("Trips")
    val template = testContext.persistAsset(folder = Some(folder))
    val query = new SearchQuery(params = browsingView, folderIds = Set(folder.persistedId), rpp = 50, searchSort = byImport)

    val plan = atScale(seedCopies(template.persistedId, copies = 5000, folders = 500))(planOf(flatPage(query)))

    withClue(plan)(plan should include("asset_folder"))
  }

  test("A Date Imported page reads the import-time index") {

    /**
     * Setup:
     *
     * An asset seeded into 5,000 copies over 500 folders for the planner, and an unscoped page of 50 sorted by import time.
     *
     * Assertions:
     *
     * The engine's plan for the page reads the `asset_search_created` index.
     */
    val template = testContext.persistAsset()
    val query = new SearchQuery(params = browsingView, rpp = 50, searchSort = byImport)

    val plan = atScale(seedCopies(template.persistedId, copies = 5000, folders = 500))(planOf(flatPage(query)))

    withClue(plan)(plan should include("asset_search_created"))
  }

  test("An asset's faces, Search document and metadata parameters are found by the index that leads with the asset") {

    /**
     * Setup:
     *
     * One imported asset, and a lookup by its ID in each of `face`, `search_document` and `metadata_parameter`.
     *
     * Assertions:
     *
     * Each lookup's plan is a seek on the table's `_01` index by `asset_id`, not a pass over an index that happens to hold the
     * column.
     */
    val engine = searchDialect
    import engine.dialect.*
    val asset = importAsset("beach.jpg")

    for (
      (table, index) <- List(
        "face" -> "face_01",
        "search_document" -> "search_document_01",
        "metadata_parameter" -> "metadata_parameter_01")
    ) {
      val plan = lookupPlanOf(sql"SELECT asset_id FROM ${SqlStr.raw(table)} WHERE asset_id = ${asset.persistedId}")
      // A seek on the asset, not a pass over an index that happens to hold the column
      val seek =
        if (isPostgres) s"(?s)$index.*Index Cond: \\(asset_id = "
        else s"SEARCH $table USING (COVERING )?INDEX $index \\(asset_id=\\?"

      withClue(plan)(seek.r.findFirstIn(plan).isDefined shouldBe true)
    }
  }

  if (isPostgres) test("An excluded term is an anti-join on PostgreSQL") {

    /**
     * Setup:
     *
     * PostgreSQL only: assets "beach.jpg" and "lake.jpg", and the capped count statement of the text "-beach" resolved against
     * the repository.
     *
     * Assertions:
     *
     * The plan excludes through an Anti Join. PostgreSQL plans `NOT IN` over a subquery as a SubPlan probed per asset, while
     * `NOT EXISTS` over the source's CTE is a hash anti-join.
     */
    importAsset("beach.jpg")
    importAsset("lake.jpg")
    val query = new SearchQuery(text = Some("-beach"), params = browsingView)
    val resolved = query.withResolvedText(testApp.service.search.resolveText(query.textExpression.value))

    val plan = planOf(SearchQueries.cappedCount(searchDialect, resolved, RequestContext.getRepository.persistedId))

    withClue(plan)(plan should include("Anti Join"))
  }

  test("Text with a positive group that has no hit matches nothing, and an exclusion alone is matched over the library") {

    /**
     * Setup:
     *
     * One asset, "beach.jpg".
     *
     * Assertions:
     *
     * A positive group with no hit ("zzz OR qqq") resolves to an empty candidate set, so the text matches nothing; text made only
     * of an exclusion has no hits to read and is matched over the whole library.
     */
    val beach = importAsset("beach.jpg").persistedId

    testApp.service.search.resolveText(SearchText.parse("zzz OR qqq beach").value).candidates shouldBe Some(Set())
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some("zzz OR qqq beach"))).total shouldBe Some(0)
    found("zzz") shouldBe Set()

    // An excluded term has no hits to read
    testApp.service.search.resolveText(SearchText.parse("-zzz").value).candidates shouldBe None
    found("-zzz") shouldBe Set(beach)
  }

  test("A selective search reads the assets by their primary key") {

    /**
     * Setup:
     *
     * An asset "beach.jpg" seeded into 5,000 copies for the planner; the copies have no Search document, so the text "beach" has
     * the original as its one candidate.
     *
     * Assertions:
     *
     * The capped count's plan reads the asset through its primary key index.
     */
    val template = importAsset("beach.jpg")
    val query = new SearchQuery(text = Some("beach"), params = browsingView)

    // The copies have no Search document, so the text's one hit is the template
    val plan = atScale(seedCopies(template.persistedId, copies = 5000, folders = 500)) {
      val resolved = query.withResolvedText(testApp.service.search.resolveText(query.textExpression.value))
      resolved.requireResolvedText.candidates shouldBe Some(Set(template.persistedId))
      planOf(SearchQueries.cappedCount(searchDialect, resolved, RequestContext.getRepository.persistedId))
    }

    withClue(plan)(plan should include(if (isPostgres) "asset_pkey" else "sqlite_autoindex_asset_1"))
  }

  /** Three assets whose file names and "place" metadata values share words in different orders */
  private def fixtureForText: Object { val beach: String; val lake: String; val dog: String } = new {
    private val place = testApp.service.metadata.addField(UserMetadataField(name = "place", fieldType = FieldType.KEYWORD))

    private def add(fileName: String, placeValue: String): String =
      val asset = importAsset(fileName)
      testApp.service.metadata.addMetadataValue(asset.persistedId, fieldId = place.persistedId, newValue = placeValue)
      asset.persistedId

    val beach: String = add("IMG_1234-beachSunset.jpg", "Golden Gate")
    val lake: String = add("IMG_1299-mountainLake.jpg", "Bay Bridge")
    val dog: String = add("sunsetBeach.png", "Golden Retriever")
  }

  test("Search text: every term must match, in the file name or in a metadata value") {

    /**
     * Setup:
     *
     * Three assets from `fixtureForText`: "IMG_1234-beachSunset.jpg" with the place "Golden Gate", "IMG_1299-mountainLake.jpg"
     * with "Bay Bridge" and "sunsetBeach.png" with "Golden Retriever".
     *
     * Assertions:
     *
     * An asset is found only when every term matches it, each in either its file name or its place value.
     */
    val f = fixtureForText

    found("beach golden") shouldBe Set(f.beach, f.dog)
    found("gate beach") shouldBe Set(f.beach)
    found("beach bridge") shouldBe Set()
  }

  test("Search text: a term matches the start of a word") {

    /**
     * Setup:
     *
     * Three assets from `fixtureForText`: "IMG_1234-beachSunset.jpg" with the place "Golden Gate", "IMG_1299-mountainLake.jpg"
     * with "Bay Bridge" and "sunsetBeach.png" with "Golden Retriever".
     *
     * Assertions:
     *
     * A term matches the words it is a prefix of, in any case, and not those it is only inside of.
     *
     * Edge cases:
     *
     * A term typed in upper case ("GOLD") and a suffix of a word ("unset").
     */
    val f = fixtureForText

    found("sun") shouldBe Set(f.beach, f.dog)
    found("GOLD") shouldBe Set(f.beach, f.dog)
    found("unset") shouldBe Set()
  }

  test("Search text: a term of several words is those words in sequence, the last one a prefix") {

    /**
     * Setup:
     *
     * Three assets from `fixtureForText`: "IMG_1234-beachSunset.jpg" with the place "Golden Gate", "IMG_1299-mountainLake.jpg"
     * with "Bay Bridge" and "sunsetBeach.png" with "Golden Retriever".
     *
     * Assertions:
     *
     * A term that splits into several words matches them only consecutively and in order, all whole but the last, which may be a
     * prefix.
     *
     * Edge cases:
     *
     * Words in the wrong order, and a prefix in a word that is not the last.
     */
    val f = fixtureForText

    found("IMG_12") shouldBe Set(f.beach, f.lake)
    found("img-123") shouldBe Set(f.beach)
    found("beachSun") shouldBe Set(f.beach)
    found("12_IMG") shouldBe Set()
    // Only the last word is a prefix
    found("IM_1234") shouldBe Set()
  }

  test("Search text: a phrase is whole consecutive words") {

    /**
     * Setup:
     *
     * Three assets from `fixtureForText`: "IMG_1234-beachSunset.jpg" with the place "Golden Gate", "IMG_1299-mountainLake.jpg"
     * with "Bay Bridge" and "sunsetBeach.png" with "Golden Retriever".
     *
     * Assertions:
     *
     * A phrase matches its words whole, consecutively and in the order typed, in the file name or in a metadata value.
     *
     * Edge cases:
     *
     * A partial word inside a phrase, and an unbalanced quote, which is closed at the end of the text.
     */
    val f = fixtureForText

    found("\"beach sunset\"") shouldBe Set(f.beach)
    found("\"sunset beach\"") shouldBe Set(f.dog)
    found("\"Golden Gate\"") shouldBe Set(f.beach)
    found("\"beach sun\"") shouldBe Set()
    found("\"gold\"") shouldBe Set()
    // An unbalanced quote is closed at the end of the text
    found("\"golden retriever") shouldBe Set(f.dog)
  }

  test("Search text: OR makes alternatives and binds tighter than AND") {

    /**
     * Setup:
     *
     * Three assets from `fixtureForText`: "IMG_1234-beachSunset.jpg" with the place "Golden Gate", "IMG_1299-mountainLake.jpg"
     * with "Bay Bridge" and "sunsetBeach.png" with "Golden Retriever".
     *
     * Assertions:
     *
     * Alternatives joined by OR match either term, and the group is AND-ed with the terms around it.
     *
     * Edge cases:
     *
     * A phrase as an alternative, and a lower-case "or", which is an ordinary word.
     */
    val f = fixtureForText

    found("gate OR bridge") shouldBe Set(f.beach, f.lake)
    found("retriever OR bridge img") shouldBe Set(f.lake)
    found("img \"bay bridge\" OR 1234") shouldBe Set(f.beach, f.lake)
    // Lower case is a word, not the operator
    found("gate or bridge") shouldBe Set()
  }

  test("Search text: a leading minus excludes") {

    /**
     * Setup:
     *
     * Three assets from `fixtureForText`: "IMG_1234-beachSunset.jpg" with the place "Golden Gate", "IMG_1299-mountainLake.jpg"
     * with "Bay Bridge" and "sunsetBeach.png" with "Golden Retriever".
     *
     * Assertions:
     *
     * An excluded term removes the assets it matches, alone or beside positive terms.
     *
     * Edge cases:
     *
     * Text of only exclusions, an excluded phrase, an excluded term of several words, and an excluded alternative.
     */
    val f = fixtureForText

    found("golden -gate") shouldBe Set(f.dog)
    found("-golden") shouldBe Set(f.lake)
    found("-gold -lake") shouldBe Set()
    found("beach -\"beach sunset\"") shouldBe Set(f.dog)
    found("-IMG_12") shouldBe Set(f.dog)
    // An excluded alternative: not a gate, or else a bridge
    found("-golden OR gate") shouldBe Set(f.beach, f.lake)
  }

  test("Search text: nothing typed is query syntax, and text without a word is no text") {

    /**
     * Setup:
     *
     * Three assets from `fixtureForText`: "IMG_1234-beachSunset.jpg" with the place "Golden Gate", "IMG_1299-mountainLake.jpg"
     * with "Bay Bridge" and "sunsetBeach.png" with "Golden Retriever".
     *
     * Assertions:
     *
     * Operators of the engines' full-text syntax are matched as plain words or separators, never as operators, and text with no
     * usable word matches every asset, like no text at all.
     *
     * Edge cases:
     *
     * `&`, `|`, `!`, `:*`, quotes, parentheses, AND, NOT and NEAR typed into the text.
     */
    val f = fixtureForText

    found("beach & (sunset:* | 'x') !") shouldBe Set()
    found("beach* AND NOT NEAR(lake)") shouldBe Set()
    found("(beach) & sunset:*") shouldBe Set(f.beach, f.dog)
    found("- !!! OR") shouldBe Set(f.beach, f.lake, f.dog)
  }

  test("Search names: a named, visible person finds the assets with a Face of theirs") {

    /**
     * Setup:
     *
     * Two assets: one with a Face of "Alice Liddell", the other with Faces of a hidden person, a person marked as a bad match and
     * an unnamed person.
     *
     * Assertions:
     *
     * A person's name finds the assets with their Face, by a word, a prefix, or two words of the same name; hidden, bad-match and
     * unnamed people find nothing.
     *
     * Edge cases:
     *
     * An unnamed person's generated "Unknown N" name, which is not searchable.
     */
    val withAlice = importAsset("one.jpg")
    val withOthers = importAsset("two.jpg")
    addFace(addPerson("Alice Liddell"), withAlice)

    val hidden = addPerson("Harry Hidden")
    val badMatch = addPerson("Bob Badmatch")
    val unnamed = testApp.service.person.addPerson(Person())
    List(hidden, badMatch, unnamed).foreach(addFace(_, withOthers))
    testApp.service.person.setVisibility(hidden, isHidden = true)
    testApp.service.person.markAsBadMatch(badMatch)

    found("alice") shouldBe Set(withAlice.persistedId)
    found("lidd") shouldBe Set(withAlice.persistedId)
    // Two terms may match in the same name
    found("liddell alice") shouldBe Set(withAlice.persistedId)
    found("harry") shouldBe Set()
    found("bob") shouldBe Set()
    // An unnamed person is "Unknown N"
    unnamed.name.value.startsWith(PersonService.UNKNOWN_NAME_PREFIX) shouldBe true
    found(PersonService.UNKNOWN_NAME_PREFIX) shouldBe Set()
  }

  test("Search names: renaming, hiding and merging people shows in the next search") {

    /**
     * Setup:
     *
     * Two assets with a Face each, of Alice and of Bob.
     *
     * Assertions:
     *
     * The next search after each change follows the people: a renamed person is found by the new name only, a hidden one not at
     * all until shown again, and a merge leaves both assets under the surviving name.
     *
     * Edge cases:
     *
     * The person merged away is no candidate at all, whatever Faces it may still have.
     */
    val withAlice = importAsset("one.jpg")
    val withBob = importAsset("two.jpg")
    val alice = addPerson("Alice")
    val bob = addPerson("Bob")
    addFace(alice, withAlice)
    addFace(bob, withBob)

    testApp.service.person.updateName(alice, "Alicia")
    found("alice") shouldBe Set()
    found("alicia") shouldBe Set(withAlice.persistedId)

    testApp.service.person.setVisibility(bob, isHidden = true)
    found("bob") shouldBe Set()
    testApp.service.person.setVisibility(bob, isHidden = false)
    found("bob") shouldBe Set(withBob.persistedId)

    testApp.service.person.merge(dest = testApp.service.person.getById(alice.persistedId), source = bob)
    found("alicia") shouldBe Set(withAlice.persistedId, withBob.persistedId)
    found("bob") shouldBe Set()
    // The person merged away is no candidate at all, whatever Faces it may still have
    personHits("bob") shouldBe Set()
    personHits("alicia") shouldBe Set(alice.persistedId)
  }

  test("Search names: a Location finds its assets, a Category those of its Locations") {

    /**
     * Setup:
     *
     * Rome and Milan in the Category "Italy", Paris without a Category, one asset in each, and a fourth asset in none.
     *
     * Assertions:
     *
     * A Location's name finds its assets and a Category's name finds those of all its Locations, combining with prefixes,
     * exclusions and OR like any term.
     */
    val italy = testApp.service.location.addCategory("Italy")
    val rome = testApp.service.location.addLocation("Rome", 41.9, 12.5, Some(italy.persistedId))
    val milan = testApp.service.location.addLocation("Milan", 45.5, 9.2, Some(italy.persistedId))
    val paris = testApp.service.location.addLocation("Paris", 48.9, 2.4)
    val inRome = importAsset("one.jpg")
    val inMilan = importAsset("two.jpg")
    val inParis = importAsset("three.jpg")
    importAsset("four.jpg")
    testApp.service.location.addAssets(rome.persistedId, Set(inRome.persistedId))
    testApp.service.location.addAssets(milan.persistedId, Set(inMilan.persistedId))
    testApp.service.location.addAssets(paris.persistedId, Set(inParis.persistedId))

    found("rome") shouldBe Set(inRome.persistedId)
    found("italy") shouldBe Set(inRome.persistedId, inMilan.persistedId)
    found("par") shouldBe Set(inParis.persistedId)
    found("italy -milan") shouldBe Set(inRome.persistedId)
    found("rome OR paris") shouldBe Set(inRome.persistedId, inParis.persistedId)
  }

  test("Search names: renaming, moving, deleting and refilling Locations shows in the next search") {

    /**
     * Setup:
     *
     * Rome in the Category "Italy" holding one asset, an empty Category "France", and a second asset.
     *
     * Assertions:
     *
     * The next search after each change follows the Locations: renaming the Location and its Category, moving it to another
     * Category, adding and removing assets, deleting the Category and deleting the Location.
     *
     * Edge cases:
     *
     * A deleted Category leaves its Locations at the top level, still found by their own names.
     */
    val italy = testApp.service.location.addCategory("Italy")
    val france = testApp.service.location.addCategory("France")
    val rome = testApp.service.location.addLocation("Rome", 41.9, 12.5, Some(italy.persistedId))
    val asset = importAsset("one.jpg").persistedId
    val other = importAsset("two.jpg").persistedId
    testApp.service.location.addAssets(rome.persistedId, Set(asset))

    testApp.service.location.rename(rome.persistedId, "Roma")
    found("rome") shouldBe Set()
    found("roma") shouldBe Set(asset)

    testApp.service.location.rename(italy.persistedId, "Italia")
    found("italy") shouldBe Set()
    found("italia") shouldBe Set(asset)

    testApp.service.location.moveToCategory(rome.persistedId, Some(france.persistedId))
    found("italia") shouldBe Set()
    found("france") shouldBe Set(asset)

    // Membership changes
    testApp.service.location.addAssets(rome.persistedId, Set(other))
    found("roma") shouldBe Set(asset, other)
    testApp.service.location.removeAssets(rome.persistedId, Set(asset))
    found("france") shouldBe Set(other)

    // A deleted Category leaves its Locations at the top level
    testApp.service.location.deleteById(france.persistedId)
    found("france") shouldBe Set()
    found("roma") shouldBe Set(other)

    testApp.service.location.deleteById(rome.persistedId)
    found("roma") shouldBe Set()
  }

  test("Search names: a folder finds the assets in it and in every folder below it, the root none") {

    /**
     * Setup:
     *
     * Folders Trips > Japan 2019 > Kyoto with an asset at each level, a folder "Work" with an asset, and an asset in the root
     * folder.
     *
     * Assertions:
     *
     * A folder's name finds the assets in it and in every folder below it, and terms may match different folders of one path,
     * exclusions included.
     *
     * Edge cases:
     *
     * A digit word of a folder name ("2019"), and the root folder's name, which finds nothing.
     */
    val trips: Folder = testApp.service.folder.add("Trips")
    val japan: Folder = testApp.service.folder.add("Japan 2019", Some(trips.persistedId))
    val kyoto: Folder = testApp.service.folder.add("Kyoto", Some(japan.persistedId))
    val work: Folder = testApp.service.folder.add("Work")
    val inTrips = importAsset("one.jpg", Some(trips)).persistedId
    val inJapan = importAsset("two.jpg", Some(japan)).persistedId
    val inKyoto = importAsset("three.jpg", Some(kyoto)).persistedId
    importAsset("four.jpg", Some(work))
    importAsset("five.jpg")

    found("trips") shouldBe Set(inTrips, inJapan, inKyoto)
    found("japan") shouldBe Set(inJapan, inKyoto)
    found("2019") shouldBe Set(inJapan, inKyoto)
    found("kyoto") shouldBe Set(inKyoto)
    // Terms may match at different levels of one path
    found("trips kyoto") shouldBe Set(inKyoto)
    found("trips -japan") shouldBe Set(inTrips)
    // The root folder holds everything and names nothing
    found(FieldConst.Folder.Name.ROOT) shouldBe Set()
  }

  test("Search names: renaming, moving and deleting folders, and moving assets, shows in the next search") {

    /**
     * Setup:
     *
     * Folders Trips > Japan with one asset, a folder "Archive", and a second asset in the root folder.
     *
     * Assertions:
     *
     * The next search after each change follows the folder paths: renaming a folder, moving it under another, moving assets
     * between folders, deleting a folder and restoring an asset from the trash.
     *
     * Edge cases:
     *
     * A deleted folder is recycled with everything below it and neither names anything, even for a search that includes the
     * trash; restoring an asset restores its folders and their names.
     */
    val trips: Folder = testApp.service.folder.add("Trips")
    val japan: Folder = testApp.service.folder.add("Japan", Some(trips.persistedId))
    val archive: Folder = testApp.service.folder.add("Archive")
    val asset = importAsset("one.jpg", Some(japan)).persistedId
    val other = importAsset("two.jpg").persistedId

    testApp.service.folder.rename(japan.persistedId, "Nippon")
    found("japan") shouldBe Set()
    found("nippon") shouldBe Set(asset)

    testApp.service.folder.move(japan.persistedId, archive.persistedId)
    found("trips") shouldBe Set()
    found("archive") shouldBe Set(asset)

    testApp.service.library.moveAssetsToFolder(Set(other), japan.persistedId)
    found("nippon") shouldBe Set(asset, other)
    testApp.service.library.moveAssetsToFolder(Set(asset), trips.persistedId)
    found("archive") shouldBe Set(other)
    found("trips") shouldBe Set(asset)

    // A deleted folder is recycled with everything below it: neither names anything, even for a search that includes the trash
    testApp.service.library.deleteFolderById(archive.persistedId)
    found("archive") shouldBe Set()
    found("nippon") shouldBe Set()
    found("trips") shouldBe Set(asset)

    // Restoring the asset restores its folders
    testApp.service.library.restoreRecycledAssets(Set(other))
    found("archive") shouldBe Set(other)
  }

  test("Search names: an album finds its assets, and its changes show in the next search") {

    /**
     * Setup:
     *
     * An album "Best of Summer" holding one of two assets.
     *
     * Assertions:
     *
     * The album's name finds its assets, by a word or as a phrase, and the next search follows a rename, added and removed
     * assets, and the album's deletion.
     */
    val album = testApp.service.album.add("Best of Summer")
    val asset = importAsset("one.jpg").persistedId
    val other = importAsset("two.jpg").persistedId
    testApp.service.album.addAssets(album.persistedId, Set(asset))

    found("summer") shouldBe Set(asset)
    found("\"best of summer\"") shouldBe Set(asset)

    testApp.service.album.rename(album.persistedId, "Winter")
    found("summer") shouldBe Set()
    found("winter") shouldBe Set(asset)

    testApp.service.album.addAssets(album.persistedId, Set(other))
    found("winter") shouldBe Set(asset, other)
    testApp.service.album.removeAssets(album.persistedId, Set(asset))
    found("winter") shouldBe Set(other)

    testApp.service.album.deleteById(album.persistedId)
    found("winter") shouldBe Set()
  }

  test("Search names: a phrase, and a term of several words, match within one name") {

    /**
     * Setup:
     *
     * An asset "ann.jpg" with a Face of "Mary", in the album "Mary" and the Location "Ann Arbor", and another asset in the folder
     * "Mary Ann".
     *
     * Assertions:
     *
     * Separate terms may match in different names and in the Search document, but a phrase or a term of several words must match
     * within one name or within the document.
     *
     * Edge cases:
     *
     * A phrase that crosses two names, and a partial last word in a phrase.
     */
    val annArbor = testApp.service.location.addLocation("Ann Arbor", 42.3, -83.7)
    val album = testApp.service.album.add("Mary")
    val folder: Folder = testApp.service.folder.add("Mary Ann")
    val split = importAsset("ann.jpg").persistedId
    val whole = importAsset("one.jpg", Some(folder)).persistedId
    addFace(addPerson("Mary"), testApp.service.asset.getById(split))
    testApp.service.album.addAssets(album.persistedId, Set(split))
    testApp.service.location.addAssets(annArbor.persistedId, Set(split))

    // Both words are on the first asset, but in different names and in the document
    found("mary ann") shouldBe Set(split, whole)
    found("\"mary ann\"") shouldBe Set(whole)
    found("mary-an") shouldBe Set(whole)
    found("\"ann arbor\"") shouldBe Set(split)
    found("\"ann jpg\"") shouldBe Set(split)
    found("\"mary ann arbor\"") shouldBe Set()
    // Whole words only in a phrase
    found("\"mary an\"") shouldBe Set()
  }

  test("Search names: a name with a camelCase hump is found by the word in one case and by the part after the hump") {

    /**
     * Setup:
     *
     * Assets each reached by one humped name - the person "DeShawn", the folder "MacArthur", the album "LaGuardia", the Location
     * "McAllen", and the Category "DeKalb" of the Location "Sycamore" - one with a Face of "Leblanc", and a decoy.
     *
     * Assertions:
     *
     * Every kind of name with a hump is found by the whole word in one case and by the part after the hump.
     *
     * Edge cases:
     *
     * The other way round: a name written in one case ("Leblanc") is found by the word typed with a hump.
     */
    val byPerson = importAsset("one.jpg")
    val byFolder = importAsset("two.jpg", Some(testApp.service.folder.add("MacArthur"))).persistedId
    val byAlbum = importAsset("three.jpg").persistedId
    val byLocation = importAsset("four.jpg").persistedId
    val byCategory = importAsset("five.jpg").persistedId
    val byOneCaseName = importAsset("six.jpg")
    importAsset("seven.jpg")

    addFace(addPerson("DeShawn"), byPerson)
    testApp.service.album.addAssets(testApp.service.album.add("LaGuardia").persistedId, Set(byAlbum))
    testApp.service.location.addAssets(testApp.service.location.addLocation("McAllen", 26.2, -98.2).persistedId, Set(byLocation))
    val dekalb = testApp.service.location.addCategory("DeKalb")
    val sycamore = testApp.service.location.addLocation("Sycamore", 41.9, -88.7, Some(dekalb.persistedId))
    testApp.service.location.addAssets(sycamore.persistedId, Set(byCategory))
    addFace(addPerson("Leblanc"), byOneCaseName)

    found("deshawn") shouldBe Set(byPerson.persistedId)
    found("shawn") shouldBe Set(byPerson.persistedId)
    found("macarthur") shouldBe Set(byFolder)
    found("arthur") shouldBe Set(byFolder)
    found("laguardia") shouldBe Set(byAlbum)
    found("guardia") shouldBe Set(byAlbum)
    found("mcallen") shouldBe Set(byLocation)
    found("allen") shouldBe Set(byLocation)
    found("dekalb") shouldBe Set(byCategory)
    found("kalb") shouldBe Set(byCategory)
    // And the other way round: a name in one case is found by the word typed with a hump
    found("LeBlanc") shouldBe Set(byOneCaseName.persistedId)
  }

  test("Search names: a phrase matches within one reading of a name with a camelCase hump") {

    /**
     * Setup:
     *
     * An asset in the Location "LaGuardia Airport".
     *
     * Assertions:
     *
     * A phrase matches the name typed in one case, split at the hump, or with the hump.
     *
     * Edge cases:
     *
     * A phrase that would match only by running from the end of one reading into the other.
     */
    val airport = testApp.service.location.addLocation("LaGuardia Airport", 40.8, -73.9)
    val asset = importAsset("one.jpg").persistedId
    testApp.service.location.addAssets(airport.persistedId, Set(asset))

    found("\"laguardia airport\"") shouldBe Set(asset)
    found("\"la guardia airport\"") shouldBe Set(asset)
    found("\"LaGuardia airport\"") shouldBe Set(asset)
    // The two readings are not one run of words: the end of one does not lead into the other
    found("\"airport laguardia\"") shouldBe Set()
  }

  test("Search names: terms combine across sources, and an exclusion holds in every source") {

    /**
     * Setup:
     *
     * Alice's Face in three assets - one in the Location Rome, one in the folder "Rome trip", one in neither - and an asset named
     * "rome.jpg".
     *
     * Assertions:
     *
     * Terms may each match in a different source (a person, a Location, a folder, a file name), and an excluded term removes an
     * asset when it matches in any source.
     */
    val rome = testApp.service.location.addLocation("Rome", 41.9, 12.5)
    val folder: Folder = testApp.service.folder.add("Rome trip")
    val alice = addPerson("Alice")
    val aliceInRome = importAsset("one.jpg")
    val aliceInFolder = importAsset("two.jpg", Some(folder))
    val aliceAlone = importAsset("three.jpg")
    val romeFile = importAsset("rome.jpg").persistedId
    List(aliceInRome, aliceInFolder, aliceAlone).foreach(addFace(alice, _))
    testApp.service.location.addAssets(rome.persistedId, Set(aliceInRome.persistedId))

    found("alice rome") shouldBe Set(aliceInRome.persistedId, aliceInFolder.persistedId)
    found("rome") shouldBe Set(aliceInRome.persistedId, aliceInFolder.persistedId, romeFile)
    found("alice -rome") shouldBe Set(aliceAlone.persistedId)
    found("-alice") shouldBe Set(romeFile)
    found("-alice OR three") shouldBe Set(romeFile, aliceAlone.persistedId)
  }

  test("Search names: counts and grouped pages follow the names, resolved afresh for every page") {

    /**
     * Setup:
     *
     * Two assets in the folder "Trips" and one outside it, searched for "trips" in grouped pages of one asset.
     *
     * Assertions:
     *
     * The count and the first page's total follow the folder name, and the page after the cursor still finds an asset moved
     * meanwhile into a new subfolder, as the names are resolved again for every page.
     */
    val folder: Folder = testApp.service.folder.add("Trips")
    val first = importAsset("a.jpg", Some(folder)).persistedId
    val second = importAsset("b.jpg", Some(folder)).persistedId
    importAsset("c.jpg")

    def page(cursor: Option[SearchCursor]): GroupedSearchResult =
      searchGrouped(
        new SearchQuery(
          text = Some("trips"),
          rpp = 1,
          searchSort = List(SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)),
          grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC)),
          cursor = cursor
        ))

    count(new SearchQuery(text = Some("trips"))) shouldBe 2

    val firstPage = page(None)
    firstPage.total shouldBe Some(2)
    firstPage.assets.map(_.persistedId) shouldBe List(first)

    // The cursor belongs to the text as typed; the folder's new subfolder is found by the page after it
    val sub: Folder = testApp.service.folder.add("Sub", Some(folder.persistedId))
    testApp.service.library.moveAssetsToFolder(Set(second), sub.persistedId)
    page(firstPage.nextCursor).assets.map(_.persistedId) shouldBe List(second)
  }

  test("Relevance: an asset ranks by the best source the term matched it in") {

    /**
     * Setup:
     *
     * One term, "al", that reaches each asset through a different source: a file name, an album, a folder, a Category, a Location
     * and a person, plus one asset reached through all of them at once and an unrelated asset. Capture times order the assets of
     * equal worth.
     *
     * Assertions:
     *
     * Assets rank by the worth of their best source - a person, then a Location, a Category, a folder or an album alike, then the
     * file name - with the newer capture first among equals.
     *
     * Edge cases:
     *
     * The asset matched in every source is worth its best source, not the sum of them.
     */
    val folder: Folder = testApp.service.folder.add("Alfa")
    val alps = testApp.service.location.addCategory("Alps")
    val chamonix = testApp.service.location.addLocation("Chamonix", 45.9, 6.9, Some(alps.persistedId))
    val alba = testApp.service.location.addLocation("Alba", 44.7, 8.0)
    val album = testApp.service.album.add("Alto")
    val alice = addPerson("Alice")

    val byDocument = importAsset("almond.jpg")
    val byAlbum = importAsset("one.jpg")
    val byFolder = importAsset("two.jpg", Some(folder))
    val byCategory = importAsset("three.jpg")
    val byLocation = importAsset("four.jpg")
    val byPerson = importAsset("five.jpg")
    // Every source at once is worth its best source, a person, and not their sum
    val byEverySource = importAsset("alder.jpg", Some(folder))
    importAsset("unrelated.jpg")

    testApp.service.album.addAssets(album.persistedId, Set(byAlbum.persistedId, byEverySource.persistedId))
    testApp.service.location.addAssets(chamonix.persistedId, Set(byCategory.persistedId, byEverySource.persistedId))
    testApp.service.location.addAssets(alba.persistedId, Set(byLocation.persistedId, byEverySource.persistedId))
    List(byPerson, byEverySource).foreach(addFace(alice, _))

    // A folder and an album are worth the same, and so are the two assets of the person: the newer capture reads first
    takenAt(byFolder, "2026-09-06T10:00:00")
    takenAt(byAlbum, "2026-09-05T10:00:00")
    takenAt(byPerson, "2026-09-06T10:00:00")
    takenAt(byEverySource, "2026-09-05T10:00:00")

    ranked("al") shouldBe
      List(byPerson, byEverySource, byLocation, byCategory, byFolder, byAlbum, byDocument).map(_.persistedId)
  }

  test("Relevance: terms add up, alternatives count as the best of them, and an exclusion counts for nothing") {

    /**
     * Setup:
     *
     * Alice's Face in an asset in the Location Rome and in an asset named "rome.jpg", an asset named "alice-rome.jpg", and
     * another asset in Rome, captured on four consecutive days.
     *
     * Assertions:
     *
     * Each AND-ed term adds the worth of its best source, an OR group is worth its best matching alternative, and an excluded
     * term filters without scoring.
     *
     * Edge cases:
     *
     * Text of only exclusions, where every match ties and the tiebreakers alone decide the order.
     */
    val rome = testApp.service.location.addLocation("Rome", 41.9, 12.5)
    val alice = addPerson("Alice")
    val aliceInRome = importAsset("one.jpg")
    val aliceAndFile = importAsset("rome.jpg")
    val fileOnly = importAsset("alice-rome.jpg")
    val inRome = importAsset("two.jpg")
    List(aliceInRome, aliceAndFile).foreach(addFace(alice, _))
    testApp.service.location.addAssets(rome.persistedId, Set(aliceInRome.persistedId, inRome.persistedId))
    takenAt(aliceInRome, "2026-09-01T10:00:00")
    takenAt(aliceAndFile, "2026-09-02T10:00:00")
    takenAt(fileOnly, "2026-09-03T10:00:00")
    takenAt(inRome, "2026-09-04T10:00:00")

    // 5 + 4, 5 + 1, 1 + 1: the older capture leads because it is the better match
    ranked("alice rome") shouldBe List(aliceInRome, aliceAndFile, fileOnly).map(_.persistedId)
    // Either alternative as a person is 5, however many of them match, so the newer capture leads; then the Location, the file
    ranked("alice OR rome") shouldBe List(aliceAndFile, aliceInRome, inRome, fileOnly).map(_.persistedId)
    // An exclusion filters and scores nothing
    ranked("alice -two") shouldBe List(aliceAndFile, aliceInRome, fileOnly).map(_.persistedId)
    ranked("alice OR -one") shouldBe List(aliceAndFile, aliceInRome, fileOnly, inRome).map(_.persistedId)
    // Nothing but exclusions: every match is as relevant as the next, and the order is the tiebreakers'
    ranked("-two") shouldBe List(fileOnly, aliceAndFile, aliceInRome).map(_.persistedId)
  }

  test("Relevance: equal matches read newest capture first, the undated ones last, then by ID, page after page") {

    /**
     * Setup:
     *
     * Six assets that "beach" matches equally: an older capture, the newest one, two twins captured at the same time between
     * them, and two undated assets.
     *
     * Assertions:
     *
     * Equal matches read newest capture first, undated ones last, and by ID within a tie, and offset pages of two and of five cut
     * the same order.
     */
    val assets = (1 to 6).map(n => importAsset(s"beach-$n.jpg").persistedId).toList
    val List(older, newest, twinA, twinB, undatedA, undatedB) = assets: @unchecked
    val imported = OffsetDateTime.parse("2026-09-07T12:00:00Z")
    testContext.setAssetDates(older, Some(LocalDateTime.parse("2026-09-04T10:00:00")), imported)
    testContext.setAssetDates(newest, Some(LocalDateTime.parse("2026-09-06T10:00:00")), imported)
    List(twinA, twinB).foreach(testContext.setAssetDates(_, Some(LocalDateTime.parse("2026-09-05T10:00:00")), imported))
    List(undatedA, undatedB).foreach(testContext.setAssetDates(_, None, imported))

    val expected = newest :: List(twinA, twinB).sorted ::: older :: List(undatedA, undatedB).sorted
    ranked("beach") shouldBe expected
    // Offset pages of the flat grid cut the same order
    (1 to 3).toList.flatMap(page => ranked("beach", rpp = 2, page = page)) shouldBe expected
    (1 to 2).toList.flatMap(page => ranked("beach", rpp = 5, page = page)) shouldBe expected
  }

  /** The IDs of the assets a Search text matches, most relevant first */
  private def ranked(text: String, rpp: Int = PAGE_SIZE, page: Int = 1): List[String] =
    search(new SearchQuery(text = Some(text), rpp = rpp, page = page, searchSort = List(SearchSort.Relevance))).records
      .map(_.persistedId)

  /** Gives the asset a capture time, the tiebreaker of equally relevant matches */
  private def takenAt(asset: Asset, taken: String): Unit =
    testContext.setAssetDates(asset.persistedId, Some(LocalDateTime.parse(taken)), OffsetDateTime.parse("2026-09-07T12:00:00Z"))

  /** The IDs of the assets a Search text matches */
  private def found(text: String): Set[String] =
    search(new SearchQuery(rpp = PAGE_SIZE, text = Some(text))).records.map(_.persistedId).toSet

  /** The people a one-term Search text resolves to */
  private def personHits(text: String): Set[String] =
    testApp.service.search
      .resolveText(SearchText.parse(text).value)
      .groups
      .head
      .alternatives
      .head
      .ids
      .getOrElse(SearchSource.Person, Set())

  private def importAsset(fileName: String, folder: Option[Folder] = None, metadata: UserMetadata = UserMetadata()): Asset =
    testApp.service.library.addAsset(
      testContext.makeAssetWithData(Some(testContext.makeAsset(filename = fileName, folder = folder, userMetadata = metadata))))

  private def addPerson(name: String): Person = testApp.service.person.addPerson(Person(name = Some(name)))

  /** A Face of the person in the asset; the vector is irrelevant to search */
  private def addFace(person: Person, asset: Asset): Face =
    testContext.addTestFace(person, asset, Array.fill(FaceDetectionService.EMBEDDING_DIMENSIONS)(Random.nextFloat()))

  /** The fields of an asset's metadata parameter rows, one per indexed value, sorted */
  private def parameterFields(asset: Asset): List[String] =
    testApp.txManager.asReadOnly {
      query("SELECT field_id FROM metadata_parameter WHERE asset_id = ?", asset.persistedId).map(_("field_id").toString).sorted
    }

  /**
   * The rows of `search_document` that `write` inserted or updated, counted in its own transaction: the transaction's table
   * statistics on PostgreSQL, the connection's change count on SQLite (an asset without metadata changes no other table)
   */
  private def documentRowsWrittenBy(write: => Unit): Long =
    testApp.txManager.withTransaction {
      def written: Long =
        if (isPostgres)
          query("SELECT n_tup_ins + n_tup_upd AS n FROM pg_stat_xact_user_tables WHERE relname = 'search_document'")
            .head("n")
            .toString
            .toLong
        else query("SELECT total_changes() AS n").head("n").toString.toLong

      val before = written
      write
      written - before
    }

  /** Every flat search reads a bounded page; one of this size holds every fixture */
  private val PAGE_SIZE = 100

  /** The default view and sort of the grid */
  private val browsingView = Map[String, Any](FieldConst.Asset.IS_RECYCLED -> false)
  private val byImport = List(SearchSort(FieldConst.CREATED_AT, SortDirection.DESC))

  /** A flat page's statement, for its plan */
  private def flatPage(query: SearchQuery): SqlStr =
    SearchQueries.flat(searchDialect, query, RequestContext.getRepository.persistedId)

  test("Indexing writes open their own transaction") {

    /**
     * Setup:
     *
     * An asset and a KEYWORD field, with the search service's index writes called directly, outside any transaction.
     *
     * Assertions:
     *
     * Adding a metadata value and reindexing a rename each write the Search document on their own.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = "keywords", fieldType = FieldType.KEYWORD))
    val asset = testContext.persistAsset()

    val withValue = asset.copy(userMetadata = UserMetadata(Map(field.persistedId -> Set("giraffe"))))
    testApp.service.search.addMetadataValue(withValue, field, "giraffe")
    documentBody(asset).get should include("giraffe")

    val renamed = asset.copy(fileName = "zebra.jpg")
    testApp.service.search.reindexAsset(renamed)
    documentBody(asset).get should include("zebra")
  }

  test("Search reads open their own transaction") {

    /**
     * Setup:
     *
     * One asset with coordinates, and the search service's reads called directly, outside any transaction.
     *
     * Assertions:
     *
     * The flat search, the count, the capped count, the map cells, the map bounds and the grouped search each find the asset on
     * their own.
     */
    val asset = testContext.persistAsset()
    testContext.setAssetCoordinates(asset.persistedId, latitude = 10, longitude = 20)
    val q = new SearchQuery(rpp = PAGE_SIZE)

    testApp.service.search.search(q).records.map(_.persistedId) shouldBe List(asset.persistedId)
    testApp.service.search.count(q) shouldBe 1
    testApp.service.search.cappedCount(q) shouldBe 1
    testApp.service.search.mapCells(q, BoundingBox(-90, -180, 90, 180), zoom = 0).cells.map(_.count).sum shouldBe 1
    testApp.service.search.mapBounds(q).map(_.count) shouldBe Some(1)

    val grouped = new SearchQuery(
      rpp = PAGE_SIZE,
      searchSort = List(SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)),
      grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC))
    )
    testApp.service.search.searchGrouped(grouped, scopeFingerprint = "test").assets.map(_.persistedId) shouldBe
      List(asset.persistedId)
  }

  /** The stored body of an asset's Search document, if it has one */
  private def documentBody(asset: Asset): Option[String] =
    testApp.txManager.asReadOnly {
      query("SELECT body FROM search_document WHERE asset_id = ?", asset.persistedId).headOption.map(_("body").toString)
    }

}
