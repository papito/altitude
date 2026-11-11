package altitude.core.models

import java.time.Instant

object TokenClaims:

  /**
   * The claims of `account`, with `grants` (none for an Admin, who holds every Permission by type) at `version`. The account is
   * kept to what a token carries, so claims built from the database equal those read back from the token they mint.
   */
  def of(account: User, grants: Map[String, Permissions], version: Long, expiresAt: Instant): TokenClaims =
    TokenClaims(
      account = User(
        id = account.id,
        email = account.email,
        username = account.username,
        name = account.name,
        accountType = account.accountType,
        canCreateLibraries = account.canCreateLibraries
      ),
      grants = if account.accountType == AccountType.Admin then Map.empty else grants,
      version = version,
      expiresAt = expiresAt
    )

/**
 * What a login token says, and all a request is authorized by: the account (its ID, email, username, name, account type and
 * Create Libraries flag), its grants as a Permission bitmask per Library ID (none for an Admin), the account's version when the
 * token was minted (`AccountVersions`), and when the token expires
 */
final case class TokenClaims(account: User, grants: Map[String, Permissions], version: Long, expiresAt: Instant):

  /** The account's Permissions in the Library: every one for an Admin, its grant's for anyone else, None without a grant there */
  def permissionsIn(repositoryId: String): Option[Permissions] =
    if account.accountType == AccountType.Admin then Some(Permissions.all) else grants.get(repositoryId)
