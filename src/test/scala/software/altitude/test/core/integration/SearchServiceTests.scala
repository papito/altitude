package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Altitude
import software.altitude.core.Api
import software.altitude.core.FieldConst
import software.altitude.core.models._
import software.altitude.core.util._
import software.altitude.test.core.IntegrationTestCore

import scala.language.reflectiveCalls
import scala.math.Ordered.orderingToOrdered

@DoNotDiscover class SearchServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Index and search by term") {
    val field1 = testApp.service.metadata.addField(
      UserMetadataField(
        name = "keywords",
        fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(
      UserMetadataField(
        name = "quotes",
        fieldType = FieldType.TEXT))

    val field3 = testApp.service.metadata.addField(
      UserMetadataField(
        name = "cast",
        fieldType = FieldType.KEYWORD))

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
      field3.persistedId -> Set("Lindsay Lohan", "Conan O'Brien", "Teri Hatcher", "Sam Rockwell"))

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
      field3.persistedId -> Set("Keanu Reeves", "Sandra Bullock", "Dennis Hopper", "Teri Hatcher"))

    testContext.persistAsset(metadata = UserMetadata(data))

    var results: SearchResult = testApp.service.library.search(new SearchQuery(text = Some("keanu")))
    results.nonEmpty shouldBe true
    results.total shouldBe 1
    // check that the document is indeed - an asset
    val resultJson = results.records.head
    Asset.fromJson(resultJson)

    results = testApp.service.library.search(new SearchQuery(text = Some("TERI")))
    results.nonEmpty shouldBe true
    results.total shouldBe 2
  }

  test("Filter by folder") {
    val field1 = testApp.service.metadata.addField(
      UserMetadataField(
        name = "keywords",
        fieldType = FieldType.KEYWORD))

    val data = Map[String, Set[String]](
      field1.persistedId -> Set("space", "force", "tactical", "pants")
    )

    val metadata = UserMetadata(data)

    val folder1: Folder = testApp.service.library.addFolder("folder1")

    val folder1_1: Folder = testApp.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    1 to 3 foreach {_ =>
      testContext.persistAsset(folder=Some(folder1_1), metadata=metadata)
    }
    1 to 3 foreach {_ =>
      testContext.persistAsset(folder=Some(folder1), metadata=metadata)
    }

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

  def fixtureForPersonFilter: Object {val assetsPerPersonCount: Int; val people: Seq[Person]} = new {
    val peopleCount =  3
    val assetsPerPersonCount = 3

    val people: Seq[Person] = 1 to peopleCount map { _ =>
      val person = testApp.service.person.addPerson(Person())
      1 to assetsPerPersonCount foreach { _ =>
        testContext.addTestFacesAndAssets(person)
      }

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
    1 to 6 foreach { n =>
      testContext.persistAsset()
    }

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
    val field1 = testApp.service.metadata.addField(
      UserMetadataField(
        name = "field 1",
        fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(
      UserMetadataField(
        name = "field 2",
        fieldType = FieldType.NUMBER))

    val field3 = testApp.service.metadata.addField(
      UserMetadataField(
        name = "field 3",
        fieldType = FieldType.BOOL))

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
      new SearchQuery(metadataFilters = Map(
        field3.persistedId -> Query.EQUALS(true),
        field2.persistedId -> Query.EQUALS(1)))
    )
    results.total shouldBe 2
  }

  /**
    * What happens if we have a number field and search by integer?
    */
  test("Search by wrong field type") {
    val field1 = testApp.service.metadata.addField(
      UserMetadataField(
        name = "field 1",
        fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(
      UserMetadataField(
        name = "field 2",
        fieldType = FieldType.NUMBER))

    val data = Map[String, Set[String]](
      field1.persistedId -> Set("one"),
      field2.persistedId -> Set("1")
    )
    testContext.persistAsset(metadata = UserMetadata(data))

   val results = testApp.service.library.search(
      new SearchQuery(metadataFilters = Map(
        field1.persistedId -> Query.EQUALS(1)))
    )
    results.total shouldBe 0
  }

  test("Parametarized search") {
    val field1 = testApp.service.metadata.addField(
      UserMetadataField(
        name = "field 1",
        fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(
      UserMetadataField(
        name = "field 2",
        fieldType = FieldType.NUMBER))

    val asset1: Asset = testContext.persistAsset()
    val asset2: Asset = testContext.persistAsset()
    val asset3: Asset = testContext.persistAsset()

    testApp.service.library.addMetadataValue(asset1.persistedId, fieldId = field1.persistedId, newValue = "one")
    testApp.service.library.addMetadataValue(asset2.persistedId, fieldId = field1.persistedId, newValue = "one")
    testApp.service.library.addMetadataValue(asset3.persistedId, fieldId = field1.persistedId, newValue = "two")

    testApp.service.library.addMetadataValue(asset1.persistedId, fieldId = field2.persistedId, newValue = 1)
    testApp.service.library.addMetadataValue(asset2.persistedId, fieldId = field2.persistedId, newValue = 1)
    testApp.service.library.addMetadataValue(asset3.persistedId, fieldId = field2.persistedId, newValue = 2)

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
    val field1 = testApp.service.metadata.addField(
      UserMetadataField(
        name = "field 1",
        fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(
      UserMetadataField(
        name = "field 2",
        fieldType = FieldType.NUMBER))

    val asset1: Asset = testContext.persistAsset()

    testApp.service.library.addMetadataValue(asset1.persistedId, fieldId = field1.persistedId, newValue = "one")
    // it's the only value for this field so get it
    val metadata: UserMetadata = testApp.service.metadata.getMetadata(asset1.persistedId)
    val mdVal = metadata(field1.persistedId).head

    // tag a second field for posterity
    testApp.service.library.addMetadataValue(asset1.persistedId, fieldId = field2.persistedId, newValue = 3)

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
    testApp.service.library.updateMetadataValue(asset1.persistedId, mdVal.persistedId, "newone")
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
    testApp.service.library.deleteMetadataValue(assetId = asset1.persistedId, valueId = mdVal.persistedId)

    results = testApp.service.library.search(new SearchQuery(text = Some("one")))
    results.isEmpty shouldBe true
  }

  test("Can sort in ASC order by created at date") {
    val assets = List.fill(4)(testContext.persistAsset())

    testApp.txManager.withTransaction {
      assets.zipWithIndex.foreach { case (asset, index) =>
        val futureTime = new java.sql.Timestamp(System.currentTimeMillis() + (3600000 * (index + 1)))
        this.update("UPDATE asset SET created_at = ? WHERE id = ?", getSqlDateTime(futureTime), asset.persistedId)
      }
    }

    val sort = SearchSort(field = Api.Field.SearchSort.BY_ASSET_CREATED_AT, direction = SortDirection.ASC)
    val resultsAsc = testApp.service.library.search(new SearchQuery(searchSort = List(sort)))
    val sortedAssetsAsc: List[Asset] = resultsAsc.records.map(Asset.fromJson)

    sortedAssetsAsc.sliding(2).forall(
      assets => assets.head.createdAt.get >= assets.last.createdAt.get)
  }

  test("Can sort in DESC order by created at date") {
    val assets = List.fill(4)(testContext.persistAsset())

    testApp.txManager.withTransaction {
      assets.zipWithIndex.foreach { case (asset, index) =>
        val futureTime = new java.sql.Timestamp(System.currentTimeMillis() + (3600000 * (index + 1)))
        this.update("UPDATE asset SET created_at = ? WHERE id = ?", getSqlDateTime(futureTime), asset.persistedId)
      }
    }

    val sort = SearchSort(field = Api.Field.SearchSort.BY_ASSET_CREATED_AT, direction = SortDirection.DESC)
    val resultsAsc = testApp.service.library.search(new SearchQuery(searchSort = List(sort)))
    val sortedAssetsAsc: List[Asset] = resultsAsc.records.map(Asset.fromJson)

    sortedAssetsAsc.sliding(2).forall(
      assets => assets.head.createdAt.get <= assets.last.createdAt.get)
  }

  test("Sort info should be returned with query results") {
    1 to 2 foreach { idx =>
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
      FieldConst.Asset.IS_PIPELINE_PROCESSED -> false,
    )
    testApp.service.asset.updateByQuery(assetQuery, updateData)

    val assetSearchQuery = new SearchQuery(rpp = 3, page = 1)
    testApp.service.library.search(assetSearchQuery).total shouldBe 0
  }
}
