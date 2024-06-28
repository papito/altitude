package sofware.altitude.test

import org.scalatra.ScalatraServlet
import software.altitude.core.AltitudeServletContext
import software.altitude.core.models.AccountType
import software.altitude.core.models.User

class TestingController extends ScalatraServlet {

  put("/user") {
    contentType = "text/html"

    val userId = params.getOrElse("userId", throw new IllegalArgumentException("userId is required"))
    val userEmail = params.getOrElse("userEmail", throw new IllegalArgumentException("userEmail is required"))

     AltitudeServletContext.app.loggedInTestUser = Some(
      User(Some(userId),
        email = userEmail,
        accountType = AccountType.User)
    )

    "OK"
  }

}
