package altitude.core.actors

import altitude.core.dao.FaceDao
import altitude.core.dao.jdbc.BaseDao
import altitude.core.{Altitude, AltitudeActorSystem, App, RequestContext}
import altitude.core.models.Face
import altitude.core.util.ImageUtil.matFromBytes
import org.apache.commons.dbutils.QueryRunner
import org.apache.commons.dbutils.handlers.MapListHandler
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.face.LBPHFaceRecognizer
import scala.jdk.CollectionConverters._

import java.util

object FaceRecModelActor {
  sealed trait Response
  final case class FacePrediction(faceRecs: List[Map[String, AnyRef]]) extends Response
  final case class ModelSize(size: Int) extends Response
  final case class ModelLabels(labels: Seq[Int])

  sealed trait Command
  final case class AddFace(face: Face, personLabel: Int) extends Command
  final case class AddFaces(face: Seq[Face]) extends Command
  final case class Initialize(app: Altitude, replyTo: ActorRef[AltitudeActorSystem.EmptyResponse]) extends Command
  final case class Predict(repositoryId: String, features: Array[Float], replyTo: ActorRef[FacePrediction]) extends Command
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
  
  private def initialize(app: Altitude): Unit = {
  }

  private def toVectorAsF32Arg(values: Array[Float]): String =
    // Must match: vector_as_f32('[0.3, 1.0, ...]')
    // We bind just the bracketed list as a String parameter.
    values.mkString("[", ", ", "]")

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

      case Initialize(app, replyTo) =>
        initialize(app)
        replyTo ! AltitudeActorSystem.EmptyResponse()
        Behaviors.same

      case Predict(repositoryId, features, replyTo) =>
        println(s"PREDICT ACTOR !!!!")
        val sql =
          """
            SELECT
              v.rowid,
              row_number() OVER (ORDER BY v.distance) AS rank_number,
              v.distance,
              face.*
            FROM vector_full_scan('face', 'features', vector_as_f32(?)) AS v
            JOIN face ON face.rowid = v.rowid
            WHERE face.repository_id = ?
              AND v.distance < 0.49
            ORDER BY v.distance
            LIMIT 1;
         """

        App.altitude.txManager.withTransaction {
          val conn = RequestContext.conn.value.get
          conn
            .prepareStatement(
              "SELECT load_extension('/Users/andrei/projects/altitude/altitude/resources/sqlite-vector/macos/vector.dylib')"
            )
            .execute()

          conn
            .prepareStatement(
              "SELECT vector_init('face', 'features', 'dimension=128,type=FLOAT32,distance=cosine')"
            )
            .execute()


          val res = QueryRunner().query(RequestContext.getConn, sql, new MapListHandler(), toVectorAsF32Arg(features), repositoryId).asScala.toList

          replyTo ! FacePrediction(res.map(_.asScala.toMap[String, AnyRef]))
          Behaviors.same
        }



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
