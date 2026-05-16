package altitude.core.dao.jdbc

import com.typesafe.config.Config

import altitude.core.FieldConst
import altitude.core.models.Repository

abstract class RepositoryDao(override val config: Config) extends BaseDao[Repository] with altitude.core.dao.RepositoryDao:

  final override val tableName = "repository"

  override protected def makeModel(rec: Map[String, AnyRef]): Repository =
    val fileStoreConfigCol = rec(FieldConst.Repository.FILES_STORE_CONFIG)
    val fileStoreConfigJsonStr: String =
      if fileStoreConfigCol == null then "{}"
      else fileStoreConfigCol.asInstanceOf[String]

    val fileStoreConfigJson = ujson.read(fileStoreConfigJsonStr).asInstanceOf[ujson.Obj]
    val fileStoreConfig = fileStoreConfigJson.obj.map { case (k, v) => k -> v.str }.toMap

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
