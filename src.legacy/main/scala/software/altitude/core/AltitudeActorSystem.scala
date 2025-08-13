package software.altitude.core

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.Signal
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.Timeout
import org.slf4j.Logger

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

import software.altitude.core.actors.FaceRecManagerActor
import software.altitude.core.actors.FaceRecManagerActor.Initialize
import software.altitude.core.actors.ImportStatusWsActor

object AltitudeActorSystem {
  trait Command
  final case class EmptyResponse() extends Command
  def apply(): Behavior[Command] = Behaviors.setup(context => new AltitudeActorSystem(context))
}

private class AltitudeActorSystem(context: ActorContext[AltitudeActorSystem.Command])
  extends AbstractBehavior[AltitudeActorSystem.Command](context) {

  private val websocketImportStatusManagerActor = context.spawn(ImportStatusWsActor(), "importStatusWsActor")
  private val faceRecManagerActor = context.spawn(FaceRecManagerActor(), "faceRecManagerActor")

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

      case command: Initialize =>
        faceRecManagerActor
          .ask(FaceRecManagerActor.Initialize(command.repositoryId, _))
          .onComplete {
            case Success(response) => command.replyTo ! response
            case Failure(exception) => logger.error("Failed to initialize face rec model actor", exception)
          }(ec)
        Behaviors.same

      case command: FaceRecManagerActor.AddFaces =>
        faceRecManagerActor ! FaceRecManagerActor.AddFaces(command.repositoryId, command.faces)
        Behaviors.same

      case command: FaceRecManagerActor.AddFace =>
        faceRecManagerActor ! FaceRecManagerActor.AddFace(command.repositoryId, command.face, command.personLabel)
        Behaviors.same

      case command: FaceRecManagerActor.Predict =>
        faceRecManagerActor
          .ask(FaceRecManagerActor.Predict(command.repositoryId, command.face, _))
          .onComplete {
            case Success(response) => command.replyTo ! response
            case Failure(exception) => logger.error("Failed to run face ec", exception)
          }(ec)
        Behaviors.same

      case command: FaceRecManagerActor.GetModelSize =>
        faceRecManagerActor
          .ask(FaceRecManagerActor.GetModelSize(command.repositoryId, _))
          .onComplete {
            case Success(response) => command.replyTo ! response
            case Failure(exception) => logger.error("Failed to get model size", exception)
          }(ec)
        Behaviors.same

      case command: FaceRecManagerActor.GetModelLabels =>
        faceRecManagerActor
          .ask(FaceRecManagerActor.GetModelLabels(command.repositoryId, _))
          .onComplete {
            case Success(response) => command.replyTo ! response
            case Failure(exception) => logger.error("Failed to get model labels", exception)
          }(ec)
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
