/**
 * What an upload section of the Import page sends (`includes/upload_sections`,
 * `alpine/components/import-uploads.js`): the files chosen in its picker or dropped on its zone,
 * each with its path, the names of the folders it is in and then its own, less the files imports
 * ignore. The list of ignored names is the server's (`IgnoredFiles`, which the page carries and the
 * upload routes enforce again): a file or a directory under such a name is left out, a directory
 * with everything in it, and so is a file with no content. The one folder that was chosen, or
 * dropped alone, is taken whatever its name: it is the folder the user named, as a Watch folder's
 * directory is on the server, and its own name is no part of any path that is sent.
 */

// A path's separator, the server's (`RelPath`)
const SEPARATOR = "/"

/** Whether imports ignore a file or a directory of the name; `ignored` holds its names, prefixes and suffixes in lower case */
export function ignoresName(ignored, name) {
    const folded = name.toLowerCase()

    return (
        ignored.names.includes(folded) ||
        ignored.prefixes.some((prefix) => folded.startsWith(prefix)) ||
        ignored.suffixes.some((suffix) => folded.endsWith(suffix))
    )
}

/** The files in the order of their paths, a number in a name compared by its value */
function inPathOrder(files) {
    const collator = new Intl.Collator(undefined, { numeric: true })

    return files.sort((a, b) =>
        collator.compare(a.path.join(SEPARATOR), b.path.join(SEPARATOR)),
    )
}

/**
 * What was picked, for `uploadOf`: `files` with their paths, the one folder everything is in
 * (`folder`, its name) when a single folder was picked, and how many folders were picked at the top
 */
function picked(files, topFolders) {
    return {
        files: inPathOrder(files),
        folder: topFolders.length === 1 ? topFolders[0] : null,
        folders: topFolders.length,
    }
}

/**
 * The files chosen in a section's picker: the file picker's are loose files, the folder picker's
 * (`webkitdirectory`) the files of one folder, each with its path from that folder down
 * (`webkitRelativePath`), whose first name, the chosen folder's own, is not held to the ignored
 * names
 */
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

/** Every entry of a directory: `readEntries` hands them over in batches, and is asked until a batch is empty */
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

/**
 * Collects the files at and below `entry` into `files`, each with its path under `parents`,
 * whatever the entry's own name is; an entry below it under an ignored name is left out, and so is
 * a file that cannot be read
 */
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

/**
 * The entries of what a drop holds, files and directories. They have to be taken while the `drop`
 * event is being handled: the items are gone once the handler returns, so the walk, which waits,
 * starts from these (`filesOfEntries`).
 */
export function entriesOfDrop(dataTransfer) {
    return [...dataTransfer.items]
        .filter((item) => item.kind === "file")
        .map((item) => item.webkitGetAsEntry())
        .filter(Boolean)
}

/**
 * The files of dropped entries, the dropped directories walked to their last file. A folder dropped
 * alone is taken whatever its name, as the one chosen in the folder picker is; of several dropped
 * items each is held to the ignored names, a folder among them being sent under its own.
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
 * The upload a section makes of what was picked: the name of its Import, if it has one, and each
 * file with the path it is sent under. File upload sends every file under its bare name, whatever
 * folder it was in, and names nothing. Folder upload sends each file's path in the tree: a single
 * folder is the top of the tree, so its own name is the Import's and no part of the paths, while a
 * drop of several items is its own top, its folders named in the paths and counted in the Import's
 * name ("3 folders"); loose files alone name nothing.
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
