package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.tagobjects.Slow

import scala.util.Random

import altitude.core.Altitude
import altitude.core.SearchCursorException
import altitude.core.models.Asset
import altitude.core.models.SceneEmbedding
import altitude.core.models.SceneEmbeddings
import altitude.core.models.SceneModel
import altitude.core.models.Settings
import altitude.core.util.SearchCursor
import altitude.core.util.SearchPage
import altitude.core.util.SearchResult
import altitude.core.util.SearchSort
import altitude.core.util.SortValue

object DescribeSearchTests {

  /** A Describe match threshold so low that every embedded still image clears it, whatever the description */
  private val EVERY_IMAGE = "0.000000000000001"

  private val PHOTOS = List("people/affleck.jpg", "people/damon.jpg", "people/bullock.jpg")
  private val CACTUS = "images/cactus.jpg"
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

  /** A fixture image imported and moved into the repository's root folder, so out of triage */
  private def sorted(relPath: String): Asset = {
    val asset = triaged(relPath)
    testApp.service.library.moveAssetsToFolder(Set(asset.persistedId), testContext.repository.rootFolderId)
    asset
  }

  /** A sorted asset with no file, whose SigLIP 2 Scene embedding is `embedding` */
  private def embedded(embedding: SceneEmbedding): Asset =
    testContext.insertAsset(testContext.makeAsset().copy(sceneEmbeddings = SceneEmbeddings(siglip2 = Some(embedding))))

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

  /** The probability, by the model, that the image matches the description: what the Describe match threshold is compared to */
  private def probability(asset: Asset, description: String): Double =
    1 / (1 + math.exp(-(cosine(asset, description) * SceneModel.SIGLIP2_LOGIT_SCALE + SceneModel.SIGLIP2_LOGIT_BIAS)))

  /** A probability as a Setting's text: a plain decimal */
  private def plain(probability: Double): String = BigDecimal(probability).bigDecimal.toPlainString

  private def describe(
      description: String,
      triaged: Boolean = false,
      cursor: Option[SearchCursor] = None,
      pageSize: Int = 50): SearchResult =
    testApp.service.search.describe(description, triaged, cursor, pageSize)

  /** A page read from the DAO, with the cutoff and the limit given, inside a transaction set up for a search of that limit */
  private def page(
      embedding: SceneEmbedding,
      minCosine: Double = -1,
      limit: Int = 1000,
      triaged: Boolean = false,
      cursor: Option[SearchCursor] = None,
      pageSize: Int = 50): SearchPage =
    testApp.txManager.withVectorSearch(limit) {
      testApp.DAO.search.describe(embedding, minCosine, limit, triaged, cursor, pageSize)
    }

  private def ids(result: SearchResult): List[String] = result.records.map(_.persistedId)

  /** Every page of a description's results, `pageSize` assets at a time, each continued from the cursor of the page before it */
  private def pages(description: String, pageSize: Int): List[SearchResult] = {
    val first = describe(description, pageSize = pageSize)
    first :: List.unfold(first.nextCursor) {
      _.map {
        cursor =>
          val next = describe(description, cursor = Some(cursor), pageSize = pageSize)
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
    val photos = PHOTOS.map(sorted)
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

      val withTriage = describe("a portrait of a person", triaged = true)
      ids(withTriage).toSet shouldBe Set(kept.persistedId, inTriage.persistedId)
      withTriage.total shouldBe Some(2)

      ids(describe("a cactus", triaged = true)).toSet shouldBe Set(kept.persistedId, inTriage.persistedId)
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
      ids(describe("a cactus", triaged = true)) shouldBe List(cactus.persistedId)

      switchContextRepo(first)
      val found = describe("a cactus", triaged = true)
      ids(found) shouldBe List(portrait.persistedId)
      found.total shouldBe Some(1)
      ids(describe("a man in a suit", triaged = true)) shouldBe List(portrait.persistedId)
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
    val photos = PHOTOS.map(sorted)
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
    val all = sorted(CACTUS) :: PHOTOS.map(sorted)
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
     * of them. A cursor is refused for another description and for the same description with the triaged assets asked for, and a
     * page of no assets is refused.
     */
    val cactus = sorted(CACTUS)
    PHOTOS.foreach(sorted)
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
      intercept[SearchCursorException](describe(description, triaged = true, cursor = cursor))
      intercept[IllegalArgumentException](describe(description, pageSize = 0))
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
    val scope = SearchCursor.describeFingerprint("a cactus", false, testContext.repository.persistedId, testApp.dataSourceType)
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
       * scans an index.
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
          testApp.DAO.search.describe(description, minCosine = -1, limit = 2000, triaged = false, None, pageSize = 700)
        notSetUp.total.value should be < 100
        val scansBefore = describeIndexScans

        val first = page(description, limit = 2000, pageSize = 700)
        describeIndexScans shouldBe scansBefore + 1
        first.total shouldBe Some(2000)
        first.rows should have size 700
        first.hasMore shouldBe true

        def distances(found: SearchPage): List[Double] = found.rows.map(_.sortValue.bindValue.asInstanceOf[Double])
        def cursorAfter(found: SearchPage): Option[SearchCursor] =
          found.rows.lastOption.map(last => SearchCursor(None, None, last.sortValue, last.asset.persistedId, scope = ""))

        val second = page(description, limit = 2000, cursor = cursorAfter(first), pageSize = 700)
        val third = page(description, limit = 2000, cursor = cursorAfter(second), pageSize = 700)
        third.rows should have size 600
        third.hasMore shouldBe false
        val all = List(first, second, third)
        all.flatMap(_.rows.map(_.asset.persistedId)).distinct should have size 2000
        all.flatMap(distances) shouldBe all.flatMap(distances).sorted
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
       * A page for a limit of 580 counts 580 rows, all the context repository's, though the scan passes nearly all of the other
       * repository's 40,000 vectors to find them: the search lifts the cap.
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

        val found = page(unitVector(2), limit = 580, pageSize = 500)
        describeIndexScans shouldBe scansBefore + 1
        found.total shouldBe Some(580)
        found.rows.map(_.asset.persistedId).foreach(_ should startWith("small-"))
      }
    }
  }
}
