package altitude.core.util

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/**
 * The one fold for every name the app keeps unique, and the one normalization of a stored name.
 *
 * The fold is done in Scala, never in SQL: SQLite's `lower()` folds ASCII only and PostgreSQL's follows the database's collation,
 * and neither folds compatibility forms. Every case mapping takes `Locale.ROOT`, so the key does not depend on the JVM's locale
 * (under a Turkish one, "I" would lowercase to a dotless "ı").
 */
object NameKey:

  /** NFC, which keeps compatibility forms: a full-width letter stays full width */
  def normalized(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)

  /**
   * Two names are the same name when their keys are equal. The key folds case in any script ("Straße", "STRASSE" and "STRAẞE"),
   * canonical forms (the combining accents of macOS file names) and compatibility forms (a full-width "Ａlbum", "ﬁ", "①"), but not
   * accents: "Émile" and "Emile" are two names.
   *
   * The upper step is what folds ("ß" to "SS", every sigma to "Σ"); the first lower brings "ẞ", which has no uppercase mapping,
   * to "ß" before it; the last lower gives the readable form. The closing NFKC recomposes what the case mappings decompose (the
   * uppercase of "ǰ" is "J" with a combining caron). Folding a key again gives the same key.
   *
   * Unlike Unicode's `NFKC_Casefold`, a dotless "ı" folds to "i", and default-ignorable code points (a soft hyphen) are kept.
   */
  def of(name: String): String =
    val compatible = Normalizer.normalize(name, Normalizer.Form.NFKC)
    val caseFolded = compatible.toLowerCase(Locale.ROOT).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT)
    Normalizer.normalize(caseFolded, Normalizer.Form.NFKC)

  /**
   * UTF-8 bytes, so every key fits its unique index: PostgreSQL's btree refuses an index row over 2704 bytes, and the widest,
   * `folder_02`, spends about 90 on the row header and two IDs. A 250-character name's key is at most 1250 bytes ("ᾀ" folds to
   * "ἀι", 5 bytes) unless it holds compatibility characters, one of which can spell out many (U+FDFA is a 33-byte key); the
   * index's compression is no bound to rely on.
   */
  val MAX_KEY_BYTES = 2000

  def keyFits(name: String): Boolean = of(name).getBytes(StandardCharsets.UTF_8).length <= MAX_KEY_BYTES
