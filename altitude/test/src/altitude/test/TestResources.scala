package altitude.test

import java.nio.file.Path

/** The test resources as files on disk */
object TestResources {

  /**
   * A file under the resources' `import/` folder, as a path. Made from the resource URL's URI and never from its `getPath`, which
   * on Windows is `/D:/...`, a path no native library (OpenCV's `imread`) opens, and which keeps a space as `%20` on every
   * system.
   */
  def importFile(relPath: String): Path = {
    val url = Option(getClass.getResource(s"/import/$relPath")).getOrElse {
      throw new RuntimeException(s"No test resource /import/$relPath")
    }
    Path.of(url.toURI)
  }
}
