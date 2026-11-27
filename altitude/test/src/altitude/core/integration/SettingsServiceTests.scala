package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.NominatimStub
import altitude.test.TestFocus
import altitude.test.TestSettings
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.models.GeocoderResult
import altitude.core.models.SceneModel
import altitude.core.models.SettingGroup
import altitude.core.models.SettingKind
import altitude.core.models.Settings
import altitude.core.models.SettingType

/**
 * The Settings: the seeded `setting` rows, the typed snapshot a save replaces, validation that writes nothing when any field
 * fails, and the Restart settings whose saved value waits for the next start.
 */
@DoNotDiscover class SettingsServiceTests(override val testApp: Altitude) extends IntegrationTestCore with TestFocus {

  private val seeds: Map[String, String] = TestSettings.seeds

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

    /**
     * Setup:
     *
     * The test database's `setting` table, every row at its seed.
     *
     * Assertions:
     *
     * There is one row for each Setting of the registry and no other. Each row's value is its default, and each default is the
     * seed of `all.sql`. The snapshot carries the same defaults, and its typed accessors read the seeded values.
     */
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
    current.sequenceMaxGapMs shouldBe 60000
    current.sequenceDinov2MinSimilarity shouldBe 0.8
    current.sequenceSiglip2MinSimilarity shouldBe 0.9
    current.sequenceMinSimilarity(SceneModel.Dinov2) shouldBe 0.8
    current.sequenceMinSimilarity(SceneModel.Siglip2) shouldBe 0.9
    current.sequenceMaxHashDistance shouldBe 10
    current.curationBlurRatio shouldBe 0.5
    current.curationFaceQualityGap shouldBe 2.0
    current.curationClippedFraction shouldBe 0.05
    current.curationCrushedFraction shouldBe 0.05
    current.curationDarkLuminance shouldBe 0.15
    current.curationBrightLuminance shouldBe 0.75
    current.curationLuminanceGap shouldBe 0.1
    current.searchDescribeMinProbability shouldBe 0.00009
    current.searchDescribeMaxResults shouldBe 1000
    current.searchDescribeWeakerMatchRatio shouldBe 0.75
    current.mapTileUrl shouldBe "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    current.mapTileAttribution shouldBe "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"
    current.mapGeocoderEnabled shouldBe false
    current.mapGeocoderUrl shouldBe "https://nominatim.openstreetmap.org/search"
    current.dbPostgresReadStatementTimeout shouldBe 30
    current.pipelineShutdownTimeout shouldBe 30
    current.importParallelism shouldBe 0
    current.importHistoryDays shouldBe 90
    current.watchFolderScanInterval shouldBe 10
  }

  test("The Watch folder scan interval is a Live setting of the Operations section, in whole minutes from 2 to 360") {

    /**
     * Setup:
     *
     * The registry's entry of the Watch folder scan interval, and values validated and saved for it.
     *
     * Assertions:
     *
     * It is a Live setting of the Operations section on both engines, labelled in minutes, with a default of 10. A whole number
     * of minutes from 2 to 360 is valid, both bounds included. A saved value applies at once, with no restart waiting.
     *
     * Edge cases:
     *
     * One minute, 361 minutes and a fraction are refused, each saying why. The help says a file is read once seen unchanged for
     * at least 5 seconds, and does not call the interval its rest.
     */
    val name = Settings.WATCH_FOLDER_SCAN_INTERVAL
    val definition = Settings.byName(name)
    definition.group shouldBe SettingGroup.Operations
    definition.kind shouldBe SettingKind.Live
    definition.engine shouldBe None
    definition.label shouldBe "Watch folder scan interval (min)"
    testApp.settings.current.default(name) shouldBe "10"
    testApp.settings.current.watchFolderScanInterval shouldBe 10

    testApp.settings.validate(Map(name -> "2")) shouldBe empty
    testApp.settings.validate(Map(name -> "360")) shouldBe empty
    testApp.settings.validate(Map(name -> "1")) shouldBe Map(name -> "Must be at least 2")
    testApp.settings.validate(Map(name -> "361")) shouldBe Map(name -> "Must be at most 360")
    testApp.settings.validate(Map(name -> "7.5")) shouldBe Map(name -> "Must be a whole number")

    definition.help should startWith("Minutes between two scans of the Watch folders, after the scan at startup.")
    definition.help should include("seen unchanged for at least 5 seconds")
    definition.help should include("second scan after it appears")
    (definition.help should not).include("rests")

    testApp.settings.save(Map(name -> "15"))
    testApp.settings.current.watchFolderScanInterval shouldBe 15
    testApp.settings.pendingRestart shouldBe empty
  }

  test("The Import history Setting is a Live setting of the Operations section, in whole days from 0") {

    /**
     * Setup:
     *
     * The registry's entry of the Import history Setting, and values validated and saved for it.
     *
     * Assertions:
     *
     * It is a Live setting of the Operations section on both engines. A whole number of days from 0 up is valid, 0 keeping the
     * history forever. A saved value applies at once, with no restart waiting.
     *
     * Edge cases:
     *
     * A negative number and a fraction of a day are refused.
     */
    val name = Settings.IMPORT_HISTORY_DAYS
    val definition = Settings.byName(name)
    definition.group shouldBe SettingGroup.Operations
    definition.kind shouldBe SettingKind.Live
    definition.engine shouldBe None

    testApp.settings.validate(Map(name -> "0")) shouldBe empty
    testApp.settings.validate(Map(name -> "3650")) shouldBe empty
    testApp.settings.validate(Map(name -> "-1")) shouldBe Map(name -> "Must be at least 0")
    testApp.settings.validate(Map(name -> "1.5")) shouldBe Map(name -> "Must be a whole number")

    testApp.settings.save(Map(name -> "30"))
    testApp.settings.current.importHistoryDays shouldBe 30
    testApp.settings.pendingRestart shouldBe empty
  }

  test("A save replaces the snapshot and the rows, and a value not submitted keeps its current value") {

    /**
     * Setup:
     *
     * The seeded Settings, and a save of three of them: the smallest face size, the geocoder switch and an empty tile
     * attribution.
     *
     * Assertions:
     *
     * The snapshot and the rows both hold the three saved values, and a reload reads them back. A Setting not submitted keeps its
     * value in both.
     *
     * Edge cases:
     *
     * An empty text is a value of its own: the attribution is saved empty, not refused.
     */
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

    /**
     * Setup:
     *
     * The seeded Settings, and a save of values that each break their Setting's rule (a bound, a whole number, a number, a flag,
     * a tile URL's placeholders, an http URL), along with a name the registry does not know.
     *
     * Assertions:
     *
     * The save is refused with an error for every submitted field, the same errors that validation alone gives. No row changes,
     * and the snapshot keeps its values.
     */
    val before = storedValues
    val values = Map(
      Settings.FACE_YUNET_CONFIDENCE_THRESHOLD -> "1.5", // above 1
      Settings.FACE_YUNET_NMS_THRESHOLD -> "0", // not above 0
      Settings.FACE_DETECTION_MIN_FACE_SIZE -> "0", // below 1
      Settings.FACE_RECOGNITION_MATCH_COUNT -> "2.5", // not an integer
      Settings.VIDEO_PREVIEW_MIN_LUMINANCE -> "256", // above 255
      Settings.SEQUENCE_MAX_GAP_MS -> "0", // below 1
      Settings.SEQUENCE_DINOV2_MIN_SIMILARITY -> "1.5", // above 1
      Settings.SEQUENCE_SIGLIP2_MIN_SIMILARITY -> "-1.5", // below -1
      Settings.SEQUENCE_MAX_HASH_DISTANCE -> "65", // above 64, the bits of a hash
      Settings.CURATION_BLUR_RATIO -> "1.5", // above 1
      Settings.CURATION_FACE_QUALITY_GAP -> "-1", // below 0
      Settings.CURATION_CLIPPED_FRACTION -> "1.5", // above 1
      Settings.CURATION_CRUSHED_FRACTION -> "-0.1", // below 0
      Settings.CURATION_DARK_LUMINANCE -> "dark", // not a number
      Settings.CURATION_BRIGHT_LUMINANCE -> "1.1", // above 1
      Settings.CURATION_LUMINANCE_GAP -> "-0.1", // below 0
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

    /**
     * Setup:
     *
     * A bounding box size under the smallest face size, then one equal to it.
     *
     * Assertions:
     *
     * The smaller box is refused, on the bounding box alone. A box equal to the smallest face is valid.
     */
    val errors = testApp.settings.validate(
      Map(Settings.FACE_DETECTION_BOUNDING_BOX_SIZE -> "100", Settings.FACE_DETECTION_MIN_FACE_SIZE -> "200"))
    errors.keySet shouldBe Set(Settings.FACE_DETECTION_BOUNDING_BOX_SIZE)

    testApp.settings.validate(
      Map(Settings.FACE_DETECTION_BOUNDING_BOX_SIZE -> "200", Settings.FACE_DETECTION_MIN_FACE_SIZE -> "200")) shouldBe empty
  }

  test("The hash distance is capped at the bits of a hash, and an uncapped whole number takes any large value") {

    /**
     * Setup:
     *
     * Hash distances at and past the 64 bits of a hash, and a Sequence time gap of an hour.
     *
     * Assertions:
     *
     * A distance of 64 is valid and 65 is refused, saying why. The time gap, a whole number with a minimum but no maximum, takes
     * the hour.
     */
    testApp.settings.validate(Map(Settings.SEQUENCE_MAX_HASH_DISTANCE -> "64")) shouldBe empty
    testApp.settings.validate(Map(Settings.SEQUENCE_MAX_HASH_DISTANCE -> "65")) shouldBe
      Map(Settings.SEQUENCE_MAX_HASH_DISTANCE -> "Must be at most 64")

    // An hour's gap: Integer(min) alone caps nothing
    testApp.settings.validate(Map(Settings.SEQUENCE_MAX_GAP_MS -> "3600000")) shouldBe empty
  }

  test("The Sequence Settings open the Triage section, Live on both engines, in the order of the screen") {

    /**
     * Setup:
     *
     * The registry's entries of the four Sequence Settings: the time gap, the two similarity thresholds and the hash distance.
     *
     * Assertions:
     *
     * Each is a Live setting of the Triage section on both engines, so each applies to this engine. They are the first Settings
     * of the Triage section, in that order.
     */
    val sequence = List(
      Settings.SEQUENCE_MAX_GAP_MS,
      Settings.SEQUENCE_DINOV2_MIN_SIMILARITY,
      Settings.SEQUENCE_SIGLIP2_MIN_SIMILARITY,
      Settings.SEQUENCE_MAX_HASH_DISTANCE
    )
    sequence.map(Settings.byName).foreach {
      definition =>
        definition.group shouldBe SettingGroup.Triage
        definition.kind shouldBe SettingKind.Live
        definition.engine shouldBe None
    }
    Settings.all.filter(_.group == SettingGroup.Triage).map(_.name).take(sequence.size) shouldBe sequence
    testApp.settings.applicable.map(_.name) should contain allElementsOf sequence
  }

  test("A similarity threshold is a cosine, inclusive of -1 and 1") {

    /**
     * Setup:
     *
     * Values validated for the DINOv2 and the SigLIP 2 similarity thresholds.
     *
     * Assertions:
     *
     * Each threshold takes -1, 0 and 1.
     *
     * Edge cases:
     *
     * A value just past either bound is refused, saying which bound, and a word is refused as no number.
     */
    List(Settings.SEQUENCE_DINOV2_MIN_SIMILARITY, Settings.SEQUENCE_SIGLIP2_MIN_SIMILARITY).foreach {
      name =>
        withClue(name) {
          testApp.settings.validate(Map(name -> "-1")) shouldBe empty
          testApp.settings.validate(Map(name -> "0")) shouldBe empty
          testApp.settings.validate(Map(name -> "1")) shouldBe empty
          testApp.settings.validate(Map(name -> "-1.01")) shouldBe Map(name -> "Must be at least -1")
          testApp.settings.validate(Map(name -> "1.01")) shouldBe Map(name -> "Must be at most 1")
          testApp.settings.validate(Map(name -> "high")) shouldBe Map(name -> "Must be a number")
        }
    }
  }

  test("Saved Sequence thresholds reach their accessors at once, with no restart waiting") {

    /**
     * Setup:
     *
     * Both similarity thresholds and the time gap saved with values other than their seeds.
     *
     * Assertions:
     *
     * The snapshot's accessors, the per-model similarity among them, return the saved values at once, with no restart waiting. A
     * reload reads them back.
     */
    testApp.settings.save(
      Map(
        Settings.SEQUENCE_DINOV2_MIN_SIMILARITY -> "0.7",
        Settings.SEQUENCE_SIGLIP2_MIN_SIMILARITY -> "0.95",
        Settings.SEQUENCE_MAX_GAP_MS -> "120000"
      ))

    val current = testApp.settings.current
    current.sequenceDinov2MinSimilarity shouldBe 0.7
    current.sequenceSiglip2MinSimilarity shouldBe 0.95
    current.sequenceMinSimilarity(SceneModel.Dinov2) shouldBe 0.7
    current.sequenceMinSimilarity(SceneModel.Siglip2) shouldBe 0.95
    current.sequenceMaxGapMs shouldBe 120000
    testApp.settings.pendingRestart shouldBe empty

    // A reload reads the saved values back
    testApp.settings.load()
    testApp.settings.current.sequenceSiglip2MinSimilarity shouldBe 0.95
  }

  test("The Curation thresholds are Live settings of the Triage section, and the fractions are inclusive of 0 and 1") {

    /**
     * Setup:
     *
     * The registry's entries of the seven Curation thresholds, and values validated for them.
     *
     * Assertions:
     *
     * Each is a Live setting of the Triage section on both engines. Each of the six fractions takes 0 and 1 and refuses a value
     * just outside either.
     *
     * Edge cases:
     *
     * The Face quality gap, a difference of embedding norms rather than a fraction, has no upper bound: it takes 30 and refuses
     * only a value under 0.
     */
    val curation = List(
      Settings.CURATION_BLUR_RATIO,
      Settings.CURATION_FACE_QUALITY_GAP,
      Settings.CURATION_CLIPPED_FRACTION,
      Settings.CURATION_CRUSHED_FRACTION,
      Settings.CURATION_DARK_LUMINANCE,
      Settings.CURATION_BRIGHT_LUMINANCE,
      Settings.CURATION_LUMINANCE_GAP
    )
    curation.map(Settings.byName).foreach {
      definition =>
        definition.group shouldBe SettingGroup.Triage
        definition.kind shouldBe SettingKind.Live
        definition.engine shouldBe None
    }

    val fractions = curation.filterNot(_ == Settings.CURATION_FACE_QUALITY_GAP)
    fractions.foreach {
      name =>
        testApp.settings.validate(Map(name -> "0")) shouldBe empty
        testApp.settings.validate(Map(name -> "1")) shouldBe empty
        testApp.settings.validate(Map(name -> "-0.01")) shouldBe Map(name -> "Must be at least 0")
        testApp.settings.validate(Map(name -> "1.01")) shouldBe Map(name -> "Must be at most 1")
    }

    // A gap in Face quality, which is an embedding's norm and not a fraction: no cap
    testApp.settings.validate(Map(Settings.CURATION_FACE_QUALITY_GAP -> "0")) shouldBe empty
    testApp.settings.validate(Map(Settings.CURATION_FACE_QUALITY_GAP -> "30")) shouldBe empty
    testApp.settings.validate(Map(Settings.CURATION_FACE_QUALITY_GAP -> "-0.01")) shouldBe
      Map(Settings.CURATION_FACE_QUALITY_GAP -> "Must be at least 0")
  }

  test("A saved Curation threshold reaches its accessor at once, with no restart waiting") {

    /**
     * Setup:
     *
     * The seven Curation thresholds saved with values other than their seeds.
     *
     * Assertions:
     *
     * Each accessor of the snapshot returns its saved value at once, with no restart waiting.
     */
    testApp.settings.save(
      Map(
        Settings.CURATION_BLUR_RATIO -> "0.4",
        Settings.CURATION_FACE_QUALITY_GAP -> "3.5",
        Settings.CURATION_CLIPPED_FRACTION -> "0.2",
        Settings.CURATION_CRUSHED_FRACTION -> "0.3",
        Settings.CURATION_DARK_LUMINANCE -> "0.1",
        Settings.CURATION_BRIGHT_LUMINANCE -> "0.9",
        Settings.CURATION_LUMINANCE_GAP -> "0.25"
      ))

    val current = testApp.settings.current
    current.curationBlurRatio shouldBe 0.4
    current.curationFaceQualityGap shouldBe 3.5
    current.curationClippedFraction shouldBe 0.2
    current.curationCrushedFraction shouldBe 0.3
    current.curationDarkLuminance shouldBe 0.1
    current.curationBrightLuminance shouldBe 0.9
    current.curationLuminanceGap shouldBe 0.25
    testApp.settings.pendingRestart shouldBe empty
  }

  test(
    "The Describe Settings are Live settings of the Search section: a probability, a limit to 10,000 and a ratio from 0 to 1") {

    /**
     * Setup:
     *
     * The registry's entries of the Describe match threshold, result limit and weaker matches ratio, and values validated and
     * saved for them.
     *
     * Assertions:
     *
     * The three are Live settings of the Search section, which follows Triage, on both engines, with their labels and help and
     * the defaults 0.00009, 1000 and 0.75. The threshold is a probability strictly between 0 and 1, the limit a whole number from
     * 1 to `Const.Search.TOTAL_CAP` (10,000), and the ratio a decimal from 0 to 1. Saved values apply at once, with no restart
     * waiting.
     *
     * Edge cases:
     *
     * A threshold of 0 or 1, and a limit of 0, 10,001 or a fraction, are refused, each saying why. A ratio of 0 or 1 is valid;
     * under 0 or over 1 is refused.
     */
    val threshold = Settings.SEARCH_DESCRIBE_MIN_PROBABILITY
    val limit = Settings.SEARCH_DESCRIBE_MAX_RESULTS
    val ratio = Settings.SEARCH_DESCRIBE_WEAKER_MATCH_RATIO
    List(threshold, limit, ratio).map(Settings.byName).foreach {
      definition =>
        definition.group shouldBe SettingGroup.Search
        definition.kind shouldBe SettingKind.Live
        definition.engine shouldBe None
    }
    SettingGroup.Search.label shouldBe "Search"
    SettingGroup.values.toList.map(_.label) shouldBe
      List("Face detection", "Face recognition and quality", "Video", "Triage", "Search", "Map", "Operations")
    Settings.all.filter(_.group == SettingGroup.Search).map(_.name) shouldBe List(threshold, limit, ratio)
    testApp.settings.applicable.map(_.name) should contain allElementsOf List(threshold, limit, ratio)

    Settings.byName(threshold).label shouldBe "Describe match threshold"
    Settings.byName(threshold).help shouldBe
      "How likely, by the model, a photo must be to match a description to be shown. Lower shows more, weaker matches."
    Settings.byName(limit).label shouldBe "Describe result limit"
    Settings.byName(limit).help shouldBe "The most photos a description shows, best first."
    Settings.byName(ratio).label shouldBe "Describe weaker matches"
    Settings.byName(ratio).help shouldBe
      "A photo that looks less than this fraction as much like a description as the best match does is a weaker match, " +
      "shown after the others under its own header. Higher puts more photos under it; 0 shows no such header."
    testApp.settings.current.default(threshold) shouldBe "0.00009"
    testApp.settings.current.default(limit) shouldBe "1000"
    testApp.settings.current.default(ratio) shouldBe "0.75"

    testApp.settings.validate(Map(threshold -> "0.5")) shouldBe empty
    testApp.settings.validate(Map(threshold -> "0.999999")) shouldBe empty
    testApp.settings.validate(Map(threshold -> "0.0000001")) shouldBe empty
    testApp.settings.validate(Map(threshold -> "0")) shouldBe Map(threshold -> "Must be above 0")
    testApp.settings.validate(Map(threshold -> "-0.1")) shouldBe Map(threshold -> "Must be above 0")
    testApp.settings.validate(Map(threshold -> "1")) shouldBe Map(threshold -> "Must be below 1")
    testApp.settings.validate(Map(threshold -> "1.5")) shouldBe Map(threshold -> "Must be below 1")

    testApp.settings.validate(Map(limit -> "1")) shouldBe empty
    // The most a first page's total counts to: a Describe search's total is exact, so its limit is no more than that
    val cap = Const.Search.TOTAL_CAP
    cap shouldBe 10000
    Settings.byName(limit).valueType shouldBe SettingType.Integer(1, cap)
    testApp.settings.validate(Map(limit -> cap.toString)) shouldBe empty
    testApp.settings.validate(Map(limit -> "0")) shouldBe Map(limit -> "Must be at least 1")
    testApp.settings.validate(Map(limit -> (cap + 1).toString)) shouldBe Map(limit -> s"Must be at most $cap")
    testApp.settings.validate(Map(limit -> "99.5")) shouldBe Map(limit -> "Must be a whole number")

    Settings.byName(ratio).valueType shouldBe SettingType.Decimal(0, 1)
    testApp.settings.validate(Map(ratio -> "0")) shouldBe empty
    testApp.settings.validate(Map(ratio -> "1")) shouldBe empty
    testApp.settings.validate(Map(ratio -> "-0.1")) shouldBe Map(ratio -> "Must be at least 0")
    testApp.settings.validate(Map(ratio -> "1.1")) shouldBe Map(ratio -> "Must be at most 1")

    testApp.settings.save(Map(threshold -> "0.002", limit -> "250", ratio -> "0.65"))
    testApp.settings.current.searchDescribeMinProbability shouldBe 0.002
    testApp.settings.current.searchDescribeMaxResults shouldBe 250
    testApp.settings.current.searchDescribeWeakerMatchRatio shouldBe 0.65
    testApp.settings.pendingRestart shouldBe empty
  }

  test("A decimal Setting is written in plain notation: 0.00009 is stored and read as typed, never with an exponent") {

    /**
     * Setup:
     *
     * The Describe match threshold, whose default of 0.00009 a JVM prints as 9.0E-5, and values validated and saved for it.
     *
     * Assertions:
     *
     * The seeded value and default are the text 0.00009, which the snapshot reads as that number. A smaller plain decimal is
     * saved and, after a reload, read back as typed.
     *
     * Edge cases:
     *
     * An exponent in either case, a hexadecimal number and a type suffix, all numbers to a JVM, are refused with a request for a
     * plain decimal. Infinity, NaN and other words are refused as no number. Nothing refused is written. A leading sign or a
     * digit left out on one side of the point is still a plain decimal.
     */
    val threshold = Settings.SEARCH_DESCRIBE_MIN_PROBABILITY
    storedValues(threshold) shouldBe "0.00009"
    testApp.settings.current.value(threshold) shouldBe "0.00009"
    testApp.settings.current.default(threshold) shouldBe "0.00009"
    testApp.settings.current.searchDescribeMinProbability shouldBe 0.00009

    val refused =
      List("9e-5", "9.0E-5", "9E-5", "1e-1", "0x1p-3", "0.5d", "0.5f").map(_ -> "Must be a plain decimal, with no exponent") ++
        List("NaN", "Infinity", "-Infinity", "high", "").map(_ -> "Must be a number")
    refused.foreach {
      (text, message) =>
        withClue(s"[$text]: ") {
          testApp.settings.validate(Map(threshold -> text)) shouldBe Map(threshold -> message)
          intercept[ValidationException](testApp.settings.save(Map(threshold -> text)))
        }
    }
    storedValues(threshold) shouldBe "0.00009"

    List("+0.5", ".5", "0.").foreach {
      text => withClue(text)(testApp.settings.validate(Map(Settings.SEQUENCE_DINOV2_MIN_SIMILARITY -> text)) shouldBe empty)
    }

    testApp.settings.save(Map(threshold -> "0.000012"))
    storedValues(threshold) shouldBe "0.000012"
    testApp.settings.load()
    testApp.settings.current.value(threshold) shouldBe "0.000012"
    testApp.settings.current.searchDescribeMinProbability shouldBe 0.000012
  }

  test("The vector index keeps at least four times the match count, a rule of PostgreSQL alone") {

    /**
     * Setup:
     *
     * A match count of 10 with a search width of 39 and of 40, and a match count of 30 against the seeded width of 100.
     *
     * Assertions:
     *
     * On PostgreSQL a width under four times the match count is refused on the width, and the width is an applicable Setting.
     * SQLite neither shows nor applies the width, so the same values pass there and a match count of 30 saves.
     */
    val tooNarrow = Map(Settings.FACE_RECOGNITION_HNSW_EF_SEARCH -> "39", Settings.FACE_RECOGNITION_MATCH_COUNT -> "10")
    val wide = Map(Settings.FACE_RECOGNITION_HNSW_EF_SEARCH -> "40", Settings.FACE_RECOGNITION_MATCH_COUNT -> "10")
    val manyCandidates = Map(Settings.FACE_RECOGNITION_MATCH_COUNT -> "30")

    testApp.dataSourceType match {
      case Const.DbEngineName.POSTGRES =>
        testApp.settings.validate(tooNarrow).keySet shouldBe Set(Settings.FACE_RECOGNITION_HNSW_EF_SEARCH)
        testApp.settings.validate(wide) shouldBe empty
        testApp.settings.validate(manyCandidates).keySet shouldBe Set(Settings.FACE_RECOGNITION_HNSW_EF_SEARCH)
        testApp.settings.applicable.map(_.name) should contain(Settings.FACE_RECOGNITION_HNSW_EF_SEARCH)
      case _ =>
        testApp.settings.validate(tooNarrow) shouldBe empty
        testApp.settings.save(manyCandidates)
        testApp.settings.current.faceRecognitionMatchCount shouldBe 30
        testApp.settings.applicable.map(_.name) should not contain Settings.FACE_RECOGNITION_HNSW_EF_SEARCH
    }
  }

  test("The enroll threshold is at least the keep threshold") {

    /**
     * Setup:
     *
     * An enroll threshold just under the keep threshold, then one equal to it.
     *
     * Assertions:
     *
     * The lower enroll threshold is refused, on the enroll threshold alone. An equal one is valid.
     */
    val errors = testApp.settings.validate(
      Map(Settings.FACE_QUALITY_ENROLL_THRESHOLD -> "14.9", Settings.FACE_QUALITY_KEEP_THRESHOLD -> "15.0"))
    errors.keySet shouldBe Set(Settings.FACE_QUALITY_ENROLL_THRESHOLD)

    testApp.settings.validate(
      Map(Settings.FACE_QUALITY_ENROLL_THRESHOLD -> "15.0", Settings.FACE_QUALITY_KEEP_THRESHOLD -> "15.0")) shouldBe empty
  }

  test("A Video is sampled for at least as many frames as a cluster needs") {

    /**
     * Setup:
     *
     * A sampled frame limit under the frames a cluster needs, then one equal to it.
     *
     * Assertions:
     *
     * The lower limit is refused, on the sampled frame limit alone. An equal one is valid.
     */
    val errors = testApp.settings.validate(
      Map(Settings.VIDEO_FACES_MAX_SAMPLED_FRAMES -> "1", Settings.VIDEO_FACES_MIN_CLUSTER_FRAMES -> "2"))
    errors.keySet shouldBe Set(Settings.VIDEO_FACES_MAX_SAMPLED_FRAMES)

    testApp.settings.validate(
      Map(Settings.VIDEO_FACES_MAX_SAMPLED_FRAMES -> "2", Settings.VIDEO_FACES_MIN_CLUSTER_FRAMES -> "2")) shouldBe empty
  }

  test("A cross-field rule is not judged on a field that failed on its own") {

    /**
     * Setup:
     *
     * An enroll threshold that is not a number, beside a valid keep threshold.
     *
     * Assertions:
     *
     * Validation returns rather than failing on the rule between the two, and names the enroll threshold alone, for its own rule.
     */
    val errors = testApp.settings.validate(
      Map(Settings.FACE_QUALITY_ENROLL_THRESHOLD -> "x", Settings.FACE_QUALITY_KEEP_THRESHOLD -> "15.0"))
    errors.keySet shouldBe Set(Settings.FACE_QUALITY_ENROLL_THRESHOLD)
  }

  test("A saved Restart setting reports its saved and running values until the next start; a Live setting never does") {

    /**
     * Setup:
     *
     * No restart waiting, then a save of the import parallelism, a Restart setting, and the smallest face size, a Live one.
     *
     * Assertions:
     *
     * Only the import parallelism waits for a restart, with its saved value and the running one loaded at startup. The startup
     * snapshot keeps the running value while the current one has the saved value. Saved back to the running value, nothing waits
     * for a restart.
     */
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

    /**
     * Setup:
     *
     * The registry of Settings.
     *
     * Assertions:
     *
     * Only the face search width of the vector index and the read statement timeout belong to one engine, and that engine is
     * PostgreSQL.
     */
    Settings.all.filter(_.engine.isDefined).map(_.name).toSet shouldBe
      Set(Settings.FACE_RECOGNITION_HNSW_EF_SEARCH, Settings.DB_POSTGRES_READ_STATEMENT_TIMEOUT)
    Settings.all.filter(_.engine.isDefined).foreach(_.engine shouldBe Some(Const.DbEngineName.POSTGRES))
  }

  test("The read statement timeout is at most 2,147,483 seconds, the most PostgreSQL's limit holds in milliseconds") {

    /**
     * Setup:
     *
     * The read statement timeout's rule, and on PostgreSQL a save of one second past its bound.
     *
     * Assertions:
     *
     * 2,147,483 seconds is valid and 2,147,484 is refused, saying why: PostgreSQL's `statement_timeout` is at most 2,147,483,647
     * milliseconds. On PostgreSQL the save is a `ValidationException` and the stored value stays the seed.
     */
    val name = Settings.DB_POSTGRES_READ_STATEMENT_TIMEOUT
    val rule = Settings.byName(name).valueType
    rule.error("2147483") shouldBe None
    rule.error("2147484") shouldBe Some("Must be at most 2147483")

    if (testApp.dataSourceType == Const.DbEngineName.POSTGRES) {
      intercept[ValidationException](testApp.settings.save(Map(name -> "2147484"))).errors.keySet shouldBe Set(name)
      storedValues(name) shouldBe seeds(name)
    }
  }

  test("A submitted value is trimmed, and a number spelled differently is not a change") {

    /**
     * Setup:
     *
     * The seeded Settings, and a save of the confidence threshold spelled with a trailing zero, the import parallelism padded
     * with spaces, and a new smallest face size after a space.
     *
     * Assertions:
     *
     * The two that equal their current values as numbers are no change: their rows keep the seed's spelling and no restart waits.
     * The changed value is stored trimmed and reaches the snapshot.
     */
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

    /**
     * Setup:
     *
     * The smallest face size's row changed in the database behind the snapshot's back, which still holds the seed.
     *
     * Assertions:
     *
     * A save of the snapshot's own value writes nothing, so the row keeps the value changed behind it.
     */
    // The row is changed behind the snapshot's back, so a write would show
    testApp.txManager.withTransaction {
      update("UPDATE setting SET value = ? WHERE name = ?", "60", Settings.FACE_DETECTION_MIN_FACE_SIZE)
    }
    testApp.settings.save(Map(Settings.FACE_DETECTION_MIN_FACE_SIZE -> "50"))
    storedValues(Settings.FACE_DETECTION_MIN_FACE_SIZE) shouldBe "60"
  }

  test("A load fails on an invalid stored value and warns about a row the registry does not know") {

    /**
     * Setup:
     *
     * The smallest face size's row set to a text that is not a number, then restored, and a row added for a name the registry
     * does not know.
     *
     * Assertions:
     *
     * The load of the invalid value fails, and the snapshot stays the one before it. With the unknown row the load succeeds,
     * ignoring the row.
     */
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

    /**
     * Setup:
     *
     * A confidence threshold at its upper bound, and two geocoder URLs with an http scheme but no host.
     *
     * Assertions:
     *
     * The threshold at its bound is valid. Each URL with no host is refused, on the geocoder URL.
     */
    testApp.settings.validate(Map(Settings.FACE_YUNET_CONFIDENCE_THRESHOLD -> "1")) shouldBe empty
    testApp.settings.validate(Map(Settings.MAP_GEOCODER_URL -> "http://")).keySet shouldBe Set(Settings.MAP_GEOCODER_URL)
    testApp.settings.validate(Map(Settings.MAP_GEOCODER_URL -> "https:foo")).keySet shouldBe Set(Settings.MAP_GEOCODER_URL)
  }

  test("The DAO refuses to update a Setting that has no row") {

    /**
     * Setup:
     *
     * A transaction updating the value of a name that has no `setting` row.
     *
     * Assertions:
     *
     * The DAO fails with a not-found error rather than updating nothing.
     */
    intercept[NotFoundException] {
      testApp.txManager.withTransaction {
        testApp.DAO.setting.updateValues(Map("no.such.setting" -> "1"))
      }
    }
  }

  test("A changed detection threshold is used by the next detection") {

    /**
     * Setup:
     *
     * The `people/meme-ben.jpg` portrait, in which detection finds one face at the seeded confidence threshold.
     *
     * Assertions:
     *
     * With the threshold saved as 0.999 detection finds no face, and with the seed restored it finds the face again: the
     * threshold is read per detection, not at startup.
     */
    val bytes = IntegrationTestUtil.getImportAsset("people/meme-ben.jpg").bytes
    testApp.service.faceDetection.extractFaces(bytes) should have size 1

    withSetting(Settings.FACE_YUNET_CONFIDENCE_THRESHOLD, "0.999") {
      testApp.service.faceDetection.extractFaces(bytes) shouldBe empty
    }

    testApp.service.faceDetection.extractFaces(bytes) should have size 1
  }

  test("A changed geocoder switch is used by the next search") {

    /**
     * Setup:
     *
     * A stub of a Nominatim-compatible endpoint on localhost that answers one place; the geocoder is seeded off.
     *
     * Assertions:
     *
     * A search is refused while the geocoder is off. Once the switch and the URL are saved, the next search reaches the stub and
     * maps its place, with no restart.
     */
    val stub = NominatimStub()
    stub.response = (200, """[{"place_id": 1, "display_name": "Paris", "lat": "48.85", "lon": "2.35"}]""")
    stub.start()

    try {
      intercept[IllegalOperationException] {
        testApp.service.geocoder.search("Paris")
      }

      testApp.settings.save(Map(Settings.MAP_GEOCODER_ENABLED -> "true", Settings.MAP_GEOCODER_URL -> stub.url))

      testApp.service.geocoder.search("Paris") shouldEqual List(GeocoderResult("Paris", 48.85, 2.35))
    } finally stub.stop()
  }
}
