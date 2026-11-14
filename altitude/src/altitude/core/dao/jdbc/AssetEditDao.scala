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
import altitude.core.models.EditRecipe

/**
 * An asset's Edit (`asset_edit`), scoped to the context repository: every statement here is hand-written and carries
 * `repository_id`. The Edit is JSON, `jsonb` on PostgreSQL, which pgJDBC hands back as its text.
 */
abstract class AssetEditDao(override val config: Config) extends BaseDao[AssetEdit] with altitude.core.dao.AssetEditDao:
  final override val tableName = "asset_edit"

  final override type Row[T[_]] = AssetEditRow[T]
  final override protected def table: Table[Row] = AssetEditRow

  private val F = FieldConst.AssetEdit

  override protected def toModel(row: AssetEditRow[Sc]): AssetEdit =
    AssetEdit(
      assetId = row.assetId,
      recipe = EditRecipe.parse(row.recipe),
      editVersion = row.editVersion,
      renderedVersion = row.renderedVersion,
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
      recipe = EditRecipe.parse(rec(F.RECIPE).toString),
      editVersion = getIntField(rec(F.EDIT_VERSION)),
      renderedVersion = Option(rec(F.RENDERED_VERSION)).map(getIntField),
      lastRev = getIntField(rec(F.LAST_REV)),
      isEdited = getBooleanField(rec(F.IS_EDITED)),
      width = getIntField(rec(F.WIDTH)),
      height = getIntField(rec(F.HEIGHT)),
      updatedByAccountId = Option(rec(F.UPDATED_BY_ACCOUNT_ID)).map(_.toString),
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT)),
      updatedAt = getDateTimeField(rec.get(FieldConst.UPDATED_AT))
    )

  private def repositoryId: String = RequestContext.getRepository.persistedId

  // The bind value of the current moment, for `created_at` and `updated_at`
  private def now: Any = nativeUtcTimestamp(OffsetDateTime.now(ZoneOffset.UTC))

  private val byAsset = s"${FieldConst.REPO_ID} = ? AND ${F.ASSET_ID} = ?"

  /**
   * The condition of an Edit whose edit version is not rendered, `rendered_version` NULL counting as unlike any: spelled as the
   * partial index `asset_edit_01` is on the engine, so the planner takes the index
   */
  protected def versionNotRendered: String

  override def get(assetId: String): Option[AssetEdit] =
    manyBySqlQuery(s"SELECT * FROM $tableName WHERE $byAsset", List(repositoryId, assetId)).headOption.map(makeModel)

  override def getForUpdate(assetId: String): Option[AssetEdit] =
    manyBySqlQuery(s"SELECT * FROM $tableName WHERE $byAsset $forUpdate", List(repositoryId, assetId)).headOption.map(makeModel)

  override def insert(edit: AssetEdit, accountId: String): Unit =
    val sql = s"""
      INSERT INTO $tableName (${FieldConst.REPO_ID}, ${F.ASSET_ID}, ${F.RECIPE}, ${F.EDIT_VERSION}, ${F.IS_EDITED}, ${F.WIDTH},
                              ${F.HEIGHT}, ${F.UPDATED_BY_ACCOUNT_ID}, ${FieldConst.CREATED_AT}, ${FieldConst.UPDATED_AT})
           VALUES (?, ?, $jsonFunc, ?, ?, ?, ?, ?, ?, ?)
      """
    val at = now
    val values = List(
      repositoryId,
      edit.assetId,
      ujson.write(edit.recipe.toJson),
      edit.editVersion,
      nativeBool(edit.isEdited),
      edit.width,
      edit.height,
      accountId,
      at,
      at)
    addRecord(sql, values)

  override def save(
      assetId: String,
      editVersion: Int,
      recipe: EditRecipe,
      isEdited: Boolean,
      width: Int,
      height: Int,
      accountId: String): Unit =
    val sql = s"""
      UPDATE $tableName
         SET ${F.RECIPE} = $jsonFunc, ${F.EDIT_VERSION} = ?, ${F.IS_EDITED} = ?, ${F.WIDTH} = ?, ${F.HEIGHT} = ?,
             ${F.UPDATED_BY_ACCOUNT_ID} = ?, ${FieldConst.UPDATED_AT} = ?
       WHERE $byAsset
      """
    val values =
      List(ujson.write(recipe.toJson), editVersion, nativeBool(isEdited), width, height, accountId, now, repositoryId, assetId)
    val updated = updateByBySql(sql, values)
    require(updated == 1, s"The Edit of asset [$assetId] has $updated rows, not one")

  override def setRendered(assetId: String, editVersion: Int, lastRev: Int): Unit =
    val sql = s"UPDATE $tableName SET ${F.RENDERED_VERSION} = ?, ${F.LAST_REV} = ? WHERE $byAsset"
    val updated = updateByBySql(sql, List(editVersion, lastRev, repositoryId, assetId))
    require(updated == 1, s"The Edit of asset [$assetId] has $updated rows, not one")

  override def pendingRenders(): List[String] =
    val sql = s"SELECT ${F.ASSET_ID} FROM $tableName WHERE ${FieldConst.REPO_ID} = ? AND $versionNotRendered"
    manyBySqlQuery(sql, List(repositoryId)).map(_(F.ASSET_ID).toString)
