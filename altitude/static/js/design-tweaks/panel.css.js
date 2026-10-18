/**
 * The design tweaks panel's stylesheet, as a constructable sheet adopted by the panel's shadow
 * root, so the bundle carries nothing of it. The panel has a look of its own: every custom
 * property it reads is declared on the host, as are the inherited text properties, so no tweak of
 * the app's tokens, and no theme, restyles the panel while it is used to explore them.
 */
export const panelSheet = new CSSStyleSheet()

panelSheet.replaceSync(`
:host {
    /* The panel's own palette: fixed literals, never the app's tokens */
    --dt-bg: #1b1c21;
    --dt-bg-raised: #26272e;
    --dt-fg: #e9e9ec;
    --dt-muted: #9a9ca8;
    --dt-accent: #7b96ff;
    --dt-changed: #f0b64b;
    --dt-border: #363841;
    --dt-font: system-ui, -apple-system, "Segoe UI", sans-serif;
    --dt-mono: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
    --dt-size: 12px;
    --dt-gap: 6px;
    --dt-pad: 10px;
    --dt-radius: 4px;
    --dt-width: 26rem;
    --dt-height: 45vh;

    /* The inherited text properties, pinned so the page's do not reach in */
    font: 400 var(--dt-size) / 1.4 var(--dt-font);
    letter-spacing: normal;
    color: var(--dt-fg);
    color-scheme: dark;

    /* A popover in the top layer; the UA's centering and frame are replaced by the dock */
    position: fixed;
    inset: auto;
    margin: 0;
    padding: 0;
    border: 0;
    width: var(--dt-width);
    max-width: 100vw;
    height: 100vh;
    max-height: 100vh;
    overflow: hidden;
    background: var(--dt-bg);
    box-shadow: 0 0 0 1px var(--dt-border), 0 8px 30px rgb(0 0 0 / 0.5);
    display: grid;
    grid-template-rows: auto auto minmax(0, 1fr);
}
:host([data-dock="right"]) { top: 0; right: 0; }
:host([data-dock="left"]) { top: 0; left: 0; }
:host([data-dock="bottom"]) {
    left: 0;
    right: 0;
    bottom: 0;
    width: auto;
    height: var(--dt-height);
}
:host([data-collapsed]) {
    height: auto;
    width: auto;
    max-width: 100vw;
    grid-template-rows: auto;
}
:host([data-collapsed][data-dock="right"]) { top: auto; bottom: 0; }
:host([data-collapsed][data-dock="left"]) { top: auto; bottom: 0; }
:host([data-collapsed]) .tabs,
:host([data-collapsed]) .tab,
:host([data-collapsed]) header .full { display: none; }
:host(:not([data-collapsed])) header .strip { display: none; }

* { box-sizing: border-box; }
/* The panel's own display rules would otherwise beat the attribute */
[hidden] { display: none !important; }

header {
    display: flex;
    align-items: center;
    gap: var(--dt-gap);
    padding: var(--dt-gap) var(--dt-pad);
    border-bottom: 1px solid var(--dt-border);
    background: var(--dt-bg-raised);
}
header strong { font-weight: 600; margin-right: auto; white-space: nowrap; }
header .strip { color: var(--dt-muted); white-space: nowrap; }
header .group { display: inline-flex; gap: 2px; }

button, select, input, textarea {
    font: inherit;
    color: inherit;
    letter-spacing: inherit;
}
button {
    background: var(--dt-bg-raised);
    border: 1px solid var(--dt-border);
    border-radius: var(--dt-radius);
    padding: 2px 7px;
    cursor: pointer;
    white-space: nowrap;
}
button:hover { border-color: var(--dt-muted); }
button[aria-pressed="true"], button.active { background: var(--dt-accent); color: #0d0f18; border-color: var(--dt-accent); }
button.quiet { background: none; border-color: transparent; color: var(--dt-muted); padding: 2px 4px; }
button.quiet:hover { color: var(--dt-fg); }
button:disabled { opacity: 0.4; cursor: default; }
input[type="text"], input[type="number"], select, textarea {
    background: var(--dt-bg);
    border: 1px solid var(--dt-border);
    border-radius: var(--dt-radius);
    padding: 2px 5px;
    min-width: 0;
}
input[type="text"], textarea { font-family: var(--dt-mono); }
input[type="number"] { width: 5.5em; }
input[type="range"] { flex: 1 1 4em; min-width: 3em; accent-color: var(--dt-accent); margin: 0; }
input[type="color"] { width: 2em; height: 1.6em; padding: 0; border: 1px solid var(--dt-border); background: none; }
input[type="checkbox"] { accent-color: var(--dt-accent); margin: 0 4px 0 0; }
textarea { width: 100%; min-height: 6em; resize: vertical; }
label { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }

.tabs { display: flex; gap: 2px; padding: var(--dt-gap) var(--dt-pad) 0; border-bottom: 1px solid var(--dt-border); }
.tabs button { border-bottom: 0; border-radius: var(--dt-radius) var(--dt-radius) 0 0; }
.tab { overflow: auto; padding: var(--dt-pad); display: none; }
.tab.shown { display: block; }

.toolbar { display: flex; flex-wrap: wrap; gap: var(--dt-gap); align-items: center; margin-bottom: var(--dt-pad); }
.toolbar input[type="text"] { flex: 1 1 8em; }
.chip { display: inline-flex; gap: 4px; align-items: center; border: 1px solid var(--dt-accent); border-radius: 1em; padding: 0 8px; color: var(--dt-accent); }

section.group { margin-bottom: var(--dt-pad); }
section.group > h3 {
    display: flex;
    align-items: center;
    gap: var(--dt-gap);
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--dt-muted);
    margin: var(--dt-pad) 0 var(--dt-gap);
    border-bottom: 1px solid var(--dt-border);
    padding-bottom: 2px;
}
section.group > h3 .reset { margin-left: auto; }
section.group[hidden] { display: none; }
.group-controls {
    display: grid;
    gap: var(--dt-gap);
    padding: var(--dt-gap);
    margin-bottom: var(--dt-gap);
    border: 1px dashed var(--dt-border);
    border-radius: var(--dt-radius);
}

.card {
    display: grid;
    gap: 4px;
    padding: var(--dt-gap) 0 var(--dt-gap) var(--dt-pad);
    border-left: 2px solid transparent;
    border-bottom: 1px solid var(--dt-border);
}
.card[hidden] { display: none; }
.card.changed { border-left-color: var(--dt-changed); }
.card .name { display: flex; align-items: center; gap: var(--dt-gap); }
.card .name code { font-family: var(--dt-mono); font-size: 11px; }
.card .name .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--dt-changed); visibility: hidden; }
.card.changed .name .dot { visibility: visible; }
.card .name .reset { margin-left: auto; }
.card .note { color: var(--dt-muted); font-size: 11px; }
.card .value { font-family: var(--dt-mono); font-size: 11px; color: var(--dt-muted); overflow-wrap: anywhere; }
.card .status { font-size: 11px; color: var(--dt-muted); }
.card .status.missing { color: var(--dt-changed); }

.control { display: grid; gap: 3px; }
.row { display: flex; align-items: center; gap: var(--dt-gap); flex-wrap: wrap; }
.row > span.k { color: var(--dt-muted); width: 1.2em; text-align: right; font-family: var(--dt-mono); font-size: 11px; }
.row > span.v { font-family: var(--dt-mono); font-size: 11px; width: 4.5em; }
.row > input[type="text"] { flex: 1 1 6em; }
.swatch { width: 1.6em; height: 1.6em; border-radius: var(--dt-radius); border: 1px solid var(--dt-border); flex: none; }
.sample {
    background: var(--dt-bg-raised);
    border-radius: var(--dt-radius);
    padding: 4px 6px;
    color: var(--dt-fg);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

table { border-collapse: collapse; width: 100%; }
th, td { text-align: left; vertical-align: top; padding: 3px 6px 3px 0; border-bottom: 1px solid var(--dt-border); }
th { color: var(--dt-muted); font-weight: 600; font-size: 11px; }
td code { font-family: var(--dt-mono); font-size: 11px; }
details summary { cursor: pointer; color: var(--dt-accent); }
.locations div { font-family: var(--dt-mono); font-size: 11px; color: var(--dt-muted); }

.snapshot { display: flex; flex-wrap: wrap; align-items: center; gap: var(--dt-gap); padding: var(--dt-gap) 0; border-bottom: 1px solid var(--dt-border); }
.snapshot .title { font-weight: 600; margin-right: auto; }
.snapshot.active .title::before { content: "● "; color: var(--dt-accent); }
.snapshot .count { color: var(--dt-muted); }

.export { margin-bottom: var(--dt-pad); }
.export h3 { display: flex; align-items: center; gap: var(--dt-gap); font-size: 12px; margin: 0 0 var(--dt-gap); }
.export h3 button { margin-left: auto; }
.export pre {
    font-family: var(--dt-mono);
    font-size: 11px;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    background: var(--dt-bg-raised);
    border: 1px solid var(--dt-border);
    border-radius: var(--dt-radius);
    padding: var(--dt-gap);
    margin: 0;
    max-height: 40vh;
    overflow: auto;
}

p.hint { color: var(--dt-muted); margin: 0 0 var(--dt-gap); }

/* The outline of the element picked by Inspect: drawn over the page from the top layer */
.inspect-outline {
    position: fixed;
    pointer-events: none;
    border: 2px solid var(--dt-accent);
    background: rgb(123 150 255 / 0.12);
    display: none;
}
.inspect-outline.shown { display: block; }
`)
