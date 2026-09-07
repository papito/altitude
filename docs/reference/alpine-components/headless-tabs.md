# Tabs, headless

- **Source:** https://alpinejs.dev/component/headless-tabs (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Headless (`@alpinejs/ui`)
- **Dependencies:** alpinejs v3.x, @alpinejs/ui v3.x, @alpinejs/focus v3.x

## What it does

Headless component from the `@alpinejs/ui` plugin: behavior, keyboard handling, and ARIA come from the plugin's directives; all styling is yours. Full documentation is linked from each page (https://alpinejs.dev/components#headless).

The tabs pattern with keyboard navigation and ARIA supplied by the plugin.

## Variants on the page

- Default
- Minimal
- Minimal with icons

## How it is built

- `x-tabs` on the root, `x-tabs:list` around the tab buttons, `x-tabs:tab` on each button, `x-tabs:panels` around the panels, `x-tabs:panel` on each panel; tabs and panels pair up by order.
- `$tab.isSelected` is available inside a tab for styling (`:class`).
- The "Minimal" variants only change the markup around the same directives (underline style, icons).

## Keyboard and accessibility

- Arrow keys move between tabs, Home/End jump; selection follows focus.
- `role="tablist"`, `role="tab"`, `aria-selected`, `role="tabpanel"` set by the directives.

## State

Internal; the plugin tracks the selected index.

## Minimal example (original)

```html
<div x-tabs>
    <div x-tabs:list>
        <button x-tabs:tab type="button" :class="{ 'active': $tab.isSelected }">Folders</button>
        <button x-tabs:tab type="button" :class="{ 'active': $tab.isSelected }">People</button>
    </div>
    <div x-tabs:panels>
        <section x-tabs:panel>Folder tree</section>
        <section x-tabs:panel>People list</section>
    </div>
</div>
```
