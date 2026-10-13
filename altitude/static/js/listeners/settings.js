import { Const } from "../constants.js"
import { showSuccessSnackBar } from "../common/snackbar.js"

/** The Settings form's outcome: a save is confirmed through the snackbar */
export function registerSettingsListeners() {
    document.body.addEventListener(Const.events.settingsSaved, () => {
        showSuccessSnackBar("Settings saved")
    })
}
