package software.altitude.core.service

import javax.servlet.http.HttpServletRequest
import software.altitude.core.RequestContext
``
class UrlService {
  def getBrowserViewUrl(request: HttpServletRequest): String =
    s"/r/${RequestContext.getRepository.persistedId}?${request.getQueryString}" + gerFragment(request)

  private def gerFragment(request: HttpServletRequest): String = {
    val currentUrl = request.getHeader("HX-Current-URL")

    if (currentUrl == null) return ""

    val urlFragment = currentUrl.split("#").lastOption.getOrElse("")
    if (urlFragment.isEmpty) "" else s"#$urlFragment"
  }
}
