package altitude.core.integration

import altitude.core.Altitude
import altitude.core.Const.FaceRecognition
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.util.Query
import altitude.test.IntegrationTestUtil
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.should.Matchers.{should, shouldBe}

@DoNotDiscover class PersonServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  // default face count for a person
  val NUM_OF_FACES = 12

  test("Can save and retrieve a face object") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/movies-speed.png")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    val faces = testApp.service.faceDetection.extractFaces(importAsset.data)
    faces.size should be(2)

    val persistedFaces = testApp.service.person.getAssetFaces(importedAsset.persistedId)
    persistedFaces.size should be(faces.size)

    val people = testApp.service.person.getPeopleForAsset(importedAsset.persistedId)
    people.size should be(2)
    people.head.numOfFaces should be(1)
    people.last.numOfFaces should be(1)
  }

  test("An unknown person is marked as known edit") {
    val person = testApp.service.person.addPerson(Person())
    person.isNamed should be(false)

    val persistedPerson: Person = testApp.service.person.getById(person.persistedId)
    persistedPerson.isNamed should be(false)

    testApp.service.person.updateName(person, "Ben")

    val updatedPerson: Person = testApp.service.person.getById(person.persistedId)
    updatedPerson.isNamed should be(true)
  }

  test("Update person's name") {
    val name = "Ben"
    val person: Person = testApp.service.person.addPerson(Person(name=Some(name)))
    person.isNamed should be(true)

    val personQuery = "select * from person where id = ?"

    testApp.txManager.asReadOnly {
      val row = this.query(personQuery, person.persistedId).head
      row("name") should be(name)
      row("name_for_sort") should be(name.toLowerCase)
    }

    val newName = "Jerry"
    testApp.service.person.updateName(person, newName)

    testApp.txManager.asReadOnly {
      val row = this.query(personQuery, person.persistedId).head
      row("name") should be(newName)
      row("name_for_sort") should be(newName.toLowerCase)
    }

  }

  test("Faces are added to a person") {
    val importAsset1 = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    testApp.service.library.addImportAsset(importAsset1)

    // Add another face to the same person
    val importAsset2 = IntegrationTestUtil.getImportAsset("people/meme-ben2.png")
    val importedAsset2: Asset = testApp.service.library.addImportAsset(importAsset2)

    val people = testApp.service.person.getPeopleForAsset(importedAsset2.persistedId)
    people.size should be(1)
    people.head.numOfFaces should be(2)
  }

  test("Person becomes valid after adding enough faces") {
    val person: Person = testApp.service.person.addPerson(Person())
    person.isAboveThreshold should be(false)

    testContext.addTestFacesAndAssets(person, FaceRecognition.MIN_FACES_THRESHOLD)

    val updatedPerson: Person = testApp.service.person.getById(person.persistedId)
    updatedPerson.isAboveThreshold should be(true)
  }

  test("Person has cover face assigned", Focused) {
    val importAsset = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val people = testApp.service.person.getPeopleForAsset(importedAsset.persistedId)
    val faces = testApp.service.person.getAssetFaces(importedAsset.persistedId)

    people.size should be(1)
    faces.size should be(1)

    people.head.coverFaceId.get should be(faces.head.persistedId)
  }

  test("Can add and retrieve a person") {
    val person1Model = Person()
    val person1: Person = testApp.service.person.addPerson(person1Model)

    val retrievedPerson1: Person = testApp.service.person.getById(person1.persistedId)
    retrievedPerson1.isHidden should be(false)

    val person2Model = Person()
    val person2: Person = testApp.service.person.addPerson(person2Model)
    val retrievedPerson2: Person = testApp.service.person.getById(person2.persistedId)
  }

  test("Merging people results in correct persistence state") {
    val personA: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(personA, 3)

    val personB: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(personB, 4)

    val mergedB: Person = testApp.service.person.merge(dest=personB, source=personA)
    val NEW_FACES_TOTAL = 7
    mergedB.numOfFaces should be(NEW_FACES_TOTAL)

    val mergedBPersisted: Person = testApp.service.person.getById(mergedB.persistedId)

    // merged person should have the correct face number
    mergedBPersisted.numOfFaces should be(NEW_FACES_TOTAL)

    // all face labels should be the same as the merged INTO person label
    val faces  = testApp.service.person.getPersonFaces(mergedB.persistedId)
    faces.count(_.personId.get == mergedB.persistedId) should be(NEW_FACES_TOTAL)
  }

  test("Person merge B -> A") {
    val personA: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(personA, NUM_OF_FACES)

    val personB: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(personB, NUM_OF_FACES)

    // *** Merge B -> A
    //
    val mergedA: Person = testApp.service.person.merge(dest=personA, source=personB)

    //
    // *** Sanity checks for persisted instances of source and destination2
    //
    val aFacesInDb: List[Face] = testApp.service.person.getPersonFaces(mergedA.persistedId)
    aFacesInDb.size should be(NUM_OF_FACES * 2)

    val persistedA: Person = testApp.service.person.getById(personA.persistedId)
    persistedA.numOfFaces should be(NUM_OF_FACES * 2)

    val persistedB: Person = testApp.service.person.getById(personB.persistedId)
    persistedB.numOfFaces should be(0)

    val bFacesInDb: List[Face] = testApp.service.person.getPersonFaces(persistedB.persistedId)
    bFacesInDb shouldBe empty
  }

  test("Person merge C -> B, B -> A") {
    val personC: Person = testApp.service.person.addPerson(Person(name=Some("C")))
    testContext.addTestFacesAndAssets(personC, NUM_OF_FACES)

    val personB: Person = testApp.service.person.addPerson(Person(name=Some("B")))
    testContext.addTestFacesAndAssets(personB, NUM_OF_FACES)

    // no faces, to keep it simple
    val personA: Person = testApp.service.person.addPerson(Person(name=Some("A")))

    //
    // *** C -> B
    //
    val mergedB: Person = testApp.service.person.merge(dest=personB, source=personC)
    // B is trained on C faces
    mergedB.numOfFaces should be(NUM_OF_FACES * 2)
    mergedB.isAboveThreshold should be(true)

    var bFacesInDb: List[Face] = testApp.service.person.getPersonFaces(mergedB.persistedId)
    bFacesInDb.size should be(NUM_OF_FACES * 2)

    var cFacesInDb: List[Face] = testApp.service.person.getPersonFaces(personC.persistedId)
    cFacesInDb.size should be(0)

    val persistedB: Person = testApp.service.person.getById(personB.persistedId)
    persistedB.numOfFaces should be(NUM_OF_FACES * 2)

    val persistedC: Person = testApp.service.person.getById(personC.persistedId)
    persistedC.numOfFaces should be(0)

    //
    // *** B -> A
    //
    val persistedA: Person = testApp.service.person.getById(personA.persistedId)
    val mergedA: Person = testApp.service.person.merge(dest=persistedA, source=mergedB)
    
    // A is trained on B faces (which now has B + C faces)
    val aFacesInDb: List[Face] = testApp.service.person.getPersonFaces(mergedA.persistedId)
    aFacesInDb.size should be(NUM_OF_FACES * 2)

    bFacesInDb = testApp.service.person.getPersonFaces(personB.persistedId)
    bFacesInDb.size should be(0)

    cFacesInDb = testApp.service.person.getPersonFaces(personC.persistedId)
    cFacesInDb.size should be(0)
    
  }

  test("Merged named person does not cause naming conflicts") {
    val mergedIntoName = "London"
    val personA: Person = testApp.service.person.addPerson(Person(name=Some(mergedIntoName)))
    testContext.addTestFacesAndAssets(personA)

    val mergedFromName = "Phoenix"
    val personB: Person = testApp.service.person.addPerson(Person(name=Some(mergedFromName)))
    testContext.addTestFacesAndAssets(personB)

    testApp.service.person.merge(dest=personA, source=personB)

    val updatedPersonA: Person = testApp.service.person.getById(personA.persistedId)
    updatedPersonA.name.get should be(mergedIntoName)

    // at this point mergedFromName should be available for use
    val personC: Person = testApp.service.person.addPerson(Person(name=Some(mergedFromName)))
    personC.name.get should be(mergedFromName)
  }

  test("Known person keeps the same when merged into Unknown") {
    // destination merge person is not named
    val personA: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(personA)

    val mergedFromName = "London"
    val personB: Person = testApp.service.person.addPerson(Person(name=Some(mergedFromName)))
    testContext.addTestFacesAndAssets(personB)

    testApp.service.person.merge(dest=personA, source=personB)

    val mergedPerson: Person = testApp.service.person.getById(personA.persistedId)

    // should inherit the known person name
    mergedPerson.name.get should be(mergedFromName)
  }

  test("Same person can appear in the same image more than once") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/twins.png")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val people = testApp.service.person.getPeopleForAsset(importedAsset.persistedId)

    people.length should be(1)
    people.head.numOfFaces should be(2)
  }

  test("Hidden person should not be returned with bulk retrieval") {
    val person: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person, 6)

    val hiddenPerson: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(hiddenPerson, 6)

    testApp.service.person.getAllAboveThreshold.size should be(2)
    testApp.service.person.setVisibility(hiddenPerson, isHidden = true)
    testApp.service.person.getAllAboveThreshold.size should be(1)
  }

  test("Hidden person is not returned for an asset") {
    val importAsset1 = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val asset = testApp.service.library.addImportAsset(importAsset1)

    var people = testApp.service.person.getPeopleForAsset(asset.persistedId)
    people.size should be(1)

    testApp.service.person.setVisibility(people.head, isHidden = true)
    people = testApp.service.person.getPeopleForAsset(asset.persistedId)
    people.size should be(0)
  }

  test("Recycled asset should not count toward face count") {
    val totalAssets = 5
    val people = List.fill(2)(testApp.service.person.addPerson(Person()))
    var person: Person = people.head

    testContext.addTestFacesAndAssets(people, assetCount = totalAssets)

    person = testApp.service.person.getById(person.persistedId)
    person.numOfFaces should be(totalAssets)

    val allAssets: List[Asset] = testApp.service.asset.query(new Query()).records.map(Asset.fromJson)

    // recycle some assets
    val recycleCount = 2
    allAssets.take(recycleCount).foreach { asset =>
      testApp.service.library.recycleAssets(Set(asset.persistedId))
    }

    person = testApp.service.person.getById(person.persistedId)
    person.numOfFaces should be(totalAssets - recycleCount)
    // The faces are still in DB, but they are not counted toward the person,
    // as they are in the trash bin until being Purged.
    testApp.service.person.getPersonFaces(person.persistedId).length should be(totalAssets)
  }

  test("Restored asset should restore person face counts", Focused) {
    val totalAssets = 5
    val people = List.fill(2)(testApp.service.person.addPerson(Person()))
    var person: Person = people.head
    testContext.addTestFacesAndAssets(people, assetCount = totalAssets)

    person = testApp.service.person.getById(person.persistedId)
    person.numOfFaces should be(totalAssets)

    val allAssets: List[Asset] = testApp.service.asset.query(new Query()).records.map(Asset.fromJson)

    // recycle some assets
    val recycleCount = 2
    allAssets.take(recycleCount).foreach { asset =>
      testApp.service.library.recycleAssets(Set(asset.persistedId))
    }

    person = testApp.service.person.getById(person.persistedId)
    person.numOfFaces should be(totalAssets - recycleCount)

    // restore the assets (move from recycle)
    allAssets.take(recycleCount).foreach { asset =>
      testApp.service.library.moveAssetsToFolder(Set(asset.persistedId), testContext.repository.rootFolderId)
    }

    person = testApp.service.person.getById(person.persistedId)
    person.numOfFaces should be(totalAssets)
  }
}
