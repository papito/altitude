package software.altitude.core.controllers.htmx

import org.scalatra.Route
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.RequestContext

/**
  * @ /htmx/album/
 */
class AlbumActionController extends BaseHtmxController{

  before() {
    requireLogin()

    if (RequestContext.repository.value.isEmpty){
      throw new RuntimeException("Repository not set")
    }
  }

  val showAlbumsTab: Route = get("/tab") {
    ssp("htmx/albums", "albums" -> List())
  }

}
