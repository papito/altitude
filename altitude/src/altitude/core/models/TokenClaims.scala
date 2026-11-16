package altitude.core.models

import java.time.Instant

object TokenClaims:

  /**
   * The account is kept to what a token carries, so claims built from the database equal those read back from the token they
   * mint. An Admin carries no grants: it holds every Permission by type.
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
 * What a login token says, and all a request is authorized by. `grants` is keyed by Library ID; `version` is the account's
 * version when the token was minted (`AccountVersions`).
 */
final case class TokenClaims(account: User, grants: Map[String, Permissions], version: Long, expiresAt: Instant):

  def permissionsIn(repositoryId: String): Option[Permissions] =
    if account.accountType == AccountType.Admin then Some(Permissions.all) else grants.get(repositoryId)
