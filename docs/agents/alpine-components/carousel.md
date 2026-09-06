# Carousel

- **Source:** https://alpinejs.dev/component/carousel (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Lesson (hand-built with core Alpine)
- **Dependencies:** alpinejs v3.x, @alpinejs/intersect v3.x, smoothscroll-polyfill v0.4.x

## What it does

A horizontally scrolling strip of slides with previous/next buttons, keyboard navigation, and
buttons that disable at the ends. Scrolling is native (`scroll-snap`), Alpine only drives it.

## How it is built

- The slide strip is an overflow-x container referenced with `x-ref`; navigation calls `scrollTo({ left, behavior: 'smooth' })` (the polyfill covers older browsers).
- `skip` is the number of slides per page; `next()`, `prev()`, and `to()` compute the target offset from the first slide's width.
- `atBeginning` / `atEnd` are set from the intersect plugin: the first and last slides carry `x-intersect:enter` / `x-intersect:leave` bindings (declared once as an `x-bind` object and applied to those slides).
- The previous/next buttons bind `:aria-disabled` to those flags and refuse to scroll further.
- A `focusableWhenVisible` binding object toggles `tabindex` on links inside slides as they enter or leave the viewport, so off-screen slides are skipped by Tab.

## Keyboard and accessibility

- Left and Right arrows on the focused strip (`tabindex="0"`, `role="region"`, `aria-labelledby`) call `prev()` / `next()`.
- Buttons use `aria-disabled` rather than `disabled` so they stay focusable at the ends.
- Links in slides that are scrolled out of view get `tabindex="-1"`.

## State

`skip: number`, `atBeginning: boolean`, `atEnd: boolean`; methods `next()`, `prev()`, `to(strategy)`.

## Minimal example (original)

```html
<div x-data="{
        skip: 1, atBeginning: false, atEnd: false,
        slideWidth() { return this.$refs.strip.firstElementChild.getBoundingClientRect().width },
        next() { this.$refs.strip.scrollBy({ left: this.slideWidth() * this.skip, behavior: 'smooth' }) },
        prev() { this.$refs.strip.scrollBy({ left: -this.slideWidth() * this.skip, behavior: 'smooth' }) },
     }">
    <button type="button" :aria-disabled="atBeginning" x-on:click="atBeginning || prev()">Previous</button>

    <ul x-ref="strip" tabindex="0" role="region" aria-label="Slides"
        x-on:keydown.left="prev()" x-on:keydown.right="next()"
        style="display: flex; overflow-x: auto; scroll-snap-type: x mandatory">
        <li x-intersect:enter="atBeginning = true" x-intersect:leave="atBeginning = false">1</li>
        <li>2</li>
        <li x-intersect:enter="atEnd = true" x-intersect:leave="atEnd = false">3</li>
    </ul>

    <button type="button" :aria-disabled="atEnd" x-on:click="atEnd || next()">Next</button>
</div>
```

## Notes for Altitude

Asset detail navigation (`detail-navigator.js`) is a different problem (one image at a time, loaded lazily), but the intersect-based `atEnd` flag is the same idea the search grid uses for infinite scroll.
