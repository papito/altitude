package altitude.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.models.ExtractedMetadata
import altitude.core.util.GeoLocationResolver

@DoNotDiscover class GeoLocationResolverTests extends AnyFunSuite {
  private val sydney = List("GPS Latitude" -> "33° 51' 25.2\"", "GPS Longitude" -> "151° 12' 54.72\"")

  private def gps(fields: (String, String)*): ExtractedMetadata = ExtractedMetadata(Map("GPS" -> fields.toMap))

  private def resolved(fields: (String, String)*): (Double, Double) = {
    val point = GeoLocationResolver.resolve(gps(fields*)).getOrElse(fail(s"No point resolved from $fields"))
    (point.latitude, point.longitude)
  }

  private def expect(actual: (Double, Double), latitude: Double, longitude: Double): Unit = {
    actual._1 shouldBe latitude +- 1e-4
    actual._2 shouldBe longitude +- 1e-4
  }

  test("DMS descriptions resolve to decimal degrees and the ref decides the hemisphere") {
    expect(resolved(sydney ++ List("GPS Latitude Ref" -> "N", "GPS Longitude Ref" -> "E")*), 33.857, 151.2152)
    expect(resolved(sydney ++ List("GPS Latitude Ref" -> "S", "GPS Longitude Ref" -> "W")*), -33.857, -151.2152)
    val signed = List("GPS Latitude" -> "-33° 51' 25.2\"", "GPS Longitude" -> "-151° 12' 54.72\"")
    expect(resolved(signed ++ List("GPS Latitude Ref" -> "s", "GPS Longitude Ref" -> "w")*), -33.857, -151.2152)
    // Without a ref, the description's own sign is all there is
    expect(resolved(signed*), -33.857, -151.2152)
    expect(resolved(sydney*), 33.857, 151.2152)
  }

  test("A phone video's ISO 6709 location resolves in each of its forms, below EXIF GPS") {
    def iso(value: String): ExtractedMetadata = ExtractedMetadata(Map("QuickTime Metadata" -> Map("ISO 6709" -> value)))
    def resolvedIso(value: String): (Double, Double) = {
      val point = GeoLocationResolver.resolve(iso(value)).getOrElse(fail(s"No point resolved from $value"))
      (point.latitude, point.longitude)
    }

    expect(resolvedIso("+37.3318-122.0312+015.000/"), 37.3318, -122.0312)
    expect(resolvedIso("-33.8570+151.2152/"), -33.857, 151.2152)
    expect(resolvedIso("+37.3318-122.0312"), 37.3318, -122.0312)
    // Degrees and minutes, then degrees, minutes and seconds
    expect(resolvedIso("+4012.22-07500.25/"), 40.2037, -75.0042)
    expect(resolvedIso("+401213.1-0750015.1/"), 40.2036, -75.0042)

    GeoLocationResolver.resolve(iso("+00.0000+000.0000/")) shouldBe None
    GeoLocationResolver.resolve(iso("+95.0000-010.0000/")) shouldBe None
    GeoLocationResolver.resolve(iso("somewhere")) shouldBe None

    val both = ExtractedMetadata(
      Map(
        "GPS" -> (sydney ++ List("GPS Latitude Ref" -> "N", "GPS Longitude Ref" -> "E")).toMap,
        "QuickTime Metadata" -> Map("ISO 6709" -> "+37.3318-122.0312/")))
    val point = GeoLocationResolver.resolve(both).get
    expect((point.latitude, point.longitude), 33.857, 151.2152)
  }

  test("An MP4's decimal coordinates resolve when nothing else places it") {
    def mp4(fields: (String, String)*): ExtractedMetadata = ExtractedMetadata(Map("MP4" -> fields.toMap))
    val point = GeoLocationResolver.resolve(mp4("Latitude" -> "48.8566", "Longitude" -> "2.3522")).get
    expect((point.latitude, point.longitude), 48.8566, 2.3522)
    GeoLocationResolver.resolve(mp4("Latitude" -> "48.8566")) shouldBe None
    GeoLocationResolver.resolve(mp4("Latitude" -> "0", "Longitude" -> "0")) shouldBe None
    GeoLocationResolver.resolve(mp4("Latitude" -> "48.8566", "Longitude" -> "181")) shouldBe None
  }

  test("The ref restores the sign a sub-degree description loses") {
    val london = List("GPS Latitude" -> "51° 30' 2.52\"", "GPS Latitude Ref" -> "N", "GPS Longitude" -> "0° 7' 39.36\"")
    expect(resolved(london :+ ("GPS Longitude Ref" -> "W")*), 51.5007, -0.1276)
    expect(resolved(london :+ ("GPS Longitude Ref" -> "E")*), 51.5007, 0.1276)
    expect(resolved(london*), 51.5007, 0.1276)
  }

  test("Descriptions written under another formatting locale still parse") {
    expect(resolved("GPS Latitude" -> "33° 51' 25,2\"", "GPS Longitude" -> "151°12'54,72\""), 33.857, 151.2152)
  }

  test("Missing, partial, garbage, out-of-range and null-island coordinates resolve to None") {
    GeoLocationResolver.resolve(ExtractedMetadata()) shouldBe None
    GeoLocationResolver.resolve(gps(sydney.head)) shouldBe None
    GeoLocationResolver.resolve(gps(sydney.last)) shouldBe None
    List("", "garbage", "33.857", "33° 51'", "33° 51' 25.2", "N 33° 51' 25.2\"").foreach {
      raw => GeoLocationResolver.resolve(gps("GPS Latitude" -> raw, sydney.last)) shouldBe None
    }
    GeoLocationResolver.resolve(gps("GPS Latitude" -> "90° 0' 0.01\"", sydney.last)) shouldBe None
    GeoLocationResolver.resolve(gps(sydney.head, "GPS Longitude" -> "180° 0' 0.01\"")) shouldBe None
    expect(resolved("GPS Latitude" -> "-90° 0' 0\"", "GPS Longitude" -> "180° 0' 0\""), -90, 180)
    GeoLocationResolver.resolve(gps("GPS Latitude" -> "0° 0' 0\"", "GPS Longitude" -> "0° 0' 0\"")) shouldBe None
    GeoLocationResolver.resolve(
      gps("GPS Latitude" -> "0° 0' 0\"", "GPS Longitude" -> "0° 0' 0\"", "GPS Latitude Ref" -> "S")) shouldBe
      None
    expect(resolved("GPS Latitude" -> "0° 0' 0\"", sydney.last), 0, 151.2152)
  }
}
