package altitude.core

/**
 * The system libraries that bytedeco's OpenCV and FFmpeg link on Linux and do not bundle: `opencv_highgui` links GTK 2 (and,
 * through it, GDK, Cairo, Pango, ATK and GLib), FFmpeg's `avdevice` ALSA and XCB. A desktop has them; a headless server, and a
 * slim container image, may have none, and then the first native load fails with an `UnsatisfiedLinkError` that names a file but
 * not what to install.
 */
object SystemLibraries:

  /** The start of the file names of those libraries, as the dynamic linker names a missing one */
  private val LINKED =
    Seq("libgtk", "libgdk", "libcairo", "libpango", "libatk", "libglib", "libgobject", "libasound", "libxcb", "libX11")

  /** The Debian and Ubuntu packages that hold them */
  val DEBIAN_PACKAGES = "libgtk2.0-0 libasound2 libxcb1 libxcb-shm0"

  /** The same packages on Ubuntu 24.04 and Debian 13 and later, where the 64-bit time transition renamed two of them */
  val DEBIAN_T64_PACKAGES = "libgtk2.0-0t64 libasound2t64 libxcb1 libxcb-shm0"

  /**
   * On Linux, where loading OpenCV or FFmpeg failed for one of those libraries, the reason for the failed start's line: the
   * linker's message, which names the missing file, and the packages that hold it. None for any other failure, and anywhere but
   * Linux.
   *
   * The `UnsatisfiedLinkError` may be `failure` itself or a cause of it: javacv wraps FFmpeg's, and a class whose initializer
   * failed is reported by a `NoClassDefFoundError`, which on a later use carries the original error's text in its cause's message
   * rather than the error itself.
   */
  def missingFrom(failure: Throwable, osName: String): Option[String] =
    if !osName.toLowerCase.startsWith("linux") then None
    else
      Iterator
        .iterate(failure)(_.getCause)
        .takeWhile(_ != null)
        // A cause chain can loop back on itself
        .take(20)
        .collectFirst {
          case error
              if Option(error.getMessage).exists(
                message =>
                  (error.isInstanceOf[UnsatisfiedLinkError] || message.contains(classOf[UnsatisfiedLinkError].getName)) &&
                    LINKED.exists(message.contains)) =>
            s"${error.getMessage.stripSuffix(".")}. OpenCV and FFmpeg need GTK 2, ALSA and XCB, which this system does not " +
              s"have: on Debian or Ubuntu, install $DEBIAN_PACKAGES (on Ubuntu 24.04, Debian 13 and later: $DEBIAN_T64_PACKAGES)"
        }
