package altitude.core.unit

import altitude.test.TestFocus
import altitude.test.TestModelFiles
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.util.ModelFile
import altitude.core.util.Siglip2Tokenizer

/**
 * [[Siglip2Tokenizer]] against the SigLIP 2 checkpoint's reference processor: `siglip2/tokens.json`, written by
 * `scripts/siglip2-golden.py`, holds the 64 token IDs the processor gives each description. Needs the tokenizer model file.
 */
@DoNotDiscover class Siglip2TokenizerTests extends AnyFunSuite with TestFocus {

  /** The golden descriptions as written, each with the reference processor's IDs of its lowercase */
  private val golden: Seq[(String, Seq[Int])] =
    ujson
      .read(getClass.getResourceAsStream("/siglip2/tokens.json").readAllBytes())
      .arr
      .map(entry => entry("description").str -> entry("ids").arr.map(_.num.toInt).toSeq)
      .toSeq

  test("Every golden description tokenizes to the reference processor's token IDs") {

    /**
     * Setup:
     *
     * The tokenizer model file and a few hundred golden descriptions covering punctuation, digits, odd whitespace, accents, other
     * scripts, emoji, mixed case and repeated characters.
     *
     * Assertions:
     *
     * Each description tokenizes to exactly the 64 IDs the reference gives its lowercase: the same pieces, one EOS, then pads.
     *
     * Edge cases:
     *
     * The empty description is the EOS and 63 pads; a longer one than fits keeps its first 63 tokens and still ends in the EOS. A
     * character with no piece becomes its UTF-8 byte pieces. A dotted capital I and a final sigma lowercase as the reference
     * does. A special token, an HTML tag or a run of line feeds is one piece, with the text around it tokenized apart.
     */
    golden.size should be >= 200
    val tokenizer = Siglip2Tokenizer.load(TestModelFiles.models.path(ModelFile.Siglip2Tokenizer))

    val different = golden.flatMap {
      (description, ids) =>
        val tokenized = tokenizer.tokenize(description).toSeq
        Option.when(tokenized != ids)(s"${ujson.Str(description)}: expected ${ids.mkString(" ")}, got ${tokenized.mkString(" ")}")
    }
    withClue(s"${different.size} of ${golden.size} descriptions tokenize differently:\n${different.take(10).mkString("\n")}\n") {
      different.size shouldBe 0
    }
  }
}
