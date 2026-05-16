export function showSuccessSnackBar(message) {
    window.Oat.toast(message, { variant: "success" })
}

export function showWarningSnackBar(message) {
    window.Oat.toast(message, { variant: "warning" })
}

export function showErrorSnackBar(message) {
    window.Oat.toast(message, { variant: "error" })
}
