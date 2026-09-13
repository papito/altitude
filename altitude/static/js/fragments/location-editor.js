/**
 * The Location pin editor (`data-app-fragment="location-editor"`, inside the Add location modal,
 * `htmx/add_location_dialog.scala.html`): a Leaflet map on which the pin is placed by clicking and
 * moved by dragging, a read-only readout of the pin under it, and, when the server enabled the
 * geocoder, a place-name search whose result places the pin. The coordinates are never typed: the
 * editor writes them into the form's hidden `latitude` / `longitude` inputs, which is all the
 * server sees.
 *
 * Leaflet is the plain-script `window.L` (see `static/js/lib/README.md`). One editor exists at a
 * time: hydrating a new one (a fresh dialog, or a validation replacement of the form, which
 * re-renders the hidden inputs with the submitted values) disposes of the previous map first. The
 * modal host is shown by `x-show` in a later task than the swap, so the map is sized once its
 * container is actually displayed, and again whenever the container resizes.
 */
import { showErrorSnackBar } from "../common/snackbar.js"
import { getHttpErrorMessage, http } from "../http/client.js"

const PIN_ZOOM = 12
const WORLD_VIEW = { center: [20, 0], zoom: 2 }
const HIDDEN_DECIMALS = 6
const READOUT_DECIMALS = 4

// The editor currently on screen, disposed of before the next one is created
let current = null

export function hydrateLocationEditorFragment({ fragmentEl }) {
    if (typeof L === "undefined") {
        console.warn("Leaflet is not loaded; the location editor has no map")
        return
    }

    disposeCurrentEditor()

    const mapEl = fragmentEl.querySelector("#locationEditorMap")
    const latitudeEl = fragmentEl.querySelector('input[name="latitude"]')
    const longitudeEl = fragmentEl.querySelector('input[name="longitude"]')
    const readoutEl = fragmentEl.querySelector("#locationPinReadout")
    const nameEl = fragmentEl
        .closest("form")
        ?.querySelector('input[name="name"]')

    const map = L.map(mapEl, { worldCopyJump: true })
    L.tileLayer(fragmentEl.dataset.mapTileUrl, {
        attribution: fragmentEl.dataset.mapAttribution,
        maxZoom: 19,
    }).addTo(map)

    const pinIcon = L.divIcon({
        className: "location-pin",
        html: '<i class="fas fa-map-marker-alt" aria-hidden="true"></i>',
        iconSize: [28, 28],
        // The glyph's tip is its bottom centre
        iconAnchor: [14, 28],
    })
    let marker = null

    /** Places or moves the pin and writes it into the hidden inputs and the readout */
    function setPin(latlng) {
        // A click past the antimeridian, after panning around the world, is folded back into -180..180
        const point = latlng.wrap()

        if (marker) {
            marker.setLatLng(point)
        } else {
            marker = L.marker(point, { draggable: true, icon: pinIcon }).addTo(
                map,
            )
            marker.on("dragend", () => setPin(marker.getLatLng()))
        }

        latitudeEl.value = point.lat.toFixed(HIDDEN_DECIMALS)
        longitudeEl.value = point.lng.toFixed(HIDDEN_DECIMALS)
        readoutEl.textContent = `${point.lat.toFixed(READOUT_DECIMALS)}, ${point.lng.toFixed(READOUT_DECIMALS)}`
    }

    map.on("click", (event) => setPin(event.latlng))

    // A validation replacement carries the submitted pin: show it where it was
    const initialPin = parsePin(latitudeEl.value, longitudeEl.value)
    if (initialPin) {
        map.setView(initialPin, PIN_ZOOM)
        setPin(L.latLng(initialPin))
    } else {
        map.setView(WORLD_VIEW.center, WORLD_VIEW.zoom)
    }

    // Leaflet measures the container when the map is created; inside a modal that is still
    // `display: none` that measurement is zero, so it is repeated once the host is displayed
    const resizeObserver = new ResizeObserver(() => map.invalidateSize())
    resizeObserver.observe(mapEl)
    whenDisplayed(mapEl, () => map.invalidateSize())

    bindGeocoder(fragmentEl, { map, setPin, nameEl })

    current = { map, resizeObserver }
}

/**
 * Whatever state the previous map is in (its container may already have left the DOM with the
 * dialog that held it), the new dialog must get its map: a failing removal is logged, not raised.
 */
function disposeCurrentEditor() {
    if (!current) {
        return
    }

    const previous = current
    current = null
    previous.resizeObserver.disconnect()

    try {
        previous.map.remove()
    } catch (error) {
        console.warn(
            "Could not dispose of the previous location editor map",
            error,
        )
    }
}

function parsePin(latitude, longitude) {
    const lat = Number(latitude)
    const lng = Number(longitude)

    if (
        latitude === "" ||
        longitude === "" ||
        !Number.isFinite(lat) ||
        !Number.isFinite(lng)
    ) {
        return null
    }

    return [lat, lng]
}

/** Runs `callback` once `el` is displayed (the modal's `x-show` reveals it in a later task), giving up if it never is */
function whenDisplayed(el, callback, attempt = 0) {
    if (!el.isConnected || attempt > 100) {
        return
    }

    if (el.offsetParent === null) {
        setTimeout(() => whenDisplayed(el, callback, attempt + 1))
        return
    }

    callback()
}

/**
 * The place-name search, present only when the server rendered it (the geocoder is config-gated).
 * Enter in the box or the Search button asks the server's proxy; each result is a button that
 * jumps the map to the place, places the pin, and fills the name when it is still empty (the first
 * segment of the place's label, which Nominatim writes as "Name, Street, City, Country"). The
 * newest search wins: a slower earlier response is dropped.
 */
function bindGeocoder(fragmentEl, { map, setPin, nameEl }) {
    const inputEl = fragmentEl.querySelector("#locationGeocode")
    if (!inputEl) {
        return
    }

    const buttonEl = fragmentEl.querySelector("#locationGeocodeBtn")
    const resultsEl = fragmentEl.querySelector("#locationGeocodeResults")
    let searchSeq = 0

    async function search() {
        const q = inputEl.value.trim()
        if (!q) {
            return
        }

        const seq = ++searchSeq

        try {
            const response = await http.get(
                `/api/map/r/${window.ctx.getRepoId()}/geocode`,
                { params: { q } },
            )
            if (seq !== searchSeq || !fragmentEl.isConnected) {
                return
            }

            renderResults(response.data)
        } catch (error) {
            if (seq !== searchSeq) {
                return
            }

            showErrorSnackBar(
                `Place search failed: ${getHttpErrorMessage(error)}`,
            )
        }
    }

    function renderResults(results) {
        if (results.length === 0) {
            const noneEl = document.createElement("div")
            noneEl.textContent = "No places found"
            resultsEl.replaceChildren(noneEl)
            return
        }

        resultsEl.replaceChildren(
            ...results.map((result) => {
                const resultEl = document.createElement("button")
                resultEl.type = "button"
                resultEl.textContent = result.label
                resultEl.addEventListener("click", () => {
                    const point = L.latLng(result.latitude, result.longitude)
                    map.setView(point, PIN_ZOOM)
                    setPin(point)

                    if (nameEl && !nameEl.value.trim()) {
                        nameEl.value = result.label.split(",")[0].trim()
                    }

                    resultsEl.replaceChildren()
                })
                return resultEl
            }),
        )
    }

    // Enter in the box searches rather than submitting the form
    inputEl.addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
            event.preventDefault()
            search()
        }
    })
    buttonEl.addEventListener("click", search)
}
