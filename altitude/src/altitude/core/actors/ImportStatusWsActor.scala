package altitude.core.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import altitude.core.AltitudeActorSystem
import altitude.core.Api
import altitude.core.Const
import altitude.core.models.Import
import altitude.core.service.AccountVersions

/**
 * The import status feed: the sockets the Import pages hold open (`ImportController.pipelineStatus`), kept by Library and
 * account, and the Imports' statuses pushed to them. `ImportService` tells of an Import after each commit that changes it
 * (`Altitude` wires `ImportService.onChange` to [[ImportStatusWsActor.ImportChanged]]); the actor keeps each Import as it last
 * heard of it and sends its status (includes/import_status, as `hx-ws` takes it) to every socket of the Import's Library, at most
 * once an interval while the Import is in progress, and once more when it closes.
 */
object ImportStatusWsActor:
  sealed trait Command

  /** A socket of the account, opened on the Library's Import page, to tell of that Library's Imports from now on */
  final case class AddClient(repositoryId: String, userId: String, client: castor.Actor[cask.Ws.Event])
    extends AltitudeActorSystem.Command
    with Command

  final case class RemoveClient(repositoryId: String, userId: String, client: castor.Actor[cask.Ws.Event])
    extends AltitudeActorSystem.Command
    with Command

  /** An Import of the Library as a change committed it (`ImportService.onChange`) */
  final case class ImportChanged(repositoryId: String, anImport: Import) extends AltitudeActorSystem.Command with Command

  /**
   * Closes every socket of the account, in every Library, and forgets them: its versions changed (`AccountVersions`), so what
   * opened them may hold no more. A change closes them with 1013 (try again later), which the page's client reconnects on, its
   * handshake checking the login and the Permission again (refused for an account deactivated or deleted, or one that lost Import
   * there); a revocation with 1008 (policy violation), which it does not.
   */
  final case class CloseAllFor(userId: String, change: AccountVersions.Change) extends AltitudeActorSystem.Command with Command

  // The Import's interval has passed: its status is pushed again if it changed meanwhile
  final private case class PushDue(importId: String) extends Command

  // A closed Import has been remembered long enough
  final private case class Forget(importId: String) extends Command

  private val TRY_AGAIN_LATER = 1013
  private val POLICY_VIOLATION = 1008

  // An Import's status is pushed at most once in this long, and once more at its close
  val PUSH_INTERVAL: FiniteDuration = 1.second

  // How long a closed Import is remembered, so a change told late, after the close it came before, is not pushed
  private val FORGET_AFTER: FiniteDuration = 1.minute

  /** What a socket is sent once it is added: from then on it is told of its Library's Imports */
  val CONNECTED: String = ujson.write(ujson.Obj(Api.Field.ImportStatus.CONNECTED -> true))

  /**
   * The message of an Import's status, as `hx-ws` takes it: the Import's status element (includes/import_status), which replaces
   * the one of its ID on the page, with the Import's ID and whether it is closed for the page's script (js/import-status.js).
   * Rendered by Twirl, which escapes the Import's name.
   */
  def statusMessage(anImport: Import): String =
    ujson.write(
      ujson.Obj(
        Api.Field.ImportStatus.TARGET -> s"#${Const.ImportHistory.statusId(anImport.persistedId)}",
        Api.Field.ImportStatus.SWAP -> "outerHTML",
        Api.Field.ImportStatus.CONTENT -> includes.html.import_status(anImport, isPushed = true).body,
        Api.Field.ImportStatus.IMPORT_ID -> anImport.persistedId,
        Api.Field.ImportStatus.IS_CLOSED -> !anImport.isInProgress
      ))

  /**
   * How far along an Import is: a number that every change to it raises, since a file added, a file settled, the mark that it is
   * complete and its close each only ever add to one of its parts. Of two states of one Import the later has the greater.
   */
  private def progress(anImport: Import): Int =
    anImport.total + (anImport.total - anImport.pending) + (if anImport.isComplete then 1 else 0) +
      (if anImport.isInProgress then 0 else 1)

  /** `render` is the message of an Import's status and `pushInterval` the least time between two of one Import */
  def apply(render: Import => String = statusMessage, pushInterval: FiniteDuration = PUSH_INTERVAL): Behavior[Command] =
    Behaviors.setup(context => Behaviors.withTimers(timers => new ImportStatusWsActor(context, timers, render, pushInterval)))

class ImportStatusWsActor(
    context: ActorContext[ImportStatusWsActor.Command],
    timers: TimerScheduler[ImportStatusWsActor.Command],
    render: Import => String,
    pushInterval: FiniteDuration)
  extends AbstractBehavior[ImportStatusWsActor.Command](context):

  import ImportStatusWsActor.*

  private type Client = castor.Actor[cask.Ws.Event]

  /**
   * An Import as the actor last heard of it, in its Library. `isWaiting` says its interval is running, so a change is held until
   * the interval ends, and `isUnsent` that one was held.
   */
  final private class Feed(val repositoryId: String, var latest: Import, var isWaiting: Boolean, var isUnsent: Boolean)

  // The open sockets: Library, then account
  private val sockets = collection.mutable.Map[String, Map[String, List[Client]]]()

  // The Imports in progress, and the closed ones until they are forgotten
  private val feeds = collection.mutable.Map[String, Feed]()

  override def onMessage(msg: ImportStatusWsActor.Command): Behavior[ImportStatusWsActor.Command] =
    msg match
      case AddClient(repositoryId, userId, client) =>
        context.log.trace(s"Adding client $client of user $userId on repository $repositoryId")
        val ofRepository = sockets.getOrElse(repositoryId, Map.empty)
        sockets.update(repositoryId, ofRepository.updated(userId, client :: ofRepository.getOrElse(userId, Nil)))
        client.send(cask.Ws.Text(CONNECTED))

      case ImportChanged(repositoryId, anImport) =>
        importChanged(repositoryId, anImport)

      case PushDue(importId) =>
        feeds.get(importId).foreach {
          feed =>
            if feed.isUnsent then
              push(feed)
              timers.startSingleTimer(importId, PushDue(importId), pushInterval)
            else feed.isWaiting = false
        }

      case Forget(importId) =>
        feeds.remove(importId): Unit

      case CloseAllFor(userId, change) =>
        val (code, reason) = change match
          case AccountVersions.Change.Bumped => (TRY_AGAIN_LATER, "Account changed")
          case AccountVersions.Change.Revoked => (POLICY_VIOLATION, "Session revoked")
        for (repositoryId, ofRepository) <- sockets.toList do
          ofRepository.getOrElse(userId, Nil).foreach {
            client =>
              context.log.debug(s"Closing client $client of user $userId: $reason")
              client.send(cask.Ws.Close(code, reason))
          }
          keep(repositoryId, ofRepository - userId)

      case RemoveClient(repositoryId, userId, client) =>
        context.log.trace(s"Removing client $client of user $userId on repository $repositoryId")
        sockets.get(repositoryId).foreach {
          ofRepository =>
            val left = ofRepository.getOrElse(userId, Nil).filterNot(_ == client)
            keep(repositoryId, if left.isEmpty then ofRepository - userId else ofRepository.updated(userId, left))
        }
    Behaviors.same

  /** The Library's sockets after a change, or no entry once it has none */
  private def keep(repositoryId: String, ofRepository: Map[String, List[Client]]): Unit =
    if ofRepository.isEmpty then sockets.remove(repositoryId): Unit else sockets.update(repositoryId, ofRepository)

  /**
   * An Import changed. Its first change, and one after an interval with none, is pushed at once and starts an interval; one
   * inside an interval is held, and the interval's end pushes the Import as it stands then. Its close is pushed at once, whatever
   * the interval, and is the last message of the Import: the changes of one Import can be told out of order
   * (`ImportService.changed`), so one that is not further along than what the actor has is dropped, as is any after the close.
   */
  private def importChanged(repositoryId: String, anImport: Import): Unit =
    val importId = anImport.persistedId
    val feed = feeds.get(importId) match
      case Some(known) if progress(anImport) <= progress(known.latest) =>
        context.log.trace(s"Import $importId was told no further along than it is known to be")
        return
      case Some(known) =>
        known.latest = anImport
        known
      case None =>
        val added = Feed(repositoryId, anImport, isWaiting = false, isUnsent = false)
        feeds.update(importId, added)
        added

    if !anImport.isInProgress then
      push(feed)
      // Under the Import's key, so this takes the place of its interval's timer: nothing more is due
      timers.startSingleTimer(importId, Forget(importId), FORGET_AFTER)
    else if feed.isWaiting then feed.isUnsent = true
    else
      push(feed)
      feed.isWaiting = true
      timers.startSingleTimer(importId, PushDue(importId), pushInterval)

  /**
   * Sends the Import's status to every socket of its Library, rendered once and only when there is a socket to send it to. A
   * status that cannot be rendered is logged and not sent: the next change tries again.
   */
  private def push(feed: Feed): Unit =
    feed.isUnsent = false
    val clients = sockets.getOrElse(feed.repositoryId, Map.empty).values.flatten
    if clients.nonEmpty then
      try
        val message = cask.Ws.Text(render(feed.latest))
        context.log.trace(s"Pushing Import ${feed.latest.persistedId} to ${clients.size} clients of ${feed.repositoryId}")
        clients.foreach(_.send(message))
      catch case NonFatal(e) => context.log.warn(s"Could not push the status of Import ${feed.latest.persistedId}", e)
