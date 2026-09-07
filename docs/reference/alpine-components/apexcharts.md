# ApexCharts

- **Source:** https://alpinejs.dev/component/apexcharts (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Integration (third-party library wired through `x-init` / `x-ref`)
- **Dependencies:** alpinejs v3.x, apexcharts v3.35.x

## What it does

Integration recipe: the library is loaded from a CDN, initialized in the component's `init()` against an `x-ref` element, and kept in sync with Alpine state through the library's change event and `$watch`.

## How it is built

- `x-data` holds `values` and `labels`; `init()` builds `new ApexCharts($refs.chart, { chart: { type, toolbar }, series, xaxis: { categories }, tooltip })` and calls `chart.render()`.
- `$watch('values', ...)` calls `chart.updateOptions({ series: [...] })` so the chart animates to the new data.

## Keyboard and accessibility

- SVG output; ApexCharts includes basic accessibility, but add a text summary for important data.

## State

`values: number[]`, `labels: string[]`.

## Minimal example (original)

```html
<script src="https://cdn.jsdelivr.net/npm/apexcharts"></script>

<div x-data="{
        labels: ['Jan', 'Feb', 'Mar'], values: [3, 7, 4],
        init() {
            const chart = new ApexCharts(this.$refs.chart, {
                chart: { type: 'bar', toolbar: { show: false } },
                series: [{ name: 'Imports', data: this.values }],
                xaxis: { categories: this.labels },
            })
            chart.render()
            this.$watch('values', (values) => chart.updateOptions({ series: [{ data: values }] }))
        },
     }">
    <div x-ref="chart"></div>
</div>
```
