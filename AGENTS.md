## General notes

Avoid documentation drift. When related code is added, changed, or removed, update AGENTS.md, CLAUDE.md, and ARCHITECTURE.md if they exist.

Read [altitude/AGENTS.md](altitude/AGENTS.md) for architecture and build/test commands. For frontend work, including JavaScript under `altitude/static/js/`, also read [altitude/views/AGENTS.md](altitude/views/AGENTS.md) for template, event, and component conventions.

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

Use the fewest props and the least markup needed to accomplish the task.

Do not assume aesthetic preferences such as color, spacing, or padding unless they are specified.

Strongly prefer CSS Grid and Flexbox.

The main stylesheet is `altitude/static/css/core.css`; reuse its `:root` variables. Component styles also live in Twirl partials, such as the folder tree and popover styles in `altitude/views/htmx/folders.scala.html`.

Identify the main CSS file and use :root variables. Example:

```css
:root {
    color-scheme: light dark;

    --background-color: #363636;
    --background-form-color: #363535;
    --background-secondary-color: rgb(56, 56, 65);
    --background-tertiary-color: rgb(75, 75, 87);
}
```

### Database migrations

DO NOT add new migrations or bump the version number. All changes go into all.sql for both Postgres and Sqlite - as original table/index defintions as if it were a fresh schema (no ALTER).