package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestContext
import altitude.test.TestVideos
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.tagobjects.Slow

import scala.util.Random

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.SearchCursorException
import altitude.core.dao.sql.search.PostgresSearchDialect
import altitude.core.dao.sql.search.SearchQueries
import altitude.core.models.Asset
import altitude.core.models.Folder
import altitude.core.models.Person
import altitude.core.models.SceneEmbedding
import altitude.core.models.SceneEmbeddings
import altitude.core.models.SceneModel
import altitude.core.models.Settings
import altitude.core.util.DateRange
import altitude.core.util.DescribeRanking
import altitude.core.util.PeopleMatch
import altitude.core.util.SearchCursor
import altitude.core.util.SearchPage
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult
import altitude.core.util.SearchSort
import altitude.core.util.SortValue

object DescribeSearchTests {

  /** A Describe match threshold so low that every embedded still image clears it, whatever the description */
  private val EVERY_IMAGE = "0.000000000000001"

  private val PHOTOS = List("people/affleck.jpg", "people/damon.jpg", "people/bullock.jpg")
  private val CACTUS = "images/cactus.jpg"

  /**
   * The filters a description is searched within, in the repository view: outside the trash and, unless `triaged`, outside
   * triage. With none set, the description is searched alone.
   */
  private case class Filters(
      triaged: Boolean = false,
      text: Option[String] = None,
      folderIds: Set[String] = Set(),
      personIds: Set[String] = Set(),
      peopleMatch: PeopleMatch = PeopleMatch.All,
      locationIds: Set[String] = Set(),
      takenRange: Option[DateRange] = None,
      importedRange: Option[DateRange] = None) {

    /**
     * The search of these filters as a request asks for it, unresolved: the page of `pageSize` assets after `cursor`.
     * `exactLimit` is how many matching assets PostgreSQL ranks one by one before it ranks through its vector index.
     */
    def query(
        cursor: Option[SearchCursor] = None,
        pageSize: Int = 50,
        exactLimit: Int = Const.Search.DESCRIBE_EXACT_LIMIT): SearchQuery =
      new SearchQuery(
        params = Map[String, Any](FieldConst.Asset.IS_RECYCLED -> false) ++
          Option.unless(triaged)(FieldConst.Asset.IS_TRIAGED -> false),
        text = text,
        folderIds = folderIds,
        personIds = personIds,
        peopleMatch = peopleMatch,
        locationIds = locationIds,
        takenRange = takenRange,
        importedRange = importedRange,
        rpp = pageSize,
        cursor = cursor,
        describeExactLimit = exactLimit
      )
  }

  /** A still image of a test's library with the SigLIP 2 Scene embedding it is ranked by */
  private case class Photo(asset: Asset, embedding: SceneEmbedding) {
    def id: String = asset.persistedId
  }
}

/**
 * The Describe search: a description ranked against the SigLIP 2 Scene embeddings of the repository's still images, in the
 * database, on both engines. The fixtures are embedded by the real Scene models and the descriptions by the real text tower.
 */
@DoNotDiscover class DescribeSearchTests(override val testApp: Altitude) extends IntegrationTestCore with SearchPlans {
  import DescribeSearchTests.*

  override protected def usesSceneModels: Boolean = true

  override protected def usesDescribeModel: Boolean = true

  /** A fixture image imported through the whole pipeline, which leaves it in triage, as the import returned it */
  private def triaged(relPath: String): Asset =
    testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset(relPath))

  /** A fixture image imported and moved into `folder`, the repository's root folder when none is given, so out of triage */
  private def sorted(relPath: String, folder: Option[Folder] = None): Asset = {
    val asset = triaged(relPath)
    testApp.service.library
      .moveAssetsToFolder(Set(asset.persistedId), folder.fold(testContext.repository.rootFolderId)(_.persistedId))
    asset
  }

  /** An asset with no file whose SigLIP 2 Scene embedding is `embedding`: sorted into `folder`, or left in triage */
  private def embedded(
      embedding: SceneEmbedding,
      folder: Option[Folder] = None,
      filename: Option[String] = None,
      isTriaged: Boolean = false): Asset = {
    val asset = testContext.makeAsset(folder = folder, isTriaged = isTriaged)
    testContext.insertAsset(
      asset.copy(fileName = filename.getOrElse(asset.fileName), sceneEmbeddings = SceneEmbeddings(siglip2 = Some(embedding))))
  }

  /** A fixture image imported and sorted into `folder`, with the Scene embedding the import returned */
  private def imported(relPath: String, folder: Option[Folder] = None): Photo = {
    val asset = sorted(relPath, folder)
    Photo(asset, asset.sceneEmbeddings.siglip2.value)
  }

  /** An asset with no file whose Scene embedding is at `cosine` to the description's, random by `seed` */
  private def placed(
      description: String,
      cosine: Double,
      seed: Int,
      folder: Option[Folder] = None,
      filename: Option[String] = None,
      isTriaged: Boolean = false): Photo = {
    val embedding = TestContext.atCosine(embeddingOf(description), cosine, seed)
    Photo(embedded(embedding, folder, filename, isTriaged), embedding)
  }

  /** The photos' IDs in the order a description ranks them: the highest cosine of their Scene embedding to its own first */
  private def ranked(photos: List[Photo], description: String): List[String] = {
    val described = embeddingOf(description)
    photos.sortBy(photo => (-photo.embedding.similarity(described), photo.id)).map(_.id)
  }

  /** A reproducible unit vector of the model's dimensions */
  private def unitVector(seed: Int): SceneEmbedding = {
    val random = new Random(seed)
    val values = Array.fill(SceneModel.Siglip2.dimensions)(random.nextGaussian())
    val norm = math.sqrt(values.map(v => v * v).sum)
    SceneEmbedding(IArray.unsafeFromArray(values.map(v => (v / norm).toFloat)))
  }

  private def embeddingOf(description: String): SceneEmbedding = testApp.describeEmbedder.embed(description).value

  /** The cosine of an imported image's SigLIP 2 Scene embedding, as the import returned it, to a description's */
  private def cosine(asset: Asset, description: String): Double =
    asset.sceneEmbeddings.siglip2.value.similarity(embeddingOf(description))

  /** The probability, by the model, that an image at `cosine` to a description matches it: what the threshold is compared to */
  private def probabilityAt(cosine: Double): Double =
    1 / (1 + math.exp(-(cosine * SceneModel.SIGLIP2_LOGIT_SCALE + SceneModel.SIGLIP2_LOGIT_BIAS)))

  /** The probability, by the model, that the image matches the description */
  private def probability(asset: Asset, description: String): Double = probabilityAt(cosine(asset, description))

  /** A probability as a Setting's text: a plain decimal */
  private def plain(probability: Double): String = BigDecimal(probability).bigDecimal.toPlainString

  private def describe(
      description: String,
      filters: Filters = Filters(),
      cursor: Option[SearchCursor] = None,
      pageSize: Int = 50,
      exactLimit: Int = Const.Search.DESCRIBE_EXACT_LIMIT): SearchResult =
    testApp.service.search.describe(description, filters.query(cursor, pageSize, exactLimit))

  /**
   * A page read from the DAO, with the cutoff and the limit given, inside a transaction set up for a search of that limit, where
   * the filters' search is resolved as the service resolves one
   */
  private def page(
      embedding: SceneEmbedding,
      minCosine: Double = -1,
      limit: Int = 1000,
      filters: Filters = Filters(),
      cursor: Option[SearchCursor] = None,
      pageSize: Int = 50,
      exactLimit: Int = Const.Search.DESCRIBE_EXACT_LIMIT): SearchPage =
    testApp.txManager.withVectorSearch(limit) {
      val query = testApp.service.search.withResolvedScope(filters.query(cursor, pageSize, exactLimit))
      testApp.DAO.search.describe(query, DescribeRanking(embedding, minCosine, limit))
    }

  private def ids(result: SearchResult): List[String] = result.records.map(_.persistedId)

  /** The assets the filters' own search finds, with no description */
  private def matchedBy(filters: Filters): Set[String] =
    testApp.service.library.search(filters.query(pageSize = 500)).records.map(_.persistedId).toSet

  /**
   * Every page of a description's results within the filters, `pageSize` assets at a time, each continued from the cursor of the
   * page before it
   */
  private def pages(description: String, pageSize: Int, filters: Filters = Filters()): List[SearchResult] = {
    val first = describe(description, filters, pageSize = pageSize)
    first :: List.unfold(first.nextCursor) {
      _.map {
        cursor =>
          val next = describe(description, filters, cursor = Some(cursor), pageSize = pageSize)
          (next, next.nextCursor)
      }
    }
  }

  test("A description ranks the still images that look most like it first") {

    /**
     * Setup:
     *
     * A repository with a photo of a cactus and three portraits of people, sorted into a folder, and a Describe match threshold
     * that every image clears.
     *
     * Assertions:
     *
     * "a cactus" finds all four with the cactus first, and "a man in a suit" a portrait first and the cactus after it. Each
     * result is in the order of the cosines of the images' Scene embeddings to the description's, highest first, carries the
     * total, and is sorted by similarity.
     *
     * Edge cases:
     *
     * Under the default threshold "a cactus" still finds the cactus, and first.
     */
    val cactus = sorted(CACTUS)
    val photos = PHOTOS.map(sorted(_))
    val all = cactus :: photos

    def byCosine(description: String): List[String] = all.sortBy(asset => -cosine(asset, description)).map(_.persistedId)

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      val cacti = describe("a cactus")
      ids(cacti).head shouldBe cactus.persistedId
      ids(cacti) shouldBe byCosine("a cactus")
      cacti.total shouldBe Some(4)
      cacti.nextCursor shouldBe None
      cacti.sort shouldBe List(SearchSort.Similarity)

      val men = describe("a man in a suit")
      photos.map(_.persistedId) should contain(ids(men).head)
      ids(men).indexOf(cactus.persistedId) should be > 0
      ids(men) shouldBe byCosine("a man in a suit")
      men.total shouldBe Some(4)
    }

    ids(describe("a cactus")).head shouldBe cactus.persistedId
  }

  test("A row stands at the cosine distance of its Scene embedding from the description's") {

    /**
     * Setup:
     *
     * A repository with a photo of a cactus and a portrait, and the page of the DAO for the embedding of "a cactus", with no
     * cutoff.
     *
     * Assertions:
     *
     * Each row's position is one less the cosine of its image's Scene embedding to the description's, as computed from the
     * embeddings the import returned, to the precision of a float, on either engine; the rows are nearest first, the page counts
     * both and nothing follows it.
     */
    val assets = List(sorted(CACTUS), sorted(PHOTOS.head))
    val found = page(embeddingOf("a cactus"))

    found.total shouldBe Some(2)
    found.hasMore shouldBe false
    found.rows.map(_.asset.persistedId) shouldBe assets.map(_.persistedId)
    found.rows.zip(assets).foreach {
      case (row, asset) =>
        val distance = row.sortValue match {
          case SortValue.Real(value) => value
          case other => fail(s"A Describe row stands at a distance, not at $other")
        }
        distance shouldBe (1 - cosine(asset, "a cactus")) +- 1e-5
        row.day shouldBe None
        row.secondSortValue shouldBe SortValue.Null
    }
  }

  test("A Describe search reads the repository outside the trash, and triage only when asked") {

    /**
     * Setup:
     *
     * A repository with a sorted portrait, a second sorted portrait that is then recycled, a portrait left in triage, and a photo
     * of a cactus left in triage and then recycled from it. The Describe match threshold is one every image clears.
     *
     * Assertions:
     *
     * A description finds the sorted portrait alone. With the triaged assets asked for, it finds the portrait in triage too.
     *
     * Edge cases:
     *
     * A recycled asset is never found, sorted or recycled from triage, with the triaged assets asked for or not.
     */
    val kept = sorted(PHOTOS(0))
    val recycled = sorted(PHOTOS(1))
    val inTriage = triaged(PHOTOS(2))
    val recycledFromTriage = triaged(CACTUS)
    testApp.service.library.recycleAssets(Set(recycled.persistedId, recycledFromTriage.persistedId))

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      val withoutTriage = describe("a portrait of a person")
      ids(withoutTriage) shouldBe List(kept.persistedId)
      withoutTriage.total shouldBe Some(1)

      val withTriage = describe("a portrait of a person", Filters(triaged = true))
      ids(withTriage).toSet shouldBe Set(kept.persistedId, inTriage.persistedId)
      withTriage.total shouldBe Some(2)

      ids(describe("a cactus", Filters(triaged = true))).toSet shouldBe Set(kept.persistedId, inTriage.persistedId)
    }
  }

  test("A Describe search never finds another repository's assets, a Video or an image with no Scene embedding") {

    /**
     * Setup:
     *
     * Two repositories. The first holds a sorted portrait, a Video of that portrait and an asset with no Scene embedding; the
     * second a sorted photo of a cactus. The Describe match threshold is one every image clears, and the triaged assets are asked
     * for, the Video being in triage.
     *
     * Assertions:
     *
     * In the first repository "a cactus" finds its portrait alone, though the nearest image is the other repository's, and in the
     * second it finds the cactus alone.
     *
     * Edge cases:
     *
     * The Video and the asset with no embedding are found by no description, their repository's included.
     */
    val first = testContext.repository
    val portrait = sorted(PHOTOS.head)
    val video = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.portrait.toFile))
    video.assetType.mediaType shouldBe "video"
    testContext.insertAsset()

    val second = testContext.persistRepository()
    switchContextRepo(second)
    val cactus = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset(CACTUS))

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      ids(describe("a cactus", Filters(triaged = true))) shouldBe List(cactus.persistedId)

      switchContextRepo(first)
      val found = describe("a cactus", Filters(triaged = true))
      ids(found) shouldBe List(portrait.persistedId)
      found.total shouldBe Some(1)
      ids(describe("a man in a suit", Filters(triaged = true))) shouldBe List(portrait.persistedId)
    }
  }

  test("The Describe match threshold leaves out an image the model finds less likely to match") {

    /**
     * Setup:
     *
     * A repository with a photo of a cactus and three portraits, and the probability the model gives each of matching "a cactus",
     * computed from the embeddings the import returned. The Describe match threshold is saved between the cactus's probability
     * and the most likely portrait's, then above the cactus's.
     *
     * Assertions:
     *
     * Under the first threshold "a cactus" finds the cactus alone, and its total is 1. Under the second it finds nothing, and its
     * total is 0.
     *
     * Edge cases:
     *
     * The DAO, given the cosine halfway between the cactus's and the nearest portrait's, returns the cactus alone, and given the
     * cosine of no cutoff, all four.
     */
    val cactus = sorted(CACTUS)
    val photos = PHOTOS.map(sorted(_))
    val description = "a cactus"
    val likeliestPortrait = photos.map(probability(_, description)).max
    probability(cactus, description) should be > likeliestPortrait

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, plain((probability(cactus, description) + likeliestPortrait) / 2)) {
      val found = describe(description)
      ids(found) shouldBe List(cactus.persistedId)
      found.total shouldBe Some(1)
    }

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, plain((probability(cactus, description) + 1) / 2)) {
      val found = describe(description)
      found.records shouldBe empty
      found.total shouldBe Some(0)
      found.nextCursor shouldBe None
    }

    val between = (cosine(cactus, description) + photos.map(cosine(_, description)).max) / 2
    page(embeddingOf(description), minCosine = between).rows.map(_.asset.persistedId) shouldBe List(cactus.persistedId)
    page(embeddingOf(description), minCosine = -1).rows should have size 4
  }

  test("The Describe result limit caps what a description finds, the nearest kept") {

    /**
     * Setup:
     *
     * A repository with a photo of a cactus and three portraits, a Describe match threshold that every image clears, and a
     * Describe result limit of 2.
     *
     * Assertions:
     *
     * "a cactus" finds the two nearest of the four, the cactus first, and its total is 2, the limit.
     *
     * Edge cases:
     *
     * Read a page of one asset at a time, the results end after the second: the limit bounds the pages together, not each.
     */
    val all = sorted(CACTUS) :: PHOTOS.map(sorted(_))
    val nearest = all.sortBy(asset => -cosine(asset, "a cactus")).map(_.persistedId).take(2)

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      withSetting(Settings.SEARCH_DESCRIBE_MAX_RESULTS, "2") {
        val found = describe("a cactus")
        ids(found) shouldBe nearest
        found.total shouldBe Some(2)
        found.nextCursor shouldBe None

        val onePerPage = pages("a cactus", pageSize = 1)
        onePerPage.flatMap(ids) shouldBe nearest
        onePerPage.map(_.total) shouldBe List(Some(2), None)
      }
    }
  }

  test("The pages of a description concatenate to its ranked list, with no repeat and no gap") {

    /**
     * Setup:
     *
     * A repository with a photo of a cactus, three portraits and two assets that share the cactus's Scene embedding, so three
     * images stand at one distance from any description. The Describe match threshold is one every image clears.
     *
     * Assertions:
     *
     * Read two assets at a time, and one at a time, the pages of "a cactus" hold the six assets of the single page that holds
     * them all, in its order. A first page carries the total and a cursor, a page after it no total, and the last page no cursor.
     *
     * Edge cases:
     *
     * The three images at one distance are in the order of their IDs, and a page that ends among them is continued with the next
     * of them. A cursor is refused for another description and for the same description with the triaged assets asked for. A page
     * of no assets is refused, and so is a search asked for with a sort, the similarity sort included: likeness is the one order,
     * and no query names it.
     */
    val cactus = sorted(CACTUS)
    PHOTOS.foreach(sorted(_))
    val twins = List.fill(2)(embedded(cactus.sceneEmbeddings.siglip2.value))
    val description = "a cactus"

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      val whole = describe(description)
      whole.total shouldBe Some(6)
      whole.nextCursor shouldBe None
      ids(whole).take(3) shouldBe (cactus :: twins).map(_.persistedId).sorted

      val byTwo = pages(description, pageSize = 2)
      byTwo.map(_.records.length) shouldBe List(2, 2, 2)
      byTwo.flatMap(ids) shouldBe ids(whole)
      byTwo.map(_.total) shouldBe List(Some(6), None, None)
      byTwo.map(_.nextCursor.isDefined) shouldBe List(true, true, false)

      pages(description, pageSize = 1).flatMap(ids) shouldBe ids(whole)

      val cursor = byTwo.head.nextCursor
      intercept[SearchCursorException](describe("a man in a suit", cursor = cursor))
      intercept[SearchCursorException](describe(description, Filters(triaged = true), cursor = cursor))
      intercept[IllegalArgumentException](describe(description, pageSize = 0))
      intercept[IllegalArgumentException](
        testApp.service.search.describe(description, new SearchQuery(rpp = 2, searchSort = List(SearchSort.Similarity))))
    }
  }

  test("A cursor that carries no distance is refused") {

    /**
     * Setup:
     *
     * A repository with a photo of a cactus, and a cursor that names it with the fingerprint of a description's search but, as
     * its position, a text and not a distance: what no page of a Describe search hands out.
     *
     * Assertions:
     *
     * The search refuses it as a malformed cursor.
     */
    val cactus = sorted(CACTUS)
    val scope =
      SearchCursor.describeFingerprint("a cactus", Filters().query(), testContext.repository.persistedId, testApp.dataSourceType)
    val forged = SearchCursor(None, None, SortValue.Text("0.5"), cactus.persistedId, scope)

    intercept[SearchCursorException](describe("a cactus", cursor = Some(forged))).getMessage shouldBe "Malformed cursor"
  }

  test("A description finds nothing while the Describe model is off") {

    /**
     * Setup:
     *
     * A repository with a photo of a cactus, and the test instance's Describe embedder switched off for the search, so it embeds
     * nothing.
     *
     * Assertions:
     *
     * "a cactus" finds nothing, with a total of 0, rather than failing.
     */
    sorted(CACTUS)
    val embedder = testApp.describeEmbedder.asInstanceOf[altitude.test.SwitchableDescribeEmbedder]
    embedder.turnOff()
    try {
      val found = describe("a cactus")
      found.records shouldBe empty
      found.total shouldBe Some(0)
    } finally embedder.turnOn()
  }

  test("A description is narrowed by each Search filter, alone and together, and keeps its own order") {

    /**
     * Setup:
     *
     * A repository with the folder Trips and its subfolder Lisbon, the people Alice, Bob and Carol, who has no Face, and the
     * Location Lisbon. Nine still images, each with a Scene embedding: the photo of a cactus and three portraits, imported, and
     * five placed at cosines of their own to "a cactus", two of them left in triage. They differ in their folder, their Faces,
     * their Location, their capture day, their import day and their file name. The Describe match threshold is one every image
     * clears.
     *
     * Assertions:
     *
     * With the triaged assets and nothing else, "a cactus" ranks all nine by the cosine of their embeddings, and with no filter
     * at all the seven sorted ones: the description alone. Within each filter (a Search text matched by file names and one
     * matched by a person's name, the folder with its subfolder, one person, two people as all and as any of them, the Location,
     * a Date Taken range, a Date Imported range) and within two or three together, it finds exactly the images of that ranking
     * that the filters' own search finds, in the ranking's order, and counts them.
     *
     * Edge cases:
     *
     * Filters that match no asset find nothing, with a total of 0, and are no error: a person with no Face, a person and a
     * Location the repository does not have, a Search text no asset has, and two filters no asset passes together.
     */
    val description = "a cactus"
    val trips = testApp.service.folder.add("Trips")
    val lisbonFolder = testApp.service.folder.add("Lisbon", parentId = trips.id)
    val List(alice, bob, carol) =
      List("Alice", "Bob", "Carol").map(name => testApp.service.person.addPerson(Person(name = Some(name))))
    val lisbon = testApp.service.location.addLocation("Lisbon", 38.72, -9.14)

    val cactus = imported(CACTUS)
    val affleck = imported(PHOTOS(0), Some(trips))
    val damon = imported(PHOTOS(1), Some(lisbonFolder))
    val bullock = imported(PHOTOS(2))
    val beachOne = placed(description, 0.5, seed = 1, folder = Some(trips), filename = Some("beach one.jpg"))
    val beachTwo = placed(description, 0.45, seed = 2, folder = Some(lisbonFolder), filename = Some("beach two.jpg"))
    val plainOne = placed(description, 0.4, seed = 3, filename = Some("plain.jpg"))
    val beachThree = placed(description, 0.35, seed = 4, filename = Some("beach three.jpg"), isTriaged = true)
    val other = placed(description, 0.3, seed = 5, filename = Some("other.jpg"), isTriaged = true)
    val all = List(cactus, affleck, damon, bullock, beachOne, beachTwo, plainOne, beachThree, other)

    def faces(person: Person, photos: Photo*): Unit =
      photos.zipWithIndex.foreach((photo, seed) => testContext.addTestFace(person, photo.asset, TestContext.unitVector(seed)))
    faces(alice, affleck, damon, beachTwo, plainOne, beachThree)
    faces(bob, damon, bullock, plainOne)
    testApp.service.location.addAssets(lisbon.persistedId, Set(cactus, damon, beachOne, beachThree).map(_.id))

    // Each photo's capture day, none for one of them, and its import day
    val dates = Map(
      cactus -> (Some("2024-06-10"), "2025-01-05"),
      affleck -> (Some("2024-06-15"), "2025-01-08"),
      damon -> (Some("2024-07-01"), "2025-01-08"),
      bullock -> (None, "2025-02-01"),
      beachOne -> (Some("2024-06-20"), "2025-01-08"),
      beachTwo -> (Some("2024-06-25"), "2025-01-09"),
      plainOne -> (Some("2024-08-01"), "2025-01-09"),
      beachThree -> (Some("2024-06-12"), "2025-01-08"),
      other -> (Some("2024-06-13"), "2025-03-01")
    )
    dates.foreach {
      case (photo, (taken, importedOn)) =>
        testContext.setAssetDates(
          photo.id,
          taken.map(day => LocalDateTime.parse(s"${day}T10:00:00")),
          // Midday, so the instant is in that day in the server's zone, wherever the suite runs
          OffsetDateTime.of(LocalDateTime.parse(s"${importedOn}T12:00:00"), ZoneOffset.UTC)
        )
    }

    val june = Some(DateRange(Some(LocalDate.parse("2024-06-01")), Some(LocalDate.parse("2024-06-30"))))
    val eighthOfJanuary = Some(DateRange(Some(LocalDate.parse("2025-01-08")), Some(LocalDate.parse("2025-01-08"))))
    val trip = Set(trips.persistedId)
    val inLisbon = Set(lisbon.persistedId)
    def people(found: Person*): Set[String] = found.map(_.persistedId).toSet

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      val ranking = ids(describe(description, Filters(triaged = true)))
      ranking shouldBe ranked(all, description)

      val alone = describe(description)
      ids(alone) shouldBe ranked(all.filterNot(Set(beachThree, other)), description)
      alone.total shouldBe Some(7)

      /** The description within the filters finds the images of the ranking that the filters' own search finds, in its order */
      def narrowedTo(filters: Filters, expected: Photo*): Unit = withClue(s"$filters: ") {
        val matched = matchedBy(filters)
        matched shouldBe expected.map(_.id).toSet
        val found = describe(description, filters)
        ids(found) shouldBe ranking.filter(matched)
        found.total shouldBe Some(expected.size)
        found.nextCursor shouldBe None
      }

      narrowedTo(Filters(text = Some("beach")), beachOne, beachTwo)
      narrowedTo(Filters(text = Some("alice")), affleck, damon, beachTwo, plainOne)
      narrowedTo(Filters(folderIds = trip), affleck, damon, beachOne, beachTwo)
      narrowedTo(Filters(personIds = people(alice)), affleck, damon, beachTwo, plainOne)
      narrowedTo(Filters(personIds = people(alice, bob)), damon, plainOne)
      val eitherOfThem = Filters(personIds = people(alice, bob), peopleMatch = PeopleMatch.Any)
      narrowedTo(eitherOfThem, affleck, damon, bullock, beachTwo, plainOne)
      narrowedTo(Filters(locationIds = inLisbon), cactus, damon, beachOne)
      narrowedTo(Filters(takenRange = june), cactus, affleck, beachOne, beachTwo)
      narrowedTo(Filters(importedRange = eighthOfJanuary), affleck, damon, beachOne)

      narrowedTo(Filters(folderIds = trip, takenRange = june), affleck, beachOne, beachTwo)
      narrowedTo(Filters(text = Some("beach"), personIds = people(alice)), beachTwo)
      narrowedTo(Filters(triaged = true, locationIds = inLisbon), cactus, damon, beachOne, beachThree)
      narrowedTo(Filters(triaged = true, personIds = people(alice), importedRange = eighthOfJanuary), affleck, damon, beachThree)

      val unknown = Set(java.util.UUID.randomUUID.toString)
      val nothing = List(
        Filters(personIds = people(carol)),
        Filters(personIds = unknown),
        Filters(locationIds = unknown),
        Filters(text = Some("zebra")),
        Filters(text = Some("plain"), folderIds = trip)
      )
      nothing.foreach(narrowedTo(_))
    }
  }

  test("A description within filters never finds a recycled asset, another repository's, a Video or an image with no embedding") {

    /**
     * Setup:
     *
     * Two repositories. The first has the Location Lisbon, which holds a sorted portrait, a second portrait that is then
     * recycled, a Video of a portrait and an asset with no Scene embedding, each of them named for a beach, and a third sorted
     * portrait outside the Location, under its own name. The second holds a photo of a cactus named for a beach too. The Describe
     * match threshold is one every image clears, and the triaged assets are asked for, the Video being in triage.
     *
     * Assertions:
     *
     * In the first repository "a cactus" within the Location, and within the Search text "beach", finds the sorted portrait
     * alone, with a total of 1, though the image nearest the description is the other repository's. In the second, within the
     * Search text, it finds the cactus alone.
     *
     * Edge cases:
     *
     * The filters' own search finds the Video and the asset with no embedding, which the description leaves out, and the
     * description alone finds the third portrait, which the filters leave out.
     */
    val first = testContext.repository
    val lisbon = testApp.service.location.addLocation("Lisbon", 38.72, -9.14)
    val portrait = sorted(PHOTOS(0))
    val recycled = sorted(PHOTOS(1))
    sorted(PHOTOS(2))
    val video = testApp.service.library.addImportAsset(IntegrationTestUtil.fileToImportAsset(TestVideos.portrait.toFile))
    val unembedded = testContext.insertAsset()
    val members = List(portrait, recycled, video, unembedded)
    members.zipWithIndex.foreach((asset, n) => testApp.service.asset.rename(asset.persistedId, s"beach $n.jpg"))
    testApp.service.location.addAssets(lisbon.persistedId, members.map(_.persistedId).toSet)
    testApp.service.library.recycleAssets(Set(recycled.persistedId))

    val second = testContext.persistRepository()
    switchContextRepo(second)
    val cactus = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset(CACTUS))
    testApp.service.asset.rename(cactus.persistedId, "beach cactus.jpg")
    val byText = Filters(triaged = true, text = Some("beach"))

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      ids(describe("a cactus", byText)) shouldBe List(cactus.persistedId)

      switchContextRepo(first)
      val inLisbon = Filters(triaged = true, locationIds = Set(lisbon.persistedId))
      for (filters <- List(inLisbon, byText)) withClue(s"$filters: ") {
        matchedBy(filters) shouldBe Set(portrait, video, unembedded).map(_.persistedId)
        val found = describe("a cactus", filters)
        ids(found) shouldBe List(portrait.persistedId)
        found.total shouldBe Some(1)
      }
      describe("a cactus", Filters(triaged = true)).total shouldBe Some(2)
    }
  }

  test("The Describe match threshold and the result limit hold within filters: the nearest of the assets they match") {

    /**
     * Setup:
     *
     * A repository with the folder Trips, which holds four assets whose Scene embeddings are at cosines of 0.20, 0.18, 0.16 and
     * 0.14 to "a cactus", and two assets outside it at 0.30 and 0.28, nearer the description than any in the folder.
     *
     * Assertions:
     *
     * With a Describe result limit of 2 and a threshold every image clears, "a cactus" within the folder finds the folder's two
     * nearest, with a total of 2: the limit counts the assets the filters match, not the nearer ones they leave out. With the
     * threshold at the probability of a cosine of 0.17, it finds those two again, the only ones of the folder that clear it.
     *
     * Edge cases:
     *
     * Read one asset to a page under the limit, the results end after the second. A threshold at the probability of a cosine of
     * 0.25, which only the assets outside the folder clear, finds nothing within it, with a total of 0, while the description
     * alone finds those two. The DAO, given a cosine of 0.15 and a limit of 3, returns the folder's three nearest.
     */
    val description = "a cactus"
    val trips = testApp.service.folder.add("Trips")
    val inFolder =
      List(0.20, 0.18, 0.16, 0.14).zipWithIndex.map((cosine, seed) => placed(description, cosine, seed, folder = Some(trips)))
    val outside = List(0.30, 0.28).zipWithIndex.map((cosine, seed) => placed(description, cosine, seed + 10))
    val within = Filters(folderIds = Set(trips.persistedId))
    val nearest = inFolder.take(2).map(_.id)

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      withSetting(Settings.SEARCH_DESCRIBE_MAX_RESULTS, "2") {
        val found = describe(description, within)
        ids(found) shouldBe nearest
        found.total shouldBe Some(2)
        found.nextCursor shouldBe None

        val onePerPage = pages(description, pageSize = 1, within)
        onePerPage.flatMap(ids) shouldBe nearest
        onePerPage.map(_.total) shouldBe List(Some(2), None)
      }
    }

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, plain(probabilityAt(0.17))) {
      val found = describe(description, within)
      ids(found) shouldBe nearest
      found.total shouldBe Some(2)
    }

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, plain(probabilityAt(0.25))) {
      val found = describe(description, within)
      found.records shouldBe empty
      found.total shouldBe Some(0)
      ids(describe(description)) shouldBe outside.map(_.id)
    }

    val three = page(embeddingOf(description), minCosine = 0.15, limit = 3, filters = within)
    three.rows.map(_.asset.persistedId) shouldBe inFolder.take(3).map(_.id)
    three.total shouldBe Some(3)
  }

  test("The pages of a description within filters concatenate to its ranked list, and a cursor continues that search alone") {

    /**
     * Setup:
     *
     * A repository with the folders Trips and Work. Trips holds five assets: three that share one Scene embedding, at a cosine of
     * 0.5 to "a cactus", so they stand at one distance from it, and two at 0.4 and 0.3. Work holds one at 0.45, and the root
     * folder one at 0.6. The Describe match threshold is one every image clears.
     *
     * Assertions:
     *
     * Read two assets at a time, and one at a time, the pages of "a cactus" within Trips hold the five assets of the single page
     * that holds them all, in its order, and none of the assets outside the folder. A first page carries the total and a cursor,
     * a page after it no total, and the last page no cursor.
     *
     * Edge cases:
     *
     * The three images at one distance are in the order of their IDs, and a page that ends among them is continued with the next
     * of them. A cursor of the description within Trips is refused for the description alone, within Work, within Trips with the
     * triaged assets, for another description within Trips, and for the folder's own search with no description; that search's
     * cursor is refused for the description within the folder.
     */
    val description = "a cactus"
    val trips = testApp.service.folder.add("Trips")
    val work = testApp.service.folder.add("Work")
    val shared = TestContext.atCosine(embeddingOf(description), 0.5, seed = 1)
    val twins = List.fill(3)(Photo(embedded(shared, folder = Some(trips)), shared))
    val rest = List(0.4, 0.3).zipWithIndex.map((cosine, seed) => placed(description, cosine, seed + 2, folder = Some(trips)))
    placed(description, 0.45, seed = 10, folder = Some(work))
    placed(description, 0.6, seed = 11)
    val inTrips = Filters(folderIds = Set(trips.persistedId))

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      val whole = describe(description, inTrips)
      ids(whole) shouldBe twins.map(_.id).sorted ++ rest.map(_.id)
      whole.total shouldBe Some(5)
      whole.nextCursor shouldBe None

      val byTwo = pages(description, pageSize = 2, inTrips)
      byTwo.map(_.records.length) shouldBe List(2, 2, 1)
      byTwo.flatMap(ids) shouldBe ids(whole)
      byTwo.map(_.total) shouldBe List(Some(5), None, None)
      byTwo.map(_.nextCursor.isDefined) shouldBe List(true, true, false)

      pages(description, pageSize = 1, inTrips).flatMap(ids) shouldBe ids(whole)

      val cursor = byTwo.head.nextCursor
      intercept[SearchCursorException](describe(description, cursor = cursor))
      intercept[SearchCursorException](describe(description, Filters(folderIds = Set(work.persistedId)), cursor = cursor))
      intercept[SearchCursorException](describe(description, inTrips.copy(triaged = true), cursor = cursor))
      intercept[SearchCursorException](describe("a man in a suit", inTrips, cursor = cursor))
      intercept[SearchCursorException](testApp.service.library.search(inTrips.query(cursor, pageSize = 2)))

      val folderCursor = testApp.service.library.search(inTrips.query(pageSize = 2)).nextCursor
      folderCursor shouldBe defined
      intercept[SearchCursorException](describe(description, inTrips, cursor = folderCursor))
    }
  }

  if (isPostgres) {

    /**
     * `count` sorted assets, copies of `templateId` in its repository, each with a random unit vector of its own as its SigLIP 2
     * Scene embedding, their IDs opening with `prefix`
     */
    def seedEmbedded(templateId: String, count: Int, prefix: String): Unit =
      update(
        s"""INSERT INTO asset (id, repository_id, owner_user_id, original_owner, checksum, media_type, media_subtype, mime_type,
           |                   width, height, area_size, folder_id, filename, size_bytes, is_triaged, is_recycled, is_purged,
           |                   is_pipeline_processed, created_at, siglip2_embedding)
           |SELECT '$prefix' || lpad(g.n::text, ${36 - prefix.length}, '0'), repository_id, owner_user_id, original_owner,
           |       '$prefix' || lpad(g.n::text, ${64 - prefix.length}, '0'), media_type, media_subtype, mime_type,
           |       width, height, area_size, folder_id, filename, size_bytes, is_triaged, is_recycled, is_purged,
           |       is_pipeline_processed, created_at,
           |       l2_normalize((SELECT array_agg(random() - 0.5 + (s + g.n) * 0) FROM generate_series(1, 768) AS s)::real[]::vector)
           |  FROM asset CROSS JOIN generate_series(1, $count) AS g (n)
           | WHERE asset.id = ?""".stripMargin,
        templateId
      )

    /** Has the planner read the vector index for a nearest-rows statement, which over seeded rows it may cost above a scan */
    def preferVectorIndex(): Unit =
      List("seqscan", "bitmapscan", "sort").foreach(path => update(s"SET LOCAL enable_$path = off"))

    /** How many scans of the Describe index the current transaction has made */
    def describeIndexScans: Long =
      query("SELECT pg_stat_get_xact_numscans('asset_describe'::regclass) AS n").head("n").asInstanceOf[Number].longValue

    test("A PostgreSQL Describe search finds its limit of rows past the candidates the vector index keeps") {

      /**
       * Setup:
       *
       * A repository of 2,500 sorted assets, each with a random unit vector as its Scene embedding, with the planner told to read
       * the vector index, and a random unit vector as the description's embedding. pgvector keeps 1,000 candidates at most as it
       * scans an index. Every search ranks through the index, the limit of an exact ranking being 0.
       *
       * Assertions:
       *
       * A page for a limit of 2,000 counts 2,000 rows, twice the candidates, read from the vector index, and its rows are the
       * first of them, nearest first. Read 700 rows at a time, the pages hold the 2,000 once each, in order.
       *
       * Edge cases:
       *
       * The same statement in a transaction not set up for a vector search counts only the index's candidates, a handful: it is
       * the setup that has the scan go on until the limit is met.
       */
      val template = testContext.insertAsset()
      val description = unitVector(1)

      atScale(seedEmbedded(template.persistedId, 2500, "embedded-")) {
        preferVectorIndex()
        // Before any search sets the transaction up, which lasts until it ends
        val notSetUp =
          testApp.DAO.search
            .describe(Filters().query(pageSize = 700, exactLimit = 0), DescribeRanking(description, minCosine = -1, limit = 2000))
        notSetUp.total.value should be < 100
        val scansBefore = describeIndexScans

        val first = page(description, limit = 2000, pageSize = 700, exactLimit = 0)
        describeIndexScans shouldBe scansBefore + 1
        first.total shouldBe Some(2000)
        first.rows should have size 700
        first.hasMore shouldBe true

        def distances(found: SearchPage): List[Double] = found.rows.map(_.sortValue.bindValue.asInstanceOf[Double])
        def cursorAfter(found: SearchPage): Option[SearchCursor] =
          found.rows.lastOption.map(last => SearchCursor(None, None, last.sortValue, last.asset.persistedId, scope = ""))

        val second = page(description, limit = 2000, cursor = cursorAfter(first), pageSize = 700, exactLimit = 0)
        val third = page(description, limit = 2000, cursor = cursorAfter(second), pageSize = 700, exactLimit = 0)
        third.rows should have size 600
        third.hasMore shouldBe false
        val all = List(first, second, third)
        all.flatMap(_.rows.map(_.asset.persistedId)).distinct should have size 2000
        all.flatMap(distances) shouldBe all.flatMap(distances).sorted
      }
    }

    test("A PostgreSQL description within filters ranks few matching assets exactly, and many through the vector index") {

      /**
       * Setup:
       *
       * A repository with the folder Trips, which holds the photo of a cactus and three portraits, imported, two assets placed at
       * cosines of their own to "a cactus", and one asset with no Scene embedding: six that carry an embedding. Two more are
       * placed outside the folder. The planner is told to read the vector index wherever a statement lets it, and the Describe
       * match threshold is one every image clears.
       *
       * Assertions:
       *
       * Within the folder, "a cactus" ranked exactly, as six assets are under the limit of 75,000, and ranked through the index,
       * under a limit of 5, finds the same six in the same order, with the same total. The exact search scans the vector index
       * not once and the search past the limit once a page. A cursor of the exact search's first page continues through the index
       * and one of the index's continues exactly, each to the rest of the list with no repeat and no gap.
       *
       * Edge cases:
       *
       * The limit counts the assets that carry an embedding: six under a limit of 6 are ranked exactly.
       */
      val description = "a cactus"
      val trips = testApp.service.folder.add("Trips")
      (CACTUS :: PHOTOS).foreach(sorted(_, Some(trips)))
      placed(description, 0.5, seed = 1, folder = Some(trips))
      placed(description, 0.3, seed = 2, folder = Some(trips))
      testContext.insertAsset(testContext.makeAsset(folder = Some(trips)))
      placed(description, 0.6, seed = 3)
      placed(description, 0.4, seed = 4)
      val inTrips = Filters(folderIds = Set(trips.persistedId))

      withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
        testApp.txManager.withTransaction {
          preferVectorIndex()

          /** What a search finds, and how many scans of the vector index it made */
          def scanning(search: => SearchResult): (SearchResult, Long) = {
            val before = describeIndexScans
            val found = search
            (found, describeIndexScans - before)
          }

          val (exact, exactScans) = scanning(describe(description, inTrips))
          exact.total shouldBe Some(6)
          ids(exact) should have size 6
          exactScans shouldBe 0

          val (byIndex, indexScans) = scanning(describe(description, inTrips, exactLimit = 5))
          ids(byIndex) shouldBe ids(exact)
          byIndex.total shouldBe Some(6)
          indexScans shouldBe 1

          scanning(describe(description, inTrips, exactLimit = 6))._2 shouldBe 0

          val firstExact = describe(description, inTrips, pageSize = 2)
          val (restByIndex, restScans) =
            scanning(describe(description, inTrips, cursor = firstExact.nextCursor, pageSize = 10, exactLimit = 5))
          ids(firstExact) ++ ids(restByIndex) shouldBe ids(exact)
          restScans shouldBe 1

          val firstByIndex = describe(description, inTrips, pageSize = 2, exactLimit = 5)
          ids(firstByIndex) shouldBe ids(firstExact)
          val (restExact, restExactScans) =
            scanning(describe(description, inTrips, cursor = firstByIndex.nextCursor, pageSize = 10))
          ids(firstByIndex) ++ ids(restExact) shouldBe ids(exact)
          restExactScans shouldBe 0
        }
      }
    }

    test("A PostgreSQL description with no filter ranks a view of few embedded assets exactly") {

      /**
       * Setup:
       *
       * Two repositories. The context's holds three sorted assets placed at cosines of their own to "a cactus", two more left in
       * triage, one sorted and then recycled, and a sorted asset with no Scene embedding; the other holds five sorted assets,
       * each with an embedding. The planner is told to read the vector index wherever a statement lets it, and the Describe match
       * threshold is one every image clears.
       *
       * Assertions:
       *
       * "a cactus" with no filter finds the context repository's three sorted assets, nearest first, and scans the vector index
       * not once: the view's three are counted, as any search's matches are, and being under the limit of 75,000 they are ranked
       * exactly. Under a limit of 3 they are ranked exactly still, and under a limit of 2 through the index, in one scan.
       *
       * Edge cases:
       *
       * The count is of the view's assets that carry an embedding, so the other repository's five, the recycled asset, the two in
       * triage and the one with no embedding count for nothing. With the triaged assets asked for the view holds five, ranked
       * exactly under a limit of 5 and through the index under a limit of 4. What the index hands out is held to the exact
       * ranking's rows in its order, and not to every one of them: the index is approximate, and over a few rows a scan was seen
       * to miss one (`docs/test-coverage.md`, **Describe search**).
       */
      val description = "a cactus"
      val kept = List(0.5, 0.4, 0.3).zipWithIndex.map((cosine, seed) => placed(description, cosine, seed))
      val inTriage = List(0.45, 0.35).zipWithIndex.map((cosine, seed) => placed(description, cosine, seed + 10, isTriaged = true))
      testApp.service.library.recycleAssets(Set(placed(description, 0.6, seed = 20).id))
      testContext.insertAsset()

      val first = testContext.repository
      val second = testContext.persistRepository()
      switchContextRepo(second)
      (30 until 35).foreach {
        seed =>
          val asset = testContext.makeAsset(repository = Some(second))
          testContext.insertAsset(asset.copy(sceneEmbeddings = SceneEmbeddings(siglip2 = Some(unitVector(seed)))))
      }
      switchContextRepo(first)

      withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
        testApp.txManager.withTransaction {
          preferVectorIndex()

          /** What the description finds under a limit of exact ranking, and how many scans of the vector index it made */
          def scanning(exactLimit: Int, filters: Filters = Filters()): (List[String], Long) = {
            val before = describeIndexScans
            val found = describe(description, filters, exactLimit = exactLimit)
            (ids(found), describeIndexScans - before)
          }

          /** One scan of the index, handing out rows of the exact ranking alone, each once, in its order */
          def throughIndex(exactLimit: Int, filters: Filters, exact: List[String]): Unit = {
            val (found, scans) = scanning(exactLimit, filters)
            scans shouldBe 1
            found shouldBe exact.filter(found.toSet)
          }

          scanning(Const.Search.DESCRIBE_EXACT_LIMIT) shouldBe (kept.map(_.id), 0)
          scanning(exactLimit = 3) shouldBe (kept.map(_.id), 0)
          throughIndex(exactLimit = 2, Filters(), kept.map(_.id))

          val withTriage = ranked(kept ++ inTriage, description)
          scanning(exactLimit = 5, Filters(triaged = true)) shouldBe (withTriage, 0)
          throughIndex(exactLimit = 4, Filters(triaged = true), withTriage)
        }
      }
    }

    test("A PostgreSQL description's exact ranking plans no vector index, and its index ranking plans it with the filters") {

      /**
       * Setup:
       *
       * A repository with the folder Trips holding 2,500 sorted assets, each with a random unit vector as its Scene embedding,
       * every other one of them in the Location Lisbon, with the planner told to read the vector index wherever a statement lets
       * it, and a random unit vector as the description's embedding. The search is within the folder, the Location, a Date
       * Imported range that holds them all, and a Search text matched over the library that excludes a word none of them has.
       *
       * Assertions:
       *
       * The plan of the page ranked exactly names no `asset_describe`, the vector index, and sorts. The plan of the page ranked
       * through the index scans `asset_describe` in its order, with the folder, the Location and the Search text tested on the
       * rows it hands out. Read with a limit of 1,000, both find 1,000 of the 1,250 matching assets, all of the Location; the
       * exact page scans the vector index not once and the index page once, and the exact page's are the 1,000 nearest of all.
       *
       * Edge cases:
       *
       * The capped count that chooses between the two, 1,250 against a limit of 1,249 and of 1,250, does not read the vector
       * index either.
       */
      val trips = testApp.service.folder.add("Trips")
      val lisbon = testApp.service.location.addLocation("Lisbon", 38.72, -9.14)
      val template = testContext.insertAsset(testContext.makeAsset(folder = Some(trips)))
      val description = unitVector(1)
      val prefix = "embedded-"
      // The IDs `seedEmbedded` gives its even copies: the prefix, then the copy's number padded with zeros to an ID's length
      val members = (2 to 2500 by 2).map(n => prefix + "0" * (36 - prefix.length - n.toString.length) + n).toSet
      val today = LocalDate.now
      val filters = Filters(
        text = Some("-zebra"),
        folderIds = Set(trips.persistedId),
        locationIds = Set(lisbon.persistedId),
        importedRange = Some(DateRange(Some(today.minusDays(2)), Some(today.plusDays(2))))
      )

      atScale {
        seedEmbedded(template.persistedId, 2500, prefix)
        testApp.service.location.addAssets(lisbon.persistedId, members) shouldBe 1250
      } {
        preferVectorIndex()
        val query = testApp.service.search.withResolvedScope(filters.query())
        val ranking = DescribeRanking(description, minCosine = -1, limit = 1000)
        def planned(exactly: Boolean): String =
          planOf(
            SearchQueries.described(
              searchDialect,
              query,
              testContext.repository.persistedId,
              if (exactly) PostgresSearchDialect.ranked(_, ranking) else PostgresSearchDialect.rankedByIndex(_, ranking)
            ))

        val exactPlan = planned(exactly = true)
        (exactPlan should not).include("asset_describe")
        exactPlan should include("Sort")

        val indexPlan = planned(exactly = false)
        indexPlan should include("Index Scan using asset_describe")
        indexPlan should include("Order By: ((siglip2_embedding)::halfvec(768) <=>")
        List("folder_id", "location_asset", "text_0_0_document").foreach(indexPlan should include(_))

        val scansBefore = describeIndexScans
        val exact = page(description, filters = filters, pageSize = 1000)
        describeIndexScans shouldBe scansBefore
        val byIndex = page(description, filters = filters, pageSize = 1000, exactLimit = 1249)
        describeIndexScans shouldBe scansBefore + 1
        page(description, filters = filters, pageSize = 1, exactLimit = 1250)
        describeIndexScans shouldBe scansBefore + 1

        def distances(found: SearchPage): List[Double] = found.rows.map(_.sortValue.bindValue.asInstanceOf[Double])
        for (found <- List(exact, byIndex)) {
          found.total shouldBe Some(1000)
          found.rows should have size 1000
          found.rows.map(_.asset.persistedId).toSet.subsetOf(members) shouldBe true
          distances(found) shouldBe distances(found).sorted
        }
        // Nothing the index hands out is nearer than the nearest of all, row for row
        distances(exact).zip(distances(byIndex)).foreach((nearest, approximate) => nearest should be <= approximate)
      }
    }

    test("A PostgreSQL Describe search of a small Library finds its rows among a large instance's", Slow) {

      /**
       * Setup:
       *
       * Two repositories in one database, with the planner told to read the vector index: the context's holds 600 sorted assets
       * and another 40,000, each asset with a random unit vector as its Scene embedding. A scan of the index that goes on stops,
       * by pgvector's own cap, once it has visited 20,000 vectors.
       *
       * Assertions:
       *
       * Ranked through the index, the limit of an exact ranking being 0, a page for a limit of 580 counts 580 rows, all the
       * context repository's, though the scan passes nearly all of the other repository's 40,000 vectors to find them: the search
       * lifts the cap.
       *
       * Edge cases:
       *
       * Under the limit of 75,000 the same search does not scan the index at all: the repository's 600 are counted and ranked
       * exactly, and the page counts 580 of them again.
       */
      val template = testContext.insertAsset()
      val first = testContext.repository
      val second = testContext.persistRepository()
      switchContextRepo(second)
      val other = testContext.insertAsset(testContext.makeAsset(repository = Some(second)))
      switchContextRepo(first)

      atScale {
        seedEmbedded(template.persistedId, 600, "small-")
        seedEmbedded(other.persistedId, 40000, "large-")
      } {
        preferVectorIndex()
        val scansBefore = describeIndexScans

        val found = page(unitVector(2), limit = 580, pageSize = 500, exactLimit = 0)
        describeIndexScans shouldBe scansBefore + 1
        found.total shouldBe Some(580)
        found.rows.map(_.asset.persistedId).foreach(_ should startWith("small-"))

        val exact = page(unitVector(2), limit = 580, pageSize = 500)
        describeIndexScans shouldBe scansBefore + 1
        exact.total shouldBe Some(580)
        exact.rows.map(_.asset.persistedId).foreach(_ should startWith("small-"))
      }
    }
  }
}
