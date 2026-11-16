/**
 * The design tweaks panel (dev only; its script tags in `includes/header_common` are commented out): the vendored
 * tokentweak package configured for Altitude. The package reads every `:root` token of the bundle and applies changes
 * through one adopted stylesheet, kept in local storage so the boot script adopts it again before the first paint.
 * This module passes only what the package cannot infer: the style guide's section order, the spacing and type
 * scales, the two tokens the server sets inline, the explorer size (a share, not a token), the scan and the export
 * file name. `Ctrl+Alt+T` toggles the panel; there is no trigger element.
 */
import {
    DEFAULT_WIDTH_SHARE,
    explorerShare,
    hasExplorerDock,
    setExplorerShare,
} from "./common/explorer-dock.js"
import { Const } from "./constants.js"
import { createTokenTweaks } from "./lib/tokentweak.js"

// The package takes one default, so a top or bottom dock, whose default height share differs, resets to the width's
const EXPLORER_DEFAULT = DEFAULT_WIDTH_SHARE

createTokenTweaks({
    storageKey: Const.localStore.designTweaks,
    hostId: "designTweaks",
    stylesheet: "/assets/",
    shortcut: "Ctrl+Alt+T",
    sources: "/style-guide/scan.json",
    // The style guide's sections, in its order; what none claims goes to the automatic Other
    sections: [
        { id: "theme", title: "Theme colors", match: /^--theme-/ },
        {
            id: "status",
            title: "Status, shadow and channels",
            match: /^--(?:(?:success|warning|error|info)-.+|shadow-color|histogram-(?:red|green|blue))$/,
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
    // The two tokens the server sets inline; the defaults (the server constants' values) serve a page without their
    // containers
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
            label: "Explorer size",
            section: "panels",
            min: 10,
            max: 70,
            step: 1,
            unit: "%",
            default: EXPLORER_DEFAULT,
            get: explorerShare,
            set: setExplorerShare,
            available: hasExplorerDock,
            unavailableNote: "Set on the main page, where the explorer is",
            exportLabel:
                "the explorer's share of the width or the height on its Dock side (js/common/explorer-dock.js)",
            describe: (value, defaultValue) =>
                `An explorer share of ${value}% of its axis instead of ${defaultValue}%`,
        },
    ],
    exports: { fileName: "core.css" },
}).catch((error) =>
    console.error("The design tweaks panel did not start", error),
)
