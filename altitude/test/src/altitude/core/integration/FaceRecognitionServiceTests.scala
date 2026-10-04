package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import scala.jdk.CollectionConverters.*
import scala.util.Random

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.service.FaceDetectionService
import altitude.core.service.FaceRecognitionService

@DoNotDiscover class FaceRecognitionServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Recognize a person") {

    /**
     * Setup:
     *
     * Two photos of the same person, `people/meme-ben2.png` and `people/meme-ben3.png`, each imported, which stores their faces.
     * Each photo's face is also extracted again outside the import to query recognition directly.
     *
     * Assertions:
     *
     * Extraction produces all four face images: the crop, the display thumbnail, the aligned crop and its grayscale copy.
     * Recognizing either face returns the Person the first import started, and that Person has two Faces, one per photo.
     */
    val importAsset1 = IntegrationTestUtil.getImportAsset("people/meme-ben2.png")
    testApp.service.library.addImportAsset(importAsset1)
    val (face1, faceImages) = testApp.service.faceDetection.extractFaces(importAsset1.bytes).head
    faceImages.image should not be empty
    faceImages.displayImage should not be empty
    faceImages.alignedImage should not be empty
    faceImages.alignedImageGs should not be empty
    val recognizedPerson: Person = testApp.service.faceRecognition.recognizeFace(face1).value

    // Recognize
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
     * Three People, each with a Face at the same synthetic embedding on one asset: one enrolled, one match-only, and one enrolled
     * but of a person marked as a bad match.
     *
     * Assertions:
     *
     * Both an enrolled and a match-only query at that embedding resolve to the enrolled person, so neither the match-only Face
     * nor the bad match's Face is a candidate.
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
      .recognizeFace(query(vector, isEnrolled = true))
      .value
      .persistedId shouldBe enrolled.persistedId
    testApp.service.faceRecognition
      .recognizeFace(query(vector, isEnrolled = false))
      .value
      .persistedId shouldBe enrolled.persistedId
  }

  test("With no candidate, an enrolled Face starts a Person and a match-only Face is nobody's") {

    /**
     * Setup:
     *
     * One Person whose only Face, at a synthetic embedding, is match-only, so that embedding has no candidate.
     *
     * Assertions:
     *
     * A match-only query there resolves to nobody, while an enrolled query starts a new Person rather than joining the match-only
     * Face's owner.
     */
    val asset = testContext.persistAsset()
    val vector = unitVector(2)
    val matchOnly = testApp.service.person.addPerson(Person())
    testContext.addTestFace(matchOnly, asset, vector, isEnrolled = false)

    testApp.service.faceRecognition.recognizeFace(query(vector, isEnrolled = false)) shouldBe None
    val started = testApp.service.faceRecognition.recognizeFace(query(vector, isEnrolled = true)).value
    started.persistedId should not be matchOnly.persistedId
  }

  test("A tied vote goes to the closest Face") {

    /**
     * Setup:
     *
     * Two People with one enrolled Face each: one at a synthetic embedding, the other at the normalized mean of it and an
     * unrelated vector, about 0.29 away and within the match threshold.
     *
     * Assertions:
     *
     * A query at either Face matches both, one vote per Person, and the tie goes to the Person whose Face is closer to the query.
     */
    val asset = testContext.persistAsset()
    val vector = unitVector(3)
    val nearby = FaceRecognitionService.meanNormalized(Seq(vector, unitVector(4)))
    val personAtVector = testApp.service.person.addPerson(Person())
    testContext.addTestFace(personAtVector, asset, vector)
    val personNearby = testApp.service.person.addPerson(Person())
    testContext.addTestFace(personNearby, asset, nearby)

    testApp.service.faceRecognition
      .recognizeFace(query(vector, isEnrolled = true))
      .value
      .persistedId shouldBe personAtVector.persistedId
    testApp.service.faceRecognition
      .recognizeFace(query(nearby, isEnrolled = true))
      .value
      .persistedId shouldBe personNearby.persistedId
  }

  test("A match-only face of nobody leaves neither a Person nor a Face behind") {

    /**
     * Setup:
     *
     * An import of `affleck.jpg` letterboxed into a clip-sized frame and blurred at FRAME_BLUR, which makes its face match-only,
     * into a library with no People.
     *
     * Assertions:
     *
     * The face has no Person to join and is dropped: the asset has no Faces and no People, and no Person exists.
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
     * The clip has one enrolled Face, with a Frame time within the clip's duration, and one Person.
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
        // Each Face is pinned to a frame of its own person's span: the first three seconds, then the next three
        first should be < 3000L
        second should be >= 3000L
    testApp.service.person.getPeopleForAsset(clip.persistedId).map(_.persistedId).distinct.size should be(2)
  }

  test("The same person in a second clip, and in a photo, is one Person") {

    /**
     * Setup:
     *
     * Three imports of one person: the `affleck.jpg` photo, the three-second `TestVideos.oneFace` clip, and a second, two-second
     * clip of the same portrait.
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
     * An import of a clip of `bullock.jpg` for half a second, then two and a half seconds of black, into a library with no
     * People. Frames are sampled at 0, 1000 and 2000 ms, so the person is in the first sampled frame only.
     *
     * Assertions:
     *
     * With a single frame of support the Face is match-only, and with no Person to join it is dropped: the clip has no Faces and
     * no Person exists.
     *
     * Edge cases:
     *
     * A cluster under the minimum of two supporting frames, from a sharp face that would otherwise enroll.
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
     * The `affleck.jpg` photo imported first, then a clip of the same portrait for half a second followed by two and a half
     * seconds of black, so the person is in one sampled frame.
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
     * The blurred face is match-only even though it is in every sampled frame, so with no Person to join, the clip has no Faces
     * and no Person exists.
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

  test("Faces rolled back with their asset leave no files behind") {

    /**
     * Setup:
     *
     * A persisted asset, then face processing run on a staged PNG of `bullock.jpg` drawn twice side by side, so both detections
     * and their crops are byte-identical.
     *
     * Assertions:
     *
     * The second Face breaks the face checksum index after the first Face's files are written. Processing fails with a duplicate
     * error, the asset keeps no Faces, and the repository's faces directory holds no files.
     */
    val asset = testContext.persistAsset()
    val dataAsset = AssetWithData(asset, testApp.service.staging.stage(samePortraitTwice("people/bullock.jpg")))

    // The second crop is byte-identical to the first, which the face checksum index refuses after the first face's files are
    // written
    intercept[DuplicateException] {
      testApp.service.faceRecognition.processAsset(dataAsset)
    }

    testApp.service.person.getAssetFaces(asset.persistedId) shouldBe empty
    faceFiles shouldBe empty
  }

  /**
   * One portrait drawn twice side by side, at an offset that is a multiple of the detector's largest stride and in an image small
   * enough not to be resized, so both detections, and their crops, are the same
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

  /** A reproducible unit vector; two seeds give vectors about a cosine distance of 1 apart */
  private def unitVector(seed: Int): Array[Float] =
    val random = new Random(seed)
    FaceRecognitionService.meanNormalized(
      Seq(Array.fill(FaceDetectionService.EMBEDDING_DIMENSIONS)(random.nextGaussian().toFloat)))

  /** An unsaved detection with the given embedding, as recognition receives it */
  private def query(features: Array[Float], isEnrolled: Boolean): Face =
    Face(
      x1 = 0,
      y1 = 0,
      width = 10,
      height = 10,
      detectionScore = 0.9,
      checksum = Random.nextInt(),
      features = features,
      quality = 20.0,
      isEnrolled = isEnrolled)
}
