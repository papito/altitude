# Users and permissions: accounts, per-Library Permissions, and the Users page

## Goals

- An Admin, and a User allowed to, adds accounts from a Users page: a grid of account cards under three tabs (Admins, Users,
  Guests, in that order), with an Add User button that opens the only way to add one, a modal.
- Each account carries its own Permissions, per Library: Import, Curate own, Curate all, Organize, Edit Metadata, Create
  Users, Create Guests, Recycle, Purge. Import and Curate own are checked by default. Looking needs no Permission: an active
  account with access to a Library can view it.
- The creating account chooses which Libraries the new account can see, never one it cannot see itself, and never a
  Permission it does not hold in that Library.
- An Admin chooses which Libraries any account sees, and can have an account granted access to Libraries created later, with
  Permissions the Admin picks for them.
- An account has a `username`. The username or the email logs in, never the name, and neither can belong to another account.
- `asset.owner_user_id` names the account that owns an asset.
- Deleting an account is permanent. Its assets move to the deleting Admin, and each asset keeps a JSON record of the person
  who imported it (name, username, email).
- Only an Admin changes an account (name, email, username, type, Permissions), deactivates it, reactivates it or deletes it.
  A change of the name, username or email rewrites the importer record of the assets that person imported.
- A deactivated account can do nothing until it is reactivated.
- Authorization reads no database on a request whose login token is current.

Out of scope: rate limiting and lockout of failed logins, two-factor login, an audit log screen, share links for clients, an
account's own edit of its name or email.

## How it works

**Accounts.** `account` holds `email`, `username`, `name`, `account_type` (Admin, User, Guest), `password_hash`, `is_active`,
the Create Libraries flag (`can_create_libraries`), the new-Library grant (`grants_new_libraries`,
`new_library_permissions`), `created_by_account_id` (`ON DELETE SET NULL`), `last_login_at` and `last_active_repo_id`. The
email address and the username are each unique under their name key (`email_key` under `account_01`, `username_key` under
`account_02`, `NameKey.of`, folded in Scala). `User.valid` holds an account to its rules: a username of 3 to 32 ASCII
letters, digits, `.`, `_` and `-`, a name of 2 to 80 characters once normalized, an email address of 3 to 80 ASCII
characters.

**Libraries and grants.** A Library has no owner. An Admin reaches every Library by type; anyone else reaches a Library
through a `repository_grant` row (`account_id`, `repository_id`, `permissions`, a bitmask of `Permission` bits; 0 for a
Guest). A `Permission` keeps a stable bit (0 to 8, in the order the UI lists them); `Permissions` is the mask and refuses an
unknown bit. `RepositoryService.setGrant` and `replaceGrants` lock the account row, hold the grant to the account's stored
type (none to an Admin, no Permission to a Guest) and to `MAX_GRANTS_PER_ACCOUNT` (35), and bump the account's version once
committed.

**Login and the token.** `UserService.loginAndSetUser(login, password, clientIp)` folds the login with `NameKey.of` and reads
the account by `email_key` when the folded login holds an "@", by `username_key` otherwise, and checks the bcrypt hash in a
read-only transaction (a dummy hash for an unknown login; a login with a control character is not looked up). An unknown
login, a wrong password and a deactivated account answer the same 401. The account's version is then read, and a short write
transaction locks the account (`UserDao.lockWithPasswordHash`), refuses the login if the hash changed meanwhile, builds the
claims, stamps `last_login_at` and records the audit event. The token is PASETO v2.local under a key random per process, in
the `auth_token` cookie (`SessionController.authCookie`) or a Bearer header, valid 7 days to the second. Its claims
(`TokenClaims`) are the account's ID, email, username, name, account type and Create Libraries flag, its grants (Library ID
to mask; none for an Admin) and its version; `PasetoService.readToken` is the one reader.

**Account versions.** `AccountVersions` (one per process, `AccountVersions.processWide`) holds per account a current version
and a revoked-below version, with `snapshot`, `bump`, `revoke` and `onChange`. Account writes call them through
`TransactionManager.afterCommit`. `UserService.checkToken` judges a token against one snapshot: current, its claims hold with
no query; stale, it is rebuilt from the database with its original expiry and the answer replaces the cookie, unless the
token came in a Bearer header or the account is deactivated or gone, which are refused; revoked, refused. A refusal clears
the cookie only for a token that does not decrypt or has expired, or whose account is gone or deactivated.

**A request.** `decorators.requireLogin` authenticates (`decorators.authenticate`: the dev user, or the token of the Bearer
header or the cookie). A Library in the path that the token gives no access to is a 404, as an unknown one is. The token's
Permissions in the path's Library go into `RequestContext.permissions` (every one for an Admin) and its grants into
`RequestContext.grants`. Without a login, an htmx request is a 401 with `HX-Redirect: /login?redirect=…`, an API request a
JSON 401, any other a redirect to the login. The gates after it, `requirePermission(anyOf*)`, `requireMember`,
`requireAccountCreator`, `requireLibraryCreator` and `requireAdmin`, answer a refusal with one 403 shape
(`decorators.forbidden`): `{"error": why}` in JSON, "Forbidden: why" in plain text.

**Rules on the asset.** `AssetRules` checks, in the service's transaction and over assets read scoped to the Library, what no
route can decide: Curate own against `owner_user_id`, recycling and restoring a triaged asset (a Curate Permission) or a
sorted one (Recycle). One refused asset fails the operation with `ForbiddenException`, which `BaseController.answering` and
`answeringJson` answer 403. Under Curate own alone, Curation shows and counts only the account's own frames
(`AssetRules.curatedOwner`).

**Account management.** `UserService` holds the Users page's writes (`create`, `update`, `deactivate`, `reactivate`,
`delete`, `setPassword`, `changeOwnPassword`), each in one transaction that reads the acting account from the database and
records its audit event. The three statements that reach `asset` in every Library with no `repository_id` are
`AssetDao.countOwnedBy`, `reassignOwner` and `rewriteImporterRecords`.

**Assets.** `asset.owner_user_id` (`NOT NULL REFERENCES account (id)`, no delete action) is the importer until the account is
deleted. `asset.original_owner` is the importer record (`AccountSnapshot`: `id`, `name`, `username`, `email`), built when the
import inserts the asset from the account row as stored then, under `FOR KEY SHARE`.

**Audit.** `AuditService.record` writes an append-only `audit_event` row in the caller's transaction and logs it at INFO once
that commits.

## Decisions

| Concern | Decision |
|---|---|
| Words | **Account**: the row. **Account type**: Admin, User or Guest (not "role"). **Permission**: one of the nine, per account and Library. **Library**, never Catalog or Repository, in user-facing text. The page and nav entry read "Users", the button "Add User" |
| Admin | Holds every Permission in every Library with no grant row. Alone changes Settings, creates an Admin, and edits, sets the password of, deactivates, reactivates and deletes accounts. Several may exist. A write that could remove an Admin locks the active Admins (`UserDao.lockActiveAdmins`, `ORDER BY id FOR UPDATE`) and requires the actor to be one of them under that lock, else `ForbiddenException` (`FORBIDDEN_ADMIN`), so one active Admin is always left; there is no separate last-Admin message. An Admin cannot delete or deactivate its own account (`IllegalOperationException`, `OWN_ACCOUNT`, a 409) or take the Admin type from it (`OWN_ACCOUNT` on the type field) |
| User | Holds any of the nine Permissions, per Library |
| Guest | Sees the Libraries shared with it and never holds a Permission. Sees Browse alone: no Triage, Trash, Curation or Import, as nav entries or routes (403). A route that takes the view as a parameter (the main page, the search results, the map's cells and bounds, the People list's `triaged`) answers `view=triage`, `view=trashbin` or `triaged=true` with 403 (`decorators.maySeeView`, `refusedView`). A triaged or recycled asset opened by ID (the Detail panel, the asset modal and its JSON, the asset page, the content route) is a 404 (`decorators.visibleOnly`); a Face crop is served. Its import status handshake is a 403 |
| A User's views | Every view of a Library it has access to; a control shows only where its Permission is held |
| Library ownership | None. A Library belongs to the server; access is an Admin's by type and anyone else's by a grant row |
| Create Libraries | One flag on the account, set by an Admin only (`requireLibraryCreator` lets an Admin or the flag's holder through). Creating a Library grants a non-Admin creator all nine Permissions in it; a creator at its bound of grants gets no Library: the create is refused with `TOO_MANY_GRANTS` and the dialog shows it |
| Create Users, Create Guests | Per Library: the holder may give a new User, or a new Guest, access to that Library. The Users page and Add User open for an Admin or a holder of either in any Library (`requireAccountCreator`, `RequestContext.createsAccounts`, from the token's grants). A User may pass both on |
| Grant ceiling | A non-Admin creator gives a User only Libraries where it holds Create Users, a Guest only Libraries where it holds Create Guests, and only Permissions it holds there, read from its grants in the write's transaction (`UserService.withinCeiling`). It cannot create an Admin or give the Create Libraries flag, the new-Library switch or new-Library Permissions (even with the switch off). Anything above is `ForbiddenException` (`FORBIDDEN_CEILING`, a 403); the modal does not render it. Reducing a creator later does not reduce the accounts it created |
| Access for a non-Admin | At least one Library (`LIBRARY_REQUIRED`) and at most `MAX_GRANTS_PER_ACCOUNT` (`TOO_MANY_GRANTS`) |
| Grant bound | `RepositoryService.MAX_GRANTS_PER_ACCOUNT = 35`, measured from a worst-case token: a name of 80 four-byte characters (12 bytes each once the token's JSON escapes them), an ASCII email of 80 characters, a 32-character username, every Permission in each Library; it fits the 4096 bytes a browser keeps of a cookie with a margin. `setGrant` refuses a new grant past it with `IllegalOperationException` (`TOO_MANY_GRANTS`) under the account's row lock. `PasetoService.createToken` logs an ERROR for a token past `MAX_TOKEN_BYTES` (3950) |
| New-Library grant | Per account, Admin only: a switch and a Permission set, the chips pre-filled with Import and Curate own (`AccountForm.PRESET`); a Guest's switch gives access with no Permission. With the switch off the Permissions are cleared (`UserService.shapedByType`). Creating a Library grants, in the add's transaction, every account whose switch is on (no Admin, not the creator) its new-Library Permissions; an account at its bound is skipped with a WARN. Each grant is recorded as `PermissionsChanged`, acted by the creator, and bumps the account's version |
| Import | The Import page, upload and cancel |
| Curate own, Curate all | Open Curation (its shell, queue, sequence, count and selection, and the main page's `?curate=`), resolve and reopen, and recycle in Triage: the account's own assets, or any triaged asset. Under Curate own alone, Curation shows only the account's own frames (`AssetRules.curatedOwner`), the queue counts only Sequences of them, a Sequence is complete when the set holds every live frame of it the account is shown, and the grouped grid's Curate button counts its own frames (`CurationService.curatableFrameCounts`). The only rule that reads `owner_user_id` |
| Organize | Sort assets out of Triage into folders, move sorted assets, manage folders, albums and their members, Locations, Categories and their members, geocode a place, and merge People (from the People list or from a Face), hide, show, delete and set the cover of People |
| Edit Metadata | A Face's Person (assign, name a Face's new Person, remove a Face), the list of assignable People, and a Person's name (edit and rename): the metadata edits the app has routes for |
| Recycle | Recycle a sorted asset, including by deleting a folder that holds assets |
| Purge | Delete permanently from Trash, one asset or all |
| Restore | What recycling the asset would take (`Permissions.mayRecycle`): Recycle for a sorted asset; Curate all, or Curate own on one's own, for a triaged one. Moving an asset out of the Trash is a restore |
| Where a Permission is checked | A route decorator, `@requirePermission(...)` (any one of the Permissions), `@requireMember`, `@requireAccountCreator`, `@requireLibraryCreator` or `@requireAdmin`, applied after `@requireLogin`. The rules that depend on the asset are checked in the service (`AssetRules`), in the transaction of the write. Templates and scripts render controls from the same bitmask (`RequestContext.holds`, `data-app-permissions`, `context.holds`) |
| A refusal | One 403 (`decorators.forbidden`), from a decorator or from a service's `ForbiddenException`. A service reads every ID scoped to the Library before any Permission check (Curation's `resolve` and `reopen` through `requireKnown`), so another Library's ID is a 404 as an unknown one is, never a 403 |
| DAOs of the new tables | `repository_grant` and `audit_event` are read and written with no Library in the context, so their DAOs opt out of scoping (`repositoryScoped = false`), as the DAOs of `account`, `repository`, `setting` and `system_metadata` do |
| Account writes across Libraries | Three statements reach `asset` in every Library at once, by account ID: `AssetDao.reassignOwner` (a delete), `rewriteImporterRecords` (an update, through the `asset_importer` index) and `countOwnedBy` (the Delete dialog). Each runs in the transaction of the account write; they are the only statements on a Library's table with no Library |
| Where authorization data lives | In the token's claims. No database read on a request whose token is current; a stale token's rebuild reads the account and its grants, a revoked cookie token reads whether the account is active, and the dev user's claims are read on each request |
| A change to an account | Takes effect at the account's next request. `setGrant` and `replaceGrants` bump, an Admin's edit bumps (through its grants), a reactivation and a delete bump; a deactivation and a password change revoke. A rebuilt token keeps the original expiry. `requireLogin` does not add its renewed cookie to an answer that sets the session cookie itself |
| Password change | Revokes every token of the account; the session that made the change (its own change, or an Admin setting its own password) gets a new cookie (`OwnPasswordController.renewedSession`) |
| Username | Required for every account, the setup Admin included, under `User.validUsername` (`USERNAME_RULE`). A login containing `@` is looked up as an email, any other as a username, so a username never equals an email |
| Uniqueness whatever the case | The username and the email are each unique under `NameKey.of` in their own `TEXT NOT NULL` key column under a unique index: `email_key` (`account_01`) and `username_key` (`account_02`). Never SQL `lower()` or a collation. A unique violation is the field's error (`EMAIL_TAKEN`, `USERNAME_TAKEN`). That a non-Admin creator learns an address or username is taken is an accepted exposure |
| Passwords | 8 to 50 characters. The creator types the new account's password (the modal can generate one). Every account changes its own, giving the current one (a wrong one is recorded as `PasswordChangeFailed` with the IP). An Admin sets any account's |
| A login | A deactivated account, a wrong password and a login with a control character all answer the same 401. The failed-login audit detail keeps the login cut to 64 characters with control characters escaped (`Util.printable`), and the WARN line escapes it the same way |
| Importer record | `asset.original_owner`, JSON (`AccountSnapshot`), built at insert from the account row as stored, under `FOR KEY SHARE` (`UserService.lockImporter`); a gone or deactivated account fails the file with `ImporterUnavailableException`. An update that changes the name, username or email rewrites the records whose `id` is that account, whoever owns the asset now. A delete leaves them |
| Deleting an account | An Admin only, in one transaction: its assets' `owner_user_id` becomes the deleting Admin, the row is deleted, which takes its grants (`ON DELETE CASCADE`) and clears `created_by_account_id` of the accounts it created (`ON DELETE SET NULL`) |
| `asset.owner_user_id` | `NOT NULL REFERENCES account (id)` on both engines, with no delete action, so an account with assets cannot be deleted without the reassignment; indexed by `asset_owner` |
| Asset JSON | The JSON sent to the browser (`Asset.toBrowserJson`) carries neither `owner_user_id` nor `original_owner` |
| Users page for a non-Admin | Read-only cards of the accounts it created that are no Admins, each listing only the Libraries the viewer can see |
| Users page layout | Capped at 75rem and centered. The header holds the title, a filter box that narrows the cards by name, username or email in any case or Unicode form, with no request, and one Add User button at the right. Under it a toggle group of three tabs (Admins, Users, Guests, each with its count) shows one section at a time, as a grid of cards about 17rem wide that auto-fills the row. The server writes the start tab (`UserActionController.startTab`: the first tab holding a card, else Users, or Guests for a creator of Guests alone) |
| An account card | Padded by `--space-6`. An initials avatar, then the name with `@username` under it, dimmed and monospace. Then Email, Status (Active or Deactivated; a deactivated card is dimmed), Libraries, New Libraries and Created (`created_at`) |
| Libraries on a card | A one-line summary ("4 Libraries · 17 Permissions") that expands a detail listing each Library with its Permission names ("Access only" for none). An Admin reads "All Libraries"; a Guest's Libraries carry no Permission count |
| Card actions | For an Admin, a ⋯ context menu on each card: Edit, Set password, Deactivate or Reactivate, Delete; the last two are not on its own card. Delete asks in a small dialog that names how many assets move to the Admin. A non-Admin's cards have no menu |
| Add User modal | In the general modal host, 66rem wide, one column: identity, then access. Name, Username, Email and Password one per row; Password has Generate and Show buttons. The footer stays visible while the body scrolls. Editing reuses the form, without the password, titled by the stored name. It opens with the first type the creator may create other than Admin and, where the creator may give it, access to the Library it was opened from with the preset |
| Edit conflicts | The edit form carries a fingerprint of the account and its grants (`UserService.editState`); a save over an account changed since it was opened, by another Admin or by a new Library's grant, is refused with `ACCOUNT_CHANGED`, compared under the account's row lock |
| Account and Library IDs | A UUID, else a 400 (`UserActionController.uuid`) |
| Account type in the modal | A row of cards, each with a one-line description of the type, limited to the types the creator may create |
| Admin-only section of the modal | Inline, for an Admin creator: Create Libraries, and the new-Library grant as a switch followed by its Permission chips |
| Permission matrix | A checkbox grid under a sticky header: one row per Library with an access checkbox before its name, one column per Permission in the order of the Goals, 4.4rem wide, headers spelled out and wrapped. A row has an All toggle; a column header toggles its Permission across the chosen Libraries. Checking a Permission turns the Library's access on; turning access on pre-checks Import and Curate own for a User. Once as many Libraries are checked as an account may hold, the unchecked ones are disabled. A Guest gets the access column alone; an Admin a notice in place of the matrix. A Library or Permission above a non-Admin creator's ceiling is not rendered at all |
| Nav | Triage and Trash for a member, Import for the holder of Import, Users for an account creator, New library for an Admin or the flag's holder, Settings for an Admin, and an account menu (its trigger named after the username) with Change password and Log out |
| Styles | The existing `core.css` tokens and components: action buttons, toggle group, switch, context menu, the modal hosts. No new color. The page's sizes are tokens in `core.css`; the card, matrix and modal layout styles live in `static/css/pages/users.css`, in the `app.css` bundle |
| Type change | An Admin, any direction. To Admin: the grant rows go. To Guest: access stays, every Permission clears. From Admin: the Library matrix is filled in |
| Audit | An append-only `audit_event` table written from the service layer, in the transaction of the change: `AccountCreated` (the setup Admin's included), `AccountEdited`, `PermissionsChanged` (an Admin's edit, and each new-Library grant), `AccountTypeChanged`, `AccountDeactivated`, `AccountReactivated`, `AccountDeleted`, `PasswordSet`, `PasswordChangeFailed`, `LoginSucceeded` and `LoginFailed`, each with the client IP where a request has one. Actor and target are snapshots (`AccountSnapshot`), since accounts are deleted. No screen. Each event is logged at INFO after its transaction commits (`TransactionManager.afterCommit`) |
| Import status WebSocket | Checks the login itself (`decorators.authenticate`, since no decorator wraps a WebSocket), refuses a foreign `Origin` (403, `decorators.isSameOriginOrNone`), no login (401) and a Guest (403). The account comes from the token. Its sockets close when its versions change (`ImportStatusWsActor.CloseAllFor`): 1013 on a bump, which the client reconnects on, and 1008 on a revocation, which it does not. The Import page reconnects after its own password change and stops after 5 failed reconnects with a notice (`js/import-status.js`) |
| Failed requests | The client sends any 401 to the login (`js/common/session.js`), from htmx and from the axios client. `js/common/request-failure.js` shows the server's plain-text reason for a 403 or a 409, and the axios client shows a JSON refusal's `error` |
| Dev user | Only under ENV=dev (`Environment.devSetting`); `dev.user` is a username or an email. The password is checked until it first succeeds, then the claims are built from the database on each request; a failed dev login is not retried for 30 seconds |

## Constraints

- A read or write by ID is scoped to the Library in the request path, and another Library's ID answers 404, never 403. A
  statement that reaches a Library's table without its Library is one of the three the Decisions table names.
- Every route has a row in `RouteTableTests` saying who may call it, which must be what its decorators declare, and reads
  each ID field of its body through `Api.Field`. A route that changes something in a Library declares a gate past the login.
- A concurrency guard (the active-Admin lock, the grant bound, the lock order of account writes, the login's password check)
  is proved on PostgreSQL, whose writers run at once; SQLite's one write connection serializes them.
- An Admin's account write and the create of a Library take their row locks in one order: the active Admins' first, in ID
  order, then the accounts they write.
- Schema changes go into `all.sql` (both engines) only, with no versioned migration and no `schemaVersion` bump.
- A token fits the session cookie, 4096 bytes with its name and attributes: the account rules and `MAX_GRANTS_PER_ACCOUNT`
  bound it.
- Login writes (last login, the audit event) run in their own short transaction after the read-only password check, so a
  login does not hold the SQLite write connection through bcrypt.
- `CONTEXT.md` defines Admin, Guest, Permission and Organize; user-facing words follow it.

## Changes

### A. Schema and models

1. **`account` columns.** `username`, `email_key` and `username_key` (each `TEXT NOT NULL` under a unique index, `account_01`
   and `account_02`), `is_active`, `can_create_libraries`, `grants_new_libraries`, `new_library_permissions` (`CHECK >= 0`),
   `created_by_account_id` (`REFERENCES account (id) ON DELETE SET NULL`) and `last_login_at`, in both `all.sql` files,
   `AccountRow`, `FieldConst.User`, `User` and `UserDao`. `User` derives both keys with `NameKey.of`; `User.valid` and
   `User.validUsername` hold the account rules (`Api.Constraints.MIN_`/`MAX_USERNAME_LENGTH`, `Validators.isEmail`).
   `UserDao` adds the account with its keys, reads by key with the hash (`getWithPasswordHash`), locks by ID
   (`lockWithPasswordHash`, `lockById`, `lockAsImporter`, `lockActiveAdmins`, `lockGrantees`), lists (`getAll`,
   `getCreatedBy`) and writes (`update`, `setActive`, `setPasswordHash`, `recordLogin`).
2. **`repository_grant`.** `(account_id, repository_id, permissions INT)`, the pair the primary key, cascading from both
   parents, with `repository_grant_01` on `repository_id`. `Permission` (nine cases, a stable bit and a label each),
   `Permissions` (the mask, refusing an unknown bit), `RepositoryGrant`, and `RepositoryGrantDao` (`setGrant` as an upsert,
   `deleteByAccount`, `getByAccount`, `getByAccounts`, `getGrant`), which opts out of scoping.
3. **No Library owner.** `repository.owner_account_id`, `Repository.ownerAccountId`, `RepositoryRow.ownerAccountId`,
   `FieldConst.Repository.OWNER_ACCOUNT_ID` and `RepositoryDao.getOwnedBy` are gone; `addRepository` takes an optional
   `creatorId`. `PipelineContext.account` is optional, beside the account's `permissions`; `PipelineUtils.withContext` binds
   both, and none without an account. `requeuePurgePending` queues with no account, and the import sink notifies only when
   there is one.
4. **`asset.owner_user_id` and `asset.original_owner`.** `owner_user_id NOT NULL REFERENCES account (id)` on both engines and
   the JSON column (`STORAGE MAIN` on PostgreSQL), with the indexes `asset_owner` and `asset_importer`; `Asset.ownerUserId`,
   `Asset.originalOwner` (`AccountSnapshot`) and `Asset.toBrowserJson`. `LibraryService.stagedFileToAsset` writes both;
   `persistAndIndex` builds the record again from `UserService.lockImporter`. `BaseDao.jsonText` and `forKeyShare` are the
   engine spellings. `SearchPlans`' hand-written insert names the column and an account that exists.
5. **`audit_event`.** The table (`id`, `event`, `actor`, `target`, `detail`, `client_ip`, `created_at`) with `audit_event_01`
   on `created_at`, `AuditEvent` and `AuditEventType` (stored by `dbValue`), `AccountSnapshot`, `AuditEventDao`, which opts
   out of scoping, and `AuditService.record`. `TransactionManager.afterCommit` runs an action once the outermost write
   transaction has committed, dropping it on a rollback.

### B. Login and the token

6. **Login by username or email.** `loginAndSetUser(login, password, clientIp)` as **How it works** says, answering the
   account, the token and its expiry. `SessionController.doLogin` passes the client IP and logs a failure with the login made
   printable; `authCookie` and `clearedAuthCookie` are the one place the session cookie is built. The setup form and
   `initializeSystem` take a username (`Api.Field.Setup.ADMIN_USERNAME`) and record the setup Admin's `AccountCreated`; the
   login form's label reads "Email or username".
7. **Claims.** `TokenClaims` and `PasetoService.createToken`/`readToken` as **How it works** says; `MAX_TOKEN_BYTES` and the
   ERROR past it. `RepositoryService.MAX_GRANTS_PER_ACCOUNT` bounds the grants. `RequestContext.permissions`, `grants`,
   `holds`, `holdsAnywhere`, `createsAccounts`, `isMember`, `isGuest`, `recyclesIn` and `restores` read them.
8. **Account versions.** `AccountVersions` with `snapshot`, `bump`, `revoke` and `onChange`; `UserService.checkToken` and
   `TokenCheck` (`Current`, `Renewed`, `Refused(clearsCookie)`); `decorators.authenticate`, `Login`, `NoLogin` and
   `renewedCookies`. `requireLogin` sets a renewed cookie unless the answer sets the session cookie, and answers an htmx
   request with no login with a 401 and `HX-Redirect`. `/` authenticates the same way. The dev user's credentials are read
   once through `Environment.devSetting` (`UserService.devCredentials`), with a 30-second wait after a failed dev login.
9. **Library access by grant.** `requireLogin` admits an Admin to every Library and anyone else to a Library its token holds
   a grant in, else 404. `RepositoryService.getAccessibleTo` (`RepositoryDao.getAll`, `getGrantedTo`) feeds the nav's Library
   menu, `landingRepositoryOf` and the Users page.
10. **Import status behind login.** `ImportController.pipelineStatus` checks the `Origin`, the login and the account type,
    and the template connects to `/import/status` with no query. `ImportStatusWsActor.CloseAllFor` closes an account's
    sockets on a version change (`Altitude` registers it with `onChange`). `js/import-status.js` stops reconnecting after
    `MAX_RECONNECTS` (5) and shows `#importStatusLost`; `js/listeners/account.js` reconnects the page's sockets after a
    password change. Its row in `RouteTableTests` checks the login in the route.

### C. Enforcement

11. **Gate decorators.** `requirePermission(anyOf*)`, `requireMember`, `requireAccountCreator` and `requireLibraryCreator`
    beside `requireAdmin`, all through `allowedOnly` and `forbidden`, with the reasons in `Const.Msg.Err.FORBIDDEN_*`.
    `maySeeView`, `refusedView`, `maySeeAsset` and `visibleOnly` hold a Guest to Browse on the routes that take the view or
    an asset ID.
12. **Routes declare their Permission.** Import: the Import page, upload and cancel. Curate own or Curate all: Curation's
    shell, queue, sequence, selection, count, resolve and reopen. Organize: `api/asset` move; the folder, album and Location
    dialogs, writes and member changes; geocoding; People cover, merge, hide, show and delete; merging a Person from a Face.
    Edit Metadata: a Face's Person, its name, a Face's removal, the assignable People, a Person's name edit and rename.
    Recycle or a Curate Permission: `api/asset` recycle and restore. Purge: both purge routes. `requireMember`: the Triage
    partials. `requireLibraryCreator`: the New library dialog and its create. `requireAccountCreator`: the Users page, its
    cards, Add User and its create. `requireAdmin`: Settings, and an account's edit, password, deactivate, reactivate and
    delete.
13. **Asset-dependent rules in the services.** `AssetRules.requireCurate`, `requireRecycle` and `curatedOwner`.
    `LibraryService.recycleAssets` (and so a folder delete), `restoreRecycledAssets` and a move out of the Trash check
    recycling; `CurationService.resolve` and `reopen` read every ID through `requireKnown` before checking the Picks and the
    recycled frames; the queue, the sets, a selection and `curatableFrameCounts` keep to the account's own frames under
    Curate own (`AssetDao` takes an optional `owner`, and `countOwnedSequenceFrames`). `ForbiddenException` is a 403 in
    `answering` and `answeringJson`.
14. **Permissions in the route table.** Each row of `RouteTableTests` says who may call its route (`Access`: public, login,
    login checked in the route, member, holder of any of a set of Permissions, account creator, Library creator, Admin), and
    must equal what the route's decorators declare; a route that changes a Library and declares no gate fails. The table's
    tests: every route is listed with its IDs probed; every non-public route refuses an anonymous request; every route
    answers 403 exactly the accounts its row refuses (an Admin, a Guest, Users holding none, all, all with the flag, all but
    the row's, and each of the row's alone); an account with no grant in a Library gets 404 on every route of it; another
    Library's IDs are answered as unknown ones, for a User holding every Permission in both Libraries.
15. **Controls follow Permissions.** The nav (Triage, Import, Trash, Users, New library, Settings, the account menu), the
    batch bar (Move and the album and Location buttons with Organize, Recycle by `recyclesIn`, Curate Selected with a Curate
    Permission and, outside Triage, Recycle, Purge, Restore by `restores`), the Trash's Purge all, Triage's Curate row, the
    grouped grid's Curate button, the Detail panel's Curate link and People section, the People list's cover and actions, the
    Person name editor, the View settings dialog's triaged switch, the explorer's folder, album and Location menus (folder
    Delete with Organize when the folder is empty, with Recycle as well when it holds assets) and the drags. The main page's
    body carries `data-app-permissions` and `data-app-recycles` for the scripts (`context.holds`, `context.recyclesHere`).
    `js/common/session.js` sends any 401 to the login; `js/common/request-failure.js` and `http/client.js` show the server's
    reason.

### D. Account management

16. **`UserService` writes.** `create` (locks the creator and refuses an inactive one; the ceiling for a non-Admin; the
    account held to its type and rules; its grants through `replaceGrants`), `update` (the active Admins locked first and the
    actor one of them; the edit-state check; the type rule; the importer records rewritten on a change of name, username or
    email; one event per kind of change), `deactivate` (revokes) and `reactivate` (bumps), `delete` (the reassignment;
    bumps), `setPassword` (the active Admins locked first; revokes), `changeOwnPassword` (the current password checked
    read-only and again under the row lock; revokes), `lockImporter`, `countOwnedAssets`, `openForEdit`, `accountsVisibleTo`
    and `fieldErrors`. A unique violation of a key is the field's error. The three instance-wide `AssetDao` statements serve
    them.
17. **New-Library grants.** `addRepository(name, fileStoreType, creatorId)` locks the active Admins, then the grantees in ID
    order (`lockGrantees`), grants each switch-on account its new-Library Permissions (skipping one at its bound with a WARN)
    and a non-Admin creator every Permission (failing the add at its bound), records each grant and restores the caller's
    Library context on a refusal. `setGrant`, `replaceGrants` and `getGrants`.
18. **Users page.** `/users/r/:repoId` (`UsersController`) and its nav entry, for an account creator: the header with the
    filter box and Add User, the three tabs with counts and the server's start tab, and the card grid (`htmx/user_accounts`,
    reloaded from `/htmx/users/r/:repoId/accounts` after each change), as the Decisions table lays them out; every account
    for an Admin, and for anyone else the non-Admin accounts it created, read-only, with the Libraries it sees. The filter
    runs in the page (`usersPage`, `partialMatchKey` from `js/common/name-key.js`). `js/users-page.js` starts the page;
    `pages/users.css` and the size tokens join the `app.css` bundle.
19. **Add User modal.** `htmx/account_form` and `account-form.js`: identity fields with the password's Generate and Show
    (`includes/password_field`, `password-field.js`), the account type cards, the Permission matrix limited to the creator's
    ceiling (`UserActionController.Ceiling`) and to the grant bound, and for an Admin the Create Libraries and new-Library
    grant section. The matrix's toggles run in the page. Every field the rules refuse comes back in the form with the values
    submitted; anything above the ceiling is a 403.
20. **Edit, deactivate, delete, set password.** For an Admin, from a card's ⋯ menu (`includes/menu_ctrl`): the same form for
    editing, carrying `openedState`; Deactivate or Reactivate as a menu entry; Set password in a popover dialog
    (`htmx/set_password_dialog`), which renews the Admin's own session when it sets its own; and a confirm dialog for Delete
    (`htmx/delete_account_dialog`) that names how many assets move to the Admin. Account IDs are UUIDs, else 400.
21. **Change own password.** The nav's account menu opens a popover dialog (`htmx/own_password_dialog`,
    `OwnPasswordController`, under `/htmx/account`): current password, new password twice. The session that changed it gets a
    new cookie, and the page confirms it and reconnects its sockets.

### E. Tests' fixtures and docs

22. **Fixtures.** `TestContext.makeUser` gives an email with an "@" and a username; `persistUser` takes an optional username;
    `persistRepository()` takes no owner and grants nobody; `grant(account, repository, permissions)` and `grantOf(account,
    repositoryId)`. `ControllerTestCore.login()` grants the context account every Permission in the first Library (none for a
    Guest); `loginAs(accountType, permissions)` requires the Permissions, all for an Admin and none for a Guest;
    `cookiesOf(host, account)`; `addLibrary(name, account)` grants by type. `IntegrationTestCore` turns every account's
    new-Library switch off before each test and acts with every Permission.
23. **Docs.** `altitude/AGENTS.md` (**Accounts and access**: the account, Permissions and grants, the route gates, the asset
    rules, login and the token, account versions, account management, new-Library grants, the Users page, the audit log;
    **Libraries** without an owner; **Repository scoping** with the six DAOs that opt out and the three instance-wide
    `AssetDao` statements; `ForbiddenException` in `answering` and `answeringJson`; **Names** with `email_key` and
    `username_key`; the dev user), `altitude/views/AGENTS.md` (**Controls follow Permissions**, **Users page**),
    `docs/READINESS.md` (Login and sessions, Permissions, User management, the accepted exposure) and
    `docs/test-coverage.md`.

## Verification

- `make test` passes on all four bundles: unit 290, controllers 262, SQLite 689, PostgreSQL 688.
- **Token and login** (`LoginTokenTests`, `UserServiceTests`, `LoginControllerTests`, `SessionControllerTests`,
  `EnvironmentTests`): the claims read back; the gate reads the token, not the database; a stale token is rebuilt keeping its
  expiry, and refused when its account is deactivated or gone or it came as a Bearer token; a revoked one is refused, and a
  rebuild at a snapshot taken before a revocation is refused once it lands; a forged or tampered token is refused; the grant
  bound refuses with `TOO_MANY_GRANTS`, a token at the bound fits the cookie and one past `MAX_TOKEN_BYTES` logs an ERROR;
  logins by username or email in any case or form, a deactivated account and a control character answer as a wrong password,
  with the audit events and the IP; the dev user works only under ENV=dev and waits after a failure; the cookie is replaced
  on a change, cleared only when the token will never be good again, and an htmx request with no login gets the 401 with
  `HX-Redirect`.
- **Account management** (`AccountManagementTests`, `AuditServiceTests`, `RepositoryServiceTests`): create, update, the
  account rules and taken keys on their fields, the ceiling, the type rules, the importer-record rewrite in every Library
  touching no other account's records, deactivate and reactivate, delete moving the assets and taking the grants, the
  edit-state conflict (another Admin's edit, a new Library's grant), a deactivated creator, the new-Library grant and its
  events, passwords set and changed with their sessions refused, and the Users page's account list; events committed are
  logged once and rolled-back ones never.
- **Asset rules** (`AssetPermissionTests`, `AssetImportServiceTests`, `SearchResultsControllerTests`): recycle, restore and
  folder delete by Permission; another Library's asset not found whatever is held; Curate own over its own frames only, in
  the queue, the sets, a Sequence's completeness and the grouped grid's Curate; foreign or unknown IDs not found beside a
  refused frame; the importer record built at insert, and a file of a gone or deactivated account failed.
- **Routes and controls** (`RouteTableTests`, `PermissionControllerTests`, `AssetActionControllerTests`,
  `IndexControllerTests`, `ContentViewControllerTests`, `LibraryActionControllerTests`): every route's gate as the table
  says, one 403 in JSON and in plain text, a Guest refused Triage and the Trash and answered a triaged or recycled asset as
  unknown, the controls of the Permissions held and no others, the asset JSON without the owner and the importer record,
  Library access by grant, and the New library dialog for the flag's holder and at the bound.
- **Users page** (`UsersControllerTests`, `SetupFormControllerTests`): the cards for an Admin and for a creator, the start
  tab, the nav entry, the form within the ceiling and 403 above it, field errors with the values submitted, the Admin's menu
  actions, own and Admin-set passwords renewing the session, a change reaching an open session, IDs that are no UUID a 400,
  and the setup username.
- **Import status** (`ImportControllerTests`): the socket opens for a login and refuses no login, a Guest and a foreign
  `Origin`; an account's sockets close on a bump and on a revocation; the Import page connects naming no account.
- **PostgreSQL concurrency proofs**, each racing two transactions: two Admins deactivating, demoting or deleting each other,
  and a demotion against a delete, leave one active Admin (`AccountManagementTests`); two Libraries created at once granting
  each other's creator, and a Library created while an Admin edits an account it grants, both commit in one lock order
  (`RepositoryServiceTests`); two grants racing at the bound leave the account at the bound (`LoginTokenTests`); a login
  whose password changes while it waits fails, and one waits only after its password check (`UserServiceTests`); two adds of
  one username leave one account (`UserServiceTests`).
- The dev database is recreated from `all.sql` before the dev server runs the change.
