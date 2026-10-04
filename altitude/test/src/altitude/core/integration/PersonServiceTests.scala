package altitude.core.integration

import altitude.test.IntegrationTestUtil
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.empty
import org.scalatest.matchers.should.Matchers.{ should, shouldBe }
import scalasql.core.SqlStr.SqlStringSyntax

import scala.util.Random

import altitude.core.Altitude
import altitude.core.Const.FaceRecognition
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.util.Query
import altitude.core.util.SearchQuery
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

  private def makeFace(frameTimeMs: Option[Long] = None, quality: Double = 20.0, isEnrolled: Boolean = true): Face = Face(
    x1 = 1,
    y1 = 1,
    width = 10,
    height = 10,
    detectionScore = 0.9,
    checksum = Random.nextInt(),
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

  test("An unknown person is marked as known edit") {

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
     * The row holds the name and its lowercase sort name, both as added and after renaming to "Jerry".
     */
    val name = "Ben"
    val person: Person = testApp.service.person.addPerson(Person(name = Some(name)))
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
     * The person is below the face threshold when added and above it once the Faces are in.
     */
    val person: Person = testApp.service.person.addPerson(Person())
    person.isAboveThreshold should be(false)

    testContext.addTestFacesAndAssets(person, FaceRecognition.MIN_FACES_THRESHOLD)

    val updatedPerson: Person = testApp.service.person.getById(person.persistedId)
    updatedPerson.isAboveThreshold should be(true)
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
     * The first reads back by ID as not hidden; the second is read back without any check.
     */
    val person1Model = Person()
    val person1: Person = testApp.service.person.addPerson(person1Model)

    val retrievedPerson1: Person = testApp.service.person.getById(person1.persistedId)
    retrievedPerson1.isHidden should be(false)

    val person2Model = Person()
    val person2: Person = testApp.service.person.addPerson(person2Model)
    val retrievedPerson2: Person = testApp.service.person.getById(person2.persistedId)
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
    testApp.service.person.setFaceAsCover(second, testApp.service.person.getFaceById(firstCover))
    testApp.service.person.merge(dest = third, source = testApp.service.person.getById(second.persistedId))

    val mergedAway = testApp.txManager.asReadOnly {
      query("SELECT cover_face_id FROM person WHERE id IN (?, ?) AND is_deleted = ?", first.persistedId, second.persistedId, true)
    }
    mergedAway.map(_("cover_face_id").toString.trim) shouldBe List(firstCover, firstCover)
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
     * into the first.
     *
     * Assertions:
     *
     * The merged person's face count is one, not two: the count is recounted from a Search for the person's assets, and both
     * Faces are in one asset.
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
     * After the merge, there should be still oen asset and one person with one asset. This is technically not a real scenario -
     * except for twins, one person cannot be in the image twice, but the use case is real. As same person gets merged into
     * themselves across assets, the face count should not drift and reflect the actual search results
     */
    val mergedIntoPerson = testApp.service.person.getById(mergedPerson.persistedId)
    // yes, two faces in DB, but only one asset will be returned when you query for the person
    mergedIntoPerson.numOfFaces should be(1)
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
     * A takes B's name, since only the source was named.
     */
    val personA: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(personA)

    val mergedFromName = "London"
    val personB: Person = testApp.service.person.addPerson(Person(name = Some(mergedFromName)))
    testContext.addTestFacesAndAssets(personB)

    testApp.service.person.merge(dest = personA, source = personB)

    val mergedPerson: Person = testApp.service.person.getById(personA.persistedId)

    // should inherit the known person name
    mergedPerson.name.get should be(mergedFromName)
  }

  test("Same person can appear in the same image more than once") {

    /**
     * Setup:
     *
     * An import of `people/twins.png`, the same person twice in one image.
     *
     * Assertions:
     *
     * The asset has one Person with two Faces: the second face matches the first, stored just before it.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/twins.png")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)
    val people = testApp.service.person.getPeopleForAsset(importedAsset.persistedId)

    people.length should be(1)
    people.head.numOfFaces should be(2)
  }

  test("Hidden person should not be returned with bulk retrieval") {

    /**
     * Setup:
     *
     * Two Persons with six test Faces each, both above the face threshold; one is then hidden.
     *
     * Assertions:
     *
     * Listing the people above the threshold returns both before hiding and only the visible one after.
     */
    val person: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person, 6)

    val hiddenPerson: Person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(hiddenPerson, 6)

    testApp.service.person.getAllAboveThreshold.size should be(2)
    testApp.service.person.setVisibility(hiddenPerson, isHidden = true)
    testApp.service.person.getAllAboveThreshold.size should be(1)
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
      checksum = Random.nextInt(),
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
