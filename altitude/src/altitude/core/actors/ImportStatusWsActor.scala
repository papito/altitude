package altitude.core.actors

import cask.WsChannelActor
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import altitude.core.AltitudeActorSystem
import altitude.core.DuplicateException
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalid
import altitude.core.pipeline.PipelineUtils
import altitude.core.service.AccountVersions

object ImportStatusWsActor:
  sealed trait Command
  final case class AddClient(userId: String, client: WsChannelActor) extends AltitudeActorSystem.Command with Command
  final case class UserWideImportStatus(userId: String, assetOrInvalid: TAssetOrInvalid)
    extends AltitudeActorSystem.Command
    with Command
  final case class RemoveClient(userId: String, client: WsChannelActor) extends AltitudeActorSystem.Command with Command

  /**
   * Closes every socket of the account and forgets them: its versions changed (`AccountVersions`), so what opened them may hold
   * no more. A change closes them with 1013 (try again later), which the page's client reconnects on, its handshake checking the
   * login again (refused for an account deactivated or deleted); a revocation with 1008 (policy violation), which it does not.
   */
  final case class CloseAllFor(userId: String, change: AccountVersions.Change) extends AltitudeActorSystem.Command with Command

  private val TRY_AGAIN_LATER = 1013
  private val POLICY_VIOLATION = 1008

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
                    // A duplicate is a warning, any other drop an error; each worded by its cause's name, never its message
                    val template = invalid.cause match {
                      case Some(_: DuplicateException) => warningStatusTickerTemplate
                      case _ => errorStatusTickerTemplate
                    }
                    template.format(s"Error importing ${invalid.payload.fileName}: ${PipelineUtils.dropReason(invalid.cause)}")
                }

                client.send(cask.Ws.Text(wsContent))
            }
        }
        Behaviors.same

      case CloseAllFor(userId, change) =>
        val (code, reason) = change match
          case AccountVersions.Change.Bumped => (TRY_AGAIN_LATER, "Account changed")
          case AccountVersions.Change.Revoked => (POLICY_VIOLATION, "Session revoked")
        userToWsClientLookup.remove(userId).toList.flatten.foreach {
          client =>
            context.log.debug(s"Closing client $client of user $userId: $reason")
            client.send(cask.Ws.Close(code, reason))
        }
        Behaviors.same

      case RemoveClient(userId, client) =>
        context.log.trace(s"Removing client $client for user $userId")
        userToWsClientLookup.get(userId).foreach(clients => userToWsClientLookup.update(userId, clients.filterNot(_ == client)))
        Behaviors.same
    }
