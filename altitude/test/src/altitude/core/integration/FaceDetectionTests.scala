package altitude.core.integration

import altitude.core.Altitude
import altitude.core.service.FaceDetectionService
import altitude.core.util.ImageUtil.matFromBytes
import altitude.test.IntegrationTestUtil
import org.opencv.core.Mat
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.should

@DoNotDiscover class FaceDetectionTests(override val testApp: Altitude) extends IntegrationTestCore {

  def dumpDetections(imageMat: Mat, detections: List[Mat]): Unit = {
    detections.indices.foreach(
      idx => {
        val detection = detections(idx)
        val faceMat = imageMat.submat(FaceDetectionService.faceDetectToRect(detection))
        FaceDetectionService.writeDebugOpenCvMat(faceMat, s"res$idx.jpg")
      })
  }

  test("Face is detected in an image (1)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg")

    val detections = testApp.service.faceDetection.detectFacesWithYunet(matFromBytes(importAsset.data))
    detections.length should be(1)
  }

  test("Faces are detected in an image (1)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/movies-speed.png")
    val detections = testApp.service.faceDetection.detectFacesWithDnnNet(matFromBytes(importAsset.data))
    detections.length should be(2)
  }

  test("Faces are detected in an image (2)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/meme-wednesday.png")
    val detections = testApp.service.faceDetection.detectFacesWithDnnNet(matFromBytes(importAsset.data))
    detections.length should be(2)
  }

  test("Small face image is detected (1)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/small-face1.jpg")
    val detections = testApp.service.faceDetection.detectFacesWithYunet(matFromBytes(importAsset.data))
    detections.length should be(1)
  }

  test("Small face image is detected (2)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/small-face2.jpg")
    val detections = testApp.service.faceDetection.detectFacesWithYunet(matFromBytes(importAsset.data))
    detections.length should be(1)
  }

  test("Large portrait face image is detected (1)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/affleck.jpg")
    val imageMat = matFromBytes(importAsset.data)
    val detections = testApp.service.faceDetection.detectFacesWithYunet(imageMat)
    detections.length should be(1)
  }

  test("Large portrait face image is detected (2)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/bullock.jpg")
    val detections = testApp.service.faceDetection.detectFacesWithYunet(matFromBytes(importAsset.data))
    detections.length should be(1)
  }

  test("Large portrait face image is detected (3)") {
    val importAsset = IntegrationTestUtil.getImportAsset("people/damon.jpg")
    val detections = testApp.service.faceDetection.detectFacesWithYunet(matFromBytes(importAsset.data))
    detections.length should be(1)
  }
}
