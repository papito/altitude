package altitude.core.unit

import altitude.test.TestFocus
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
 * [[OnnxDescribeEmbedder]] against the PyTorch model of the SigLIP 2 checkpoint: `siglip2/embeddings.json`, which
 * `scripts/siglip2-golden.py` writes, holds the embedding the model gives each of its descriptions. Needs the text tower, the
 * token-embedding table and the tokenizer file `scripts/fetch-models.py` writes, as the Scene model tests need the model files.
 */
@DoNotDiscover class DescribeEmbedderTests extends AnyFunSuite with TestFocus {

  /** What the golden embeddings and the application's must agree to */
  private val MIN_COSINE = 0.9999

  /** The golden descriptions, each with the unit embedding the PyTorch model gives it */
  private val golden: Seq[(String, SceneEmbedding)] =
    ujson
      .read(getClass.getResourceAsStream("/siglip2/embeddings.json").readAllBytes())
      .arr
      .map(entry => entry("description").str -> SceneEmbedding(IArray.from(entry("embedding").arr.map(_.num.toFloat))))
      .toSeq

  /** Runs `f` over an embedder of its own, closed afterwards: the text tower is 340 MB, opened by the first description */
  private def withEmbedder[T](embedder: OnnxDescribeEmbedder = OnnxDescribeEmbedder())(f: OnnxDescribeEmbedder => T): T =
    try f(embedder)
    finally embedder.close()

  /** An embedder that counts how many times it opened its text tower */
  private def counting(opens: AtomicInteger): OnnxDescribeEmbedder =
    new OnnxDescribeEmbedder() {
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
     * The bit patterns of float16 values of every kind: zeros, ordinary values, the largest, and the subnormals, which have no
     * implicit leading one.
     *
     * Assertions:
     *
     * Each widens to the float of exactly its value, the sign kept.
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
     * The cosines SigLIP 2's constants give for an even chance of a match, for the default Describe match threshold of 0.00009,
     * and for two probabilities at the ends of the scale.
     *
     * Assertions:
     *
     * An even chance is the cosine 0.1486 and the default threshold 0.0660. The cosine rises with the probability, and the
     * probability the model's formula gives back for each cosine, `sigmoid(cosine * t + b)`, is the one it came from.
     *
     * Edge cases:
     *
     * 0 and 1 are no probability, nor is anything outside them: each is refused.
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
     * The golden file's dozen descriptions: a few words, one word, mixed case, punctuation and digits, accents, CJK, an emoji, a
     * line break, and one past the 64 tokens.
     *
     * Assertions:
     *
     * Each description's embedding is of SigLIP 2's dimensions and of unit length, and its cosine to the PyTorch model's
     * embedding is at least 0.9999: the tokenizer, the float16 token embeddings and the exported text tower together give what
     * the reference gives.
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
     * The same description again is answered with the embedding of the first time, the same object, and so is the description in
     * another case or with its whitespace written differently: the cache's key is the lowercase text with each run of whitespace
     * as one space and none at the ends.
     *
     * Edge cases:
     *
     * A description of other words has an embedding of its own.
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
    }
  }

  test("The cache holds the descriptions used last, up to its size") {

    /**
     * Setup:
     *
     * An embedder whose cache holds two descriptions. Three are embedded, the first asked for again before the third.
     *
     * Assertions:
     *
     * The first and the third description are still served from the cache. The second, the one used longest ago when the third
     * came, is embedded again: its embedding is a new object, equal to the one before.
     */
    withEmbedder(OnnxDescribeEmbedder(cacheSize = 2)) {
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
     * A new embedder that counts the times it opens its text tower, and eight threads released together, each embedding all the
     * golden descriptions.
     *
     * Assertions:
     *
     * Nothing is opened before the first description. The threads' first descriptions find no tower and open it once between
     * them; every thread gets every description's embedding, each within the golden cosine. Afterwards each description is served
     * from the cache as one object, whichever thread's pass stored it.
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
     * An embedder with its text tower open, and four threads each embedding one new description after another, so every ask is a
     * pass of the model. Once each thread has an embedding, the embedder is closed under them.
     *
     * Assertions:
     *
     * Every thread stops on an `IllegalStateException`, the refusal of a closed embedder, and on nothing else: a pass under way
     * at the close finishes with its embedding, and the next one finds the embedder closed. Afterwards a new description and one
     * embedded before are both refused, and the tower is not opened again.
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
