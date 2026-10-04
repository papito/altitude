package altitude.core.unit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import org.scalatest.BeforeAndAfterAll
import org.scalatest.DoNotDiscover
import org.scalatest.OptionValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.GeocoderException
import altitude.core.IllegalOperationException
import altitude.core.models.GeocoderResult
import altitude.core.service.GeocoderService

/**
 * The geocoder proxy against a stub of a Nominatim-compatible endpoint on localhost: the config gate, the request it sends (the
 * identifying `User-Agent` the Nominatim policy asks for, `format=json`, a small `limit`), and what it makes of the answer. No
 * network is touched.
 */
@DoNotDiscover class GeocoderServiceTests extends AnyFunSuite with BeforeAndAfterAll with OptionValues {

  private val server: HttpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

  /** What the stub answers next, and the last request it saw */
  @volatile private var response: (Int, String) = (200, "[]")
  @volatile private var lastQuery: Option[String] = None
  @volatile private var lastUserAgent: Option[String] = None

  server.createContext(
    "/search",
    (exchange: HttpExchange) => {
      lastQuery = Option(exchange.getRequestURI.getRawQuery)
      lastUserAgent = Option(exchange.getRequestHeaders.getFirst("User-Agent"))
      val body = response._2.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(response._1, body.length)
      exchange.getResponseBody.write(body)
      exchange.close()
    }
  )

  override def beforeAll(): Unit = server.start()
  override def afterAll(): Unit = server.stop(0)

  private def config(enabled: Boolean): Config = ConfigFactory.parseString(s"""
    map.geocoder.enabled = $enabled
    map.geocoder.url = "http://127.0.0.1:${server.getAddress.getPort}/search"
  """)

  private val nominatimBody: String =
    """[
      {"place_id": 1, "display_name": "Paris, Île-de-France, France", "lat": "48.8588897", "lon": "2.3200410"},
      {"place_id": 2, "display_name": "Paris, Texas, United States", "lat": "33.6617962", "lon": "-95.5555130"}
    ]"""

  test("A disabled geocoder refuses every search and says so") {

    /**
     * Setup:
     *
     * A geocoder service configured with map.geocoder.enabled off, pointed at the local stub.
     *
     * Assertions:
     *
     * It reports itself disabled, refuses a search, and never sends a request to the stub.
     */
    val service = GeocoderService(config(enabled = false))
    service.isEnabled shouldBe false
    intercept[IllegalOperationException] {
      service.search("Paris")
    }
    lastQuery shouldBe None
  }

  test("A search asks for JSON with a small limit, identifies itself, and maps the places") {

    /**
     * Setup:
     *
     * An enabled geocoder service and a stub answering with two Nominatim places named Paris; the query is padded with spaces.
     *
     * Assertions:
     *
     * The places map to labelled results in the order given, and the request carries the trimmed query, format=json, the limit of
     * five and the Altitude User-Agent.
     */
    response = (200, nominatimBody)
    val results = GeocoderService(config(enabled = true)).search("  Paris ")

    results shouldEqual List(
      GeocoderResult("Paris, Île-de-France, France", 48.8588897, 2.3200410),
      GeocoderResult("Paris, Texas, United States", 33.6617962, -95.5555130))

    val parameters = lastQuery.value.split("&").toSet
    parameters should contain("format=json")
    parameters should contain("limit=5")
    parameters should contain("q=Paris")
    lastUserAgent.value should startWith("Altitude")
  }

  test("A blank query asks nothing of the geocoder") {

    /**
     * Setup:
     *
     * An enabled geocoder service and a query of only whitespace.
     *
     * Assertions:
     *
     * The search returns nothing without sending a request.
     */
    lastQuery = None
    GeocoderService(config(enabled = true)).search(" \t ") shouldBe Nil
    lastQuery shouldBe None
  }

  test("Query text is URL-encoded, and a place without coordinates is skipped") {

    /**
     * Setup:
     *
     * A stub answering with three places - one with coordinates, one without, one with coordinates that are not numbers - and a
     * query with an apostrophe, an accented letter and an ampersand.
     *
     * Assertions:
     *
     * Only the place with usable coordinates comes back, and the query reaches the stub URL-encoded.
     *
     * Edge cases:
     *
     * Missing and non-numeric coordinates, and characters that would otherwise break the query string.
     */
    response = (
      200,
      """[{"display_name": "Somewhere", "lat": "1.5", "lon": "2.5"}, {"display_name": "Nowhere"}, {"display_name": "Bad", "lat": "x", "lon": "y"}]""")
    GeocoderService(config(enabled = true)).search("Rue de l'Église & co") shouldEqual List(GeocoderResult("Somewhere", 1.5, 2.5))
    lastQuery.value should include("q=Rue+de+l%27%C3%89glise+%26+co")
  }

  test("An upstream failure or a body that is not a place list is a GeocoderException") {

    /**
     * Setup:
     *
     * A stub answering in turn with a 503, an HTML page, and a JSON error object.
     *
     * Assertions:
     *
     * Each of them fails the search with a GeocoderException rather than an empty result.
     *
     * Edge cases:
     *
     * A non-200 status, a body that is not JSON, and JSON that is not a list.
     */
    response = (503, "busy")
    intercept[GeocoderException] {
      GeocoderService(config(enabled = true)).search("Paris")
    }

    response = (200, "<html>not json</html>")
    intercept[GeocoderException] {
      GeocoderService(config(enabled = true)).search("Paris")
    }

    response = (200, """{"error": "Unable to geocode"}""")
    intercept[GeocoderException] {
      GeocoderService(config(enabled = true)).search("Paris")
    }
  }
}
