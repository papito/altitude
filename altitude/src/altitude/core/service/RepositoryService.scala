package altitude.core.service

import java.sql.SQLException

import altitude.core.{ Const => C }
import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.Validators
import altitude.core.dao.RepositoryDao
import altitude.core.dao.RepositoryGrantDao
import altitude.core.dao.jdbc.BaseDao
import altitude.core.dao.jdbc.BaseDao.newDuplicateExceptionOrRethrow
import altitude.core.models.AccountType
import altitude.core.models.AuditEventType
import altitude.core.models.Folder
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.Stats
import altitude.core.models.User
import altitude.core.transactions.TransactionManager
import altitude.core.util.SortKey
import altitude.core.util.Util

object RepositoryService:

  /**
   * How many Libraries a User or a Guest may be granted: every grant rides in the login token in the session cookie, which a
   * browser caps at 4096 bytes. A grant costs about 57 bytes; the longest account (80 four-byte name characters, each escaped to
   * 12 bytes in JSON, and the longest email and username) costs about 1820 with none, so 37 fit with the cookie's name and
   * attributes, and 35 keeps a margin (`LoginTokenTests` proves it fits).
   */
  val MAX_GRANTS_PER_ACCOUNT = 35

class RepositoryService(val app: Altitude) extends BaseService[Repository]:
  protected val dao: RepositoryDao = app.DAO.repository
  private val grantDao: RepositoryGrantDao = app.DAO.repositoryGrant

  override protected val txManager: TransactionManager = app.txManager

  /**
   * Adds a Library with its root folder, stats and new-Library grants ([[grantNewLibrary]]). A Library has no owner: an Admin
   * reaches it by type, anyone else by a grant. `repository_01` decides a name-key clash, so of two adds racing on one name, one
   * fails with a `DuplicateException`. A non-Admin creator at its grant bound fails the whole add, so no Library is left that its
   * creator cannot open.
   */
  def addRepository(name: String, fileStoreType: String, creatorId: Option[String] = None): Repository =
    val id = BaseDao.genId

    val repoToSave = Repository(
      id = Some(id),
      name = Repository.validName(name),
      rootFolderId = BaseDao.genId,
      fileStoreType = fileStoreType
    )

    // The add switches the request's Library to the new one; a refusal puts the caller's back, so the error response still names
    // the Library it came from
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
            if BaseDao.violates(e, RepositoryDao.NAME_KEY_INDEX) then
              throw DuplicateException(Some(C.Msg.Err.LIBRARY_NAME_TAKEN.format(repoToSave.name)))
            throw newDuplicateExceptionOrRethrow(e)

      // The folder and stats writes below act in the context Library
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
   * Grants a new Library: their new-Library Permissions to accounts whose switch is on, and every Permission to a non-Admin
   * creator (by stored type). An account at its grant bound is skipped with a WARN, so another account's bound never fails the
   * add; the creator's does. Each grant is audited as the creator's act (no actor at setup).
   */
  private def grantNewLibrary(repo: Repository, creatorId: Option[String]): Unit =
    // Rows are locked in the order of every account write, so none deadlocks with this: the active Admins first (an Admin's
    // account write takes them), then every grantee in ID order, before any grant is written. An Admin creator is among the
    // grantees, so without the Admins first it could hold a grantee's row that an Admin's edit of that grantee waits on.
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
      .sortBy(repository => (SortKey.of(repository.name), repository.persistedId))

  /**
   * Gives the account `permissions` in the Library, replacing what it held there. The account's type is read under its row lock,
   * not taken from a caller's model, and the lock makes the second of two grants racing past `MAX_GRANTS_PER_ACCOUNT` count the
   * first. Once committed, the bumped account version makes its sessions pick the grant up at their next request.
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

  /** Replaces all the account's grants (Library ID to Permissions) under the rules of [[setGrant]]; a refusal writes nothing */
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

  // `getById` refuses an unknown Library; an Admin holds every Permission without grants, a Guest holds none
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
   * The Library login and `/` open: the last active one (from the database, as the login token does not carry it) while `user`
   * can still see it, else the first they can see by name, recorded as the new last active one; None when they can see none
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
    if app.repositoriesById.contains(id) then return app.repositoriesById(id)

    val repo: Repository = super.getById(id)

    app.repositoriesById += (id -> repo)
    repo

  def switchContextToRepository(repo: Repository): Unit =
    logger.trace(s"Switching the repository context to [${repo.persistedId}]")
    RequestContext.repository.value = Some(repo)

  /**
   * Sets the Library a request's path names as the repository context, or none for an unknown one. An ID that is no UUID is
   * refused before any read: PostgreSQL compares a text parameter with a `CHAR(36)` ID as `bpchar`, ignoring trailing spaces, so
   * an ID padded with them would find its Library, and each such spelling would cost a read and a place in the cache.
   */
  def setContextFromRequest(repoId: Option[String]): Unit =
    repoId.foreach {
      id =>
        if !Validators.isUuid(id) then
          logger.debug(s"Repository [${Util.printable(id, 64)}] from the request is no ID; no context set")
        else
          try RequestContext.repository.value = Some(getById(id))
          catch case _: NotFoundException => logger.debug(s"Repository [$id] from the request not found; no context set")
    }
