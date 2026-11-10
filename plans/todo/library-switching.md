# Create Libraries and switch between them

## Goals

- The nav's Browse goes to the one repository created at setup. Users can create a new one and switch to it.
- Browse opens a flyout menu, like the results toolbar's Group and Sort menus, where the user picks the repository to switch to.
- The last entry is always the one that creates a repository. It opens a dialog asking for the name.
- Submitting runs the common-sense checks: no empty name, no name that already exists ignoring case, and so on.

## Decisions

| Concern | Decision |
|---|---|
| The word | **Library** in UI text and the glossary. The code keeps `Repository`. The setup form already says "Library name" |
| The Browse cell | The whole cell is a choice menu trigger reading "Browse · <Library name> ▾", with the Browse icon. It keeps `aria-current="page"` while Browse is shown |
| Menu entries | The Libraries the user owns, sorted by name: case-insensitive and natural ("Client 2" before "Client 10"). Names only, with the current Library checked. A separator, then "New library…" |
| Picking a Library | A page load of that Library's Browse (`/r/<id>`), with Browse's defaults. Picking the current Library goes back to its Browse from anywhere, the way the Browse link did |
| Long names | The trigger and the entries cut a long name with an ellipsis; the full name is the `title` |
| Who creates | Admins only. "New library…" is rendered only for an Admin, and its routes refuse anyone else. The creator owns the new Library |
| The dialog | A popover dialog, as Add album opens, anchored below the Browse cell and centered on it. Its title is "New library": one name field, Create and Cancel. Enter submits, Esc cancels, and focus returns to the trigger. Errors show under the field, and the values are kept |
| Name normalization | Trim, collapse each run of whitespace to one space, then Unicode NFC. The name is stored that way |
| Name validity | Required, 2 to 80 characters after normalization (`MIN_`/`MAX_REPOSITORY_NAME_LENGTH`), no control characters |
| Name uniqueness | Instance-wide: across every owner, compared lowercased after normalization. "Client  Smith" and "client smith" are the same name. The database enforces it, so two creates racing on one name leave one Library |
| Error texts | "Enter a name for the library", "Use at least 2 characters", "Use at most 80 characters", "Remove the control characters", "A library named “<name>” already exists" |
| After Create | The browser goes to the new Library's Import page (`/pipeline/r/<newId>`) |
| Setup | The first Library's name follows the same normalization and validity rules. The form is otherwise unchanged |
| Landing (login and `/`) | The account's last active Library, read from the database. Any full page load of a Library records it: the main page in any view, an asset's address, Import, Settings, the Style Guide. If it is missing or not owned, the user's first owned Library by name is used and recorded |
| The login token | Stops carrying `lastActiveRepoId`, which went stale on the first switch |
| Tabs | Each page's URL names its Library, so tabs are independent. The last tab loaded decides where the next login lands |
| Browser tab title | The main page is "<Library> · Altitude". Import, Settings and the Style Guide are "<Page> · <Library> · Altitude". Setup is unchanged |
| UI text | "in this repository" becomes "in this library", and so does every other user-facing "repository" |
| Per-browser state | Unchanged. The map's remembered views are already keyed by the Library. The rest (theme, dock, View settings, People buckets, Curation switches) is preference that carries across Libraries |
| Out of scope | Renaming and deleting a Library, membership and roles, the never-invalidated repository cache (nothing changes a cached Library yet), the user-wide import ticker (an Import page shows imports of the user's other Libraries running in other tabs), and a guard against leaving the Import page mid-upload (as today with any nav link) |

## Constraints

- Schema changes go into both `all.sql` files only, with no versioned migration and no `schemaVersion` bump. The database is recreated.
- Libraries are owned, not shared, so the menu never needs the owner beside a name.
- File storage needs nothing new: `data/repositories/<id>/…` is created lazily by `FileSystemStoreService` on the first write.

## Changes

Groups A to C are server work, red-green with integration tests, each test opening with the Setup / Assertions Scaladoc. The
uniqueness race is proven on PostgreSQL (`make test-focused-psql`), since SQLite's single writer serializes it. Groups D and E are
front-end code and docs, checked in the app.

### A. Library names: the rule, the column and the index

1. **Tests first (`RepositoryServiceTests`).**
   - A name is stored trimmed, with its whitespace collapsed and in NFC.
   - Empty, one-character, 81-character and control-character names fail with a `ValidationException` naming the field.
   - A second Library whose name differs only in case, spacing or Unicode composition fails with `DuplicateException`, whether
     its owner is the same Admin or another one.
   - Two transactions adding the same name concurrently leave exactly one Library (on PostgreSQL).
2. **The schema (`postgres/all.sql`, `sqlite/all.sql`, `RepositoryRow`, `jdbc.RepositoryDao`).** `repository.name_lc` is NOT NULL,
   with `CREATE UNIQUE INDEX repository_01 ON repository (name_lc)`. The key is computed in Scala rather than with SQL `lower()`,
   because SQLite's `lower()` folds ASCII only; this matches `album.name_lc`. `add` writes it, and a unique violation becomes
   `DuplicateException` through `Util.newDuplicateExceptionOrRethrow`.
3. **The rule (`Repository`, `RepositoryService.addRepository`).** One function normalizes and validates a Library name, and the
   model exposes `nameLowercase` as `Album` does. `addRepository` applies it, so setup and the new dialog share it, and turns a
   duplicate into `DuplicateException` with the "already exists" text. `SetupFormController` maps a `ValidationException` from it
   to the field's error, as it maps the request validator's.
4. **Test fixtures (`TestContext.persistRepository`).** Each fixture Library gets a unique name ("Test Repository 1", "… 2", …), so a
   suite with two Libraries does not hit the new index.

### B. Listing Libraries and landing in one

5. **Tests first (`IndexControllerTests`, `LoginControllerTests`, `RepositoryServiceTests`).**
   - `getOwnedBy` lists only the account's Libraries, sorted case-insensitively and naturally.
   - Login and `/` open the last active Library. After a page load of another owned Library, `/` opens that one, even with a token
     issued before the switch.
   - A last active Library that is missing, or owned by someone else, falls back to the first owned Library by name and is
     recorded. A login without a last active Library no longer fails with a 500.
   - Each full page route records its Library, and a load of the Library already recorded writes nothing.
6. **Listing and landing (`RepositoryService`, `RepositoryDao`).** `getOwnedBy(account)` queries by `owner_account_id` and sorts
   in Scala with a natural, case-insensitive comparator; there are only a few Libraries. `landingRepositoryOf(user)` replaces
   `getDefaultRepository`, which assumed one Library.
7. **The token and the redirects (`PasetoService`, `IndexController`, `SessionController`).** The `lastActiveRepoId` claim is
   dropped. `/`, the dev-user path and the login redirect use `landingRepositoryOf`, which removes the `.get`s on
   `lastActiveRepoId`.
8. **Recording the last active Library (`UserService`, the page routes).** `rememberLastActiveRepository(user, repoId)` is one
   conditional `UPDATE … WHERE last_active_repo_id IS DISTINCT FROM ?` (`IS NOT` on SQLite). It runs from `IndexController`
   `repositoryView` and `assetView`, `ImportController.pipeline`, the Settings page and the Style Guide page. Partials and API
   routes do not record it.

### C. The New library dialog

9. **Tests first (new `LibraryActionControllerTests`).**
   - The dialog route renders the form for an Admin and is refused for a non-Admin.
   - A valid POST creates a Library owned by the caller, responds with a redirect to `/pipeline/r/<newId>`, and records nothing
     until that page loads.
   - Empty, too-short, too-long, control-character and duplicate names (another case, spacing, or another Admin's) re-render the
     form with the field's error and the typed value.
   - A non-Admin POST is refused and creates nothing, and an anonymous one is a 401.
10. **The routes and template (`LibraryActionController`, `htmx/new_library_dialog.scala.html`, `Api.Field.Library`,
    `Const.UI`).**
    - `GET /htmx/library/r/:repoId/dialogs/new` and `POST /htmx/library/r/:repoId/new`, behind `requireLogin` and `requireAdmin`.
    - The template follows `add_album_dialog`: `data-app-fragment="modal"`, the autofocus selector, and validation responses
      through `dialogFormValidationResponse`.
    - Success answers with `HX-Redirect`.
    - `Const.UI` gains `NEW_LIBRARY_DIALOG_TITLE` ("New library") and `NEW_LIBRARY_OPTION` ("New library…"). The controller logs
      the creation on INFO.

### D. The nav's Browse menu

11. **The nav template (`includes/nav.scala.html`, `NavController`).**
    - The Browse cell holds a choice menu host, `data-choice-menu="Browse"` with the database icon.
    - It has one option per owned Library: a `menuitemradio` that loads `/r/<id>`, with the current Library `aria-checked`. Then a
      separator and, for an Admin, the "New library…" dialog trigger, a plain `menuitem` that opens the popover dialog anchored
      to the trigger.
    - The nav reload renders the same.
12. **The choice menu builder (`common/context-menu-markup.js`, `common/choice-menus.js`, `global.js`).**
    - `buildChoiceMenuCtrl` learns two option kinds: navigation options, which go to their `href` after closing the menu, and
      action options after a separator, which are not radios and keep their dialog-trigger behavior.
    - The nav's host is built on every page with a nav, and again after each nav reload swaps the nav's content. That reload
      happens every 5 seconds on Import, so the open menu must survive it: no reload while the panel is `:popover-open`.
13. **Import gets the shared hosts (`pipeline.scala.html`, `upload-form-page.js`).** The page includes `html_common`, registers
    `registerHtmlCommonBindings` and starts Alpine, as Settings does, so the popover dialog and snackbar exist there.
14. **Styles and the style guide (`core.css`, `style_guide.scala.html`).** The trigger's Library name is cut with an ellipsis to a
    maximum width, and the separator and action entry get their style. The style guide shows the nav's Browse menu with a long
    name and the New library dialog with an error.

### E. Wording, titles, docs

15. **"Library" in UI text (`includes/search_results.scala.html`, `htmx/triage_scope_dialog.scala.html`).** "in this repository"
    becomes "in this library", and "results in the repository" becomes "results in the library". The assertions in
    `SearchResultsControllerTests` and `TriageControllerTests` follow.
16. **Page titles (`index`, `pipeline`, `settings`, `style_guide`).** The titles follow the Decisions table, read from the request's
    Library.
17. **Docs.**
    - `altitude/AGENTS.md`: the Library name rule, owned listing, landing and last active recording, and the token without the
      claim.
    - `views/AGENTS.md`: the nav's Browse menu, choice menus with navigation and action options, the New library dialog, the Import
      page's shared hosts, and the wording table rows.
    - `docs/READINESS.md`: an Admin may own several Libraries. The second-user item loses its login `.get` and default-repository
      redirect.
    - `docs/test-coverage.md`: the new tests.
    - `CONTEXT.md` already defines Library and the Browse choice menu.

## Verification

- `make test` passes, and `make test-focused-psql` proves the uniqueness race red without the index and green with it.
- In the app, as an Admin:
  - Create "Client Smith". Land on its Import page, with the nav reading "Browse · Client Smith" and the tab "Import · Client
    Smith · Altitude".
  - Switch back to the setup Library through the menu.
  - Try "client  smith", "C", an empty name, and an 81-character name, and see each error.
  - Log out and in, and land in the Library loaded last.
- Open two tabs in two Libraries. Each keeps its own; the last loaded decides the next login.
- On Import, Settings and the Style Guide, the menu opens, survives the Import page's nav refresh, and opens the dialog.
