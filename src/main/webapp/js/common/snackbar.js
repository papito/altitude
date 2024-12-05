const state = {
  snackbarTimeout: null,
}

function _showSnackbar({ type = "success", message }) {
  let elSnackbar = htmx.find("#snackbar")

  elSnackbar.classList.remove("success")
  elSnackbar.classList.remove("warning")
  elSnackbar.classList.remove("error")

  elSnackbar.innerHTML = message
  elSnackbar.classList.add("show")

  if (type === "error") {
    elSnackbar.classList.add("error")
  } else if (type === "warning") {
    elSnackbar.classList.add("warning")
  } else {
    elSnackbar.classList.add("success")
  }

  if (state.snackbarTimeout) {
    clearTimeout(state.snackbarTimeout)
  }

  state.snackbarTimeout = setTimeout(function () {
    elSnackbar.classList.remove("show")
    elSnackbar.innerHTML = ""
  }, 3000)
}

export function showSuccessSnackBar(message) {
  _showSnackbar({ message: message })
}

export function showWarningSnackBar(message) {
  _showSnackbar({ type: "warning", message: message })
}

export function showErrorSnackBar(message) {
  _showSnackbar({ type: "error", message: message })
}
