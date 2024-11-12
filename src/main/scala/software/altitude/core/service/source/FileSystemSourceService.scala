package software.altitude.core.service.source

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.IOFileFilter
import org.apache.commons.io.filefilter.TrueFileFilter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.Altitude
import software.altitude.core.models._

import java.io.File

private object FileSystemSourceService {
  private val ANY_FILE_FILTER: IOFileFilter = TrueFileFilter.INSTANCE
}

class FileSystemSourceService(app: Altitude) extends AssetSourceService {
  protected final val logger: Logger = LoggerFactory.getLogger(getClass)

  override def assetIterator(path: String): Iterator[ImportAsset] = {
    require(path != null)
    logger.info(s"Importing from '$path'")

    val files = FileUtils.iterateFiles(
      new File(path),
      FileSystemSourceService.ANY_FILE_FILTER,
      FileSystemSourceService.ANY_FILE_FILTER)

    new Iterable[ImportAsset] {
      def iterator: Iterator[ImportAsset] = new Iterator[ImportAsset] {
        def hasNext: Boolean = files.hasNext

        def next(): ImportAsset = fileToImportAsset(new File(files.next().toString))
      }
    }.iterator
  }

  override def count(path: String): Int = {
    val itr = assetIterator(path)

    var count = 0

    while (itr.hasNext) {
      count += 1
      itr.next()
    }
    count
  }

  private def fileToImportAsset(file: File): ImportAsset = new ImportAsset(
    fileName = file.getName,
    data = FileUtils.readFileToByteArray(file),
    metadata = UserMetadata())
}
