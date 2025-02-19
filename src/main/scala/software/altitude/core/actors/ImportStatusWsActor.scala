package software.altitude.core.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatra.atmosphere.AtmosphereClient
import org.scalatra.atmosphere.TextMessage
import software.altitude.core.AltitudeActorSystem
import software.altitude.core.DuplicateException
import software.altitude.core.StorageException
import software.altitude.core.UnsupportedMediaTypeException
import software.altitude.core.pipeline.PipelineTypes.TAssetOrInvalid

import scala.concurrent.ExecutionContext.Implicits.global

object ImportStatusWsActor {
  sealed trait Command
  final case class AddClient(userId: String, client: AtmosphereClient) extends AltitudeActorSystem.Command with Command
  final case class UserWideImportStatus(userId: String, assetOrInvalid: TAssetOrInvalid)
    extends AltitudeActorSystem.Command
    with Command
  final case class RemoveClient(userId: String, client: AtmosphereClient) extends AltitudeActorSystem.Command with Command

  private val successStatusTickerTemplate = "<div id=\"statusText\">%s</div>"
  private val warningStatusTickerTemplate = "<div id=\"statusText\" class=\"warning\">%s</div>"
  private val errorStatusTickerTemplate = "<div id=\"statusText\" class=\"error\">%s</div>"

  def apply(): Behavior[Command] = Behaviors.setup(context => new ImportStatusWsActor(context))
}

class ImportStatusWsActor(context: ActorContext[ImportStatusWsActor.Command])
  extends AbstractBehavior[ImportStatusWsActor.Command](context) {

  import ImportStatusWsActor._

  private val userToWsClientLookup = collection.mutable.Map[String, List[AtmosphereClient]]()

  override def onMessage(msg: ImportStatusWsActor.Command): Behavior[ImportStatusWsActor.Command] = {
    msg match {
      case AddClient(userId, client) =>
        context.log.info(s"Adding client $client for user $userId")
        val clients = userToWsClientLookup.getOrElse(userId, List())
        userToWsClientLookup.update(userId, client :: clients)
        Behaviors.same

      case UserWideImportStatus(userId, assetOrInvalid) =>
        context.log.info(s"Sending message to WS clients for user $userId")
        userToWsClientLookup.get(userId).foreach {
          clients =>
            clients.foreach {
              client =>
                context.log.info(s"Sending message to client $client")

                val wsContent = assetOrInvalid match {
                  case Left(asset) =>
                    successStatusTickerTemplate.format(s"Imported ${asset.fileName}")

                  case Right(invalid) =>
                    val errorMessage = invalid.cause.get match {
                      case _: DuplicateException =>
                        warningStatusTickerTemplate.format(s"Error importing ${invalid.payload.fileName}: Duplicate asset")
                      case _: UnsupportedMediaTypeException =>
                        errorStatusTickerTemplate.format(s"Error importing ${invalid.payload.fileName}: Unsupported media type")
                      case _: StorageException =>
                        errorStatusTickerTemplate.format(s"Error importing ${invalid.payload.fileName}: Storage error")
                      case _ =>
                        errorStatusTickerTemplate.format(s"Unknown error importing ${invalid.payload.fileName}")
                    }
                    errorMessage
                }

                client.send(TextMessage(wsContent))
            }
        }
        Behaviors.same

      case RemoveClient(userId, client) =>
        context.log.info(s"Removing client $client for user $userId")
        userToWsClientLookup.get(userId).foreach(clients => userToWsClientLookup.update(userId, clients.filterNot(_ == client)))
        Behaviors.same
    }
  }
}
