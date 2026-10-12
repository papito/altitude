## General notes

Avoid documentation drift. When related code is added, changed, or removed, update AGENTS.md, CLAUDE.md, and ARCHITECTURE.md if they exist.

Read [altitude/AGENTS.md](altitude/AGENTS.md) for architecture and build/test commands. For face detection and recognition, read [docs/faces.md](docs/faces.md). For security, data safety and operational readiness, read [docs/READINESS.md](docs/READINESS.md); a change that closes or opens one of its items updates it. For frontend work, including JavaScript under `altitude/static/js/`, also read [altitude/views/AGENTS.md](altitude/views/AGENTS.md) for template, event, and component conventions.

When adding features or modifying existing behavior, review nearby code comments and update them where needed.

Always add comments for less than trivial logic, unless the comment is redundant with the code, and it's a simple getter/setter or similar.
If you find yourself writing a comment that starts with "This is needed because..." or "This exists to work around...", consider whether the code can be refactored to eliminate the need for the comment.

Always log on INFO important events and log on DEBUG events that may be useful for debugging. Avoid logging on DEBUG events that are too noisy to be useful (use TRACE if available).

### Server-side

Use test-driven Development with strict red-green-refactor cycle using integration tests. Do not trigger TDD for documentation-only, configuration-only changes, or front-end-facing code.

Extract shared logic into functions when the same or similar logic appears more than once.

Write the minimal amount of code that preserves clarity, readability, and maintainability.

"Minimal" does NOT mean that you should leave dead or redundant code after changes and refactoring.

### Front-end

For UI work, prompt the developer to run "make db" and "make watch" if localhost:8080 is not accessible or if the repo is not initialized (setup form visible or no assets present).

Use the fewest properties/tags and the least CSS/HTML standards to accomplish the task.

Folder, Album, and Location actions (including Add controls and category actions) open separate modals through `altitude/static/js/common/modal.js`. Anchor those modals below the row's menu trigger or the Add button and size ordinary forms to their content without widening inputs. Keep their context menus as action lists; only View settings uses an inline popover dialog.

Do not assume aesthetic preferences such as color, spacing, or padding unless they are specified.

Always prefer CSS Grid and Flexbox.

The application's CSS is one bundle: `altitude/static/css/app.css` lists `@import` lines in cascade order (`core.css`, `tabs.css`, then one file per template under `pages/`, `includes/` and `htmx/`, named after it), which the build inlines into one content-hashed file. Reuse `core.css`'s `:root` variables. A template's styles go in its own file, such as the folder tree and popover styles in `altitude/static/css/htmx/folders.css`, never in a `<style>` block. The bundle applies on every page, so scope a rule by an element only its template renders (usually an ID), and a page's own rule by its body class at zero specificity (`:where(body.setup) main`). A Scala value reaches CSS as a custom property the template sets on the container (`style="--preview-box: ...px"`). Bundled CSS is served from `/assets/`, so its URLs are absolute or `data:`.

Strongly prefer CSS variables over hard-coded values, especially if duplicated.

### Database migrations

DO NOT add new migrations or bump the version number. All changes go into all.sql for both Postgres and Sqlite - as original table/index defintions as if it were a fresh schema (no ALTER).