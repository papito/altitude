package altitude.core.integration

import java.time.LocalDateTime
import java.time.OffsetDateTime
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.should.Matchers.{ shouldBe, shouldNot }

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.language.reflectiveCalls
import scala.math.Ordered.orderingToOrdered
import scala.util.Random

import altitude.core.Altitude
import altitude.core.Api
import altitude.core.FieldConst
import altitude.core.models.*
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.sinks.VoidAssetSink
import altitude.core.service.FaceDetectionService
import altitude.core.service.PersonService
import altitude.core.util.*

@DoNotDiscover class SearchServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Index and search by term") {
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

    var results: SearchResult = testApp.service.library.search(new SearchQuery(text = Some("keanu")))
    results.nonEmpty shouldBe true
    results.total shouldBe 1

    results = testApp.service.library.search(new SearchQuery(text = Some("TERI")))
    results.nonEmpty shouldBe true
    results.total shouldBe 2
  }

  test("Filter by folder") {
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "keywords", fieldType = FieldType.KEYWORD))

    val data = Map[String, Set[String]](
      field1.persistedId -> Set("space", "force", "tactical", "pants")
    )

    val metadata = UserMetadata(data)

    val folder1: Folder = testApp.service.folder.add("folder1")

    val folder1_1: Folder = testApp.service.folder.add(name = "folder1_1", parentId = folder1.id)

    (1 to 3).foreach(_ => testContext.persistAsset(folder = Some(folder1_1), metadata = metadata))
    (1 to 3).foreach(_ => testContext.persistAsset(folder = Some(folder1), metadata = metadata))

    val qFolder1_1 = new SearchQuery(text = Some("space"), folderIds = Set(folder1_1.persistedId))
    var results: SearchResult = testApp.service.library.search(qFolder1_1)
    results.total shouldBe 3

    val qFolder1 = new SearchQuery(text = Some("space"), folderIds = Set(folder1.persistedId))
    results = testApp.service.library.search(qFolder1)
    results.total shouldBe 6

    val qAllFolders = new SearchQuery()
    results = testApp.service.library.search(qAllFolders)
    results.total shouldBe 6

  }

  test("Searching with root folder ID includes triaged assets") {
    val folder1: Folder = testApp.service.folder.add("folder1")

    // 2 sorted assets in a sub-folder
    (1 to 2).foreach(_ => testContext.persistAsset(folder = Some(folder1)))
    // 1 triaged asset (no folder assigned)
    testContext.persistAsset(isTriaged = true)

    val rootFolderId = testContext.repository.rootFolderId

    val results = testApp.service.library.search(new SearchQuery(folderIds = Set(rootFolderId)))
    results.total shouldBe 3
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
    val f = fixtureForPersonFilter
    val q = new SearchQuery(personIds = Set(f.people.head.persistedId))
    val results = testApp.service.library.search(q)
    results.total shouldBe f.assetsPerPersonCount
  }

  test("Filter by more than one person") {
    val f = fixtureForPersonFilter
    val q = new SearchQuery(personIds = f.people.map(_.persistedId).toSet)
    val results = testApp.service.library.search(q)
    results.total shouldBe f.assetsPerPersonCount * f.people.length
  }

  test("Pagination") {
    (1 to 6).foreach(n => testContext.persistAsset())

    val q = new SearchQuery(rpp = 2, page = 1)
    val results = testApp.service.library.search(q)
    results.total shouldBe 6
    results.records.length shouldBe 2
    results.nonEmpty shouldBe true
    results.totalPages shouldBe 3

    val q2 = new SearchQuery(rpp = 2, page = 2)
    val results2 = testApp.service.library.search(q2)
    results2.total shouldBe 6
    results2.records.length shouldBe 2
    results2.totalPages shouldBe 3

    val q3 = new SearchQuery(rpp = 2, page = 3)
    val results3 = testApp.service.library.search(q3)
    results3.total shouldBe 6
    results3.records.length shouldBe 2
    results3.totalPages shouldBe 3

    // page too far
    val q4 = new SearchQuery(rpp = 2, page = 4)
    val results4 = testApp.service.library.search(q4)
    results4.total shouldBe 0
    results4.records.length shouldBe 0
    results4.totalPages shouldBe 0

    val q5 = new SearchQuery(rpp = 6, page = 1)
    val results5 = testApp.service.library.search(q5)
    results5.total shouldBe 6
    results5.records.length shouldBe 6
    results5.totalPages shouldBe 1

    val q6 = new SearchQuery(rpp = 20, page = 1)
    val results6 = testApp.service.library.search(q6)
    results6.total shouldBe 6
    results6.records.length shouldBe 6
    results6.totalPages shouldBe 1
  }

  test("Create assets and search by metadata") {
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
    var results = testApp.service.library.search(new SearchQuery(text = Some("one")))
    results.total shouldBe 1

    results = testApp.service.library.search(
      new SearchQuery(metadataFilters = Map(field3.persistedId -> Query.EQUALS(true), field2.persistedId -> Query.EQUALS(1)))
    )
    results.total shouldBe 2
  }

  /** What happens if we have a number field and search by integer? */
  test("Search by wrong field type") {
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "field 1", fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = "field 2", fieldType = FieldType.NUMBER))

    val data = Map[String, Set[String]](
      field1.persistedId -> Set("one"),
      field2.persistedId -> Set("1")
    )
    testContext.persistAsset(metadata = UserMetadata(data))

    val results = testApp.service.library.search(
      new SearchQuery(metadataFilters = Map(field1.persistedId -> Query.EQUALS(1)))
    )
    results.total shouldBe 0
  }

  test("Parametarized search") {
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

    val results = testApp.service.library.search(
      new SearchQuery(
        metadataFilters = Map(
          field1.persistedId -> Query.EQUALS("one"),
          field2.persistedId -> Query.EQUALS(1)
        )
      )
    )
    results.total shouldBe 2
  }

  test("Updating and removing metadata values updates search index") {
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = "field 1", fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = "field 2", fieldType = FieldType.NUMBER))

    val asset1: Asset = testContext.persistAsset()

    testApp.service.metadata.addMetadataValue(asset1.persistedId, fieldId = field1.persistedId, newValue = "one")
    // it's the only value for this field so get it
    val metadata: UserMetadata = testApp.service.metadata.getMetadata(asset1.persistedId)
    val mdVal = metadata(field1.persistedId).head

    // tag a second field for posterity
    testApp.service.metadata.addMetadataValue(asset1.persistedId, fieldId = field2.persistedId, newValue = 3)

    var results = testApp.service.library.search(new SearchQuery(text = Some("one")))
    results.total shouldBe 1

    // parametarized search
    results = testApp.service.library.search(
      new SearchQuery(
        metadataFilters = Map(
          field1.persistedId -> "one",
          field2.persistedId -> 3
        )
      )
    )
    results.records.length shouldBe 1
    results.total shouldBe 1

    // update the value and search again
    testApp.service.metadata.updateMetadataValue(asset1.persistedId, mdVal.persistedId, "newone")
    results = testApp.service.library.search(new SearchQuery(text = Some("newone")))
    results.total shouldBe 1

    // parametarized search
    results = testApp.service.library.search(
      new SearchQuery(
        metadataFilters = Map(
          field1.persistedId -> "newone",
          field2.persistedId -> 3
        )
      )
    )
    results.records.length shouldBe 1
    results.total shouldBe 1

    // remove the value and search again
    testApp.service.metadata.deleteMetadataValue(assetId = asset1.persistedId, valueId = mdVal.persistedId)

    results = testApp.service.library.search(new SearchQuery(text = Some("one")))
    results.isEmpty shouldBe true
  }

  test("Can sort in ASC order by created at date") {
    val assets = List.fill(4)(testContext.persistAsset())

    testApp.txManager.withTransaction {
      assets.zipWithIndex.foreach {
        case (asset, index) =>
          val futureTime = new java.sql.Timestamp(System.currentTimeMillis() + (3600000 * (index + 1)))
          this.update("UPDATE asset SET created_at = ? WHERE id = ?", getSqlDateTime(futureTime), asset.persistedId)
      }
    }

    val sort = SearchSort(field = Api.Field.SearchSort.BY_ASSET_CREATED_AT, direction = SortDirection.ASC)
    val resultsAsc = testApp.service.library.search(new SearchQuery(searchSort = List(sort)))
    val sortedAssetsAsc: List[Asset] = resultsAsc.records

    sortedAssetsAsc.sliding(2).forall(assets => assets.head.createdAt.get >= assets.last.createdAt.get)
  }

  test("Can sort in DESC order by created at date") {
    val assets = List.fill(4)(testContext.persistAsset())

    testApp.txManager.withTransaction {
      assets.zipWithIndex.foreach {
        case (asset, index) =>
          val futureTime = new java.sql.Timestamp(System.currentTimeMillis() + (3600000 * (index + 1)))
          this.update("UPDATE asset SET created_at = ? WHERE id = ?", getSqlDateTime(futureTime), asset.persistedId)
      }
    }

    val sort = SearchSort(field = Api.Field.SearchSort.BY_ASSET_CREATED_AT, direction = SortDirection.DESC)
    val resultsAsc = testApp.service.library.search(new SearchQuery(searchSort = List(sort)))
    val sortedAssetsAsc: List[Asset] = resultsAsc.records

    sortedAssetsAsc.sliding(2).forall(assets => assets.head.createdAt.get <= assets.last.createdAt.get)
  }

  test("Sort info should be returned with query results") {
    (1 to 2).foreach {
      idx =>
        val asset: Asset = testContext.persistAsset()
    }

    // try with no sort info at all
    val sort = SearchSort(field = Api.Field.SearchSort.BY_ASSET_CREATED_AT, direction = SortDirection.ASC)
    val results = testApp.service.library.search(new SearchQuery(searchSort = List(sort)))
    results.sort shouldNot be(empty)
    results.sort.head.direction shouldBe SortDirection.ASC
    results.sort.head.field shouldBe "created_at"
  }

  test("Dangling assets should not be searchable") {
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
    testApp.service.library.search(assetSearchQuery).total shouldBe 0
  }

  test("An imported asset has a Search document of its file name words") {
    val asset = importAsset("IMG_1234-beach.final.jpg")

    documentBody(asset) shouldBe Some("img 1234 beach final jpg")
    testApp.service.library.search(new SearchQuery(text = Some("beach"))).total shouldBe 1
  }

  test("A Search document holds both readings of a value with a camelCase hump, with and without it") {
    val asset = importAsset("IMG_1234-beachSunset.final.jpg")

    documentBody(asset) shouldBe Some("img 1234 beach sunset final jpg img 1234 beachsunset final jpg")
  }

  test("Search text: a word with a camelCase hump is found by the word in one case, a prefix of it, and its parts") {
    val humped = importAsset("McDonald_beachSunset.jpg").persistedId
    importAsset("donut.jpg")

    List("mcdonald", "mcdon", "McDonald", "donald", "sunset", "beachsunset").foreach {
      text => withClue(s"[$text] ")(found(text) shouldBe Set(humped))
    }
  }

  test("Search text: a word typed with a camelCase hump finds the word written in one case") {
    val capitalized = importAsset("Mcdonald.jpg").persistedId
    val upperCase = importAsset("MCDONALD.jpg").persistedId
    val spaced = importAsset("Mc Donald.jpg").persistedId
    importAsset("donald.jpg")

    found("McDonald") shouldBe Set(capitalized, upperCase, spaced)
    found("\"McDonald\"") shouldBe Set(capitalized, upperCase, spaced)
  }

  test("Renaming an asset rewrites its Search document") {
    val asset = importAsset("beachSunset.jpg")

    testApp.service.asset.rename(asset.persistedId, "mountain-lake.jpg")

    documentBody(asset) shouldBe Some("mountain lake jpg")
    // The full-text index follows the document, not just the stored body
    testApp.service.library.search(new SearchQuery(text = Some("sunset"))).total shouldBe 0
    testApp.service.library.search(new SearchQuery(text = Some("lake"))).total shouldBe 1
  }

  test("Editing metadata rewrites the Search document") {
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
    val notes = testApp.service.metadata.addField(UserMetadataField(name = "notes", fieldType = FieldType.TEXT))
    val asset = importAsset("one.jpg")
    importAsset("two.jpg")

    testApp.service.metadata.addMetadataValue(asset.persistedId, fieldId = notes.persistedId, newValue = "The tide comes in")

    found("tide") shouldBe Set(asset.persistedId)
    parameterFields(asset) shouldBe List()
  }

  test("An imported or reindexed asset has metadata parameters for its faceted values only, and is found by all of them") {
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
      testApp.service.library.search(new SearchQuery(metadataFilters = filters)).records.map(_.persistedId) shouldBe
        List(asset.persistedId)
    }

    isIndexed()
    // A rename reindexes the asset
    testApp.service.asset.rename(asset.persistedId, "renamed.jpg")
    isIndexed()
  }

  test("Recycling an asset keeps its Search document") {
    val asset = importAsset("beach.jpg")

    testApp.service.library.recycleAssets(Set(asset.persistedId))

    documentBody(asset) shouldBe Some("beach jpg")
  }

  test("Purging an asset removes its Search document") {
    val kept = importAsset("lake.jpg")
    val purged = importAsset("beach.jpg")

    val source = Source.single((purged, PipelineContext(testContext.repository, testContext.user)))
    Await.result(testApp.service.purgePipeline.run(source, VoidAssetSink()), Duration.Inf)

    documentBody(purged) shouldBe None
    documentBody(kept) shouldBe Some("lake jpg")

    // A document written after the purge may take the purged one's place in the table: the full-text index must have
    // forgotten the purged words, or they would now lead to this asset
    importAsset("mountain.jpg")
    testApp.service.library.search(new SearchQuery(text = Some("beach"))).total shouldBe 0
    testApp.service.library.search(new SearchQuery(text = Some("mountain"))).total shouldBe 1
    testApp.service.library.search(new SearchQuery(text = Some("lake"))).total shouldBe 1
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
    val f = fixtureForText

    found("beach golden") shouldBe Set(f.beach, f.dog)
    found("gate beach") shouldBe Set(f.beach)
    found("beach bridge") shouldBe Set()
  }

  test("Search text: a term matches the start of a word") {
    val f = fixtureForText

    found("sun") shouldBe Set(f.beach, f.dog)
    found("GOLD") shouldBe Set(f.beach, f.dog)
    found("unset") shouldBe Set()
  }

  test("Search text: a term of several words is those words in sequence, the last one a prefix") {
    val f = fixtureForText

    found("IMG_12") shouldBe Set(f.beach, f.lake)
    found("img-123") shouldBe Set(f.beach)
    found("beachSun") shouldBe Set(f.beach)
    found("12_IMG") shouldBe Set()
    // Only the last word is a prefix
    found("IM_1234") shouldBe Set()
  }

  test("Search text: a phrase is whole consecutive words") {
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
    val f = fixtureForText

    found("gate OR bridge") shouldBe Set(f.beach, f.lake)
    found("retriever OR bridge img") shouldBe Set(f.lake)
    found("img \"bay bridge\" OR 1234") shouldBe Set(f.beach, f.lake)
    // Lower case is a word, not the operator
    found("gate or bridge") shouldBe Set()
  }

  test("Search text: a leading minus excludes") {
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
    val f = fixtureForText

    found("beach & (sunset:* | 'x') !") shouldBe Set()
    found("beach* AND NOT NEAR(lake)") shouldBe Set()
    found("(beach) & sunset:*") shouldBe Set(f.beach, f.dog)
    found("- !!! OR") shouldBe Set(f.beach, f.lake, f.dog)
  }

  test("Search names: a named, visible person finds the assets with a Face of theirs") {
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
    val folder: Folder = testApp.service.folder.add("Trips")
    val first = importAsset("a.jpg", Some(folder)).persistedId
    val second = importAsset("b.jpg", Some(folder)).persistedId
    importAsset("c.jpg")

    def page(cursor: Option[SearchCursor]): GroupedSearchResult =
      testApp.service.library.searchGrouped(
        new SearchQuery(
          text = Some("trips"),
          rpp = 1,
          searchSort = List(SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)),
          grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC)),
          cursor = cursor
        ))

    testApp.service.library.count(new SearchQuery(text = Some("trips"))) shouldBe 2

    val firstPage = page(None)
    firstPage.total shouldBe Some(2)
    firstPage.assets.map(_.persistedId) shouldBe List(first)

    // The cursor belongs to the text as typed; the folder's new subfolder is found by the page after it
    val sub: Folder = testApp.service.folder.add("Sub", Some(folder.persistedId))
    testApp.service.library.moveAssetsToFolder(Set(second), sub.persistedId)
    page(firstPage.nextCursor).assets.map(_.persistedId) shouldBe List(second)
  }

  test("Relevance: an asset ranks by the best source the term matched it in") {
    // One term, "al", that reaches each asset through a different source
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
  private def ranked(text: String, rpp: Int = 0, page: Int = 1): List[String] =
    testApp.service.library
      .search(new SearchQuery(text = Some(text), rpp = rpp, page = page, searchSort = List(SearchSort.Relevance)))
      .records
      .map(_.persistedId)

  /** Gives the asset a capture time, the tiebreaker of equally relevant matches */
  private def takenAt(asset: Asset, taken: String): Unit =
    testContext.setAssetDates(asset.persistedId, Some(LocalDateTime.parse(taken)), OffsetDateTime.parse("2026-09-07T12:00:00Z"))

  /** The IDs of the assets a Search text matches */
  private def found(text: String): Set[String] =
    testApp.service.library.search(new SearchQuery(text = Some(text))).records.map(_.persistedId).toSet

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

  /** The stored body of an asset's Search document, if it has one */
  private def documentBody(asset: Asset): Option[String] =
    testApp.txManager.asReadOnly {
      query("SELECT body FROM search_document WHERE asset_id = ?", asset.persistedId).headOption.map(_("body").toString)
    }

}
