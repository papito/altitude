import js from "@eslint/js"
import importPlugin from "eslint-plugin-import"
import prettierRecommended from "eslint-plugin-prettier/recommended"
import globals from "globals"

/**
 * ESLint flat config for the front-end modules under altitude/static/js. The vendored libraries
 * in static/js/lib are not linted. `htmx` and `interact` are loaded as plain scripts by the page
 * templates, so they are globals; Alpine is imported from its ES module wherever it is used.
 */
export default [
    {
        ignores: ["altitude/static/js/lib/**", "altitude/static/js/out/**"],
    },
    js.configs.recommended,
    importPlugin.flatConfigs.recommended,
    prettierRecommended,
    {
        files: ["altitude/static/js/**/*.js"],
        languageOptions: {
            ecmaVersion: 2023,
            sourceType: "module",
            globals: {
                ...globals.browser,
                htmx: "readonly",
                interact: "readonly",
            },
        },
        settings: {
            "import/resolver": {
                node: {
                    extensions: [".js"],
                },
            },
        },
        rules: {
            "prettier/prettier": ["error", { endOfLine: "auto" }],
            "no-unused-vars": [
                "error",
                { argsIgnorePattern: "^_", caughtErrors: "none" },
            ],
            "import/extensions": ["error", "ignorePackages", { js: "always" }],
            "import/no-unresolved": ["error", { ignore: ["^https://"] }],
            "import/no-cycle": "error",
            "max-len": [
                "warn",
                { code: 160, ignoreComments: true, ignoreUrls: true },
            ],
            semi: ["error", "never"],
        },
    },
]
