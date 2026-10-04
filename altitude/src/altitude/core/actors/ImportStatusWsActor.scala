package altitude.core.actors

import cask.WsChannelActor
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import altitude.core.AltitudeActorSystem
import altitude.core.DuplicateException
import altitude.core.ImageException
import altitude.core.StorageException
import altitude.core.UnsupportedMediaTypeException
import altitude.core.VideoException
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalid

object ImportStatusWsActor:
  sealed trait Command
  final case class AddClient(userId: String, client: WsChannelActor) extends AltitudeActorSystem.Command with Command
  final case class UserWideImportStatus(userId: String, assetOrInvalid: TAssetOrInvalid)
    extends AltitudeActorSystem.Command
    with Command
  final case class RemoveClient(userId: String, client: WsChannelActor) extends AltitudeActorSystem.Command with Command

  private val successStatusTickerTemplate = "<div id=\"statusText\">%s</div>"
  private val warningStatusTickerTemplate = "<div id=\"statusText\" class=\"warning\">%s</div>"
  private val errorStatusTickerTemplate = "<div id=\"statusText\" class=\"error\">%s</div>"

  def apply(): Behavior[Command] = Behaviors.setup(context => new ImportStatusWsActor(context))

class ImportStatusWsActor(context: ActorContext[ImportStatusWsActor.Command])
  extends AbstractBehavior[ImportStatusWsActor.Command](context):

  import ImportStatusWsActor.*

  private val userToWsClientLookup = collection.mutable.Map[String, List[WsChannelActor]]()

  override def onMessage(msg: ImportStatusWsActor.Command): Behavior[ImportStatusWsActor.Command] =
    Behaviors.same
    msg match {
      case AddClient(userId, client) =>
        context.log.trace(s"Adding client $client for user $userId")
        val clients = userToWsClientLookup.getOrElse(userId, List())
        userToWsClientLookup.update(userId, client :: clients)
        Behaviors.same

      case UserWideImportStatus(userId, assetOrInvalid) =>
        context.log.trace(s"Sending message to WS clients for user $userId")
        userToWsClientLookup.get(userId).foreach {
          clients =>
            clients.foreach {
              client =>
                context.log.trace(s"Sending message to client $client")

                val wsContent = assetOrInvalid match {
                  case Left(asset) =>
                    successStatusTickerTemplate.format(s"Imported ${asset.fileName}")

                  case Right(invalid) =>
                    val fileName = invalid.payload.fileName
                    invalid.cause match {
                      case Some(_: DuplicateException) =>
                        warningStatusTickerTemplate.format(s"Error importing $fileName: Duplicate asset")
                      case Some(_: UnsupportedMediaTypeException) =>
                        errorStatusTickerTemplate.format(s"Error importing $fileName: Unsupported media type")
                      case Some(_: ImageException) =>
                        errorStatusTickerTemplate.format(s"Error importing $fileName: Cannot decode image")
                      case Some(_: VideoException) =>
                        errorStatusTickerTemplate.format(s"Error importing $fileName: Cannot decode video")
                      case Some(_: StorageException) =>
                        errorStatusTickerTemplate.format(s"Error importing $fileName: Storage error")
                      // An exception's message may carry paths or SQL, so a cause not named above is shown by its class alone
                      case cause =>
                        val name = cause.fold("Unknown error")(_.getClass.getSimpleName)
                        errorStatusTickerTemplate.format(s"Error importing $fileName: $name")
                    }
                }

                client.send(cask.Ws.Text(wsContent))
            }
        }
        Behaviors.same

      case RemoveClient(userId, client) =>
        context.log.trace(s"Removing client $client for user $userId")
        userToWsClientLookup.get(userId).foreach(clients => userToWsClientLookup.update(userId, clients.filterNot(_ == client)))
        Behaviors.same
    }
