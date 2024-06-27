package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.models._
import software.altitude.core.util._
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class SearchServiceTests(val config: Map[String, Any]) extends IntegrationTestCore {

  test("Index and search by term") {
    val field1 = altitude.service.metadata.addField(
      MetadataField(
        name = "keywords",
        fieldType = FieldType.KEYWORD))

    val field2 = altitude.service.metadata.addField(
      MetadataField(
        name = "quotes",
        fieldType = FieldType.TEXT))

    val field3 = altitude.service.metadata.addField(
      MetadataField(
        name = "cast",
        fieldType = FieldType.KEYWORD))

    var data = Map[String, Set[String]](
      field1.id.get -> Set("picture", "man", "office", "monday", "how is this my life?"),
      field2.id.get -> Set(
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
      field3.id.get -> Set("Lindsay Lohan", "Conan O'Brien", "Teri Hatcher", "Sam Rockwell"))

    testContext.persistAsset(metadata = Metadata(data))

    data = Map[String, Set[String]](
      field1.id.get -> Set("tree", "shoe", "desert", "California"),
      field2.id.get -> Set(
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
      field3.id.get -> Set("Keanu Reeves", "Sandra Bullock", "Dennis Hopper", "Teri Hatcher"))

    testContext.persistAsset(metadata = Metadata(data))

    var results: SearchResult = altitude.service.library.search(new SearchQuery(text = Some("keanu")))
    results.nonEmpty shouldBe true
    results.total shouldBe 1
    // check that the document is indeed - an asset
    val resultJson = results.records.head
    Asset.fromJson(resultJson)

    results = altitude.service.library.search(new SearchQuery(text = Some("TERI")))
    results.nonEmpty shouldBe true
    results.total shouldBe 2
  }

  test("Narrow down search to a folder") {
    val field1 = altitude.service.metadata.addField(
      MetadataField(
        name = "keywords",
        fieldType = FieldType.KEYWORD))

    val data = Map[String, Set[String]](
      field1.id.get -> Set("space", "force", "tactical", "pants")
    )

    val metadata = Metadata(data)

    val folder1: Folder = altitude.service.library.addFolder("folder1")

    val folder1_1: Folder = altitude.service.library.addFolder(
      name = "folder1_1", parentId = folder1.id)

    1 to 3 foreach {_ =>
      testContext.persistAsset(folder=Some(folder1_1), metadata=metadata)
    }
    1 to 3 foreach {_ =>
      testContext.persistAsset(folder=Some(folder1), metadata=metadata)
    }

    val qFolder1_1 = new SearchQuery(text = Some("space"), folderIds = Set(folder1_1.id.get))
    var results: SearchResult = altitude.service.library.search(qFolder1_1)
    results.total shouldBe 3

    val qFolder1 = new SearchQuery(text = Some("space"), folderIds = Set(folder1.id.get))
    results = altitude.service.library.search(qFolder1)
    results.total shouldBe 6

    val qAllFolders = new SearchQuery()
    results = altitude.service.library.search(qAllFolders)
    results.total shouldBe 6

  }

  test("Recycled assets should not be in the search index") {
    val asset: Asset = testContext.persistAsset()
    testContext.persistAsset()

    altitude.service.library.recycleAsset(asset.id.get)

    val results = altitude.service.library.search(new SearchQuery)
    results.total shouldBe 1
  }

  test("Pagination") {
    1 to 6 foreach { n =>
      testContext.persistAsset()
    }

    val q = new SearchQuery(rpp = 2, page = 1)
    val results = altitude.service.library.search(q)
    results.total shouldBe 6
    results.records.length shouldBe 2
    results.nonEmpty shouldBe true
    results.totalPages shouldBe 3

    val q2 = new SearchQuery(rpp = 2, page = 2)
    val results2 = altitude.service.library.search(q2)
    results2.total shouldBe 6
    results2.records.length shouldBe 2
    results2.totalPages shouldBe 3

    val q3 = new SearchQuery(rpp = 2, page = 3)
    val results3 = altitude.service.library.search(q3)
    results3.total shouldBe 6
    results3.records.length shouldBe 2
    results3.totalPages shouldBe 3

    // page too far
    val q4 = new SearchQuery(rpp = 2, page = 4)
    val results4 = altitude.service.library.search(q4)
    results4.total shouldBe 0
    results4.records.length shouldBe 0
    results4.totalPages shouldBe 0

    val q5 = new SearchQuery(rpp = 6, page = 1)
    val results5 = altitude.service.library.search(q5)
    results5.total shouldBe 6
    results5.records.length shouldBe 6
    results5.totalPages shouldBe 1

    val q6 = new SearchQuery(rpp = 20, page = 1)
    val results6 = altitude.service.library.search(q6)
    results6.total shouldBe 6
    results6.records.length shouldBe 6
    results6.totalPages shouldBe 1
  }

  test("Create assets and search by metadata") {
    val field1 = altitude.service.metadata.addField(
      MetadataField(
        name = "field 1",
        fieldType = FieldType.KEYWORD))

    val field2 = altitude.service.metadata.addField(
      MetadataField(
        name = "field 2",
        fieldType = FieldType.NUMBER))

    val field3 = altitude.service.metadata.addField(
      MetadataField(
        name = "field 3",
        fieldType = FieldType.BOOL))

    var data = Map[String, Set[String]](
      field1.id.get -> Set("one", "two", "three"),
      field2.id.get -> Set("1", "2", "3.002", "14.1", "1.25", "123456789"),
      field3.id.get -> Set("true"))
    testContext.persistAsset(metadata = Metadata(data))

    data = Map[String, Set[String]](
      field1.id.get -> Set("six", "seven"),
      field2.id.get -> Set("5", "1001", "1"),
      field3.id.get -> Set("true"))
    testContext.persistAsset(metadata = Metadata(data))

    // simple value search
    var results = altitude.service.library.search(new SearchQuery(text = Some("one")))
    results.total shouldBe 1

    results = altitude.service.library.search(
      new SearchQuery(params = Map(
        field3.id.get -> Query.EQUALS(true),
        field2.id.get -> Query.EQUALS(1)))
    )
    results.total shouldBe 2
  }

  /**
    * What happens if we have a number field and search by integer?
    */
  test("Search by wrong field type") {
    val field1 = altitude.service.metadata.addField(
      MetadataField(
        name = "field 1",
        fieldType = FieldType.KEYWORD))

    val field2 = altitude.service.metadata.addField(
      MetadataField(
        name = "field 2",
        fieldType = FieldType.NUMBER))

    val data = Map[String, Set[String]](
      field1.id.get -> Set("one"),
      field2.id.get -> Set("1")
    )
    testContext.persistAsset(metadata = Metadata(data))

   val results = altitude.service.library.search(
      new SearchQuery(params = Map(
        field1.id.get -> Query.EQUALS(1)))
    )
    results.total shouldBe 0
  }

  test("Parametarized search") {
    val field1 = altitude.service.metadata.addField(
      MetadataField(
        name = "field 1",
        fieldType = FieldType.KEYWORD))

    val field2 = altitude.service.metadata.addField(
      MetadataField(
        name = "field 2",
        fieldType = FieldType.NUMBER))

    val asset1: Asset = testContext.persistAsset()
    val asset2: Asset = testContext.persistAsset()
    val asset3: Asset = testContext.persistAsset()

    altitude.service.library.addMetadataValue(asset1.id.get, fieldId = field1.id.get, newValue = "one")
    altitude.service.library.addMetadataValue(asset2.id.get, fieldId = field1.id.get, newValue = "one")
    altitude.service.library.addMetadataValue(asset3.id.get, fieldId = field1.id.get, newValue = "two")

    altitude.service.library.addMetadataValue(asset1.id.get, fieldId = field2.id.get, newValue = 1)
    altitude.service.library.addMetadataValue(asset2.id.get, fieldId = field2.id.get, newValue = 1)
    altitude.service.library.addMetadataValue(asset3.id.get, fieldId = field2.id.get, newValue = 2)

    val results = altitude.service.library.search(
      new SearchQuery(
        params = Map(
          field1.id.get -> Query.EQUALS("one"),
          field2.id.get -> Query.EQUALS(1)
        )
      )
    )
    results.total shouldBe 2
  }

  test("Updating and removing metadata values updates search index") {
    val field1 = altitude.service.metadata.addField(
      MetadataField(
        name = "field 1",
        fieldType = FieldType.KEYWORD))

    val field2 = altitude.service.metadata.addField(
      MetadataField(
        name = "field 2",
        fieldType = FieldType.NUMBER))

    val asset1: Asset = testContext.persistAsset()

    altitude.service.library.addMetadataValue(asset1.id.get, fieldId = field1.id.get, newValue = "one")
    // it's the only value for this field so get it
    val metadata: Metadata = altitude.service.metadata.getMetadata(asset1.id.get)
    val mdVal = metadata(field1.id.get).head

    // tag a second field for posterity
    altitude.service.library.addMetadataValue(asset1.id.get, fieldId = field2.id.get, newValue = 3)

    var results = altitude.service.library.search(new SearchQuery(text = Some("one")))
    results.total shouldBe 1

    // parametarized search
    results = altitude.service.library.search(
      new SearchQuery(
        params = Map(
          field1.id.get -> "one",
          field2.id.get -> 3
        )
      )
    )
    results.records.length shouldBe 1
    results.total shouldBe 1

    // update the value and search again
    altitude.service.library.updateMetadataValue(asset1.id.get, mdVal.id.get, "newone")
    results = altitude.service.library.search(new SearchQuery(text = Some("newone")))
    results.total shouldBe 1

    // parametarized search
    results = altitude.service.library.search(
      new SearchQuery(
        params = Map(
          field1.id.get -> "newone",
          field2.id.get -> 3
        )
      )
    )
    results.records.length shouldBe 1
    results.total shouldBe 1

    // remove the value and search again
    altitude.service.library.deleteMetadataValue(assetId = asset1.id.get, valueId = mdVal.id.get)

    results = altitude.service.library.search(new SearchQuery(text = Some("one")))
    results.isEmpty shouldBe true
  }

  test("Can sort in ASC and DESC order on a user meta field") {
    val kwField = altitude.service.metadata.addField(
      MetadataField(
        name = "keyword field",
        fieldType = FieldType.KEYWORD))
    val numField = altitude.service.metadata.addField(
      MetadataField(
        name = "number field",
        fieldType = FieldType.NUMBER))
    val boolField = altitude.service.metadata.addField(
      MetadataField(
        name = "boolean field",
        fieldType = FieldType.BOOL))

    val asset1: Asset = testContext.persistAsset()

    val asset2: Asset = testContext.persistAsset()

    val asset3: Asset = testContext.persistAsset()

    altitude.service.library.addMetadataValue(asset1.id.get, fieldId = kwField.id.get, newValue = "c")
    altitude.service.library.addMetadataValue(asset2.id.get, fieldId = kwField.id.get, newValue = "a")
    altitude.service.library.addMetadataValue(asset3.id.get, fieldId = kwField.id.get, newValue = "b")

    altitude.service.library.addMetadataValue(asset1.id.get, fieldId = numField.id.get, newValue = 50)
    altitude.service.library.addMetadataValue(asset2.id.get, fieldId = numField.id.get, newValue = 300)
    altitude.service.library.addMetadataValue(asset3.id.get, fieldId = numField.id.get, newValue = 200)

    altitude.service.library.addMetadataValue(asset1.id.get, fieldId = boolField.id.get, newValue = false)
    altitude.service.library.addMetadataValue(asset2.id.get, fieldId = boolField.id.get, newValue = true)
    altitude.service.library.addMetadataValue(asset3.id.get, fieldId = boolField.id.get, newValue = false)

    // sort by string field
    var sort = SearchSort(field = kwField, direction = SortDirection.ASC)
    var results = altitude.service.library.search(new SearchQuery(searchSort = List(sort)))
    (results.records.head: Asset).metadata.get(kwField.id.get).value.head.value shouldBe "a"

    sort = SearchSort(field = kwField, direction = SortDirection.DESC)
    results = altitude.service.library.search(new SearchQuery(searchSort = List(sort)))
    (results.records.head: Asset).metadata.get(kwField.id.get).value.head.value shouldBe "c"

    // sort by number field
    sort = SearchSort(field = numField, direction = SortDirection.ASC)
    results = altitude.service.library.search(new SearchQuery(searchSort = List(sort)))
    (results.records.head: Asset).metadata.get(numField.id.get).value.head.value shouldBe "50"

    sort = SearchSort(field = numField, direction = SortDirection.DESC)
    results = altitude.service.library.search(new SearchQuery(searchSort = List(sort)))
    (results.records.head: Asset).metadata.get(numField.id.get).value.head.value shouldBe "300"

    // sort by number field
    sort = SearchSort(field = boolField, direction = SortDirection.ASC)
    results = altitude.service.library.search(new SearchQuery(searchSort = List(sort)))
    (results.records.head: Asset).metadata.get(boolField.id.get).value.head.value shouldBe "false"

    sort = SearchSort(field = boolField, direction = SortDirection.DESC)
    results = altitude.service.library.search(new SearchQuery(searchSort = List(sort)))
    (results.records.head: Asset).metadata.get(boolField.id.get).value.head.value shouldBe "true"
  }

  test("Sort info should be returned with query results") {
    val kwField = altitude.service.metadata.addField(
      MetadataField(
        name = "keyword field",
        fieldType = FieldType.NUMBER
      ))

    1 to 5 foreach { idx =>
      val asset: Asset = testContext.persistAsset()
      altitude.service.library.addMetadataValue(asset.id.get, fieldId = kwField.id.get, newValue = idx)
    }

    // try with no sort info at all
    var results = altitude.service.library.search(new SearchQuery())

    results.sort shouldBe empty

    val sort = SearchSort(field = kwField, direction = SortDirection.ASC)
    results = altitude.service.library.search(new SearchQuery(searchSort = List(sort)))
    results.sort shouldNot be(empty)
    results.sort.head.direction shouldBe SortDirection.ASC
    results.sort.head.field.name shouldBe kwField.name
  }

}
