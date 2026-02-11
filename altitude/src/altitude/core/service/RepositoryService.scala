package altitude.core.service

import altitude.core.{Altitude, FieldConst, NotFoundException, RequestContext}
import altitude.core.dao.RepositoryDao
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Folder
import altitude.core.models.Repository
import altitude.core.models.Stats
import altitude.core.models.User
import altitude.core.transactions.TransactionManager
import altitude.core.util.Query
import altitude.core.util.QueryResult
import play.api.libs.json.JsObject

class RepositoryService(val app: Altitude) extends BaseService[Repository] {
  protected val dao: RepositoryDao = app.DAO.repository

  override protected val txManager: TransactionManager = app.txManager

  /**
   * The Repository model is a model that does not have repository_id. Other models are scoped by it as no operations are
   * cross-repo (normally).
   */
  override def query(query: Query): QueryResult = {
    txManager.asReadOnly[QueryResult] {
      dao.query(query)
    }
  }

  def addRepository(name: String, fileStoreType: String, owner: User): JsObject = {
    val id = BaseDao.genId

    val repoToSave = Repository(
      id = Some(id),
      name = name,
      ownerAccountId = owner.persistedId,
      rootFolderId = BaseDao.genId,
      fileStoreType = fileStoreType
    )

    txManager.withTransaction[JsObject] {
      val repo: Repository = super.add(repoToSave)

      // we must force the context to the new repository because following operations depend on this
      switchContextToRepository(repo)

      logger.info(s"Creating repository [$repo] system folders")

      val rootFolder = Folder(
        id = Some(contextRepo.rootFolderId),
        parentId = contextRepo.rootFolderId,
        name = FieldConst.Folder.Name.ROOT
      )

      app.service.folder.add(rootFolder)

      app.service.stats.createStat(Stats.SORTED_ASSETS)
      app.service.stats.createStat(Stats.SORTED_BYTES)

      app.service.stats.createStat(Stats.TRIAGE_ASSETS)
      app.service.stats.createStat(Stats.TRIAGE_BYTES)

      app.service.stats.createStat(Stats.RECYCLED_ASSETS)
      app.service.stats.createStat(Stats.RECYCLED_BYTES)
      logger.info(s"Created repository [$repo]")

      repo.toJson
    }
  }

  override def getById(id: String): JsObject = {
    // try cache first
    if (app.repositoriesById.contains(id)) {
      return app.repositoriesById(id).toJson
    }

    val repo = super.getById(id)

    app.repositoriesById += (id -> repo)
    repo
  }

  def switchContextToRepository(repo: Repository): Unit = {
    RequestContext.repository.value = Some(repo)
  }

  def setContextFromRequest(repoId: Option[String]): Unit = {
    if (repoId.nonEmpty) {
      try {
        val repo: Repository = getById(repoId.get)
        RequestContext.repository.value = Some(repo)
      }
      catch  {
        case _: NotFoundException =>
      }
    }
  }
}
