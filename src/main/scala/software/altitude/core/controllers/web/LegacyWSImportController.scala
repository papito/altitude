/*package software.altitude.core.controllers.web

import java.io.{File, PrintWriter, StringWriter}
import java.util.concurrent.atomic.AtomicBoolean
import akka.actor.Actor.ignoringBehavior
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.routing.RoundRobinPool
import org.json4s.JsonAST.{JField, JObject, JString}
import org.json4s.{DefaultFormats, Formats, JValue, _}
import org.scalatra.{ScalatraServlet, SessionSupport}
import org.scalatra.atmosphere._
import org.scalatra.json.{JValueResult, JacksonJsonSupport}
import org.slf4j.LoggerFactory
import play.api.libs.json._
import software.altitude.core.SingleApplication.app
import software.altitude.core.models.{Asset, ImportAsset, Repository, User}
import software.altitude.core.{Context, DuplicateException, MetadataExtractorException, Const => C}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ImportController(actorSystem: ActorSystem)
  extends PassThroughWebController
    with JValueResult
    with JacksonJsonSupport
    with SessionSupport
    with AtmosphereSupport  {

  private final val log = LoggerFactory.getLogger(getClass)

  private val userHomeDir = System.getProperty("user.home")
  implicit protected val jsonFormats: Formats = DefaultFormats

  private case class NotImportable(importAsset: ImportAsset) extends Exception

  case class ImportAssetMsg(importAsset: ImportAsset,
                            isLastAsset: Boolean,
                            client: AtmosphereClient,
                            akkaRouter: ActorRef,
                            stopImport: AtomicBoolean,
                            context: Context)

  case class GetFileCountMsg(path: String, client: AtmosphereClient, context: Context)

  abstract class AtmosphereActor extends Actor {
    protected def writeToYou(client: AtmosphereClient, jsonMessage: JsValue): Unit = {
      log.info(s"YOU -> $jsonMessage")
      client.send(jsonMessage.toString())
    }
  }

  class FileCountGetterActor extends AtmosphereActor {
    override def receive: Actor.Receive = {
      case msg: GetFileCountMsg => {
        val count = app.service.source.fileSystem.count(msg.path)
        writeToYou(msg.client, Json.obj("total" -> JsNumber(count)))
      }
    }
  }

  class AssetImportActor extends AtmosphereActor {
    override def receive: Actor.Receive = {
      case msg: ImportAssetMsg => {
        val importAsset: ImportAsset = msg.importAsset
        val client: AtmosphereClient = msg.client
        val context: Context = msg.context
        val stopImport = msg.stopImport

        if (stopImport.get) {
          log.info(s"STOP. Ignoring message for import asset $importAsset on actor [${self.path}]")
          return ignoringBehavior
        }

        log.info(s"Processing import asset $importAsset on actor [${self.path}]")

        try {
          val asset: Option[Asset] =
            app.service.assetImport.importAsset(importAsset)(ctx = context)
          if (asset.isEmpty) throw NotImportable(importAsset)

          val resp = Json.obj(
            C.Api.Asset.ASSET -> asset.get.toJson,
            C.Api.ImportAsset.IMPORT_ASSET -> importAsset.toJson,
            C.Api.Import.IMPORTED -> JsBoolean(true))

          if (!stopImport.get) {
            writeToYou(client, resp)
          }
          else {
            return ignoringBehavior
          }
        }
        catch {
          case _: NotImportable => {
            val resp = Json.obj(
              C.Api.WARNING -> JsString(C.Msg.Err.NOT_SUPPORTED),
              C.Api.ImportAsset.IMPORT_ASSET -> importAsset.toJson,
              C.Api.Import.IMPORTED -> JsBoolean(false))

            writeToYou(client, resp)
          }
          case ex: DuplicateException => {
            val duplicateOf: Asset =
              app.service.library.getById(ex.existingAssetId)(ctx = context)
            val resp = Json.obj(
              C.Api.WARNING -> JsString(s"${C.Msg.Err.DUPLICATE} of ${duplicateOf.path.get}"),
              C.Api.ImportAsset.IMPORT_ASSET -> importAsset.toJson,
              C.Api.DUPLICATE_OF -> duplicateOf.toJson,
              C.Api.Import.IMPORTED -> JsBoolean(false))

            writeToYou(client, resp)
          }

          case ex: MetadataExtractorException => {
            log.warn(s"Metadata extraction error for $importAsset")
            val resp = Json.obj(
              C.Api.WARNING -> JsString(
                s"Metadata parser(s) failed. Asset still imported"),
              C.Api.Asset.ASSET -> ex.asset.toJson,
              C.Api.ImportAsset.IMPORT_ASSET -> importAsset.toJson,
              C.Api.Import.IMPORTED -> JsBoolean(true))

            writeToYou(client, resp)
          }

          case ex: Exception => {
            log.error(s"Import error for $importAsset")
            val sw: StringWriter = new StringWriter()
            val pw: PrintWriter = new PrintWriter(sw)
            ex.printStackTrace(pw)

            log.error(s"${ex.getClass.getName} exception: ${sw.toString}")

            val resp = Json.obj(
              C.Api.ERROR -> JsString(ex.toString),
              C.Api.Import.IMPORTED -> JsBoolean(false),
              C.Api.ImportAsset.IMPORT_ASSET -> importAsset.toJson)

            writeToYou(client, resp)
          }
        }
        finally {
          if (!msg.isLastAsset) {
            log.info("Last asset...")
            writeToYou(client, Json.obj("end" -> JsBoolean(true)))
            log.warn("Stopping the router")
            msg.akkaRouter ! PoisonPill
          }
        }
      }
    }

    override def preStart(): Unit = {
      log.trace(s"Actor ${self.path} starting")
    }

    override def postStop(): Unit = {
      log.trace(s"Actor ${self.path} stopped")
    }
  }

  post("/") {
    // val files: Seq[FileItem] = fileMultiParams("files[]")
  }

  get("/fs/listing") {
    val file: File = new File(this.params.getOrElse(C.Api.PATH, userHomeDir))
    log.debug(s"Getting directory name list for $file")
    val files: Seq[File] = file.listFiles().toSeq
    val directoryList: Seq[String] = files.filter(_.isDirectory == true).map(_.getName)
    contentType = "application/json"
    Json.obj(
      C.Api.DIRECTORY_NAMES -> directoryList,
      C.Api.CURRENT_PATH -> file.getAbsolutePath,
      C.Api.OS_DIR_SEPARATOR -> File.separator).toString()
  }

  get("/fs/listing/parent") {
    val path = this.params.getOrElse(C.Api.PATH, userHomeDir)
    log.debug(s"Path: $path")
    val file: File = new File(this.params.getOrElse(C.Api.PATH, userHomeDir))
    val parentPath: String = file.getParent
    log.debug(s"Parent path: $parentPath")

    val parentFile = if (parentPath != null) new File(parentPath) else file

    log.debug(s"Getting parent directory name list for $file")
    val files: Seq[File] = parentFile.listFiles().toSeq
    val directoryList: Seq[String] = files.filter(_.isDirectory == true).map(_.getName)
    contentType = "application/json"
    Json.obj(
      C.Api.DIRECTORY_NAMES -> directoryList,
      C.Api.CURRENT_PATH -> parentFile.getAbsolutePath).toString()
  }

  atmosphere("/fs/ws") {
    new AtmosphereClient {
      private def uuidJson: JsValue = Json.obj("uid" -> JsString(uuid))

      private val numWorkers = sys.runtime.availableProcessors

      val akkaRouter: ActorRef = actorSystem.actorOf(
        RoundRobinPool(numWorkers).props(Props(new AssetImportActor())))

      private val stopImport: AtomicBoolean = new AtomicBoolean(false)
      var path: Option[String] = None

      // SCAFFOLD
      val repository: Repository = app.service.repository.getRepositoryById(app.app.REPOSITORY_ID)
      // SCAFFOLD
      val user: User = app.service.user.getUserById(app.app.USER_ID)
      implicit val context: Context = new Context(repo = repository, user = user)

      private def writeToYou(jsonMessage: JsValue): Unit = {
        log.info(s"YOU -> $jsonMessage")
        this.send(jsonMessage.toString())
      }

      def receive: AtmoReceive = {
        case message @ JsonMessage(JObject(JField("action", JString("getUID")) :: _)) => {
          val json: JValue = message.content
          log.info(s"WS <- $json")
          this.writeToYou(uuidJson)
        }

        case message @ JsonMessage(JObject(JField("action", JString("stopImport")) :: _)) => {
          val json: JValue = message.content
          log.info(s"WS <- $json")
          stopImport.set(true)
          writeToYou(Json.obj("end" -> JsBoolean(true)))
        }

        case message @ JsonMessage(JObject(JField("action", JString("startImport")) :: _)) => {
          val json: JValue = message.content
          log.info(s"WS <- $json")

          stopImport.set(false)

          // kick off the scan for total asset count in the tree
          val path: String = (json \ "path").extract[String]
          val fileCountGetterActor = actorSystem.actorOf(Props(new FileCountGetterActor))
          fileCountGetterActor ! GetFileCountMsg(path = path, client = this, context = context)

          // kick off the import itself
          val assetsIt = app.service.source.fileSystem.assetIterator(path)

          Future {
            while (assetsIt.hasNext && !stopImport.get) {
              val msg = ImportAssetMsg(
                importAsset = assetsIt.next(),
                isLastAsset = assetsIt.hasNext,
                client = this,
                akkaRouter = akkaRouter,
                stopImport = stopImport,
                context = context)
              log.info(s"Sending message for client $uuid")
              akkaRouter ! msg
            }
          } onFailure {
            case ex: Exception =>
              log.error(s"Unknown worker exception [$ex]")
          }
        }

        case TextMessage(data: String) =>
          log.info(s"WS -> $data")

        case Connected =>
            log.info("Client connected")

        case Disconnected(ClientDisconnected, _) =>
          log.info("Client disconnected")
          stopImport.set(true)
          akkaRouter ! PoisonPill

        case Disconnected(ServerDisconnected, _) =>
          log.info("Server disconnected")
          stopImport.set(true)
          akkaRouter ! PoisonPill

        case Error(Some(error)) =>
          val sw: StringWriter = new StringWriter()
          val pw: PrintWriter = new PrintWriter(sw)
          error.printStackTrace(pw)
          error.printStackTrace()
          log.error(s"Error: ${error.getMessage}. ${sw.toString}")
      }
    }
  }

  /*
    error {
      case _: SizeConstraintExceededException => RequestEntityTooLarge(s"sFile is larger than $ONE_HUNDRED_MEGABYTES")
    }
  */
}
*/
