package altitude.core

import altitude.core.actors.ImportStatusWsActor
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.Signal
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.Timeout
import org.slf4j.Logger

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object AltitudeActorSystem {
  trait Command
  final case class EmptyResponse() extends Command
  def apply(): Behavior[Command] = Behaviors.setup(context => new AltitudeActorSystem(context))
}

private class AltitudeActorSystem(context: ActorContext[AltitudeActorSystem.Command])
  extends AbstractBehavior[AltitudeActorSystem.Command](context) {

  private val websocketImportStatusManagerActor = context.spawn(ImportStatusWsActor(), "importStatusWsActor")

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = context.system.scheduler
  implicit val ec: ExecutionContext = context.executionContext
  val logger: Logger = context.log

  /** Route messages to the appropriate actors and actor managers */
  override def onMessage(msg: AltitudeActorSystem.Command): Behavior[AltitudeActorSystem.Command] = {
    msg match {
      case command: ImportStatusWsActor.Command =>
        websocketImportStatusManagerActor ! command
        Behaviors.same

      case _ =>
        Behaviors.unhandled
    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[AltitudeActorSystem.Command]] = {
    case PostStop =>
      logger.info("Actor system stopped")
      this
  }

  logger.info("Actor system started")
}
