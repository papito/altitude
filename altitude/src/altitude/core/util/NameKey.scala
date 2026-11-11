package altitude.core.util

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/**
 * The one fold for every name the app keeps unique, and the one normalization of a name as it is stored.
 *
 * Two names are the same name when their keys are equal ([[of]]): the key folds letter case in any script ("Straße", "STRASSE"
 * and "STRAẞE"; a Greek name with a final sigma and with a plain one), canonical forms (a precomposed "é" and an "e" with a
 * combining accent, which is what macOS file names carry) and compatibility forms (a full-width "Ａlbum", the "ﬁ" ligature, "①").
 * It does not fold accents: "Émile" and "Emile" are two names.
 *
 * The fold is done here, in Scala, and never in SQL: SQLite's `lower()` folds ASCII only and PostgreSQL's folds by the database's
 * collation, so the two engines would disagree, and neither folds compatibility forms. Every case mapping takes `Locale.ROOT`, so
 * the key does not depend on the JVM's default locale (under a Turkish one, "I" would lowercase to a dotless "ı").
 */
object NameKey:

  /** A name as it is stored and displayed: in NFC, which keeps its compatibility forms (a full-width letter stays full width). */
  def normalized(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)

  /**
   * The key under which two names are the same name: `NFKC(lower(upper(lower(NFKC(name)))))`, each case mapping under
   * `Locale.ROOT`.
   *
   * The upper step is what folds: "ß" becomes "SS", and every sigma becomes "Σ". The first lower brings "ẞ" (U+1E9E), which has
   * no uppercase mapping of its own, to "ß" before that. The last lower gives the key its readable form, a final sigma at the end
   * of a word included. The opening NFKC folds the compatibility forms, and the closing one recomposes what the case mappings
   * decompose (the uppercase of "ǰ" is "J" with a combining caron). Folding a key again gives the same key, for every code point.
   *
   * Two differences from Unicode's `NFKC_Casefold`, both accepted: a dotless "ı" folds to "i", since its uppercase is "I"; and
   * default-ignorable code points (a zero-width space, a soft hyphen) are kept rather than removed.
   */
  def of(name: String): String =
    val compatible = Normalizer.normalize(name, Normalizer.Form.NFKC)
    val caseFolded = compatible.toLowerCase(Locale.ROOT).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT)
    Normalizer.normalize(caseFolded, Normalizer.Form.NFKC)

  /**
   * The most UTF-8 bytes a name key may hold, so that every key fits the unique index that holds it: PostgreSQL's btree refuses
   * an index row over 2704 bytes (SQLSTATE 54000), and the widest, `folder_02`, spends about 90 of them on the row header and the
   * two IDs ahead of the key. A key is no longer than its name but for what the fold spells out: a name of the longest length the
   * app allows (250 characters) has a key of at most 1250 bytes unless it holds compatibility characters, since no other
   * character's key passes 5 bytes ("ᾀ" folds to "ἀι"); a compatibility character can stand for many (U+FDFA, one character, is a
   * 33-byte key), and the index's compression is no bound to rely on.
   */
  val MAX_KEY_BYTES = 2000

  /** Whether the name key of `name` is within [[MAX_KEY_BYTES]] */
  def keyFits(name: String): Boolean = of(name).getBytes(StandardCharsets.UTF_8).length <= MAX_KEY_BYTES
