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
import altitude.core.util.GroupBy
import altitude.core.util.GroupedSearchResult
import altitude.core.util.PeopleMatch
import altitude.core.util.SearchCursor
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchGroupKey
import altitude.core.util.SearchPage
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult
import altitude.core.util.SearchSort
import altitude.core.util.SortDirection
import altitude.core.util.SortValue
import altitude.core.util.WeakerMatches

object DescribeSearchTests {

  /** A Describe match threshold so low that every embedded still image clears it, whatever the description */
  private val EVERY_IMAGE = "0.000000000000001"

  private val PHOTOS = List("people/affleck.jpg", "people/damon.jpg", "people/bullock.jpg")
  private val CACTUS = "images/cactus.jpg"

  /**
   * The filters a description is searched within: the repository view outside the trash and, unless `triaged`, outside triage;
   * or, with `triageView`, the triaged assets alone
   */
  private case class Filters(
      triaged: Boolean = false,
      triageView: Boolean = false,
      text: Option[String] = None,
      folderIds: Set[String] = Set(),
      personIds: Set[String] = Set(),
      peopleMatch: PeopleMatch = PeopleMatch.All,
      locationIds: Set[String] = Set(),
      takenRange: Option[DateRange] = None,
      importedRange: Option[DateRange] = None) {

    /**
     * The search of these filters as a request asks for it, unresolved. `exactLimit` is how many matching assets PostgreSQL ranks
     * exactly before it ranks through its vector index.
     */
    def query(
        cursor: Option[SearchCursor] = None,
        pageSize: Int = 50,
        exactLimit: Int = Const.Search.DESCRIBE_EXACT_LIMIT,
        sort: Option[SearchSort] = None,
        grouping: Option[SearchGrouping] = None): SearchQuery =
      new SearchQuery(
        params =
          if (triageView) SearchQuery.TRIAGE_VIEW
          else
            Map[String, Any](FieldConst.Asset.IS_RECYCLED -> false) ++ Option.unless(triaged)(
              FieldConst.Asset.IS_TRIAGED -> false),
        text = text,
        folderIds = folderIds,
        personIds = personIds,
        peopleMatch = peopleMatch,
        locationIds = locationIds,
        takenRange = takenRange,
        importedRange = importedRange,
        rpp = pageSize,
        cursor = cursor,
        describeExactLimit = exactLimit,
        searchSort = sort.toList,
        grouping = grouping
      )
  }

  /** A still image of a test's library with the SigLIP 2 Scene embedding it is ranked by */
  private case class Photo(asset: Asset, embedding: SceneEmbedding) {
    def id: String = asset.persistedId
  }
}

/**
 * The Describe search: a description ranked against the SigLIP 2 Scene embeddings of the repository's still images, on both
 * engines, with the real Scene models and text tower.
 */
@DoNotDiscover class DescribeSearchTests(override val testApp: Altitude) extends IntegrationTestCore with SearchPlans {
  import DescribeSearchTests.*

  override protected def usesSceneModels: Boolean = true

  override protected def usesDescribeModel: Boolean = true

  /** A fixture image imported through the whole pipeline, which leaves it in triage */
  private def triaged(relPath: String): Asset =
    testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset(relPath))

  /** A fixture image imported and moved out of triage into `folder`, the root folder by default */
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
      isTriaged: Boolean = false,
      taken: Option[LocalDateTime] = None): Asset = {
    val asset = testContext.makeAsset(folder = folder, isTriaged = isTriaged, originalCreatedAt = taken)
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
      isTriaged: Boolean = false,
      taken: Option[LocalDateTime] = None): Photo = {
    val embedding = TestContext.atCosine(embeddingOf(description), cosine, seed)
    Photo(embedded(embedding, folder, filename, isTriaged, taken), embedding)
  }

  /** The photos' IDs in the order a description ranks them: the highest cosine of their Scene embedding to its own first */
  private def ranked(photos: List[Photo], description: String): List[String] = {
    val described = embeddingOf(description)
    photos.sortBy(photo => (-photo.embedding.similarity(described), photo.id)).map(_.id)
  }

  /** A unit vector of the model's dimensions, random by `seed` */
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

  /** A probability as a Setting's text: a plain decimal, never in exponent notation */
  private def plain(probability: Double): String = BigDecimal(probability).bigDecimal.toPlainString

  private def describe(
      description: String,
      filters: Filters = Filters(),
      cursor: Option[SearchCursor] = None,
      pageSize: Int = 50,
      exactLimit: Int = Const.Search.DESCRIBE_EXACT_LIMIT,
      sort: Option[SearchSort] = None): SearchResult =
    testApp.service.search.describe(description, filters.query(cursor, pageSize, exactLimit, sort))

  /** A page of a description's results grouped by `grouping`, each group's assets in the order of `sort` */
  private def describeGrouped(
      description: String,
      grouping: SearchGrouping,
      sort: SearchSort,
      cursor: Option[SearchCursor] = None,
      pageSize: Int = 50,
      exactLimit: Int = Const.Search.DESCRIBE_EXACT_LIMIT,
      filters: Filters = Filters()): GroupedSearchResult =
    testApp.service.search
      .describeGrouped(description, filters.query(cursor, pageSize, exactLimit, Some(sort), Some(grouping)))

  /** Every page of a search, each read from the cursor of the page before it */
  private def continued[Page](read: Option[SearchCursor] => Page)(next: Page => Option[SearchCursor]): List[Page] = {
    val first = read(None)
    first :: List.unfold(next(first))(_.map {
      cursor =>
        val page = read(Some(cursor))
        (page, next(page))
    })
  }

  /** A page's groups as its assets' IDs, group by group */
  private def groupIds(result: GroupedSearchResult): List[List[String]] = result.groups.map(_.assets.map(_.persistedId))

  /** A page read from the DAO with the cutoff and limit given, in a transaction set up for a vector search of that limit */
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
      testApp.DAO.search.describe(query, DescribeRanking(embedding, minCosine, limit, weakerRatio = 0))
    }

  private def ids(result: SearchResult): List[String] = result.records.map(_.persistedId)

  /** The assets the filters' own search finds, with no description */
  private def matchedBy(filters: Filters): Set[String] =
    testApp.service.library.search(filters.query(pageSize = 500)).records.map(_.persistedId).toSet

  /** Every page of a description's results within the filters, `pageSize` assets at a time */
  private def pages(
      description: String,
      pageSize: Int,
      filters: Filters = Filters(),
      sort: Option[SearchSort] = None): List[SearchResult] =
    continued(cursor => describe(description, filters, cursor, pageSize, sort = sort))(_.nextCursor)

  test("A description ranks the still images that look most like it first") {

    /**
     * Setup:
     *
     * A repository with a photo of a cactus and three portraits of people, sorted into a folder, and a Describe match threshold
     * that every image clears.
     *
     * Assertions:
     *
     * "a cactus" finds all four with the cactus first, and "a man in a suit" a portrait first. Each result is in descending order
     * of cosine to the description, carries the total, and is sorted by similarity.
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
     * A photo of a cactus and a portrait, and the DAO's page for "a cactus" with no cutoff.
     *
     * Assertions:
     *
     * Each row's sort value is one minus its image's cosine to the description, to float precision on either engine; the rows are
     * nearest first and the page counts both.
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

  test("A Describe search of the Triage view ranks the triaged assets alone, within its filters and page by page") {

    /**
     * Setup:
     *
     * Four triaged assets at cosines of 0.8, 0.6, 0.4 and 0.2 to "a cactus", the second and fourth named beach; a triaged asset
     * with no Scene embedding; a sorted beach asset at 0.9; and a recycled triaged one at 0.95. Every image clears the threshold.
     *
     * Assertions:
     *
     * In the Triage view "a cactus" finds the four triaged assets, nearest first, with a total of 4, and within the Search text
     * "beach" the two of that name. Two to a page, the pages concatenate to the whole list, and ranking through PostgreSQL's
     * vector index gives the same list and total.
     *
     * Edge cases:
     *
     * The sorted asset, though nearest, the recycled one and the one with no embedding are never found there. The repository view
     * finds the sorted asset alone, and with the triaged assets asked for, the five.
     */
    val description = "a cactus"
    val inTriage = List(0.8 -> None, 0.6 -> Some("beach one.jpg"), 0.4 -> None, 0.2 -> Some("beach two.jpg")).zipWithIndex.map {
      case ((cosine, filename), seed) => placed(description, cosine, seed, filename = filename, isTriaged = true)
    }
    testContext.insertAsset(testContext.makeAsset(isTriaged = true))
    val sortedPhoto = placed(description, 0.9, seed = 10, filename = Some("beach three.jpg"))
    val recycled = placed(description, 0.95, seed = 11, isTriaged = true)
    testApp.service.library.recycleAssets(Set(recycled.id))
    val triageView = Filters(triageView = true)

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      val found = describe(description, triageView)
      ids(found) shouldBe inTriage.map(_.id)
      found.total shouldBe Some(4)

      ids(describe(description, triageView.copy(text = Some("beach")))) shouldBe List(inTriage(1).id, inTriage(3).id)

      pages(description, pageSize = 2, triageView).flatMap(ids) shouldBe inTriage.map(_.id)

      val byIndex = describe(description, triageView, exactLimit = 1)
      ids(byIndex) shouldBe inTriage.map(_.id)
      byIndex.total shouldBe Some(4)

      ids(describe(description)) shouldBe List(sortedPhoto.id)
      ids(describe(description, Filters(triaged = true))) shouldBe sortedPhoto.id :: inTriage.map(_.id)
    }
  }

  test("A Describe search never finds another repository's assets, a Video or an image with no Scene embedding") {

    /**
     * Setup:
     *
     * Two repositories: the first with a sorted portrait, a triaged Video and an asset with no Scene embedding, the second with a
     * photo of a cactus. Every image clears the threshold, and the triaged assets are asked for.
     *
     * Assertions:
     *
     * In each repository "a cactus" finds that repository's image alone.
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
     * A photo of a cactus and three portraits, and the probability the model gives each of matching "a cactus". The threshold is
     * set between the cactus's probability and the likeliest portrait's, then above the cactus's.
     *
     * Assertions:
     *
     * Under the first threshold "a cactus" finds the cactus alone, under the second nothing, and the totals agree.
     *
     * Edge cases:
     *
     * The DAO, given a cosine cutoff between the cactus's and the nearest portrait's, returns the cactus alone, and with no
     * cutoff all four.
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
     * A photo of a cactus and three portraits, a threshold every image clears, and a Describe result limit of 2.
     *
     * Assertions:
     *
     * "a cactus" finds the two nearest, the cactus first, with a total of 2.
     *
     * Edge cases:
     *
     * One asset to a page, the results end after the second: the limit bounds the pages together, not each.
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

  test("A description's weaker matches begin where a photo looks under a fraction as much like it as the best match") {

    /**
     * Setup:
     *
     * Photos at cosines of 0.40, 0.36, 0.31, 0.29 and 0.20 to "a cactus", the last three in the folder Trips, and two at -0.05
     * and -0.10 in the folder Apart. Every image clears the threshold, and a weaker matches ratio of 0.75 puts the line at 0.30.
     *
     * Assertions:
     *
     * The search finds the seven by cosine, with four weaker matches from the fourth. Within Trips the best match is at 0.31, so
     * the line is at 0.2325 and only the last photo is weaker.
     *
     * Edge cases:
     *
     * A ratio of 0 names no weaker match, and a ratio of 1 leaves the best match alone above the line. A search whose best match
     * has no positive cosine (Apart) names none: a fraction of such a cosine is no line.
     */
    val description = "a cactus"
    val trips = testApp.service.folder.add("Trips")
    val photos = List(
      placed(description, 0.40, seed = 1),
      placed(description, 0.36, seed = 2),
      placed(description, 0.31, seed = 3, folder = Some(trips)),
      placed(description, 0.29, seed = 4, folder = Some(trips)),
      placed(description, 0.20, seed = 5, folder = Some(trips))
    )
    val apart = testApp.service.folder.add("Apart")
    val opposed =
      List(placed(description, -0.05, seed = 6, folder = Some(apart)), placed(description, -0.10, seed = 7, folder = Some(apart)))

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      withSetting(Settings.SEARCH_DESCRIBE_WEAKER_MATCH_RATIO, "0.75") {
        val found = describe(description)
        ids(found) shouldBe (photos ++ opposed).map(_.id)
        found.weaker shouldBe Some(WeakerMatches(index = 3, count = 4))

        val inTrips = describe(description, Filters(folderIds = Set(trips.persistedId)))
        ids(inTrips) shouldBe photos.drop(2).map(_.id)
        inTrips.weaker shouldBe Some(WeakerMatches(index = 2, count = 1))
      }

      withSetting(Settings.SEARCH_DESCRIBE_WEAKER_MATCH_RATIO, "0") {
        describe(description).weaker shouldBe None
      }

      withSetting(Settings.SEARCH_DESCRIBE_WEAKER_MATCH_RATIO, "1") {
        describe(description).weaker shouldBe Some(WeakerMatches(index = 1, count = 6))
      }

      withSetting(Settings.SEARCH_DESCRIBE_WEAKER_MATCH_RATIO, "0.75") {
        val unlike = describe(description, Filters(folderIds = Set(apart.persistedId)))
        ids(unlike) shouldBe opposed.map(_.id)
        unlike.weaker shouldBe None
      }
    }
  }

  test("The page that holds the first weaker match of a description names it, wherever the pages end") {

    /**
     * Setup:
     *
     * Six photos at cosines of 0.40, 0.38, 0.36, 0.29, 0.25 and 0.20 to "a cactus", every one clearing the threshold, and a
     * weaker matches ratio of 0.75, so the last three are weaker.
     *
     * Assertions:
     *
     * Two to a page, only the second page (0.36 and 0.29) names the weaker matches, at its second row. One to a page, only the
     * fourth does.
     *
     * Edge cases:
     *
     * Three to a page, the weaker matches open the second page, which names its first row.
     */
    val description = "a cactus"
    List(0.40, 0.38, 0.36, 0.29, 0.25, 0.20).zipWithIndex.foreach((cosine, seed) => placed(description, cosine, seed))
    val three = Some(WeakerMatches(index = 0, count = 3))

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      withSetting(Settings.SEARCH_DESCRIBE_WEAKER_MATCH_RATIO, "0.75") {
        pages(description, pageSize = 2).map(_.weaker) shouldBe List(None, Some(WeakerMatches(index = 1, count = 3)), None)
        pages(description, pageSize = 1).map(_.weaker) shouldBe List(None, None, None, three, None, None)
        pages(description, pageSize = 3).map(_.weaker) shouldBe List(None, three)
      }
    }
  }

  test("A sorted Describe search shows the matches above the line in the sort's order, then the weaker ones in it") {

    /**
     * Setup:
     *
     * Six photos at cosines of 0.40, 0.38, 0.36, 0.29, 0.25 and 0.20 to "a cactus", named c, a, e, f, b and d, every one clearing
     * the threshold, and a weaker matches ratio of 0.75, so a, c and e are above the line. The search is sorted by file name.
     *
     * Assertions:
     *
     * One page holds a, c, e, then b, d, f, each part by name, names the three weaker matches from its fourth asset, carries a
     * total of 6 and says it is sorted by file name. Two to a page, the second page (e, b) names the weaker matches at its second
     * asset; one to a page, the fourth does. Only the first page carries the total.
     *
     * Edge cases:
     *
     * Three to a page, the weaker matches open the second page. Under a ratio of 0 the six are one part, a to f. A cursor of the
     * sorted search is refused by the similarity order, the reverse sort and another description, and a similarity cursor by the
     * sorted search.
     */
    val description = "a cactus"
    val byName = Some(SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC))
    val photos = List(0.40 -> "c", 0.38 -> "a", 0.36 -> "e", 0.29 -> "f", 0.25 -> "b", 0.20 -> "d").zipWithIndex.map {
      case ((cosine, name), seed) => name -> placed(description, cosine, seed, filename = Some(s"$name.jpg")).id
    }.toMap
    def named(names: String): List[String] = names.map(name => photos(name.toString)).toList
    val threeWeaker = (index: Int) => Some(WeakerMatches(index, count = 3))

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      withSetting(Settings.SEARCH_DESCRIBE_WEAKER_MATCH_RATIO, "0.75") {
        val whole = describe(description, sort = byName)
        ids(whole) shouldBe named("acebdf")
        whole.weaker shouldBe threeWeaker(3)
        whole.total shouldBe Some(6)
        whole.nextCursor shouldBe None
        whole.sort shouldBe byName.toList

        val byTwo = pages(description, pageSize = 2, sort = byName)
        byTwo.map(ids) shouldBe List(named("ac"), named("eb"), named("df"))
        byTwo.map(_.weaker) shouldBe List(None, threeWeaker(1), None)
        byTwo.map(_.total) shouldBe List(Some(6), None, None)

        val byOne = pages(description, pageSize = 1, sort = byName)
        byOne.flatMap(ids) shouldBe named("acebdf")
        byOne.map(_.weaker) shouldBe List(None, None, None, threeWeaker(0), None, None)

        val byThree = pages(description, pageSize = 3, sort = byName)
        byThree.map(ids) shouldBe List(named("ace"), named("bdf"))
        byThree.map(_.weaker) shouldBe List(None, threeWeaker(0))

        val sortedCursor = byTwo.head.nextCursor
        intercept[SearchCursorException](describe(description, cursor = sortedCursor))
        intercept[SearchCursorException](
          describe(description, cursor = sortedCursor, sort = Some(SearchSort(FieldConst.Asset.FILENAME, SortDirection.DESC))))
        intercept[SearchCursorException](describe("a man in a suit", cursor = sortedCursor, sort = byName))
        intercept[SearchCursorException](
          describe(description, cursor = describe(description, pageSize = 2).nextCursor, sort = byName))
      }

      withSetting(Settings.SEARCH_DESCRIBE_WEAKER_MATCH_RATIO, "0") {
        val onePart = describe(description, sort = byName)
        ids(onePart) shouldBe named("abcdef")
        onePart.weaker shouldBe None
        onePart.total shouldBe Some(6)
      }
    }
  }

  test("A grouped Describe search groups each of its two parts, and under the similarity sort a group is most alike first") {

    /**
     * Setup:
     *
     * Six photos at cosines to "a cactus": c, e, f at 0.40, 0.36, 0.29 taken on January 2, 2026, and a, b, d at 0.38, 0.25, 0.20
     * on January 1. Every image clears the threshold and the weaker matches ratio is 0.75, so those under 0.30 are weaker. The
     * search is grouped by Date Taken, newest first, sorted by similarity and then by file name.
     *
     * Assertions:
     *
     * Sorted by similarity, the page has four groups, January 2 (c, e) and January 1 (a), then for the weaker matches January 2
     * (f) and January 1 (b, d), each counting its own part's matches; the weaker matches begin at the third group. Two to a page,
     * no page continues a group: a day's weaker part is a group of its own.
     *
     * Edge cases:
     *
     * Sorted by file name, the groups are the same, each by name. One to a page by similarity, the second page continues the
     * first group.
     */
    val description = "a cactus"
    val second = LocalDateTime.of(2026, 1, 2, 10, 0)
    val first = LocalDateTime.of(2026, 1, 1, 10, 0)
    val photos = List(
      (0.40, "c", second),
      (0.36, "e", second),
      (0.29, "f", second),
      (0.38, "a", first),
      (0.25, "b", first),
      (0.20, "d", first)).zipWithIndex.map {
      case ((cosine, name, taken), seed) =>
        name -> placed(description, cosine, seed, filename = Some(s"$name.jpg"), taken = Some(taken)).id
    }.toMap
    def named(names: String): List[String] = names.map(name => photos(name.toString)).toList
    val byDay = SearchGrouping(GroupBy.DateTaken, SortDirection.DESC)
    val byName = SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)
    def days(result: GroupedSearchResult): List[Int] =
      result.groups.map(_.key).collect { case SearchGroupKey.Day(Some(date)) => date.getDayOfMonth }

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      withSetting(Settings.SEARCH_DESCRIBE_WEAKER_MATCH_RATIO, "0.75") {
        val alike = describeGrouped(description, byDay, SearchSort.Similarity)
        groupIds(alike) shouldBe List(named("ce"), named("a"), named("f"), named("bd"))
        days(alike) shouldBe List(2, 1, 2, 1)
        alike.groups.map(_.total) shouldBe List(2, 1, 1, 2)
        alike.weaker shouldBe Some(WeakerMatches(index = 2, count = 3))
        alike.total shouldBe Some(6)
        alike.sort shouldBe SearchSort.Similarity
        alike.continuesGroup shouldBe false

        val byTwo =
          continued(cursor => describeGrouped(description, byDay, SearchSort.Similarity, cursor, pageSize = 2))(_.nextCursor)
        byTwo.map(groupIds) shouldBe List(List(named("ce")), List(named("a"), named("f")), List(named("bd")))
        byTwo.map(_.weaker) shouldBe List(None, Some(WeakerMatches(index = 1, count = 3)), None)
        byTwo.map(_.continuesGroup) shouldBe List(false, false, false)
        byTwo.map(_.total) shouldBe List(Some(6), None, None)

        val inNameOrder = describeGrouped(description, byDay, byName)
        groupIds(inNameOrder) shouldBe List(named("ce"), named("a"), named("f"), named("bd"))
        inNameOrder.weaker shouldBe Some(WeakerMatches(index = 2, count = 3))

        val byOne =
          continued(cursor => describeGrouped(description, byDay, SearchSort.Similarity, cursor, pageSize = 1))(_.nextCursor)
        byOne.flatMap(groupIds).flatten shouldBe named("ceafbd")
        byOne.map(_.continuesGroup) shouldBe List(false, true, false, false, false, true)
        byOne.map(_.weaker.map(_.index)) shouldBe List(None, None, None, Some(0), None, None)
      }
    }
  }

  test("A Describe search grouped by Location or by Sequence groups each of its parts, most alike first within a group") {

    /**
     * Setup:
     *
     * Four sorted photos at cosines of 0.40, 0.36, 0.29 and 0.25 to "a cactus": the first and third in Lisbon, the second in
     * Porto, the fourth in no Location; and three triaged photos at 0.39, 0.33 and 0.21 in no Sequence. Every image clears the
     * threshold and the weaker matches ratio is 0.75.
     *
     * Assertions:
     *
     * Grouped by Location, the sorted photos are in four groups: Lisbon (0.40), Porto (0.36), then for the weaker matches Lisbon
     * (0.29) and no Location (0.25). In the Triage view grouped by Sequence, the triaged photos are in two groups of no Sequence:
     * 0.39 and 0.33, then the weaker one.
     *
     * Edge cases:
     *
     * One to a page, the Location grouping's pages hold the same photos in the same order.
     */
    val description = "a cactus"
    val lisbon = testApp.service.location.addLocation("Lisbon", 38.72, -9.14)
    val porto = testApp.service.location.addLocation("Porto", 41.15, -8.61)
    val sorted = List(0.40, 0.36, 0.29, 0.25).zipWithIndex.map((cosine, seed) => placed(description, cosine, seed).id)
    testApp.service.location.addAssets(lisbon.persistedId, Set(sorted(0), sorted(2))) shouldBe 2
    testApp.service.location.addAssets(porto.persistedId, Set(sorted(1))) shouldBe 1
    val inTriage =
      List(0.39, 0.33, 0.21).zipWithIndex.map((cosine, seed) => placed(description, cosine, seed + 10, isTriaged = true).id)
    val byLocation = SearchGrouping(GroupBy.Location)

    withSetting(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY, EVERY_IMAGE) {
      withSetting(Settings.SEARCH_DESCRIBE_WEAKER_MATCH_RATIO, "0.75") {
        val located = describeGrouped(description, byLocation, SearchSort.Similarity)
        groupIds(located) shouldBe List(List(sorted(0)), List(sorted(1)), List(sorted(2)), List(sorted(3)))
        located.groups.map(_.key).collect { case SearchGroupKey.Location(_, _, name, _) => name } shouldBe
          List(Some("Lisbon"), Some("Porto"), Some("Lisbon"), None)
        located.weaker shouldBe Some(WeakerMatches(index = 2, count = 2))
        located.total shouldBe Some(4)

        continued(cursor => describeGrouped(description, byLocation, SearchSort.Similarity, cursor, pageSize = 1))(_.nextCursor)
          .flatMap(groupIds)
          .flatten shouldBe sorted

        val sequenced = describeGrouped(
          description,
          SearchGrouping(GroupBy.Sequence),
          SearchSort.Similarity,
          filters = Filters(triageView = true))
        groupIds(sequenced) shouldBe List(inTriage.take(2), inTriage.drop(2))
        sequenced.groups.map(_.key) shouldBe List.fill(2)(SearchGroupKey.Sequence(None, None))
        sequenced.weaker shouldBe Some(WeakerMatches(index = 1, count = 1))
      }
    }
  }

  test("The pages of a description concatenate to its ranked list, with no repeat and no gap") {

    /**
     * Setup:
     *
     * A photo of a cactus, three portraits and two assets sharing the cactus's Scene embedding, so three images tie on distance.
     * Every image clears the threshold.
     *
     * Assertions:
     *
     * Two and one to a page, the pages of "a cactus" concatenate to the single page of all six. Only the first page carries the
     * total, and the last has no cursor.
     *
     * Edge cases:
     *
     * The tied images are in ID order, and a page ending among them continues with the next. A cursor is refused for another
     * description or other filters. A page of no assets is refused, and so is an explicit similarity sort: likeness is the order
     * of a query that names no sort.
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
     * A photo of a cactus, and a cursor with a description search's fingerprint but a text, not a distance, as its position.
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
     * A photo of a cactus, and the Describe embedder switched off, so it embeds nothing.
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
     * The folder Trips with its subfolder Lisbon, the people Alice, Bob and Carol (who has no Face), and the Location Lisbon.
     * Nine embedded still images, the cactus and three portraits imported and five placed, two of them in triage, differing in
     * folder, Faces, Location, capture day, import day and file name. Every image clears the threshold.
     *
     * Assertions:
     *
     * With the triaged assets, "a cactus" ranks all nine by cosine, and with no filter the seven sorted ones. Within each Search
     * filter, alone and two or three together, it finds exactly what the filters' own search finds, in the ranking's order, and
     * counts them.
     *
     * Edge cases:
     *
     * Filters that match no asset find nothing, with a total of 0, and are no error.
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
     * Two repositories. In the first, the Location Lisbon holds a sorted portrait, a recycled one, a triaged Video and an asset
     * with no Scene embedding, all named for a beach; a third portrait is outside it under its own name. The second holds a
     * cactus named for a beach. Every image clears the threshold, and the triaged assets are asked for.
     *
     * Assertions:
     *
     * In the first repository "a cactus" within the Location, and within the Search text "beach", finds the sorted portrait
     * alone, with a total of 1. In the second it finds the cactus alone.
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
     * The folder Trips with four assets at cosines of 0.20, 0.18, 0.16 and 0.14 to "a cactus", and two nearer assets outside it
     * at 0.30 and 0.28.
     *
     * Assertions:
     *
     * With a result limit of 2, "a cactus" within the folder finds the folder's two nearest, with a total of 2: the limit counts
     * the assets the filters match. With the threshold at a cosine of 0.17, it finds those two again.
     *
     * Edge cases:
     *
     * One to a page under the limit, the results end after the second. A threshold at a cosine of 0.25 finds nothing in the
     * folder, while the description alone finds the two outside. The DAO, given a cutoff of 0.15 and a limit of 3, returns the
     * folder's three nearest.
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
     * The folder Trips with three assets tied at a cosine of 0.5 to "a cactus" and two at 0.4 and 0.3; the folder Work with one
     * at 0.45; the root folder one at 0.6. Every image clears the threshold.
     *
     * Assertions:
     *
     * Two and one to a page, the pages of "a cactus" within Trips concatenate to the single page of its five. Only the first page
     * carries the total, and the last has no cursor.
     *
     * Edge cases:
     *
     * The tied images are in ID order, and a page ending among them continues with the next. A cursor of the search within Trips
     * is refused by any other filters or description and by the folder's search with no description, and that search's cursor by
     * the description.
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

    /** `count` copies of `templateId`, each with a random unit vector as its SigLIP 2 embedding, IDs opening with `prefix` */
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

    /** Makes the planner read the vector index, which over seeded rows it may cost above a scan */
    def preferVectorIndex(): Unit =
      List("seqscan", "bitmapscan", "sort").foreach(path => update(s"SET LOCAL enable_$path = off"))

    /** How many scans of the Describe index the current transaction has made */
    def describeIndexScans: Long =
      query("SELECT pg_stat_get_xact_numscans('asset_describe'::regclass) AS n").head("n").asInstanceOf[Number].longValue

    test("A PostgreSQL Describe search finds its limit of rows past the candidates the vector index keeps") {

      /**
       * Setup:
       *
       * 2,500 assets with random unit vectors as Scene embeddings, a random description embedding, and the planner told to read
       * the vector index. pgvector keeps at most 1,000 candidates in a scan. Every search ranks through the index.
       *
       * Assertions:
       *
       * A page for a limit of 2,000 counts 2,000 rows, twice the candidates, in one index scan. 700 to a page, the pages hold the
       * 2,000 once each, nearest first.
       *
       * Edge cases:
       *
       * The same statement in a transaction not set up for a vector search counts only a handful: the setup makes the scan go on
       * until the limit is met.
       */
      val template = testContext.insertAsset()
      val description = unitVector(1)

      atScale(seedEmbedded(template.persistedId, 2500, "embedded-")) {
        preferVectorIndex()
        // Before any search sets the transaction up: the setup lasts until it ends
        val notSetUp =
          testApp.DAO.search
            .describe(
              Filters().query(pageSize = 700, exactLimit = 0),
              DescribeRanking(description, minCosine = -1, limit = 2000, weakerRatio = 0))
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
       * The folder Trips with the cactus and three portraits, two placed assets and one with no Scene embedding: six embedded.
       * Two more are placed outside it. The planner is told to read the vector index, every image clears the threshold, and the
       * weaker matches ratio is the test Settings' 0.75.
       *
       * Assertions:
       *
       * Within the folder, "a cactus" ranked exactly (six under the default limit) and through the index (under a limit of 5)
       * finds the same six in the same order, total and weaker matches. The exact search makes no index scan and the other one a
       * page. A cursor from either continues the other way with no repeat and no gap. Sorted by file name, both hold the same two
       * parts; past the limit, each part's statement scans the index once.
       *
       * Edge cases:
       *
       * The limit counts only assets with an embedding: six under a limit of 6 are ranked exactly.
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
          exact.weaker shouldBe Some(WeakerMatches(index = 1, count = 5))
          exactScans shouldBe 0

          val (byIndex, indexScans) = scanning(describe(description, inTrips, exactLimit = 5))
          ids(byIndex) shouldBe ids(exact)
          byIndex.total shouldBe Some(6)
          byIndex.weaker shouldBe exact.weaker
          indexScans shouldBe 1

          scanning(describe(description, inTrips, exactLimit = 6))._2 shouldBe 0

          val firstExact = describe(description, inTrips, pageSize = 2)
          val (restByIndex, restScans) =
            scanning(describe(description, inTrips, cursor = firstExact.nextCursor, pageSize = 10, exactLimit = 5))
          ids(firstExact) ++ ids(restByIndex) shouldBe ids(exact)
          firstExact.weaker shouldBe exact.weaker
          restByIndex.weaker shouldBe None
          restScans shouldBe 1

          val firstByIndex = describe(description, inTrips, pageSize = 2, exactLimit = 5)
          ids(firstByIndex) shouldBe ids(firstExact)
          val (restExact, restExactScans) =
            scanning(describe(description, inTrips, cursor = firstByIndex.nextCursor, pageSize = 10))
          ids(firstByIndex) ++ ids(restExact) shouldBe ids(exact)
          restExactScans shouldBe 0

          val byName = Some(SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC))
          val (sortedExact, sortedExactScans) = scanning(describe(description, inTrips, sort = byName))
          ids(sortedExact).toSet shouldBe ids(exact).toSet
          sortedExact.weaker shouldBe exact.weaker
          sortedExactScans shouldBe 0
          val (sortedByIndex, sortedIndexScans) = scanning(describe(description, inTrips, exactLimit = 5, sort = byName))
          ids(sortedByIndex) shouldBe ids(sortedExact)
          sortedByIndex.weaker shouldBe sortedExact.weaker
          sortedIndexScans shouldBe 2
        }
      }
    }

    test("A PostgreSQL description with no filter ranks a view of few embedded assets exactly") {

      /**
       * Setup:
       *
       * Two repositories. The context's holds three sorted placed assets, two triaged, one recycled, and one with no Scene
       * embedding; the other holds five embedded assets. The planner is told to read the vector index, and every image clears the
       * threshold.
       *
       * Assertions:
       *
       * "a cactus" with no filter finds the three sorted assets, nearest first, with no index scan: the view's count is under the
       * limit, so they are ranked exactly. Under a limit of 3 still exactly, under a limit of 2 through the index in one scan.
       *
       * Edge cases:
       *
       * Only the view's embedded assets count. With the triaged assets asked for, the view's five are ranked exactly under a
       * limit of 5 and through the index under 4. The index's rows are checked to follow the exact ranking's order, not to be all
       * of it: the index is approximate, and over a few rows a scan was seen to miss one.
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
       * The folder Trips with 2,500 assets with random unit vectors as Scene embeddings, every other one in the Location Lisbon,
       * a random description embedding, and the planner told to read the vector index. The search is within the folder, the
       * Location, a Date Imported range holding them all, and a Search text excluding a word none has.
       *
       * Assertions:
       *
       * The exact page's query plan sorts and names no `asset_describe`. The index page's plan scans `asset_describe` in order,
       * testing the folder, Location and Search text on the rows it hands out. Under a limit of 1,000, both find 1,000 of the
       * 1,250 matches, the exact one with no index scan and the other with one, and no index row is nearer than the exact row.
       *
       * Edge cases:
       *
       * The capped count that chooses between the two (1,250 against limits of 1,249 and 1,250) reads no vector index.
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
        val ranking = DescribeRanking(description, minCosine = -1, limit = 1000, weakerRatio = 0)
        def planned(exactly: Boolean): String =
          planOf(
            SearchQueries
              .described(searchDialect, query, testContext.repository.persistedId, ranking.copy(throughIndex = !exactly)))

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
       * Two repositories, the context's with 600 assets and the other with 40,000, each with a random unit vector as its Scene
       * embedding, and the planner told to read the vector index. pgvector's own cap stops a scan after 20,000 vectors.
       *
       * Assertions:
       *
       * Ranked through the index, a page for a limit of 580 counts 580 rows, all the context repository's, though the scan passes
       * nearly all of the other's 40,000 vectors: the search lifts the cap.
       *
       * Edge cases:
       *
       * Under the default limit the same search makes no index scan: the 600 are ranked exactly, and the page counts 580 again.
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
