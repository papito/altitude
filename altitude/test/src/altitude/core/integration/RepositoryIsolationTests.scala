package altitude.core.integration

import altitude.test.TestContext
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.dao.AssetDao
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.*
import altitude.core.service.BaseService
import altitude.core.util.Query
import altitude.core.util.QueryResult

/** Every read and write of a repository-scoped table stays in the context repository, on both engines */
@DoNotDiscover class RepositoryIsolationTests(override val testApp: Altitude) extends IntegrationTestCore {

  /** The tables that carry `repository_id`, whose rows only their own repository's context reaches */
  private val scopedTables = List(
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
    "search_document")

  /**
   * A row of another repository as a scoped DAO reaches it: `key` selects it alone (by ID, or by a dimension only that repository
   * has, for the stats, which have no ID), `update` is a harmless change of one column, and `counter` a numeric column for
   * `increment`, where the table has one
   */
  private case class ScopedRow(
      table: String,
      dao: BaseDao[?],
      key: (String, Any),
      update: (String, Any),
      counter: Option[String] = None) {
    def id: Option[String] = Option.when(key._1 == FieldConst.ID)(key._2.toString)
    def keyQuery: Query = new Query().add(key)
  }

  /** The two repositories and a row of each scoped DAO's table in the second, which also holds the memberships and the stats */
  private case class TwoRepositories(first: Repository, second: Repository, rows: List[ScopedRow])

  private val isolationDimension = "isolation_test"

  /**
   * A second repository with a row of every scoped table: an asset (with its Search document), a folder, an album and a Location
   * holding the asset, a Person with a Face on it, a metadata field with a value on it, and a stat of its own. The context is the
   * first repository again when it returns.
   */
  private def twoRepositories(): TwoRepositories = {
    val first = testContext.repository
    val second = testContext.persistRepository()
    switchContextRepo(second)

    val asset = testContext.insertAsset(testContext.makeAsset(repository = Some(second)))
    val folder = testApp.service.folder.add("theirs")
    val album = testApp.service.album.add("theirs")
    testApp.service.album.addAssets(album.persistedId, Set(asset.persistedId))
    val location = testApp.service.location.addLocation("theirs", 48.8566, 2.3522)
    testApp.service.location.addAssets(location.persistedId, Set(asset.persistedId))
    val person = testApp.service.person.addPerson(Person())
    val face = testContext.addTestFace(person, asset, TestContext.unitVector(1))
    val field = testApp.service.metadata.addField(UserMetadataField(name = "theirs", fieldType = FieldType.KEYWORD))
    testApp.service.metadata.setMetadata(asset.persistedId, UserMetadata(Map(field.persistedId -> Set("value"))))
    testApp.txManager.withTransaction(testApp.DAO.stats.add(Stat(isolationDimension, 0)))

    switchContextRepo(first)

    val rows = List(
      ScopedRow(
        "asset",
        testApp.DAO.asset,
        FieldConst.ID -> asset.persistedId,
        FieldConst.Asset.FILENAME -> "changed",
        Some(FieldConst.Asset.SIZE_BYTES)),
      ScopedRow("folder", testApp.DAO.folder, FieldConst.ID -> folder.persistedId, FieldConst.Folder.NAME -> "changed"),
      ScopedRow("album", testApp.DAO.album, FieldConst.ID -> album.persistedId, FieldConst.Album.NAME -> "changed"),
      ScopedRow(
        "location",
        testApp.DAO.location,
        FieldConst.ID -> location.persistedId,
        FieldConst.Location.NAME -> "changed",
        Some(FieldConst.Location.LATITUDE)),
      ScopedRow(
        "person",
        testApp.DAO.person,
        FieldConst.ID -> person.persistedId,
        FieldConst.Person.IS_HIDDEN -> true,
        Some(FieldConst.Person.NUM_OF_FACES)),
      ScopedRow("face", testApp.DAO.face, FieldConst.ID -> face.persistedId, FieldConst.Face.X1 -> 99, Some(FieldConst.Face.X1)),
      ScopedRow(
        "metadata_field",
        testApp.DAO.metadataField,
        FieldConst.ID -> field.persistedId,
        FieldConst.MetadataField.NAME -> "changed"),
      ScopedRow("stats", testApp.DAO.stats, FieldConst.Stat.DIMENSION -> isolationDimension, FieldConst.Stat.DIM_VAL -> 7L)
    )

    TwoRepositories(first, second, rows)
  }

  /** Every row of every scoped table in the repository, each as text, read by hand-written SQL that no scoping touches */
  private def rowsOf(repository: Repository): Map[String, List[String]] =
    testApp.txManager.asReadOnly {
      scopedTables.map {
        table =>
          val rows = query(s"SELECT * FROM $table WHERE repository_id = ?", repository.persistedId)
          table -> rows.map(asText).sorted
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

  /** The ID of the second repository's row of the table */
  private def idOf(repos: TwoRepositories, table: String): String =
    repos.rows.find(_.table == table).flatMap(_.id).get

  /** The DAO's generic `query`; an asset is only ever queried through a view, of which `queryAll` reads every asset */
  private def queryOf(row: ScopedRow, q: Query): QueryResult[?] = row.dao match {
    case assets: AssetDao => assets.queryAll(q)
    case dao => dao.query(q)
  }

  test("A scoped DAO's generic reads find nothing of another repository") {

    /**
     * Setup:
     *
     * Two repositories, the second holding a row of every scoped table that a DAO reads: an asset, a folder, an album, a
     * Location, a Person, a Face, a metadata field and a stat of a dimension only it has.
     *
     * Assertions:
     *
     * From the first repository, each DAO's `getById`, `getByIds`, `getOneByQuery` and `query`, given the second repository's ID
     * (or the stat's dimension), find nothing: the reads by one row are `NotFoundException`, the reads of many are empty. From
     * the second repository the same reads find each row.
     */
    val repos = twoRepositories()

    testApp.txManager.asReadOnly {
      repos.rows.foreach {
        row =>
          withClue(s"${row.table}: ") {
            row.id.foreach {
              id =>
                intercept[NotFoundException](row.dao.getById(id))
                row.dao.getByIds(Set(id)) shouldBe empty
            }
            intercept[NotFoundException](row.dao.getOneByQuery(row.keyQuery))
            queryOf(row, row.keyQuery).records shouldBe empty
          }
      }
    }

    switchContextRepo(repos.second)
    testApp.txManager.asReadOnly {
      repos.rows.foreach {
        row =>
          withClue(s"${row.table}: ") {
            row.id.foreach {
              id =>
                row.dao.getById(id)
                row.dao.getByIds(Set(id)) should have size 1
            }
            row.dao.getOneByQuery(row.keyQuery)
            queryOf(row, row.keyQuery).records should have size 1
          }
      }
    }
  }

  test("A scoped DAO's generic writes change nothing of another repository") {

    /**
     * Setup:
     *
     * Two repositories, the second holding a row of every scoped table: an asset with its Search document, a folder, an album and
     * a Location each holding the asset, a Person with a Face on it, a metadata field with a value on it, and its stats.
     *
     * Assertions:
     *
     * From the first repository, each DAO's `updateById`, `updateByQuery`, `increment` (where the table has a counter),
     * `deleteById` and `deleteByQuery`, given the second repository's ID (or the stat's dimension), change no row and report 0.
     * Read again, every row of every scoped table of the second repository is as it was: the same values, counters and
     * memberships.
     */
    val repos = twoRepositories()
    val before = rowsOf(repos.second)

    testApp.txManager.withTransaction {
      repos.rows.foreach {
        row =>
          withClue(s"${row.table}: ") {
            row.id.foreach(id => row.dao.updateById(id, Map(row.update)) shouldBe 0)
            row.dao.updateByQuery(row.keyQuery, Map(row.update)) shouldBe 0
            for (id <- row.id; counter <- row.counter) row.dao.increment(id, counter) shouldBe 0
          }
      }

      repos.rows.foreach {
        row =>
          withClue(s"${row.table}: ") {
            row.id.foreach(id => row.dao.deleteById(id) shouldBe 0)
            row.dao.deleteByQuery(row.keyQuery) shouldBe 0
          }
      }
    }

    rowsOf(repos.second) shouldEqual before
  }

  test("An update or a delete by an empty query is refused") {

    /**
     * Setup:
     *
     * Two repositories, the second holding a row of every scoped table.
     *
     * Assertions:
     *
     * Each scoped DAO refuses `updateByQuery` and `deleteByQuery` with a query that has no parameters, and so does a service. No
     * row of either repository changes.
     *
     * Edge cases:
     *
     * The account DAO, whose table has no repository, refuses an empty update too: the refusal is not the scoping's.
     */
    val repos = twoRepositories()
    val firstBefore = rowsOf(repos.first)
    val secondBefore = rowsOf(repos.second)

    testApp.txManager.withTransaction {
      intercept[IllegalArgumentException] {
        testApp.DAO.user.updateByQuery(new Query(), Map(FieldConst.User.NAME -> "changed"))
      }

      repos.rows.foreach {
        row =>
          withClue(s"${row.table}: ") {
            intercept[IllegalArgumentException](row.dao.updateByQuery(new Query(), Map(row.update)))
            intercept[IllegalArgumentException](row.dao.deleteByQuery(new Query()))
          }
      }
    }

    intercept[IllegalArgumentException] {
      testApp.service.album.updateByQuery(new Query(), Map(FieldConst.Album.NAME -> "changed"))
    }
    intercept[IllegalArgumentException](testApp.service.album.deleteByQuery(new Query()))

    rowsOf(repos.first) shouldEqual firstBefore
    rowsOf(repos.second) shouldEqual secondBefore
  }

  test("A scoped DAO with no repository in the context refuses every generic read and write") {

    /**
     * Setup:
     *
     * Two repositories, the second holding a row of every scoped table, and no repository in the context.
     *
     * Assertions:
     *
     * Each scoped DAO's `getById`, `getByIds`, `getOneByQuery`, `query`, `updateById`, `updateByQuery`, `increment`, `deleteById`
     * and `deleteByQuery` throw for the missing repository, and the second repository's rows are as they were.
     *
     * Edge cases:
     *
     * The DAOs of the tables with no repository (accounts, repositories) still read by ID with none in the context.
     */
    val repos = twoRepositories()
    val before = rowsOf(repos.second)

    def refused(f: => Any): Unit =
      intercept[RuntimeException](f).getMessage shouldBe "No repository in context"

    testApp.txManager.withTransaction {
      RequestContext.repository.withValue(None) {
        repos.rows.foreach {
          row =>
            withClue(s"${row.table}: ") {
              refused(row.dao.getOneByQuery(row.keyQuery))
              refused(queryOf(row, row.keyQuery))
              refused(row.dao.updateByQuery(row.keyQuery, Map(row.update)))
              refused(row.dao.deleteByQuery(row.keyQuery))
              row.id.foreach {
                id =>
                  refused(row.dao.getById(id))
                  refused(row.dao.getByIds(Set(id)))
                  refused(row.dao.updateById(id, Map(row.update)))
                  row.counter.foreach(counter => refused(row.dao.increment(id, counter)))
                  refused(row.dao.deleteById(id))
              }
            }
        }

        testApp.DAO.user.getById(testContext.user.persistedId).persistedId shouldBe testContext.user.persistedId
        testApp.DAO.repository.getById(repos.second.persistedId).persistedId shouldBe repos.second.persistedId
      }
    }

    rowsOf(repos.second) shouldEqual before
  }

  test("A service's write by ID of another repository's row, or of an unknown one, is not found") {

    /**
     * Setup:
     *
     * Two repositories, the second holding an asset, a folder, an album, a Location and a Person.
     *
     * Assertions:
     *
     * From the first repository, `updateById`, `deleteById` and `increment` of the asset, album, Location and Person services,
     * and `updateById` of the folder service, given the second repository's IDs, are `NotFoundException`, and so are they given
     * an ID that no row has. Every row of the second repository is as it was.
     */
    val repos = twoRepositories()
    val before = rowsOf(repos.second)
    val idOf: Map[String, String] = repos.rows.flatMap(row => row.id.map(row.table -> _)).toMap

    val services: List[(String, BaseService[?], (String, Any), Option[String])] = List(
      ("asset", testApp.service.asset, FieldConst.Asset.FILENAME -> "changed", Some(FieldConst.Asset.SIZE_BYTES)),
      ("folder", testApp.service.folder, FieldConst.Folder.NAME -> "changed", None),
      ("album", testApp.service.album, FieldConst.Album.NAME -> "changed", None),
      ("location", testApp.service.location, FieldConst.Location.NAME -> "changed", Some(FieldConst.Location.LATITUDE)),
      ("person", testApp.service.person, FieldConst.Person.IS_HIDDEN -> true, Some(FieldConst.Person.NUM_OF_FACES))
    )

    services.foreach {
      case (table, service, update, counter) =>
        List(idOf(table), "unknown").foreach {
          id =>
            withClue(s"$table [$id]: ") {
              intercept[NotFoundException](service.updateById(id, Map(update)))
              counter.foreach(field => intercept[NotFoundException](service.increment(id, field)))
              // A folder is never deleted by ID: the library service recycles it
              if (table != "folder") intercept[NotFoundException](service.deleteById(id))
            }
        }
    }

    rowsOf(repos.second) shouldEqual before
  }

  test("Another repository's album can be neither deleted nor emptied, and its memberships are not read") {

    /**
     * Setup:
     *
     * Two repositories, the second holding an album with an asset in it.
     *
     * Assertions:
     *
     * From the first repository, the album DAO's membership removal, given the second repository's album and asset, removes
     * nothing; through the album service, deleting the album and removing the asset from it are `NotFoundException`, and its
     * asset IDs read as none. Every row of the second repository is as it was: the album and its membership stay.
     */
    val repos = twoRepositories()
    val before = rowsOf(repos.second)
    val albumId = idOf(repos, "album")
    val assetId = idOf(repos, "asset")

    testApp.txManager.withTransaction(testApp.DAO.album.removeAssets(albumId, Set(assetId))) shouldBe 0
    intercept[NotFoundException](testApp.service.album.removeAssets(albumId, Set(assetId)))
    intercept[NotFoundException](testApp.service.album.deleteById(albumId))
    testApp.service.album.getAssetIds(albumId) shouldBe empty

    rowsOf(repos.second) shouldEqual before
  }

  test("Another repository's Location is not emptied, and its memberships are not read") {

    /**
     * Setup:
     *
     * Two repositories, the second holding a Location with an asset in it.
     *
     * Assertions:
     *
     * From the first repository, the Location DAO's membership removal, given the second repository's Location and asset, removes
     * nothing; through the Location service, removing the asset from it is `NotFoundException`, and its asset IDs read as none.
     * Every row of the second repository is as it was: the Location and its membership stay.
     */
    val repos = twoRepositories()
    val before = rowsOf(repos.second)
    val locationId = idOf(repos, "location")
    val assetId = idOf(repos, "asset")

    testApp.txManager.withTransaction(testApp.DAO.location.removeAssets(locationId, Set(assetId))) shouldBe 0
    intercept[NotFoundException](testApp.service.location.removeAssets(locationId, Set(assetId)))
    testApp.service.location.getAssetIds(locationId) shouldBe empty

    rowsOf(repos.second) shouldEqual before
  }

  test("Another repository's folder is neither renamed, moved, moved into nor deleted, and its tree is not read") {

    /**
     * Setup:
     *
     * Two repositories, the second holding a folder with a child folder under it, and the first a folder of its own.
     *
     * Assertions:
     *
     * From the first repository, the children, the children all the way down and the ancestors of the second repository's
     * folders, its root included, read as none. Renaming its folder, moving it, and moving its root folder are
     * `NotFoundException`; moving the first repository's folder into either of them is refused as a move into a folder that does
     * not exist (`ValidationException`); deleting its folder is `NotFoundException`. Every row of both repositories is as it was.
     */
    val repos = twoRepositories()
    val theirs = idOf(repos, "folder")
    val theirRoot = repos.second.rootFolderId
    switchContextRepo(repos.second)
    val theirChild = testApp.service.folder.add("their child", Some(theirs))
    switchContextRepo(repos.first)
    val mine = testApp.service.folder.add("mine")
    val firstBefore = rowsOf(repos.first)
    val secondBefore = rowsOf(repos.second)

    List(theirs, theirRoot).foreach {
      folderId =>
        withClue(s"[$folderId]: ") {
          testApp.service.folder.getChildren(folderId) shouldBe empty
          testApp.service.folder.getChildrenRecursive(folderId) shouldBe empty
        }
    }
    testApp.service.folder.getAncestors(theirChild.persistedId) shouldBe empty

    intercept[NotFoundException](testApp.service.folder.rename(theirs, "changed"))
    intercept[NotFoundException](testApp.service.folder.move(theirs, mine.persistedId))
    intercept[NotFoundException](testApp.service.folder.move(theirRoot, mine.persistedId))
    intercept[ValidationException](testApp.service.folder.move(mine.persistedId, theirs))
    intercept[ValidationException](testApp.service.folder.move(mine.persistedId, theirRoot))
    intercept[NotFoundException](testApp.service.library.deleteFolderById(theirs))

    rowsOf(repos.first) shouldEqual firstBefore
    rowsOf(repos.second) shouldEqual secondBefore
  }

  test("Another repository's Person is neither read nor changed, and a merge with them is not found") {

    /**
     * Setup:
     *
     * Two repositories, the second holding a Person with a Face, and the first a Person with a Face of their own.
     *
     * Assertions:
     *
     * From the first repository, the second repository's Person is not found by ID and their Faces read as none. Hiding, showing,
     * marking as a bad match, renaming them and setting their cover, given them, are `NotFoundException`, and so is a merge with
     * them, as the source or as the destination. Every row of both repositories is as it was: both people, their Faces and their
     * counts.
     */
    val repos = twoRepositories()
    val theirId = idOf(repos, "person")
    val theirs = Person(id = Some(theirId))
    val myAsset = testContext.insertAsset(testContext.makeAsset(repository = Some(repos.first)))
    val added = testApp.service.person.addPerson(Person())
    val myFace = testContext.addTestFace(added, myAsset, TestContext.unitVector(2))
    val mine: Person = testApp.service.person.getById(added.persistedId)
    val firstBefore = rowsOf(repos.first)
    val secondBefore = rowsOf(repos.second)

    intercept[NotFoundException](testApp.service.person.getById(theirId))
    testApp.service.person.getPersonFaces(theirId) shouldBe empty

    intercept[NotFoundException](testApp.service.person.setVisibility(theirs, isHidden = true))
    intercept[NotFoundException](testApp.service.person.setVisibility(theirs, isHidden = false))
    intercept[NotFoundException](testApp.service.person.markAsBadMatch(theirs))
    intercept[NotFoundException](testApp.service.person.updateName(theirs, "Changed"))
    intercept[NotFoundException](testApp.service.person.setFaceAsCover(theirs, myFace))
    intercept[NotFoundException](testApp.service.person.merge(dest = mine, source = theirs))
    intercept[NotFoundException](testApp.service.person.merge(dest = theirs, source = mine))

    rowsOf(repos.first) shouldEqual firstBefore
    rowsOf(repos.second) shouldEqual secondBefore
  }

  test("Another repository's asset metadata and metadata field are neither read nor changed") {

    /**
     * Setup:
     *
     * Two repositories, the second holding an asset with a value of its own metadata field, and the first a metadata field of its
     * own.
     *
     * Assertions:
     *
     * From the first repository, the second repository's asset's metadata is not found, read or updated (with a value of the
     * first repository's field), and its field is not found by ID; deleting the field deletes nothing. Every row of the second
     * repository is as it was: the asset's metadata and the field.
     */
    val repos = twoRepositories()
    val assetId = idOf(repos, "asset")
    val fieldId = idOf(repos, "metadata_field")
    val myField = testApp.service.metadata.addField(UserMetadataField(name = "mine", fieldType = FieldType.KEYWORD))
    val before = rowsOf(repos.second)

    intercept[NotFoundException](testApp.service.metadata.getMetadata(assetId))
    intercept[NotFoundException] {
      testApp.service.metadata.updateMetadata(assetId, UserMetadata(Map(myField.persistedId -> Set("changed"))))
    }
    intercept[NotFoundException](testApp.service.metadata.getFieldById(fieldId))
    testApp.service.metadata.deleteFieldById(fieldId) shouldBe 0

    rowsOf(repos.second) shouldEqual before
  }
}
