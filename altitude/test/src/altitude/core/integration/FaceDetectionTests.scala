package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.nio.file.Files
import org.opencv.core.Mat
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

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
     * Its face has a positive quality and is enrolled.
     */
    val face = testApp.service.faceDetection.extractFaces(IntegrationTestUtil.getImportAsset("people/affleck.jpg").bytes).head._1
    face.quality should be > 0.0
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

  /** The norm of the raw ArcFace embedding of the first face YuNet finds in the image */
  private def embeddingNorm(image: Mat): Float =
    val detection = testApp.service.faceDetection.detectFacesWithYunet(image).head
    val aligned = testApp.service.faceDetection.alignCropFaceFromDetection(image, detection)
    testApp.service.faceDetection.getArcFaceEmbedding(aligned).norm
}
