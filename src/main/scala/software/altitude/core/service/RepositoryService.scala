package software.altitude.core.service


import net.codingwell.scalaguice.InjectorExtensions._
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject
import software.altitude.core.Altitude
import software.altitude.core.Context
import software.altitude.core.NotFoundException
import software.altitude.core.dao.RepositoryDao
import software.altitude.core.models.BaseModel
import software.altitude.core.models.Repository
import software.altitude.core.models.Stats
import software.altitude.core.models.User
import software.altitude.core.transactions.AbstractTransactionManager
import software.altitude.core.transactions.TransactionId
import software.altitude.core.{Const => C}

class RepositoryService(val app: Altitude) extends BaseService[Repository] {
  private final val log = LoggerFactory.getLogger(getClass)
  protected val dao: RepositoryDao = app.injector.instance[RepositoryDao]
  override protected val txManager: AbstractTransactionManager = app.injector.instance[AbstractTransactionManager]

  override def getById(id: String)(implicit ctx: Context, txId: TransactionId = new TransactionId): JsObject = {
    throw new NotImplementedError
  }

  def getRepositoryById(id: String)(implicit txId: TransactionId = new TransactionId): Repository = {
    txManager.asReadOnly[JsObject] {
      implicit val context = Context.EMPTY

      dao.getById(id) match {
        case Some(obj) => obj
        case None => throw NotFoundException(s"Cannot find ID '$id'")
      }
    }
  }

  def addRepository(name: String, fileStoreType: C.FileStoreType.Value, user: User, id: Option[String] = None)
         (implicit txId: TransactionId = new TransactionId): JsObject = {

    log.info(s"Creating repository [$name]")

    val workPath = System.getProperty("user.dir")
    log.info(s"Repository [$name] work path: [$workPath]")
    val dataDir = app.config.getString("dataDir")
    log.info(s"Repository [$name] data path: [$dataDir]")
    val dataPath = FilenameUtils.concat(workPath, dataDir)
    log.info(s"Repository [$name] work path")
    log.info(s"Data path: [$dataPath]")

    val repoToSave = Repository(
      id = id,
      name = name,
      rootFolderId = BaseModel.genId,
      triageFolderId = BaseModel.genId,
      fileStoreType = fileStoreType,
      fileStoreConfig = Map(C.Repository.Config.PATH -> dataPath))

    txManager.withTransaction[JsObject] {
      val repo: Repository = super.add(repoToSave)(txId = txId, ctx = new Context(repo = null, user = user))
      implicit val ctx: Context = new Context(repo = repo, user = user)

      log.info(s"Creating repository [${ctx.repo.name}] system folders")

      val rootFolder = app.service.folder.add(app.service.folder.rootFolder)
      app.service.fileStore.addFolder(rootFolder)

      val triageFolder = app.service.folder.add(app.service.folder.triageFolder)
      app.service.fileStore.addFolder(triageFolder)

      // trash does not have an explicit folder record - just the storage location
      app.service.fileStore.createPath(app.service.fileStore.trashFolderPath)

      log.info(s"Setting up repository [${ctx.repo.name}] statistics")
      app.service.stats.createStat(Stats.SORTED_ASSETS)
      app.service.stats.createStat(Stats.SORTED_BYTES)
      app.service.stats.createStat(Stats.TRIAGE_ASSETS)
      app.service.stats.createStat(Stats.TRIAGE_BYTES)
      app.service.stats.createStat(Stats.RECYCLED_ASSETS)
      app.service.stats.createStat(Stats.RECYCLED_BYTES)

      repo
    }
  }
}

