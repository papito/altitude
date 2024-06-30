package software.altitude.core.service


import net.codingwell.scalaguice.InjectorExtensions._
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject
import software.altitude.core.Altitude
import software.altitude.core.RequestContext
import software.altitude.core.dao.RepositoryDao
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.Repository
import software.altitude.core.models.Stats
import software.altitude.core.models.User
import software.altitude.core.transactions.TransactionManager
import software.altitude.core.{Const => C}

class RepositoryService(val app: Altitude) extends BaseService[Repository] {
  private final val log = LoggerFactory.getLogger(getClass)
  protected val dao: RepositoryDao = app.injector.instance[RepositoryDao]
  override protected val txManager: TransactionManager = app.txManager

  def addRepository(name: String, fileStoreType: C.FileStoreType.Value, owner: User): JsObject = {
    log.info(s"Creating repository [$name]")

    val workPath = System.getProperty("user.dir")
    log.info(s"Repository [$name] work path: [$workPath]")
    val dataDir = app.config.getString("dataDir")
    log.info(s"Repository [$name] data path: [$dataDir]")
    val dataPath = FilenameUtils.concat(workPath, dataDir)
    log.info(s"Repository [$name] work path")
    log.info(s"Data path: [$dataPath]")

    val id = BaseDao.genId

    // FIXME: storage service function
    val reposDataPath = FilenameUtils.concat(dataPath, "repositories")
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

      log.info(s"Creating repository [${repo.name}] system folders")

      app.service.folder.add(app.service.folder.rootFolder)

      log.info(s"Setting up repository [${RequestContext.repository.value.get.name}] statistics")
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
