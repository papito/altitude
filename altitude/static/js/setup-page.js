import { startSetupBackground } from "./setup-background/setup-background.js"

/**
 * The setup page. Its form submits through htmx and json-enc.js, loaded as plain scripts, so the module only starts the
 * background scene.
 */
startSetupBackground(document.getElementById("setupBackground"))
