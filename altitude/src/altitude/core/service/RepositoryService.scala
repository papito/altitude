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

object RepositoryService:

  /**
   * How many Libraries a User or a Guest may be granted access to: every grant rides in the login token, which the session cookie
   * carries, and a browser keeps at most 4096 bytes of a cookie. A grant costs about 57 bytes of token; the longest account a
   * token describes (80 four-byte characters of name, which the token's JSON escapes to 12 bytes each, and the longest email
   * address and username) costs about 1820 with none, so 37 fit with the cookie's name and attributes, and the bound keeps a
   * margin below that (`LoginTokenTests`, "A token at the grant bound fits the session cookie").
   */
  val MAX_GRANTS_PER_ACCOUNT = 35

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
   * Gives the account access to the Library with `permissions`, replacing what it held there. The account and the Library are
   * read in the write's transaction, so an unknown one is a `NotFoundException`, and the account's type is the stored one, not
   * that of a model the caller holds: an Admin holds every Permission in every Library with no grant, and a Guest holds none, so
   * a grant to an Admin, or one with a Permission to a Guest, is an `IllegalOperationException`. So is a grant in one Library
   * more than an account may hold (`MAX_GRANTS_PER_ACCOUNT`, `TOO_MANY_GRANTS`); the account's row is locked first, so of two
   * grants racing past the bound, the second counts the first's. Once committed, the account's version is bumped, so its sessions
   * pick the grant up at their next request.
   */
  def setGrant(accountId: String, repositoryId: String, permissions: Permissions): Unit =
    txManager.withTransaction {
      val account = app.DAO.user.lockById(accountId)
      dao.getById(repositoryId)

      account.accountType match
        case AccountType.Admin =>
          throw IllegalOperationException("An Admin holds every Permission in every Library with no grant")
        case AccountType.Guest if !permissions.isEmpty => throw IllegalOperationException("A Guest holds no Permission")
        case _ =>

      val held = grantDao.getByAccount(accountId)
      if !held.exists(_.repositoryId == repositoryId) && held.size >= RepositoryService.MAX_GRANTS_PER_ACCOUNT then
        throw IllegalOperationException(C.Msg.Err.TOO_MANY_GRANTS.format(RepositoryService.MAX_GRANTS_PER_ACCOUNT))

      grantDao.setGrant(accountId, repositoryId, permissions)

      txManager.afterCommit {
        logger.info(s"Account [$accountId] granted $permissions in [$repositoryId]")
        app.service.accountVersions.bump(accountId)
      }
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
