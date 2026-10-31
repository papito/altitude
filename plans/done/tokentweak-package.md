# tokentweak: the design tweaks panel as a reusable package

## Goals

- The dev-only design tweaks panel becomes `tokentweak`, a framework-free npm package that edits the CSS custom
  properties of any running page live, with snapshots, exports and an optional source scan, so any project can adopt it
  with one script tag or one import.
- Altitude is its first consumer: the vendored build plus a thin adapter replace the six modules under
  `static/js/design-tweaks/`, and the panel behaves on Altitude exactly as it does today.
- The package is TypeScript, bundled with esbuild, tested with Node's test runner on its pure parts and one Playwright
  smoke test, and ready for `npm publish` (the publish itself is the user's step).

## Status

Implemented. Publishing to npm (`npm publish` in `~/projects/tokentweak`, then a git tag) is the remaining manual step.

## Decisions

| Concern | Decision |
|---|---|
| Form | npm package `tokentweak` (the name is free on the registry), MIT, in its own repo `~/projects/tokentweak` (`git init`; no commits are made by agents). Altitude vendors `dist/tokentweak.js` and `dist/boot.js` under `static/js/lib/` byte-for-byte, per `lib/README.md`'s conventions, with a `tokentweak.LICENSE` beside them |
| Language and build | TypeScript, `strict`, `erasableSyntaxOnly`, `.ts` import specifiers with `rewriteRelativeImportExtensions`, so Node 24's type stripping runs the unit tests with no transpiler. esbuild (`scripts/build.mjs`) emits four outputs, all `target: es2022`: `dist/tokentweak.js` (ESM, readable, not minified: it is what Altitude vendors), `dist/tokentweak.iife.js` (`globalName: "TokenTweak"`, minified), `dist/boot.js` (IIFE, minified, a few hundred bytes), `dist/cli/{index,scan}.js` (`platform: node`, `packages: external`, shebang on `scan.js`). `tsc -p tsconfig.build.json` emits the `.d.ts` files |
| Version | `0.1.0`; `CHANGELOG.md` in Keep a Changelog form |
| Public API | `createTokenTweaks(config?: TokenTweaksConfig): Promise<TokenTweaks>`, async because the stylesheet may be awaited until `load` and `sources` may be fetched. The handle: `open`, `close`, `toggle`, `destroy` (removes host and listeners; the adopted sheet stays so the look holds), `isOpen`, `host`, `tokens`, `getState`, `onChange(listener): unsubscribe`, `refresh`. The main entry also exports the generic helpers (`kindOf`, `splitOutsideParentheses`, `referencedNamesOf`, `themeColorsOf`, `withThemeColor`, `applyTheme`, `resolveColor`, `isFontAvailable`, `asFontFamily`, `oklchOf`, `hexOf`) and every type |
| Stylesheet | Default: every same-origin sheet in `document.styleSheets` whose `cssRules` are readable and hold a rule whose comma-split selector list includes `rootSelector` (default `:root`); merged, later declarations win. `stylesheet` narrows it: a string is an href substring, a RegExp is tested on the href, a function is a predicate. A sheet still loading is waited for until `load`. `@property` rules of the selected sheets give registrations |
| Override sheet and boot | One `CSSStyleSheet` in `document.adoptedStyleSheets`, rewritten per change. Its first rule is a marker, `:root { --tokentweak: <storageKey>; }`, then the `:root` tweak rule, then one `!important` rule per scoped token on its selector. `tokentweak/boot` is a classic blocking `<script src=… data-key="…">` for the head: it reads `document.currentScript.dataset.key ?? "tokentweak"`, parses `localStorage[key]`, adopts a sheet from `state.css` and sets `documentElement.style.colorScheme` from `state.theme`; errors are a `console.warn`. On start the store finds the boot sheet by its marker (`cssRules[0]` is a `CSSStyleRule` whose `--tokentweak` equals the key) and takes it over; none found means boot did not run, and a new sheet is appended. No `window` global |
| State | JSON under `storageKey` (default `tokentweak`): `{ v: 1, tweaks, extras, snapshots, activeSnapshot, theme, dock, open, collapsed, ab, css }`; a snapshot is `{ name, theme, tweaks, extras }`. `extras` replaces Altitude's `explorer` field; a stored state without `v` is read with unknown fields dropped (dev-only data, no migration) |
| Kinds | By name first (`typographyNames`, default `font-size`, `font-weight`, `letter-spacing`, `line-height` → `typography`), then by value through an injected `supports` (default `CSS.supports`): `color`, `length` (one or more lengths), `border` (a style keyword and `supports("border")`), `shadow`, `font`, else `other`. A scoped token is kind `scoped`. `isRecipe` is true when the declared text references other tokens |
| Sections | Inferred: tokens group by the first name segment after `--`; a segment with two or more tokens is a section titled by the capitalized segment, in order of first appearance; singletons go to "Other", last. With `sections` config: list order, first match wins (`match` is a RegExp on the name, a list of prefixes or a predicate on the token; `kinds` filters further); unmatched tokens go to an automatic "Other". Scoped tokens and extra controls go to their `section` id, else "Other" |
| Scale and roles | `scale.steps` (a RegExp or a list) names the steps a role token may pick from. Default inferred: among length tokens whose declared value is one `<number><unit>` literal, strip a trailing `-\d+` from the name; the largest family with three or more members is the scale. `scaleRole` is `step` for its members and `role` for a length whose declared text is exactly `var(--x)` with `--x` a step. The role control lists the steps; "detach" writes the step's literal. `scale: false` disables role selects |
| Type scale | `typeScale: { steps, base }` or inferred: tokens matching `/font-size/` with rem values, sorted by resolved px, base the one named `*-base` else the median, shown only with three or more steps. The control writes every step from a base and a ratio. `false` hides it |
| Seed | `seed.targets: { base, raised, muted, text, accent }`; shown in the section holding `targets.accent`, hidden without config |
| Theme | `theme: "auto"` (default) shows the Light/Dark toggle when any root token holds `light-dark()`; `true`/`false` force it. Editing a color edits the shown scheme's side of its pair; exports write the full pair |
| Scoped tokens | `scopedTokens: [{ name, selector, default, control?: { min, max, step }, section?, exportLabel? }]`: tokens set inline on a container, read from the container's inline style when present, overridden `!important` on the selector; a px slider, defaults `0..3×default` step 1 |
| Extra controls | `extraControls: [{ id, label, section?, min, max, step, unit?, default, get, set, available?, unavailableNote?, exportLabel?, describe? }]`: a generic range card whose value lives in `extras`, applied live on input, reset calls `set(default)`; stored extras are applied at `window` `load` when `available()` is true |
| Ranges | `ranges: RangeRule[]` (`{ match, min, max, step, unit }`) with defaults: font-size rem 0..3 step 0.0625; font-weight 100..900 step 100; letter-spacing em -0.1..0.5 step 0.01; line-height 0.8..2.5 step 0.05; rem lengths 0..4 step 0.0625 |
| Exports | Prompt, CSS and snapshot JSON as today; `exports.fileName` (default "the stylesheet") names the file in both headers, `exports.indent` (default four spaces) the declaration lines; `exportLabel` names a scoped token or an extra control in the notes; `describe(value, default)` phrases an extra control |
| Sources | `sources: string | Sources | (() => Promise<Sources>) | null`; a string is fetched same-origin with `fetch` (credentials included). Schema v1 keeps the field names Altitude's Scala scan emits: `{ version?: 1, rootTokens: [{ name, value, location, note? }], literals: [{ value, locations }], lengthLiterals: [{ property, value, locations }] }`; unknown keys are ignored, missing arrays filled. With sources, the CSS export carries `file:line` and the Not reachable tab lists the literals; without, the export omits locations and the tab is hidden |
| CLI | `tokentweak-scan <globs…> --root <dir> [--out <file>] [--exclude <name,…>]`, globs through `fs.promises.glob`; a 1:1 port of `StyleGuideScan.scala`'s regexes and comment blanking: `.css` yields root tokens (with the comment above as `note`), color literals and length literals; markup yields the literals of inline `style=` declarations; `.js` is accepted and yields nothing. Locations are the path relative to `--root` plus `:line`. Output is pretty JSON with `version: 1`. `cli/index.ts` is the pure `scan({ files: { path, text }[] })` the tests drive |
| Other config | `hostId` (`tokentweak`), `title` (`Tweaks`), `trigger` (selector or element, delegated on `document` for click and Enter/Space so a swapped-in button keeps working; default none), `shortcut` (`Ctrl+Alt+T`; `null` disables), `dock` (`right`, first run only), `inertGuard` (true: a MutationObserver strips `inert` from the host) |
| Panel look | `panel.css.ts`, a constructable sheet on the shadow root with its own `--tt-*` palette and pinned inherited text properties, as today's `--dt-*` |
| Tests | `node --test test/unit/` over the pure modules with fakes for storage, sheet and `supports`; one Playwright test over `demo/` (served by `scripts/serve.mjs`, a dependency-free static server): toggle with the shortcut, set a theme color, assert the computed value, reload and assert again, then abort the main module with `page.route`, reload, and assert the value still holds and `document.adoptedStyleSheets[0]` carries the marker. `npx playwright install chromium` once |
| Lint | ESLint flat config (`@eslint/js`, `typescript-eslint`, prettier plugin) and Altitude's `.prettierrc.json` values (no semicolons, four spaces, double quotes); `dist/` and `demo/sources.json` ignored |
| Altitude's shared modules | `common/token-kinds.js`, `theme.js` and `font-availability.js` stay as they are: the style guide needs `TOKEN_KINDS` sample metadata, Altitude's group names and `rememberedTheme`, none of which belong in the package. The generic overlap is duplicated with the vendored file, by choice |
| Dev-only guarantee | The vendored files are served under `/static/` like every lib but referenced only inside `@if(Environment.isDev)`; the non-dev controller test asserts the index and the nav name neither `design-tweaks`, `designTweaks` nor `tokentweak` |
| Publishing | `npm pack` produces `tokentweak-0.1.0.tgz` and its contents are checked; `npm publish` and the git tag are the user's steps after the Altitude checks pass |

## Package layout

```
tokentweak/
  package.json  tsconfig.json  tsconfig.build.json  eslint.config.js  .prettierrc.json  .prettierignore  .gitignore
  LICENSE  README.md  CHANGELOG.md  playwright.config.ts
  scripts/build.mjs  scripts/serve.mjs
  src/
    index.ts      createTokenTweaks, handle, trigger and shortcut, inert guard, extras at load, re-exports
    config.ts     TokenTweaksConfig, defaults, resolveConfig
    state.ts      TweaksState, Snapshot, Change, DEFAULT_STATE, readState, copyName (pure)
    store.ts      TweaksStore over { storage: { get, set }, sheet: { replace } } ports (pure)
    sheet.ts      sheetText(tweaks, scoped, rootSelector, storageKey) with the marker rule; findBootSheet
    tokens.ts     stylesheet selection, readTokens from the CSSOM, waiting for load
    kinds.ts      kindOf(name, value, { supports }), splitOutsideParentheses, referencedNamesOf, TOKEN_REFERENCE
    sections.ts   section inference and overrides, scale and role inference, type-scale inference (pure)
    theme.ts      THEMES, themeColorsOf, withThemeColor, applyTheme, themeOrStylesheets, resolveColor, inTheme
    color.ts      oklchOf, hexOf, isColor
    fonts.ts      isFontAvailable, asFontFamily
    dom.ts        el, copyText, downloadText, flash
    controls.ts   one builder per kind, role, scoped pixel, number, type scale, seed, extra-control card
    inspect.ts    Inspector, tokensReferencedBy over every selected sheet
    exports.ts    buildPrompt, buildCss, buildSnapshotJson (pure; color phrasing via injected oklchOf/hexOf)
    sources.ts    Sources type, loadSources, normalizeSources
    panel.ts      the Panel class: header, tabs, cards, filters, snapshots, exports, unreachable
    panel.css.ts  the constructable sheet
    boot.ts       the pre-paint entry
  cli/index.ts  cli/scan.ts
  test/unit/*.test.ts  test/e2e/smoke.spec.ts  test/fixtures/
  demo/index.html  demo/demo.css  demo/demo.js  demo/sources.json (generated by `npm run demo:scan`)
  dist/ (gitignored, published)
```

`package.json`: `type: module`, `engines.node >= 22`, `repository` `git+https://github.com/papito/tokentweak.git`,
`sideEffects: ["./dist/boot.js", "./dist/tokentweak.iife.js"]`, `types: ./dist/index.d.ts`, `exports` for `.`,
`./boot`, `./iife`, `./cli`, `./package.json`, `bin: { tokentweak-scan }`, `files: [dist, LICENSE, README.md,
CHANGELOG.md]`, scripts `build`, `check`, `lint`, `format`, `test`, `test:e2e`, `demo`, `demo:scan`, `prepublishOnly`.
Dev dependencies: esbuild, typescript, @types/node, @playwright/test, eslint, @eslint/js, typescript-eslint,
eslint-plugin-prettier, eslint-config-prettier, prettier, globals.

## Altitude after the migration

- `static/js/lib/tokentweak.js`, `lib/tokentweak-boot.js`, `lib/tokentweak.LICENSE`: copied from the built package;
  `lib/README.md` gains their rows (version `0.1.0`, the dist paths) and a sentence on the boot script's `data-key`.
- `static/js/design-tweaks.js`, the adapter, imports `createTokenTweaks` from `./lib/tokentweak.js` and the explorer
  functions from `./common/explorer-split.js`, and passes: `storageKey: Const.localStore.designTweaks`,
  `hostId: "designTweaks"`, `stylesheet: "/assets/"`, `trigger: "#designTweaksBtn"`, `shortcut: "Ctrl+Alt+T"`,
  `sources: "/style-guide/scan.json"`, the sections (Theme colors `/^--theme-/`; Status and shadow
  `/^--(?:(?:success|warning|error)-.+|shadow-color)$/`; Derived, kinds color; Typography, kinds font and typography;
  Scale, `scaleRole === "step"`; Roles, `scaleRole === "role"`; Sizes, kinds length; Borders and shadows, kinds border
  and shadow; Panels), `scale: { steps: /^--space-\d+$/ }`, the type scale (`--font-size-{xs,small,base,medium,
  large,xl,display}`, base `--font-size-base`), the seed targets (`--theme-{base,raised,muted,text,accent}`), the two
  scoped tokens (`--preview-box` on `#assets`, 200px, label `Const.AssetView.PREVIEW_BOX_PIXELS`; `--face-box` on
  `#people`, 80px, label `FaceDetectionService.faceDetectionBoxPx`; both 40..600 step 10, section Panels), the explorer
  extra control (`explorerPercent`, `setExplorerSizes`, `hasExplorerSplit`, 10..70 %, default 25, section Panels,
  the unavailable note "Set on the main page, where the explorer is", the Split sizes phrasing) and
  `exports: { fileName: "core.css" }`.
- `views/includes/header_common.scala.html`: inside `@if(Environment.isDev)`, the boot script tag with
  `data-key="designTweaks"` and the module tag for `/static/js/design-tweaks.js`; the comment explains both.
- `views/includes/nav.scala.html`: markup unchanged; the comment says the adapter passes the id as `trigger`.
- `static/js/constants.js`: the `designTweaks` comment points at the adapter and the boot script's `data-key`.
- `StyleGuideController.excludedFiles`: the adapter's file name only; the doc comment names the sources shape.
  `StyleGuideScan.Result.toJson` gains `"version" -> 1`.
- `StyleGuideControllerTests`: the non-dev test also asserts `tokentweak` is absent from the index and the nav; the
  scan route test asserts `version` is 1.
- `static/js/design-tweaks/` is deleted. The `common/` header comments no longer say "shared with the design tweaks
  panel"; `explorer-split.js`'s says the adapter passes `setExplorerSizes` as an extra control.
- `views/AGENTS.md`: the Stack bullet lists tokentweak among the vendored libraries; the directory row for the panel
  names the adapter; Key Files drop the five module rows and add the adapter, `lib/tokentweak.js` and
  `lib/tokentweak-boot.js`; the Style guide entry no longer says the panel imports the shared modules; the **Design
  tweaks** entry describes the package (its README), what the adapter configures, the boot script and `data-key`, the
  marker-rule handshake and the dev-only guarantee. `docs/test-coverage.md` names the `version` field if it describes
  the route's shape.

## Tasks

1. **Scaffold tokentweak**: `git init ~/projects/tokentweak`; `package.json`, `tsconfig.json`, `tsconfig.build.json`,
   `scripts/build.mjs`, `scripts/serve.mjs`, `eslint.config.js`, `.prettierrc.json`, `.prettierignore`, `.gitignore`,
   `LICENSE`, `README.md` outline, `CHANGELOG.md`, a `src/index.ts` and `src/boot.ts` stub; `npm install`;
   `npm run build` produces the four outputs and the `.d.ts` files; `npm run lint` and `npm run check` pass.
2. **The pure core with tests**: `src/config.ts`, `state.ts`, `store.ts`, `sheet.ts`, `kinds.ts`, `sections.ts`,
   `sources.ts`, `exports.ts`, ported from `altitude/static/js/design-tweaks/tweaks-store.js`, `exports.js` and
   `altitude/static/js/common/token-kinds.js`; `test/unit/*.test.ts` cover: the store (defaults, set/reset/many/all,
   extras, changedCount, snapshots and `copyName`, A/B, `css` on commit, change kinds, a stale state), the sheet text
   (marker first, root block, scoped rules, a custom root selector), kinds (each kind with a table `supports`,
   typography by name, the splitters), sections (inferred, singletons to Other, regex/prefix/predicate/kinds overrides,
   scale and role inference, type-scale inference and config), exports (grouping, pair sides, length phrases,
   scoped and extra notes, `fileName`, missing locations, stubbed color phrasing), sources normalization. `npm test`
   green.
3. **The browser layer**: `src/theme.ts`, `color.ts`, `fonts.ts`, `dom.ts`, `controls.ts`, `inspect.ts`,
   `panel.css.ts`, `panel.ts`, `tokens.ts`, `index.ts`, `boot.ts`, ported from `design-tweaks.js`, `controls.js`,
   `inspect.js`, `panel.css.js`, `common/theme.js`, `common/font-availability.js`, generalized as the decisions say;
   `npm run build`, `npm run check`, `npm run lint` pass.
4. **The CLI**: `cli/index.ts` and `cli/scan.ts` porting `StyleGuideScan.scala`; fixtures and tests ported from
   `altitude/test/src/altitude/core/unit/StyleGuideScanTests.scala` (root tokens with notes, a hex literal against an id
   selector, sizing-property length literals, blanked comments keeping line numbers); `npm test` green.
5. **Demo, smoke test, release prep**: `demo/` with tokens modeled on `core.css` (light-dark theme colors,
   `--space-1..9`, font sizes, a role, an `@property`), `npm run demo:scan`, `test/e2e/smoke.spec.ts` with
   `playwright.config.ts` and its `webServer`; the README finished (install, the script tag and the import, the boot
   script, every config option, the sources schema, the CLI, the exports); `npm pack` and the tarball's contents
   checked.
6. **Altitude: vendor and adapter**: the three `lib/` files; `static/js/design-tweaks.js`; `header_common`, the nav
   comment, the `constants.js` comment, the `common/*.js` header comments; `static/js/design-tweaks/` deleted;
   `make lint`.
7. **Altitude: server, tests and docs**: `StyleGuideController`, `StyleGuideScan.toJson`,
   `StyleGuideControllerTests`; `lib/README.md`, `views/AGENTS.md`, `docs/test-coverage.md`; `make test-unit`,
   `make test-controllers`.
8. **Browser verification**: the checks below in Altitude; a fix lands in tokentweak, is rebuilt and re-vendored.

## Checks

tokentweak: `npm run check`, `npm run lint`, `npm test`, `npm run build`, `npm run demo:scan`, `npm run test:e2e`;
`npm pack` holds `dist/`, LICENSE, README and CHANGELOG only; the demo page works through every tab by hand.

Altitude: `make lint`, `make test-unit`, `make test-controllers`; in the browser on a dev run: a theme color change
repaints the nav, the explorer and a dialog; a `--space-*` step moves the grid; a role token's select follows the scale
and detaches to rem; a snapshot survives a reload without a flash and an htmx tab switch; the preview slider resizes the
cells and the face slider the people grid; the explorer slider moves the gutter and the stored Split sizes; the CSS
export applied to `core.css` reproduces the look with the panel reset; the Not reachable tab lists the scan's literals;
Inspect filters to an element's tokens; the panel stays usable while a modal is open; the nav button works after a nav
reload; the style guide is unchanged; a non-dev run serves no panel script, no boot script and no button.
