package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.time.OffsetDateTime
import java.time.ZoneOffset
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.tables.AssetEditRow
import altitude.core.models.AssetEdit
import altitude.core.models.AssetEditStep
import altitude.core.models.EditRecipe
import altitude.core.models.EditState

/**
 * An asset's Edit (`asset_edit`) and its steps (`asset_edit_step`), both scoped to the context repository: every statement here
 * is hand-written and carries `repository_id`. The step's Edit is JSON, `jsonb` on PostgreSQL, which pgJDBC hands back as its
 * text.
 */
abstract class AssetEditDao(override val config: Config) extends BaseDao[AssetEdit] with altitude.core.dao.AssetEditDao:
  final override val tableName = "asset_edit"
  private val stepTable = "asset_edit_step"

  final override type Row[T[_]] = AssetEditRow[T]
  final override protected def table: Table[Row] = AssetEditRow

  private val F = FieldConst.AssetEdit
  private val S = FieldConst.AssetEditStep

  override protected def toModel(row: AssetEditRow[Sc]): AssetEdit =
    AssetEdit(
      assetId = row.assetId,
      headSeq = row.headSeq,
      renderedSeq = row.renderedSeq,
      lastRev = row.lastRev,
      isEdited = row.isEdited,
      width = row.width,
      height = row.height,
      updatedByAccountId = row.updatedByAccountId,
      createdAt = row.createdAt.map(toLocalDateTime),
      updatedAt = row.updatedAt.map(toLocalDateTime)
    )

  override protected def makeModel(rec: Map[String, AnyRef]): AssetEdit =
    AssetEdit(
      assetId = rec(F.ASSET_ID).toString,
      headSeq = getIntField(rec(F.HEAD_SEQ)),
      renderedSeq = Option(rec(F.RENDERED_SEQ)).map(getIntField),
      lastRev = getIntField(rec(F.LAST_REV)),
      isEdited = getBooleanField(rec(F.IS_EDITED)),
      width = Option(rec(F.WIDTH)).map(getIntField),
      height = Option(rec(F.HEIGHT)).map(getIntField),
      updatedByAccountId = Option(rec(F.UPDATED_BY_ACCOUNT_ID)).map(_.toString),
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT)),
      updatedAt = getDateTimeField(rec.get(FieldConst.UPDATED_AT))
    )

  private def makeStep(rec: Map[String, AnyRef]): AssetEditStep =
    AssetEditStep(
      assetId = rec(S.ASSET_ID).toString,
      seq = getIntField(rec(S.SEQ)),
      recipe = EditRecipe.parse(rec(S.RECIPE).toString),
      label = rec(S.LABEL).toString,
      accountId = Option(rec(S.ACCOUNT_ID)).map(_.toString),
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT))
    )

  private def repositoryId: String = RequestContext.getRepository.persistedId

  // The bind value of the current moment, for `created_at` and `updated_at`
  private def now: Any = nativeUtcTimestamp(OffsetDateTime.now(ZoneOffset.UTC))

  private val byAsset = s"${FieldConst.REPO_ID} = ? AND ${F.ASSET_ID} = ?"

  override def get(assetId: String): Option[AssetEdit] =
    manyBySqlQuery(s"SELECT * FROM $tableName WHERE $byAsset", List(repositoryId, assetId)).headOption.map(makeModel)

  override def getForUpdate(assetId: String): Option[AssetEdit] =
    manyBySqlQuery(s"SELECT * FROM $tableName WHERE $byAsset $forUpdate", List(repositoryId, assetId)).headOption.map(makeModel)

  // One statement on both engines (SQLite has had the upsert since 3.24)
  override def create(assetId: String): Boolean =
    val sql = s"""
      INSERT INTO $tableName (${FieldConst.REPO_ID}, ${F.ASSET_ID}, ${FieldConst.CREATED_AT})
           VALUES (?, ?, ?)
      ON CONFLICT (${F.ASSET_ID}) DO NOTHING
      """
    updateByBySql(sql, List(repositoryId, assetId, now)) == 1

  override def steps(assetId: String): List[EditState.Step] =
    val sql = s"""
      SELECT s.${S.SEQ}, s.${S.LABEL}, s.${S.ACCOUNT_ID}, account.${FieldConst.User.NAME} AS ${S.AUTHOR},
             s.${FieldConst.CREATED_AT}
        FROM $stepTable s
        LEFT JOIN account ON account.${FieldConst.ID} = s.${S.ACCOUNT_ID}
       WHERE s.${FieldConst.REPO_ID} = ? AND s.${S.ASSET_ID} = ?
    ORDER BY s.${S.SEQ} DESC
      """
    manyBySqlQuery(sql, List(repositoryId, assetId)).map {
      rec =>
        EditState.Step(
          seq = getIntField(rec(S.SEQ)),
          label = rec(S.LABEL).toString,
          accountId = Option(rec(S.ACCOUNT_ID)).map(_.toString),
          author = Option(rec(S.AUTHOR)).map(_.toString),
          createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT)).map(instantOf)
        )
    }

  override def step(assetId: String, seq: Int): Option[AssetEditStep] =
    val sql = s"SELECT * FROM $stepTable WHERE $byAsset AND ${S.SEQ} = ?"
    manyBySqlQuery(sql, List(repositoryId, assetId, seq)).headOption.map(makeStep)

  override def insertStep(step: AssetEditStep): Unit =
    val sql = s"""
      INSERT INTO $stepTable (${FieldConst.REPO_ID}, ${S.ASSET_ID}, ${S.SEQ}, ${S.RECIPE}, ${S.LABEL}, ${S.ACCOUNT_ID},
                              ${FieldConst.CREATED_AT})
           VALUES (?, ?, ?, $jsonFunc, ?, ?, ?)
      """
    addRecord(
      sql,
      List(repositoryId, step.assetId, step.seq, ujson.write(step.recipe.toJson), step.label, step.accountId.orNull, now))

  override def deleteStepsAfter(assetId: String, seq: Int): Int =
    updateByBySql(s"DELETE FROM $stepTable WHERE $byAsset AND ${S.SEQ} > ?", List(repositoryId, assetId, seq))

  override def deleteOldestBeyond(assetId: String, keep: Int): Int =
    val sql = s"""
      DELETE FROM $stepTable
       WHERE $byAsset
         AND ${S.SEQ} <= (SELECT MAX(${S.SEQ}) - ? FROM $stepTable WHERE $byAsset)
      """
    updateByBySql(sql, List(repositoryId, assetId, keep, repositoryId, assetId))

  override def setHead(assetId: String, seq: Int, isEdited: Boolean, width: Int, height: Int, accountId: String): Unit =
    val sql = s"""
      UPDATE $tableName
         SET ${F.HEAD_SEQ} = ?, ${F.IS_EDITED} = ?, ${F.WIDTH} = ?, ${F.HEIGHT} = ?, ${F.UPDATED_BY_ACCOUNT_ID} = ?,
             ${FieldConst.UPDATED_AT} = ?
       WHERE $byAsset
      """
    val updated = updateByBySql(sql, List(seq, nativeBool(isEdited), width, height, accountId, now, repositoryId, assetId))
    require(updated == 1, s"The Edit of asset [$assetId] has $updated rows, not one")
