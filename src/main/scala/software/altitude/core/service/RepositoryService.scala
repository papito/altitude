package software.altitude.core.service

import play.api.libs.json.JsObject
import software.altitude.core.Altitude
import software.altitude.core.AltitudeServletContext
import software.altitude.core.FieldConst
import software.altitude.core.RequestContext
import software.altitude.core.dao.RepositoryDao
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.Folder
import software.altitude.core.models.Repository
import software.altitude.core.models.Stats
import software.altitude.core.models.User
import software.altitude.core.transactions.TransactionManager

class RepositoryService(val app: Altitude) extends BaseService[Repository] {
  protected val dao: RepositoryDao = app.DAO.repository

  override protected val txManager: TransactionManager = app.txManager

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

      logger.info(s"Setting up repository [${RequestContext.getRepository.name}] statistics")
      app.service.stats.createStat(Stats.SORTED_ASSETS)
      app.service.stats.createStat(Stats.SORTED_BYTES)
      app.service.stats.createStat(Stats.TRIAGE_ASSETS)
      app.service.stats.createStat(Stats.TRIAGE_BYTES)
      app.service.stats.createStat(Stats.RECYCLED_ASSETS)
      app.service.stats.createStat(Stats.RECYCLED_BYTES)

      logger.info(s"Created repository [$repo]")

      repo
    }
  }

  override def getById(id: String): JsObject = {
    // try cache first
    if (AltitudeServletContext.repositoriesById.contains(id)) {
      return AltitudeServletContext.repositoriesById(id).toJson
    }

    val repo = super.getById(id)

    AltitudeServletContext.repositoriesById += (id -> repo)
    repo
  }

  def switchContextToRepository(repo: Repository): Unit = {
    RequestContext.repository.value = Some(repo)
  }

  def setContextFromRequest(repoId: Option[String]): Unit = {
    if (repoId.nonEmpty) {
      val repo: Repository = getById(repoId.get)
      RequestContext.repository.value = Some(repo)
    }
  }
}
