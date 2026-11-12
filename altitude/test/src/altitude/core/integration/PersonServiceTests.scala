package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestContext
import java.text.Normalizer
import java.util.Locale
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe }
import org.scalatest.tagobjects.Slow
import scalasql.core.SqlStr.SqlStringSyntax

import scala.util.Random

import altitude.core.Altitude
import altitude.core.Api
import altitude.core.Const
import altitude.core.Const.FaceRecognition
import altitude.core.Const.PeopleBucket
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.dao.jdbc.PersonDao
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.routes.SearchRequestParser
import altitude.core.service.PersonService
import altitude.core.service.PersonService.FaceTarget
import altitude.core.util.Query
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSource
import altitude.core.util.SearchText
import altitude.core.util.Util

@DoNotDiscover class PersonServiceTests(override val testApp: Altitude) extends IntegrationTestCore with SearchPlans {

  // default face count for a person
  val NUM_OF_FACES = 12

  test("A Face's Frame time is stored and read back, and a Face in an image has none") {

    /**
     * Setup:
     *
     * One asset and one Person with two Faces: one with a Frame time of 4500 ms, as in a Video, and one without, as in an image.
     *
     * Assertions:
     *
     * Reading the asset's Faces returns the Video Face's Frame time as stored and none for the image Face.
     */
    val asset = testContext.persistAsset()
    val person = testApp.service.person.addPerson(Person())
    val inVideo = testApp.service.person.addFace(makeFace(frameTimeMs = Some(4500L)), asset, person)
    val inImage = testApp.service.person.addFace(makeFace(frameTimeMs = None), asset, person)

    val byId = testApp.service.person.getAssetFaces(asset.persistedId).map(face => face.persistedId -> face.frameTimeMs).toMap
    byId(inVideo.persistedId) shouldBe Some(4500L)
    byId(inImage.persistedId) shouldBe None
  }

  test("A Face's quality and tier are stored and read back") {

    /**
     * Setup:
     *
     * One asset and one Person with a match-only Face of quality 12.5 and an enrolled Face of quality 27.25.
     *
     * Assertions:
     *
     * Both Faces read back with the quality and tier they were stored with.
     */
    val asset = testContext.persistAsset()
    val person = testApp.service.person.addPerson(Person())
    val matchOnly = testApp.service.person.addFace(makeFace(quality = 12.5, isEnrolled = false), asset, person)
    val enrolled = testApp.service.person.addFace(makeFace(quality = 27.25, isEnrolled = true), asset, person)

    val byId = testApp.service.person.getAssetFaces(asset.persistedId).map(face => face.persistedId -> face).toMap
    byId(matchOnly.persistedId).quality shouldBe 12.5
    byId(matchOnly.persistedId).isEnrolled shouldBe false
    byId(enrolled.persistedId).quality shouldBe 27.25
    byId(enrolled.persistedId).isEnrolled shouldBe true
  }

  // A name in either Unicode form, whichever form the source file holds the literal in
  private def precomposed(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)
  private def decomposed(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFD)

  /** The person's stored row, for the columns the model does not carry */
  private def personRow(id: String): Map[String, AnyRef] =
    testApp.txManager.asReadOnly(query("SELECT * FROM person WHERE id = ?", id).head)

  private def makeFace(frameTimeMs: Option[Long] = None, quality: Double = 20.0, isEnrolled: Boolean = true): Face = Face(
    x1 = 1,
    y1 = 1,
    width = 10,
    height = 10,
    detectionScore = 0.9,
    checksum = TestContext.randomChecksum,
    features = Array.fill(512)(Random.nextFloat()),
    quality = quality,
    isEnrolled = isEnrolled,
    frameTimeMs = frameTimeMs
  )

  test("Can save and retrieve a face object") {

    /**
     * Setup:
     *
     * An import of `people/movies-speed.png`, a still with two faces.
     *
     * Assertions:
     *
     * Detection finds two faces, the asset has as many stored Faces, and they belong to two People with one Face each.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/movies-speed.png")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    val faces = testApp.service.faceDetection.extractFaces(importAsset.bytes)
    faces.size should be(2)

    val persistedFaces = testApp.service.person.getAssetFaces(importedAsset.persistedId)
    persistedFaces.size should be(faces.size)

    val people = testApp.service.person.getPeopleForAsset(importedAsset.persistedId)
    people.size should be(2)
    people.head.numOfFaces should be(1)
    people.last.numOfFaces should be(1)
  }

  test("Renaming an unknown person marks it as named") {

    /**
     * Setup:
     *
     * A Person added without a name, which gives it an "Unknown N" name.
     *
     * Assertions:
     *
     * The person is unnamed when added and when read back, and becomes named once renamed to "Ben".
     */
    val person = testApp.service.person.addPerson(Person())
    person.isNamed should be(false)

    val persistedPerson: Person = testApp.service.person.getById(person.persistedId)
    persistedPerson.isNamed should be(false)

    testApp.service.person.updateName(person, "Ben")

    val updatedPerson: Person = testApp.service.person.getById(person.persistedId)
    updatedPerson.isNamed should be(true)
  }

  test("Update person's name") {

    /**
     * Setup:
     *
     * A Person added with the name "Ben", its row read back with raw SQL.
     *
     * Assertions:
     *
     * The row holds the name and its name key as sort name, both as added and after renaming to "Jerry".
     */
    val name = "Ben"
    val person: Person = testApp.service.person.addPerson(Person(name = Some(name)))
    person.isNamed should be(true)

    val row = personRow(person.persistedId)
    row("name") should be(name)
    row("name_for_sort") should be("ben")

    val newName = "Jerry"
    testApp.service.person.updateName(person, newName)

    val renamedRow = personRow(person.persistedId)
    renamedRow("name") should be(newName)
    renamedRow("name_for_sort") should be("jerry")
  }

  test("Faces are added to a person") {

    /**
     * Setup:
     *
     * Imports of two photos of the same person, `people/meme-ben2.png` and `people/meme-ben3.png`.
     *
     * Assertions:
     *
     * The second photo's face joins the Person the first photo started, who then has two Faces.
     */
    val importAsset1 = IntegrationTestUtil.getImportAsset("people/meme-ben2.png")
    testApp.service.library.addImportAsset(importAsset1)

    // Add another face to the same person
    val importAsset2 = IntegrationTestUtil.getImportAsset("people/meme-ben3.png")
    val importedAsset2: Asset = testApp.service.library.addImportAsset(importAsset2)

    val people = testApp.service.person.getPeopleForAsset(importedAsset2.persistedId)
    people.size should be(1)
    people.head.numOfFaces should be(2)
  }

  test("Person becomes valid after adding enough faces") {

    /**
     * Setup:
     *
     * A new Person given `MIN_FACES_THRESHOLD` (3) test Faces, each on an asset of its own.
     *
     * Assertions:
     *
     * The person is not Matched when added and is once the Faces are in.
     */
    val person: Person = testApp.service.person.addPerson(Person())
    person.isMatched should be(false)

    testContext.addTestFacesAndAssets(person, FaceRecognition.MIN_FACES_THRESHOLD)

    val updatedPerson: Person = testApp.service.person.getById(person.persistedId)
    updatedPerson.isMatched should be(true)
  }

  test("Person has cover face assigned") {

    /**
     * Setup:
     *
     * An import of `people/meme-ben.jpg`, a photo of one person, into a library with no People.
     *
     * Assertions:
     *
     * The import starts one Person with one Face, and that Face is the person's cover.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val people = testApp.service.person.getPeopleForAsset(importedAsset.persistedId)
    val faces = testApp.service.person.getAssetFaces(importedAsset.persistedId)

    people.size should be(1)
    faces.size should be(1)

    people.head.coverFaceId.get should be(faces.head.persistedId)
  }

  test("Can add and retrieve a person") {

    /**
     * Setup:
     *
     * Two Persons added with no name and no Faces.
     *
     * Assertions:
     *
     * Both read back by ID as visible and unnamed, each with its own "Unknown N" name.
     */
    val person1Model = Person()
    val person1: Person = testApp.service.person.addPerson(person1Model)

    val retrievedPerson1: Person = testApp.service.person.getById(person1.persistedId)
    retrievedPerson1.isHidden should be(false)
    retrievedPerson1.isNamed should be(false)

    val person2Model = Person()
    val person2: Person = testApp.service.person.addPerson(person2Model)
    val retrievedPerson2: Person = testApp.service.person.getById(person2.persistedId)
    retrievedPerson2.isHidden should be(false)
    retrievedPerson2.isNamed should be(false)
    // Each unnamed person draws its own number from the label sequence
    retrievedPerson2.name should not be retrievedPerson1.name
  }

  test("Merging people results in correct persistence state") {

    /**
     * Setup:
     *
     * Person A with three test Faces and Person B with four, each Face on an asset of its own; A is merged into B.
     *
     * Assertions:
     *
     * B has seven Faces by the merge result, by its stored count and by a Search for its assets, and every one of A's former
     * Faces now belongs to B.
     */
    val personA: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(personA, 3)

    val personB: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(personB, 4)

    val mergedB: Person = testApp.service.person.merge(dest = personB, source = personA)
    val NEW_FACES_TOTAL = 7
    mergedB.numOfFaces should be(NEW_FACES_TOTAL)

    val mergedBPersisted: Person = testApp.service.person.getById(mergedB.persistedId)

    val mergedSearchTotal = testApp.service.library
      .count(
        new SearchQuery(
          params = Map(altitude.core.FieldConst.Asset.IS_RECYCLED -> false),
          personIds = Set(mergedB.persistedId)
        )
      )

    // merged person should have the correct face number
    mergedBPersisted.numOfFaces should be(NEW_FACES_TOTAL)
    mergedBPersisted.numOfFaces should be(mergedSearchTotal)

    // all face labels should be the same as the merged INTO person label
    val faces = testApp.service.person.getPersonFaces(mergedB.persistedId)
    faces.count(_.personId.get == mergedB.persistedId) should be(NEW_FACES_TOTAL)
  }

  test("A person's top faces are the best detections, best first") {

    /**
     * Setup:
     *
     * A person with eight faces of random detection scores, read with a limit of three and with no limit to speak of.
     *
     * Assertions:
     *
     * The three returned are the three highest-scoring of the eight, in descending score order.
     */
    val person: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person, 8)

    val all = testApp.service.person.getPersonFaces(person.persistedId)
    val top = testApp.service.person.getPersonFaces(person.persistedId, limit = 3)

    all.size should be(8)
    all.map(_.detectionScore) should be(all.map(_.detectionScore).sorted.reverse)
    top.map(_.persistedId) should be(all.take(3).map(_.persistedId))
  }

  test("A person's top faces leave out the Faces on an asset marked for purging") {

    /**
     * Setup:
     *
     * A person with Faces on three assets, of detection scores 0.9, 0.8 and 0.7, the best one's asset recycled and marked for
     * purging, and the next one's asset recycled only.
     *
     * Assertions:
     *
     * The person's top faces are the Faces of 0.8 and 0.7, in that order: the one on the asset marked for purging, which could
     * not be set as the cover, is not among them, and the one in the trash is.
     */
    val person: Person = testApp.service.person.addPerson(Person())
    val faces = List(0.9, 0.8, 0.7).zipWithIndex.map {
      case (score, n) =>
        testContext.addTestFace(
          testApp.service.person.getById(person.persistedId),
          testContext.insertAsset(),
          TestContext.unitVector(n + 1),
          detectionScore = score)
    }
    testApp.service.library.recycleAssets(Set(faces.head.assetId.get, faces(1).assetId.get))
    testApp.service.asset.updateById(faces.head.assetId.get, Map(FieldConst.Asset.IS_PURGED -> true))

    testApp.service.person.getPersonFaces(person.persistedId).map(_.persistedId) shouldBe faces.drop(1).map(_.persistedId)
  }

  test("A cover is refused for another Person's Face, another repository's Face and a Face on an asset marked for purging") {

    /**
     * Setup:
     *
     * A Person with Faces on three assets, the first Face their cover, the second's asset recycled and marked for purging; a
     * second Person with a Face of their own; and a second repository with a Person and a Face.
     *
     * Assertions:
     *
     * Setting the first Person's cover to the second Person's Face, or to their own Face on the asset marked for purging, is a
     * `ValidationException`, and to the other repository's Face a `NotFoundException`. The cover is still the first Face after
     * each. Their Face on the third asset, which is live, becomes their cover, both on the Person the call answers and as stored.
     */
    val repo = testContext.repository
    val added: Person = testApp.service.person.addPerson(Person())
    val faces = (1 to 3).toList.map {
      n =>
        val asset = testContext.insertAsset()
        testContext.addTestFace(testApp.service.person.getById(added.persistedId), asset, TestContext.unitVector(n))
    }
    val person: Person = testApp.service.person.getById(added.persistedId)
    person.coverFaceId shouldBe Some(faces.head.persistedId)

    val purging = faces(1).assetId.get
    testApp.service.library.recycleAssets(Set(purging))
    testApp.service.asset.updateById(purging, Map(FieldConst.Asset.IS_PURGED -> true))

    val other: Person = testApp.service.person.addPerson(Person())
    val othersFace = testContext.addTestFace(other, testContext.insertAsset(), TestContext.unitVector(4))

    val otherRepo = testContext.persistRepository()
    switchContextRepo(otherRepo)
    val foreignFace = testContext.addTestFace(
      testApp.service.person.addPerson(Person()),
      testContext.insertAsset(testContext.makeAsset(repository = Some(otherRepo))),
      TestContext.unitVector(5))
    switchContextRepo(repo)

    intercept[ValidationException](testApp.service.person.setFaceAsCover(person.persistedId, othersFace.persistedId))
    intercept[ValidationException](testApp.service.person.setFaceAsCover(person.persistedId, faces(1).persistedId))
    intercept[NotFoundException](testApp.service.person.setFaceAsCover(person.persistedId, foreignFace.persistedId))
    testApp.service.person.getById(person.persistedId).coverFaceId shouldBe Some(faces.head.persistedId)

    val covered = testApp.service.person.setFaceAsCover(person.persistedId, faces(2).persistedId)
    covered.coverFaceId shouldBe Some(faces(2).persistedId)
    testApp.service.person.getById(person.persistedId).coverFaceId shouldBe Some(faces(2).persistedId)
  }

  test("Person merge B -> A") {

    /**
     * Setup:
     *
     * Persons A and B with twelve test Faces each, every Face on an asset of its own; B is merged into A.
     *
     * Assertions:
     *
     * A holds all 24 Faces, by its stored count and by the Faces stored for it, while B is left with a zero count and no Faces.
     */
    val personA: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(personA, NUM_OF_FACES)

    val personB: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(personB, NUM_OF_FACES)

    // *** Merge B -> A
    //
    val mergedA: Person = testApp.service.person.merge(dest = personA, source = personB)

    //
    // *** Sanity checks for persisted instances of source and destination
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

    /**
     * Setup:
     *
     * Named Persons C and B with twelve test Faces each and A with none; C is merged into B, then the merged B into A.
     *
     * Assertions:
     *
     * After each merge the destination holds all 24 Faces, B is above the face threshold after the first, and every merged-away
     * person is left with a zero count and no Faces, so C's Faces travel through B to A.
     */
    val personC: Person = testApp.service.person.addPerson(Person(name = Some("C")))
    testContext.addTestFacesAndAssets(personC, NUM_OF_FACES)

    val personB: Person = testApp.service.person.addPerson(Person(name = Some("B")))
    testContext.addTestFacesAndAssets(personB, NUM_OF_FACES)

    // no faces, to keep it simple
    val personA: Person = testApp.service.person.addPerson(Person(name = Some("A")))

    //
    // *** C -> B
    //
    val mergedB: Person = testApp.service.person.merge(dest = personB, source = personC)
    // B is trained on C faces
    mergedB.numOfFaces should be(NUM_OF_FACES * 2)
    mergedB.isMatched should be(true)

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
    val mergedA: Person = testApp.service.person.merge(dest = persistedA, source = mergedB)

    // A is trained on B faces (which now has B + C faces)
    val aFacesInDb: List[Face] = testApp.service.person.getPersonFaces(mergedA.persistedId)
    aFacesInDb.size should be(NUM_OF_FACES * 2)

    bFacesInDb = testApp.service.person.getPersonFaces(personB.persistedId)
    bFacesInDb.size should be(0)

    cFacesInDb = testApp.service.person.getPersonFaces(personC.persistedId)
    cFacesInDb.size should be(0)

  }

  test("Merged named person does not cause naming conflicts") {

    /**
     * Setup:
     *
     * Persons named "London" and "Phoenix" with one test Face each; Phoenix is merged into London.
     *
     * Assertions:
     *
     * London keeps its name, and a new Person can then take the name "Phoenix", since the name index covers only live people.
     */
    val mergedIntoName = "London"
    val personA: Person = testApp.service.person.addPerson(Person(name = Some(mergedIntoName)))
    testContext.addTestFacesAndAssets(personA)

    val mergedFromName = "Phoenix"
    val personB: Person = testApp.service.person.addPerson(Person(name = Some(mergedFromName)))
    testContext.addTestFacesAndAssets(personB)

    testApp.service.person.merge(dest = personA, source = personB)

    val updatedPersonA: Person = testApp.service.person.getById(personA.persistedId)
    updatedPersonA.name.get should be(mergedIntoName)

    // at this point mergedFromName should be available for use
    val personC: Person = testApp.service.person.addPerson(Person(name = Some(mergedFromName)))
    personC.name.get should be(mergedFromName)
  }

  test("A live person cannot take the name of another live person") {

    /**
     * Setup:
     *
     * Two live Persons named "Alice" and "Bob".
     *
     * Assertions:
     *
     * Renaming Bob to "Alice" fails as a duplicate.
     */
    testApp.service.person.addPerson(Person(name = Some("Alice")))
    val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))

    intercept[DuplicateException](testApp.service.person.updateName(bob, "Alice"))
  }

  test("A live person cannot take another live person's name in another case") {

    /**
     * Setup:
     *
     * Two live Persons named "Tessa" and "Bob".
     *
     * Assertions:
     *
     * Renaming Bob to "tessa" or to "TESSA" fails as a duplicate, and Bob keeps his name.
     */
    testApp.service.person.addPerson(Person(name = Some("Tessa")))
    val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))

    intercept[DuplicateException](testApp.service.person.updateName(bob, "tessa"))
    intercept[DuplicateException](testApp.service.person.updateName(bob, "TESSA"))

    val persistedBob: Person = testApp.service.person.getById(bob.persistedId)
    persistedBob.name shouldBe Some("Bob")
  }

  test("A live person cannot take another live person's name in another case of a non-ASCII letter") {

    /**
     * Setup:
     *
     * Two live Persons named "élodie" and "Bob".
     *
     * Assertions:
     *
     * Renaming Bob to "Élodie" fails as a duplicate: the name key folds beyond ASCII on both engines.
     */
    testApp.service.person.addPerson(Person(name = Some(precomposed("élodie"))))
    val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))

    intercept[DuplicateException](testApp.service.person.updateName(bob, precomposed("Élodie")))
  }

  test("A live person cannot take another live person's name in another Unicode form") {

    /**
     * Setup:
     *
     * Two live Persons named "Élodie", its É precomposed (U+00C9), and "Ann".
     *
     * Assertions:
     *
     * Renaming Ann to "Élodie" decomposed (E + U+0301) fails as a duplicate. Renaming her to "Zoë" decomposed (e + U+0308) stores
     * the precomposed name, with its name key as sort name.
     */
    testApp.service.person.addPerson(Person(name = Some(precomposed("Élodie"))))
    val ann: Person = testApp.service.person.addPerson(Person(name = Some("Ann")))

    intercept[DuplicateException](testApp.service.person.updateName(ann, decomposed("Élodie")))

    val renamed = testApp.service.person.updateName(ann, decomposed("Zoë"))
    renamed.name shouldBe Some(precomposed("Zoë"))

    val row = personRow(ann.persistedId)
    row("name") shouldBe precomposed("Zoë")
    row("name_for_sort") shouldBe precomposed("zoë")
  }

  test("A live person's name in another case fold is refused to a rename, and a Face moved to it joins them") {

    /**
     * Setup:
     *
     * Live Persons named "Weiß" (with a Face), "σοφος" with a final sigma, and "Bob", and a source Person with two Faces.
     *
     * Assertions:
     *
     * Renaming Bob to "WEISS" or to "σοφοσ" with a plain sigma fails as a duplicate, and Bob keeps his name. Looking up "WEISS"
     * finds Weiß, and moving a source Face to "WEISS" gives it to Weiß, who keeps her name and counts two assets, rather than
     * creating a second Person.
     */
    val weiss: Person = testApp.service.person.addPerson(Person(name = Some("Weiß")))
    testContext.addTestFace(weiss, testContext.insertAsset(), TestContext.unitVector(1))
    testApp.service.person.addPerson(Person(name = Some("σοφος")))
    val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))
    val source: Person = testApp.service.person.addPerson(Person())
    val moved = testContext.addTestFace(source, testContext.insertAsset(), TestContext.unitVector(2))
    testContext.addTestFace(
      testApp.service.person.getById(source.persistedId),
      testContext.insertAsset(),
      TestContext.unitVector(3))

    intercept[DuplicateException](testApp.service.person.updateName(bob, "WEISS"))
    intercept[DuplicateException](testApp.service.person.updateName(bob, "σοφοσ"))
    testApp.service.person.getById(bob.persistedId).name shouldBe Some("Bob")

    testApp.service.person.getLiveByNameKey("WEISS").map(_.persistedId) shouldBe Some(weiss.persistedId)

    val move = testApp.service.person.moveFace(moved.persistedId, FaceTarget.Named("WEISS"))
    move.target.persistedId shouldBe weiss.persistedId
    val after: Person = testApp.service.person.getById(weiss.persistedId)
    after.name shouldBe Some("Weiß")
    after.numOfFaces shouldBe 2
  }

  test("A person renamed to its own name in another case takes the new name") {

    /**
     * Setup:
     *
     * A live Person named "Tessa".
     *
     * Assertions:
     *
     * Renaming her "TESSA" saves: the row holds the new name and the name key "tessa", which it already held.
     */
    val tessa: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))

    val renamed = testApp.service.person.updateName(tessa, "TESSA")
    renamed.name shouldBe Some("TESSA")

    val row = personRow(tessa.persistedId)
    row("name") shouldBe "TESSA"
    row("name_for_sort") shouldBe "tessa"
  }

  test("A merged-away or bad-match person does not hold its name") {

    /**
     * Setup:
     *
     * Persons named "London" and "Tessa" with one test Face each, Tessa merged into London; Persons named "Bob" and "Cara".
     *
     * Assertions:
     *
     * Bob can be renamed "TESSA", since the merged-away Tessa is not live. Once Bob is marked as a bad match, Cara can be renamed
     * "tessa", since a bad match holds no name either.
     */
    val london: Person = testApp.service.person.addPerson(Person(name = Some("London")))
    testContext.addTestFacesAndAssets(london)
    val tessa: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))
    testContext.addTestFacesAndAssets(tessa)
    testApp.service.person.merge(dest = london, source = tessa)

    val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))
    val renamedBob = testApp.service.person.updateName(bob, "TESSA")
    renamedBob.name shouldBe Some("TESSA")

    testApp.service.person.markAsBadMatch(renamedBob)

    val cara: Person = testApp.service.person.addPerson(Person(name = Some("Cara")))
    testApp.service.person.updateName(cara, "tessa").name shouldBe Some("tessa")
  }

  test("A live Person of the context repository is found, and a merged-away, bad-match or other repository's one is not") {

    /**
     * Setup:
     *
     * Persons named "London" and "Tessa" with one test Face each, Tessa merged into London; Persons named "Bob" and "Carl", Carl
     * marked as a bad match, and Bob hidden; a second repository with a Person named "Eve".
     *
     * Assertions:
     *
     * Through the first repository, London and the hidden Bob are found live, with their names. Tessa, Carl, Eve and an ID that
     * no Person has are not found, though Tessa and Carl are found when liveness does not matter (`getById`).
     */
    val repo = testContext.repository
    val london: Person = testApp.service.person.addPerson(Person(name = Some("London")))
    testContext.addTestFacesAndAssets(london)
    val tessa: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))
    testContext.addTestFacesAndAssets(tessa)
    testApp.service.person.merge(dest = london, source = tessa)
    val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))
    testApp.service.person.setVisibility(bob, isHidden = true)
    val carl: Person = testApp.service.person.markAsBadMatch(testApp.service.person.addPerson(Person(name = Some("Carl"))))

    val otherRepo = testContext.persistRepository()
    testApp.service.repository.switchContextToRepository(otherRepo)
    val eve: Person = testApp.service.person.addPerson(Person(name = Some("Eve")))
    testApp.service.repository.switchContextToRepository(repo)

    testApp.service.person.getLiveInRepository(london.persistedId).name shouldBe Some("London")
    testApp.service.person.getLiveInRepository(bob.persistedId).name shouldBe Some("Bob")

    List(tessa.persistedId, carl.persistedId, eve.persistedId, java.util.UUID.randomUUID.toString).foreach {
      personId =>
        intercept[NotFoundException] {
          testApp.service.person.getLiveInRepository(personId)
        }
    }
    testApp.service.person.getById(tessa.persistedId).name shouldBe Some("Tessa")
    testApp.service.person.getById(carl.persistedId).name shouldBe Some("Carl")
  }

  test("A person cannot be named as an unnamed person is") {

    /**
     * Setup:
     *
     * A live Person named "Bob".
     *
     * Assertions:
     *
     * Renaming him "unknown 42", "Unknown 42" with two spaces, "UNKNOWN 0042", or "Unknown 42" followed by a Unicode no-break
     * space fails validation with the reserved-name error, and he keeps his name. "Unknown Soldier", with no number, is a name
     * like any other.
     *
     * Edge cases:
     *
     * Any case, more than one space before the number, and spacing `String.strip` does not remove (U+00A0, U+2007, U+202F).
     */
    val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))

    // The spaces are built from their code points, which the formatter and the linter cannot rewrite
    val trailingNoBreakSpaces = List(0xa0, 0x2007, 0x202f).map("Unknown 42" + _.toChar)

    (List("unknown 42", "Unknown  42", "UNKNOWN 0042") ++ trailingNoBreakSpaces).foreach {
      name =>
        val ex = intercept[ValidationException](testApp.service.person.updateName(bob, name))
        withClue(name)(ex.message shouldBe Const.Msg.Err.RESERVED_PERSON_NAME)
    }

    val persistedBob: Person = testApp.service.person.getById(bob.persistedId)
    persistedBob.name shouldBe Some("Bob")

    testApp.service.person.updateName(bob, "Unknown Soldier").name shouldBe Some("Unknown Soldier")
  }

  test("A person cannot be named as an unnamed person is in compatibility forms") {

    /**
     * Setup:
     *
     * A live Person named "Bob".
     *
     * Assertions:
     *
     * Renaming him "Unknown 42" with a full-width "U", or with the number in circled digits, fails validation with the
     * reserved-name error, and he keeps his name: the name's compatibility forms fold to an unnamed person's form, which would
     * otherwise reach the name key.
     */
    val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))

    List("Ｕnknown 42", "Unknown ④②").foreach {
      name =>
        val ex = intercept[ValidationException](testApp.service.person.updateName(bob, name))
        withClue(name)(ex.message shouldBe Const.Msg.Err.RESERVED_PERSON_NAME)
    }

    testApp.service.person.getById(bob.persistedId).name shouldBe Some("Bob")
  }

  test("A person name is held to 2 to 80 characters once in NFC, and to a name key that is not too long") {

    /**
     * Setup:
     *
     * A live Person named "Bob", and an unnamed Person with a Face.
     *
     * Assertions:
     *
     * Renaming Bob, or moving the Face to a Person by name, to 80 of U+FB2C (80 characters as typed, 240 in NFC) fails validation
     * with the name field's too-long error; to "e" and a combining accent (one character in NFC) with the too-short error; and to
     * 70 of U+FDFA (whose name key NFKC spells out to 2310 bytes) with the name key error. Bob keeps his name, and the Face its
     * Person.
     */
    val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))
    val unnamed: Person = testApp.service.person.addPerson(Person())
    val face = testContext.addTestFace(unnamed, testContext.insertAsset(), TestContext.unitVector(1))
    val refused = List(
      lengthenedByNfc(Api.Constraints.MAX_NAME_LENGTH) -> Const.Msg.Err.VALUE_TOO_LONG.format(Api.Constraints.MAX_NAME_LENGTH),
      "e\u0301" -> Const.Msg.Err.VALUE_TOO_SHORT.format(Api.Constraints.MIN_NAME_LENGTH),
      longKeyName(70) -> Const.Msg.Err.NAME_KEY_TOO_LONG
    )

    for ((name, message) <- refused) withClue(s"[$name]: ") {
      refusesName(Api.Field.Person.NAME, message)(testApp.service.person.updateName(bob, name))
      refusesName(Api.Field.Person.NAME, message)(testApp.service.person.moveFace(face.persistedId, FaceTarget.Named(name)))
    }

    testApp.service.person.getById(bob.persistedId).name shouldBe Some("Bob")
    testApp.service.person.getFaceById(face.persistedId).personId shouldBe unnamed.id
  }

  test("Adding a named person stores its name in NFC and its name key as sort name") {

    /**
     * Setup:
     *
     * A Person added with the name "ÉLODIE" spelled with a combining accent (E + U+0301), and an unnamed Person.
     *
     * Assertions:
     *
     * The named row holds the precomposed name and the name key "élodie" as sort name, and the returned model the precomposed
     * name. The unnamed row's sort name keeps its zero-padded "unknown 0042" form.
     */
    val named: Person = testApp.service.person.addPerson(Person(name = Some(decomposed("ÉLODIE"))))
    named.name shouldBe Some(precomposed("ÉLODIE"))

    val unnamed: Person = testApp.service.person.addPerson(Person())

    val namedRow = personRow(named.persistedId)
    namedRow("name") shouldBe precomposed("ÉLODIE")
    namedRow("name_for_sort") shouldBe precomposed("élodie")

    val unnamedRow = personRow(unnamed.persistedId)
    withClue(unnamedRow)("unknown \\d{4,}".r.matches(unnamedRow("name_for_sort").toString) shouldBe true)
  }

  test("The name key folds case the same whatever the JVM's default locale") {

    /**
     * Setup:
     *
     * The JVM's default locale set to Turkish, whose lowercase of "I" is a dotless ı, for the length of the test.
     *
     * Assertions:
     *
     * The name key of "INGRID" is "ingrid".
     */
    withDefaultLocale(Locale.forLanguageTag("tr")) {
      PersonService.nameKey("INGRID") shouldBe "ingrid"
    }
  }

  test("The live person holding a name is found by it, hidden or not") {

    /**
     * Setup:
     *
     * A hidden Person named "Tessa" and a visible Person named "Bob".
     *
     * Assertions:
     *
     * Looking up "tessa" finds the hidden Tessa, and "BOB" finds Bob; a name no one holds finds no one.
     */
    val tessa: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))
    testApp.service.person.setVisibility(tessa, isHidden = true)
    val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))

    val holder = testApp.service.person.getLiveByNameKey("tessa")
    holder.map(_.persistedId) shouldBe Some(tessa.persistedId)
    holder.map(_.isHidden) shouldBe Some(true)
    testApp.service.person.getLiveByNameKey("BOB").map(_.persistedId) shouldBe Some(bob.persistedId)
    testApp.service.person.getLiveByNameKey("Cara") shouldBe None
  }

  test("A merged-away or bad-match person is not found by its name") {

    /**
     * Setup:
     *
     * Persons named "London" and "Tessa" with one test Face each, Tessa merged into London, and a Person named "Bob" marked as a
     * bad match.
     *
     * Assertions:
     *
     * Looking up "Tessa" or "Bob" finds no one, while "London" finds the merged Person.
     */
    val london: Person = testApp.service.person.addPerson(Person(name = Some("London")))
    testContext.addTestFacesAndAssets(london)
    val tessa: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))
    testContext.addTestFacesAndAssets(tessa)
    testApp.service.person.merge(dest = london, source = tessa)

    val bob: Person = testApp.service.person.addPerson(Person(name = Some("Bob")))
    testApp.service.person.markAsBadMatch(bob)

    testApp.service.person.getLiveByNameKey("Tessa") shouldBe None
    testApp.service.person.getLiveByNameKey("Bob") shouldBe None
    testApp.service.person.getLiveByNameKey("London").map(_.persistedId) shouldBe Some(london.persistedId)
  }

  test("The live person holding a name is found by it in another case and Unicode form") {

    /**
     * Setup:
     *
     * A Person named "Élodie", its É precomposed (U+00C9).
     *
     * Assertions:
     *
     * Looking up "ÉLODIE" decomposed (E + U+0301) finds her, and so does "élodie" precomposed.
     */
    val elodie: Person = testApp.service.person.addPerson(Person(name = Some(precomposed("Élodie"))))

    testApp.service.person.getLiveByNameKey(decomposed("ÉLODIE")).map(_.persistedId) shouldBe Some(elodie.persistedId)
    testApp.service.person.getLiveByNameKey(precomposed("élodie")).map(_.persistedId) shouldBe Some(elodie.persistedId)
  }

  test("A person merged away after taking the cover face of one merged into them leaves two merged-away people with one cover") {

    /**
     * Setup:
     *
     * Three Persons with one test Face each. The first is merged into the second, the second takes the first's cover Face as its
     * own, and the second is then merged into the third.
     *
     * Assertions:
     *
     * Both merged-away rows keep the same cover face ID, which the cover face index allows because it covers only live people.
     *
     * Edge cases:
     *
     * Two soft-deleted people sharing one cover Face.
     */
    val first: Person = testApp.service.person.addPerson(Person())
    val second: Person = testApp.service.person.addPerson(Person())
    val third: Person = testApp.service.person.addPerson(Person())
    List(first, second, third).foreach(testContext.addTestFacesAndAssets(_))
    val firstCover = testApp.service.person.getById(first.persistedId).coverFaceId.value

    testApp.service.person.merge(dest = second, source = first)
    testApp.service.person.setFaceAsCover(second.persistedId, firstCover)
    testApp.service.person.merge(dest = third, source = testApp.service.person.getById(second.persistedId))

    val mergedAway = testApp.txManager.asReadOnly {
      query("SELECT cover_face_id FROM person WHERE id IN (?, ?) AND is_deleted = ?", first.persistedId, second.persistedId, true)
    }
    mergedAway.map(_("cover_face_id").toString.trim) shouldBe List(firstCover, firstCover)
  }

  test("A named Person with no cover takes the cover of a Person merged into them, or their best Face when it is about to go") {

    /**
     * Setup:
     *
     * Two named Persons with no Face, so no cover. Two unnamed Persons, each with Faces on three assets of their own: the first
     * at detection scores of 0.5 (their cover), 0.9 and 0.7; the second at 0.9 (their cover), 0.5 and 0.7, the cover's asset
     * recycled and marked for purging. The first unnamed Person is merged into the first named one, the second into the second.
     *
     * Assertions:
     *
     * The first named Person's cover is the merged Person's cover, though a Face of better score came with it. The second named
     * Person's cover is their best Face on an asset not marked for purging, the one at 0.7. The merge answers each Person with
     * the cover stored.
     *
     * Edge cases:
     *
     * The merged Person's cover is on an asset marked for purging, which a cover never rests on.
     */
    def withFaces(scores: List[Double], firstVector: Int): (Person, List[Face]) = {
      val added = testApp.service.person.addPerson(Person())
      val faces = scores.zipWithIndex.map {
        case (score, n) =>
          testContext.addTestFace(
            testApp.service.person.getById(added.persistedId),
            testContext.insertAsset(),
            TestContext.unitVector(firstVector + n),
            detectionScore = score)
      }
      (testApp.service.person.getById(added.persistedId), faces)
    }

    val first: Person = testApp.service.person.addPerson(Person(name = Some("Dana")))
    val second: Person = testApp.service.person.addPerson(Person(name = Some("Ezra")))
    List(first, second).foreach(person => testApp.service.person.getById(person.persistedId).coverFaceId shouldBe None)

    val (firstSource, firstFaces) = withFaces(List(0.5, 0.9, 0.7), firstVector = 1)
    firstSource.coverFaceId shouldBe Some(firstFaces.head.persistedId)
    val (secondSource, secondFaces) = withFaces(List(0.9, 0.5, 0.7), firstVector = 4)
    secondSource.coverFaceId shouldBe Some(secondFaces.head.persistedId)
    val purging = secondFaces.head.assetId.get
    testApp.service.library.recycleAssets(Set(purging))
    testApp.service.asset.updateById(purging, Map(FieldConst.Asset.IS_PURGED -> true))

    testApp.service.person.merge(dest = first, source = firstSource).coverFaceId shouldBe Some(firstFaces.head.persistedId)
    testApp.service.person.getById(first.persistedId).coverFaceId shouldBe Some(firstFaces.head.persistedId)

    testApp.service.person.merge(dest = second, source = secondSource).coverFaceId shouldBe Some(secondFaces(2).persistedId)
    testApp.service.person.getById(second.persistedId).coverFaceId shouldBe Some(secondFaces(2).persistedId)
  }

  test("Listing the live people reads a partial index over them") {

    /**
     * Setup:
     *
     * A lookup on the person table with the predicates of `PersonDao.getAll`: the repository, a non-zero face count and not
     * deleted.
     *
     * Assertions:
     *
     * The engine's plan reads one of the partial indexes over the live people, `person_03`, or `person_02` when PostgreSQL plans
     * over a handful of rows.
     */
    val engine = searchDialect
    import engine.dialect.*
    val repositoryId = RequestContext.getRepository.persistedId

    // The predicates of PersonDao.getAll, written as it writes them; no other index serves them
    val plan =
      lookupPlanOf(sql"SELECT id FROM person WHERE repository_id = $repositoryId AND num_of_faces > 0 AND is_deleted = FALSE")

    // person_03 serves it; over a handful of rows PostgreSQL may scan person_02 instead, also partial on the live people
    withClue(plan)("person_0[23]".r.findFirstIn(plan).isDefined shouldBe true)
  }

  test("Merging of people in the same asset results in correct face counts") {

    /**
     * Setup:
     *
     * An import of `people/movies-speed.png`, which starts two People with one Face each in the same asset; the second is merged
     * into the first. The asset is then recycled, and restored.
     *
     * Assertions:
     *
     * The merged person's face count is one, not two: the count is the number of assets outside the trash on which the person has
     * a Face, and both Faces are in one asset. Recycling the asset takes the count to zero, not below it, and restoring it brings
     * the count back to one, the two Faces still stored throughout.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/movies-speed.png")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    // two people  - one asset
    val faces = testApp.service.faceDetection.extractFaces(importAsset.bytes)
    faces.size should be(2)

    val people = testApp.service.person.getPeopleForAsset(importedAsset.persistedId)
    people.size should be(2)
    people.head.numOfFaces should be(1)
    people.last.numOfFaces should be(1)

    // merge the two people
    val mergedPerson: Person = testApp.service.person.merge(dest = people.head, source = people.last)

    /**
     * After the merge, there should be still one asset and one person with one asset. This is technically not a real scenario -
     * except for twins, one person cannot be in the image twice, but the use case is real. As same person gets merged into
     * themselves across assets, the face count should not drift and reflect the actual search results
     */
    val mergedIntoPerson = testApp.service.person.getById(mergedPerson.persistedId)
    // yes, two faces in DB, but only one asset will be returned when you query for the person
    mergedIntoPerson.numOfFaces should be(1)

    // The asset's two Faces are one asset of the person: recycling takes one from the count, restoring gives one back
    testApp.service.library.recycleAssets(Set(importedAsset.persistedId))
    testApp.service.person.getById(mergedPerson.persistedId).numOfFaces should be(0)

    testApp.service.library.restoreRecycledAssets(Set(importedAsset.persistedId))
    testApp.service.person.getById(mergedPerson.persistedId).numOfFaces should be(1)
    testApp.service.person.getAssetFaces(importedAsset.persistedId).size should be(2)
  }

  test("Known person keeps the same when merged into Unknown") {

    /**
     * Setup:
     *
     * An unnamed Person A and a Person B named "London", each with one test Face; B is merged into A, so the destination is not
     * named.
     *
     * Assertions:
     *
     * A takes B's name, since only the source was named, and is named in full: the returned model and the stored row are named,
     * the row's sort name is the name key "london", and A is in the Matched bucket with its two Faces, below the face threshold,
     * so by its name alone. A Search for "London" finds A.
     */
    val personA: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(personA)

    val mergedFromName = "London"
    val personB: Person = testApp.service.person.addPerson(Person(name = Some(mergedFromName)))
    testContext.addTestFacesAndAssets(personB)

    val returned: Person = testApp.service.person.merge(dest = personA, source = personB)
    returned.name shouldBe Some(mergedFromName)
    returned.isNamed shouldBe true

    val mergedPerson: Person = testApp.service.person.getById(personA.persistedId)

    // should inherit the known person name
    mergedPerson.name.get should be(mergedFromName)
    mergedPerson.isNamed shouldBe true
    mergedPerson.numOfFaces shouldBe 2
    mergedPerson.numOfFaces should be < FaceRecognition.MIN_FACES_THRESHOLD

    val row = personRow(personA.persistedId)
    row("name_for_sort") shouldBe "london"

    testApp.service.person.getInBuckets(Set(PeopleBucket.MATCHED)).map(_.persistedId) shouldBe List(personA.persistedId)

    val resolved = testApp.service.search.resolveText(SearchText.parse(mergedFromName).value)
    resolved.groups.head.alternatives.head.ids.get(SearchSource.Person) shouldBe Some(Set(personA.persistedId))
  }

  test("A named person merged into keeps its own name, name key and flag") {

    /**
     * Setup:
     *
     * Persons named "London" and "Phoenix" with one test Face each; Phoenix is merged into London.
     *
     * Assertions:
     *
     * The returned model and the stored row keep London's name and flag, and the row its name key "london" as sort name.
     */
    val london: Person = testApp.service.person.addPerson(Person(name = Some("London")))
    testContext.addTestFacesAndAssets(london)
    val phoenix: Person = testApp.service.person.addPerson(Person(name = Some("Phoenix")))
    testContext.addTestFacesAndAssets(phoenix)

    val returned: Person = testApp.service.person.merge(dest = london, source = phoenix)
    returned.name shouldBe Some("London")
    returned.isNamed shouldBe true

    val row = personRow(london.persistedId)
    row("name") shouldBe "London"
    row("name_for_sort") shouldBe "london"
    testApp.service.person.getById(london.persistedId).isNamed shouldBe true
  }

  test("A merge decides the merged name from the stored people, not from the models passed in") {

    /**
     * Setup:
     *
     * Two pairs of Persons with one test Face each, every model read while its Person was unnamed: Person B renamed "Tessa" after
     * its model was read, merged into the unnamed A; Person C renamed "London" after its model was read, merged into by the
     * unnamed D.
     *
     * Assertions:
     *
     * A takes the name "Tessa", which its stale source model does not carry. C keeps its name "London" and its name key, which
     * its stale destination model does not show, and does not take D's "Unknown N" name.
     *
     * Edge cases:
     *
     * A caller's model older than the row, on either side of the merge.
     */
    val personA: Person = testApp.service.person.addPerson(Person())
    val staleB: Person = testApp.service.person.addPerson(Person())
    List(personA, staleB).foreach(testContext.addTestFacesAndAssets(_))
    testApp.service.person.updateName(staleB, "Tessa")

    val mergedA: Person = testApp.service.person.merge(dest = personA, source = staleB)
    mergedA.name shouldBe Some("Tessa")
    mergedA.isNamed shouldBe true
    val rowA = personRow(personA.persistedId)
    rowA("name") shouldBe "Tessa"
    rowA("name_for_sort") shouldBe "tessa"

    val staleC: Person = testApp.service.person.addPerson(Person())
    val personD: Person = testApp.service.person.addPerson(Person())
    List(staleC, personD).foreach(testContext.addTestFacesAndAssets(_))
    testApp.service.person.updateName(staleC, "London")

    val mergedC: Person = testApp.service.person.merge(dest = staleC, source = personD)
    mergedC.name shouldBe Some("London")
    mergedC.isNamed shouldBe true
    val rowC = personRow(staleC.persistedId)
    rowC("name") shouldBe "London"
    rowC("name_for_sort") shouldBe "london"
  }

  test("A stale merge takes no name from a source that is no longer live") {

    /**
     * Setup:
     *
     * "Tessa", merged away into "London", and a new live "Tessa" holding the name since; "Phoenix", marked a bad match; two
     * unnamed Persons. Each unnamed Person is the destination of a stale merge of one of the two sources.
     *
     * Assertions:
     *
     * Neither merge raises an error, and each destination stays unnamed in the returned model and on its row: its sort name keeps
     * the unnamed form and `is_named` stays false.
     *
     * Edge cases:
     *
     * The merged-away source's name held by another live Person, which the destination could not take without breaking
     * `person_01`.
     */
    val london: Person = testApp.service.person.addPerson(Person(name = Some("London")))
    val mergedAway: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))
    val badMatch: Person = testApp.service.person.addPerson(Person(name = Some("Phoenix")))
    val intoMergedAway: Person = testApp.service.person.addPerson(Person())
    val intoBadMatch: Person = testApp.service.person.addPerson(Person())
    List(london, mergedAway, badMatch, intoMergedAway, intoBadMatch).foreach(testContext.addTestFacesAndAssets(_))

    testApp.service.person.merge(dest = london, source = mergedAway)
    testApp.service.person.addPerson(Person(name = Some("Tessa")))
    testApp.service.person.markAsBadMatch(badMatch)

    List(intoMergedAway -> mergedAway, intoBadMatch -> badMatch).foreach {
      case (dest, source) =>
        val returned: Person = testApp.service.person.merge(dest = dest, source = source)
        withClue(source.name)(returned.isNamed shouldBe false)
        returned.name shouldBe dest.name

        val row = personRow(dest.persistedId)
        row("name") shouldBe dest.name.get
        withClue(row)("unknown \\d{4,}".r.matches(row("name_for_sort").toString) shouldBe true)
        testApp.service.person.getById(dest.persistedId).isNamed shouldBe false
    }
  }

  test("A merge waits for a concurrent merge of its source, and then takes no name from it") {

    /**
     * Setup:
     *
     * A Person named "Tessa" and two unnamed Persons. On PostgreSQL the second merge of Tessa is held at the first's row lock
     * until the first commits; on SQLite the write connection serializes them.
     *
     * Assertions:
     *
     * The first destination takes the name, and the second, which merged a source already merged away by the time it read the
     * rows, stays unnamed.
     */
    val tessa: Person = testApp.service.person.addPerson(Person(name = Some("Tessa")))
    val firstDest: Person = testApp.service.person.addPerson(Person())
    val secondDest: Person = testApp.service.person.addPerson(Person())
    List(tessa, firstDest, secondDest).foreach(testContext.addTestFacesAndAssets(_))

    contend(
      testApp.service.person.merge(dest = firstDest, source = tessa),
      testApp.service.person.merge(dest = secondDest, source = tessa)
    )

    testApp.service.person.getById(firstDest.persistedId).name shouldBe Some("Tessa")
    testApp.service.person.getById(secondDest.persistedId).name shouldBe secondDest.name
  }

  test("Same person can appear in the same image more than once") {

    /**
     * Setup:
     *
     * An import of `people/twins.png`, the same person twice in one image.
     *
     * Assertions:
     *
     * The asset has one Person with two Faces: the second face matches the first, stored just before it. The person's face count
     * is one, the one asset they are found on, not the two Faces.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/twins.png")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val people = testApp.service.person.getPeopleForAsset(importedAsset.persistedId)

    people.length should be(1)
    people.head.numOfFaces should be(1)
    testApp.service.person.getAssetFaces(importedAsset.persistedId).size should be(2)
  }

  test("Hidden person should not be returned with bulk retrieval") {

    /**
     * Setup:
     *
     * Two Persons with six test Faces each, both Matched; one is then hidden.
     *
     * Assertions:
     *
     * Listing the Matched bucket returns both before hiding and only the visible one after.
     */
    val person: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person, 6)

    val hiddenPerson: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(hiddenPerson, 6)

    testApp.service.person.getInBuckets(Set(PeopleBucket.MATCHED)).size should be(2)
    testApp.service.person.setVisibility(hiddenPerson, isHidden = true)
    testApp.service.person.getInBuckets(Set(PeopleBucket.MATCHED)).size should be(1)
  }

  test("A hidden person is in the Hidden bucket alone") {

    /**
     * Setup:
     *
     * A hidden unnamed Person with two test Faces, below the face threshold, and a hidden named Person with six.
     *
     * Assertions:
     *
     * Both are listed by the Hidden bucket and by neither the Unmatched nor the Matched bucket, whatever their count or name.
     */
    val fewFaces: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(fewFaces, 2)
    testApp.service.person.setVisibility(fewFaces, isHidden = true)

    val named: Person = testApp.service.person.addPerson(Person(name = Some("Hidden Harry")))
    testContext.addTestFacesAndAssets(named, 6)
    testApp.service.person.setVisibility(named, isHidden = true)

    def ids(buckets: Set[String]): Set[String] = testApp.service.person.getInBuckets(buckets).map(_.persistedId).toSet

    ids(Set(PeopleBucket.HIDDEN)) should be(Set(fewFaces.persistedId, named.persistedId))
    ids(Set(PeopleBucket.UNMATCHED)) shouldBe empty
    ids(Set(PeopleBucket.MATCHED)) shouldBe empty
  }

  test("A named person is Matched whatever the face count") {

    /**
     * Setup:
     *
     * A named Person with one test Face, below the face threshold.
     *
     * Assertions:
     *
     * The person is listed by the Matched bucket and not by the Unmatched one.
     */
    val person: Person = testApp.service.person.addPerson(Person(name = Some("One Face")))
    testContext.addTestFacesAndAssets(person, 1)

    testApp.service.person.getInBuckets(Set(PeopleBucket.MATCHED)).map(_.persistedId) should be(List(person.persistedId))
    testApp.service.person.getInBuckets(Set(PeopleBucket.UNMATCHED)) shouldBe empty
  }

  test("An unnamed person below the face threshold is Unmatched") {

    /**
     * Setup:
     *
     * An unnamed Person with two test Faces, below the face threshold.
     *
     * Assertions:
     *
     * The person is listed by the Unmatched bucket and not by the Matched one.
     */
    val person: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person, 2)

    testApp.service.person.getInBuckets(Set(PeopleBucket.UNMATCHED)).map(_.persistedId) should be(List(person.persistedId))
    testApp.service.person.getInBuckets(Set(PeopleBucket.MATCHED)) shouldBe empty
  }

  test("A discarded person is in no bucket") {

    /**
     * Setup:
     *
     * A Person with six test Faces, marked as a bad match.
     *
     * Assertions:
     *
     * Every bucket together lists no one.
     */
    val person: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person, 6)
    testApp.service.person.markAsBadMatch(person)

    testApp.service.person.getInBuckets(PeopleBucket.ALL) shouldBe empty
  }

  test("Every bucket together lists everyone but the discarded") {

    /**
     * Setup:
     *
     * Four People with test Faces: a Matched one (six Faces), an Unmatched one (two), a Hidden one (two), and one marked as a bad
     * match (six).
     *
     * Assertions:
     *
     * The three buckets together list the same people as the plain listing less the bad match; no bucket at all lists no one.
     */
    val matched: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(matched, 6)

    val unmatched: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(unmatched, 2)

    val hidden: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(hidden, 2)
    testApp.service.person.setVisibility(hidden, isHidden = true)

    val badMatch: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(badMatch, 6)
    testApp.service.person.markAsBadMatch(badMatch)

    val everyone: Set[String] = testApp.service.person.getAll.map(_.persistedId).toSet
    everyone should be(Set(matched, unmatched, hidden, badMatch).map(_.persistedId))

    val inBuckets: Set[String] = testApp.service.person.getInBuckets(PeopleBucket.ALL).map(_.persistedId).toSet
    inBuckets should be(everyone - badMatch.persistedId)

    testApp.service.person.getInBuckets(Set()) shouldBe empty
  }

  test("An unknown bucket name is refused") {

    /**
     * Setup:
     *
     * No people; a listing asked for the Matched bucket and a name that is no bucket.
     *
     * Assertions:
     *
     * The listing is an `IllegalArgumentException`.
     */
    intercept[IllegalArgumentException](testApp.service.person.getInBuckets(Set(PeopleBucket.MATCHED, "verified")))
  }

  private def listedInBuckets(showTriaged: Boolean, buckets: Set[String] = PeopleBucket.ALL) =
    testApp.service.person.getListedInBuckets(buckets, showTriaged).people

  private def listedCounts(showTriaged: Boolean): Map[String, Int] =
    listedInBuckets(showTriaged).map(listed => listed.person.persistedId -> listed.visibleCount).toMap

  /** What opening the Person from the repository view finds with triaged items hidden: the search `SearchRequestParser` builds */
  private def foundInRepositoryView(person: Person): Int = {
    val scope = SearchRequestParser
      .parse(SearchRequestParser.Params(personId = Some(person.persistedId)))
      .getOrElse(fail("The repository view of a Person is a valid scope"))
    testApp.service.library.count(scope.query())
  }

  private def markAsStillImporting(asset: Asset): Unit = testApp.txManager.withTransaction {
    update("UPDATE asset SET is_pipeline_processed = FALSE WHERE id = ?", asset.persistedId)
  }

  private def assetWithFaces(person: Person, isTriaged: Boolean, faces: Int = 1): Asset = {
    val asset = testContext.persistAsset(isTriaged = isTriaged)
    (1 to faces).foreach(_ => testApp.service.person.addFace(makeFace(), asset, person))
    asset
  }

  test("With triaged items hidden, each listed count is what a search of the repository view finds") {

    /**
     * Setup:
     *
     * A Person with Faces on two sorted assets, one of them with two Faces of that Person, on two triaged assets, again one with
     * two Faces, on a triaged asset that was then recycled, and on a triaged asset still being imported. A second Person with one
     * sorted and one triaged asset.
     *
     * Assertions:
     *
     * With triaged items hidden, each Person's listed count equals `library.count` for the repository view of that Person (2 and
     * 1). The listed Person's `numOfFaces` keeps the whole count (5), so the number never reaches what `isMatched` reads.
     *
     * Edge cases:
     *
     * Two Faces on one asset count that asset once, an asset recycled from triage is in neither number, and an asset still being
     * imported, which `numOfFaces` holds and a search skips, is subtracted.
     */
    val person: Person = testApp.service.person.addPerson(Person())
    assetWithFaces(person, isTriaged = false)
    assetWithFaces(person, isTriaged = false, faces = 2)
    assetWithFaces(person, isTriaged = true)
    assetWithFaces(person, isTriaged = true, faces = 2)
    val recycledFromTriage = assetWithFaces(person, isTriaged = true)
    testApp.service.library.recycleAssets(Set(recycledFromTriage.persistedId))
    markAsStillImporting(assetWithFaces(person, isTriaged = true))

    val other: Person = testApp.service.person.addPerson(Person())
    assetWithFaces(other, isTriaged = false)
    assetWithFaces(other, isTriaged = true)

    foundInRepositoryView(person) shouldBe 2
    foundInRepositoryView(other) shouldBe 1

    val listed = listedInBuckets(showTriaged = false)
    listed.map(entry => entry.person.persistedId -> entry.visibleCount).toMap shouldBe
      Map(person.persistedId -> 2, other.persistedId -> 1)
    listed.find(_.person.persistedId == person.persistedId).value.person.numOfFaces shouldBe 5
  }

  test("With triaged items shown, each listed count is the whole count") {

    /**
     * Setup:
     *
     * A Person with one sorted and two triaged assets.
     *
     * Assertions:
     *
     * With triaged items shown, the listed count is the Person's `numOfFaces` (3).
     */
    val person: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person, 1)
    testContext.addTestFacesAndAssets(person, 2, isTriaged = true)

    val listed = listedInBuckets(showTriaged = true)
    listed.map(_.visibleCount) shouldBe List(3)
    listed.head.person.numOfFaces shouldBe 3
  }

  test("A Person with only triaged assets is listed only with triaged items shown") {

    /**
     * Setup:
     *
     * A Person with Faces on two triaged assets and nothing else.
     *
     * Assertions:
     *
     * With triaged items hidden the Person is left off every bucket; with them shown the Person is listed with a count of 2.
     */
    val person: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person, 2, isTriaged = true)

    listedInBuckets(showTriaged = false) shouldBe empty
    listedCounts(showTriaged = true) shouldBe Map(person.persistedId -> 2)
  }

  test("The switch moves no Person between buckets and keeps the order") {

    /**
     * Setup:
     *
     * An unnamed Person on three assets, two of them triaged, so Matched on the whole count and 1 below the threshold without the
     * triaged ones. An unnamed Person on two assets, one of them triaged, so Unmatched. Two named People, Zoe and Adam, one with
     * a triaged asset, added in that order.
     *
     * Assertions:
     *
     * Matched lists Adam, Zoe and the unnamed Person in that order, and Unmatched the other unnamed Person, with triaged items
     * hidden and shown alike, which is also the order `getInBuckets` gives.
     */
    val matchedOnWholeCount: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(matchedOnWholeCount, 1)
    testContext.addTestFacesAndAssets(matchedOnWholeCount, 2, isTriaged = true)

    val unmatched: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(unmatched, 1)
    testContext.addTestFacesAndAssets(unmatched, 1, isTriaged = true)

    val zoe: Person = testApp.service.person.addPerson(Person(name = Some("Zoe")))
    testContext.addTestFacesAndAssets(zoe, 1)
    testContext.addTestFacesAndAssets(zoe, 1, isTriaged = true)

    val adam: Person = testApp.service.person.addPerson(Person(name = Some("Adam")))
    testContext.addTestFacesAndAssets(adam, 1)

    def ids(bucket: String, showTriaged: Boolean): List[String] =
      listedInBuckets(showTriaged, Set(bucket)).map(_.person.persistedId)

    val matchedOrder = List(adam, zoe, matchedOnWholeCount).map(_.persistedId)
    val unmatchedOrder = List(unmatched.persistedId)

    testApp.service.person.getInBuckets(Set(PeopleBucket.MATCHED)).map(_.persistedId) shouldBe matchedOrder
    List(true, false).foreach {
      showTriaged =>
        withClue(s"showTriaged = $showTriaged: ") {
          ids(PeopleBucket.MATCHED, showTriaged) shouldBe matchedOrder
          ids(PeopleBucket.UNMATCHED, showTriaged) shouldBe unmatchedOrder
        }
    }
  }

  test("After a merge during an import the Person is left off and no count is negative") {

    /**
     * Setup:
     *
     * A destination Person with one sorted asset and a source Person with two triaged assets still being imported, merged into
     * the destination. The merge recounts through a search, which skips the assets being imported, so `numOfFaces` becomes 1
     * while two triaged assets carry that Person's Faces.
     *
     * Assertions:
     *
     * With triaged items hidden the difference is below zero, and the Person is left off; with them shown the Person reads
     * `numOfFaces`.
     *
     * Edge cases:
     *
     * No listed count is negative in either state.
     */
    val dest: Person = testApp.service.person.addPerson(Person())
    assetWithFaces(dest, isTriaged = false)
    val source: Person = testApp.service.person.addPerson(Person())
    markAsStillImporting(assetWithFaces(source, isTriaged = true))
    markAsStillImporting(assetWithFaces(source, isTriaged = true))

    testApp.service.person.merge(dest = dest, source = source)
    testApp.service.person.getById(dest.persistedId).numOfFaces shouldBe 1

    val hidden = listedInBuckets(showTriaged = false)
    hidden.map(_.person.persistedId) should not contain dest.persistedId
    hidden.foreach(_.visibleCount should be > 0)

    listedCounts(showTriaged = true) shouldBe Map(dest.persistedId -> 1)
  }

  test("The triaged assets per person are counted from a partial index over the triaged assets", Slow) {

    /**
     * Setup:
     *
     * A triaged asset with one Face, seeded into 5,000 copies of which one in ten stay triaged, each copy with a Face of the same
     * Person, analyzed and rolled back once the plan is read, and the statement behind `getTriagedAssetCounts`. With only a
     * handful of Face rows the planner would start from `face`.
     *
     * Assertions:
     *
     * The engine's plan reads one of the `asset_triage_` partial indexes, so the read follows the size of the triage set and not
     * of the library.
     */
    val template = testContext.persistAsset(isTriaged = true)
    val person: Person = testApp.service.person.addPerson(Person())
    testApp.service.person.addFace(makeFace(), template, person)
    val (sql, values) = testApp.DAO.person.asInstanceOf[PersonDao].triagedAssetCountsStatement

    val plan = atScale {
      seedCopies(template.persistedId, copies = 5000, folders = 1)
      // Every copy whose zero-padded number does not end in 0 is sorted, so the triage set is a tenth of the library
      update("UPDATE asset SET is_triaged = FALSE WHERE id <> ? AND substr(id, 36, 1) <> '0'", template.persistedId)
      // A Face per copy, taken from the template's, none enrolled, which keeps them out of PostgreSQL's vector index; the copies
      // alone, as the bundle's database holds every test's repositories
      update(
        """INSERT INTO face (id, repository_id, asset_id, person_id, x1, y1, width, height, detection_score, features, checksum,
          |                  quality, is_enrolled)
          |SELECT a.id, a.repository_id, a.id, t.person_id, t.x1, t.y1, t.width, t.height, t.detection_score, t.features, a.checksum,
          |       t.quality, FALSE
          |  FROM asset a CROSS JOIN face t
          | WHERE t.asset_id = ? AND a.id <> t.asset_id AND a.repository_id = t.repository_id""".stripMargin,
        template.persistedId
      )
    }(lookupPlanOf(sql, values))

    withClue(plan)(plan should include("asset_triage_"))
  }

  test("Hidden person is not returned for an asset") {

    /**
     * Setup:
     *
     * An import of `people/meme-ben.jpg`, which starts one Person; that person is then hidden.
     *
     * Assertions:
     *
     * The asset lists its Person before hiding and no one after.
     */
    val importAsset1 = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val asset = testApp.service.library.addImportAsset(importAsset1)

    var people = testApp.service.person.getPeopleForAsset(asset.persistedId)
    people.size should be(1)

    testApp.service.person.setVisibility(people.head, isHidden = true)
    people = testApp.service.person.getPeopleForAsset(asset.persistedId)
    people.size should be(0)
  }

  test("Recycled asset should not count toward face count") {

    /**
     * Setup:
     *
     * Two Persons sharing five assets, each asset with one test Face of each; two of the assets are recycled.
     *
     * Assertions:
     *
     * The first person's face count drops from five to three on recycling, while all five of their Faces stay stored until a
     * purge.
     */
    val totalAssets = 5
    val people = List.fill(2)(testApp.service.person.addPerson(Person()))
    var person: Person = people.head

    testContext.addTestFacesAndAssets(people, assetCount = totalAssets)

    person = testApp.service.person.getById(person.persistedId)
    person.numOfFaces should be(totalAssets)

    val allAssets: List[Asset] = testApp.service.asset.query(new Query()).records

    // recycle some assets
    val recycleCount = 2
    val recycledAssetIds = allAssets.take(recycleCount).map(_.persistedId).toSet
    testApp.service.library.recycleAssets(recycledAssetIds)

    person = testApp.service.person.getById(person.persistedId)
    person.numOfFaces should be(totalAssets - recycleCount)

    // The faces are still in DB, but they are not counted toward the person,
    // as they are in the trash bin until being Purged.
    testApp.service.person.getPersonFaces(person.persistedId).length should be(totalAssets)
  }

  test("Restored asset should restore person face counts") {

    /**
     * Setup:
     *
     * Two Persons sharing five assets, each asset with one test Face of each; two of the assets are recycled, then restored.
     *
     * Assertions:
     *
     * The first person's face count drops from five to three on recycling and returns to five on restore.
     */
    val totalAssets = 5
    val people = List.fill(2)(testApp.service.person.addPerson(Person()))
    var person: Person = people.head
    testContext.addTestFacesAndAssets(people, assetCount = totalAssets)

    person = testApp.service.person.getById(person.persistedId)
    person.numOfFaces should be(totalAssets)

    val allAssets: List[Asset] = testApp.service.asset.query(new Query()).records

    // recycle some assets
    val recycleCount = 2
    val recycledAssetIds = allAssets.take(recycleCount).map(_.persistedId).toSet
    testApp.service.library.recycleAssets(recycledAssetIds)

    person = testApp.service.person.getById(person.persistedId)
    person.numOfFaces should be(totalAssets - recycleCount)

    // restore the recycled assets
    testApp.service.library.restoreRecycledAssets(recycledAssetIds)

    // face counts should be back to the original number
    person = testApp.service.person.getById(person.persistedId)
    person.numOfFaces should be(totalAssets)
  }

  test("Moving triaged asset to a folder does not change person face counts") {

    /**
     * Setup:
     *
     * A Person with one Face on a triaged asset, which is then moved to the repository's root folder.
     *
     * Assertions:
     *
     * The person's face count is one before the move and still one after.
     */
    val person: Person = testApp.service.person.addPerson(Person())
    val triagedAsset: Asset = testContext.persistAsset(isTriaged = true)

    val face = Face(
      id = Some(Util.randomStr(32)),
      x1 = 10,
      y1 = 10,
      width = 30,
      height = 30,
      assetId = Some(triagedAsset.persistedId),
      personId = Some(person.persistedId),
      personLabel = Some(1),
      detectionScore = 0.99,
      checksum = TestContext.randomChecksum,
      features = Array.fill(512)(0.1f),
      quality = 20.0,
      isEnrolled = true
    )

    testApp.service.person.addFace(face, triagedAsset, person)

    var persistedPerson = testApp.service.person.getById(person.persistedId)
    persistedPerson.numOfFaces shouldBe 1

    testApp.service.library.moveAssetsToFolder(Set(triagedAsset.persistedId), testContext.repository.rootFolderId)

    persistedPerson = testApp.service.person.getById(person.persistedId)
    persistedPerson.numOfFaces shouldBe 1
  }

  test("Incrementing a counter opens its own transaction") {

    /**
     * Setup:
     *
     * A new Person whose face count is incremented with no transaction around the call.
     *
     * Assertions:
     *
     * The increment is committed: the person reads back with one face.
     */
    val person = testApp.service.person.addPerson(Person())
    testApp.service.person.increment(person.persistedId, FieldConst.Person.NUM_OF_FACES)
    testApp.service.person.getPersonById(person.persistedId).numOfFaces shouldBe 1
  }

  test("An asset's faces are read with their people") {

    /**
     * Setup:
     *
     * An import of `people/movies-speed.png`, a still with two faces, and a second asset with no faces.
     *
     * Assertions:
     *
     * Each of the first asset's two Faces comes paired with the Person it belongs to, and the faceless asset yields nothing.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/movies-speed.png")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    val facesWithPeople = testApp.service.person.getAssetFacesWithPeople(importedAsset.persistedId)
    facesWithPeople.size should be(2)
    facesWithPeople.foreach { case (face, person) => face.personId shouldBe Some(person.persistedId) }

    testApp.service.person.getAssetFacesWithPeople(testContext.persistAsset().persistedId) shouldBe empty
  }
}
