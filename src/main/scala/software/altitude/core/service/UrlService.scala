package software.altitude.core.service

import javax.servlet.http.HttpServletRequest
import software.altitude.core.RequestContext
import software.altitude.core.Api

import scala.jdk.CollectionConverters.MapHasAsScala

class UrlService {
  def getBrowserViewUrl(request: HttpServletRequest): String = {
    val urlParams = request.getParameterMap.asScala.map {
      case (key, values) => key -> values.toSeq
    }.toMap

    // based on the url params we got, construct the url for the browser view
    val browserViewUrlParams = urlParams.foldLeft(Map[String, String]()) {
      case (acc, (key, values)) =>
      key match {
        case Api.Field.Search.PEOPLE_IDS =>
          acc ++ Map(Api.Field.Search.VIEW -> Api.Field.Search.PERSON, Api.Field.Search.PERSON_ID -> values.head)
        case Api.Field.Search.SORT =>
          acc ++ Map(Api.Field.Search.SORT -> values.head)
        case Api.Field.Search.PREVIEW_SIZE =>
            acc ++ Map(Api.Field.Search.PREVIEW_SIZE -> values.head)
        case _ => acc
      }
    }
    val compiledUrlParams = browserViewUrlParams.map{ case (k, v) => s"$k=$v" }.mkString("&")

    s"/r/${RequestContext.getRepository.persistedId}?$compiledUrlParams" + gerFragment(request)
  }

  private def gerFragment(request: HttpServletRequest): String = {
    val currentUrl = request.getHeader("HX-Current-URL")

    if (currentUrl == null) return ""

    val urlFragment = currentUrl.split("#").lastOption.getOrElse("")
    if (urlFragment.isEmpty) "" else s"#$urlFragment"
  }
}
