import js from "@eslint/js"
import importPlugin from "eslint-plugin-import"
import prettierRecommended from "eslint-plugin-prettier/recommended"
import globals from "globals"

/**
 * ESLint flat config for the front-end modules; the vendored libraries are not linted. The page
 * modules see the browser's globals plus the libraries loaded as plain scripts (Leaflet,
 * supercluster and Flatpickr by js/common/vendor-loader.js); a Worker (`*-worker.js`) sees the
 * Worker scope's and no `document`.
 */

const language = { ecmaVersion: 2023, sourceType: "module" }

const settings = {
    "import/resolver": {
        node: {
            extensions: [".js"],
        },
    },
}

const rules = {
    "prettier/prettier": ["error", { endOfLine: "auto" }],
    "no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", caughtErrors: "none" },
    ],
    "import/extensions": ["error", "ignorePackages", { js: "always" }],
    "import/no-unresolved": ["error", { ignore: ["^https://"] }],
    "import/no-cycle": "error",
    "max-len": ["warn", { code: 160, ignoreComments: true, ignoreUrls: true }],
    semi: ["error", "never"],
}

export default [
    {
        ignores: ["altitude/static/js/lib/**", "altitude/static/js/out/**"],
    },
    js.configs.recommended,
    importPlugin.flatConfigs.recommended,
    prettierRecommended,
    {
        files: ["altitude/static/js/**/*.js"],
        ignores: ["altitude/static/js/**/*-worker.js"],
        languageOptions: {
            ...language,
            globals: {
                ...globals.browser,
                htmx: "readonly",
                interact: "readonly",
                L: "readonly",
                Supercluster: "readonly",
                flatpickr: "readonly",
            },
        },
        settings,
        rules,
    },
    {
        files: ["altitude/static/js/**/*-worker.js"],
        languageOptions: {
            ...language,
            // The worker set of globals (v14) stops at the classic Worker scope: what a Worker
            // that owns an OffscreenCanvas also has is listed by hand
            globals: {
                ...globals.worker,
                OffscreenCanvas: "readonly",
                Path2D: "readonly",
                requestAnimationFrame: "readonly",
                cancelAnimationFrame: "readonly",
            },
        },
        settings,
        rules,
    },
]
