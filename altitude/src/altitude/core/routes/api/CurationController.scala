package altitude.core.routes.api

import cask.Request
import cask.Response
import org.slf4j.Logger

import scala.util.Try

import altitude.core.Api
import altitude.core.App
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

/** Curation's JSON API: the queue the modal walks and its count, and a Curation set's outcome and the Undo of it */
class CurationController(using logger: Logger) extends BaseController:
  private val prefix = "api/curation"
  private val fields = Api.Field.Curation

  /** The queue's Sequence IDs, in the order the modal walks them */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/queue")
  def queue(repoId: String)(using request: Request): Response[String] =
    jsonResponse(ujson.Obj(fields.SEQUENCE_IDS -> App.altitude.service.curation.queue().map(_.sequenceId)))

  /** The number of Sequences in the queue, read from the database: the client never adds or subtracts */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/count")
  def count(repoId: String)(using request: Request): Response[String] =
    jsonResponse(ujson.Obj(fields.COUNT -> App.altitude.service.curation.countQueue()))

  /** Keeps the Picks, recycles the others and answers with each Pick that left its Sequence, for Undo to post back to `reopen` */
  @requireLogin()
  @cask.post(f"/$prefix/r/:repoId/resolve")
  def resolve(repoId: String)(using request: Request): Response[String] =
    val jsonIn = unscrubbedJson.get // a wrong content type keeps its own validation error
    Try((idsOf(jsonIn, fields.KEEP_IDS), idsOf(jsonIn, fields.RECYCLE_IDS))).toOption match
      case None => jsonError(s"Expected ${fields.KEEP_IDS} and ${fields.RECYCLE_IDS} arrays of strings", 400)
      case Some((keepIds, recycleIds)) =>
        refusalsAnswered {
          val sequenceOf = App.altitude.service.curation.resolve(keepIds, recycleIds)
          jsonResponse(ujson.Obj(fields.SEQUENCE_OF -> ujson.Obj.from(sequenceOf.view.mapValues(ujson.Str(_)))))
        }

  /** The Undo of a `resolve`, given its lists and its answer; a duplicate left in the trash is reported as a restore does */
  @requireLogin()
  @cask.post(f"/$prefix/r/:repoId/reopen")
  def reopen(repoId: String)(using request: Request): Response[String] =
    val jsonIn = unscrubbedJson.get // a wrong content type keeps its own validation error
    Try((idsOf(jsonIn, fields.KEEP_IDS), idsOf(jsonIn, fields.RECYCLE_IDS), sequenceOfIn(jsonIn))).toOption match
      case None =>
        jsonError(s"Expected ${fields.KEEP_IDS} and ${fields.RECYCLE_IDS} arrays of strings, and ${fields.SEQUENCE_OF}", 400)
      case Some((keepIds, recycleIds, sequenceOf)) =>
        refusalsAnswered(restoreResponse(App.altitude.service.curation.reopen(keepIds, recycleIds, sequenceOf)))

  private def idsOf(jsonIn: ujson.Obj, field: String): Set[String] = jsonIn(field).arr.map(_.str).toSet

  private def sequenceOfIn(jsonIn: ujson.Obj): Map[String, String] =
    jsonIn(fields.SEQUENCE_OF).obj.toMap.view.mapValues(_.str).toMap

  /** A refused outcome is the client's error; an asset outside this repository is not found. */
  private def refusalsAnswered(outcome: => Response[String]): Response[String] =
    try outcome
    catch
      case ex: NotFoundException => jsonError(ex.getMessage, 404)
      case ex: IllegalOperationException => jsonError(ex.getMessage, 400)

  initialize()
