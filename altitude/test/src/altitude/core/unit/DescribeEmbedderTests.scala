package altitude.core.unit

import altitude.test.TestFocus
import altitude.test.TestModelFiles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.DoNotDiscover
import org.scalatest.OptionValues.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import scala.util.Try

import altitude.core.models.SceneEmbedding
import altitude.core.models.SceneModel
import altitude.core.util.OnnxDescribeEmbedder

/**
 * [[OnnxDescribeEmbedder]] against the PyTorch SigLIP 2 checkpoint: `siglip2/embeddings.json`, written by
 * `scripts/siglip2-golden.py`, holds the reference embedding of each description, with each run of whitespace given as one space,
 * as the embedder hands it to its tokenizer. Needs the Describe model files (`TestModelFiles`).
 */
@DoNotDiscover class DescribeEmbedderTests extends AnyFunSuite with TestFocus {

  /** The cosine the golden embeddings and the application's must reach */
  private val MIN_COSINE = 0.9999

  private val golden: Seq[(String, SceneEmbedding)] =
    ujson
      .read(getClass.getResourceAsStream("/siglip2/embeddings.json").readAllBytes())
      .arr
      .map(entry => entry("description").str -> SceneEmbedding(IArray.from(entry("embedding").arr.map(_.num.toFloat))))
      .toSeq

  /** Runs `f` over an embedder closed afterwards, since the text tower it opens holds hundreds of MB */
  private def withEmbedder[T](embedder: OnnxDescribeEmbedder = OnnxDescribeEmbedder(TestModelFiles.models))(
      f: OnnxDescribeEmbedder => T): T =
    try f(embedder)
    finally embedder.close()

  /** An embedder that counts how many times it opened its text tower */
  private def counting(opens: AtomicInteger): OnnxDescribeEmbedder =
    new OnnxDescribeEmbedder(TestModelFiles.models) {
      override protected def openTower(): OnnxDescribeEmbedder.TextTower = {
        opens.incrementAndGet()
        super.openTower()
      }
    }

  private def norm(embedding: SceneEmbedding): Double = Math.sqrt(embedding.similarity(embedding))

  test("A float16 of the token-embedding table widens to the float of the same value") {

    /**
     * Setup:
     *
     * The bit patterns of float16 zeros, ordinary values, the largest, the subnormals (no implicit leading one), the infinities
     * and a NaN.
     *
     * Assertions:
     *
     * Each widens to the float of exactly its value, sign kept.
     *
     * Edge cases:
     *
     * Negative zero keeps its sign, the smallest subnormal is 2^-24, the infinities stay infinite and a NaN stays a NaN.
     */
    def widened(bits: Int): Float = OnnxDescribeEmbedder.halfToFloat(bits.toShort)

    widened(0x0000) shouldBe 0f
    java.lang.Float.floatToRawIntBits(widened(0x8000)) shouldBe 0x80000000
    widened(0x3c00) shouldBe 1f
    widened(0xbc00) shouldBe -1f
    widened(0xc000) shouldBe -2f
    widened(0x3800) shouldBe 0.5f
    widened(0x3555) shouldBe 0.33325195f
    widened(0x4248) shouldBe 3.140625f
    widened(0x7bff) shouldBe 65504f
    widened(0xfbff) shouldBe -65504f
    widened(0x0400) shouldBe Math.scalb(1f, -14)
    widened(0x03ff) shouldBe Math.scalb(1023f, -24)
    widened(0x0001) shouldBe Math.scalb(1f, -24)
    widened(0x8001) shouldBe -Math.scalb(1f, -24)
    widened(0x7c00) shouldBe Float.PositiveInfinity
    widened(0xfc00) shouldBe Float.NegativeInfinity
    widened(0x7e00).isNaN shouldBe true
  }

  test("A SigLIP 2 match probability is a cosine, by the checkpoint's scale and bias") {

    /**
     * Setup:
     *
     * The cosines for an even chance of a match, for the default Describe minimum probability of 0.00009, and for two
     * probabilities near the ends of the scale.
     *
     * Assertions:
     *
     * An even chance is the cosine 0.1486 and the default 0.0660. The cosine rises with the probability, and the model's
     * `sigmoid(cosine * t + b)` gives each probability back.
     *
     * Edge cases:
     *
     * 0, 1 and anything outside them are refused.
     */
    SceneModel.siglip2Cosine(0.5) shouldBe 0.1486 +- 0.0001
    SceneModel.siglip2Cosine(0.00009) shouldBe 0.0660 +- 0.0001

    val probabilities = List(0.000000000000001, 0.00009, 0.5, 0.999999)
    val cosines = probabilities.map(SceneModel.siglip2Cosine)
    cosines shouldBe cosines.sorted
    probabilities.zip(cosines).foreach {
      case (probability, cosine) =>
        val back = 1 / (1 + math.exp(-(cosine * SceneModel.SIGLIP2_LOGIT_SCALE + SceneModel.SIGLIP2_LOGIT_BIAS)))
        back shouldBe probability +- probability * 1e-9
    }

    List(0.0, 1.0, -0.1, 1.5).foreach(probability => intercept[IllegalArgumentException](SceneModel.siglip2Cosine(probability)))
  }

  test("Every golden description embeds as the PyTorch model embeds it") {

    /**
     * Setup:
     *
     * The golden file's dozen descriptions: one word and a few, mixed case, punctuation and digits, accents, CJK, an emoji, a
     * line break, and one past 64 tokens.
     *
     * Assertions:
     *
     * Each embedding has SigLIP 2's dimensions and unit length, and is within a cosine of 0.9999 of the reference: normalization,
     * tokenizer, float16 token embeddings and the exported tower together match PyTorch.
     *
     * Edge cases:
     *
     * The description with a line break embeds as the reference does with a space in its place.
     */
    golden.size should be >= 12

    withEmbedder() {
      embedder =>
        golden.foreach {
          (description, expected) =>
            withClue(s"${ujson.Str(description)}: ") {
              val embedding = embedder.embed(description).value
              embedding.values.length shouldBe SceneModel.Siglip2.dimensions
              norm(embedding) shouldBe 1.0 +- 1e-5
              embedding.similarity(expected) should be >= MIN_COSINE
            }
        }
    }
  }

  test("A repeated description is served from the cache") {

    /**
     * Setup:
     *
     * One embedder, and a description embedded once.
     *
     * Assertions:
     *
     * The same description, also in another case or with other whitespace, gets the first embedding object back: the cache's key
     * is the normalized text.
     *
     * Edge cases:
     *
     * Other words get an embedding of their own. So do a capital sigma beside a digit and the same in small sigmas, which the JDK
     * lowercases alike but the tokenizer does not.
     */
    withEmbedder() {
      embedder =>
        val first = embedder.embed("a woman in a park").value

        (embedder.embed("a woman in a park").value should be).theSameInstanceAs(first)
        (embedder.embed("A Woman in a PARK").value should be).theSameInstanceAs(first)
        (embedder.embed("  a woman\tin  a\npark ").value should be).theSameInstanceAs(first)

        val other = embedder.embed("a woman in the park").value
        other should not be theSameInstanceAs(first)
        other.similarity(first) should be < 1.0

        val capitals = embedder.embed("ΣΑΣ1a").value
        val small = embedder.embed("σασ1a").value
        small should not be theSameInstanceAs(capitals)
        small should not be capitals
    }
  }

  test("A description embeds as its normalized text does, whatever its case and whitespace") {

    /**
     * Setup:
     *
     * An embedder with a one-description cache, so each ask after another description is a pass of the tower. "a woman in a park"
     * is embedded, then variants in capitals, with tabs, line breaks, doubled and no-break spaces, and spaces at the ends.
     *
     * Assertions:
     *
     * Each variant gets a new embedding equal to the original's: the normalized text is what is embedded, so the result does not
     * depend on which spelling was asked first.
     *
     * Edge cases:
     *
     * The words in another order embed differently.
     */
    withEmbedder(OnnxDescribeEmbedder(TestModelFiles.models, cacheSize = 1)) {
      embedder =>
        val normalized = embedder.embed("a woman in a park").value
        val variants = List(
          "A Woman in a PARK",
          "a\twoman\tin\ta\tpark",
          "a woman\nin a\r\npark",
          "a  woman   in a park",
          "a woman in\u00a0a park",
          "  a woman\tin  a\npark \n"
        )

        variants.foreach {
          variant =>
            withClue(s"${ujson.Str(variant)}: ") {
              // Takes the one place of the cache, so the variant is embedded anew
              embedder.embed("a dog")
              val embedding = embedder.embed(variant).value
              embedding should not be theSameInstanceAs(normalized)
              embedding shouldBe normalized
            }
        }

        embedder.embed("a park in a woman").value should not be normalized
    }
  }

  test("The cache holds the descriptions used last, up to its size") {

    /**
     * Setup:
     *
     * An embedder with a two-description cache. Three are embedded, the first asked for again before the third.
     *
     * Assertions:
     *
     * The first and third are still cached. The second, least recently used when the third came, is embedded again into a new,
     * equal object.
     */
    withEmbedder(OnnxDescribeEmbedder(TestModelFiles.models, cacheSize = 2)) {
      embedder =>
        val cactus = embedder.embed("a cactus").value
        val dog = embedder.embed("a dog").value
        (embedder.embed("a cactus").value should be).theSameInstanceAs(cactus)
        val cat = embedder.embed("a cat").value

        (embedder.embed("a cactus").value should be).theSameInstanceAs(cactus)
        (embedder.embed("a cat").value should be).theSameInstanceAs(cat)

        val dogAgain = embedder.embed("a dog").value
        dogAgain should not be theSameInstanceAs(dog)
        dogAgain shouldBe dog
    }
  }

  test("The text tower opens on the first description, once, however many threads ask at once") {

    /**
     * Setup:
     *
     * A new embedder that counts how often it opens its text tower, and eight threads released together, each embedding every
     * golden description.
     *
     * Assertions:
     *
     * Nothing opens before the first description, and the threads open the tower once between them. Every thread gets every
     * embedding within the golden cosine, and afterwards each description is served from the cache as one object.
     */
    val opens = AtomicInteger()
    val threads = 8
    val pool = Executors.newFixedThreadPool(threads)

    try
      withEmbedder(counting(opens)) {
        embedder =>
          opens.get shouldBe 0

          val start = CountDownLatch(1)
          val asked = (1 to threads).map {
            _ =>
              pool.submit {
                () =>
                  start.await()
                  golden.map((description, _) => embedder.embed(description).value)
              }
          }
          start.countDown()
          val answers = asked.map(_.get(2, TimeUnit.MINUTES))

          opens.get shouldBe 1
          answers.foreach {
            embeddings =>
              embeddings.zip(golden).foreach {
                case (embedding, (description, expected)) =>
                  withClue(s"${ujson.Str(description)}: ")(embedding.similarity(expected) should be >= MIN_COSINE)
              }
          }
          golden.foreach {
            (description, _) => (embedder.embed(description).value should be).theSameInstanceAs(embedder.embed(description).value)
          }
      }
    finally pool.shutdownNow()
  }

  test("Closing waits for the passes under way, and a closed embedder refuses") {

    /**
     * Setup:
     *
     * An embedder with its tower open and four threads each embedding new descriptions in a loop, so every ask is a pass. Once
     * each thread has an embedding, the embedder is closed under them.
     *
     * Assertions:
     *
     * Every thread stops on the closed embedder's `IllegalStateException` and nothing else: a pass under way finishes, and the
     * next finds the embedder closed. Afterwards a new description and a cached one are both refused, and the tower is not opened
     * again.
     *
     * Edge cases:
     *
     * A second close does nothing.
     */
    val opens = AtomicInteger()
    val threads = 4
    val pool = Executors.newFixedThreadPool(threads)
    val embedder = counting(opens)

    try
      embedder.embed("a cactus").value
      val started = CountDownLatch(threads)
      val asking = (1 to threads).map {
        thread =>
          pool.submit {
            () =>
              // Each description is new, so none is served from the cache
              Try(Iterator.from(0).foreach {
                i =>
                  embedder.embed(s"photo $i of thread $thread").value
                  started.countDown()
              })
          }
      }
      started.await(2, TimeUnit.MINUTES) shouldBe true

      embedder.close()

      asking.foreach(ended => ended.get(2, TimeUnit.MINUTES).failed.get shouldBe an[IllegalStateException])
      an[IllegalStateException] should be thrownBy embedder.embed("a description never embedded")
      an[IllegalStateException] should be thrownBy embedder.embed("a cactus")
      opens.get shouldBe 1

      embedder.close()
    finally
      pool.shutdownNow()
      embedder.close()
  }
}
