package altitude.test

import altitude.core.Altitude
import altitude.core.Const as C
import altitude.core.models.AccountType
import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.AssetWithData
import altitude.core.models.Face
import altitude.core.models.FaceImages
import altitude.core.models.Folder
import altitude.core.models.Person
import altitude.core.models.Repository
import altitude.core.models.User
import altitude.core.models.UserMetadata
import altitude.core.util.Util
import altitude.test.IntegrationTestUtil.generateRandomImagBytesBgr
import altitude.test.IntegrationTestUtil.generateRandomImagBytesGray
import java.lang.Thread.sleep

import scala.util.Random

object TestContext {
  val ASSET_SIZE = 652084
}

class TestContext(val testApp: Altitude) {
  var users: List[User] = List()
  var repositories: List[Repository] = List()
  var assets: List[Asset] = List()

  def makeUser(): User = User(
    email = Util.randomStr(),
    name = Util.randomStr(),
    accountType = AccountType.User
  )

  def makeAdminUser(): User = User(
    email = Util.randomStr(),
    name = Util.randomStr(),
    accountType = AccountType.Admin
  )

  def persistUser(user: Option[User] = None, password: String = "password"): User = {
    val userModel = user.getOrElse(makeUser())

    val persistedUser: User = testApp.service.user.add(userModel, password = password)
    users = users ::: persistedUser :: Nil

    // if this is the only (or the first user), set current request context
    if (users.length == 1) {
      testApp.service.user.switchContextToUser(persistedUser)
    }

    persistedUser
  }

  def persistRepository(user: Option[User] = None): Repository = {
    if (user.isEmpty && users.length > 1) {
      throw new RuntimeException(
        "Cannot use existing user when there are multiple test context users - must supply a user explicitly")
    }

    if (user.isEmpty && users.isEmpty) {
      persistUser(None)
    }

    // use supplied user, then existing single user, then new user
    val persistedUser = user.getOrElse(users.headOption.getOrElse(makeUser()))

    val persistedRepo: Repository = testApp.service.repository.addRepository(
      name = "Test Repository 1",
      fileStoreType = C.StorageEngineName.FS,
      owner = persistedUser)
    repositories = repositories ::: persistedRepo :: Nil

    // if this is the only (or the first repo), set current request context
    if (repositories.length == 1) {
      testApp.service.repository.switchContextToRepository(persistedRepo)
      testApp.service.user.switchContextToUser(persistedUser)
    }

    persistedRepo
  }

  def makeAsset(
      repository: Option[Repository] = None,
      filename: String = Util.randomStr(50),
      user: Option[User] = None,
      folder: Option[Folder] = None,
      userMetadata: UserMetadata = UserMetadata(),
      isTriaged: Boolean = false,
      isRecycled: Boolean = false): Asset = {
    if (repository.isEmpty && repositories.isEmpty) {
      throw new RuntimeException("Cannot make an asset without a repository previously created")
    }

    if (repository.isEmpty && repositories.length > 1) {
      throw new RuntimeException("Cannot make an asset when there are multiple test context repositories. Specify one explicitly")
    }

    val currentRepo = repository.getOrElse(repositories.headOption.get)
    val folderId = if (folder.isDefined) folder.get.persistedId else currentRepo.rootFolderId

    val currentUser = user.getOrElse(this.user)

    Asset(
      userId = currentUser.persistedId,
      folderId = folderId,
      assetType = new AssetType(mediaType = "image", mediaSubtype = "png", mime = "image/png"),
      fileName = filename,
      checksum = Random.nextInt(500000),
      userMetadata = userMetadata,
      sizeBytes = TestContext.ASSET_SIZE,
      isTriaged = isTriaged,
      isRecycled = isRecycled
    )
  }

  def makeAssetWithData(asset: Option[Asset] = None): AssetWithData = AssetWithData(
    asset = asset.getOrElse(makeAsset()),
    data = generateRandomImagBytesBgr(dimensions = 150)
  )

  def persistAsset(
      repository: Option[Repository] = None,
      user: Option[User] = None,
      folder: Option[Folder] = None,
      metadata: UserMetadata = UserMetadata(),
      isTriaged: Boolean = false,
      isRecycled: Boolean = false): Asset = {

    if (repository.isEmpty && repositories.length > 1) {
      throw new RuntimeException(
        "Cannot use existing repository when there are  multiple test context repositories - must supply a repository explicitly")
    }

    val persistedRepository = repository.getOrElse(repositories.headOption.getOrElse(persistRepository(user = user)))

    val asset = makeAsset(
      repository = Some(persistedRepository),
      filename = Util.randomStr(50),
      user = user,
      folder = folder,
      userMetadata = metadata,
      isTriaged = isTriaged,
      isRecycled = isRecycled
    )

    val dataAsset = makeAssetWithData(Some(asset))

    val persistedAsset: Asset = testApp.service.library.addAsset(dataAsset)

    assets = assets ::: persistedAsset :: Nil

    persistedAsset
  }

  def addTestFacesAndAssets(people: List[Person], assetCount: Int): Unit = {
    require(people.count(_.id.isEmpty) == 0, "Person must have an ID for a mock face to be added")

    val randomGrImage = generateRandomImagBytesGray()

    for (idx <- 1 to assetCount) {
      val asset: Asset = persistAsset()

      people.foreach {
        person =>
          val face = Face(
            id = Some(Util.randomStr(32)),
            x1 = Random.nextInt(100) + 1,
            y1 = Random.nextInt(100) + 1,
            width = Random.nextInt(100) + 1,
            height = Random.nextInt(100) + 1,
            assetId = Some(asset.persistedId),
            personId = Some(person.persistedId),
            personLabel = Some(idx),
            detectionScore = Random.nextDouble(),
            embeddings = Array.fill(128)(Random.nextFloat()),
            features = Array.fill(128)(Random.nextFloat()),
            checksum = Random.nextInt(),
            alignedImageGs = randomGrImage
          )

          val persistedFace = testApp.service.person.addFace(face, asset, person)

          val faceImages = FaceImages(
            image = generateRandomImagBytesBgr(),
            alignedImageGs = randomGrImage,
            alignedImage = generateRandomImagBytesBgr(),
            displayImage = generateRandomImagBytesBgr()
          )
          testApp.service.fileStore.addFace(persistedFace, faceImages)
      }
    }
  }

  def addTestFacesAndAssets(person: Person, assetCount: Int = 1): Unit = {
    addTestFacesAndAssets(List(person), assetCount)
  }

  def user: User = {
    if (users.length > 1) {
      throw new RuntimeException("Cannot get a single user when there are multiple test context users")
    }
    users.head
  }

  def repository: Repository = {
    if (repositories.length > 1) {
      throw new RuntimeException("Cannot get a single repository when there are multiple test context repositories")
    }
    repositories.head
  }

}
