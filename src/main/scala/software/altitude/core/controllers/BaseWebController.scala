package software.altitude.core.controllers

import org.scalatra.scalate.ScalateSupport
import software.altitude.core.models.{Repository, User}
import software.altitude.core.{Const, Environment, RequestContext}
import software.altitude.core.util.Query

class BaseWebController extends BaseController with ScalateSupport {
  before() {
    Environment.ENV match {
      case Environment.PROD =>

      case Environment.DEV =>
        val res = app.service.repository.query(new Query().add(Const.Repository.NAME -> "Personal"))
        RequestContext.repository.value = Some(res.records.head: Repository)
        logger.warn(s"Using hardcoded repository: ${RequestContext.repository.value.get.name}")

      case Environment.TEST =>
        logger.info("TEST AUTHENTICATION VIA HEADER")

        val testRepoId: String = request.getHeader(Const.Api.REPO_TEST_HEADER_ID)
        if (testRepoId != null) {
          val repo: Repository = app.service.repository.getById(testRepoId)
          RequestContext.repository.value = Some(repo)
        }

        val testUserId: String = request.getHeader(Const.Api.USER_TEST_HEADER_ID)
        if (testUserId != null) {
          val user: User = app.service.user.getById(testUserId)
          RequestContext.account.value = Some(user)
        }

        logger.info(RequestContext.account.value.toString)
      case _ => throw new RuntimeException("Unknown environment")
    }

  }
}
