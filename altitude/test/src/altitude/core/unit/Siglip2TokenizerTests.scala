package altitude.core.unit

import altitude.test.TestFocus
import altitude.test.TestModelFiles
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.util.ModelFile
import altitude.core.util.Siglip2Tokenizer

/**
 * [[Siglip2Tokenizer]] against the reference processor of the SigLIP 2 checkpoint: `siglip2/tokens.json`, which
 * `scripts/siglip2-golden.py` writes, holds the 64 token IDs the processor gives each of its descriptions. Needs the tokenizer
 * file among the model files (`TestModelFiles`), as the Scene model tests need theirs.
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
     * A tokenizer over the vocabulary file of the model files, and the golden file's descriptions, a few hundred: plain words,
     * punctuation, digits, spaces at the ends and repeated, tabs and line breaks, apostrophes, accents and other scripts, CJK,
     * emoji, mixed case, runs of one character.
     *
     * Assertions:
     *
     * Each description tokenizes to exactly the 64 IDs the reference processor gives its lowercase: the same pieces in the same
     * order, one EOS after them, and the pad ID to the end.
     *
     * Edge cases:
     *
     * The empty description is the EOS and 63 pads. A description of 63 tokens fills the 64 with its EOS, and a longer one keeps
     * its first 63 and still ends in the EOS. A character with no piece becomes the pieces of its UTF-8 bytes. A capital whose
     * lowercase is two characters or depends on its place (a dotted capital I, a final sigma) lowercases as the reference does. A
     * special token, an HTML tag or a run of line feeds written in a description is the one piece the reference matches whole,
     * and the text on either side of it is tokenized apart.
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
