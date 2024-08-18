package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Altitude
import software.altitude.core.models.Asset
import software.altitude.core.models.Face
import software.altitude.core.models.Person
import software.altitude.core.service.FaceRecognitionService
import software.altitude.test.IntegrationTestUtil
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class PersonServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  // default face count for a person
  val NUM_OF_FACES = 12

  test("Can save and retrieve a face object") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/movies-speed.png")
    val importedAsset: Asset = testApp.service.assetImport.importAsset(importAsset).get

    val faces = testApp.service.faceDetection.extractFaces(importAsset.data)
    faces.size should be(2)

    val persistedFaces = testApp.service.person.getAssetFaces(importedAsset.persistedId)
    persistedFaces.size should be(faces.size)

    val people = testApp.service.person.getPeople(importedAsset.persistedId)
    people.size should be(2)
    people.head.numOfFaces should be(1)
    people.last.numOfFaces should be(1)
  }

  test("Faces are added to a person") {
    val importAsset1 = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    testApp.service.assetImport.importAsset(importAsset1).get

    // Add another face to the same person
    val importAsset2 = IntegrationTestUtil.getImportAsset("people/meme-ben2.png")
    val importedAsset2: Asset = testApp.service.assetImport.importAsset(importAsset2).get

    val people = testApp.service.person.getPeople(importedAsset2.persistedId)
    people.size should be(1)
    people.head.numOfFaces should be(2)
  }

  test("Person has cover face assigned") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val importedAsset: Asset = testApp.service.assetImport.importAsset(importAsset).get
    val people = testApp.service.person.getPeople(importedAsset.persistedId)
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

    retrievedPerson2.label - retrievedPerson1.label should be(1)
  }

  test("Person merge B -> A") {
    val personA: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFaces(personA, NUM_OF_FACES)

    //
    // *** Person in cache should have only the required top faces, sorted by detection score
    //
    var cachedA = testApp.service.faceCache.getPersonByLabel(personA.label).get
    cachedA.numOfFaces should be(NUM_OF_FACES)
    cachedA.getFaces.size should be(FaceRecognitionService.MAX_COMPARISONS_PER_PERSON)

    // save the destination scores to compare with later
    val destOriginalFaceScores = cachedA.getFaces.map(_.detectionScore)

    cachedA.getFaces.sliding(2).forall(faces =>
      faces.head.detectionScore >= faces.last.detectionScore) should be(true)

    val personB: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFaces(personB, NUM_OF_FACES)

    var cachedB = testApp.service.faceCache.getPersonByLabel(personB.label).get
    cachedB.getFaces.size should be(FaceRecognitionService.MAX_COMPARISONS_PER_PERSON)

    //
    // *** Merge B -> A
    //
    val mergedA: Person = testApp.service.person.merge(dest=personA, source=personB)
    getNumberOfModelLabels should be(NUM_OF_FACES )

    // A is trained on B faces
    getLabels.count(_ == personA.label) should be(NUM_OF_FACES)

    //
    // *** Merged destination should have the merged with ID (one)
    //
    mergedA.mergedWithIds.size should be(1)
    mergedA.mergedWithIds.head should be(personB.persistedId)

    //
    // *** Merged cached person should have top faces, sorted by detection score, and not identical by score to where we started
    //
    cachedA = testApp.service.faceCache.getPersonByLabel(personA.label).get
    cachedA.getFaces.size should be(FaceRecognitionService.MAX_COMPARISONS_PER_PERSON)

    cachedA.getFaces.sliding(2).forall(faces =>
      faces.head.detectionScore >= faces.last.detectionScore) should be(true)

    cachedA.getFaces.map(_.detectionScore) should not be destOriginalFaceScores

    //
    // *** Cached source person should now return the merged person, if retrieved by label from cache
    //
    cachedB = testApp.service.faceCache.getPersonByLabel(personB.label).get
    cachedB should be theSameInstanceAs cachedA

    //
    // *** Sanity checks for persisted instances of source and destination2
    //
    val aFacesInDb: List[Face] = testApp.service.person.getPersonFaces(mergedA.persistedId)
    aFacesInDb.size should be(NUM_OF_FACES * 2)

    val persistedA: Person = testApp.service.person.getById(personA.persistedId)
    persistedA.mergedWithIds should be(mergedA.mergedWithIds)
    persistedA.numOfFaces should be(NUM_OF_FACES * 2)

    val persistedB: Person = testApp.service.person.getById(personB.persistedId)
    persistedB.mergedIntoId should be(Some(mergedA.persistedId))
    persistedB.mergedIntoLabel should be(Some(mergedA.label))
    persistedB.numOfFaces should be(0)

    val bFacesInDb: List[Face] = testApp.service.person.getPersonFaces(persistedB.persistedId)
    bFacesInDb shouldBe empty
  }

  test("Person merge C -> B, B -> A") {
    val personC: Person = testApp.service.person.addPerson(Person(name=Some("C")))
    testContext.addTestFaces(personC, NUM_OF_FACES)

    val personB: Person = testApp.service.person.addPerson(Person(name=Some("B")))
    testContext.addTestFaces(personB, NUM_OF_FACES)

    // no faces, to keep it simple
    val personA: Person = testApp.service.person.addPerson(Person(name=Some("A")))

    //
    // *** C -> B
    //
    val mergedB: Person = testApp.service.person.merge(dest=personB, source=personC)
    // B is trained on C faces
    getLabels.count(_ == personB.label) should be(NUM_OF_FACES)

    val bOrigFaceScores = mergedB.getFaces.map(_.detectionScore)

    var bFacesInDb: List[Face] = testApp.service.person.getPersonFaces(mergedB.persistedId)
    bFacesInDb.size should be(NUM_OF_FACES * 2)

    var cFacesInDb: List[Face] = testApp.service.person.getPersonFaces(personC.persistedId)
    cFacesInDb.size should be(0)

    var cachedC = testApp.service.faceCache.getPersonByLabel(personC.label).get
    var cachedB = testApp.service.faceCache.getPersonByLabel(personB.label).get

    intercept[NoSuchElementException] {
      testApp.service.faceCache.getPersonByLabel(personA.label).get
    }

    cachedB should be theSameInstanceAs cachedC

    val persistedB: Person = testApp.service.person.getById(personB.persistedId)
    persistedB.numOfFaces should be(NUM_OF_FACES * 2)

    val persistedC: Person = testApp.service.person.getById(personC.persistedId)
    persistedC.numOfFaces should be(0)

    //
    // *** B -> A
    //
    val persistedA: Person = testApp.service.person.getById(personA.persistedId)

    val mergedA: Person = testApp.service.person.merge(dest=persistedA, source=cachedB)

    // A is trained on B faces (which now has B + C faces)
    getLabels.count(_ == personA.label) should be(NUM_OF_FACES * 2)

    val aFacesInDb: List[Face] = testApp.service.person.getPersonFaces(mergedA.persistedId)
    aFacesInDb.size should be(NUM_OF_FACES * 2)

    bFacesInDb = testApp.service.person.getPersonFaces(personB.persistedId)
    bFacesInDb.size should be(0)

    cFacesInDb = testApp.service.person.getPersonFaces(personC.persistedId)
    cFacesInDb.size should be(0)

    val cachedA = testApp.service.faceCache.getPersonByLabel(personA.label).get

    // B was merged, A was empty, so after merge A should have the same faces as B
    cachedA.getFaces.map(_.detectionScore) shouldBe bOrigFaceScores

    // C should be pointing to A now
    cachedC = testApp.service.faceCache.getPersonByLabel(personC.label).get
    cachedC should be theSameInstanceAs cachedA

    // B should be pointing to A now
    cachedB = testApp.service.faceCache.getPersonByLabel(personB.label).get
    cachedB should be theSameInstanceAs cachedA

    // testApp.service.faceCache.dump()
  }

  def isSortedDescending(seq: Seq[Double]): Boolean = {
    seq.sliding(2).forall {
      case Seq(x, y) => x >= y
      case _ => true
    }
  }
}
