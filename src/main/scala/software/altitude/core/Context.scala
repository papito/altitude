package software.altitude.core

import software.altitude.core.models.Repository
import software.altitude.core.models.User


object Context {
  val EMPTY = new Context(repo = null, user = null)
}
/**
 * The Context is the object that is implicitly passed from the service layer
 * (or test layer) to other services and ultimately the data access layer,
 * to keep track of what repository and which user we are operating on.
 */
class Context(val repo: Repository, val user: User)
