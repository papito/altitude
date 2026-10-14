import { startSetupBackground } from "./setup-background/setup-background.js"

/**
 * The setup page. Its one form submits through htmx and json-enc.js, which the page loads as plain scripts, so the module
 * registers nothing else: it only starts the background scene (js/setup-background/) on the page's canvas.
 */
startSetupBackground(document.getElementById("setupBackground"))
