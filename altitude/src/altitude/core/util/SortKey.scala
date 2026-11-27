package altitude.core.util

import com.ibm.icu.text.Collator
import com.ibm.icu.text.RuleBasedCollator
import com.ibm.icu.util.ULocale
import java.nio.ByteBuffer
import java.util.Arrays

/**
 * A name's place in the order a person reads names, as bytes that order by `memcmp`: the one definition of [[NaturalOrder]], and
 * what a `*_sort` column stores (`bytea` on PostgreSQL, `BLOB` on SQLite, both compared byte by byte), so a list sorted in memory
 * and one sorted by either database come in one order.
 *
 * The key is ICU's sort key of the name under the root locale's collation (the Unicode Collation Algorithm's order for every
 * script) with numeric collation on, then a tie-break:
 *
 *   - Letters sort by their base letter first, an accent or case only telling apart names alike otherwise, so "Émile" sits beside
 *     "emile" and "Øst", "Łódź" and "Æble" beside "Ost", "Lodz" and "Aeble". The strength is tertiary: base letters, then
 *     accents, then case. A letter the root collation counts as its own stays apart ("Þ" after "z"): a language's own order would
 *     need a tailored collation.
 *   - A run of decimal digits, in any script, compares as its number: "IMG_9" before "IMG_10", "Client 2" before "client 10".
 *     Leading zeros are no part of the number, so "7" and "07" are alike to the collation.
 *   - Spaces and punctuation come before symbols, symbols (an emoji) before numbers, numbers before letters.
 *   - Names the collation finds alike are told apart by length, the shorter first ("7" before "07"), then by their UTF-16 code
 *     units: ICU's key ends in a 0 byte, which no other byte of it is, so what follows only ever breaks a tie. So no two
 *     different names compare equal, a canonically equivalent pair (an accent precomposed or combining) included.
 *
 * Stability: a key is ICU's, so it depends on the ICU version `build.mill` pins (and on the collator settings here), never on the
 * JDK or the JVM's locale. A new ICU version can change the keys of names whose order a new Unicode or CLDR release changes, and
 * a change to this function changes every key. A stored key is then stale: the lists the database orders and those ordered in
 * memory disagree until every `*_sort` column is written again, by recreating the database (as every schema change does) or by a
 * manual re-key. No key version is stored and nothing re-keys at startup. A stale key only misorders: no `*_sort` column is
 * unique or looked up by.
 *
 * Computing a key costs a collation pass over the name, so a sort of many names computes each name's key once (`sortBy` on
 * `SortKey.of`), not per comparison as [[NaturalOrder]] does.
 */
final class SortKey private (private val key: Array[Byte]):

  /** The key's bytes, as a `*_sort` column stores them */
  def bytes: Array[Byte] = key.clone

  override def equals(other: Any): Boolean = other match
    case that: SortKey => Arrays.equals(key, that.key)
    case _ => false

  override def hashCode: Int = Arrays.hashCode(key)

object SortKey:

  // A frozen collator is immutable, so every thread shares the one
  private val collator: Collator =
    val root = Collator.getInstance(ULocale.ROOT).asInstanceOf[RuleBasedCollator]
    root.setStrength(Collator.TERTIARY)
    root.setNumericCollation(true)
    // A name typed with a combining accent collates as the same name precomposed
    root.setDecomposition(Collator.CANONICAL_DECOMPOSITION)
    root.freeze()

  def of(name: String): SortKey =
    val collated = collator.getCollationKey(name).toByteArray
    // The code units themselves, not an encoding of them, which would replace a lone surrogate
    val key =
      ByteBuffer.allocate(collated.length + Integer.BYTES + name.length * Character.BYTES).put(collated).putInt(name.length)
    name.foreach(key.putChar)
    SortKey(key.array)

  given Ordering[SortKey] = (a, b) => Arrays.compareUnsigned(a.key, b.key)
