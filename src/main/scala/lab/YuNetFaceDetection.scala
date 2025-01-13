package lab

import java.io.File
import org.apache.commons.io.FileUtils
import org.opencv.core._
import org.opencv.imgproc.Imgproc

import software.altitude.core.service.FaceDetectionService
import software.altitude.core.util.ImageUtil.matFromBytes

object YuNetFaceDetection extends SandboxApp {
  private var totalFaceRegions = 0
  private val markerColor = new Scalar(0, 255, 255, 0)

  override def process(path: String): Unit = {
    val file = new File(path)

    println("\n=========================================")
    println(s"Processing ${file.getAbsolutePath}")

    val fileByteArray: Array[Byte] = FileUtils.readFileToByteArray(file)
    val image: Mat = matFromBytes(fileByteArray)

    val results: List[Mat] = altitude.service.faceDetection.detectFacesWithYunet(image)

    for (res <- results) {
      totalFaceRegions += 1
      val rect = FaceDetectionService.faceDetectToRect(res)
      Imgproc.rectangle(image, rect.tl(), rect.br(), markerColor, 2)
    }

    writeResult(file, image)
  }

  allFilePaths.foreach(process)

  println(s"\n=======\nTotal face regions detected: $totalFaceRegions")
}
