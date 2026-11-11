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
import altitude.core.models.AuditEventType
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
   *
   * In the same transaction, every account whose new-Library switch is on is granted its new-Library Permissions in the Library
   * (`grantNewLibrary`), and `creatorId`, the account creating it from the New library dialog, unless it is an Admin, is granted
   * every Permission: a creator already at its bound of grants fails the whole add with `IllegalOperationException`
   * (`TOO_MANY_GRANTS`), so no Library is left that its creator cannot open.
   */
  def addRepository(name: String, fileStoreType: String, creatorId: Option[String] = None): Repository =
    val id = BaseDao.genId

    val repoToSave = Repository(
      id = Some(id),
      name = Repository.validName(name),
      rootFolderId = BaseDao.genId,
      fileStoreType = fileStoreType
    )

    // The add switches the request's Library to the new one, which a refusal rolls back: the caller's is put back then, so the
    // answer to the refusal (the New library dialog again) still names the Library it was opened from
    val previousContext = RequestContext.repository.value
    try addRepositoryInContext(repoToSave, creatorId)
    catch
      case e: Throwable =>
        RequestContext.repository.value = previousContext
        throw e

  private def addRepositoryInContext(repoToSave: Repository, creatorId: Option[String]): Repository =
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

      grantNewLibrary(repo, creatorId)
      repo
    }

  /**
   * The grants of a Library just created: those of the accounts whose new-Library switch is on, each with its new-Library
   * Permissions, and the creator's, every Permission for one that is no Admin (by its stored type). An account refused a grant
   * (one at its bound of grants) is skipped with a WARN, so creating a Library never fails for another account's bound; the
   * creator's refusal fails the add. Each grant is recorded as a change of Permissions, acted by the creator (none for a Library
   * added with no creator, as setup adds one), and bumps its account's version once the add commits (`setGrant`).
   */
  private def grantNewLibrary(repo: Repository, creatorId: Option[String]): Unit =
    // The rows are locked in the one order of every account write: the active Admins' first, in ID order (`lockActiveAdmins`, as
    // an Admin's account write takes them), then every account granted in one pass, in ID order, before any grant is written.
    // An Admin creating the Library is among the grantees' rows, so without the Admins first it could hold a grantee's row while
    // waiting on its own, which an Admin's edit of that grantee holds while it waits on the grantee; and two creates at once,
    // each granting the other's creator, take the rows in one order, so neither holds one the other waits on while it waits.
    app.DAO.user.lockActiveAdmins()
    val locked = app.DAO.user.lockGrantees(creatorId)
    val actor = creatorId.flatMap(id => locked.find(_.id.contains(id)))
    val creator = actor.filter(_.accountType != AccountType.Admin)

    def grant(account: User, permissions: Permissions): Unit =
      setGrant(account.persistedId, repo.persistedId, permissions)
      app.service.audit.record(
        AuditEventType.PermissionsChanged,
        actor = actor,
        target = Some(account),
        detail = ujson.Obj("library" -> repo.persistedId, "permissions" -> ujson.Arr.from(permissions.toList.map(_.toString)))
      ): Unit

    locked
      .filter(_.grantsNewLibraries)
      .filterNot(account => account.accountType == AccountType.Admin || creator.exists(_.id == account.id))
      .foreach {
        account =>
          try grant(account, account.newLibraryPermissions)
          catch
            case e: IllegalOperationException =>
              logger.warn(s"Account [${account.persistedId}] not granted the new library [${repo.persistedId}]: ${e.getMessage}")
      }

    creator.foreach(grant(_, Permissions.all))

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
      checkGrant(account, repositoryId, permissions)

      val held = grantDao.getByAccount(accountId)
      if !held.exists(_.repositoryId == repositoryId) && held.size >= RepositoryService.MAX_GRANTS_PER_ACCOUNT then throwTooMany()

      grantDao.setGrant(accountId, repositoryId, permissions)

      txManager.afterCommit {
        logger.info(s"Account [$accountId] granted $permissions in [$repositoryId]")
        app.service.accountVersions.bump(accountId)
      }
    }

  /**
   * Replaces the account's grants with `grants`, Library ID to Permissions, in one transaction: the grants it held elsewhere go,
   * and an Admin is left with none. Each grant is held to the rules of [[setGrant]] (a stored Library, no grant to an Admin, no
   * Permission to a Guest, at most `MAX_GRANTS_PER_ACCOUNT`), the account's row locked first, and any refusal writes nothing.
   * Once committed, the account's version is bumped.
   */
  def replaceGrants(accountId: String, grants: Map[String, Permissions]): Unit =
    txManager.withTransaction {
      val account = app.DAO.user.lockById(accountId)
      grants.foreach((repositoryId, permissions) => checkGrant(account, repositoryId, permissions))
      if grants.size > RepositoryService.MAX_GRANTS_PER_ACCOUNT then throwTooMany()

      grantDao.deleteByAccount(accountId)
      grants.foreach((repositoryId, permissions) => grantDao.setGrant(accountId, repositoryId, permissions))

      txManager.afterCommit {
        logger.info(s"Account [$accountId] holds grants in [${grants.size}] libraries")
        app.service.accountVersions.bump(accountId)
      }
    }

  // A grant to the account, as stored, in the Library: refused for an unknown Library (`NotFoundException`), to an Admin, who
  // holds every Permission in every Library with no grant, and of a Permission to a Guest, who holds none
  private def checkGrant(account: User, repositoryId: String, permissions: Permissions): Unit =
    dao.getById(repositoryId)
    account.accountType match
      case AccountType.Admin =>
        throw IllegalOperationException("An Admin holds every Permission in every Library with no grant")
      case AccountType.Guest if !permissions.isEmpty => throw IllegalOperationException("A Guest holds no Permission")
      case _ =>

  private def throwTooMany(): Nothing =
    throw IllegalOperationException(C.Msg.Err.TOO_MANY_GRANTS.format(RepositoryService.MAX_GRANTS_PER_ACCOUNT))

  /** The account's grants, Library ID to its Permissions there: what it may pass on to an account it creates */
  def getGrants(account: User): Map[String, Permissions] =
    txManager.asReadOnly {
      grantDao.getByAccount(account.persistedId).map(grant => grant.repositoryId -> grant.permissions).toMap
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
