package altitude.core.integration

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.models.SettingKind
import altitude.core.models.Settings

/**
 * The Settings: the seeds of the `setting` table, the one typed snapshot a save replaces, the validation that writes nothing when
 * any field fails, and the Restart settings whose saved value waits for the next start.
 */
@DoNotDiscover class SettingsServiceTests(override val testApp: Altitude) extends IntegrationTestCore with TestFocus {

  /** The seeds the plan names, which the table's default_value column carries */
  private val seeds: Map[String, String] = Map(
    Settings.FACE_YUNET_CONFIDENCE_THRESHOLD -> "0.85",
    Settings.FACE_YUNET_NMS_THRESHOLD -> "0.3",
    Settings.FACE_DETECTION_BOUNDING_BOX_SIZE -> "1280",
    Settings.FACE_DETECTION_MIN_FACE_SIZE -> "50",
    Settings.FACE_RECOGNITION_COSINE_DISTANCE_THRESHOLD -> "0.55",
    Settings.FACE_RECOGNITION_MATCH_COUNT -> "5",
    Settings.FACE_RECOGNITION_HNSW_EF_SEARCH -> "100",
    Settings.FACE_QUALITY_ENROLL_THRESHOLD -> "18.0",
    Settings.FACE_QUALITY_KEEP_THRESHOLD -> "15.0",
    Settings.VIDEO_FACES_SAMPLE_INTERVAL_MS -> "1000",
    Settings.VIDEO_FACES_MAX_SAMPLED_FRAMES -> "120",
    Settings.VIDEO_FACES_MIN_CLUSTER_FRAMES -> "2",
    Settings.VIDEO_PREVIEW_MIN_LUMINANCE -> "40",
    Settings.MAP_TILE_URL -> "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    Settings.MAP_TILE_ATTRIBUTION -> "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors",
    Settings.MAP_GEOCODER_ENABLED -> "false",
    Settings.MAP_GEOCODER_URL -> "https://nominatim.openstreetmap.org/search",
    Settings.DB_POSTGRES_READ_STATEMENT_TIMEOUT -> "30",
    Settings.PIPELINE_SHUTDOWN_TIMEOUT -> "30",
    Settings.IMPORT_PARALLELISM -> "0"
  )

  /** The stored value of every Setting */
  private def storedValues: Map[String, String] =
    testApp.txManager.asReadOnly {
      query("SELECT name, value FROM setting").map(rec => rec("name").toString -> rec("value").toString).toMap
    }

  // Every test leaves the seeds behind, since the other suites read the snapshot. A test may have changed a row behind the
  // snapshot's back, so the rows are read first, and the seeds saved over what is really there.
  override def afterEach(): Unit = {
    testApp.settings.load()
    testApp.settings.save(seeds)
    super.afterEach()
  }

  test("The seeds load: every Setting has a row whose value is its default, and the snapshot carries the seeds") {
    val rows = testApp.txManager.asReadOnly {
      query("SELECT name, value, default_value FROM setting")
    }
    rows.map(_("name").toString).toSet shouldBe Settings.all.map(_.name).toSet
    rows.foreach(rec => rec("value").toString shouldBe rec("default_value").toString)
    rows.foreach(rec => rec("default_value").toString shouldBe seeds(rec("name").toString))
    Settings.all.foreach(d => testApp.settings.current.default(d.name) shouldBe seeds(d.name))

    val current = testApp.settings.current
    current.faceYunetConfidenceThreshold shouldBe 0.85
    current.faceYunetNmsThreshold shouldBe 0.3
    current.faceDetectionBoundingBoxSize shouldBe 1280
    current.faceDetectionMinFaceSize shouldBe 50
    current.faceRecognitionCosineDistanceThreshold shouldBe 0.55
    current.faceRecognitionMatchCount shouldBe 5
    current.faceRecognitionHnswEfSearch shouldBe 100
    current.faceQualityEnrollThreshold shouldBe 18.0
    current.faceQualityKeepThreshold shouldBe 15.0
    current.videoFacesSampleIntervalMs shouldBe 1000
    current.videoFacesMaxSampledFrames shouldBe 120
    current.videoFacesMinClusterFrames shouldBe 2
    current.videoPreviewMinLuminance shouldBe 40
    current.mapTileUrl shouldBe "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    current.mapTileAttribution shouldBe "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"
    current.mapGeocoderEnabled shouldBe false
    current.mapGeocoderUrl shouldBe "https://nominatim.openstreetmap.org/search"
    current.dbPostgresReadStatementTimeout shouldBe 30
    current.pipelineShutdownTimeout shouldBe 30
    current.importParallelism shouldBe 0
  }

  test("A save replaces the snapshot and the rows, and a value not submitted keeps its current value") {
    testApp.settings.save(
      Map(
        Settings.FACE_DETECTION_MIN_FACE_SIZE -> "60",
        Settings.MAP_GEOCODER_ENABLED -> "true",
        Settings.MAP_TILE_ATTRIBUTION -> ""))

    val current = testApp.settings.current
    current.faceDetectionMinFaceSize shouldBe 60
    current.mapGeocoderEnabled shouldBe true
    current.mapTileAttribution shouldBe ""
    // Not submitted, so unchanged
    current.faceDetectionBoundingBoxSize shouldBe 1280

    val stored = storedValues
    stored(Settings.FACE_DETECTION_MIN_FACE_SIZE) shouldBe "60"
    stored(Settings.MAP_GEOCODER_ENABLED) shouldBe "true"
    stored(Settings.MAP_TILE_ATTRIBUTION) shouldBe ""
    stored(Settings.FACE_DETECTION_BOUNDING_BOX_SIZE) shouldBe "1280"

    // A reload reads the saved values back
    testApp.settings.load()
    testApp.settings.current.faceDetectionMinFaceSize shouldBe 60
  }

  test("An invalid set writes nothing and names every failing field") {
    val before = storedValues
    val values = Map(
      Settings.FACE_YUNET_CONFIDENCE_THRESHOLD -> "1.5", // above 1
      Settings.FACE_YUNET_NMS_THRESHOLD -> "0", // not above 0
      Settings.FACE_DETECTION_MIN_FACE_SIZE -> "0", // below 1
      Settings.FACE_RECOGNITION_MATCH_COUNT -> "2.5", // not an integer
      Settings.VIDEO_PREVIEW_MIN_LUMINANCE -> "256", // above 255
      Settings.MAP_TILE_URL -> "https://tiles.example.org/{z}/{x}.png", // no {y}
      Settings.MAP_GEOCODER_ENABLED -> "yes", // not a boolean
      Settings.MAP_GEOCODER_URL -> "ftp://geocoder.example.org", // not http(s)
      Settings.PIPELINE_SHUTDOWN_TIMEOUT -> "abc", // not a number
      Settings.IMPORT_PARALLELISM -> "-1", // below 0
      "no.such.setting" -> "1"
    )

    val ex = intercept[ValidationException] {
      testApp.settings.save(values)
    }
    ex.errors.keySet shouldBe values.keySet

    // The same errors, without the attempt to save
    testApp.settings.validate(values) shouldBe ex.errors.toMap

    storedValues shouldBe before
    testApp.settings.current.faceDetectionMinFaceSize shouldBe 50
  }

  test("The bounding box is at least the smallest face") {
    val errors = testApp.settings.validate(
      Map(Settings.FACE_DETECTION_BOUNDING_BOX_SIZE -> "100", Settings.FACE_DETECTION_MIN_FACE_SIZE -> "200"))
    errors.keySet shouldBe Set(Settings.FACE_DETECTION_BOUNDING_BOX_SIZE)

    testApp.settings.validate(
      Map(Settings.FACE_DETECTION_BOUNDING_BOX_SIZE -> "200", Settings.FACE_DETECTION_MIN_FACE_SIZE -> "200")) shouldBe empty
  }

  test("The vector index keeps at least four times the match count") {
    val errors = testApp.settings.validate(
      Map(Settings.FACE_RECOGNITION_HNSW_EF_SEARCH -> "39", Settings.FACE_RECOGNITION_MATCH_COUNT -> "10"))
    errors.keySet shouldBe Set(Settings.FACE_RECOGNITION_HNSW_EF_SEARCH)

    testApp.settings.validate(
      Map(Settings.FACE_RECOGNITION_HNSW_EF_SEARCH -> "40", Settings.FACE_RECOGNITION_MATCH_COUNT -> "10")) shouldBe empty
  }

  test("The enroll threshold is at least the keep threshold") {
    val errors = testApp.settings.validate(
      Map(Settings.FACE_QUALITY_ENROLL_THRESHOLD -> "14.9", Settings.FACE_QUALITY_KEEP_THRESHOLD -> "15.0"))
    errors.keySet shouldBe Set(Settings.FACE_QUALITY_ENROLL_THRESHOLD)

    testApp.settings.validate(
      Map(Settings.FACE_QUALITY_ENROLL_THRESHOLD -> "15.0", Settings.FACE_QUALITY_KEEP_THRESHOLD -> "15.0")) shouldBe empty
  }

  test("A Video is sampled for at least as many frames as a cluster needs") {
    val errors = testApp.settings.validate(
      Map(Settings.VIDEO_FACES_MAX_SAMPLED_FRAMES -> "1", Settings.VIDEO_FACES_MIN_CLUSTER_FRAMES -> "2"))
    errors.keySet shouldBe Set(Settings.VIDEO_FACES_MAX_SAMPLED_FRAMES)

    testApp.settings.validate(
      Map(Settings.VIDEO_FACES_MAX_SAMPLED_FRAMES -> "2", Settings.VIDEO_FACES_MIN_CLUSTER_FRAMES -> "2")) shouldBe empty
  }

  test("A cross-field rule is not judged on a field that failed on its own") {
    val errors = testApp.settings.validate(
      Map(Settings.FACE_QUALITY_ENROLL_THRESHOLD -> "x", Settings.FACE_QUALITY_KEEP_THRESHOLD -> "15.0"))
    errors.keySet shouldBe Set(Settings.FACE_QUALITY_ENROLL_THRESHOLD)
  }

  test("A saved Restart setting reports its saved and running values until the next start; a Live setting never does") {
    testApp.settings.pendingRestart shouldBe empty

    testApp.settings.save(Map(Settings.IMPORT_PARALLELISM -> "3", Settings.FACE_DETECTION_MIN_FACE_SIZE -> "60"))

    val pending = testApp.settings.pendingRestart
    pending.map(_.setting.name) shouldBe List(Settings.IMPORT_PARALLELISM)
    pending.head.setting.kind shouldBe SettingKind.Restart
    pending.head.saved shouldBe "3"
    pending.head.running shouldBe "0"
    // The running value is the one loaded at startup
    testApp.settings.atStartup.importParallelism shouldBe 0
    testApp.settings.current.importParallelism shouldBe 3

    // Saved back to the running value, nothing waits for a restart
    testApp.settings.save(Map(Settings.IMPORT_PARALLELISM -> "0"))
    testApp.settings.pendingRestart shouldBe empty
  }

  test("The PostgreSQL-only Settings are the two the plan names") {
    Settings.all.filter(_.engine.isDefined).map(_.name).toSet shouldBe
      Set(Settings.FACE_RECOGNITION_HNSW_EF_SEARCH, Settings.DB_POSTGRES_READ_STATEMENT_TIMEOUT)
    Settings.all.filter(_.engine.isDefined).foreach(_.engine shouldBe Some(Const.DbEngineName.POSTGRES))
  }

  test("A submitted value is trimmed, and a number spelled differently is not a change") {
    val before = storedValues
    testApp.settings.save(
      Map(
        Settings.FACE_YUNET_CONFIDENCE_THRESHOLD -> "0.850",
        Settings.IMPORT_PARALLELISM -> " 0 ",
        Settings.FACE_DETECTION_MIN_FACE_SIZE -> " 60"))

    // The two spelled differently were no change, so their rows keep the seed's spelling
    val stored = storedValues
    stored(Settings.FACE_YUNET_CONFIDENCE_THRESHOLD) shouldBe before(Settings.FACE_YUNET_CONFIDENCE_THRESHOLD)
    stored(Settings.IMPORT_PARALLELISM) shouldBe before(Settings.IMPORT_PARALLELISM)
    testApp.settings.pendingRestart shouldBe empty
    // The changed one is stored trimmed
    stored(Settings.FACE_DETECTION_MIN_FACE_SIZE) shouldBe "60"
    testApp.settings.current.faceDetectionMinFaceSize shouldBe 60
  }

  test("A save that changes nothing writes nothing") {
    // The row is changed behind the snapshot's back, so a write would show
    testApp.txManager.withTransaction {
      update("UPDATE setting SET value = ? WHERE name = ?", "60", Settings.FACE_DETECTION_MIN_FACE_SIZE)
    }
    testApp.settings.save(Map(Settings.FACE_DETECTION_MIN_FACE_SIZE -> "50"))
    storedValues(Settings.FACE_DETECTION_MIN_FACE_SIZE) shouldBe "60"
  }

  test("A load fails on an invalid stored value and warns about a row the registry does not know") {
    testApp.txManager.withTransaction {
      update("UPDATE setting SET value = ? WHERE name = ?", "abc", Settings.FACE_DETECTION_MIN_FACE_SIZE)
    }
    intercept[IllegalStateException] {
      testApp.settings.load()
    }
    // The snapshot is the one before the failed load
    testApp.settings.current.faceDetectionMinFaceSize shouldBe 50

    testApp.txManager.withTransaction {
      update("UPDATE setting SET value = ? WHERE name = ?", "50", Settings.FACE_DETECTION_MIN_FACE_SIZE)
      update("INSERT INTO setting (name, value, default_value) VALUES (?, ?, ?)", "no.such.setting", "1", "1")
    }
    try testApp.settings.load()
    finally
      testApp.txManager.withTransaction {
        update("DELETE FROM setting WHERE name = ?", "no.such.setting")
      }
  }

  test("The upper bound of a threshold is inclusive, and a URL needs a host") {
    testApp.settings.validate(Map(Settings.FACE_YUNET_CONFIDENCE_THRESHOLD -> "1")) shouldBe empty
    testApp.settings.validate(Map(Settings.MAP_GEOCODER_URL -> "http://")).keySet shouldBe Set(Settings.MAP_GEOCODER_URL)
    testApp.settings.validate(Map(Settings.MAP_GEOCODER_URL -> "https:foo")).keySet shouldBe Set(Settings.MAP_GEOCODER_URL)
  }

  test("The DAO refuses to update a Setting that has no row") {
    intercept[NotFoundException] {
      testApp.txManager.withTransaction {
        testApp.DAO.setting.updateValues(Map("no.such.setting" -> "1"))
      }
    }
  }
}
