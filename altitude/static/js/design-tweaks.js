/**
 * The design tweaks panel (dev only, loaded by `includes/header_common` on every page): the vendored
 * tokentweak package (`lib/tokentweak.js`) configured for Altitude. The package reads every `:root`
 * token of the bundle (the stylesheet served under `/assets/`), builds the panel in a shadow root on a
 * `popover="manual"` host and applies every change through one adopted stylesheet, whose text it
 * keeps under `Const.localStore.designTweaks` so the boot script (`lib/tokentweak-boot.js`, the same
 * key in its `data-key`) adopts it again before the first paint. This module only passes what the
 * package cannot infer: the sections in the style guide's order, the spacing scale, the type scale,
 * the two tokens the server sets inline on their containers, the explorer width
 * (no token: a Split.js size `js/common/explorer-split.js` owns), the scan the server serves and
 * the file the exports name. The nav's Tweaks button is the trigger; `Ctrl+Alt+T` toggles too.
 */
import {
    explorerPercent,
    hasExplorerSplit,
    setExplorerSizes,
} from "./common/explorer-split.js"
import { Const } from "./constants.js"
import { createTokenTweaks } from "./lib/tokentweak.js"

// The explorer's default share of the width, the first of explorer-split.js's default Split sizes
const EXPLORER_DEFAULT = 25

createTokenTweaks({
    storageKey: Const.localStore.designTweaks,
    hostId: "designTweaks",
    stylesheet: "/assets/",
    trigger: "#designTweaksBtn",
    shortcut: "Ctrl+Alt+T",
    sources: "/style-guide/scan.json",
    // The style guide's sections, in its order; what none claims goes to the automatic Other
    sections: [
        { id: "theme", title: "Theme colors", match: /^--theme-/ },
        {
            id: "status",
            title: "Status and shadow",
            match: /^--(?:(?:success|warning|error|info)-.+|shadow-color)$/,
        },
        { id: "derived", title: "Derived", kinds: ["color"] },
        {
            id: "typography",
            title: "Typography",
            kinds: ["font", "typography"],
        },
        {
            id: "scale",
            title: "Scale",
            match: (token) => token.scaleRole === "step",
        },
        {
            id: "roles",
            title: "Roles",
            match: (token) => token.scaleRole === "role",
        },
        { id: "sizes", title: "Sizes", kinds: ["length"] },
        {
            id: "borders",
            title: "Borders and shadows",
            kinds: ["border", "shadow"],
        },
        // The two container tokens and the explorer width, nothing from the root
        { id: "panels", title: "Panels" },
    ],
    scale: { steps: /^--space-\d+$/ },
    typeScale: {
        steps: [
            "--font-size-xs",
            "--font-size-small",
            "--font-size-base",
            "--font-size-medium",
            "--font-size-large",
            "--font-size-xl",
            "--font-size-display",
        ],
        base: "--font-size-base",
    },
    // The two tokens the server sets inline, with the server constants' values for a page without
    // their containers; a page that has one reads the inline value instead
    scopedTokens: [
        {
            name: "--preview-box",
            selector: "#assets",
            default: "200px",
            control: { min: 40, max: 600, step: 10 },
            section: "panels",
            exportLabel: "Const.AssetView.PREVIEW_BOX_PIXELS",
        },
        {
            name: "--face-box",
            selector: "#people",
            default: "80px",
            control: { min: 40, max: 600, step: 10 },
            section: "panels",
            exportLabel: "FaceDetectionService.faceDetectionBoxPx",
        },
    ],
    extraControls: [
        {
            id: "explorer",
            label: "Explorer width",
            section: "panels",
            min: 10,
            max: 70,
            step: 1,
            unit: "%",
            default: EXPLORER_DEFAULT,
            get: explorerPercent,
            set: setExplorerSizes,
            available: hasExplorerSplit,
            unavailableNote: "Set on the main page, where the explorer is",
            exportLabel:
                "the explorer split (Split.js sizes in js/common/explorer-split.js)",
            describe: (value, defaultValue) =>
                `Split sizes [${value}, ${100 - value}] instead of [${defaultValue}, ${100 - defaultValue}]`,
        },
    ],
    exports: { fileName: "core.css" },
}).catch((error) =>
    console.error("The design tweaks panel did not start", error),
)
