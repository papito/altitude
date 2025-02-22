package software.altitude.core.service

import javax.servlet.http.HttpServletRequest

import software.altitude.core.RequestContext

class UrlService {
  def getUrlForPersonView(request: HttpServletRequest, personId: String): String = {
    s"/r/${RequestContext.getRepository.persistedId}?view=person&personId=$personId" + gerFragment(request)
  }

  private def gerFragment(request: HttpServletRequest): String = {
    val currentUrl = request.getHeader("HX-Current-URL")

    if (currentUrl == null) return ""

    val urlFragment = currentUrl.split("#").lastOption.getOrElse("")
    if (urlFragment.isEmpty) "" else s"#$urlFragment"
  }
}
