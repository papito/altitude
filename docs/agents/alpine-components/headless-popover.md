# Popover, headless

- **Source:** https://alpinejs.dev/component/headless-popover (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Headless (`@alpinejs/ui`)
- **Dependencies:** alpinejs v3.x, @alpinejs/ui v3.x, @alpinejs/focus v3.x

## What it does

Headless component from the `@alpinejs/ui` plugin: behavior, keyboard handling, and ARIA come from the plugin's directives; all styling is yours. Full documentation is linked from each page (https://alpinejs.dev/components#headless).

A button that toggles a panel anchored to it, closing on outside click, Escape, or focus leaving.
Groups let several popovers coordinate so opening one closes the others.

## Variants on the page

- Single popover
- Group (mutually exclusive)
- Attached button

## How it is built

- `x-popover` on the wrapper, `x-popover:button` on the trigger, `x-popover:panel` on the floating content (the demo adds `x-transition.origin.top.left` or `.top.right` and `x-cloak`).
- `x-popover:group` around several popovers makes them mutually exclusive and treats focus leaving the whole group as a close.
- The "Attached button" variant places the button inside the panel's positioning context so the panel hangs off it.

## Keyboard and accessibility

- Escape closes; Tab out closes.
- The trigger gets `aria-expanded` / `aria-controls`; the panel is focus-managed by the plugin.

## State

Internal to the directive; no `x-data` needed for the basic case.

## Minimal example (original)

```html
<div x-popover>
    <button x-popover:button type="button">Account</button>
    <div x-popover:panel x-transition.origin.top.left x-cloak>
        <a href="/profile">Profile</a>
        <a href="/settings">Settings</a>
    </div>
</div>
```

## Notes for Altitude

The folder and album context menus do not use this plugin (`@alpinejs/ui` is not vendored). It is a native
HTML `popover="auto"` panel opened by a `popovertarget` button, with a small core-Alpine component
(`static/js/alpine/components/context-menu.js`) for placement, focus-out and scroll/resize
dismissal, and cleanup; the browser provides the toggle, outside-click dismissal, and one-open-at-
a-time behavior this component's group variant would otherwise supply. The same panel also hosts
the folder dialogs (`static/js/fragments/inline-dialog.js`): an action's HTMX response replaces the
actions inside the open panel, with no second popover and no backdrop or focus trap.
