package altitude.core.actors

import altitude.core.{Altitude, AltitudeActorSystem}
import altitude.core.actors.FaceRecModelActor.FacePrediction
import altitude.core.actors.FaceRecModelActor.ModelLabels
import altitude.core.actors.FaceRecModelActor.ModelSize
import altitude.core.models.Face
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.Scheduler
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

object FaceRecManagerActor {
  sealed trait Command
  final case class AddFace(repositoryId: String, face: Face, personLabel: Int) extends AltitudeActorSystem.Command with Command

  final case class AddFaces(repositoryId: String, faces: Seq[Face]) extends AltitudeActorSystem.Command with Command
  final case class Initialize(app: Altitude, replyTo: ActorRef[AltitudeActorSystem.EmptyResponse])
    extends AltitudeActorSystem.Command
      with Command
  final case class Predict(repositoryId: String, features: Array[Float], replyTo: ActorRef[FacePrediction])
    extends AltitudeActorSystem.Command
      with Command
  final case class GetModelSize(repositoryId: String, replyTo: ActorRef[ModelSize])
    extends AltitudeActorSystem.Command
      with Command
  final case class GetModelLabels(repositoryId: String, replyTo: ActorRef[ModelLabels])
    extends AltitudeActorSystem.Command
      with Command

  def apply(): Behavior[Command] = Behaviors.setup(context => new FaceRecManagerActor(context))
}

class FaceRecManagerActor(context: ActorContext[FaceRecManagerActor.Command])
  extends AbstractBehavior[FaceRecManagerActor.Command](context) {
  import FaceRecManagerActor._

  private var modelActors = Map.empty[String, ActorRef[FaceRecModelActor.Command]]
  var actor: Option[ActorRef[FaceRecModelActor.Command]] = None

  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = context.system.scheduler
  implicit val ec: ExecutionContext = context.executionContext
  val logger: Logger = context.log

  override def onMessage(msg: Command): Behavior[Command] = {
    msg match {
      case Initialize(app, replyTo) =>
        actor = Some(context.spawn(FaceRecModelActor(), "faceRecModelActor"))

      actor.get
          .ask(FaceRecModelActor.Initialize(app, _))
          .mapTo[AltitudeActorSystem.EmptyResponse]
          .onComplete {
            case Success(response) => replyTo ! response
            case Failure(exception) => logger.error("Failed to initialize face rec model actor", exception)
          }(ec)
        Behaviors.same

      case AddFace(repositoryId, face, personLabel) =>
        modelActors.get(repositoryId) match {
          case Some(modelActor) =>
            modelActor ! FaceRecModelActor.AddFace(face, personLabel)
            Behaviors.same
          case None =>
            throw new RuntimeException(s"No model actor found for repositoryId: $repositoryId")
        }

      case AddFaces(repositoryId, faces) =>
        modelActors.get(repositoryId) match {
          case Some(modelActor) =>
            modelActor ! FaceRecModelActor.AddFaces(faces)
            Behaviors.same
          case None =>
            throw new RuntimeException(s"No model actor found for repositoryId: $repositoryId")
        }

      case Predict(repositoryId, features, replyTo) =>
        actor match {
          case Some(modelActor) =>
            modelActor
              .ask(FaceRecModelActor.Predict(repositoryId, features, _))
              .mapTo[FacePrediction]
              .onComplete {
                case Success(response) => replyTo ! response
                case Failure(exception) => logger.error("Failed to predict face", exception)
              }(ec)
            Behaviors.same
          case None =>
            throw new RuntimeException(s"No model actor found for repositoryId: $repositoryId")
        }

      case GetModelSize(repositoryId, replyTo) =>
        modelActors.get(repositoryId) match {
          case Some(modelActor) =>
            modelActor
              .ask(FaceRecModelActor.GetModelSize(_))
              .mapTo[ModelSize]
              .onComplete {
                case Success(response) => replyTo ! response
                case Failure(exception) => logger.error("Failed to get model size", exception)
              }(ec)
            Behaviors.same
          case None =>
            throw new RuntimeException(s"No model actor found for repositoryId: $repositoryId")
        }

      case GetModelLabels(repositoryId, replyTo) =>
        modelActors.get(repositoryId) match {
          case Some(modelActor) =>
            modelActor
              .ask(FaceRecModelActor.GetModelLabels(_))
              .mapTo[ModelLabels]
              .onComplete {
                case Success(response) => replyTo ! response
                case Failure(exception) => logger.error("Failed to get model labels", exception)
              }(ec)
            Behaviors.same
          case None =>
            throw new RuntimeException(s"No model actor found for repositoryId: $repositoryId")
        }
    }
  }
}