package altitude.core.models

/**
 * An account's access to a Library, and its [[Permissions]] there: one `repository_grant` row. A Guest's holds none. An Admin
 * needs none: it reaches every Library by its type.
 */
final case class RepositoryGrant(accountId: String, repositoryId: String, permissions: Permissions)
  extends BaseModel
  with NoId
  with NoDates
