package altitude.test

import altitude.test.IntegrationTestUtil.generateRandomImagBytesBgr
import altitude.test.IntegrationTestUtil.generateRandomImagBytesGray
import java.lang.Thread.sleep
import java.net.HttpCookie
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import org.apache.commons.dbutils.QueryRunner
import org.apache.commons.dbutils.handlers.MapListHandler

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Random

import altitude.core.Altitude
import altitude.core.Const as C
import altitude.core.RequestContext
import altitude.core.dao.sqlite.SqliteOverrides
import altitude.core.models.AccountSnapshot
import altitude.core.models.AccountType
import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.AssetWithData
import altitude.core.models.CaptureDateSource
import altitude.core.models.Face
import altitude.core.models.FaceImages
import altitude.core.models.Folder
import altitude.core.models.Histogram
import altitude.core.models.ImageQuality
import altitude.core.models.Permissions
import altitude.core.models.Person
import altitude.core.models.Repository
import altitude.core.models.User
import altitude.core.models.UserMetadata
import altitude.core.models.WatchFileState
import altitude.core.service.FaceDetectionService
import altitude.core.service.FaceRecognitionService
import altitude.core.util.Util

object TestContext {
  val ASSET_SIZE = 652084

  /**
   * Numbers the fixture Libraries of the test JVM, "Test Repository 1", "Test Repository 2", ...: a Library name is unique
   * instance-wide (`repository_01`), and an integration bundle's database keeps every test's Libraries
   */
  private val repositoryNumber = new AtomicInteger(0)

  def nextRepositoryName(): String = s"Test Repository ${repositoryNumber.incrementAndGet()}"
  val USER_PASSWORD = "password123"

  /** A checksum of the stored form, 64 lowercase hex characters of random bytes, distinct for every call */
  def randomChecksum: String = HexFormat.of().formatHex(Random.nextBytes(32))

  /** A reproducible unit vector; two seeds give vectors about a cosine distance of 1 apart */
  def unitVector(seed: Int): Array[Float] = {
    val random = new Random(seed)
    FaceRecognitionService.meanNormalized(
      Seq(Array.fill(FaceDetectionService.EMBEDDING_DIMENSIONS)(random.nextGaussian().toFloat)))
  }

  /**
   * A unit vector between two, `parts` of the first to one of the second. For two vectors a cosine distance of 1 apart, the
   * result is about 0.29 from the first at one part, 0.11 at two, 0.05 at three, 0.03 at four and 0.02 at five, so the mixes of
   * one pair keep their order by `parts` whatever the pair's own distance
   */
  def between(vector: Array[Float], other: Array[Float], parts: Int): Array[Float] =
    FaceRecognitionService.meanNormalized(Seq.fill(parts)(vector) :+ other)

  /** The tables that carry `repository_id`, whose rows only their own repository's context reaches */
  val SCOPED_TABLES: List[String] = List(
    "asset",
    "folder",
    "album",
    "album_asset",
    "location",
    "location_asset",
    "person",
    "face",
    "metadata_field",
    "metadata_parameter",
    "stats",
    "search_document",
    "import",
    "import_file",
    "watch_folder",
    "watch_folder_file"
  )
}

class TestContext(val testApp: Altitude) {
  var users: List[User] = List()
  var repositories: List[Repository] = List()
  var assets: List[Asset] = List()
  // to maintain login state for controllers
  var cookies: Map[String, HttpCookie] = Map.empty

  // An email address holds an "@", or a login with it is looked up as a username
  def makeUser(): User = User(
    email = s"${Util.randomStr()}@example.com",
    username = Util.randomStr(),
    name = Util.randomStr(),
    accountType = AccountType.User
  )

  def makeAdminUser(): User = makeUser().copy(accountType = AccountType.Admin)

  /** The account `user` describes (a User of random names when None), under `username` when one is given */
  def persistUser(
      user: Option[User] = None,
      password: String = TestContext.USER_PASSWORD,
      username: Option[String] = None): User = {
    val model = user.getOrElse(makeUser())
    val userModel = username.fold(model)(name => model.copy(username = name))

    val persistedUser: User = testApp.service.user.add(userModel, password = password)
    users = users ::: persistedUser :: Nil

    // if this is the only (or the first user), set current request context
    if (users.length == 1) {
      testApp.service.user.switchContextToUser(persistedUser)
    }

    persistedUser
  }

  /**
   * A Library, which has no owner and grants nobody access (see [[grant]]). The context gets an account first when it has none,
   * since every asset has an owner; the first Library becomes the context repository.
   */
  def persistRepository(): Repository = {
    if (users.isEmpty) {
      persistUser(None)
    }

    val persistedRepo: Repository =
      testApp.service.repository.addRepository(name = TestContext.nextRepositoryName(), fileStoreType = C.StorageEngineName.FS)
    repositories = repositories ::: persistedRepo :: Nil

    // if this is the only (or the first repo), set current request context
    if (repositories.length == 1) {
      testApp.service.repository.switchContextToRepository(persistedRepo)
    }

    persistedRepo
  }

  /** Gives a User or a Guest access to the Library with `permissions` (none for a Guest, who holds none) */
  def grant(account: User, repository: Repository, permissions: Permissions = Permissions.all): Unit =
    testApp.service.repository.setGrant(account.persistedId, repository.persistedId, permissions)

  /** The account's Permissions in the Library as stored, None when it holds no grant there: the grant table read through its DAO */
  def grantOf(account: User, repositoryId: String): Option[Permissions] =
    testApp.txManager.asReadOnly(testApp.DAO.repositoryGrant.getGrant(account.persistedId, repositoryId)).map(_.permissions)

  def makeAsset(
      repository: Option[Repository] = None,
      filename: String = Util.randomStr(50),
      user: Option[User] = None,
      folder: Option[Folder] = None,
      userMetadata: UserMetadata = UserMetadata(),
      isTriaged: Boolean = false,
      isRecycled: Boolean = false,
      originalCreatedAt: Option[LocalDateTime] = None,
      originalCreatedAtSource: Option[CaptureDateSource] = None): Asset = {
    if (repository.isEmpty && repositories.isEmpty) {
      throw new RuntimeException("Cannot make an asset without a repository previously created")
    }

    if (repository.isEmpty && repositories.length > 1) {
      throw new RuntimeException("Cannot make an asset when there are multiple test context repositories. Specify one explicitly")
    }

    val currentRepo = repository.getOrElse(repositories.headOption.get)
    val folderId =
      if (folder.isDefined) folder.get.persistedId
      else if (isTriaged) ""
      else currentRepo.rootFolderId

    val currentUser = user.getOrElse(this.user)

    Asset(
      ownerUserId = currentUser.persistedId,
      originalOwner = AccountSnapshot.of(currentUser),
      folderId = folderId,
      assetType = new AssetType(mediaType = "image", mediaSubtype = "png", mime = "image/png"),
      fileName = filename,
      checksum = TestContext.randomChecksum,
      userMetadata = userMetadata,
      sizeBytes = TestContext.ASSET_SIZE,
      isTriaged = isTriaged,
      isRecycled = isRecycled,
      originalCreatedAt = originalCreatedAt,
      originalCreatedAtSource = originalCreatedAtSource
    )
  }

  /** An asset over a staged random image, the way an upload hands one to the pipeline */
  def makeAssetWithData(asset: Option[Asset] = None): AssetWithData = AssetWithData(
    asset = asset.getOrElse(makeAsset()),
    path = testApp.service.staging.stage(generateRandomImagBytesBgr(dimensions = 150))
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

    val persistedRepository = repository.getOrElse(repositories.headOption.getOrElse(persistRepository()))

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

    val imported: Asset = testApp.service.library.addAsset(dataAsset)

    // The random image is undated and of one size, so in triage, in a suite that turns the Scene models on, the import may pair
    // it by likeness with an earlier one as it would the frames of one scene; with the models off it pairs by the hash alone, and
    // two random images' hashes never come near. A persisted asset is a plain fixture: the Sequence the import gave it is cleared
    // from every asset that took it, and a test that wants Sequences stamps them (`setAssetSequence`). An import that paired with
    // a stamped Sequence would wipe that stamp off every member with the clear, so it fails the test instead
    val persistedAsset = imported.sequenceId.fold(imported) {
      sequenceId =>
        if (stampedSequences.contains(sequenceId)) {
          throw new IllegalStateException(
            s"Fixture [${imported.persistedId}] paired with the stamped Sequence [$sequenceId]: persist the fixture before " +
              "the stamp, or give it a shape the import cannot pair")
        }
        testApp.txManager.withTransaction {
          new QueryRunner().update(
            RequestContext.getConn,
            "UPDATE asset SET sequence_id = NULL WHERE sequence_id = ?",
            sequenceId)
        }
        imported.copy(sequenceId = None)
    }

    assets = assets ::: persistedAsset :: Nil

    persistedAsset
  }

  /**
   * A fixture without the import pipeline: the asset persisted, indexed and completed (`persistAndIndex`, then `completeImport`),
   * and counted in the stats, as an import's database stages leave it. It has no file, Preview, faces, Image quality, perceptual
   * hash or Scene embeddings, so a test reading any of them persists its asset through the pipeline (`persistAsset`). With no
   * hash it is no frame, and the completion puts it in no Sequence; a test that wants one stamps it (`setAssetSequence`).
   */
  def insertAsset(asset: Asset = makeAsset()): Asset = {
    val completed = testApp.service.library.completeImport(testApp.service.library.persistAndIndex(asset))
    assets = assets ::: completed :: Nil
    completed
  }

  /** Rewrites an asset's capture time (camera wall-clock) and import time (an instant) in each engine's storage form */
  def setAssetDates(assetId: String, taken: Option[LocalDateTime], imported: OffsetDateTime): Unit = {
    val (takenValue, importedValue): (Any, Any) = testApp.dataSourceType match {
      case C.DbEngineName.SQLITE =>
        (
          taken.map(_.format(SqliteOverrides.DATETIME_FORMATTER)).orNull,
          imported.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime.format(SqliteOverrides.DATETIME_FORMATTER))
      case C.DbEngineName.POSTGRES => (taken.orNull, imported)
      case _ => throw new IllegalArgumentException("Unsupported data source type")
    }

    testApp.txManager.withTransaction {
      new QueryRunner().update(
        RequestContext.getConn,
        "UPDATE asset SET original_created_at = ?, created_at = ?, original_created_at_source = ? WHERE id = ?",
        takenValue.asInstanceOf[Object],
        importedValue.asInstanceOf[Object],
        taken.map(_ => "exif_original").orNull,
        assetId
      )
    }
  }

  /**
   * Lets the rest of the Watch folders' files pass without waiting for it: every file not read yet, of every Watch folder, is
   * remembered as first seen a minute ago, so the next scan may read it. What a scan does within the rest is
   * `WatchFolderServiceTests`' to wait for.
   */
  def letWatchedFilesRest(): Unit = {
    val aMinuteAgo = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)
    val firstSeen: Any = testApp.dataSourceType match {
      case C.DbEngineName.SQLITE => aMinuteAgo.toLocalDateTime.format(SqliteOverrides.DATETIME_FORMATTER)
      case C.DbEngineName.POSTGRES => aMinuteAgo
      case _ => throw new IllegalArgumentException("Unsupported data source type")
    }

    testApp.txManager.withTransaction {
      new QueryRunner().update(
        RequestContext.getConn,
        "UPDATE watch_folder_file SET last_seen_at = ? WHERE state = ?",
        firstSeen.asInstanceOf[Object],
        WatchFileState.Seen.dbValue): Unit
    }
  }

  /** The type of an asset, so a persisted image fixture can stand for a Video without a file to decode */
  def setAssetType(assetId: String, assetType: AssetType): Unit = {
    testApp.txManager.withTransaction {
      new QueryRunner().update(
        RequestContext.getConn,
        "UPDATE asset SET media_type = ?, media_subtype = ?, mime_type = ? WHERE id = ?",
        assetType.mediaType,
        assetType.mediaSubtype,
        assetType.mime,
        assetId
      )
    }
  }

  /** Coordinates are parsed on import only, so a fixture writes them directly, as an import of a geotagged file would have */
  def setAssetCoordinates(assetId: String, latitude: Double, longitude: Double): Unit = {
    testApp.txManager.withTransaction {
      new QueryRunner().update(
        RequestContext.getConn,
        "UPDATE asset SET latitude = ?, longitude = ? WHERE id = ?",
        latitude.asInstanceOf[Object],
        longitude.asInstanceOf[Object],
        assetId
      )
    }
  }

  /** The Sequences `setAssetSequence` stamped, which a fixture persisted afterwards must not pair with */
  private val stampedSequences = mutable.Set.empty[String]

  /**
   * Puts assets still in triage in a Sequence, as assignment would for a burst, whatever their files look like. A fixture
   * persisted after the stamp fails if the import pairs it with the Sequence, so stamps come after the fixtures
   */
  def setAssetSequence(assets: List[Asset], sequenceId: String): Unit = {
    stampedSequences += sequenceId
    testApp.txManager.withTransaction {
      testApp.DAO.asset.setSequence(assets.map(_.persistedId).toSet, sequenceId): Unit
    }
  }

  /** The metrics of an asset, or none, so a fixture decides what the measurement of its file would have found */
  def setAssetImageQuality(assetId: String, quality: Option[ImageQuality]): Unit = {
    testApp.txManager.withTransaction {
      new QueryRunner().update(
        RequestContext.getConn,
        "UPDATE asset SET sharpness = ?, luminance = ?, clipped_highlights = ?, crushed_shadows = ?, image_quality = ? " +
          "WHERE id = ?",
        quality.map(_.sharpness.asInstanceOf[Object]).orNull,
        quality.map(_.luminance.asInstanceOf[Object]).orNull,
        quality.map(_.clippedHighlights.asInstanceOf[Object]).orNull,
        quality.map(_.crushedShadows.asInstanceOf[Object]).orNull,
        quality.map(_.score.asInstanceOf[Object]).orNull,
        assetId
      )
    }
  }

  /** The Histogram of an asset, or none, as the measurement of its file would have stored it */
  def setAssetHistogram(assetId: String, histogram: Option[Histogram]): Unit = {
    testApp.txManager.withTransaction {
      new QueryRunner().update(
        RequestContext.getConn,
        "UPDATE asset SET histogram = ? WHERE id = ?",
        histogram.map(_.toBytes).orNull,
        assetId)
    }
  }

  /**
   * A Face of the person in the asset with the given embedding, so recognition can be exercised on known vectors; `withFiles`
   * writes its four images to the file store, as an import would, for a test of what becomes of them
   */
  def addTestFace(
      person: Person,
      asset: Asset,
      features: Array[Float],
      isEnrolled: Boolean = true,
      sharpness: Option[Double] = None,
      detectionScore: Double = 0.9,
      quality: Double = 20.0,
      x1: Int = 1,
      frameTimeMs: Option[Long] = None,
      withFiles: Boolean = false): Face = {
    val face = testApp.service.person.addFace(
      Face(
        x1 = x1,
        y1 = 1,
        width = 10,
        height = 10,
        detectionScore = detectionScore,
        checksum = TestContext.randomChecksum,
        features = features,
        quality = quality,
        isEnrolled = isEnrolled,
        frameTimeMs = frameTimeMs,
        sharpness = sharpness
      ),
      asset,
      person
    )
    if (withFiles) testApp.service.fileStore.addFace(face, randomFaceImages(generateRandomImagBytesGray()))
    face
  }

  private def randomFaceImages(alignedImageGs: Array[Byte]): FaceImages = FaceImages(
    image = generateRandomImagBytesBgr(),
    alignedImageGs = alignedImageGs,
    alignedImage = generateRandomImagBytesBgr(),
    displayImage = generateRandomImagBytesBgr()
  )

  def addTestFacesAndAssets(people: List[Person], assetCount: Int): Unit =
    addFacesOnNewAssets(people, assetCount, isTriaged = false)

  private def addFacesOnNewAssets(people: List[Person], assetCount: Int, isTriaged: Boolean): Unit = {
    require(people.count(_.id.isEmpty) == 0, "Person must have an ID for a mock face to be added")

    val randomGrImage = generateRandomImagBytesGray()

    for (idx <- 1 to assetCount) {
      val asset: Asset = persistAsset(isTriaged = isTriaged)

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
            features = Array.fill(FaceDetectionService.EMBEDDING_DIMENSIONS)(Random.nextFloat()),
            quality = 20.0,
            isEnrolled = true,
            checksum = TestContext.randomChecksum
          )

          val persistedFace = testApp.service.person.addFace(face, asset, person)
          testApp.service.fileStore.addFace(persistedFace, randomFaceImages(randomGrImage))
      }
    }
  }

  def addTestFacesAndAssets(person: Person, assetCount: Int = 1, isTriaged: Boolean = false): Unit = {
    addFacesOnNewAssets(List(person), assetCount, isTriaged)
  }

  /**
   * Every row of every scoped table in the repository, each as text, read by hand-written SQL that no scoping touches: two reads
   * that are equal saw no row of the repository change in between
   */
  def rowsOf(repository: Repository): Map[String, List[String]] =
    testApp.txManager.asReadOnly {
      TestContext.SCOPED_TABLES.map {
        table =>
          val read = new QueryRunner()
            .query(
              RequestContext.getConn,
              s"SELECT * FROM $table WHERE repository_id = ?",
              new MapListHandler(),
              repository.persistedId)
            .asScala
            .toList
          table -> read.map(row => asText(row.asScala.toMap)).sorted
      }.toMap
    }

  private def asText(row: Map[String, AnyRef]): String =
    row.toList
      .sortBy(_._1)
      .map {
        case (column, bytes: Array[Byte]) => s"$column=${bytes.toSeq}"
        case (column, value) => s"$column=$value"
      }
      .mkString(", ")

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
