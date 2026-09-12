# Chart.js

- **Source:** https://alpinejs.dev/component/chart-js (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Integration (third-party library wired through `x-init` / `x-ref`)
- **Dependencies:** alpinejs v3.x, chart.js v3.5.x

## What it does

Integration recipe: the library is loaded from a CDN, initialized in the component's `init()` against an `x-ref` element, and kept in sync with Alpine state through the library's change event and `$watch`. Data lives in Alpine state; the chart is re-rendered when it changes.

## How it is built

- `x-data` holds `labels` and `values`; `init()` creates `new Chart($refs.canvas.getContext('2d'), { type, data: { labels, datasets: [{ data, backgroundColor, borderColor }] }, options: { interaction: ... } })`.
- `$watch('values', ...)` assigns the new array to `chart.data.datasets[0].data` and calls `chart.update()`.

## Keyboard and accessibility

- Charts are canvas; provide a text alternative near the canvas if the data matters to screen-reader users.

## State

`labels: string[]`, `values: number[]`.

## Minimal example (original)

```html
<script src="https://cdn.jsdelivr.net/npm/chart.js@3.5.1/dist/chart.min.js"></script>

<div x-data="{
        labels: ['Jan', 'Feb', 'Mar'], values: [3, 7, 4],
        init() {
            const chart = new Chart(this.$refs.canvas.getContext('2d'), {
                type: 'line',
                data: { labels: this.labels, datasets: [{ data: this.values, borderColor: '#dca051' }] },
            })
            this.$watch('values', (values) => { chart.data.datasets[0].data = values; chart.update() })
        },
     }">
    <canvas x-ref="canvas"></canvas>
    <button type="button" x-on:click="values = values.map(v => v + 1)">Increment</button>
</div>
```

## Notes for Altitude

The import pipeline page shows counts as text; a chart would be a candidate for the stats service output if one is ever wanted.
