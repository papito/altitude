package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude

/**
 * Renders an asset's Edit at its head into the edited Preview and the Display image. `AssetEditService` calls [[enqueue]] once a
 * change of an Edit's head has committed, in the request's context (its repository and account).
 *
 * The rendering itself, its queue and its debounce are not built yet: [[enqueue]] only records the request.
 */
class EditRenderService(app: Altitude):
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /** Queues the asset's Edit, in the context repository, to be rendered at its head */
  def enqueue(assetId: String): Unit =
    logger.debug(s"Render of the Edit of asset [$assetId] requested")
