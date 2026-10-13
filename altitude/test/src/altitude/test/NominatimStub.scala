package altitude.test

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * A stub of a Nominatim-compatible endpoint on localhost, so a geocoder test touches no network: it answers `/search` with
 * whatever `response` holds (status and body, an empty list by default) and remembers the last request's query string and
 * `User-Agent`
 */
class NominatimStub {
  private val server: HttpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

  /** What the stub answers next */
  @volatile var response: (Int, String) = (200, "[]")

  /** The last request seen, as its raw query string and its `User-Agent` */
  @volatile var lastQuery: Option[String] = None
  @volatile var lastUserAgent: Option[String] = None

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

  /** The endpoint's URL, as the `map.geocoder.url` Setting takes it */
  def url: String = s"http://127.0.0.1:${server.getAddress.getPort}/search"

  def start(): Unit = server.start()
  def stop(): Unit = server.stop(0)
}
