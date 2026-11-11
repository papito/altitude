package altitude.core.routes.api

import cask.Request
import cask.Response
import org.slf4j.Logger

import scala.util.Try

import altitude.core.Api
import altitude.core.App
import altitude.core.Validators
import altitude.core.models.Permission
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.requirePermission

/** Curation's JSON API: the count of the queue the modal walks, and a Curation set's outcome and the Undo of it */
class CurationController(using logger: Logger) extends BaseController:
  private val prefix = "api/curation"
  private val fields = Api.Field.Curation

  /** The number of Sequences in the queue, read from the database: the client never adds or subtracts */
  @requireLogin()
  @requirePermission(Permission.CurateOwn, Permission.CurateAll)
  @cask.get(f"/$prefix/r/:repoId/count")
  def count(repoId: String)(using request: Request): Response[String] =
    jsonResponse(ujson.Obj(fields.COUNT -> App.altitude.service.curation.countQueue()))

  /** Keeps the Picks, recycles the others and answers with each Pick that left its Sequence, for Undo to post back to `reopen` */
  @requireLogin()
  @requirePermission(Permission.CurateOwn, Permission.CurateAll)
  @cask.post(f"/$prefix/r/:repoId/resolve")
  def resolve(repoId: String)(using request: Request): Response[String] =
    Try {
      val jsonIn = unscrubbedJson.get
      (idsOf(jsonIn, fields.KEEP_IDS), idsOf(jsonIn, fields.RECYCLE_IDS))
    }.toOption match
      case None => jsonError(s"Expected ${fields.KEEP_IDS} and ${fields.RECYCLE_IDS} arrays of UUIDs", 400)
      case Some((keepIds, recycleIds)) =>
        answeringJson {
          val sequenceOf = App.altitude.service.curation.resolve(keepIds, recycleIds)
          jsonResponse(ujson.Obj(fields.SEQUENCE_OF -> ujson.Obj.from(sequenceOf.view.mapValues(ujson.Str(_)))))
        }

  /** The Undo of a `resolve`, given its lists and its answer; a duplicate left in the trash is reported as a restore does */
  @requireLogin()
  @requirePermission(Permission.CurateOwn, Permission.CurateAll)
  @cask.post(f"/$prefix/r/:repoId/reopen")
  def reopen(repoId: String)(using request: Request): Response[String] =
    Try {
      val jsonIn = unscrubbedJson.get
      (idsOf(jsonIn, fields.KEEP_IDS), idsOf(jsonIn, fields.RECYCLE_IDS), sequenceOfIn(jsonIn))
    }.toOption match
      case None =>
        jsonError(
          s"Expected ${fields.KEEP_IDS} and ${fields.RECYCLE_IDS} arrays of UUIDs, and ${fields.SEQUENCE_OF} of UUIDs",
          400)
      case Some((keepIds, recycleIds, sequenceOf)) =>
        answeringJson(restoreResponse(App.altitude.service.curation.reopen(keepIds, recycleIds, sequenceOf)))

  private def idsOf(jsonIn: ujson.Obj, field: String): Set[String] = jsonIn(field).arr.map(id => uuid(id.str)).toSet

  private def sequenceOfIn(jsonIn: ujson.Obj): Map[String, String] =
    jsonIn(fields.SEQUENCE_OF).obj.toMap.map((pick, sequenceId) => uuid(pick) -> uuid(sequenceId.str))

  /** Every ID of these bodies reaches a statement, and a Sequence's is written to `asset.sequence_id`, a `CHAR(36)` */
  private def uuid(id: String): String =
    require(Validators.isUuid(id))
    id

  initialize()
