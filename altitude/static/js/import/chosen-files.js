/**
 * The files an Import page upload section sends, chosen in its picker or dropped, each with its folder
 * path. Empty files and names imports ignore are left out (the server's `IgnoredFiles`, enforced again
 * on upload); an ignored directory takes everything in it. The one folder chosen, or dropped alone, is
 * taken whatever its name: like a Watch folder's directory, its name is no part of the sent paths.
 */

// The server's path separator (`RelPath`)
const SEPARATOR = "/"

/** `ignored` holds names, prefixes and suffixes in lower case */
export function ignoresName(ignored, name) {
    const folded = name.toLowerCase()

    return (
        ignored.names.includes(folded) ||
        ignored.prefixes.some((prefix) => folded.startsWith(prefix)) ||
        ignored.suffixes.some((suffix) => folded.endsWith(suffix))
    )
}

function inPathOrder(files) {
    const collator = new Intl.Collator(undefined, { numeric: true })

    return files.sort((a, b) =>
        collator.compare(a.path.join(SEPARATOR), b.path.join(SEPARATOR)),
    )
}

function picked(files, topFolders) {
    return {
        files: inPathOrder(files),
        folder: topFolders.length === 1 ? topFolders[0] : null,
        folders: topFolders.length,
    }
}

/** A folder picker's paths start with the chosen folder's name, which is exempt from the ignored names */
export function filesOfInput(inputEl, ignored) {
    const below = inputEl.webkitdirectory ? 1 : 0
    const files = [...inputEl.files]
        .map((file) => ({
            file,
            path: (file.webkitRelativePath || file.name).split(SEPARATOR),
        }))
        .filter(
            ({ file, path }) =>
                file.size > 0 &&
                !path.slice(below).some((name) => ignoresName(ignored, name)),
        )
    const isFolder = inputEl.webkitdirectory && files.length > 0

    return picked(files, isFolder ? [files[0].path[0]] : [])
}

function readFile(entry) {
    return new Promise((resolve, reject) => entry.file(resolve, reject))
}

/** `readEntries` hands entries over in batches, until one comes back empty */
async function childrenOf(directoryEntry) {
    const reader = directoryEntry.createReader()
    const children = []

    for (;;) {
        const batch = await new Promise((resolve, reject) =>
            reader.readEntries(resolve, reject),
        )
        if (batch.length === 0) {
            return children
        }
        children.push(...batch)
    }
}

/** Collects the files at and below `entry`; its own name is not checked, and an unreadable file is skipped */
async function walk(entry, parents, ignored, files) {
    const path = [...parents, entry.name]

    if (entry.isDirectory) {
        for (const child of await childrenOf(entry)) {
            if (!ignoresName(ignored, child.name)) {
                await walk(child, path, ignored, files)
            }
        }
    } else {
        try {
            const file = await readFile(entry)
            if (file.size > 0) {
                files.push({ file, path })
            }
        } catch (error) {
            console.warn(`Could not read ${path.join(SEPARATOR)}`, error)
        }
    }
}

/** Must run while `drop` is being handled: the items are gone once the handler returns */
export function entriesOfDrop(dataTransfer) {
    return [...dataTransfer.items]
        .filter((item) => item.kind === "file")
        .map((item) => item.webkitGetAsEntry())
        .filter(Boolean)
}

/**
 * A folder dropped alone is taken whatever its name, like the folder picker's; of several dropped
 * items, each is checked against the ignored names.
 */
export async function filesOfEntries(entries, ignored) {
    const files = []
    const isOneFolder = entries.length === 1 && entries[0].isDirectory
    const tops = isOneFolder
        ? entries
        : entries.filter((entry) => !ignoresName(ignored, entry.name))

    for (const entry of tops) {
        await walk(entry, [], ignored, files)
    }

    return picked(
        files,
        tops.filter((entry) => entry.isDirectory).map((entry) => entry.name),
    )
}

/**
 * The Import's name and each file's sent path. File upload sends bare names and names no Import. Folder
 * upload sends tree paths: a single top folder names the Import and is cut from the paths; several
 * items keep their folders in the paths and name the Import by the folder count; loose files name
 * nothing.
 */
export function uploadOf(kind, { files, folder, folders }) {
    if (kind === "file") {
        return {
            name: null,
            files: files.map(({ file, path }) => ({
                file,
                relPath: path.at(-1),
            })),
        }
    }

    const isTop = folders === 1 && files.every(({ path }) => path.length > 1)
    const counted = folders === 1 ? "1 folder" : `${folders} folders`

    return {
        name: isTop ? folder : folders > 0 ? counted : null,
        files: files.map(({ file, path }) => ({
            file,
            relPath: path.slice(isTop ? 1 : 0).join(SEPARATOR),
        })),
    }
}
