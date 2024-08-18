package research

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.filefilter.IOFileFilter
import org.apache.commons.io.filefilter.SuffixFileFilter
import org.apache.commons.io.filefilter.TrueFileFilter
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import software.altitude.core.Altitude

import java.io.File

abstract class SandboxApp extends App {
  Loader.load(classOf[opencv_java])

  val altitude = new Altitude()

  private val IMAGE_FILE_FILTER: IOFileFilter = new SuffixFileFilter(".JPG", ".jpg", ".jpeg", ".png")

  protected val sourceDirPath: String =  System.getenv().get("SOURCE")
  protected val outputDirPath: String = System.getenv().get("OUTPUT")

  def process(path: String): Unit

  if (!new File(sourceDirPath).isDirectory) {
    println(s"Source directory [$sourceDirPath] does not exist")
  }

  if (!new File(outputDirPath).isDirectory) {
    println(s"Output directory [$outputDirPath] does not exist")
  }

  def writeResult(ogFile: File, image: Mat): Unit = {
    val outputPath = FilenameUtils.concat(outputDirPath, ogFile.getName)
    println(String.format("Writing %s", outputPath))
    if (image.empty()) {
        println("Empty image !!!")
        return
    }

    Imgcodecs.imwrite(outputPath, image)
  }

  private def allFilePathsInDir(directoryPath: String): List[String] = {
    recursiveFilePathIterator(directoryPath).toList
  }

  def recursiveFilePathIterator(path: String): Iterator[String] = {
    val files = FileUtils.iterateFiles(
      new File(path), IMAGE_FILE_FILTER, TrueFileFilter.INSTANCE)

    new Iterable[String] {
      def iterator: Iterator[String] = new Iterator[String] {
        def hasNext: Boolean = files.hasNext

        def next(): String = files.next().getAbsolutePath
      }
    }.iterator
  }

  val allFilePaths: List[String] = allFilePathsInDir(sourceDirPath)
}
