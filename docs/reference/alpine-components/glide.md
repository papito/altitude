# Glide (carousel)

- **Source:** https://alpinejs.dev/component/glide (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Integration (third-party library wired through `x-init` / `x-ref`)
- **Dependencies:** alpinejs v3.x, glide v3.5.x (core CSS + script)

## What it does

Integration recipe: the library is loaded from a CDN, initialized in the component's `init()` against an `x-ref` element, and kept in sync with Alpine state through the library's change event and `$watch`. Glide handles the sliding; Alpine only mounts it.

## How it is built

- Standard Glide markup: `.glide > .glide__track[data-glide-el="track"] > .glide__slides > .glide__slide`, plus arrow buttons with `data-glide-dir="<"` / `">"`.
- `init()` runs `new Glide($refs.slider, { type: 'carousel', perView: 3, breakpoints: { 640: { perView: 1 } } }).mount()`.

## Keyboard and accessibility

- Arrow buttons are real buttons; the demo labels them for screen readers ("Skip to previous/next slide page").

## State

None in Alpine beyond the instance.

## Minimal example (original)

```html
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@glidejs/glide@3.5.x/dist/css/glide.core.min.css">
<script src="https://cdn.jsdelivr.net/npm/@glidejs/glide@3.5.x"></script>

<div x-data="{ init() { new Glide(this.$refs.slider, { type: 'carousel', perView: 3, breakpoints: { 640: { perView: 1 } } }).mount() } }">
    <div x-ref="slider" class="glide">
        <div class="glide__track" data-glide-el="track">
            <ul class="glide__slides"><li class="glide__slide">1</li><li class="glide__slide">2</li><li class="glide__slide">3</li></ul>
        </div>
        <div data-glide-el="controls">
            <button type="button" data-glide-dir="<" aria-label="Previous">&#8249;</button>
            <button type="button" data-glide-dir=">" aria-label="Next">&#8250;</button>
        </div>
    </div>
</div>
```
