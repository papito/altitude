package software.altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.{Api, RequestContext}

class UrlService {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  def getBrowserViewUrl(combinedQueryParams: Map[String, String], browserUrl: String): String = {
    val queryString = constructQueryString(combinedQueryParams)
    logger.trace("Sending browser view URL: " + queryString)
    s"/r/${RequestContext.getRepository.persistedId}?$queryString" + gerFragment(browserUrl)
  }

  private def gerFragment(browserUrl: String): String = {
    if (browserUrl == null) return ""

    val urlFragment = browserUrl.split("#").lastOption.getOrElse("")
    if (urlFragment.isEmpty) "" else s"#$urlFragment"
  }

  def getUrlParams(queryString: String): Map[String, String] = {
    Option(queryString)
      .map {
        q =>
          q.split("&")
            .map {
              param =>
                val parts = param.split("=", 2)
                parts(0) -> (if (parts.length > 1) parts(1) else "")
            }
            .toMap
            .filter(_._1.nonEmpty)
      }
      .getOrElse(Map.empty[String, String])
  }

  private def constructQueryString(params: Map[String, String]): String = {
    if (params.isEmpty) "" else params.map { case (key, value) => s"$key=$value" }.mkString("&")
  }
}
