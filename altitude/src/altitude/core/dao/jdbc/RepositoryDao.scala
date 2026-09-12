package altitude.core.dao.jdbc

import com.typesafe.config.Config
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.dao.sql.tables.RepositoryRow
import altitude.core.models.Repository

abstract class RepositoryDao(override val config: Config) extends BaseDao[Repository] with altitude.core.dao.RepositoryDao:

  final override val tableName = "repository"

  final override type Row[T[_]] = RepositoryRow[T]
  final override protected def table: Table[Row] = RepositoryRow

  override protected def toModel(row: RepositoryRow[Sc]): Repository =
    Repository(
      id = Option(row.id),
      name = row.name,
      ownerAccountId = row.ownerAccountId,
      rootFolderId = row.rootFolderId,
      fileStoreType = row.fileStoreType,
      fileStoreConfig = fileStoreConfigOf(row.fileStoreConfig.orNull),
      createdAt = row.createdAt.map(toLocalDateTime),
      updatedAt = row.updatedAt.map(toLocalDateTime)
    )

  private def fileStoreConfigOf(column: AnyRef): Map[String, String] =
    val json = ujson.read(if column == null then "{}" else column.toString).asInstanceOf[ujson.Obj]
    json.obj.map { case (k, v) => k -> v.str }.toMap

  override protected def makeModel(rec: Map[String, AnyRef]): Repository =
    val fileStoreConfig = fileStoreConfigOf(rec(FieldConst.Repository.FILES_STORE_CONFIG))

    Repository(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      name = rec(FieldConst.Repository.NAME).asInstanceOf[String],
      ownerAccountId = rec(FieldConst.Repository.OWNER_ACCOUNT_ID).asInstanceOf[String],
      rootFolderId = rec(FieldConst.Repository.ROOT_FOLDER_ID).asInstanceOf[String],
      fileStoreType = rec(FieldConst.Repository.FILE_STORE_TYPE).asInstanceOf[String],
      fileStoreConfig = fileStoreConfig,
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT)),
      updatedAt = getDateTimeField(rec.get(FieldConst.UPDATED_AT))
    )

  override def add(repo: Repository): Repository =
    val sql = s"""
        INSERT INTO repository (
             ${FieldConst.ID}, ${FieldConst.Repository.NAME}, ${FieldConst.Repository.OWNER_ACCOUNT_ID}, ${FieldConst.Repository.FILE_STORE_TYPE},
             ${FieldConst.Repository.ROOT_FOLDER_ID},
             ${FieldConst.Repository.FILES_STORE_CONFIG})
            VALUES (?, ?, ?, ?, ?,$jsonFunc)
    """

    val id = repo.id.getOrElse(BaseDao.genId)
    val fileStoreConfigJson = ujson.Obj()
    repo.fileStoreConfig.foreach { case (k, v) => fileStoreConfigJson(k) = v }
    val sqlVals: List[Any] =
      List(id, repo.name, repo.ownerAccountId, repo.fileStoreType, repo.rootFolderId, ujson.write(fileStoreConfigJson))

    addRecord(sql, sqlVals)
    repo.copy(id = Some(id))

  def getAll: List[Repository] =
    val sql = s"SELECT ${columnsForSelect.mkString(", ")} FROM repository"
    val recs = manyBySqlQuery(sql)
    recs.map(makeModel)
