# Splide (carousel)

- **Source:** https://alpinejs.dev/component/splide (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Integration (third-party library wired through `x-init` / `x-ref`)
- **Dependencies:** alpinejs v3.x, splide v4.0.x (CSS + script)

## What it does

Integration recipe: the library is loaded from a CDN, initialized in the component's `init()` against an `x-ref` element, and kept in sync with Alpine state through the library's change event and `$watch`. Splide is the more accessible of the two carousel libraries on the site (built-in ARIA and keyboard support).

## How it is built

- Standard Splide markup: `.splide > .splide__track > .splide__list > .splide__slide`.
- `init()` runs `new Splide($refs.slider, { perPage: 3, gap: '1rem', breakpoints: { 640: { perPage: 1 } } }).mount()`.

## Keyboard and accessibility

- Splide adds `role`, `aria-label`, and keyboard arrow handling on its own.

## State

None in Alpine beyond the instance.

## Minimal example (original)

```html
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@splidejs/splide@4.0.7/dist/css/splide.min.css">
<script src="https://cdn.jsdelivr.net/npm/@splidejs/splide@4.0.7/dist/js/splide.min.js"></script>

<div x-data="{ init() { new Splide(this.$refs.slider, { perPage: 3, gap: '1rem', breakpoints: { 640: { perPage: 1 } } }).mount() } }">
    <section x-ref="slider" class="splide" aria-label="Recent imports">
        <div class="splide__track">
            <ul class="splide__list"><li class="splide__slide">1</li><li class="splide__slide">2</li><li class="splide__slide">3</li></ul>
        </div>
    </section>
</div>
```
