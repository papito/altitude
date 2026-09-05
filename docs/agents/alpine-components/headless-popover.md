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
