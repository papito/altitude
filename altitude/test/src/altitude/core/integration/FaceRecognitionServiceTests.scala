package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestContext
import altitude.test.TestVideos
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import scala.jdk.CollectionConverters.*

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.dao.postgres.FaceDao as PostgresFaceDao
import altitude.core.dao.postgres.PostgresOverrides
import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.DetectedFaces
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.models.Settings
import altitude.core.service.FaceRecognitionService

@DoNotDiscover class FaceRecognitionServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Recognize a person") {

    /**
     * Setup:
     *
     * Two photos of the same person, each imported, and each face also extracted again to query recognition directly.
     *
     * Assertions:
     *
     * Extraction produces all four face images. Recognizing either face returns the Person the first import started, who has two
     * Faces.
     */
    val importAsset1 = IntegrationTestUtil.getImportAsset("people/meme-ben2.png")
    testApp.service.library.addImportAsset(importAsset1)
    val (face1, faceImages) = testApp.service.faceDetection.extractFaces(importAsset1.bytes).head
    faceImages.image should not be empty
    faceImages.displayImage should not be empty
    faceImages.alignedImage should not be empty
    faceImages.alignedImageGs should not be empty
    val recognizedPerson: Person = testApp.service.faceRecognition.recognizeFace(face1).value

    val importAsset2 = IntegrationTestUtil.getImportAsset("people/meme-ben3.png")
    testApp.service.library.addImportAsset(importAsset2)
    val (face2, _) = testApp.service.faceDetection.extractFaces(importAsset2.bytes).head

    val samePerson: Person = testApp.service.faceRecognition.recognizeFace(face2).value
    samePerson.persistedId shouldBe recognizedPerson.persistedId

    val persistedPerson = testApp.service.person.getPersonById(recognizedPerson.persistedId)
    persistedPerson.numOfFaces should be(2)
  }

  test("Only enrolled Faces of people who are not a bad match are match candidates, for either tier of query") {

    /**
     * Setup:
     *
     * Three People with a Face at the same embedding: one enrolled, one match-only, and one enrolled but marked a bad match.
     *
     * Assertions:
     *
     * Both an enrolled and a match-only query at that embedding resolve to the enrolled person.
     */
    val asset = testContext.persistAsset()
    val vector = unitVector(1)
    val enrolled = testApp.service.person.addPerson(Person())
    testContext.addTestFace(enrolled, asset, vector)
    val matchOnly = testApp.service.person.addPerson(Person())
    testContext.addTestFace(matchOnly, asset, vector, isEnrolled = false)
    val badMatch = testApp.service.person.addPerson(Person())
    testContext.addTestFace(badMatch, asset, vector)
    testApp.service.person.markAsBadMatch(badMatch)

    testApp.service.faceRecognition
      .recognizeFace(TestContext.detectedFace(vector, isEnrolled = true))
      .value
      .persistedId shouldBe enrolled.persistedId
    testApp.service.faceRecognition
      .recognizeFace(TestContext.detectedFace(vector, isEnrolled = false))
      .value
      .persistedId shouldBe enrolled.persistedId
  }

  test("A Face of a Person merged away is not a match candidate") {

    /**
     * Setup:
     *
     * A Person with an enrolled Face, soft-deleted by hand as a merge leaves its source, with the Face left on them, and a query
     * at the Face's embedding.
     *
     * Assertions:
     *
     * The query matches nobody and starts a new Person.
     */
    val vector = unitVector(1)
    val mergedAway = testApp.service.person.addPerson(Person())
    testContext.addTestFace(mergedAway, testContext.insertAsset(), vector)
    testApp.txManager.withTransaction {
      update("UPDATE person SET is_deleted = ? WHERE id = ?", true, mergedAway.persistedId)
    }

    val recognized = testApp.service.faceRecognition.recognizeFace(TestContext.detectedFace(vector, isEnrolled = true)).value

    recognized.persistedId should not be mergedAway.persistedId
  }

  test("A Face recognized while its Person is merged away goes to the Person they were merged into") {

    /**
     * Setup:
     *
     * Persons X and Y with an enrolled Face each, far apart, and an asset with one detection near X's Face. A merge of X into Y
     * holds its transaction open until the recognition of the asset's Faces waits on it: on PostgreSQL the recognition has
     * searched by then, finding X's Face still X's.
     *
     * Assertions:
     *
     * Both finish, and the new Face is Y's, who counts all three assets; X, merged away, counts none. No Face of the repository
     * belongs to a Person merged away.
     */
    val x = testApp.service.person.addPerson(Person())
    testContext.addTestFace(x, testContext.insertAsset(), unitVector(1))
    val y = testApp.service.person.addPerson(Person())
    testContext.addTestFace(y, testContext.insertAsset(), unitVector(2))
    val asset = testContext.insertAsset()

    contend(
      first = testApp.service.person.merge(dest = y, source = x): Unit,
      second =
        testApp.service.faceRecognition.recognizeAndStore(asset, detected(TestContext.between(unitVector(1), unitVector(3), 3)))
    )

    testApp.service.person.getAllAssetFaces(asset.persistedId).map(_.personId.value) shouldBe List(y.persistedId)
    testApp.service.person.getById(y.persistedId).numOfFaces shouldBe 3
    testApp.service.person.getById(x.persistedId).numOfFaces shouldBe 0
    facesOfPeopleMergedAway shouldBe empty
  }

  test("An import storing two people's Faces and a recycle of an asset both are on both finish") {

    /**
     * Setup:
     *
     * Persons A, B and C, in ID order. A and B have a Face on a shared asset, C one on another. A new asset's detections are near
     * B's Face, then C's, then A's. A third transaction locks C. The asset's Faces are recognized and stored until they wait on
     * it; then a recycle of the shared asset starts and waits; then C is let go (`whileHolding`).
     *
     * Assertions:
     *
     * Neither the import nor the recycle fails: the new asset has a Face of each of the three, the shared asset is recycled, and
     * A and B count the new asset alone, C both of theirs.
     *
     * Edge cases:
     *
     * Storing the Faces in detection order would lock B, then wait on C; the recycle would lock A and wait on B, and the import's
     * Face of A would then wait on the recycle, a deadlock PostgreSQL ends by failing one of them.
     */
    assume(testApp.dataSourceType == Const.DbEngineName.POSTGRES)

    val List(a, b, c) = List.fill(3)(testApp.service.person.addPerson(Person())).sortBy(_.persistedId)
    val shared = testContext.insertAsset()
    testContext.addTestFace(a, shared, unitVector(1))
    testContext.addTestFace(b, shared, unitVector(2), x1 = 50)
    testContext.addTestFace(c, testContext.insertAsset(), unitVector(3))
    val asset = testContext.insertAsset()
    val detections =
      DetectedFaces(List(2, 3, 1).map(seed => TestContext.detection(TestContext.between(unitVector(seed), unitVector(9), 3))))

    whileHolding(query("SELECT id FROM person WHERE id = ? FOR UPDATE", c.persistedId): Unit)(
      () => testApp.service.faceRecognition.recognizeAndStore(asset, detections),
      () => testApp.service.library.recycleAssets(Set(shared.persistedId))
    )

    testApp.service.person.getAllAssetFaces(asset.persistedId).map(_.personId.value).toSet shouldBe
      Set(a, b, c).map(_.persistedId)
    testApp.service.asset.getById(shared.persistedId).isRecycled shouldBe true
    List(a, b, c).map(person => testApp.service.person.getById(person.persistedId).numOfFaces) shouldBe List(1, 1, 2)
  }

  test("A recognition that searches again gives its locks back and takes them again in ID order") {

    /**
     * Setup:
     *
     * Persons D, A and X, in ID order. D and A have a Face on a shared asset, X one on another. A new asset's detections are near
     * A's Face and near X's. A merge of X into D holds its transaction open while the asset's Faces are recognized, which lock A
     * and wait on X; then a recycle of the shared asset starts, which waits on D; then the merge commits (`whileHolding`).
     *
     * Assertions:
     *
     * Neither the import nor the recycle fails. The new asset's Faces are A's and D's, the Face near X's going to D, who has X's
     * Faces now; the shared asset is recycled, A counts the new asset, D the new asset and X's.
     *
     * Edge cases:
     *
     * Once X is found merged away, the import holds A and must lock D, whom the recycle took as the merge let go and who then
     * waits on A: taking D on top of A would be a deadlock, which giving A back before locking A and D again avoids.
     */
    assume(testApp.dataSourceType == Const.DbEngineName.POSTGRES)

    val List(d, a, x) = List.fill(3)(testApp.service.person.addPerson(Person())).sortBy(_.persistedId)
    val shared = testContext.insertAsset()
    testContext.addTestFace(d, shared, unitVector(1))
    testContext.addTestFace(a, shared, unitVector(2), x1 = 50)
    testContext.addTestFace(x, testContext.insertAsset(), unitVector(3))
    val asset = testContext.insertAsset()
    val detections =
      DetectedFaces(List(2, 3).map(seed => TestContext.detection(TestContext.between(unitVector(seed), unitVector(9), 3))))

    whileHolding(testApp.service.person.merge(dest = d, source = x): Unit)(
      () => testApp.service.faceRecognition.recognizeAndStore(asset, detections),
      () => testApp.service.library.recycleAssets(Set(shared.persistedId))
    )

    testApp.service.person.getAllAssetFaces(asset.persistedId).map(_.personId.value).toSet shouldBe
      Set(a, d).map(_.persistedId)
    testApp.service.asset.getById(shared.persistedId).isRecycled shouldBe true
    List(d, a, x).map(person => testApp.service.person.getById(person.persistedId).numOfFaces) shouldBe List(2, 1, 0)
    facesOfPeopleMergedAway shouldBe empty
  }

  test("A match-only detection joins a Person, and is no candidate for the asset's next detection") {

    /**
     * Setup:
     *
     * A Person P with an enrolled Face. A new asset's first detection is match-only and near P's Face; its second is enrolled,
     * near the first and far from P's Face.
     *
     * Assertions:
     *
     * The first detection is P's Face. The second starts a new Person: an asset's own match-only Face does not vote, as it would
     * not once stored.
     */
    val p = testApp.service.person.addPerson(Person())
    testContext.addTestFace(p, testContext.insertAsset(), unitVector(1))
    val asset = testContext.insertAsset()
    val first = TestContext.detection(TestContext.between(unitVector(1), unitVector(2), 1), isEnrolled = false)
    val second = TestContext.detection(TestContext.between(unitVector(2), unitVector(3), 4))

    testApp.service.faceRecognition.recognizeAndStore(asset, DetectedFaces(List(first, second)))

    val owners =
      testApp.service.person.getAllAssetFaces(asset.persistedId).map(face => face.checksum -> face.personId.value).toMap
    owners(first._1.checksum) shouldBe p.persistedId
    owners(second._1.checksum) should not be p.persistedId
  }

  test("With one Face voting, a detection goes to the nearer of a stored Face and the asset's own earlier one") {

    /**
     * Setup:
     *
     * One Face votes (`face.recognition.match_count` 1). A Person Q with an enrolled Face. Two new assets, each with a first
     * detection far from everyone, which starts a Person, and a second within the threshold of both that Person's detection and
     * Q's Face: on the first asset nearer Q's Face, on the second nearer its own first detection.
     *
     * Assertions:
     *
     * On the first asset the second detection is Q's; on the second it is the Person the first detection started.
     */
    def mix(near: Array[Float], far: Array[Float]): Array[Float] =
      FaceRecognitionService.meanNormalized(Seq(near, near, near, far, far))

    val q = testApp.service.person.addPerson(Person())
    testContext.addTestFace(q, testContext.insertAsset(), unitVector(5))

    withSetting(Settings.FACE_RECOGNITION_MATCH_COUNT, "1") {
      List((unitVector(6), true), (unitVector(7), false)).foreach {
        case (alone, nearerQ) =>
          val asset = testContext.insertAsset()
          val first = TestContext.detection(alone)
          val second = TestContext.detection(if (nearerQ) mix(unitVector(5), alone) else mix(alone, unitVector(5)))

          testApp.service.faceRecognition.recognizeAndStore(asset, DetectedFaces(List(first, second)))

          val owners =
            testApp.service.person.getAllAssetFaces(asset.persistedId).map(face => face.checksum -> face.personId.value).toMap
          withClue(s"nearer Q: $nearerQ") {
            (owners(first._1.checksum) should not).be(q.persistedId)
            owners(second._1.checksum) shouldBe (if (nearerQ) q.persistedId else owners(first._1.checksum))
          }
      }
    }
  }

  test("A Face's sharpness is stored, and a Face without one reads back without one") {

    /**
     * Setup:
     *
     * Two people's Faces on one asset, one with a sharpness and one without.
     *
     * Assertions:
     *
     * Each reads back as it was stored.
     */
    val asset = testContext.persistAsset()
    val sharp = testApp.service.person.addPerson(Person())
    testContext.addTestFace(sharp, asset, unitVector(1), sharpness = Some(123.5))
    val unmeasured = testApp.service.person.addPerson(Person())
    testContext.addTestFace(unmeasured, asset, unitVector(2))

    val faces = testApp.service.person.getAssetFaces(asset.persistedId).map(face => face.personId.value -> face.sharpness).toMap
    faces(sharp.persistedId) shouldBe Some(123.5)
    faces(unmeasured.persistedId) shouldBe None
  }

  test("A Face of another repository is not a match candidate") {

    /**
     * Setup:
     *
     * A Person's enrolled Face in a second repository, and a query at the same embedding in the first, which has no Faces.
     *
     * Assertions:
     *
     * The query matches nobody of the other repository and starts a Person in its own.
     */
    val vector = unitVector(1)
    val firstRepo = testContext.repository
    val secondRepo = testContext.persistRepository()
    switchContextRepo(secondRepo)
    val elsewhere = testApp.service.person.addPerson(Person())
    testContext.addTestFace(elsewhere, testContext.persistAsset(repository = Some(secondRepo)), vector)

    switchContextRepo(firstRepo)
    val recognized = testApp.service.faceRecognition.recognizeFace(TestContext.detectedFace(vector, isEnrolled = true)).value

    recognized.persistedId should not be elsewhere.persistedId
  }

  test("A Face's vector reads back as stored, and not from another repository") {

    /**
     * Setup:
     *
     * A Person's Face at a synthetic embedding on one asset, and a second repository.
     *
     * Assertions:
     *
     * The DAO reads the vector back equal to the one stored. The model never carries it, so this goes through the engine's vector
     * store.
     *
     * Edge cases:
     *
     * An unknown Face has no vector, and neither has the Face from the other repository's context.
     */
    val asset = testContext.persistAsset()
    val vector = unitVector(8)
    val person = testApp.service.person.addPerson(Person())
    val face = testContext.addTestFace(person, asset, vector)

    val stored = testApp.txManager.withFaceVector(testApp.DAO.face.getFeatures(face.persistedId)).value
    stored.length shouldBe vector.length
    stored.zip(vector).foreach { case (read, written) => read shouldBe written +- 1e-6f }

    testApp.txManager.withFaceVector(testApp.DAO.face.getFeatures("no-such-face")) shouldBe None

    switchContextRepo(testContext.persistRepository())
    testApp.txManager.withFaceVector(testApp.DAO.face.getFeatures(face.persistedId)) shouldBe None
  }

  test(
    "The nearest Faces of other people come closest first, leaving out the Face's Person, bad matches, match-only Faces and those past the threshold") {

    /**
     * Setup:
     *
     * Faces at known distances from an embedding: the current Person's at 0 and about 0.05, a bad match's and a match-only Face
     * at about 0.05, Person A's at about 0.11, Person B's at about 0.29, and Person C's at about 1, past the threshold.
     *
     * Assertions:
     *
     * The nearest Faces of others are A's then B's: the current Person's Faces are left out however close, as are the bad
     * match's, the match-only Face and C's.
     *
     * Edge cases:
     *
     * A limit of one keeps A's Face alone.
     */
    val asset = testContext.persistAsset()
    val vector = unitVector(9)
    val other = unitVector(10)
    val current = testApp.service.person.addPerson(Person())
    testContext.addTestFace(current, asset, vector)
    testContext.addTestFace(current, asset, TestContext.between(vector, other, 3))
    val badMatch = testApp.service.person.addPerson(Person())
    testContext.addTestFace(badMatch, asset, TestContext.between(vector, other, 3))
    testApp.service.person.markAsBadMatch(badMatch)
    val matchOnly = testApp.service.person.addPerson(Person())
    testContext.addTestFace(matchOnly, asset, TestContext.between(vector, other, 3), isEnrolled = false)
    val personA = testApp.service.person.addPerson(Person())
    val faceA = testContext.addTestFace(personA, asset, TestContext.between(vector, other, 2))
    val personB = testApp.service.person.addPerson(Person())
    val faceB = testContext.addTestFace(personB, asset, TestContext.between(vector, other, 1))
    val personC = testApp.service.person.addPerson(Person())
    testContext.addTestFace(personC, asset, other)

    def nearest(limit: Int): List[String] =
      testApp.txManager
        .withFaceVector(testApp.DAO.face.searchNearestFacesOfOthers(vector, current.persistedId, limit, 0.55))
        .map(_.persistedId)

    nearest(limit = 10) shouldBe List(faceA.persistedId, faceB.persistedId)
    nearest(limit = 1) shouldBe List(faceA.persistedId)
  }

  test("A Face of another repository is not among the nearest Faces of other people") {

    /**
     * Setup:
     *
     * An enrolled Face in a second repository at an embedding; in the first, the current Person's Face there and another Person's
     * about 0.29 away.
     *
     * Assertions:
     *
     * Searched from the first repository, the nearest Faces of others are the other Person's alone; the closer Face of the second
     * repository is not seen.
     */
    val vector = unitVector(11)
    val firstRepo = testContext.repository
    val secondRepo = testContext.persistRepository()
    switchContextRepo(secondRepo)
    val elsewhere = testApp.service.person.addPerson(Person())
    testContext.addTestFace(elsewhere, testContext.persistAsset(repository = Some(secondRepo)), vector)

    switchContextRepo(firstRepo)
    val asset = testContext.persistAsset(repository = Some(firstRepo))
    val current = testApp.service.person.addPerson(Person())
    testContext.addTestFace(current, asset, vector)
    val nearby = testApp.service.person.addPerson(Person())
    val nearbyFace = testContext.addTestFace(nearby, asset, TestContext.between(vector, unitVector(12), 1))

    val nearest =
      testApp.txManager.withFaceVector(testApp.DAO.face.searchNearestFacesOfOthers(vector, current.persistedId, 10, 0.55))

    nearest.map(_.persistedId) shouldBe List(nearbyFace.persistedId)
  }

  test("On PostgreSQL the nearest Faces are found through the vector index of the enrolled Faces") {

    /**
     * Setup:
     *
     * The nearest-Faces statements explained with the table scan and the sort ruled out, since over a handful of Faces the
     * planner would otherwise scan and sort them all.
     *
     * Assertions:
     *
     * Both the recognition statement and the Suggested one, which leaves a Person out, read the partial HNSW index `face_03`, so
     * their order and predicates match the index's declaration.
     */
    assume(testApp.dataSourceType == Const.DbEngineName.POSTGRES)
    val vector = PostgresOverrides.vectorLiteral(unitVector(1))

    def plan(sql: String, filterBinds: Any*): String =
      testApp.txManager.withTransaction {
        List("seqscan", "sort").foreach(path => update(s"SET LOCAL enable_$path = off"))
        query(
          s"EXPLAIN $sql",
          (List(vector, testContext.repository.persistedId) ++ filterBinds ++ List(vector, 20, vector, 0.55, 5))*)
          .map(_.values.head.toString)
          .mkString("\n")
      }

    val matchesPlan = plan(PostgresFaceDao.CLOSEST_MATCHES_SQL)
    withClue(matchesPlan)(matchesPlan should include("Index Scan using face_03"))
    val othersPlan = plan(PostgresFaceDao.CLOSEST_OF_OTHERS_SQL, "no-such-person")
    withClue(othersPlan)(othersPlan should include("Index Scan using face_03"))
  }

  test("With no candidate, an enrolled Face starts a Person and a match-only Face is nobody's") {

    /**
     * Setup:
     *
     * One Person whose only Face is match-only, so its embedding has no candidate.
     *
     * Assertions:
     *
     * A match-only query there resolves to nobody, while an enrolled query starts a new Person.
     */
    val asset = testContext.persistAsset()
    val vector = unitVector(2)
    val matchOnly = testApp.service.person.addPerson(Person())
    testContext.addTestFace(matchOnly, asset, vector, isEnrolled = false)

    testApp.service.faceRecognition.recognizeFace(TestContext.detectedFace(vector, isEnrolled = false)) shouldBe None
    val started = testApp.service.faceRecognition.recognizeFace(TestContext.detectedFace(vector, isEnrolled = true)).value
    started.persistedId should not be matchOnly.persistedId
  }

  test("A tied vote goes to the closest Face") {

    /**
     * Setup:
     *
     * Two People with one enrolled Face each, about 0.29 apart, within the match threshold.
     *
     * Assertions:
     *
     * A query at either Face matches both, one vote each, and the tie goes to the Person whose Face is closer.
     */
    val asset = testContext.persistAsset()
    val vector = unitVector(3)
    val nearby = FaceRecognitionService.meanNormalized(Seq(vector, unitVector(4)))
    val personAtVector = testApp.service.person.addPerson(Person())
    testContext.addTestFace(personAtVector, asset, vector)
    val personNearby = testApp.service.person.addPerson(Person())
    testContext.addTestFace(personNearby, asset, nearby)

    testApp.service.faceRecognition
      .recognizeFace(TestContext.detectedFace(vector, isEnrolled = true))
      .value
      .persistedId shouldBe personAtVector.persistedId
    testApp.service.faceRecognition
      .recognizeFace(TestContext.detectedFace(nearby, isEnrolled = true))
      .value
      .persistedId shouldBe personNearby.persistedId
  }

  test("A match-only face of nobody leaves neither a Person nor a Face behind") {

    /**
     * Setup:
     *
     * A still with a blurred, match-only face imported into a library with no People.
     *
     * Assertions:
     *
     * The face has no Person to join and is dropped: the asset has no Faces and no Person exists.
     */
    val blurred: Asset = testApp.service.library.addImportAsset(
      IntegrationTestUtil.fileToImportAsset(TestVideos.frameStill("affleck.jpg", TestVideos.FRAME_BLUR).toFile))

    testApp.service.person.getAssetFaces(blurred.persistedId) shouldBe empty
    testApp.service.person.getPeopleForAsset(blurred.persistedId) shouldBe empty
    testApp.service.person.getAll shouldBe empty
  }

  test("One person in a clip gives one enrolled Face, with a Frame time inside the clip") {

    /**
     * Setup:
     *
     * An import of a synthesized three-second clip of `affleck.jpg` (`TestVideos.oneFace`).
     *
     * Assertions:
     *
     * The clip has one enrolled Face, with a Frame time within its duration, and one Person.
     */
    val clip: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.oneFace.toFile))

    val faces = testApp.service.person.getAssetFaces(clip.persistedId)
    faces.size should be(1)
    faces.head.frameTimeMs.value should ((be >= 0L).and(be <= clip.durationMs.value))
    faces.head.isEnrolled should be(true)
    testApp.service.person.getPeopleForAsset(clip.persistedId).size should be(1)
  }

  test("Two people one after the other in a clip give two Faces of two people") {

    /**
     * Setup:
     *
     * An import of a synthesized clip of `bullock.jpg` for three seconds, then `damon.jpg` for three seconds.
     *
     * Assertions:
     *
     * The clip has two Faces of two distinct People, each with a Frame time inside its own person's span.
     */
    val clip: Asset =
      testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.twoPeopleInSequence.toFile))

    val faces = testApp.service.person.getAssetFaces(clip.persistedId)
    faces.size should be(2)
    faces.map(_.frameTimeMs.value).sorted match
      case List(first, second) =>
        first should be < 3000L
        second should be >= 3000L
    testApp.service.person.getPeopleForAsset(clip.persistedId).map(_.persistedId).distinct.size should be(2)
  }

  test("The same person in a second clip, and in a photo, is one Person") {

    /**
     * Setup:
     *
     * Three imports of one person: a photo, a three-second clip and a two-second clip of the same portrait.
     *
     * Assertions:
     *
     * Each asset has one Person, all three are the same Person, and that Person has three Faces.
     */
    val photo: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("people/affleck.jpg"))
    val clip: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.oneFace.toFile))
    val secondClip: Asset = testApp.service.library.addImportAsset(
      IntegrationTestUtil.fileToImportAsset(TestVideos.clip(Seq(TestVideos.person("affleck.jpg", 2))).toFile))

    val people = List(photo, clip, secondClip).map(asset => testApp.service.person.getPeopleForAsset(asset.persistedId))
    people.foreach(_.size should be(1))
    people.map(_.head.persistedId).distinct.size should be(1)
    testApp.service.person.getPersonById(people.head.head.persistedId).numOfFaces should be(3)
  }

  test("A person seen in only one Sampled frame does not start a Person") {

    /**
     * Setup:
     *
     * A clip of a sharp portrait for half a second, then black to three seconds, imported into a library with no People. Frames
     * are sampled each second, so the person is in the first one only.
     *
     * Assertions:
     *
     * With one frame of support, under the minimum of two, the Face is match-only and, with no Person to join, dropped: the clip
     * has no Faces and no Person exists.
     */
    val clip: Asset = testApp.service.library.addImportAsset(
      IntegrationTestUtil.fileToImportAsset(
        TestVideos.clip(Seq(TestVideos.person("bullock.jpg", 0.5), TestVideos.black(2.5))).toFile))

    testApp.service.person.getAssetFaces(clip.persistedId) shouldBe empty
    testApp.service.person.getAll shouldBe empty
  }

  test("A person seen in only one Sampled frame still joins a known person, as a match-only Face") {

    /**
     * Setup:
     *
     * A photo imported, then a clip with the same portrait in one sampled frame only.
     *
     * Assertions:
     *
     * The clip's single Face is match-only and joins the Person the photo started, who then has two Faces.
     */
    val photo: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("people/affleck.jpg"))
    val clip: Asset = testApp.service.library.addImportAsset(
      IntegrationTestUtil.fileToImportAsset(
        TestVideos.clip(Seq(TestVideos.person("affleck.jpg", 0.5), TestVideos.black(2.5))).toFile))

    val faces = testApp.service.person.getAssetFaces(clip.persistedId)
    faces.size should be(1)
    faces.head.isEnrolled should be(false)
    val person = testApp.service.person.getPeopleForAsset(photo.persistedId).head
    faces.head.personId.value shouldBe person.persistedId
    testApp.service.person.getPersonById(person.persistedId).numOfFaces should be(2)
  }

  test("A clip of only a blurred face starts no Person") {

    /**
     * Setup:
     *
     * An import of a three-second clip of `affleck.jpg` blurred at FRAME_BLUR, into a library with no People.
     *
     * Assertions:
     *
     * The blurred face is match-only though it is in every sampled frame, so the clip has no Faces and no Person exists.
     */
    val clip: Asset = testApp.service.library.addImportAsset(
      IntegrationTestUtil.fileToImportAsset(
        TestVideos.clip(Seq(TestVideos.person("affleck.jpg", 3, TestVideos.FRAME_BLUR))).toFile))

    testApp.service.person.getAssetFaces(clip.persistedId) shouldBe empty
    testApp.service.person.getAll shouldBe empty
  }

  test("Recognize two new people") {

    /**
     * Setup:
     *
     * An import of `people/movies-speed.png`, a still with two faces, into a library with no People.
     *
     * Assertions:
     *
     * The import starts two People for the asset.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/movies-speed.png")
    val importedAsset: Asset = testApp.service.library.addImportAsset(importAsset)

    val people = testApp.service.person.getPeopleForAsset(importedAsset.persistedId)
    people.size should be(2)
  }

  test("A Face's checksum is the SHA-256 of its crop as stored") {

    /**
     * Setup:
     *
     * An import of `people/meme-ben2.png`, which stores its Face and the Face's files.
     *
     * Assertions:
     *
     * The Face's checksum is the lowercase hex SHA-256 of the crop file the import wrote.
     */
    val importedAsset: Asset = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("people/meme-ben2.png"))

    val face = testApp.service.person.getAssetFaces(importedAsset.persistedId).head
    val crop = testApp.service.fileStore.getDetectedFaceById(face.persistedId).data
    face.checksum shouldBe IntegrationTestUtil.jdkSha256Hex(crop)
  }

  test("Faces rolled back with their asset leave no files behind") {

    /**
     * Setup:
     *
     * Face processing for a persisted asset over one portrait drawn twice side by side, so both crops are byte-identical.
     *
     * Assertions:
     *
     * Processing fails with a duplicate error, the asset keeps no Faces, and the repository's faces directory holds no files.
     */
    val asset = testContext.persistAsset()
    val dataAsset = AssetWithData(asset, testApp.service.staging.stage(samePortraitTwice("people/bullock.jpg")))

    // The second crop is byte-identical to the first, which the face checksum index refuses after the first face's files are
    // written
    intercept[DuplicateException] {
      testApp.service.faceRecognition.recognizeAndStore(asset, testApp.service.faceRecognition.detect(dataAsset))
    }

    testApp.service.person.getAssetFaces(asset.persistedId) shouldBe empty
    faceFiles shouldBe empty
  }

  /**
   * One portrait drawn twice side by side, offset by a multiple of the detector's largest stride in an image small enough not to
   * be resized, so both detections and crops are the same
   */
  private def samePortraitTwice(relPath: String): Array[Byte] =
    val portrait = ImageIO.read(new ByteArrayInputStream(IntegrationTestUtil.getImportAsset(relPath).bytes))
    val (width, height) = (384, 576)
    val scaled = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
    val scaling = scaled.createGraphics()
    scaling.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    scaling.drawImage(portrait, 0, 0, width, height, null)
    scaling.dispose()

    val canvas = new BufferedImage(1280, 704, BufferedImage.TYPE_3BYTE_BGR)
    val drawing = canvas.createGraphics()
    drawing.setColor(Color.WHITE)
    drawing.fillRect(0, 0, canvas.getWidth, canvas.getHeight)
    drawing.drawImage(scaled, 64, 64, null)
    drawing.drawImage(scaled, 64 + 576, 64, null)
    drawing.dispose()

    val out = new ByteArrayOutputStream()
    ImageIO.write(canvas, "png", out)
    out.toByteArray

  /** Every face file of the repository */
  private def faceFiles: List[File] =
    val facesDir =
      Paths.get(testApp.dataPath, Const.DataStore.REPOSITORIES, testContext.repository.persistedId, Const.DataStore.FACES)
    if !Files.exists(facesDir) then Nil
    else Files.walk(facesDir).iterator.asScala.filter(Files.isRegularFile(_)).map(_.toFile).toList

  private def unitVector(seed: Int): Array[Float] = TestContext.unitVector(seed)

  /** An image's one detection */
  private def detected(features: Array[Float]): DetectedFaces = DetectedFaces(List(TestContext.detection(features)))
}
