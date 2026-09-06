# Disclosure (Accordion), headless

- **Source:** https://alpinejs.dev/component/headless-disclosure (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Headless (`@alpinejs/ui`)
- **Dependencies:** alpinejs v3.x, @alpinejs/ui v3.x, @alpinejs/collapse v3.x

## What it does

Headless component from the `@alpinejs/ui` plugin: behavior, keyboard handling, and ARIA come from the plugin's directives; all styling is yours. Full documentation is linked from each page (https://alpinejs.dev/components#headless).

A button that shows or hides a panel; several disclosures can be made exclusive by sharing state.

## Variants on the page

- Basic
- With transition (collapse)
- Exclusive (one open at a time)
- Leading indicator

## How it is built

- `x-disclosure` on each item (optionally `x-model` to control it), `x-disclosure:button` on the toggle, `x-disclosure:panel` on the content.
- `$disclosure.isOpen` styles the button (for example rotating a chevron).
- "With transition" adds `x-show` + `x-collapse` on the panel; "Exclusive" keeps an `active` id in a parent `x-data` and binds each item's `x-model` to a comparison with it; "Leading indicator" only moves the icon.

## Keyboard and accessibility

- Enter/Space toggle (native button).
- `aria-expanded` and `aria-controls` on the button; the panel is referenced by id.

## State

Internal, or `x-model` boolean per item; for exclusive groups, `active: string | null` on a parent.

## Minimal example (original)

```html
<div x-data="{ active: null }">
    <!-- exclusive: each item exposes an `open` accessor over the shared `active` id -->
    <div x-data="{ get open() { return active === 'refunds' }, set open(value) { active = value ? 'refunds' : null } }"
         x-disclosure x-model="open">
        <button x-disclosure:button type="button">
            Refund policy <span aria-hidden="true" :class="{ 'rotated': $disclosure.isOpen }">&#9662;</span>
        </button>
        <div x-disclosure:panel x-collapse>Thirty days, no questions asked.</div>
    </div>
</div>
```
