package software.altitude.core.service

import net.codingwell.scalaguice.InjectorExtensions._
import play.api.libs.json.JsObject
import software.altitude.core._
import software.altitude.core.dao.UserDao
import software.altitude.core.models.User
import software.altitude.core.transactions.AbstractTransactionManager
import software.altitude.core.transactions.TransactionId

class UserService(val app: Altitude) extends BaseService[User] {
  protected val dao: UserDao = app.injector.instance[UserDao]
  override protected val txManager: AbstractTransactionManager = app.injector.instance[AbstractTransactionManager]

  override def getById(id: String)(implicit ctx: Context, txId: TransactionId = new TransactionId): JsObject = {
    throw new NotImplementedError
  }

  def getUserById(id: String)(implicit txId: TransactionId = new TransactionId): User = {
    txManager.asReadOnly[JsObject] {
      implicit val context: Context = Context.EMPTY

      dao.getById(id) match {
        case Some(obj) => obj
        case None => throw NotFoundException(s"Cannot find ID '$id'")
      }
    }
  }

  def addUser(user: User)(implicit txId: TransactionId = new TransactionId): JsObject = {
    txManager.withTransaction[JsObject] {
      implicit val context: Context = Context.EMPTY
      super.add(user)
    }
  }
}
