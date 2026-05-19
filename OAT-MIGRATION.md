# Oat UI Migration Report

This document maps the UI patterns currently used in `altitude/views` to their
equivalent [Oat UI](https://oat.ink/components/) components, so that a
migration from the hand-rolled CSS/JS approach to the Oat component library can
be planned component by component.

---

## Summary table

| Current pattern | Oat equivalent | Type |
|---|---|---|
| `.action-button` (custom CSS) | `<button>` (classless) + `data-variant` | Native HTML |
| Custom `#modalContainer` + `.modal-box` | `<dialog>` | Native HTML |
| `#snackbar` (custom JS) | `window.Oat.toast()` | Web Component / JS |
| FontAwesome `fa-spinner fa-spin` | `aria-busy="true"` on a container | Native HTML |
| Native `<progress>` (unstyled) | `<progress>` (auto-styled) | Native HTML |
| `<select>` with hand-rolled CSS | `<select>` (auto-styled) | Native HTML |
| `<input>` / `<label>` / `<textarea>` | Same elements (auto-styled) | Native HTML |
| `<input type="checkbox">` | Same element (auto-styled) | Native HTML |
| `<input type="radio">` | Same element (auto-styled) | Native HTML |
| `<tab-container>` / `role="tablist"` | `<ot-tabs>` | Web Component |
| Folder context menu (`.menu` divs) | `<ot-dropdown>` | Web Component |
| `.error` div / `#serverError` | `<div role="alert" data-variant="error">` | Native HTML |
| Custom nav / sidebar | `<nav>` (auto-styled) | Native HTML |

---

## 1. Buttons

### Current usage

Every interactive button uses the custom `.action-button` CSS class.  Variants
are expressed via additional classes: `.success`, `.warning`, `.error`, `.small`.

**Files:**
- `altitude/views/includes/batch_ops.scala.html` — Move, Recycle, Deselect All, Purge, Restore
- `altitude/views/htmx/delete_folder_modal.scala.html` — Delete confirmation button
- `altitude/views/htmx/merge_people_modal.scala.html` — Merge action
- `altitude/views/htmx/setup_form.scala.html` — "Dazzle Me" submit button
- `altitude/views/htmx/trashbin_header.scala.html` — "Purge recycle bin"
- `altitude/views/htmx/upload_form.scala.html` — "Upload from computer" / "Cancel"
- `altitude/views/includes/search_results.scala.html` — "View" settings button

**Current example:**
```html
<!-- core.css defines .action-button, .action-button.success, .warning, .error -->
<button class="action-button success drag-drop">
  <i class="fas fa-truck-moving"></i>Move
</button>

<button class="action-button warning" @click="...">
  <i class="fas fa-trash"></i>Recycle
</button>

<button class="action-button error"
        hx-delete="/htmx/trash/..."
        hx-trigger="click">
  Purge recycle bin
</button>
```

### Oat equivalent

Oat auto-styles `<button>` elements without any class.  Semantic variants are
expressed with `data-variant`.

```html
<!-- Default button -->
<button>Move</button>

<!-- Success variant -->
<button data-variant="success">Move</button>

<!-- Warning variant -->
<button data-variant="warning">Recycle</button>

<!-- Destructive variant -->
<button data-variant="error">Purge recycle bin</button>

<!-- Small / secondary -->
<button data-variant="outline">
  <i class="fas fa-cog"></i> View
</button>
```

---

## 2. Modal / Dialog

### Current usage

Two custom modal containers live in `html_common.scala.html`.  Each consists of
a backdrop div, a `.modal-box` with `.modal-toolbar` (title + close button), and
a content div.  Content is injected via HTMX.  The modal is shown/hidden with
hand-rolled JS (`modal.js`).

**Files:**
- `altitude/views/includes/html_common.scala.html` — modal infrastructure (both general and image-detail modal containers)
- `altitude/views/htmx/add_folder_modal.scala.html` — Add folder form inside modal
- `altitude/views/htmx/rename_folder_modal.scala.html` — Rename folder form
- `altitude/views/htmx/delete_folder_modal.scala.html` — Delete confirmation
- `altitude/views/htmx/choose_person_cover_face_modal.scala.html` — Face picker
- `altitude/views/htmx/merge_people_modal.scala.html` — Merge person confirmation
- `altitude/views/htmx/view_image_detail_modal.scala.html` — Full-size asset view
- `altitude/views/htmx/view_settings_modal.scala.html` — Column visibility settings

**Current example:**
```html
<!-- html_common.scala.html — shared infrastructure -->
<div id="modalContainer">
  <div class="modal-box">
    <div class="modal-toolbar">
      <div class="modal-title">Title!</div>
      <div class="close-modal">&times;</div>
    </div>
    <div id="modalContent">
      <!-- HTMX injects fragment here -->
    </div>
  </div>
</div>

<!-- A modal fragment loaded by HTMX -->
<form data-app-fragment="modal"
      data-app-modal-title="Add folder"
      data-app-modal-close-on-success="true"
      hx-post="/htmx/folder/r/.../add">
  <input type="text" name="name" placeholder="New folder name">
</form>
```

### Oat equivalent

Oat auto-styles the native `<dialog>` element and wires the close button
automatically via `command="close"`.

```html
<!-- Static modal markup -->
<dialog id="addFolderDialog">
  <h2>Add folder</h2>
  <form method="dialog">
    <label for="folderName">Folder name</label>
    <input id="folderName" type="text" placeholder="New folder name" required>
    <menu>
      <button>Save</button>
      <button command="close" data-variant="outline">Cancel</button>
    </menu>
  </form>
</dialog>

<!-- Open it from JS -->
<script>
  document.getElementById("addFolderDialog").showModal();
</script>

<!-- Or use a trigger button with popovertarget -->
<button popovertarget="addFolderDialog">Add folder</button>
```

---

## 3. Snackbar / Toast

### Current usage

A `#snackbar` div is declared in `html_common.scala.html` and shown/hidden
programmatically by `js/common/snackbar.js`.  It supports three flavours:
success, warning, and error.  It auto-dismisses after 3 seconds.

**Files:**
- `altitude/views/includes/html_common.scala.html` — `<div id="snackbar">` container
- `altitude/static/js/common/snackbar.js` — `showSuccessSnackBar`, `showWarningSnackBar`, `showErrorSnackBar`

**Current example:**
```html
<!-- html_common.scala.html -->
<div id="snackbar"></div>
```
```js
// snackbar.js
import { showSuccessSnackBar } from "/static/js/common/snackbar.js"
showSuccessSnackBar("Assets moved successfully")
```

### Oat equivalent

Oat ships a `toast()` API on `window.Oat` (loaded via `oat.min.js`).  No DOM
container needs to be declared manually.

```js
// Success
window.Oat.toast("Assets moved successfully", { variant: "success" })

// Warning
window.Oat.toast("Some files were skipped", { variant: "warning" })

// Error
window.Oat.toast("Upload failed", { variant: "error" })
```

---

## 4. Spinner / Loading indicator

### Current usage

FontAwesome's `fa-spinner fa-spin` is used inline wherever a loading state is
needed.  The image-detail modal uses it controlled by an Alpine.js `x-show`
binding.

**Files:**
- `altitude/views/includes/html_common.scala.html` — `#imageDetailSpinner` while the asset detail modal content is loading

**Current example:**
```html
<div id="imageDetailSpinner"
     x-data
     x-show="$store.imageDetailLoading.value">
  <i class="fas fa-spinner fa-spin"></i>
</div>
```

### Oat equivalent

Oat uses the native `aria-busy="true"` attribute pattern for spinners,
rendering an accessible animated indicator with no extra markup.

```html
<!-- Inline spinner on the container while loading -->
<div id="imageDetailSpinner" aria-busy="true" aria-label="Loading…" hidden></div>

<!-- Show / hide via JS -->
<script>
  const spinner = document.getElementById("imageDetailSpinner")
  spinner.hidden = false          // show
  spinner.removeAttribute("aria-busy") // hide when done
</script>
```

---

## 5. Progress bar

### Current usage

Native `<progress>` HTML element used in the file-upload form.  Styled via
inline CSS in the template.

**Files:**
- `altitude/views/htmx/upload_form.scala.html` — `#progressBar` upload progress

**Current example:**
```html
<div id="progressBarControl" hidden>
  <progress id="progressBar" value="0" max="100"></progress>
</div>
```

### Oat equivalent

Oat auto-styles the native `<progress>` element — no class changes are needed,
only removing the hand-rolled CSS.

```html
<progress id="progressBar" value="0" max="100"></progress>
```

---

## 6. Select / Dropdown (sort control)

### Current usage

A native `<select>` element with heavily custom CSS (custom arrow SVG,
background colour, border) is used as the sort-order picker in the search
results control bar.

**Files:**
- `altitude/views/includes/search_results.scala.html` — `#sortOptions` select

**Current example:**
```html
<select id="sortOptions"
        class="sort-dropdown"
        hx-get="/htmx/search/..."
        hx-trigger="change">
  <option value="original_created_at1">Date Created (Newest)</option>
  <option value="original_created_at0">Date Created (Oldest)</option>
  <!-- … -->
</select>
```

### Oat equivalent

Oat auto-styles `<select>` — the `.sort-dropdown` class and its custom CSS can
be removed entirely.

```html
<select id="sortOptions"
        hx-get="/htmx/search/..."
        hx-trigger="change">
  <option value="original_created_at1">Date Created (Newest)</option>
  <option value="original_created_at0">Date Created (Oldest)</option>
  <!-- … -->
</select>
```

For a richer popover-style menu, `<ot-dropdown>` can be used instead:

```html
<ot-dropdown label="Sort by">
  <button data-value="original_created_at1">Date Created (Newest)</button>
  <button data-value="original_created_at0">Date Created (Oldest)</button>
</ot-dropdown>
```

---

## 7. Text inputs, labels, and forms

### Current usage

Standard `<input>`, `<label>`, and `<form>` elements are used across all forms.
Styling is applied globally in `core.css` (padding, border, border-radius, etc.)
and inline in individual templates.

**Files:**
- `altitude/views/htmx/add_folder_modal.scala.html` — folder name text input
- `altitude/views/htmx/rename_folder_modal.scala.html` — rename text input
- `altitude/views/htmx/edit_person_name.scala.html` — inline person name editor
- `altitude/views/htmx/setup_form.scala.html` — full setup form (text, email, password)
- `altitude/views/login.scala.html` — login credentials

**Current example:**
```html
<div>
  <label for="fieldname">Library name</label>
  <input type="text"
         id="fieldname"
         name="name"
         placeholder="Library name"
         autocomplete="off">
</div>
<div>
  <label for="adminEmail">Administrator Email</label>
  <input type="email" id="adminEmail" name="email">
</div>
<div>
  <label for="password">Password</label>
  <input type="password" id="password" name="password">
</div>
```

### Oat equivalent

Oat auto-styles all standard form elements.  The custom `input, textarea { … }`
block in `core.css` can be removed.

```html
<form>
  <label for="repoName">Library name</label>
  <input type="text" id="repoName" name="name" placeholder="Library name" autocomplete="off">

  <label for="adminEmail">Administrator Email</label>
  <input type="email" id="adminEmail" name="email" required>

  <label for="password">Password</label>
  <input type="password" id="password" name="password" required>

  <button type="submit">Dazzle Me</button>
</form>
```

---

## 8. Checkboxes

### Current usage

Native `<input type="checkbox">` is used in the view settings modal to let the
user toggle which metadata columns are visible in the asset grid.

**Files:**
- `altitude/views/htmx/view_settings_modal.scala.html` — file name, date, dimensions, size toggles

**Current example:**
```html
<div>
  <input type="checkbox" id="fileNameCheckbox" value="fileName">
  <label for="fileNameCheckbox">File Name</label>
</div>
<div>
  <input type="checkbox" id="dimensionsCheckbox" value="dimensions">
  <label for="dimensionsCheckbox">Media Dimensions</label>
</div>
```

### Oat equivalent

Oat auto-styles checkboxes — no class changes needed.

```html
<label>
  <input type="checkbox" name="fields" value="fileName"> File Name
</label>
<label>
  <input type="checkbox" name="fields" value="dimensions"> Media Dimensions
</label>
```

---

## 9. Radio buttons

### Current usage

A group of radio buttons lets the user filter the people list by type
(Verified / Hidden / Unverified / All).

**Files:**
- `altitude/views/htmx/people.scala.html` — `#peopleTypeFilter` radio group

**Current example:**
```html
<div id="peopleTypeFilter">
  <div class="radio-group">
    <label>
      <input type="radio" name="peopleTypeFilter" value="complete"
             hx-get="/htmx/people/..." hx-trigger="change">
      Verified
    </label>
    <label>
      <input type="radio" name="peopleTypeFilter" value="hidden"
             hx-get="/htmx/people/..." hx-trigger="change">
      Hidden
    </label>
  </div>
</div>
```

### Oat equivalent

Oat auto-styles radio inputs.  The custom `.radio-group` flex layout CSS can be
replaced with Oat's utility classes or kept as a minimal layout helper.

```html
<fieldset>
  <legend>Filter people</legend>
  <label><input type="radio" name="peopleTypeFilter" value="complete"> Verified</label>
  <label><input type="radio" name="peopleTypeFilter" value="hidden">   Hidden</label>
  <label><input type="radio" name="peopleTypeFilter" value="incomplete">Unverified</label>
  <label><input type="radio" name="peopleTypeFilter" value="all">       All</label>
</fieldset>
```

---

## 10. Tabs

### Current usage

The left-hand explorer panel uses a `<tab-container>` web component (GitHub's
`@github/tab-container` element) with `role="tablist"` / `role="tab"` /
`role="tabpanel"` ARIA attributes.  Styling comes from `tabs.css` using
attribute selectors on those ARIA roles.

**Files:**
- `altitude/views/index.scala.html` — Folders / Albums / People tabs

**Current example:**
```html
<tab-container>
  <div role="tablist">
    <button type="button" id="foldersTab" role="tab" data-tab-container-no-tabstop>
      <a href="#folders" hx-get="/htmx/folder/..." hx-target="#explorerPanelContent">
        <i class="fas fa-folder"></i> Folders
      </a>
    </button>
    <button type="button" id="albumsTab" role="tab" data-tab-container-no-tabstop>
      <a href="#albums" hx-get="/htmx/album/..." hx-target="#explorerPanelContent">
        <i class="fas fa-images"></i> Albums
      </a>
    </button>
    <button type="button" id="peopleTab" role="tab" data-tab-container-no-tabstop>
      <a href="#people" hx-get="/htmx/people/..." hx-target="#explorerPanelContent">
        <i class="fas fa-users"></i> People
      </a>
    </button>
  </div>
  <div id="explorerPanelContent" role="tabpanel" class="tab-content"></div>
</tab-container>
```

### Oat equivalent

Oat provides the `<ot-tabs>` Web Component.  Tab content can still be loaded
via HTMX — simply trigger the HTMX request on the `ot-tabs` `change` event.

```html
<ot-tabs selected="folders">
  <button slot="tab" value="folders">
    <i class="fas fa-folder"></i> Folders
  </button>
  <button slot="tab" value="albums">
    <i class="fas fa-images"></i> Albums
  </button>
  <button slot="tab" value="people">
    <i class="fas fa-users"></i> People
  </button>

  <!-- Panels: content loaded lazily via HTMX -->
  <div slot="panel" value="folders"
       hx-get="/htmx/folder/r/.../tab"
       hx-trigger="revealed"
       id="explorerPanelContent"></div>
  <div slot="panel" value="albums"
       hx-get="/htmx/album/r/.../tab"
       hx-trigger="revealed"></div>
  <div slot="panel" value="people"
       hx-get="/htmx/people/r/.../tab"
       hx-trigger="revealed"></div>
</ot-tabs>
```

---

## 11. Context menu / Folder actions menu

### Current usage

Each folder row has a hidden `.menu` div that is populated via HTMX when the
user clicks the `&#10247;` (⁇) character link.  The menu contains plain
`<span>` elements that each trigger their own HTMX request.

**Files:**
- `altitude/views/htmx/folder_children.scala.html` — per-folder menu div
- `altitude/views/htmx/folders.scala.html` — root folder menu div
- `altitude/views/htmx/folder_context_menu.scala.html` — Add / Rename / Delete actions

**Current example:**
```html
<!-- trigger in folder_children.scala.html -->
<a href="#"
   hx-get="/htmx/folder/.../context-menu"
   hx-swap="innerHTML"
   hx-target="#menu-@{folder.persistedId}"
   hx-trigger="click">&#10247;</a>

<!-- The loaded fragment (folder_context_menu.scala.html) -->
<span hx-get="/htmx/folder/.../modals/add-folder"
      hx-target="#modalContent"
      hx-trigger="click">Add folder</span>

<span hx-get="/htmx/folder/.../modals/rename-folder"
      hx-target="#modalContent"
      hx-trigger="click">Rename</span>

<span hx-get="/htmx/folder/.../modals/delete-folder"
      hx-target="#modalContent"
      hx-trigger="click">Delete</span>
```

### Oat equivalent

`<ot-dropdown>` renders an accessible popover menu without any manual
show/hide logic.

```html
<ot-dropdown>
  <!-- Trigger -->
  <button slot="trigger" data-variant="ghost" aria-label="Folder options">&#10247;</button>

  <!-- Menu items -->
  <menu>
    <li><button hx-get="/htmx/folder/.../modals/add-folder"
                hx-target="#modalContent"
                hx-trigger="click">Add folder</button></li>
    <li><button hx-get="/htmx/folder/.../modals/rename-folder"
                hx-target="#modalContent"
                hx-trigger="click">Rename</button></li>
    <li><button data-variant="error"
                hx-get="/htmx/folder/.../modals/delete-folder"
                hx-target="#modalContent"
                hx-trigger="click">Delete</button></li>
  </menu>
</ot-dropdown>
```

---

## 12. Error / Alert messages

### Current usage

Form validation errors use a `<div class="error">` pattern inside each form.
Server-level messages use `#serverError` and `#serverWarning` divs in
`setup.scala.html`.

**Files:**
- `altitude/views/htmx/add_folder_modal.scala.html` — inline field error
- `altitude/views/htmx/rename_folder_modal.scala.html` — inline field error
- `altitude/views/htmx/setup_form.scala.html` — field-level errors
- `altitude/views/setup.scala.html` — `#serverError` / `#serverWarning` containers

**Current example:**
```html
<!-- Inline field error -->
@if(fieldErrors.contains(Api.Field.Folder.NAME)) {
  <div class="error">@{ fieldErrors(Api.Field.Folder.NAME) }</div>
}

<!-- Server-level messages (setup.scala.html) -->
<div id="serverError" hidden></div>
<div id="serverWarning" hidden></div>
```

### Oat equivalent

Oat auto-styles `<div role="alert">` with `data-variant` for the tone.

```html
<!-- Inline field error -->
<div role="alert" data-variant="error">Folder name is required</div>

<!-- Server-level messages -->
<div role="alert" data-variant="error" hidden id="serverError"></div>
<div role="alert" data-variant="warning" hidden id="serverWarning"></div>

<!-- Informational -->
<div role="alert" data-variant="success">Setup complete!</div>
```

---

## 13. Navigation bar

### Current usage

A horizontal `<nav>` bar with brand, repository menu items, and a search input
is defined in `nav.scala.html` and `brand.scala.html`.  Styling is custom CSS
using grid and CSS variables.

**Files:**
- `altitude/views/includes/nav.scala.html` — full nav with repo, triage, trash, import, settings links
- `altitude/views/includes/brand.scala.html` — standalone brand (used on setup / login pages)
- `altitude/views/index.scala.html` — wraps `nav` in `<nav>` tag

**Current example:**
```html
<nav>
  <div class="brand"><a href="/">Altitude</a></div>
  <div class="menu triage">
    <a href="/r/.../search?view=triage">
      <i class="fas fa-medkit"></i><span>Triage (3)</span>
    </a>
  </div>
  <div class="menu import">
    <a href="/pipeline/r/...">
      <i class="fas fa-plus-square"></i><span>Import</span>
    </a>
  </div>
  <div class="search">
    <input type="text" name="search" placeholder="Search">
  </div>
</nav>
```

### Oat equivalent

Oat auto-styles `<nav>` and its child links.  The search input is styled as any
other Oat input.  The brand link requires no extra class.

```html
<nav>
  <a href="/" aria-label="Altitude home"><strong>Altitude</strong></a>

  <a href="/r/.../search?view=triage">
    <i class="fas fa-medkit"></i> Triage (3)
  </a>
  <a href="/pipeline/r/...">
    <i class="fas fa-plus-square"></i> Import
  </a>
  <a href="/settings">
    <i class="fas fa-cog"></i> Settings
  </a>

  <input type="search" name="search" placeholder="Search" aria-label="Search assets">
</nav>
```

---

## Installation

To adopt Oat UI, add the following to `altitude/views/includes/header_common.scala.html`:

```html
<!-- Replace (or supplement) core.css with Oat -->
<link rel="stylesheet" href="https://unpkg.com/@knadh/oat/oat.min.css">
<script src="https://unpkg.com/@knadh/oat/oat.min.js" defer></script>
```

Or install via npm and reference via the static asset pipeline:

```sh
npm install @knadh/oat
```

```html
<link rel="stylesheet" href="/static/css/oat.min.css">
<script src="/static/js/oat.min.js" defer></script>
```
