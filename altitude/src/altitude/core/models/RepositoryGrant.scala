package altitude.core.models

/** An account's access to a Library and its [[Permissions]] there. A Guest's holds none; an Admin needs no grant at all. */
final case class RepositoryGrant(accountId: String, repositoryId: String, permissions: Permissions)
  extends BaseModel
  with NoId
  with NoDates
