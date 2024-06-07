package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.contain
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.DuplicateException
import software.altitude.core.NotFoundException
import software.altitude.core.Util
import software.altitude.core.ValidationException
import software.altitude.core.models._


@DoNotDiscover class MetadataServiceTests(val config: Map[String, Any]) extends IntegrationTestCore {

  test("Number field type can be added") {
    val field = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.NUMBER))
    val asset: Asset = altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))

    var data = Map[String, Set[String]](field.id.get -> Set("one"))
    intercept[ValidationException] {
      altitude.service.metadata.setMetadata(asset.id.get, Metadata(data))
    }

    data = Map[String, Set[String]](field.id.get -> Set("."))
    intercept[ValidationException] {
      altitude.service.metadata.setMetadata(asset.id.get, Metadata(data))
    }

    // these should be ok
    data = Map[String, Set[String]](
      field.id.get -> Set("000.", "0", "", "0000.00123", ".000", "36352424", "234324221"))
  }

  test("Boolean field type can be added") {
    val field = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.BOOL))
    val asset: Asset = altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))

    var data = Map[String, Set[String]](field.id.get -> Set("one"))
    intercept[ValidationException] {
      altitude.service.metadata.setMetadata(asset.id.get, Metadata(data))
    }

    data = Map[String, Set[String]](field.id.get -> Set("on"))
    intercept[ValidationException] {
      altitude.service.metadata.setMetadata(asset.id.get, Metadata(data))
    }

    // cannot have conflicting boolean values
    data = Map[String, Set[String]](field.id.get -> Set("TRUE", "FALSE"))
    intercept[ValidationException] {
      altitude.service.metadata.setMetadata(asset.id.get, Metadata(data))
    }
    // ... but non-conflicting duplicates are ok
    data = Map[String, Set[String]](field.id.get -> Set("TRUE", "TRUE"))

    // these should be ok
    data = Map[String, Set[String]](field.id.get -> Set("TRUE"))
    altitude.service.metadata.setMetadata(asset.id.get, Metadata(data))

    data = Map[String, Set[String]](field.id.get -> Set("FALSE"))
    altitude.service.metadata.setMetadata(asset.id.get, Metadata(data))

    data = Map[String, Set[String]](field.id.get -> Set("true"))
    altitude.service.metadata.setMetadata(asset.id.get, Metadata(data))

    data = Map[String, Set[String]](field.id.get -> Set("False"))
    altitude.service.metadata.setMetadata(asset.id.get, Metadata(data))

    data = Map[String, Set[String]](field.id.get -> Set("False"))
    altitude.service.metadata.setMetadata(asset.id.get, Metadata(data))
  }

  test("Setting metadata values") {
    val keywordMetadataField = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val numberMetadataField = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.NUMBER))

    val asset: Asset = altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))

    // add a field we do not expect
    val badData = Map[String, Set[String]](
        keywordMetadataField.id.get -> Set("one", "two", "three"),
        BaseModel.genId -> Set("four"))

    intercept[NotFoundException] {
        altitude.service.metadata.setMetadata(asset.id.get, Metadata(badData))
      }

    // valid
    val data = Map[String, Set[String]](
        keywordMetadataField.id.get -> Set("one", "two", "three"),
        numberMetadataField.id.get -> Set("1", "2", "3.002", "14.1", "1.25", "123456789"))

    altitude.service.metadata.setMetadata(asset.id.get, Metadata(data))

    val storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)

    storedMetadata.data should not be empty
    storedMetadata.data.keys should contain(keywordMetadataField.id.get)
    storedMetadata.data.keys should contain(numberMetadataField.id.get)
  }

  test("Test/update empty value sets") {
    val field1 = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val field2 = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.NUMBER))

    val asset: Asset = altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))

    var data = Map[String, Set[String]](
      field1.id.get -> Set("one", "two", "three"),
      field2.id.get -> Set())

    altitude.service.metadata.setMetadata(asset.id.get, Metadata(data))

    var storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)
    storedMetadata.data.keys should contain(field1.id.get)
    storedMetadata.data.keys shouldNot contain(field2.id.get)

    // update with nothing
    data = Map[String, Set[String]](field1.id.get -> Set())

    altitude.service.metadata.updateMetadata(asset.id.get, Metadata(data))

    storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)
    storedMetadata.data shouldBe empty
  }

  test("Update metadata values") {
    val field1 = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val field2 = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.NUMBER))

    val asset: Asset = altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))

    var data = Map[String, Set[String]](
        field1.id.get -> Set("one", "two", "three"),
        field2.id.get -> Set("1", "2", "3.002", "14.1", "1.25", "123456789"))

    altitude.service.metadata.setMetadata(asset.id.get, Metadata(data))

    var storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)
    storedMetadata.data.keys should contain(field1.id.get)
    storedMetadata.data.keys should contain(field2.id.get)

    val field3 = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    data = Map[String, Set[String]](
        field3.id.get -> Set("test 1", "test 2"),
        field2.id.get -> Set("3.002", "14.1", "1.25", "123456789"))

    altitude.service.metadata.updateMetadata(asset.id.get, Metadata(data))

    storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)
    storedMetadata.data.keys should contain(field1.id.get)
    storedMetadata.data.keys should contain(field2.id.get)
    storedMetadata.data.keys should contain(field3.id.get)

    storedMetadata.data(field2.id.get) shouldNot contain("1")
    storedMetadata.data(field2.id.get) shouldNot contain("2")
  }

  test("Add/get fields") {
    val metadataField = altitude.service.metadata.addField(
      MetadataField(name = "field name", fieldType = FieldType.KEYWORD))

    val storedField: MetadataField = altitude.service.metadata.getFieldById(metadataField.id.get)
    storedField.fieldType shouldBe FieldType.KEYWORD
  }

  test("Delete metadata field") {
    val metadataField = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    altitude.service.metadata.getFieldById(metadataField.id.get)

    altitude.service.metadata.deleteFieldById(metadataField.id.get)

    intercept[NotFoundException] {
      altitude.service.metadata.getFieldById(metadataField.id.get)
    }
  }

  test("Get all fields for a repo") {
    altitude.service.metadata.addField(
      MetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))
    altitude.service.metadata.addField(
      MetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    SET_SECOND_USER()
    altitude.service.metadata.addField(
      MetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    SET_FIRST_USER()
    altitude.service.metadata.getAllFields.size shouldBe 3

    SET_SECOND_USER()
    altitude.service.metadata.getAllFields.size shouldBe 3
  }

  test("Adding a duplicate-named field should not succeed") {
    val fieldName = "field name"
    altitude.service.metadata.addField(
      MetadataField(name = fieldName, fieldType = FieldType.KEYWORD))

    intercept[DuplicateException] {
          altitude.service.metadata.addField(
            MetadataField(name = fieldName, fieldType = FieldType.KEYWORD))
        }
  }

  test("Sanitizing field names") {
    val metadataField = altitude.service.metadata.addField(
      MetadataField(name = " new field\n ", fieldType = FieldType.NUMBER))

    metadataField.name shouldBe "new field"
  }

  test("Failing validation cases") {
    intercept[ValidationException] {
          altitude.service.metadata.addField(
            MetadataField(name = "  ", fieldType = FieldType.KEYWORD))
    }

    intercept[ValidationException] {
          altitude.service.metadata.addField(
            MetadataField(name = "\t\n\r   ", fieldType = FieldType.KEYWORD))
    }

    intercept[ValidationException] {
          altitude.service.metadata.addField(
            MetadataField(name = "", fieldType = FieldType.KEYWORD))
    }
  }

  test("Metadata added initially should be present") {
    val field = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val data = Map[String, Set[String]](field.id.get -> Set("one", "two"))
    val metadata = Metadata(data)

    val asset: Asset = altitude.service.library.add(
      makeAsset(altitude.service.folder.triageFolder, metadata = metadata))

    val storedAsset: Asset = altitude.service.library.getById(asset.id.get)

    storedAsset.metadata.isEmpty shouldBe false
  }

  test("Not defined user metadata values should not return") {
    altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.NUMBER))

    val field3 = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.TEXT))


    val data = Map[String, Set[String]](field3.id.get -> Set("this is some text"))
    val metadata = Metadata(data)

    val asset: Asset = altitude.service.library.add(
      makeAsset(altitude.service.folder.triageFolder, metadata = metadata))

    val storedAsset: Asset = altitude.service.library.getById(asset.id.get)

    storedAsset.metadata.isEmpty shouldBe false
    storedAsset.metadata.data.size shouldBe 1
  }

  test("Delete metadata value") {
    val field = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val data = Map[String, Set[String]](field.id.get -> Set("1", "2", "3"))
    val metadata = Metadata(data)

    val asset: Asset = altitude.service.library.add(
      makeAsset(altitude.service.folder.triageFolder, metadata = metadata))

    var storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)
    storedMetadata.get(field.id.get).value.size shouldBe 3
    val values: List[MetadataValue] = storedMetadata.get(field.id.get).value.toList

    altitude.service.library.deleteMetadataValue(asset.id.get, values.head.id.get)
    altitude.service.library.deleteMetadataValue(asset.id.get, values.last.id.get)

    storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)

    storedMetadata.get(field.id.get).value.size shouldBe 1
  }

  test("Metadata IDs should be created and not overwritten") {
    val field1 = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val field2 = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.NUMBER))

    var asset: Asset = altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))

    val data = Map[String, Set[String]](field1.id.get -> Set("1"))
    val metadata = Metadata(data)

    altitude.service.metadata.setMetadata(asset.id.get, metadata)

    var storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)
    storedMetadata.get(field1.id.get) should not be None

    val field_1_valueId = storedMetadata.get(field1.id.get).get.head.id
    field_1_valueId should not be None

    altitude.service.library.addMetadataValue(asset.id.get, field2.id.get, "2")

    storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)

    storedMetadata.get(field1.id.get).get.head.id should not be None
    storedMetadata.get(field1.id.get).get.head.id shouldBe field_1_valueId


    // now set the metadata on asset creation and make sure the auto-generated IDs are there
    asset = altitude.service.library.add(
      makeAsset(altitude.service.folder.triageFolder, metadata = metadata))

    storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)
    storedMetadata.get(field1.id.get).get.head.id should not be None
  }

  test("Adding empty keyword value should be explicitly not allowed") {
    val _metadataField = MetadataField(
      name = Util.randomStr(),
      fieldType = FieldType.KEYWORD
    )

    val metadataField = altitude.service.metadata.addField(_metadataField)
    val asset: Asset = altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))

    intercept[ValidationException] {
      altitude.service.library.addMetadataValue(asset.id.get, metadataField.id.get, "")
    }

    intercept[ValidationException] {
      altitude.service.library.addMetadataValue(asset.id.get, metadataField.id.get, "   ")
    }

    intercept[ValidationException] {
      altitude.service.library.addMetadataValue(asset.id.get, metadataField.id.get, "  \t \n ")
    }
  }

  test("Boolean values should replace each other with no errors") {
    val _metadataField = MetadataField(
      name = Util.randomStr(),
      fieldType = FieldType.BOOL
    )

    val metadataField = altitude.service.metadata.addField(_metadataField)
    val asset: Asset = altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))

    altitude.service.library.addMetadataValue(asset.id.get, metadataField.id.get, true)
    altitude.service.library.addMetadataValue(asset.id.get, metadataField.id.get, true)
    altitude.service.library.addMetadataValue(asset.id.get, metadataField.id.get, false)

    val metadata = altitude.service.metadata.getMetadata(asset.id.get)
    metadata.get(metadataField.id.get).get.size shouldBe 1
  }


  test("Text fields cannot be blank") {
    val field = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.TEXT))

    val asset: Asset = altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))

    val data = Map[String, Set[String]](field.id.get -> Set("\n\n\r"))

    intercept[ValidationException] {
      altitude.service.library.addMetadataValue(asset.id.value, field.id.value, "   ")
    }
  }

  test("Update value by ID") {
    val field = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.TEXT))

    val asset: Asset = altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))

    val data = Map[String, Set[String]](field.id.get -> Set("Some text"))
    val metadata = Metadata(data)

    altitude.service.metadata.setMetadata(asset.id.get, metadata)

    var storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)
    var storedValue = storedMetadata.get(field.id.get).get.head
    val oldValueId = storedValue.id

    val newValue = "Some updated text"

    altitude.service.library.updateMetadataValue(asset.id.get, storedValue.id.get, newValue)

    storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)
    storedValue = storedMetadata.get(field.id.get).get.head

    storedValue.id shouldBe oldValueId
    storedValue.value shouldBe newValue
  }

  test("Updating value by ID should work case-insensitively") {
    val field = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val asset: Asset = altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))

    val oldValue = "tag1"
    val data = Map[String, Set[String]](field.id.get -> Set(oldValue))
    val metadata = Metadata(data)

    altitude.service.metadata.setMetadata(asset.id.get, metadata)

    var storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)
    var storedValue = storedMetadata.get(field.id.get).get.head
    val oldValueId = storedValue.id

    val newValue = oldValue.toUpperCase

    altitude.service.library.updateMetadataValue(asset.id.get, storedValue.id.get, newValue)

    storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)
    storedValue = storedMetadata.get(field.id.get).get.head

    storedValue.id shouldBe oldValueId
    storedValue.value shouldBe newValue
  }

  test("Updating value by ID with the same value should not raise exceptions") {
    val field = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val asset: Asset = altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))

    val oldValue = "tag1"
    val data = Map[String, Set[String]](field.id.get -> Set(oldValue))
    val metadata = Metadata(data)

    altitude.service.metadata.setMetadata(asset.id.get, metadata)

    var storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)
    var storedValue = storedMetadata.get(field.id.get).get.head
    val oldValueId = storedValue.id

    altitude.service.library.updateMetadataValue(asset.id.get, storedValue.id.get, oldValue)

    storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)
    storedValue = storedMetadata.get(field.id.get).get.head

    storedValue.id shouldBe oldValueId
    storedValue.value shouldBe oldValue
  }

  test("Updating value by ID with empty value should raise") {
    val field = altitude.service.metadata.addField(
      MetadataField(
        name = Util.randomStr(),
        fieldType = FieldType.KEYWORD))

    val asset: Asset = altitude.service.library.add(makeAsset(altitude.service.folder.triageFolder))

    val oldValue = "tag1"
    val data = Map[String, Set[String]](field.id.get -> Set(oldValue))
    val metadata = Metadata(data)

    altitude.service.metadata.setMetadata(asset.id.get, metadata)

    var storedMetadata = altitude.service.metadata.getMetadata(asset.id.get)
    var storedValue = storedMetadata.get(field.id.get).get.head
    val oldValueId = storedValue.id

    intercept[ValidationException] {
      altitude.service.library.updateMetadataValue(asset.id.get, storedValue.id.get, "  \t  ")
    }
  }
}
