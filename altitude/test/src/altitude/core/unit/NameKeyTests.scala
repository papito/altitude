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
     * Names in German with a sharp s and a capital sharp s, in Greek with a final sigma, in full width, with a ligature, with a
     * combining accent, as a circled digit, as the ohm and kelvin signs, and as a title-case digraph.
     *
     * Assertions:
     *
     * Each name's key is its lowercase, case-folded, NFKC form: "ß" and "ẞ" fold to "ss", every sigma to a final sigma at the end
     * of a word, a full-width letter, a ligature, a circled digit and a digraph to their plain letters and digits, a combining
     * accent to the precomposed letter, and the ohm and kelvin signs to "ω" and "k".
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
     * The names of each group share one key: "Straße", "STRASSE" and "STRAẞE"; "σοφος", "σοφοσ" and "ΣΟΦΟΣ"; full-width "Ａlbum"
     * and "album"; "ﬁle" and "FILE"; "e" with a combining acute and "É"; "①" and "1"; "Ω" and the ohm sign; "K" and the kelvin
     * sign; "ǅ" and "ǆ".
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
     * The JVM's default locale is Turkish, whose lowercase of "I" is the dotless "ı"; the default locale is restored afterwards.
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
     * The key of each name's key is that key, so a key can be folded again safely.
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
     * The first is returned precomposed, in NFC; the second is returned as it is, since NFC leaves compatibility forms alone.
     */
    NameKey.normalized("Caf" + "é") shouldBe "Café"
    Normalizer.isNormalized(NameKey.normalized("Caf" + "é"), Normalizer.Form.NFC) shouldBe true
    NameKey.normalized("Ａlbum") shouldBe "Ａlbum"
  }
}
