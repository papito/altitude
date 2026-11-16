/**
 * A name trimmed, then folded as the server's `NameKey.of` folds it ("WEISS" is "Weiß"). The case
 * mappings take no locale, matching the server's `Locale.ROOT`; the server stays the authority.
 */
export function nameKey(text) {
    return text
        .trim()
        .normalize("NFKC")
        .toLowerCase()
        .toUpperCase()
        .toLowerCase()
        .normalize("NFKC")
}

/**
 * The name key with every final sigma (ς) read as σ, for substring tests only. Lowercasing gives a
 * word-final sigma the final form, so a query that stops mid-word on a sigma ("σοφοσ") ends in ς
 * while the name containing it ("Σοφοσάκης") has σ there.
 */
export function partialMatchKey(text) {
    return nameKey(text).replaceAll("ς", "σ")
}
