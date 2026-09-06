package altitude.core.service

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.RequestContext

class UrlService:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * The user-friendly, bookmarkable URL for a set of search results, pushed back to the browser via `HX-Replace-Url`.
   *
   * The parameters are an ordered sequence, not a map, so the same search always produces the same URL.
   */
  def getBrowserViewUrl(queryParams: Seq[(String, String)], browserUrl: String): String =
    val queryString = constructQueryString(queryParams)
    logger.trace("Sending browser view URL: " + queryString)
    s"/r/${RequestContext.getRepository.persistedId}?$queryString" + fragmentOf(browserUrl)

  /**
   * The "#tab" part of the browser URL, preserved so replacing the URL does not switch the explorer tab.
   *
   * Taken by index rather than by splitting: a URL with no "#", or with an empty one, has no fragment, but `split` drops the
   * trailing empty piece and would hand back the whole URL.
   */
  private def fragmentOf(browserUrl: String): String =
    if browserUrl == null then return ""

    val hashIdx = browserUrl.indexOf('#')
    if hashIdx < 0 || hashIdx == browserUrl.length - 1 then "" else browserUrl.substring(hashIdx)

  private def constructQueryString(params: Seq[(String, String)]): String =
    params.map { case (key, value) => s"$key=${encode(value)}" }.mkString("&")

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
