package altitude.core.service

import java.sql.SQLException

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.dao.RepositoryDao
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Folder
import altitude.core.models.Repository
import altitude.core.models.Stats
import altitude.core.models.User
import altitude.core.transactions.TransactionManager
import altitude.core.util.NaturalOrder
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.Util.newDuplicateExceptionOrRethrow

class RepositoryService(val app: Altitude) extends BaseService[Repository]:
  protected val dao: RepositoryDao = app.DAO.repository

  override protected val txManager: TransactionManager = app.txManager

  /**
   * The Repository model is a model that does not have repository_id. Other models are scoped by it as no operations are
   * cross-repo (normally).
   */
  override def query(query: Query): QueryResult[Repository] =
    txManager.asReadOnly {
      dao.query(query)
    }

  /**
   * Adds a Library owned by `owner`, with its root folder and stats, under its name as the Library name rule normalizes it
   * (`Repository.validName`, whose `ValidationException` this passes on). A name whose name key another Library holds, any
   * owner's, fails with a `DuplicateException` saying so: `repository_01` decides it, so of two adds racing on one name, one
   * fails.
   */
  def addRepository(name: String, fileStoreType: String, owner: User): Repository =
    val id = BaseDao.genId

    val repoToSave = Repository(
      id = Some(id),
      name = Repository.validName(name),
      ownerAccountId = owner.persistedId,
      rootFolderId = BaseDao.genId,
      fileStoreType = fileStoreType
    )

    txManager.withTransaction {
      val repo: Repository =
        try dao.add(repoToSave)
        catch
          case e: SQLException =>
            logger.debug(s"Adding repository [${repoToSave.name}] failed: ${e.getMessage}")
            throw newDuplicateExceptionOrRethrow(e, Some(C.Msg.Err.LIBRARY_NAME_TAKEN.format(repoToSave.name)))

      // we must force the context to the new repository because following operations depend on this
      switchContextToRepository(repo)

      logger.debug(s"Creating repository [$repo] system folders")

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
      logger.debug(s"Created repository [$repo]")

      repo
    }

  /** The Libraries `account` owns, by name: case-insensitive and natural ("Client 2" before "Client 10") */
  def getOwnedBy(account: User): List[Repository] =
    txManager
      .asReadOnly {
        dao.getOwnedBy(account.persistedId)
      }
      .sortBy(_.name)(using NaturalOrder)

  /**
   * The Library login and `/` open for `user`: the last active one, read from the database (the login token does not carry it),
   * while it exists and `user` owns it. Otherwise the first Library `user` owns by name, which is recorded as the last active
   * one, and None when `user` owns none. The reads take no write lock; only a fallback writes.
   */
  def landingRepositoryOf(user: User): Option[Repository] =
    val (lastActiveId, owned) = txManager.asReadOnly {
      (app.service.user.getById(user.persistedId).lastActiveRepoId, getOwnedBy(user))
    }

    lastActiveId.flatMap(id => owned.find(_.persistedId == id)).orElse {
      val first = owned.headOption
      first.foreach {
        repo =>
          logger.debug(s"User [${user.persistedId}] lands in [${repo.persistedId}], the first owned: last active [$lastActiveId]")
          app.service.user.rememberLastActiveRepository(user, repo.persistedId)
      }
      first
    }

  def getAll: List[Repository] =
    txManager.asReadOnly {
      dao.getAll
    }

  override def getById(id: String): Repository =
    // try cache first
    if app.repositoriesById.contains(id) then return app.repositoriesById(id)

    val repo: Repository = super.getById(id)

    app.repositoriesById += (id -> repo)
    repo

  def switchContextToRepository(repo: Repository): Unit =
    logger.trace(s"Switching the repository context to [${repo.persistedId}]")
    RequestContext.repository.value = Some(repo)

  def setContextFromRequest(repoId: Option[String]): Unit =
    if repoId.isDefined then
      try
        val repo: Repository = getById(repoId.get)
        RequestContext.repository.value = Some(repo)
      catch case _: NotFoundException => logger.debug(s"Repository [${repoId.get}] from the request not found; no context set")
