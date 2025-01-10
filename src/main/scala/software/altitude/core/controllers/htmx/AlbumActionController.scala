package software.altitude.core.controllers.htmx

import org.scalatra.Route

import software.altitude.core.Api
import software.altitude.core.controllers.BaseHtmxController

/** @ /htmx/album/ */
class AlbumActionController extends BaseHtmxController {

  before() {
    requireLogin()
  }

  val showAlbumsTab: Route = get("/r/:repoId/tab") {
    ssp("htmx/albums", Api.Field.ALBUM.ALBUMS -> List())
  }

}
