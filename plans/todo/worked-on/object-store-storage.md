# Object store Storage

## Goals

- A Library can keep its files on an S3-compatible service (an **Object store**) instead of the server's disk, so the originals of a large Library do not have to fit on the server.
- The choice is per Library: Libraries on **This server** and Libraries on Object stores live side by side on one server.
- The server stays a normal stateful install: the database, staging and a cache remain on local disk, and one server process runs at a time.
- Browsing stays fast when the Object store is remote.

## Decisions

| Area | Decision |
|---|---|
| Purpose | Capacity for originals. Not a backup target, not tiering, not a stateless server. |
| Granularity | Per Library, through `repository.file_store_type` (`fs` or `s3`) and `repository.file_store_config`. |
| What is stored there | Everything the Library's Storage holds: originals, Previews and face images. |
| Local cache | Previews and display face images of Object store Libraries are cached on local disk: one directory for the server, a byte cap, least recently used evicted. A miss reads through; an import writes through. |
| Serving | The server proxies. The content URL, both auth gates, the headers and share links do not change; the browser never talks to the Object store. |
| Import | The original is stored inside the pipeline, as the last stage that touches the file. An asset is complete only once its original is stored. |
| Credentials | Object stores are defined and named in `application.conf`. A Library records only the name of its Object store. No secret is in the database or the UI. |
| Client | AWS SDK for Java v2, async S3 client with multipart enabled, called with a blocking join. Request checksum calculation and response checksum validation are `WHEN_REQUIRED`. |
| Assignment | Chosen in the create-Library form, fixed afterwards. No built-in move between Storages. |
| Library menu | A Library on an Object store carries a cloud icon after its name, in the menu's options and on its trigger. A Library on This server carries none. |
| Who | Anyone who may create a Library may pick any configured Object store. No new Permission. |
| Purge failure | A delete the Storage refuses fails that asset's purge: the row stays purge-pending and the startup requeue retries it. Deleting something already gone is a success. Both stores follow this rule. |
| Outage and misconfiguration | No probing at startup or at Library creation. A store is contacted on first use and errors surface there. |
| Tests | A real server: Garage in docker. `make db` brings it up. |
| Language | **Storage** and **Object store** in user-facing text (in `../../../CONTEXT.md`); `FileStore` stays the code word. "Bucket" is not used in the UI: a People bucket is something else. |

## Design

### Configuration

```hocon
storage.object_stores {
  archive {                      # the name a Library records and the create form shows
    endpoint   = "http://nas.lan:3900"
    region     = "garage"
    bucket     = "altitude"
    access_key = "..."
    secret_key = "..."
    path_style = true
    prefix     = ""              # optional, for a bucket shared with something else
  }
}
# The most local disk the cache of Previews and display face images may take
storage.cache.max_size = 2G
```

`storage.engine.default` is removed: nothing chooses a store for the whole server.

A Library on an Object store has `file_store_type = "s3"` and `file_store_config = {"store": "archive"}`. Its keys mirror the on-disk layout under the Library's ID:

```
<prefix>/<repository id>/files/<id[0:2]>/<id>
<prefix>/<repository id>/preview/<id[0:2]>/<id>.webp
<prefix>/<repository id>/faces/<id[0:2]>/<id>-{display,detected,aligned,aligned-gs}.png
```

A Library whose Object store is no longer in the config fails with a `StorageException` on first use.

### The store interface

`FileStoreService` has no local path in it:

- `addAsset` consumes the staged file: a rename on disk, an upload followed by deleting the staged file on an Object store.
- `assetFile: Path` is replaced by a method that opens a byte window of an original as a stream (`NotFoundException` when it is missing). `RangeStreaming` works over that instead of a `FileChannel`, and takes the total size from the asset's row.
- `purgeAssetById` and `purgeFaceById` throw `StorageException` when a delete fails and succeed when there is nothing to delete.

`app.service.fileStore` is a dispatcher: each call goes to the store of the context's Library (`RequestContext.getRepository`). It holds the one `FileSystemStoreService` and one lazily built `S3StoreService` per configured Object store, the latter behind the cache.

### Import pipeline

`FileStoreFlow` runs after `AddPreviewFlow`, so every decoding stage reads the staged file and the path swap in `FileStoreFlow` goes away. It works on `importParallelism` assets at once, so one large upload does not hold up a Library's import. A failed upload drops the asset like any failed stage, and the discard of the import undoes it.

### Cache

`data/cache/<repository id>/…`, holding Previews and display face images of Object store Libraries only (the other three face images have no reader that serves them). Content is write-once, so nothing is ever stale. An in-memory, access-ordered index tracks size and use; it is rebuilt from the directory at startup. A purge deletes the cached file along with the object. Libraries on This server do not use the cache.

### Serving

`ContentViewController` is unchanged in its routes, gates and headers. A `StorageException` while serving content is a plain-text `503` that is not cached; a cached Preview is served without contacting the Object store.

### Library menu

In the nav's Library menu (`includes/library_menu`), the name of a Library on an Object store is followed by `fa-cloud` (Font Awesome solid, the set the app already ships). The cloud is the mark people read as "not on this machine"; `fa-server` would contradict the name This server, and `fa-database` is the Browse link's icon. The icon is decorative (`aria-hidden`), and the option's `title` and accessible name say it in words: "Clients (Object store: archive)". The trigger shows the current Library the same way, so the Storage of the Library in use is visible without opening the menu: `buildChoiceMenuCtrl` carries the checked option's icon to a choice-only trigger along with its text and `title`. The name still cuts with an ellipsis; the icon does not shrink or wrap.

### Tests and development

One Garage service in `../../../docker-compose.yml`, single node, with a one-shot bootstrap that creates a dev bucket and a test bucket with fixed keys. The image tag is pinned to the current stable release when the task is done (a release with the single-node bootstrap flag, if available, removes the manual layout step).

`S3SuiteBundle` runs on SQLite with its test Libraries on the Object store, and holds the suites that touch files: the store contract, import, purge, prune and content serving. It empties its key prefix at setup. `make test` picks it up through `*SuiteBundle`; `make test-local` leaves it out and stays docker-free.

## Not covered

- Moving an existing Library between Storages. Keys mirror the disk layout, so it can be done offline with a copy tool and one database update.
- Objects orphaned outside the purge pipeline: a discarded import or a deleted Face whose files could not be removed while the Object store was down. There is no tool that reconciles a bucket against the database.
- A retry of purge-pending assets without a restart.
- Showing a Library's Storage anywhere other than the Library menu.
- Backup, durable imports, several server processes.

## Tasks

Server-side tasks are test-driven (red, green, refactor) with integration tests. Each group is one reviewable, mergeable unit.

### A. The store interface stops assuming a local path

No behavior changes for a user; the filesystem store is still the only one.

1. **Store the original as the last file stage.** Move `FileStoreFlow` after `AddPreviewFlow`, run it on `importParallelism` assets, drop the path swap, and make `addAsset` the consumer of the staged file. Rationale: an Object store has no path to hand to later stages, and one rule (the store only consumes) fits both stores.
2. **Replace `assetFile: Path` with a ranged stream.** Add the open-a-window method, move `RangeStreaming` onto it with the size from the asset row, and remove `assetFile`. Tests that read `assetFile` with `Files` read through the store instead. Rationale: this is the leak READINESS.md names; serving is the only production reader of a stored original.
3. **Make a failed delete fail the purge.** `purgeAssetById` and `purgeFaceById` throw on failure and treat a missing file as done; `DeleteAssetFilesFlow` stops swallowing the error, so the asset stays purge-pending. Rationale: a swallowed failure against a network store orphans objects that nothing records.

### B. Storage is chosen per Library

4. **Read Object store definitions from the config.** Parse `storage.object_stores` and `storage.cache.max_size`, add `Const.StorageEngineName.S3`, remove `storage.engine.default` and `Altitude.fileStoreType`. Rationale: secrets stay out of the database, and the dead server-wide switch goes.
5. **Dispatch on the Library's Storage.** `app.service.fileStore` becomes the dispatcher over `file_store_type` and `file_store_config`; `addRepository` refuses a store name that is not configured. Rationale: the columns exist and are ignored today.

### C. The Object store

6. **Garage for development and tests.** Add the Garage service and its bootstrap to `../../../docker-compose.yml`, a commented Object store to `application-dev.conf.example`, the test store to the test config, `S3SuiteBundle`, and `make test-s3` / `make test-focused-s3`. Rationale: the following tasks need a real server to go red against.
7. **`S3StoreService`.** Add the AWS SDK v2 dependency and implement the trait: multipart upload from the staged file, ranged GET, whole-object reads for Previews and face images, deletes, `NotFoundException` for a missing key and `StorageException` for everything else, with the checksum settings above and path-style addressing from the config. Rationale: the feature itself.
8. **The local cache.** A size-capped, least-recently-used cache in front of `S3StoreService` for Previews and display face images, written on import, filled on a miss, emptied of an asset or Face on purge. Rationale: a grid page is up to 500 Preview reads.
9. **Answer a Storage failure on the content route with `503`.** Rationale: an Object store outage is not a missing file and must not be cached as one.

### D. Choosing it, and the guides

10. **Storage field in the create-Library form.** Shown when at least one Object store is configured, listing This server and each Object store by name; `LibraryActionController` passes the choice to `addRepository`. Rationale: the only place a Library's Storage is set.
11. **Mark Object store Libraries in the Library menu.** `library_menu` renders the cloud icon after the name of each Library whose Storage is an Object store and puts the Storage in the option's `title`; `choice-menus.js` carries the checked option's icon to the trigger; the style guide's Library menu specimen gains such a Library. Rationale: Storage is fixed at creation, so the menu is where a user sees which Libraries are not on the server's disk.
12. **`ClearDb` empties Object store Libraries too.** Rationale: the dev tool otherwise leaves every cleared Library's objects behind.
13. **Update the guides.** `../../../altitude/AGENTS.md` (pipeline stage table and order, staging, serving, the store and cache, configuration, the test bundle and Makefile targets), `altitude/views/AGENTS.md` (the Library menu's icon and what a choice-only trigger takes from the checked option, the form's Storage field), `docs/READINESS.md` (the "S3, WebDAV, several storage roots" row, the originals and purge items), `docs/test-coverage.md`, and the docstrings of `StagingService`, `FileStoreService` and `FileStoreFlow`. `CONTEXT.md` already has Storage and Object store.
