package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.contain
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Altitude
import software.altitude.core.DuplicateException
import software.altitude.core.NotFoundException
import software.altitude.core.ValidationException
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models._
import software.altitude.core.util.Util
import software.altitude.test.core.IntegrationTestCore


@DoNotDiscover class MetadataServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Number field type can be added") {
    val field = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.NUMBER))
    val asset: Asset = testContext.persistAsset()

    var data = Map[String, Set[String]](field.persistedId -> Set("one"))
    intercept[ValidationException] {
      testApp.service.metadata.setMetadata(asset.persistedId, Metadata(data))
    }

    data = Map[String, Set[String]](field.persistedId -> Set("."))
    intercept[ValidationException] {
      testApp.service.metadata.setMetadata(asset.persistedId, Metadata(data))
    }

    // these should be ok
    data = Map[String, Set[String]](
      field.persistedId -> Set("000.", "0", "", "0000.00123", ".000", "36352424", "234324221"))
  }

  test("Boolean field type can be added") {
    val field = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.BOOL))
    val asset: Asset = testContext.persistAsset()

    var data = Map[String, Set[String]](field.persistedId -> Set("one"))
    intercept[ValidationException] {
      testApp.service.metadata.setMetadata(asset.persistedId, Metadata(data))
    }

    data = Map[String, Set[String]](field.persistedId -> Set("on"))
    intercept[ValidationException] {
      testApp.service.metadata.setMetadata(asset.persistedId, Metadata(data))
    }

    // cannot have conflicting boolean values
    data = Map[String, Set[String]](field.persistedId -> Set("TRUE", "FALSE"))
    intercept[ValidationException] {
      testApp.service.metadata.setMetadata(asset.persistedId, Metadata(data))
    }
    // ... but non-conflicting duplicates are ok
    data = Map[String, Set[String]](field.persistedId -> Set("TRUE", "TRUE"))

    // these should be ok
    data = Map[String, Set[String]](field.persistedId -> Set("TRUE"))
    testApp.service.metadata.setMetadata(asset.persistedId, Metadata(data))

    data = Map[String, Set[String]](field.persistedId -> Set("FALSE"))
    testApp.service.metadata.setMetadata(asset.persistedId, Metadata(data))

    data = Map[String, Set[String]](field.persistedId -> Set("true"))
    testApp.service.metadata.setMetadata(asset.persistedId, Metadata(data))

    data = Map[String, Set[String]](field.persistedId -> Set("False"))
    testApp.service.metadata.setMetadata(asset.persistedId, Metadata(data))

    data = Map[String, Set[String]](field.persistedId -> Set("False"))
    testApp.service.metadata.setMetadata(asset.persistedId, Metadata(data))
  }

  test("Setting metadata values") {
    val keywordMetadataField = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val numberMetadataField = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.NUMBER))

    val asset: Asset = testContext.persistAsset()

    // add a field we do not expect
    val badData = Map[String, Set[String]](
        keywordMetadataField.persistedId -> Set("one", "two", "three"),
        BaseDao.genId -> Set("four"))

    intercept[NotFoundException] {
        testApp.service.metadata.setMetadata(asset.persistedId, Metadata(badData))
      }

    // valid
    val data = Map[String, Set[String]](
        keywordMetadataField.persistedId -> Set("one", "two", "three"),
        numberMetadataField.persistedId -> Set("1", "2", "3.002", "14.1", "1.25", "123456789"))

    testApp.service.metadata.setMetadata(asset.persistedId, Metadata(data))

    val storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)

    storedMetadata.data should not be empty
    storedMetadata.data.keys should contain(keywordMetadataField.persistedId)
    storedMetadata.data.keys should contain(numberMetadataField.persistedId)
  }

  test("Test/update empty value sets") {
    val field1 = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.NUMBER))

    val asset: Asset = testContext.persistAsset()

    var data = Map[String, Set[String]](
      field1.persistedId -> Set("one", "two", "three"),
      field2.persistedId -> Set())

    testApp.service.metadata.setMetadata(asset.persistedId, Metadata(data))

    var storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedMetadata.data.keys should contain(field1.persistedId)
    storedMetadata.data.keys shouldNot contain(field2.persistedId)

    // update with nothing
    data = Map[String, Set[String]](field1.persistedId -> Set())

    testApp.service.metadata.updateMetadata(asset.persistedId, Metadata(data))

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedMetadata.data shouldBe empty
  }

  test("Update metadata values") {
    val field1 = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.NUMBER))

    val asset: Asset = testContext.persistAsset()

    var data = Map[String, Set[String]](
        field1.persistedId -> Set("one", "two", "three"),
        field2.persistedId -> Set("1", "2", "3.002", "14.1", "1.25", "123456789"))

    testApp.service.metadata.setMetadata(asset.persistedId, Metadata(data))

    var storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedMetadata.data.keys should contain(field1.persistedId)
    storedMetadata.data.keys should contain(field2.persistedId)

    val field3 = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    data = Map[String, Set[String]](
        field3.persistedId -> Set("test 1", "test 2"),
        field2.persistedId -> Set("3.002", "14.1", "1.25", "123456789"))

    testApp.service.metadata.updateMetadata(asset.persistedId, Metadata(data))

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedMetadata.data.keys should contain(field1.persistedId)
    storedMetadata.data.keys should contain(field2.persistedId)
    storedMetadata.data.keys should contain(field3.persistedId)

    storedMetadata.data(field2.persistedId) shouldNot contain("1")
    storedMetadata.data(field2.persistedId) shouldNot contain("2")
  }

  test("Add/get fields") {
    val metadataField = testApp.service.metadata.addField(
      MetadataField(name = "field name", fieldType = FieldType.KEYWORD))

    val storedField: MetadataField = testApp.service.metadata.getFieldById(metadataField.persistedId)
    storedField.fieldType shouldBe FieldType.KEYWORD
  }

  test("Delete metadata field") {
    val metadataField = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    testApp.service.metadata.getFieldById(metadataField.persistedId)

    testApp.service.metadata.deleteFieldById(metadataField.persistedId)

    intercept[NotFoundException] {
      testApp.service.metadata.getFieldById(metadataField.persistedId)
    }
  }

  test("Get all fields for a repo") {
    testApp.service.metadata.addField(
      MetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))
    testApp.service.metadata.addField(
      MetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    // SECOND USER
    val user2 = testContext.persistUser()
    switchContextUser(user2)

    testApp.service.metadata.addField(
      MetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    // FIRST USER
    switchContextUser(testContext.users.head)
    testApp.service.metadata.getAllFields.size shouldBe 3

    // THIRD USER
    val user3 = testContext.persistUser()
    switchContextUser(user3)
    testApp.service.metadata.getAllFields.size shouldBe 3
  }

  test("Adding a duplicate-named field should not succeed") {
    val fieldName = "field name"
    testApp.service.metadata.addField(
      MetadataField(name = fieldName, fieldType = FieldType.KEYWORD))

    intercept[DuplicateException] {
          testApp.service.metadata.addField(
            MetadataField(name = fieldName, fieldType = FieldType.KEYWORD))
        }
  }

  test("Metadata added initially should be present") {
    val field = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val data = Map[String, Set[String]](field.persistedId -> Set("one", "two"))
    val metadata = Metadata(data)

    val asset: Asset = testContext.persistAsset(metadata = metadata)

    val storedAsset: Asset = testApp.service.library.getById(asset.persistedId)

    storedAsset.metadata.isEmpty shouldBe false
  }

  test("Not defined user metadata values should not return") {
    testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.NUMBER))

    val field3 = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.TEXT))


    val data = Map[String, Set[String]](field3.persistedId -> Set("this is some text"))
    val metadata = Metadata(data)

    val asset: Asset = testContext.persistAsset(metadata = metadata)

    val storedAsset: Asset = testApp.service.library.getById(asset.persistedId)

    storedAsset.metadata.isEmpty shouldBe false
    storedAsset.metadata.data.size shouldBe 1
  }

  test("Delete metadata value") {
    val field = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val data = Map[String, Set[String]](field.persistedId -> Set("1", "2", "3"))
    val metadata = Metadata(data)

    val asset: Asset = testContext.persistAsset(metadata = metadata)

    var storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedMetadata.get(field.persistedId).value.size shouldBe 3
    val values: List[MetadataValue] = storedMetadata.get(field.persistedId).value.toList

    testApp.service.library.deleteMetadataValue(asset.persistedId, values.head.persistedId)
    testApp.service.library.deleteMetadataValue(asset.persistedId, values.last.persistedId)

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)

    storedMetadata.get(field.persistedId).value.size shouldBe 1
  }

  test("Metadata IDs should be created and not overwritten") {
    val field1 = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.NUMBER))

    var asset: Asset = testContext.persistAsset()

    val data = Map[String, Set[String]](field1.persistedId -> Set("1"))
    val metadata = Metadata(data)

    testApp.service.metadata.setMetadata(asset.persistedId, metadata)

    var storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedMetadata.get(field1.persistedId) should not be None

    val field_1_valueId = storedMetadata.get(field1.persistedId).get.head.id
    field_1_valueId should not be None

    testApp.service.library.addMetadataValue(asset.persistedId, field2.persistedId, "2")

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)

    storedMetadata.get(field1.persistedId).get.head.id should not be None
    storedMetadata.get(field1.persistedId).get.head.id shouldBe field_1_valueId


    // now set the metadata on asset creation and make sure the auto-generated IDs are there
    asset = testContext.persistAsset(metadata = metadata)

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedMetadata.get(field1.persistedId).get.head.id should not be None
  }

  test("Adding empty keyword value should be explicitly not allowed") {
    val _metadataField = MetadataField(
      name = Util.randomStr(),
      fieldType = FieldType.KEYWORD
    )

    val metadataField = testApp.service.metadata.addField(_metadataField)
    val asset: Asset = testContext.persistAsset()

    intercept[ValidationException] {
      testApp.service.library.addMetadataValue(asset.persistedId, metadataField.persistedId, "")
    }

    intercept[ValidationException] {
      testApp.service.library.addMetadataValue(asset.persistedId, metadataField.persistedId, "   ")
    }

    intercept[ValidationException] {
      testApp.service.library.addMetadataValue(asset.persistedId, metadataField.persistedId, "  \t \n ")
    }
  }

  test("Boolean values should replace each other with no errors") {
    val _metadataField = MetadataField(
      name = Util.randomStr(),
      fieldType = FieldType.BOOL
    )

    val metadataField = testApp.service.metadata.addField(_metadataField)
    val asset: Asset = testContext.persistAsset()

    testApp.service.library.addMetadataValue(asset.persistedId, metadataField.persistedId, true)
    testApp.service.library.addMetadataValue(asset.persistedId, metadataField.persistedId, true)
    testApp.service.library.addMetadataValue(asset.persistedId, metadataField.persistedId, false)

    val metadata = testApp.service.metadata.getMetadata(asset.persistedId)
    metadata.get(metadataField.persistedId).get.size shouldBe 1
  }


  test("Text fields cannot be blank") {
    val field = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.TEXT))

    val asset: Asset = testContext.persistAsset()

    intercept[ValidationException] {
      testApp.service.library.addMetadataValue(asset.id.value, field.id.value, "   ")
    }
  }

  test("Update value by ID") {
    val field = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.TEXT))

    val asset: Asset = testContext.persistAsset()

    val data = Map[String, Set[String]](field.persistedId -> Set("Some text"))
    val metadata = Metadata(data)

    testApp.service.metadata.setMetadata(asset.persistedId, metadata)

    var storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    var storedValue = storedMetadata.get(field.persistedId).get.head
    val oldValueId = storedValue.id

    val newValue = "Some updated text"

    testApp.service.library.updateMetadataValue(asset.persistedId, storedValue.persistedId, newValue)

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedValue = storedMetadata.get(field.persistedId).get.head

    storedValue.id shouldBe oldValueId
    storedValue.value shouldBe newValue
  }

  test("Updating value by ID should work case-insensitively") {
    val field = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val asset: Asset = testContext.persistAsset()

    val oldValue = "tag1"
    val data = Map[String, Set[String]](field.persistedId -> Set(oldValue))
    val metadata = Metadata(data)

    testApp.service.metadata.setMetadata(asset.persistedId, metadata)

    var storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    var storedValue = storedMetadata.get(field.persistedId).get.head
    val oldValueId = storedValue.id

    val newValue = oldValue.toUpperCase

    testApp.service.library.updateMetadataValue(asset.persistedId, storedValue.persistedId, newValue)

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedValue = storedMetadata.get(field.persistedId).get.head

    storedValue.id shouldBe oldValueId
    storedValue.value shouldBe newValue
  }

  test("Updating value by ID with the same value should not raise exceptions") {
    val field = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val asset: Asset = testContext.persistAsset()

    val oldValue = "tag1"
    val data = Map[String, Set[String]](field.persistedId -> Set(oldValue))
    val metadata = Metadata(data)

    testApp.service.metadata.setMetadata(asset.persistedId, metadata)

    var storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    var storedValue = storedMetadata.get(field.persistedId).get.head
    val oldValueId = storedValue.id

    testApp.service.library.updateMetadataValue(asset.persistedId, storedValue.persistedId, oldValue)

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedValue = storedMetadata.get(field.persistedId).get.head

    storedValue.id shouldBe oldValueId
    storedValue.value shouldBe oldValue
  }

  test("Updating value by ID with empty value should raise") {
    val field = testApp.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val asset: Asset = testContext.persistAsset()

    val oldValue = "tag1"
    val data = Map[String, Set[String]](field.persistedId -> Set(oldValue))
    val metadata = Metadata(data)

    testApp.service.metadata.setMetadata(asset.persistedId, metadata)

    val storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    val storedValue = storedMetadata.get(field.persistedId).get.head

    intercept[ValidationException] {
      testApp.service.library.updateMetadataValue(asset.persistedId, storedValue.persistedId, "  \t  ")
    }
  }
}
