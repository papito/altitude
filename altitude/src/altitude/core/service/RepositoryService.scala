package altitude.core.service

import java.sql.SQLException

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.dao.RepositoryDao
import altitude.core.dao.RepositoryGrantDao
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.AccountType
import altitude.core.models.Folder
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.Stats
import altitude.core.models.User
import altitude.core.transactions.TransactionManager
import altitude.core.util.NaturalOrder
import altitude.core.util.Util.newDuplicateExceptionOrRethrow

class RepositoryService(val app: Altitude) extends BaseService[Repository]:
  protected val dao: RepositoryDao = app.DAO.repository
  private val grantDao: RepositoryGrantDao = app.DAO.repositoryGrant

  override protected val txManager: TransactionManager = app.txManager

  /**
   * Adds a Library, with its root folder and stats, under its name as the Library name rule normalizes it
   * (`Repository.validName`, whose `ValidationException` this passes on). A Library has no owner: it belongs to the server, an
   * Admin reaches it by type and anyone else by a grant. A name whose name key another Library holds fails with a
   * `DuplicateException` saying so: `repository_01` decides it, so of two adds racing on one name, one fails.
   */
  def addRepository(name: String, fileStoreType: String): Repository =
    val id = BaseDao.genId

    val repoToSave = Repository(
      id = Some(id),
      name = Repository.validName(name),
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

  /**
   * The Libraries `account` can see, by name: case-insensitive and natural ("Client 2" before "Client 10"). Every Library for an
   * Admin; for anyone else, those it holds a grant in.
   */
  def getAccessibleTo(account: User): List[Repository] =
    txManager
      .asReadOnly {
        if account.accountType == AccountType.Admin then dao.getAll else dao.getGrantedTo(account.persistedId)
      }
      .sortBy(_.name)(using NaturalOrder)

  /**
   * Whether `account` can see the Library: an Admin can see every one, anyone else one it holds a grant in. Task B moves this to
   * the grants in the login token; until then it reads the grant from the database.
   */
  def isAccessibleTo(account: User, repositoryId: String): Boolean =
    account.accountType == AccountType.Admin || txManager.asReadOnly {
      grantDao.getGrant(account.persistedId, repositoryId).isDefined
    }

  /**
   * Gives the account access to the Library with `permissions`, replacing what it held there. The account and the Library are
   * read in the write's transaction, so an unknown one is a `NotFoundException`, and the account's type is the stored one, not
   * that of a model the caller holds: an Admin holds every Permission in every Library with no grant, and a Guest holds none, so
   * a grant to an Admin, or one with a Permission to a Guest, is an `IllegalOperationException`.
   */
  def setGrant(accountId: String, repositoryId: String, permissions: Permissions): Unit =
    txManager.withTransaction {
      val account = app.service.user.getById(accountId)
      dao.getById(repositoryId)

      account.accountType match
        case AccountType.Admin =>
          throw IllegalOperationException("An Admin holds every Permission in every Library with no grant")
        case AccountType.Guest if !permissions.isEmpty => throw IllegalOperationException("A Guest holds no Permission")
        case _ => grantDao.setGrant(accountId, repositoryId, permissions)

      txManager.afterCommit(logger.info(s"Account [$accountId] granted $permissions in [$repositoryId]"))
    }

  /** The account's Permissions in the Library, None when it holds no grant there */
  def getGrant(account: User, repositoryId: String): Option[Permissions] =
    txManager.asReadOnly {
      grantDao.getGrant(account.persistedId, repositoryId).map(_.permissions)
    }

  /**
   * The Library login and `/` open for `user`: the last active one, read from the database (the login token does not carry it),
   * while it exists and `user` can see it. Otherwise the first Library `user` can see by name, which is recorded as the last
   * active one, and None when `user` can see none. The reads take no write lock; only a fallback writes.
   */
  def landingRepositoryOf(user: User): Option[Repository] =
    val (lastActiveId, accessible) = txManager.asReadOnly {
      (app.service.user.getById(user.persistedId).lastActiveRepoId, getAccessibleTo(user))
    }

    lastActiveId.flatMap(id => accessible.find(_.persistedId == id)).orElse {
      val first = accessible.headOption
      first.foreach {
        repo =>
          logger.debug(
            s"User [${user.persistedId}] lands in [${repo.persistedId}], the first accessible: last active [$lastActiveId]")
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
