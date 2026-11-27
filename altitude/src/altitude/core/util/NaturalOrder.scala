package altitude.core.util

/**
 * Names in the order a person reads them, as their [[SortKey]]s order (ICU's root collation with numeric collation, see there):
 * case and accents only tell apart names alike otherwise, a letter such as "ø" sits beside its base letter, a run of digits
 * compares as a number ("Client 2" before "client 10"), and no two different names compare equal. A list a database sorts by a
 * `*_sort` column, which holds the same key, comes in this order too.
 *
 * It computes both names' keys on every comparison, so a sort of many names sorts by `SortKey.of` instead, which computes each
 * name's key once.
 */
object NaturalOrder extends Ordering[String]:
  override def compare(a: String, b: String): Int = summon[Ordering[SortKey]].compare(SortKey.of(a), SortKey.of(b))
