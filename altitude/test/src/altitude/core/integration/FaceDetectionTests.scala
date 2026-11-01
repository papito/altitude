package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.opencv.core.Mat
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import scala.jdk.CollectionConverters.*

import altitude.core.Altitude
import altitude.core.util.ImageUtil.matFromBytes

@DoNotDiscover class FaceDetectionTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Face is detected in an image (1)") {

    /**
     * Setup:
     *
     * The `people/meme-ben.jpg` photo (680x1020) as bytes, detected directly rather than imported.
     *
     * Assertions:
     *
     * Detection keeps exactly one face.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.bytes, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Small face image is detected (1)") {

    /**
     * Setup:
     *
     * `people/small-face1.jpg`, a 75x90 image that is little more than the face.
     *
     * Assertions:
     *
     * Detection keeps exactly one face.
     *
     * Edge cases:
     *
     * The whole image is barely larger than the 50 px minimum face box.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/small-face1.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.bytes, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Small face image is detected (2)") {

    /**
     * Setup:
     *
     * `people/small-face2.jpg`, a 79x111 image that is little more than the face.
     *
     * Assertions:
     *
     * Detection keeps exactly one face.
     *
     * Edge cases:
     *
     * The whole image is barely larger than the 50 px minimum face box.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/small-face2.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.bytes, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Large portrait face image is detected (1)") {

    /**
     * Setup:
     *
     * `people/affleck.jpg`, a 1280x1786 portrait.
     *
     * Assertions:
     *
     * Detection keeps exactly one face.
     *
     * Edge cases:
     *
     * The image is larger than the 1280 px detection box, so detection runs on a downscaled copy.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/affleck.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.bytes, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Large portrait face image is detected (2)") {

    /**
     * Setup:
     *
     * `people/bullock.jpg`, a 1000x1500 portrait.
     *
     * Assertions:
     *
     * Detection keeps exactly one face.
     *
     * Edge cases:
     *
     * The image is larger than the 1280 px detection box, so detection runs on a downscaled copy.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/bullock.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.bytes, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("Large portrait face image is detected (3)") {

    /**
     * Setup:
     *
     * `people/damon.jpg`, a 1280x1665 portrait.
     *
     * Assertions:
     *
     * Detection keeps exactly one face.
     *
     * Edge cases:
     *
     * The image is larger than the 1280 px detection box, so detection runs on a downscaled copy.
     */
    val importAsset = IntegrationTestUtil.getImportAsset("people/damon.jpg")
    val faces = testApp.service.faceDetection.extractFaces(importAsset.bytes, Some(importAsset.fileName))
    faces.length should be(1)
  }

  test("A blurred copy of a portrait has a lower embedding norm than the sharp one") {

    /**
     * Setup:
     *
     * `affleck.jpg` letterboxed into a 640x480 clip frame, once sharp and once Gaussian-blurred at FRAME_BLUR. The frame is used
     * rather than the full-size photo because a blur small against the face disappears in the 112 px alignment.
     *
     * Assertions:
     *
     * The raw ArcFace embedding norm of the detected face, the measure behind Face quality, is lower for the blurred copy.
     */
    embeddingNorm(TestVideos.personFrame("affleck.jpg")) should be > embeddingNorm(
      TestVideos.personFrame("affleck.jpg", TestVideos.FRAME_BLUR))
  }

  test("A sharp portrait is enrolled") {

    /**
     * Setup:
     *
     * The full-size `people/affleck.jpg` portrait, detected directly rather than imported.
     *
     * Assertions:
     *
     * Its face is enrolled, and its quality, the value the decision is made on, clears the configured enroll threshold.
     */
    val face = testApp.service.faceDetection.extractFaces(IntegrationTestUtil.getImportAsset("people/affleck.jpg").bytes).head._1
    face.quality should be >= testApp.settings.current.faceQualityEnrollThreshold
    face.isEnrolled should be(true)
  }

  test("A blurred face is kept as match-only") {

    /**
     * Setup:
     *
     * `affleck.jpg` letterboxed into a clip-sized frame and blurred at FRAME_BLUR, written as a PNG.
     *
     * Assertions:
     *
     * YuNet finds the face, and detection keeps it as match-only, its quality between the keep and enroll thresholds.
     */
    val bytes = Files.readAllBytes(TestVideos.frameStill("affleck.jpg", TestVideos.FRAME_BLUR))
    testApp.service.faceDetection.detectFacesWithYunet(matFromBytes(bytes)).size should be(1)

    val faces = testApp.service.faceDetection.extractFaces(bytes)
    faces.size should be(1)
    faces.head._1.isEnrolled should be(false)
  }

  test("A heavily blurred face is detected but not kept") {

    /**
     * Setup:
     *
     * `meme-ben.jpg` at its own size, blurred at HEAVY_BLUR and written as a PNG.
     *
     * Assertions:
     *
     * YuNet finds the face, yet detection keeps nothing: the face falls below the keep threshold.
     */
    val bytes = Files.readAllBytes(TestVideos.still("meme-ben.jpg", TestVideos.HEAVY_BLUR))
    // Detected, so an empty result below is the quality floor at work rather than YuNet giving up
    testApp.service.faceDetection.detectFacesWithYunet(matFromBytes(bytes)).size should be(1)

    testApp.service.faceDetection.extractFaces(bytes) shouldBe empty
  }

  test("Embeddings computed concurrently match the ones computed one at a time") {

    /**
     * Setup:
     *
     * The aligned faces of `people/affleck.jpg` and `people/damon.jpg`, embedded one at a time for the expected vectors, then by
     * 8 threads, more than the ArcFace pool has nets, 20 embeddings a thread, alternating the faces.
     *
     * Assertions:
     *
     * The two faces' vectors differ, and every concurrent embedding is its own face's vector, within float noise.
     *
     * Edge cases:
     *
     * A net's output shares its data with the net, so a copy of it made after the net is back in the pool would race the next
     * borrower's forward pass and silently give a face a wrong vector. Such a race does not fire on every run, so one pass here
     * does not prove its absence.
     */
    val faces = List("people/affleck.jpg", "people/damon.jpg").map {
      path => alignedFace(matFromBytes(IntegrationTestUtil.getImportAsset(path).bytes))
    }
    val expected = faces.map(testApp.service.faceDetection.getArcFaceEmbedding(_).features)
    maxDifference(expected.head, expected.last) should be > 0.01f

    val threads = 8
    val executor = Executors.newFixedThreadPool(threads)
    val embedded =
      try {
        val tasks = (0 until threads).map {
          thread =>
            (() =>
              (0 until 20).map {
                i =>
                  val face = (thread + i) % faces.size
                  (face, testApp.service.faceDetection.getArcFaceEmbedding(faces(face)).features)
              }): Callable[IndexedSeq[(Int, Array[Float])]]
        }
        executor.invokeAll(tasks.asJava).asScala.toList.flatMap(_.get())
      } finally executor.shutdown()

    embedded.size should be(threads * 20)
    embedded.foreach {
      case (face, features) => maxDifference(features, expected(face)) should be < 1e-5f
    }
  }

  /** The first face YuNet finds in the image, aligned for ArcFace */
  private def alignedFace(image: Mat): Mat =
    val detection = testApp.service.faceDetection.detectFacesWithYunet(image).head
    testApp.service.faceDetection.alignCropFaceFromDetection(image, detection)

  /** The norm of the raw ArcFace embedding of the first face YuNet finds in the image */
  private def embeddingNorm(image: Mat): Float =
    testApp.service.faceDetection.getArcFaceEmbedding(alignedFace(image)).norm

  /** The largest difference between two vectors over their components */
  private def maxDifference(a: Array[Float], b: Array[Float]): Float =
    a.zip(b).map((x, y) => Math.abs(x - y)).max
}
