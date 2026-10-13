package altitude.core.service

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.util.Try

import altitude.core.GeocoderException
import altitude.core.IllegalOperationException
import altitude.core.models.GeocoderResult
import altitude.core.models.Settings

object GeocoderService:
  private val TIMEOUT: Duration = Duration.ofSeconds(5)
  private val RESULT_LIMIT = 5

  /** The Nominatim usage policy asks every client to identify itself; the public service refuses a generic agent */
  private val USER_AGENT = "Altitude (+https://github.com/papito/altitude)"

/**
 * Place-name search for the Add Location dialog, proxied through the server so the browser never talks to the geocoder and the
 * one identifying `User-Agent` is set in one place. Off unless `map.geocoder.enabled` is set: every query is sent to a third
 * party. The endpoint (`map.geocoder.url`) is Nominatim-compatible: `?q=&format=json&limit=` answered with a list of places
 * carrying `display_name`, `lat` and `lon`.
 */
class GeocoderService(settings: () => Settings):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private lazy val client: HttpClient =
    HttpClient.newBuilder().connectTimeout(GeocoderService.TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build()

  /** The places matching the text, best first; nothing for blank text. Refused when the geocoder is disabled. */
  def search(text: String): List[GeocoderResult] =
    val current = settings()
    if !current.mapGeocoderEnabled then throw IllegalOperationException("The geocoder is disabled (map.geocoder.enabled)")

    val query = text.trim
    if query.isEmpty then return Nil

    val url = current.mapGeocoderUrl
    val parameters = s"q=${URLEncoder.encode(query, StandardCharsets.UTF_8)}&format=json&limit=${GeocoderService.RESULT_LIMIT}"
    val uri = URI.create(url + (if url.contains("?") then "&" else "?") + parameters)
    val request = HttpRequest
      .newBuilder(uri)
      .timeout(GeocoderService.TIMEOUT)
      .header("User-Agent", GeocoderService.USER_AGENT)
      .header("Accept", "application/json")
      .GET()
      .build()

    logger.debug(s"Geocoding [$query]")
    val response =
      try client.send(request, HttpResponse.BodyHandlers.ofString())
      catch case e: Exception => throw GeocoderException(s"The geocoder could not be reached: ${e.getMessage}")

    if response.statusCode != 200 then throw GeocoderException(s"The geocoder answered ${response.statusCode}")

    val places = Try(ujson.read(response.body).arr.toList)
      .getOrElse(throw GeocoderException("The geocoder did not answer with a list of places"))

    // A place the geocoder could not pin is of no use to a dialog that needs a pin; skip it rather than fail the search
    val results = places.flatMap {
      place =>
        for
          label <- place.obj.get("display_name").map(_.str)
          latitude <- place.obj.get("lat").flatMap(value => value.str.toDoubleOption)
          longitude <- place.obj.get("lon").flatMap(value => value.str.toDoubleOption)
        yield GeocoderResult(label, latitude, longitude)
    }
    logger.debug(s"Geocoded [$query] to ${results.length} of ${places.length} places")
    results
