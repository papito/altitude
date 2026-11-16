package altitude.core.unit

import altitude.test.TestFocus
import java.text.Normalizer
import java.util.Locale
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.util.NameKey

@DoNotDiscover class NameKeyTests extends funsuite.AnyFunSuite with TestFocus {

  test("A name's key folds case in any script, canonical forms and compatibility forms") {

    /**
     * Setup:
     *
     * Names with a sharp s and a capital sharp s, a Greek final sigma, a full-width letter, a ligature, a combining accent, a
     * circled digit, the ohm and kelvin signs, and a title-case digraph.
     *
     * Assertions:
     *
     * Each name's key is its lowercase, case-folded NFKC form, with a sigma at a word's end written final.
     */
    val expected = List(
      "Straße" -> "strasse",
      "STRASSE" -> "strasse",
      "STRAẞE" -> "strasse",
      "ΣΟΦΟΣ" -> "σοφος",
      "σοφοσ" -> "σοφος",
      "Ａlbum" -> "album",
      "ﬁle" -> "file",
      "é" -> "é",
      "①" -> "1",
      "Ω" -> "ω",
      "K" -> "k",
      "ǅ" -> "dž"
    )

    for ((name, key) <- expected) {
      withClue(s"[$name]: ") {
        NameKey.of(name) shouldBe key
      }
    }
  }

  test("Names a reader takes for the same name share a key, and names that differ in an accent do not") {

    /**
     * Setup:
     *
     * Groups of names that differ only in case, case fold, canonical form or compatibility form, and a pair that differs in an
     * accent.
     *
     * Assertions:
     *
     * The names of each group share one key.
     *
     * Edge cases:
     *
     * "Émile" and "Emile" keep different keys: the key does not fold accents.
     */
    val sameName = List(
      List("Straße", "STRASSE", "STRAẞE"),
      List("σοφος", "σοφοσ", "ΣΟΦΟΣ"),
      List("Ａlbum", "album"),
      List("ﬁle", "FILE"),
      List("é", "É"),
      List("①", "1"),
      List("Ω", "Ω"),
      List("K", "K"),
      List("ǅ", "ǆ")
    )

    for (names <- sameName) {
      withClue(s"${names.mkString("[", "] [", "]")}: ") {
        names.map(NameKey.of).distinct.size shouldBe 1
      }
    }

    (NameKey.of("Émile") == NameKey.of("Emile")) shouldBe false
  }

  test("A name's key does not depend on the JVM's default locale") {

    /**
     * Setup:
     *
     * The JVM's default locale set to Turkish, whose lowercase of "I" is the dotless "ı", and restored afterwards.
     *
     * Assertions:
     *
     * The key of "I" is still "i".
     */
    val defaultLocale = Locale.getDefault
    try {
      Locale.setDefault(Locale.forLanguageTag("tr"))
      NameKey.of("I") shouldBe "i"
    } finally Locale.setDefault(defaultLocale)
  }

  test("Folding a key again gives the same key, for every code point") {

    /**
     * Setup:
     *
     * Every Unicode code point outside the surrogate range, each as a one-code-point name.
     *
     * Assertions:
     *
     * Each key is its own key, so folding it again is safe.
     */
    val notIdempotent = (0 to Character.MAX_CODE_POINT).iterator
      .filterNot(cp => cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)
      .map(Character.toString)
      .filter {
        name =>
          val key = NameKey.of(name)
          NameKey.of(key) != key
      }
      .map(name => name.codePointAt(0).toHexString)
      .toList

    notIdempotent shouldBe Nil
  }

  test("A stored name is normalized to NFC and keeps its compatibility forms") {

    /**
     * Setup:
     *
     * A name with an "e" and a combining acute accent, and a name with a full-width letter.
     *
     * Assertions:
     *
     * The first comes back precomposed; the second unchanged, since NFC leaves compatibility forms alone.
     */
    NameKey.normalized("Caf" + "é") shouldBe "Café"
    Normalizer.isNormalized(NameKey.normalized("Caf" + "é"), Normalizer.Form.NFC) shouldBe true
    NameKey.normalized("Ａlbum") shouldBe "Ａlbum"
  }
}
