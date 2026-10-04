package altitude.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.contain
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.{ should, shouldBe, shouldNot }

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.*
import altitude.core.util.Util

@DoNotDiscover class UserMetadataServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("A NUMBER field accepts only numeric values") {

    /**
     * Setup:
     *
     * A NUMBER user metadata field and one asset.
     *
     * Assertions:
     *
     * Setting the field to a value that is not a number, "one" or a lone ".", fails validation, while numbers in a variety of
     * spellings are accepted and stored as given.
     *
     * Edge cases:
     *
     * Leading and trailing dots and leading zeros; a blank value among them is dropped rather than refused.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.NUMBER))
    val asset: Asset = testContext.persistAsset()

    var data = Map[String, Set[String]](field.persistedId -> Set("one"))
    intercept[ValidationException] {
      testApp.service.metadata.setMetadata(asset.persistedId, UserMetadata(data))
    }

    data = Map[String, Set[String]](field.persistedId -> Set("."))
    intercept[ValidationException] {
      testApp.service.metadata.setMetadata(asset.persistedId, UserMetadata(data))
    }

    // these should be ok
    data = Map[String, Set[String]](field.persistedId -> Set("000.", "0", "", "0000.00123", ".000", "36352424", "234324221"))
    testApp.service.metadata.setMetadata(asset.persistedId, UserMetadata(data))

    // The blank value is dropped, the rest are stored as given
    testApp.service.metadata.getMetadata(asset.persistedId)(field.persistedId).map(_.value) shouldBe
      Set("000.", "0", "0000.00123", ".000", "36352424", "234324221")
  }

  test("A BOOL field accepts a single recognized boolean, in any letter case") {

    /**
     * Setup:
     *
     * A BOOL user metadata field and one asset.
     *
     * Assertions:
     *
     * Values that are not booleans ("one", "on") and conflicting values (TRUE and FALSE together) fail validation. Letter-case
     * variants of one boolean are a single value rather than a conflict, and every recognized spelling is stored as given.
     *
     * Edge cases:
     *
     * TRUE, FALSE, true, False, 1 and 0 are all recognized; TRUE and true together collapse into one value.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.BOOL))
    val asset: Asset = testContext.persistAsset()

    var data = Map[String, Set[String]](field.persistedId -> Set("one"))
    intercept[ValidationException] {
      testApp.service.metadata.setMetadata(asset.persistedId, UserMetadata(data))
    }

    data = Map[String, Set[String]](field.persistedId -> Set("on"))
    intercept[ValidationException] {
      testApp.service.metadata.setMetadata(asset.persistedId, UserMetadata(data))
    }

    // cannot have conflicting boolean values
    data = Map[String, Set[String]](field.persistedId -> Set("TRUE", "FALSE"))
    intercept[ValidationException] {
      testApp.service.metadata.setMetadata(asset.persistedId, UserMetadata(data))
    }
    // ... but letter-case variants of one value are non-conflicting duplicates, stored as one value
    data = Map[String, Set[String]](field.persistedId -> Set("TRUE", "true"))
    testApp.service.metadata.setMetadata(asset.persistedId, UserMetadata(data))
    testApp.service.metadata.getMetadata(asset.persistedId)(field.persistedId).map(_.value.toLowerCase) shouldBe Set("true")

    // every recognized spelling is ok, and stored as given
    Seq("TRUE", "FALSE", "true", "False", "1", "0").foreach {
      value =>
        data = Map[String, Set[String]](field.persistedId -> Set(value))
        testApp.service.metadata.setMetadata(asset.persistedId, UserMetadata(data))
        testApp.service.metadata.getMetadata(asset.persistedId)(field.persistedId).map(_.value) shouldBe Set(value)
    }
  }

  test("Setting metadata values") {

    /**
     * Setup:
     *
     * A KEYWORD field, a NUMBER field and one asset.
     *
     * Assertions:
     *
     * Metadata naming a field the repository does not have is refused as not found, even alongside a known field; valid keyword
     * and number values are stored and both fields read back.
     */
    val keywordMetadataField =
      testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    val numberMetadataField =
      testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.NUMBER))

    val asset: Asset = testContext.persistAsset()

    // add a field we do not expect
    val badData =
      Map[String, Set[String]](keywordMetadataField.persistedId -> Set("one", "two", "three"), BaseDao.genId -> Set("four"))

    intercept[NotFoundException] {
      testApp.service.metadata.setMetadata(asset.persistedId, UserMetadata(badData))
    }

    // valid
    val data = Map[String, Set[String]](
      keywordMetadataField.persistedId -> Set("one", "two", "three"),
      numberMetadataField.persistedId -> Set("1", "2", "3.002", "14.1", "1.25", "123456789"))

    testApp.service.metadata.setMetadata(asset.persistedId, UserMetadata(data))

    val storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)

    storedMetadata.data should not be empty
    storedMetadata.data.keys should contain(keywordMetadataField.persistedId)
    storedMetadata.data.keys should contain(numberMetadataField.persistedId)
  }

  test("Test/update empty value sets") {

    /**
     * Setup:
     *
     * A KEYWORD field and a NUMBER field set on one asset, the keyword to three values and the number to an empty set.
     *
     * Assertions:
     *
     * A field given no values is not stored, and updating the remaining field to no values removes it, leaving the asset with no
     * metadata.
     *
     * Edge cases:
     *
     * Empty value sets, both on set and on update.
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.NUMBER))

    val asset: Asset = testContext.persistAsset()

    var data = Map[String, Set[String]](field1.persistedId -> Set("one", "two", "three"), field2.persistedId -> Set())

    testApp.service.metadata.setMetadata(asset.persistedId, UserMetadata(data))

    var storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedMetadata.data.keys should contain(field1.persistedId)
    storedMetadata.data.keys shouldNot contain(field2.persistedId)

    // update with nothing
    data = Map[String, Set[String]](field1.persistedId -> Set())

    testApp.service.metadata.updateMetadata(asset.persistedId, UserMetadata(data))

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedMetadata.data shouldBe empty
  }

  test("Update metadata values") {

    /**
     * Setup:
     *
     * A KEYWORD field and a NUMBER field set on one asset, then a third, KEYWORD field added.
     *
     * Assertions:
     *
     * An update naming the number field and the new field keeps the keyword field it leaves out, adds the new field, and replaces
     * the number field's values, dropping the ones the update does not repeat.
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.NUMBER))

    val asset: Asset = testContext.persistAsset()

    var data = Map[String, Set[String]](
      field1.persistedId -> Set("one", "two", "three"),
      field2.persistedId -> Set("1", "2", "3.002", "14.1", "1.25", "123456789"))

    testApp.service.metadata.setMetadata(asset.persistedId, UserMetadata(data))

    var storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedMetadata.data.keys should contain(field1.persistedId)
    storedMetadata.data.keys should contain(field2.persistedId)

    val field3 = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    data = Map[String, Set[String]](
      field3.persistedId -> Set("test 1", "test 2"),
      field2.persistedId -> Set("3.002", "14.1", "1.25", "123456789"))

    testApp.service.metadata.updateMetadata(asset.persistedId, UserMetadata(data))

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedMetadata.data.keys should contain(field1.persistedId)
    storedMetadata.data.keys should contain(field2.persistedId)
    storedMetadata.data.keys should contain(field3.persistedId)

    storedMetadata.data(field2.persistedId) shouldNot contain("1")
    storedMetadata.data(field2.persistedId) shouldNot contain("2")
  }

  test("Add/get fields") {

    /**
     * Setup:
     *
     * A KEYWORD field named "field name".
     *
     * Assertions:
     *
     * The field reads back by ID with its type.
     */
    val metadataField = testApp.service.metadata.addField(UserMetadataField(name = "field name", fieldType = FieldType.KEYWORD))

    val storedField: UserMetadataField = testApp.service.metadata.getFieldById(metadataField.persistedId)
    storedField.fieldType shouldBe FieldType.KEYWORD
  }

  test("Delete metadata field") {

    /**
     * Setup:
     *
     * A KEYWORD field, read back once to show it exists.
     *
     * Assertions:
     *
     * After the field is deleted, reading it by ID fails as not found.
     */
    val metadataField =
      testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    testApp.service.metadata.getFieldById(metadataField.persistedId)

    testApp.service.metadata.deleteFieldById(metadataField.persistedId)

    intercept[NotFoundException] {
      testApp.service.metadata.getFieldById(metadataField.persistedId)
    }
  }

  test("Get all fields for a repo") {

    /**
     * Setup:
     *
     * Two fields added by the first user of the repository and one by a second user; then a third user who adds none.
     *
     * Assertions:
     *
     * Fields belong to the repository, not to the user who added them: the first and the third user both see all three.
     */
    testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))
    testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    // SECOND USER
    val user2 = testContext.persistUser()
    switchContextUser(user2)

    testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    // FIRST USER
    switchContextUser(testContext.users.head)
    testApp.service.metadata.getAllFields.size shouldBe 3

    // THIRD USER
    val user3 = testContext.persistUser()
    switchContextUser(user3)
    testApp.service.metadata.getAllFields.size shouldBe 3
  }

  test("Adding a duplicate-named field should not succeed") {

    /**
     * Setup:
     *
     * A KEYWORD field named "field name".
     *
     * Assertions:
     *
     * Adding a second field with the same name fails as a duplicate.
     */
    val fieldName = "field name"
    testApp.service.metadata.addField(UserMetadataField(name = fieldName, fieldType = FieldType.KEYWORD))

    intercept[DuplicateException] {
      testApp.service.metadata.addField(UserMetadataField(name = fieldName, fieldType = FieldType.KEYWORD))
    }
  }

  test("Metadata added initially should be present") {

    /**
     * Setup:
     *
     * A KEYWORD field, and an asset persisted with two values of it.
     *
     * Assertions:
     *
     * The asset reads back with its user metadata.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    val data = Map[String, Set[String]](field.persistedId -> Set("one", "two"))
    val metadata = UserMetadata(data)

    val asset: Asset = testContext.persistAsset(metadata = metadata)

    val storedAsset: Asset = testApp.service.asset.getById(asset.persistedId)

    storedAsset.userMetadata.isEmpty shouldBe false
  }

  test("Not defined user metadata values should not return") {

    /**
     * Setup:
     *
     * KEYWORD, NUMBER and TEXT fields, and an asset persisted with a value for the TEXT field only.
     *
     * Assertions:
     *
     * The asset's user metadata holds only the field that has a value, not the defined but unset ones.
     */
    testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.NUMBER))

    val field3 = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.TEXT))

    val data = Map[String, Set[String]](field3.persistedId -> Set("this is some text"))
    val metadata = UserMetadata(data)

    val asset: Asset = testContext.persistAsset(metadata = metadata)

    val storedAsset: Asset = testApp.service.asset.getById(asset.persistedId)

    storedAsset.userMetadata.isEmpty shouldBe false
    storedAsset.userMetadata.data.size shouldBe 1
  }

  test("Delete metadata value") {

    /**
     * Setup:
     *
     * A KEYWORD field, and an asset persisted with three values of it.
     *
     * Assertions:
     *
     * Deleting two of the values by ID leaves the field with the third.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    val data = Map[String, Set[String]](field.persistedId -> Set("1", "2", "3"))
    val metadata = UserMetadata(data)

    val asset: Asset = testContext.persistAsset(metadata = metadata)

    var storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedMetadata.get(field.persistedId).value.size shouldBe 3
    val values: List[UserMetadataValue] = storedMetadata.get(field.persistedId).value.toList

    testApp.service.metadata.deleteMetadataValue(asset.persistedId, values.head.persistedId)
    testApp.service.metadata.deleteMetadataValue(asset.persistedId, values.last.persistedId)

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)

    storedMetadata.get(field.persistedId).value.size shouldBe 1
  }

  test("Metadata IDs should be created and not overwritten") {

    /**
     * Setup:
     *
     * A KEYWORD field and a NUMBER field, an asset with one keyword value set, and a second asset persisted with the same
     * metadata.
     *
     * Assertions:
     *
     * A stored value gets an ID, adding a value of another field leaves that ID unchanged, and metadata given at asset creation
     * gets IDs too.
     */
    val field1 = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    val field2 = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.NUMBER))

    var asset: Asset = testContext.persistAsset()

    val data = Map[String, Set[String]](field1.persistedId -> Set("1"))
    val metadata = UserMetadata(data)

    testApp.service.metadata.setMetadata(asset.persistedId, metadata)

    var storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedMetadata.get(field1.persistedId) should not be None

    val field_1_valueId = storedMetadata.get(field1.persistedId).get.head.id
    field_1_valueId should not be None

    testApp.service.metadata.addMetadataValue(asset.persistedId, field2.persistedId, "2")

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)

    storedMetadata.get(field1.persistedId).get.head.id should not be None
    storedMetadata.get(field1.persistedId).get.head.id shouldBe field_1_valueId

    // now set the metadata on asset creation and make sure the auto-generated IDs are there
    asset = testContext.persistAsset(metadata = metadata)

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedMetadata.get(field1.persistedId).get.head.id should not be None
  }

  test("Adding empty keyword value should be explicitly not allowed") {

    /**
     * Setup:
     *
     * A KEYWORD field and one asset.
     *
     * Assertions:
     *
     * Adding a blank value fails validation.
     *
     * Edge cases:
     *
     * An empty string, spaces only, and a mix of spaces, a tab and a newline.
     */
    val _metadataField = UserMetadataField(
      name = Util.randomStr(),
      fieldType = FieldType.KEYWORD
    )

    val metadataField = testApp.service.metadata.addField(_metadataField)
    val asset: Asset = testContext.persistAsset()

    intercept[ValidationException] {
      testApp.service.metadata.addMetadataValue(asset.persistedId, metadataField.persistedId, "")
    }

    intercept[ValidationException] {
      testApp.service.metadata.addMetadataValue(asset.persistedId, metadataField.persistedId, "   ")
    }

    intercept[ValidationException] {
      testApp.service.metadata.addMetadataValue(asset.persistedId, metadataField.persistedId, "  \t \n ")
    }
  }

  test("Boolean values should replace each other with no errors") {

    /**
     * Setup:
     *
     * A BOOL field and one asset, given true, true again, then false.
     *
     * Assertions:
     *
     * Each value replaces the previous one without a duplicate error, so the field ends with the last value, false, alone.
     */
    val _metadataField = UserMetadataField(
      name = Util.randomStr(),
      fieldType = FieldType.BOOL
    )

    val metadataField = testApp.service.metadata.addField(_metadataField)
    val asset: Asset = testContext.persistAsset()

    testApp.service.metadata.addMetadataValue(asset.persistedId, metadataField.persistedId, true)
    testApp.service.metadata.addMetadataValue(asset.persistedId, metadataField.persistedId, true)
    testApp.service.metadata.addMetadataValue(asset.persistedId, metadataField.persistedId, false)

    val metadata = testApp.service.metadata.getMetadata(asset.persistedId)
    metadata.get(metadataField.persistedId).value.map(_.value) shouldBe Set("false")
  }

  test("Text fields cannot be blank") {

    /**
     * Setup:
     *
     * A TEXT field and one asset.
     *
     * Assertions:
     *
     * Adding a value of only spaces fails validation.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.TEXT))

    val asset: Asset = testContext.persistAsset()

    intercept[ValidationException] {
      testApp.service.metadata.addMetadataValue(asset.id.value, field.id.value, "   ")
    }
  }

  test("Update value by ID") {

    /**
     * Setup:
     *
     * A TEXT field set to "Some text" on one asset.
     *
     * Assertions:
     *
     * Updating the value by its ID changes the text and keeps the ID.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.TEXT))

    val asset: Asset = testContext.persistAsset()

    val data = Map[String, Set[String]](field.persistedId -> Set("Some text"))
    val metadata = UserMetadata(data)

    testApp.service.metadata.setMetadata(asset.persistedId, metadata)

    var storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    var storedValue = storedMetadata.get(field.persistedId).get.head
    val oldValueId = storedValue.id

    val newValue = "Some updated text"

    testApp.service.metadata.updateMetadataValue(asset.persistedId, storedValue.persistedId, newValue)

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedValue = storedMetadata.get(field.persistedId).get.head

    storedValue.id shouldBe oldValueId
    storedValue.value shouldBe newValue
  }

  test("Updating value by ID should work case-insensitively") {

    /**
     * Setup:
     *
     * A KEYWORD field set to "tag1" on one asset.
     *
     * Assertions:
     *
     * Values compare case-insensitively, yet changing the value to "TAG1" by its ID is not refused as a duplicate of itself: the
     * new case is stored under the same ID.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    val asset: Asset = testContext.persistAsset()

    val oldValue = "tag1"
    val data = Map[String, Set[String]](field.persistedId -> Set(oldValue))
    val metadata = UserMetadata(data)

    testApp.service.metadata.setMetadata(asset.persistedId, metadata)

    var storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    var storedValue = storedMetadata.get(field.persistedId).get.head
    val oldValueId = storedValue.id

    val newValue = oldValue.toUpperCase

    testApp.service.metadata.updateMetadataValue(asset.persistedId, storedValue.persistedId, newValue)

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedValue = storedMetadata.get(field.persistedId).get.head

    storedValue.id shouldBe oldValueId
    storedValue.value shouldBe newValue
  }

  test("Updating value by ID with the same value should not raise exceptions") {

    /**
     * Setup:
     *
     * A KEYWORD field set to "tag1" on one asset.
     *
     * Assertions:
     *
     * Updating the value by its ID to the same text succeeds and leaves the ID and the value unchanged.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    val asset: Asset = testContext.persistAsset()

    val oldValue = "tag1"
    val data = Map[String, Set[String]](field.persistedId -> Set(oldValue))
    val metadata = UserMetadata(data)

    testApp.service.metadata.setMetadata(asset.persistedId, metadata)

    var storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    var storedValue = storedMetadata.get(field.persistedId).get.head
    val oldValueId = storedValue.id

    testApp.service.metadata.updateMetadataValue(asset.persistedId, storedValue.persistedId, oldValue)

    storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    storedValue = storedMetadata.get(field.persistedId).get.head

    storedValue.id shouldBe oldValueId
    storedValue.value shouldBe oldValue
  }

  test("Updating value by ID with empty value should raise") {

    /**
     * Setup:
     *
     * A KEYWORD field set to "tag1" on one asset.
     *
     * Assertions:
     *
     * Updating the value by its ID to whitespace only, spaces and a tab, fails validation.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))

    val asset: Asset = testContext.persistAsset()

    val oldValue = "tag1"
    val data = Map[String, Set[String]](field.persistedId -> Set(oldValue))
    val metadata = UserMetadata(data)

    testApp.service.metadata.setMetadata(asset.persistedId, metadata)

    val storedMetadata = testApp.service.metadata.getMetadata(asset.persistedId)
    val storedValue = storedMetadata.get(field.persistedId).get.head

    intercept[ValidationException] {
      testApp.service.metadata.updateMetadataValue(asset.persistedId, storedValue.persistedId, "  \t  ")
    }
  }

  test("A value that differs only in letter case is a duplicate, however many values the field holds") {

    /**
     * Setup:
     *
     * A KEYWORD field on one asset holding five values, "one" to "five".
     *
     * Assertions:
     *
     * Adding "THREE" fails validation as a duplicate of "three", and the field keeps its five values.
     *
     * Edge cases:
     *
     * Five values make the stored set a hashed set, which looks a value up by its hash code before comparing.
     */
    val field = testApp.service.metadata.addField(UserMetadataField(name = Util.randomStr(), fieldType = FieldType.KEYWORD))
    val asset: Asset = testContext.persistAsset()
    val values = List("one", "two", "three", "four", "five")
    values.foreach(testApp.service.metadata.addMetadataValue(asset.persistedId, field.persistedId, _))

    intercept[ValidationException] {
      testApp.service.metadata.addMetadataValue(asset.persistedId, field.persistedId, "THREE")
    }

    testApp.service.metadata.getMetadata(asset.persistedId)(field.persistedId).map(_.value) shouldBe values.toSet
  }
}
