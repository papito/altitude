package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.dao.sql.tables.AuditEventRow
import altitude.core.models.AccountSnapshot
import altitude.core.models.AuditEvent
import altitude.core.models.AuditEventType
import altitude.core.util.JsonCodec

abstract class AuditEventDao(override val config: Config) extends BaseDao[AuditEvent] with altitude.core.dao.AuditEventDao:

  final override val tableName = "audit_event"

  // An event is written with or without a Library in the context: a login has none
  final override protected val repositoryScoped = false

  final override type Row[T[_]] = AuditEventRow[T]
  final override protected def table: Table[Row] = AuditEventRow

  private def snapshotOf(json: Option[String]): Option[AccountSnapshot] = json.map(JsonCodec.read[AccountSnapshot](_))

  override protected def toModel(row: AuditEventRow[Sc]): AuditEvent =
    AuditEvent(
      id = Option(row.id),
      event = AuditEventType.fromDbValue(row.event),
      actor = snapshotOf(row.actor),
      target = snapshotOf(row.target),
      detail = getJsonFromColumn(row.detail.orNull),
      clientIp = row.clientIp,
      createdAt = Some(toLocalDateTime(row.createdAt))
    )

  override protected def makeModel(rec: Map[String, AnyRef]): AuditEvent =
    def json(field: String): Option[String] = Option(rec(field)).map(_.toString)

    AuditEvent(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      event = AuditEventType.fromDbValue(rec(FieldConst.AuditEvent.EVENT).asInstanceOf[String]),
      actor = snapshotOf(json(FieldConst.AuditEvent.ACTOR)),
      target = snapshotOf(json(FieldConst.AuditEvent.TARGET)),
      detail = getJsonFromColumn(rec(FieldConst.AuditEvent.DETAIL)),
      clientIp = Option(rec(FieldConst.AuditEvent.CLIENT_IP)).map(_.toString),
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT))
    )

  override def add(event: AuditEvent): AuditEvent =
    val sql = s"""
        INSERT INTO $tableName (${FieldConst.ID}, ${FieldConst.AuditEvent.EVENT}, ${FieldConst.AuditEvent.ACTOR},
                                ${FieldConst.AuditEvent.TARGET}, ${FieldConst.AuditEvent.DETAIL},
                                ${FieldConst.AuditEvent.CLIENT_IP}, ${FieldConst.CREATED_AT})
             VALUES (?, ?, $jsonFunc, $jsonFunc, $jsonFunc, ?, ?)
    """

    val id = event.id.getOrElse(BaseDao.genId)
    // Bound in UTC rather than left to the engine default, and to the microsecond, as `AssetDao.add` binds an asset's
    val createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)

    addRecord(
      sql,
      List(
        id,
        event.event.dbValue,
        event.actor.map(_.toJson.toString).orNull,
        event.target.map(_.toJson.toString).orNull,
        ujson.write(event.detail),
        event.clientIp.orNull,
        nativeUtcTimestamp(createdAt)
      )
    )
    event.copy(id = Some(id), createdAt = Some(toLocalDateTime(createdAt)))
