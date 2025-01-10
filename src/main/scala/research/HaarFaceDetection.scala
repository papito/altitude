package research

import java.io.File
import org.apache.commons.io.FileUtils
import org.bytedeco.javacpp.Loader
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier

import software.altitude.core.util.ImageUtil.matFromBytes

object HaarFaceDetection extends SandboxApp {

  override def process(path: String): Unit = {
    val file = new File(path)

    println("\n=========================================")
    println(s"Processing ${file.getAbsolutePath}")

    val fileByteArray: Array[Byte] = FileUtils.readFileToByteArray(file)
    val image: Mat = matFromBytes(fileByteArray)

    val resourceFilePath = "opencv/haarcascade_frontalface_default.xml"
    val faceDetector: CascadeClassifier = new CascadeClassifier(resourceFilePath);
    faceDetector.load(Loader.extractResource(resourceFilePath, null, "classifier", ".xml").getAbsolutePath)

    val faceDetections: MatOfRect = new MatOfRect()
    faceDetector.detectMultiScale(image, faceDetections)
    println(String.format("Detected %s faces", faceDetections.toList.size()))

    for (rect <- faceDetections.toArray) yield {
      Imgproc.rectangle(
        image,
        new Point(rect.x, rect.y),
        new Point(rect.x + rect.width, rect.y + rect.height),
        new Scalar(0, 255, 0))
    }

    writeResult(file, image)
  }

  allFilePaths.foreach(process)
}
