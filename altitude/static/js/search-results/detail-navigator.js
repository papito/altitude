import { Const } from "../constants.js"

export function setImgSrcAndWait(img, url) {
    return new Promise((resolve, reject) => {
        const onLoad = () => {
            cleanup()
            resolve(img)
        }
        const onError = (e) => {
            cleanup()
            reject(e)
        }

        const cleanup = () => {
            Alpine.store(Const.state.imageDetailLoading).value = false
            img.removeEventListener("load", onLoad)
            img.removeEventListener("error", onError)
        }

        img.addEventListener("load", onLoad, { once: true })
        img.addEventListener("error", onError, { once: true })

        // Important: set src AFTER listeners are attached
        img.src = url
    })
}

export function initDetailNavigator() {}
