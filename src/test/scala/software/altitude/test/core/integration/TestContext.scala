package software.altitude.test.core.integration

import org.apache.commons.io.FileUtils
import software.altitude.core.Altitude
import software.altitude.core.models.AccountType
import software.altitude.core.models.Asset
import software.altitude.core.models.AssetType
import software.altitude.core.models.AssetWithData
import software.altitude.core.models.Face
import software.altitude.core.models.Folder
import software.altitude.core.models.Metadata
import software.altitude.core.models.Person
import software.altitude.core.models.Repository
import software.altitude.core.models.User
import software.altitude.core.util.Util
import software.altitude.core.{Const => C}
import software.altitude.test.IntegrationTestUtil.generateRandomImagBytesBgr
import software.altitude.test.IntegrationTestUtil.generateRandomImagBytesGray

import java.io.File
import scala.util.Random

class TestContext(val testApp: Altitude) {
  var users: List[User] = List()
  var repositories: List[Repository] = List()
  var assets: List[Asset] = List()

  def makeUser(): User = User(
    email = Util.randomStr(),
    name = Util.randomStr(),
    accountType = AccountType.User,
  )

  def makeAdminUser(): User = User(
    email = Util.randomStr(),
    name = Util.randomStr(),
    accountType = AccountType.Admin,
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

    testApp.service.user.setLastActiveRepoId(persistedUser, persistedRepo.persistedId)

    // if this is the only (or the first repo), set current request context
    if (repositories.length == 1) {
      testApp.service.repository.switchContextToRepository(persistedRepo)
      testApp.service.user.switchContextToUser(persistedUser)
    }

    persistedRepo
  }

  def makeAsset(repository: Option[Repository] = None,
                filename: String = Util.randomStr(50),
                resourcePath: Option[String] = None,
                user: Option[User] = None,
                folder: Option[Folder] = None,
                metadata: Metadata = Metadata()): Asset ={
    if (repository.isEmpty && repositories.isEmpty) {
      throw new RuntimeException("Cannot make an asset without a repository previously created")
    }

    if (repository.isEmpty && repositories.length > 1) {
      throw new RuntimeException(
        "Cannot make an asset when there are multiple test context repositories. Specify one explicitly")
    }

    val currentRepo = repository.getOrElse(repositories.headOption.get)
    val folderId = if (folder.isDefined) folder.get.persistedId else currentRepo.rootFolderId

    val currentUser = user.getOrElse(this.user)

    val path = getClass.getResource(resourcePath.getOrElse("/import/images/test_asset.png")).getPath
    val file  = new File(path)
    val data = FileUtils.readFileToByteArray(file)

    Asset(
      userId = currentUser.persistedId,
      folderId = folderId,
      assetType = new AssetType(
        mediaType = "mediaType",
        mediaSubtype = "mediaSubtype",
        mime = "mime"),
      fileName = filename,
      checksum = Random.nextInt(500000),
      metadata = metadata,
      sizeBytes = data.length)
  }

  def makeAssetWithData(repository: Option[Repository] = None,
                        filename: String = Util.randomStr(50),
                        resourcePath: Option[String] = None,
                        user: Option[User] = None,
                        folder: Option[Folder] = None,
                        metadata: Metadata = Metadata()): AssetWithData = {

    val asset = makeAsset(repository, filename, resourcePath, user, folder, metadata)
    val data = Random.nextBytes(100)
    AssetWithData(asset, data)
  }

  def persistAsset(asset: Option[Asset] = None,
                   repository: Option[Repository] = None,
                   resourcePath: Option[String] = None,
                   user: Option[User] = None,
                   folder: Option[Folder] = None,
                   metadata: Metadata = Metadata()): Asset = {

    if (repository.isEmpty && repositories.length > 1) {
      throw new RuntimeException(
        "Cannot use existing repository when there are  multiple test context repositories - must supply a repository explicitly")
    }

    val persistedRepository = repository.getOrElse(
      repositories.headOption.getOrElse(
        persistRepository(user=user)))

    val assetModel = asset.getOrElse(
      makeAsset(
        repository=Some(persistedRepository),
        folder=folder,
        resourcePath=resourcePath,
        metadata=metadata, user=user))

    val dataAsset = AssetWithData(
      asset = assetModel,
      data = Random.nextBytes(100))

    val persistedAsset: Asset = testApp.service.library.add(dataAsset)

    assets = assets ::: persistedAsset :: Nil

    persistedAsset
  }

  def addTestFaces(person: Person, count: Int = 1): Unit = {
    require(person.id.nonEmpty, "Person must have an ID for a mock face to be added")

    for (idx <- 1 to count) {
      val asset: Asset = persistAsset()
      val face = Face(id=Some(Util.randomStr(32)),
        x1 = Random.nextInt(100) + 1,
        y1 = Random.nextInt(100) + 1,
        width =Random.nextInt(100) + 1,
        height = Random.nextInt(100) + 1,
        assetId = Some(asset.persistedId),
        personId = Some(person.persistedId),
        personLabel=Some(idx),
        detectionScore = Random.nextDouble(),
        embeddings = Array.fill(128) { Random.nextFloat() },
        features = Array.fill(128) { Random.nextFloat() },
        image = generateRandomImagBytesBgr(),
        displayImage = generateRandomImagBytesBgr(),
        alignedImage = generateRandomImagBytesBgr(),
        alignedImageGs = generateRandomImagBytesGray())

      testApp.service.person.addFace(face, asset, person)
    }
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
