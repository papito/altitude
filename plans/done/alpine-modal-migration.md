# Migrate Altitude's modals to Alpine UI Components

## Original goals

Migrate the project's existing modal dialogs to the official Alpine UI Components
modal. Use the grill-me interview to establish the intended behavior and produce
a migration plan before changing application code.

## Status

Executed on 2026-09-05 (units 1-4, on branch `folder-refactor`, uncommitted). What was
built diverges from the plan in these places:

- The official Alpine UI Components modal source (https://alpinejs.dev/component/modal,
  licensed) was reviewed after implementation through the user's account and is not
  copied into the repository. The hosts follow its structure and dependencies (root
  `x-show` with `role="dialog"`, `aria-modal`, `aria-labelledby`; panel with
  `x-trap.noscroll.inert` from `@alpinejs/focus` v3; a close control that clears the
  open state) with these intentional differences: state lives in the shared `modal`
  store instead of a local `x-data` so the one-modal rule spans both hosts and JS can
  open them; Escape is handled once in `global.js` instead of `x-on:keydown.escape`
  on the root; there is no separate overlay element and no `x-transition`, keeping the
  existing backdrop CSS; the backdrop click-to-close wrapper exists only on asset
  detail; `.noreturn.noautofocus` are added so the owner places and restores focus.
  Provenance and versions are in `../../altitude/static/js/lib/README.md`.
- Escape is handled once, in `static/js/global.js`, rather than with a per-host
  `x-on:keydown.escape`; the hosts have no keyboard handlers.
- Validation replacement responses now use `HX-Reswap: outerHTML` (via
  `BaseController.modalFormValidationResponse`); with `innerHTML` the
  validated form was nested inside the submitting form.
- `data-app-modal-return-focus` (a declared control that survives the page
  update) takes precedence over the recorded opener on close, and the folder
  tree rebuild preserves focus by element id, so folder dialogs return focus to
  the folder's menu control (`#folderMenuCtrl-<id>`).
- Asset detail opens immediately with its spinner and the image is loaded
  afterwards, so dismissal is usable during loading; image loads and page
  fetches carry request tokens.
- The unused success-action metadata (`close-folder-context-menu`) and
  `closeOnSuccess="true"` attributes were removed.

## Confirmed decisions

- Use the official Alpine UI Components modal. The user confirmed this target.
- Backdrop clicks leave all six general dialogs open. Backdrop clicks dismiss
  asset detail. The user accepted this recommendation.
- Escape dismisses the active modal even when it contains unsaved edits,
  without a discard confirmation, and restores focus to the opener. Consume
  the keypress while a modal is active so it does not also cancel a background
  person-name edit. Closing view settings retains its immediately applied
  changes. The user accepted this recommendation.
- Dismissing a modal after submission closes it immediately while the submitted
  operation continues. Its result still updates the page and provides feedback.
  A late response must neither reopen the dismissed modal nor close a newer
  modal. The user accepted this recommendation.
- Only one modal may be active at a time, across general dialogs and asset
  detail. A new explicit open replaces the active modal; dialogs do not stack.
  Replacement follows the agreed dismissal rules for edits and submissions.
- Move general-modal sizing into shared CSS: one `:root` variable for the
  existing 400px content width, constrained to fit narrow screens. Remove the
  repeated Scala width constants and width plumbing through requests and
  templates. Titles stay in Scala; asset detail retains image-dependent sizing.
- An HTMX 4 migration is likely, but this plan must not be designed around it.
  The executing agent will resolve HTMX compatibility against the application
  it finds. This plan imposes no HTMX version, upgrade sequence, compatibility
  shim, or abstraction for a future version.

## Current implementation

- Six general dialogs share `#modalContainer` and `#modalContent`: add folder,
  rename folder, delete folder, merge people, choose a person's cover face, and
  view settings.
- Asset detail uses `#imageDetailModalContainer` and `#imageDetailModalContent`.
  It has image-dependent dimensions, asynchronous loading, a spinner, and
  previous/next navigation through the search results.
- Both containers are declared in
  `../../altitude/views/includes/html_common.scala.html`. Visibility and sizing are
  controlled imperatively in `../../altitude/static/js/common/modal.js`.
- `Const.UI` in `../../altitude/src/altitude/core/Const.scala` supplies titles and
  nominal minimum widths for the six general dialogs. All widths currently
  resolve to 400 pixels; the JavaScript applies that value as a content width,
  rather than a CSS minimum width. Asset detail uses asset dimensions instead.
- Twirl and HTMX supply general dialog content. Declarative fragment metadata
  drives opening, initial focus, success events, and closing through
  `../../altitude/static/js/fragments/modal.js`.
- Merge submissions update `#content`, cover-face selections update `#person`,
  and folder success events refresh the folder tree. Request completion must
  preserve these page updates even when the initiating modal has been closed;
  dialog visibility and request completion need independent lifetimes.
- Escape currently closes both containers and also dispatches an event used by
  the inline person-name editor. That editor's handler restores its display
  through an HTMX request, so Escape can dismiss a modal and cancel a background
  edit in the same keypress. Previous/next keyboard events are global.
- Folder validation errors return HTTP 200 with a replacement form, while the
  modal fragment listener treats successful HTTP requests as completed actions.
  The migration must account for this distinction; the actual browser outcome
  has not been verified.
- Asynchronous image loading calls `showAssetDetailModal()` after completion.
  Closing or switching dialogs during loading needs explicit consideration.
- The frontend uses checked-in libraries, native ES modules, and no bundler.
  Main shared styling is in `../../altitude/static/css/core.css`.

## Scope and implementation defaults

Migrate all seven existing modal flows. Preserve their current appearance and
content, apart from the agreed responsive sizing and interaction changes.
Twirl continues to render content, and existing request handlers and domain
events continue to perform folder and people operations. Keep
`frontend-app.js` as the composition root.

Use shared Alpine modal behavior for the general and asset-detail hosts.
Keeping `#modalContent` and `#imageDetailModalContent` avoids unrelated changes
to every caller and lets the two presentations retain their existing layout.
One active-modal owner must govern visibility and dismissal across both hosts.
Do not create separate implementations of focus trapping and dismissal for
the two presentations.

Preserve add-folder autofocus and rename-folder text selection. Provide an
accessible dialog name, keyboard-operable close control, focus containment,
background interaction blocking, and focus restoration. If the opener has been
removed by a page update, restore focus to a relevant surviving control. Avoid
initial focus on a destructive confirmation action. Image navigation keys must
operate only while asset detail is active and must not intercept text editing.

The [HTMX migration plan](htmx-4-migration.md) is related work only. Choose the
actual HTMX event and response APIs at execution time. No migration design or
sequencing from that plan is a prerequisite here.

## Component source

The [official modal page](https://alpinejs.dev/component/modal) lists Alpine.js
v3 and `@alpinejs/focus` v3. The component source was not available in the public
page content inspected during planning, and no official modal source was found
in the project. Obtain the chosen official source and verify its dependencies
before specifying the exact integration API.

This is an execution prerequisite, not a reason to substitute a custom modal
for the confirmed official component. Use the available official source and
its actual required dependencies, preserving the repository's native-module
and checked-in-library approach. Record the selected versions and provenance.

## Implementation units

1. **Move general-modal width ownership to CSS.** Add a shared width variable
   in `../../altitude/static/css/core.css`, preserving the current 400px content
   width on sufficiently wide screens and fitting the dialog, padding, and
   border within smaller viewports. Remove the width assignment from
   `static/js/common/modal.js` and width metadata consumption from
   `static/js/fragments/modal.js`. Remove `DEFAULT_MODAL_WIDTH` and the six
   `*_MODAL_MIN_WIDTH` constants in `Const.UI`, `Api.Modal.MIN_WIDTH`, and
   their template arguments, fragment attributes, controller arguments, and
   request values. This includes the folder action dialogs, the cover-face
   chooser, merge people, and view settings, including validation-response
   rendering. Keep identifiers and other request values intact. This unit
   eliminates presentation data that is passed through the server despite
   having the same value everywhere, and can land before the Alpine change.
   Verify the affected modal endpoints work without a width parameter.

2. **Adopt the official component and shared modal lifecycle.** Obtain and
   inspect the source described above, add its required dependencies, and
   register them before Alpine starts. Adapt the two hosts in
   `views/includes/html_common.scala.html` using shared behavior under
   `static/js/alpine/components/` or the existing shared modal module, keeping
   markup and component inputs minimal. Route existing open/close helpers and
   fragment hydration through Alpine state. Apply the one-modal limit,
   replacement behavior, backdrop policies, Escape handling, and focus rules
   to both hosts. Remove conflicting imperative display toggles and duplicate
   close listeners. Update `static/js/global.js` so closing a modal consumes
   Escape, while Escape outside a modal retains inline-editor behavior.
   Keep initialization idempotent across repeated fragment swaps. Associate
   each explicit open with its own identity so a response for an earlier open
   cannot display a dialog after dismissal or replacement. This unit gives
   every caller one consistent modal lifecycle while retaining existing
   content and request targets.

3. **Make form completion respect modal ownership and validation.** Adapt
   `static/js/fragments/modal.js` and the relevant request/event wiring so
   submitted operations complete independently of the initiating dialog's
   visibility or DOM lifetime. Capture the operation's identity and event
   data before its initiating content can be replaced. Preserve normal page
   updates and dispatch success events exactly once, including when the user
   has closed or replaced the dialog. Only the still-active initiating modal
   may be closed or have its form replaced by that response. Suppress stale
   modal swaps before they mutate a newer dialog; checking only after a swap
   is too late. Distinguish validation responses from completed operations:
   a replacement form containing errors stays open, retains entered values,
   and emits no success event. Prefer the smallest explicit indication using
   existing rendering and response conventions; HTTP success alone is not an
   operation-success signal. After dismissal, report a failed or rejected
   operation through existing snackbar feedback without reopening its form.
   Prevent duplicate submission from an active form while its operation is
   pending. Preserve merge-result, cover-face, and folder-tree updates. This
   unit implements the agreed behavior for submissions that finish after
   dismissal and fixes the validation/success ambiguity on these paths.

4. **Complete asset-detail loading and keyboard integration.** Update
   `static/js/fragments/image-detail.js`,
   `static/js/search-results/detail-navigator.js`, and the associated keyboard
   listeners to use the shared modal lifecycle. Preserve image-dependent
   sizing, the loading indicator, and previous/next navigation through search
   results. Track the current image request separately from the modal open:
   only the latest request for the active asset-detail modal may change its
   image, dimensions, title, loading state, or current-asset selection. Ignore
   superseded image completions after closing, replacement, or rapid
   navigation; none may reopen a modal. Account for asynchronous fetching of
   additional search-result pages as well as image loading. Restrict image
   navigation to active asset detail, and ensure loading failures leave
   dismissal usable and provide feedback. This unit preserves the viewer's
   specialized behavior within the one-modal rule.

Update relevant comments and agent documentation with each unit, rather than
leaving documentation describing removed behavior between merges. Remove code
and metadata made redundant by that unit. Follow the repository logging
conventions for meaningful events; avoid logging keystrokes or sensitive form
contents. Units 3 and 4 depend on unit 2's modal lifecycle.

## Acceptance checks

| Scenario | Expected result |
|---|---|
| Open each of the seven modal flows | Correct title and content, expected initial focus, one active modal |
| Click a general-dialog backdrop | Dialog remains open |
| Click the asset-detail backdrop | Asset detail closes and focus returns appropriately |
| Press Escape with unsaved form edits | Active dialog closes without a prompt; background person-name edit survives |
| Close view settings | Immediately applied settings remain persisted |
| Request another modal | Existing modal is replaced; no competing focus traps or background interaction |
| Submit, close, then open another modal | Operation completes with its normal page update and feedback; the newer modal stays intact |
| Return a validation error while the form is active | Form remains open with entered values and errors; no success event or success feedback |
| Return a validation or request error after dismissal | Feedback appears without reopening the old dialog or replacing a new one |
| Submit repeatedly while pending | One operation is sent from that form |
| Navigate images rapidly or close during loading | Superseded responses cannot change the current image, spinner, title, selection, or modal visibility |
| Use arrow keys outside asset detail or in an editor | No background image navigation or modal reopening |
| Cycle Tab and Shift+Tab, then close | Focus remains in the active modal and returns to the opener or a relevant surviving control |
| Open and close repeatedly after fragment swaps | No duplicate listeners, repeated success events, or lost focus containment |
| Use a narrow viewport | General dialogs fit the viewport; asset detail preserves aspect ratio and usable dismissal |

## Validation and documentation constraints

Follow the repository's frontend guidance: manually verify frontend changes in
the browser and run `make compile` after application edits. Server behavior
changes, if needed, require the prescribed integration-test red-green-refactor
cycle. In particular, removing required width parameters changes the affected
controller request signatures: add focused integration coverage for requests
without those parameters, observe failure before removal, then implement and
refactor. Use the project's applicable SQLite/controller test commands; do not
run `make test`. Frontend-only work is verified manually rather than adding
implementation-mirroring tests. Documentation-only planning does not require
compilation or tests.

Update `../../altitude/views/AGENTS.md` for modal markup, sizing, dismissal, and
hydration, and `../../altitude/AGENTS.md` for any changed module responsibilities.
Review root `../../AGENTS.md` and update it if the implementation changes guidance
there. Update related `CLAUDE.md` and `ARCHITECTURE.md` files if they exist at
execution time; neither was found during this interview. The interview resolved
UI behavior rather than new domain terminology, so no `CONTEXT.md` glossary
entry is needed for the generic modal concept.

This plan records intended behavior. No browser verification of the migration
has been performed, and current-implementation observations above are based on
source inspection.
