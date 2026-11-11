/**
 * A name as the server compares names: trimmed, then the server's fold (`NameKey.of`), so "WEISS"
 * is "Weiß" and "σοφοσ" is "σοφος". The case mappings take no locale, as the server's take
 * `Locale.ROOT`; the server stays the authority. Two names are one name when their keys are equal.
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
 * A name's form for finding a query within it: its name key with every final sigma (ς) read as a
 * plain one (σ). The key's last lower step gives a sigma at the end of a word the final form, so a
 * query that stops in mid-word on a sigma ("σοφοσ") ends in ς while the name it is part of
 * ("Σοφοσάκης") has σ there; reading ς as σ on both sides makes the query a part of the name again.
 * Only for the substring test: equality is the name key's.
 */
export function partialMatchKey(text) {
    return nameKey(text).replaceAll("ς", "σ")
}
