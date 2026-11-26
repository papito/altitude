package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.OptionValues.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.SystemLibraries

/** What a failed start says when OpenCV or FFmpeg cannot load a system library they link on Linux */
@DoNotDiscover class SystemLibrariesTests extends AnyFunSuite with TestFocus {

  private val cache = "/home/someone/.javacpp/cache/altitude-0.1.0-linux-x86_64.jar/org/bytedeco"

  /** The linker's error for a library that a native library of the cache's needs and the system does not have */
  private def linkError(library: String, missing: String): UnsatisfiedLinkError =
    UnsatisfiedLinkError(s"$cache/$library: $missing: cannot open shared object file: No such file or directory")

  test("A GTK, ALSA or XCB library that OpenCV or FFmpeg cannot load is reported with the packages to install") {

    /**
     * Setup:
     *
     * The errors a Linux start fails with when a system library is missing: OpenCV's GTK 2 and GDK, FFmpeg's ALSA, XCB and its
     * shared memory extension. OpenCV's arrives as the error itself; FFmpeg's wrapped, as javacv's loader throws it; and one as a
     * class whose initializer failed reports it on a later use, the error's text in its cause's message.
     *
     * Assertions:
     *
     * Each is reported with the linker's message, which names the missing file, and both sets of Debian and Ubuntu package names:
     * those before the 64-bit time transition, and the renamed ones of Ubuntu 24.04 and Debian 13.
     */
    val opencv = linkError("opencv/linux-x86_64/libopencv_highgui.so.409", "libgtk-x11-2.0.so.0")
    val gdk = linkError("opencv/linux-x86_64/libopencv_highgui.so.409", "libgdk-x11-2.0.so.0")
    val ffmpeg = Exception(
      "Failed to load class org.bytedeco.javacv.FFmpegFrameGrabber",
      linkError("ffmpeg/linux-x86_64/libavdevice.so.60", "libasound.so.2"))
    val xcb = Exception(
      "Failed to load class org.bytedeco.javacv.FFmpegFrameGrabber",
      linkError("ffmpeg/linux-x86_64/libavdevice.so.60", "libxcb-shm.so.0"))
    val initializer = NoClassDefFoundError("Could not initialize class org.bytedeco.ffmpeg.global.avdevice")
    initializer.initCause(
      ExceptionInInitializerError(
        s"Exception java.lang.UnsatisfiedLinkError: $cache/ffmpeg/linux-x86_64/libavdevice.so.60: libxcb.so.1: cannot open " +
          "shared object file: No such file or directory [in thread \"main\"]"))

    Seq(
      opencv -> "libgtk-x11-2.0.so.0",
      gdk -> "libgdk-x11-2.0.so.0",
      ffmpeg -> "libasound.so.2",
      xcb -> "libxcb-shm.so.0",
      initializer -> "libxcb.so.1").foreach {
      case (failure, missing) =>
        withClue(s"[$missing]: ") {
          val reason = SystemLibraries.missingFrom(failure, "Linux").value
          reason should include(s"$missing: cannot open shared object file")
          reason should include(SystemLibraries.DEBIAN_PACKAGES)
          reason should include(SystemLibraries.DEBIAN_T64_PACKAGES)
        }
    }
    SystemLibraries.DEBIAN_PACKAGES shouldBe "libgtk2.0-0 libasound2 libxcb1 libxcb-shm0"
    SystemLibraries.DEBIAN_T64_PACKAGES shouldBe "libgtk2.0-0t64 libasound2t64 libxcb1 libxcb-shm0"
  }

  test("Any other failure, and any failure off Linux, is left to the start's own reason") {

    /**
     * Setup:
     *
     * A GTK library missing on macOS and on Windows; on Linux, a native library missing that no system package of these holds, a
     * failure that is no link error but names GTK, and a failure with no message whose cause chain loops back on itself.
     *
     * Assertions:
     *
     * None of them is reported with the packages: the failed start's line gives its own reason.
     */
    val gtk = linkError("opencv/linux-x86_64/libopencv_highgui.so.409", "libgtk-x11-2.0.so.0")
    SystemLibraries.missingFrom(gtk, "Mac OS X") shouldBe None
    SystemLibraries.missingFrom(gtk, "Windows 11") shouldBe None

    SystemLibraries.missingFrom(linkError("opencv/linux-x86_64/libopencv_core.so.409", "libstdc++.so.6"), "Linux") shouldBe None
    SystemLibraries.missingFrom(IllegalStateException("libgtk-x11-2.0.so.0 is a file"), "Linux") shouldBe None

    val looped = Exception()
    val cause = Exception(looped)
    looped.initCause(cause)
    SystemLibraries.missingFrom(looped, "Linux") shouldBe None
  }
}
