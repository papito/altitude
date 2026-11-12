# Encrypted Libraries

## Goals

- A Library can be created **Encrypted**: its originals, Previews and face images are stored encrypted on the server's disk,
  under a key that only its **Pass key** (or its **Recovery key**) unlocks.
- Each Library has one Pass key. An account with access works in an Encrypted Library only after typing it, and the Library
  stays unlocked for that session until the session logs out, ends, or is idle past a timeout.
- Content is decrypted on the fly: a grid of Previews loads as before, and a Video plays and seeks over byte ranges.
- A lost Pass key is not a lost Library while its Recovery key is kept; losing both loses the files.
- Encrypted Libraries are offered on servers whose database is PostgreSQL.

## How it works

**Keys.** Creating an Encrypted Library makes a random 32-byte **Library secret**. It is never stored in the clear: the
`repository_key_slot` table holds it wrapped once per way to unlock it.

```
Pass key ──Argon2id(salt, m=64 MiB, t=3, p=4)──▶ 32-byte wrapping key ─┐
Recovery key (32 random bytes) ──HKDF-SHA256──▶ 32-byte wrapping key ─┴─ AES-256-GCM unwraps ─▶ Library secret
                                                                                                       │
             asset key = HKDF-SHA256(Library secret, "altitude asset v1|<repository id>|<asset id>") ◀───┤
             face key  = HKDF-SHA256(Library secret, "altitude face v1|<repository id>|<face id>")   ◀───┘
```

- A slot holds its kind (`pass_key`, `recovery`), its KDF and parameters as JSON (so they can be raised for new slots), the
  salt, and the wrapped secret (nonce and ciphertext). The wrap's associated data is the repository ID and the slot kind, so a
  slot copied onto another Library or kind does not unwrap.
- A wrong Pass key fails the GCM tag: that is how an unlock is refused. Nothing else checks the Pass key.
- The Pass key is NFC-normalized and never trimmed; 12 to 256 code points. The Recovery key is shown as 13 groups of four
  Crockford base32 characters.
- Changing the Pass key rewraps one slot; the Library secret and every stored file stay as they are.
- Keys per asset and per Face are derived, not stored: no column, no read per request, and a key for one asset says nothing
  about another.

**Files.** The layout under `data/repositories/<id>/` does not change; the bytes do.

| File | Encryption | Associated data |
|---|---|---|
| Original | Tink Streaming AEAD, AES-256-GCM-HKDF, 64 KiB segments, input key the asset key | `original` |
| Preview | AES-256-GCM under HKDF(asset key, `preview`), random nonce | repository ID, asset ID, `preview` |
| Face image (display, detected, aligned, aligned greyscale) | AES-256-GCM under HKDF(face key, kind), random nonce | repository ID, face ID, kind |

Each encrypted file starts with a four-byte format marker (`ALT` and a version byte), so a later format is recognizable. A
segment of an original decrypts on its own, so a byte range reads at most one partial segment on each end; a truncated, swapped
or altered file fails authentication rather than serving wrong bytes.

**The store.** `app.service.fileStore` dispatches on the context Library: `FileSystemStoreService` for a plain Library,
`EncryptedFileSystemStoreService` for an Encrypted one. Both share the path layout (`FileStoreLayout`). An original is read as
a `SeekableByteChannel` (`openAsset`): a `FileChannel` for a plain Library, which `RangeStreaming` still sends with
`transferTo`, and Tink's seekable decrypting channel for an Encrypted one, which it copies through a 64 KiB buffer, the size
coming from the channel. The Encrypted store takes its key from `RequestContext.libraryKey`; without one it throws
`LibraryLockedException` and never falls back to plaintext. A purge needs no key: it deletes files.

**Import.** Storing the original is the last stage that touches the file (`FileStoreFlow` after `AddPreviewFlow`), so every
decoding stage reads the staged plaintext and the store only consumes it: a rename for a plain Library, an encrypt-to-temp,
rename and delete of the staged file for an Encrypted one. Each file queued into an Encrypted Library holds a **lease** on the
Library secret (`PipelineContext.libraryKey`, bound by `PipelineUtils.withContext`), released when the file leaves the
pipeline, completed or dropped, so locking the Library mid-import does not fail the files already queued.

**Unlocking.** `LibraryKeyring` (`app.service.libraryKeys`) holds, in process memory only:

- per session and Library, an unlock with the time of its last request;
- per Library, the Library secret, held while any session has it unlocked or any lease is out, and overwritten and dropped
  when the last one goes.

A session is the login's `sid` claim, a random ID minted at login and kept through every renewal of the token (a rebuild, the
own-password change's new token), so every tab of a browser shares one unlock and another browser unlocks on its own.

- `unlock(sid, account, repository, passKey | recoveryKey)` runs Argon2id under a semaphore (two at a time, so unlock attempts
  cannot exhaust memory), rate-limits failures per account and Library (from the sixth failure in a row, each attempt waits
  2^(n−5) seconds, at most 15 minutes; a success resets it), and records `LibraryUnlocked` or `LibraryUnlockFailed`.
- A request to the Library refreshes its session's unlock. A sweep on the actor system's scheduler, once a minute, ends every
  unlock idle past the `library.unlock_timeout` Setting (seconds, 1800 by default, PostgreSQL only, Live).
- Logout ends the session's unlocks. A revocation of the account's tokens (a password change, a deactivation) and a delete of
  the account end every unlock of its sessions (`AccountVersions.onChange`). A restart ends all of them.
- The Lock control ends the session's unlock of the Library. A change of the Pass key ends every other session's unlock of it.

**The gate.** `requireLogin`, once the account and the path's Library are in the context, refuses a request to an Encrypted
Library that its session has not unlocked: a full page with a 302 to the unlock page carrying its URL as `redirect`, an htmx
request with a 423 and `HX-Redirect` to it, an API request with a JSON 423 (`LIBRARY_LOCKED`), and the content route with a
plain-text 423. A request with a signed content URL carries no session, so it gets the 423 too. The unlock page, its submit and
the Lock route are the only routes of an Encrypted Library that answer while it is locked. An unlocked request has the
Library's key in `RequestContext.libraryKey` for its length. A Guest unlocks as any account with access does.

**Browser cache.** Content of an Encrypted Library is served `Cache-Control: private, no-store`, so the browser keeps no
decrypted Preview or original on its disk; the page's own image cache still reuses an image within a page. Plain Libraries keep
`private, max-age=31536000, immutable`.

**Screens.**

- The New library dialog, on a PostgreSQL server, has an Encrypt switch with the Pass key typed twice. After the add, it shows
  the Recovery key once, with a Copy button and an "I have saved the Recovery key" checkbox that enables Continue; the creator's
  session is unlocked, and Continue goes to the Library's Import page.
- The unlock page (`GET /unlock/r/:repoId`, the login page's layout) names the Library and takes the Pass key, or the Recovery
  key behind "Use the Recovery key instead", and goes to `redirect` (held to a path of this server, as the login's is) or the
  Library's main page.
- The Library menu marks an Encrypted Library with `fa-lock` after its name, and says so in the option's `title` and accessible
  name ("Clients (Encrypted)"); the trigger carries it for the current Library.
- Beside the Library menu, an unlocked Encrypted Library has a Lock control: Lock now for anyone, and for an Admin, Change Pass
  key and New Recovery key, each a dialog that asks for the current Pass key (or the Recovery key, for a change of Pass key).
  A new Recovery key replaces the old slot and is shown once, as at creation.
- The client sends the window to the unlock page on a 423 as it sends it to the login on a 401, from htmx and from the axios
  client.

## Decisions

| Area | Decision |
|---|---|
| Scope of encryption | The Library's files: originals, Previews and the four face images. The database stays plaintext. |
| Engines | PostgreSQL only. On SQLite the New library dialog has no Encrypt switch and `addRepository` refuses a Pass key (`IllegalOperationException`). The schema is the same on both engines. |
| When | Chosen at creation, fixed afterwards. An existing Library is never encrypted, and an Encrypted one never decrypted. `Altitude.repositoriesById` stays a cache that is never invalidated. |
| One Pass key per Library | Shared by every account with access; an account's own password plays no part. |
| Who unlocks | Any account with access to the Library, by the Pass key or the Recovery key. |
| Who changes keys | An Admin, in a session that has the Library unlocked, with the current Pass key (or the Recovery key, for a change of Pass key). |
| Unlock lifetime | Per session: until logout, the token's end, a revocation, the Lock control, a restart, or `library.unlock_timeout` without a request to the Library. |
| Key hierarchy | Pass key and Recovery key slots wrap one Library secret; asset and Face keys are derived from it by HKDF. |
| Libraries | Tink (Streaming AEAD for originals; its HKDF and AES-GCM), Bouncy Castle's Argon2id. Nothing hand-rolled beyond the slot wrap and the derivations above. |
| Imports while locked | None: an upload needs the Library unlocked. A queued file keeps a lease until it leaves the pipeline. |
| Browser cache | `no-store` for an Encrypted Library's content. |
| Language | **Encrypted** (a Library), **Pass key**, **Recovery key**, **Locked**/**Unlocked**, **Unlock**, **Lock**, in `../../../CONTEXT.md`. "Passkey" is not used: it names WebAuthn credentials. |

## Constraints

- The Pass key, the Recovery key, the Library secret and every derived key are never logged, never put in an audit event, and
  never sent to the browser after creation (the Recovery key is shown once).
- An Encrypted Library's store never writes or serves plaintext, and never falls back to the plain store.
- A read by ID is scoped to the Library in the path, as everywhere: the gate does not change what another Library's ID answers
  once unlocked.
- Every route keeps its row in `RouteTableTests`; the table also says what the route answers for a locked Encrypted Library.
- Schema changes go into `all.sql` (both engines) only, with no versioned migration and no `schemaVersion` bump.
- The login token still fits the session cookie with `MAX_GRANTS_PER_ACCOUNT` grants once it carries `sid`.
- Server-side work is test-driven with integration tests; the Encrypted Library suites run in the PostgreSQL bundle.

## Changes

### A. The store reads an original as a channel and stores it last

No behaviour changes for a user.

1. **Store the original as the last file stage.** `FileStoreFlow` runs after `AddPreviewFlow`; the path swap goes, and
   `addAsset` consumes the staged file. Rationale: a stored encrypted original cannot be read by the decoders that follow.
2. **Replace `assetFile: Path` with `openAsset(id): SeekableByteChannel`.** `RangeStreaming` works over a channel and its
   size: `transferTo` for a `FileChannel`, a buffered copy otherwise. `ContentViewController` opens the channel; a missing file
   is still `NotFoundException` before any response. Tests that read `assetFile` read through the store.
3. **Extract `FileStoreLayout`.** The paths of originals, Previews and face images, shared by both stores.

### B. Keys and sessions

4. **Dependencies and `LibraryCrypto`.** Add Tink and a current Bouncy Castle (`bcprov-jdk18on`, excluding the `jdk15on` 1.69
   that jpaseto pulls in, so the classpath holds one of them). `LibraryCrypto` holds the pure operations: make a secret, wrap
   and unwrap a slot, derive asset and Face keys, encrypt and decrypt an original (seekable) and a small file. Unit tests: a
   round trip; random ranges of a decrypted original equal the plaintext's; a wrong Pass key, a slot of another Library or
   kind, a file of another asset, a truncated or altered file all fail; no 64-byte run of the plaintext appears in the
   ciphertext. The Argon2id parameters come from the config, small in the test config.
5. **Schema and the add.** `repository.is_encrypted BOOLEAN NOT NULL DEFAULT FALSE` and `repository_key_slot
   (repository_id, kind, kdf, salt, wrapped, created_at)` keyed by `(repository_id, kind)`, cascading from the Library, in
   both `all.sql` files; `Repository.isEncrypted`, `KeySlot`, `RepositoryKeySlotDao` (opts out of scoping). `addRepository`
   takes an optional Pass key: on PostgreSQL it writes the Library, both slots and the grants in one transaction and answers
   the Recovery key; on SQLite it refuses one.
6. **The session ID.** `TokenClaims.sid`, minted at login, kept by `renew` and by the own-password change's new token; the dev
   user's is fixed. `LoginTokenTests` still fits the cookie at the grant bound.
7. **`LibraryKeyring`.** Unlock, refresh, Lock, the sweep, leases, the semaphore and the failure backoff as **How it works**
   says, with an injectable clock for the tests. Logout, revocation and account delete end unlocks. The `library.unlock_timeout`
   Setting (seed rows, registry, PostgreSQL only). Audit events `LibraryUnlocked`, `LibraryUnlockFailed`,
   `LibraryPassKeyChanged` and `LibraryRecoveryKeyReplaced`, each with the Library's ID in its detail and the client IP.
8. **Changing keys.** `RepositoryService.changePassKey` and `replaceRecoveryKey` (an Admin, the Library unlocked in the
   session, the current key checked by unwrapping), each rewriting one slot; a Pass key change ends the other sessions'
   unlocks after commit.

### C. Enforcement and encryption

9. **The gate.** `requireLogin` refuses a locked Encrypted Library as **How it works** says, and binds `RequestContext.
   libraryKey` for an unlocked one; `requireLoginOrSignature` answers a signed request to an Encrypted Library with the 423.
   `RouteTableTests` probes every route against a locked Encrypted Library: the unlock page, its submit and Lock answer, every
   other route gets the locked answer and changes no row.
10. **`EncryptedFileSystemStoreService` and the dispatcher.** The Encrypted store as **How it works** says; `app.service.
    fileStore` dispatches on `Repository.isEncrypted`. The upload takes a lease per queued file into `PipelineContext`;
    `PipelineUtils.withContext` binds it; the pipeline's end releases it. Tests: an import into an Encrypted Library stores no
    plaintext; the content route serves the original, a range of it, a Preview and a face image byte for byte; a Lock during an
    import completes the queued files; prune, purge and the startup requeue run on a locked Library.
11. **Content headers.** `no-store` for an Encrypted Library's content, the plain Library's headers unchanged.

### D. Screens and guides

12. **Unlock and Lock routes.** `GET /unlock/r/:repoId`, `POST /unlock/r/:repoId`, `POST /lock/r/:repoId`, the unlock page
    template, and the 423 handling in `js/listeners/htmx-requests.js` and `js/http/client.js`.
13. **The New library dialog.** The Encrypt switch and the two Pass key fields on PostgreSQL, the Recovery key step,
    `LibraryActionController` passing the Pass key to `addRepository`.
14. **The nav.** `fa-lock` in the Library menu (options and trigger) and its `title`; the Lock control with its Admin dialogs;
    the style guide's specimens of both.
15. **Settings screen.** `library.unlock_timeout` in its group, on PostgreSQL.
16. **Guides.** `../../../altitude/AGENTS.md` (the pipeline's stage order, the store and its dispatcher, the keyring and the gate,
    leases, the token's `sid`, the audit events, the Setting, the content headers), `views/AGENTS.md` (the unlock page, the
    Library menu's lock, the Lock control and dialogs, the New library dialog's Encrypt step, the 423), `CONTEXT.md` (the
    terms in **Decisions**), `docs/READINESS.md` (the Encryption at rest row: what is and is not covered, core dumps off in
    deployment since an unlocked secret is in memory, the staging directory on an encrypted volume or tmpfs), and
    `docs/test-coverage.md`.

## Not covered

- The database: file names, metadata, GPS, People names, face and Scene embeddings, checksums and the audit log stay
  plaintext. Volume encryption under the database covers a stolen disk.
- Staging: a file is plaintext in `../../../data/staging` while it is imported, and startup empties staging.
- Encrypted Libraries on SQLite servers, and on Storage other than this server's disk.
- Encrypting an existing Library, decrypting an Encrypted one, and changing the Library secret (a rewrite of every file).
- Published read-only albums with a Pass key of their own. The derived asset keys allow it: such an album can carry only its
  assets' keys, wrapped under its own Pass key, and reveal nothing of the rest of the Library.
- Signed content URLs to an Encrypted Library.
- Other ways to unlock (a WebAuthn passkey through its PRF extension, a key in a TPM or a KMS); each would be another slot kind.
- Wiping key material from JVM memory: dropped references and overwritten arrays are best effort.
- Recovering a Library whose Pass key and Recovery key are both lost.

## Verification

- `make test-psql` and `make test` pass; the Encrypted Library suites run in the PostgreSQL bundle, and the SQLite bundle
  covers the refusal of a Pass key.
- On a second app instance over a scratch PostgreSQL database (not the dev database): create an Encrypted Library, keep the
  Recovery key, import images and a Video, and check that the files under `data/repositories/<id>/` are not readable images;
  browse the grid, open the Detail panel, play and seek the Video; let the timeout pass and see the next action land on the
  unlock page; unlock with the Recovery key; change the Pass key and see another browser's session locked; log out and in and
  see the Library locked.
