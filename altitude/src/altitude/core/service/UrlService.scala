package altitude.core.service

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.RequestContext

class UrlService:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /** The bookmarkable URL of a search. The parameters are ordered, not a map, so the same search always gives the same URL. */
  def getBrowserViewUrl(queryParams: Seq[(String, String)], browserUrl: String): String =
    val queryString = constructQueryString(queryParams)
    logger.trace("Sending browser view URL: " + queryString)
    s"/r/${RequestContext.getRepository.persistedId}?$queryString" + fragmentOf(browserUrl)

  /**
   * The "#tab" part of the browser URL, kept so replacing the URL does not switch the explorer tab. Found by index, as `split`
   * drops a trailing empty piece and would hand back the whole URL for one ending in "#".
   */
  private def fragmentOf(browserUrl: String): String =
    if browserUrl == null then return ""

    val hashIdx = browserUrl.indexOf('#')
    if hashIdx < 0 || hashIdx == browserUrl.length - 1 then "" else browserUrl.substring(hashIdx)

  private def constructQueryString(params: Seq[(String, String)]): String =
    params.map { case (key, value) => s"$key=${encode(value)}" }.mkString("&")

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
