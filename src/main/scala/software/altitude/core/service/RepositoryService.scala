package software.altitude.core.service

import org.apache.commons.io.FilenameUtils
import play.api.libs.json.JsObject
import software.altitude.core.Altitude
import software.altitude.core.RequestContext
import software.altitude.core.dao.RepositoryDao
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.{Folder, Repository, Stats, User}
import software.altitude.core.transactions.TransactionManager
import software.altitude.core.{Const => C}

class RepositoryService(val app: Altitude) extends BaseService[Repository] {
  protected val dao: RepositoryDao = app.DAO.repository

  override protected val txManager: TransactionManager = app.txManager

  def addRepository(name: String, fileStoreType: String, owner: User): JsObject = {
    logger.info(s"Creating repository [$name]")

    val id = BaseDao.genId

    // FIXME: storage service function
    val workPath = System.getProperty("user.dir")
    logger.info(s"Repository [$name] work path: [$workPath]")
    val dataDir = app.config.getString(C.Conf.FS_DATA_DIR)
    logger.info(s"Repository [$name] data path: [$dataDir]")
    val dataPath = FilenameUtils.concat(workPath, dataDir)
    logger.info(s"Repository [$name] work path")
    logger.info(s"Data path: [$dataPath]")

    val reposDataPath = FilenameUtils.concat(dataPath, C.DataStore.REPOSITORIES)
    val repoDataPath = FilenameUtils.concat(reposDataPath, id.substring(0, 8))

    val repoToSave = Repository(
      id = Some(id),
      name = name,
      ownerAccountId = owner.persistedId,
      rootFolderId = BaseDao.genId,
      fileStoreType = fileStoreType,
      fileStoreConfig = Map(
        C.Repository.Config.PATH -> repoDataPath)
    )

    txManager.withTransaction[JsObject] {
      val repo: Repository = super.add(repoToSave)

      // we must force the context to the new repository because following operations depend on this
      switchContextToRepository(repo)

      logger.info(s"Creating repository [${repo.name}] system folders")

      val rootFolder = Folder(
        id = Some(contextRepo.rootFolderId),
        parentId = contextRepo.rootFolderId,
        name = C.Folder.Name.ROOT,
      )

      app.service.folder.add(rootFolder)

      logger.info(s"Setting up repository [${RequestContext.repository.value.get.name}] statistics")
      app.service.stats.createStat(Stats.SORTED_ASSETS)
      app.service.stats.createStat(Stats.SORTED_BYTES)
      app.service.stats.createStat(Stats.TRIAGE_ASSETS)
      app.service.stats.createStat(Stats.TRIAGE_BYTES)
      app.service.stats.createStat(Stats.RECYCLED_ASSETS)
      app.service.stats.createStat(Stats.RECYCLED_BYTES)

      repo
    }
  }

  def switchContextToRepository(repo: Repository): Unit = {
    RequestContext.repository.value = Some(repo)
  }
}
