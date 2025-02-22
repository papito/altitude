package software.altitude.core.actors

import java.util
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.face.LBPHFaceRecognizer

import software.altitude.core.AltitudeActorSystem
import software.altitude.core.models.Face
import software.altitude.core.util.ImageUtil.matFromBytes

object FaceRecModelActor {
  sealed trait Response
  final case class FacePrediction(label: Int, confidence: Double) extends Response
  final case class ModelSize(size: Int) extends Response
  final case class ModelLabels(labels: Seq[Int])

  sealed trait Command
  final case class AddFace(face: Face, personLabel: Int) extends Command
  final case class AddFaces(face: Seq[Face]) extends Command
  final case class Initialize(replyTo: ActorRef[AltitudeActorSystem.EmptyResponse]) extends Command
  final case class Predict(face: Face, replyTo: ActorRef[FacePrediction]) extends Command
  final case class GetModelSize(replyTo: ActorRef[ModelSize]) extends Command
  final case class GetModelLabels(replyTo: ActorRef[ModelLabels]) extends Command

  def apply(): Behavior[Command] = Behaviors.setup(context => new FaceRecModelActor(context))
}

class FaceRecModelActor(context: ActorContext[FaceRecModelActor.Command])
  extends AbstractBehavior[FaceRecModelActor.Command](context) {
  import FaceRecModelActor._

  val recognizer: LBPHFaceRecognizer = LBPHFaceRecognizer.create()
  recognizer.setGridX(10)
  recognizer.setGridY(10)
  recognizer.setRadius(2)

  initialize()

  private def initialize(): Unit = {
    recognizer.clear()

    val initialLabels = new Mat(2, 1, CvType.CV_32SC1)
    val InitialImages = new java.util.ArrayList[Mat]()

    for (idx <- 0 to 1) {
      val bytes = getClass.getResourceAsStream(s"/train/$idx.png").readAllBytes()
      val image: Mat = matFromBytes(bytes)
      initialLabels.put(idx, 0, idx)
      InitialImages.add(image)
    }

    recognizer.train(InitialImages, initialLabels)
  }

  override def onMessage(msg: Command): Behavior[Command] = {
    msg match {
      case AddFace(face, personLabel) =>
        val labels = new Mat(1, 1, CvType.CV_32SC1)
        val images = new util.ArrayList[Mat]()
        labels.put(0, 0, personLabel)
        images.add(face.alignedImageGsMat)
        recognizer.update(images, labels)
        Behaviors.same

      case AddFaces(faces) =>
        val labels = new Mat(faces.size, 1, CvType.CV_32SC1)
        val images = new util.ArrayList[Mat]()

        faces.zipWithIndex.foreach {
          case (face, idx) =>
            labels.put(idx, 0, face.personLabel.getOrElse(-1))
            images.add(face.alignedImageGsMat)
        }

        recognizer.update(images, labels)
        Behaviors.same

      case Initialize(replyTo) =>
        initialize()
        replyTo ! AltitudeActorSystem.EmptyResponse()
        Behaviors.same

      case Predict(face, replyTo) =>
        val predLabelArr = new Array[Int](1)
        val confidenceArr = new Array[Double](1)
        recognizer.predict(face.alignedImageGsMat, predLabelArr, confidenceArr)

        val predLabel = predLabelArr.head
        val confidence = confidenceArr.head

        replyTo ! FacePrediction(predLabel, confidence)
        Behaviors.same

      case GetModelSize(replyTo) =>
        replyTo ! ModelSize(recognizer.getLabels.size().height.toInt - 2)
        Behaviors.same

      case GetModelLabels(replyTo) =>
        val labels = recognizer.getLabels
        val labelSeq = (2 until labels.height()).map(labels.get(_, 0)(0).toInt)
        replyTo ! ModelLabels(labelSeq)
        Behaviors.same
    }
  }
}
