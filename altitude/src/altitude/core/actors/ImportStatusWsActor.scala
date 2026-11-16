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
 * The import status feed: the Import pages' sockets, by Library and account. An Import's status goes to every socket of its
 * Library at most once an interval while it is in progress, and once more when it closes. A Watch folder change is told at once.
 */
object ImportStatusWsActor:
  sealed trait Command

  final case class AddClient(repositoryId: String, userId: String, client: castor.Actor[cask.Ws.Event])
    extends AltitudeActorSystem.Command
    with Command

  final case class RemoveClient(repositoryId: String, userId: String, client: castor.Actor[cask.Ws.Event])
    extends AltitudeActorSystem.Command
    with Command

  /** An Import as a commit left it */
  final case class ImportChanged(repositoryId: String, anImport: Import) extends AltitudeActorSystem.Command with Command

  final case class WatchFoldersChanged(repositoryId: String) extends AltitudeActorSystem.Command with Command

  /**
   * Closes and forgets the account's sockets in every Library: its versions changed, so what opened them may no longer hold. A
   * bump closes with 1013, which the client reconnects on (the handshake checks login and Permission again); a revocation with
   * 1008, which it does not.
   */
  final case class CloseAllFor(userId: String, change: AccountVersions.Change) extends AltitudeActorSystem.Command with Command

  // The Import's interval has passed: push it again if it changed meanwhile
  final private case class PushDue(importId: String) extends Command

  // A closed Import has been remembered long enough
  final private case class Forget(importId: String) extends Command

  private val TRY_AGAIN_LATER = 1013
  private val POLICY_VIOLATION = 1008

  val PUSH_INTERVAL: FiniteDuration = 1.second

  // How long a closed Import is remembered, so a change told after the close it preceded is not pushed
  private val FORGET_AFTER: FiniteDuration = 1.minute

  val CONNECTED: String = ujson.write(ujson.Obj(Api.Field.ImportStatus.CONNECTED -> true))

  /** Tells the page to read its Watch folders again; it swaps nothing */
  val WATCH_FOLDERS_CHANGED: String = ujson.write(ujson.Obj(Api.Field.ImportStatus.WATCH_FOLDERS -> true))

  /** An Import's status element for `hx-ws` to swap in. Rendered by Twirl, which escapes the Import's name. */
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
   * A number every change to an Import raises, since a file added, a file settled, completion and close each only add to one of
   * its parts: of two states of one Import, the later has the greater
   */
  private def progress(anImport: Import): Int =
    anImport.total + (anImport.total - anImport.pending) + (if anImport.isComplete then 1 else 0) +
      (if anImport.isInProgress then 0 else 1)

  /** `pushInterval` is the least time between two pushes of one Import */
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

  /** An Import as last heard of. `isWaiting`: its interval is running, so a change is held; `isUnsent`: one was held. */
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

      case WatchFoldersChanged(repositoryId) =>
        val message = cask.Ws.Text(WATCH_FOLDERS_CHANGED)
        sockets.getOrElse(repositoryId, Map.empty).values.flatten.foreach(_.send(message))

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
   * A change outside an interval is pushed at once and starts one; one inside is held for the interval's end. The close is pushed
   * at once and is the last message: changes can arrive out of order, so one no further along than what is known is dropped.
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

  /** Renders only when there is a socket to send to. A failed render is logged and skipped: the next change tries again. */
  private def push(feed: Feed): Unit =
    feed.isUnsent = false
    val clients = sockets.getOrElse(feed.repositoryId, Map.empty).values.flatten
    if clients.nonEmpty then
      try
        val message = cask.Ws.Text(render(feed.latest))
        context.log.trace(s"Pushing Import ${feed.latest.persistedId} to ${clients.size} clients of ${feed.repositoryId}")
        clients.foreach(_.send(message))
      catch case NonFatal(e) => context.log.warn(s"Could not push the status of Import ${feed.latest.persistedId}", e)
