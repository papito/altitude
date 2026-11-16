import { Const } from "../constants.js"
import { showSuccessSnackBar } from "../common/snackbar.js"

export function registerSettingsListeners() {
    document.body.addEventListener(Const.events.settingsSaved, () => {
        showSuccessSnackBar("Settings saved")
    })
}
