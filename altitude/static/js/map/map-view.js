import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { showErrorSnackBar } from "../common/snackbar.js"
import { getHttpErrorMessage, http } from "../http/client.js"
import { runSearch } from "../search-results/search.js"
import { openMapPanel } from "./map-panel.js"
import {
    currentScopeFingerprint,
    rememberedMapView,
    rememberMapView,
    setDisplayedMapView,
} from "./map-state.js"

/**
 * The map view (`data-app-fragment="map-view"`, `htmx/map_view.scala.html`): the results of the
 * current search plotted on a Leaflet map, in place of the grid (`layout=map`).
 *
 * The browser never receives a result set. On every settled move (debounced) the map asks
 * `/api/map/r/:repoId/cells` for its viewport at its zoom, sending the store's search parameters
 * verbatim, and gets back aggregates: cells (a count of plotted points, a centroid and the asset
 * that represents the cell) and the Locations pinned in the viewport that hold a matching asset.
 * The cells are clustered once more on screen with supercluster, so cells that overlap at this
 * zoom merge into one pin, and each pin is a Leaflet div icon:
 *
 * - a cell of one point is a thumbnail pin whose click opens the asset's detail modal, through the
 *   same `hx-get` a grid cell's image carries;
 * - a crowded pin (a cluster, or a cell of several points) shows the representative thumbnail with
 *   a count badge; a click flies to the zoom the cluster splits at, or, when it would not split
 *   below the maximum zoom, opens the crowded-pin panel (js/map/map-panel.js) for the pin's area;
 * - a Location's pin shows the glyph and the Location's path, and a click scopes the search to it.
 *
 * The initial view is the one last looked at in this scope (js/map/map-state.js), else the server's
 * bounds of everything plotted, else the world. Leaflet and supercluster are the plain scripts
 * `window.L` and `window.Supercluster` (static/js/lib/README.md). One map exists at a time: a new
 * results fragment disposes of the previous one.
 */

// The tile layer's maximum, and the deepest zoom the cells are asked at
const MAX_ZOOM = 19
const WORLD_VIEW = { center: [20, 0], zoom: 2 }
// Fitting the server's bounds never zooms past this: a single point still shows its surroundings
const FIT_MAX_ZOOM = 15
const FIT_PADDING_PX = 40
const CLUSTER_RADIUS_PX = 60
const CELLS_DEBOUNCE_MS = 150
const PIN_SIZE_PX = 56
const COORDINATE_DECIMALS = 6

// The map on screen, disposed of before the next one is created
let current = null

export function hydrateMapViewFragment({ fragmentEl }) {
    if (typeof L === "undefined" || typeof Supercluster === "undefined") {
        console.warn("Leaflet or supercluster is not loaded; there is no map")
        return
    }

    disposeMapView()

    const repoId = window.ctx.getRepoId()
    const fingerprint = currentScopeFingerprint()

    const map = L.map(fragmentEl, { worldCopyJump: true, maxZoom: MAX_ZOOM })
    L.tileLayer(fragmentEl.dataset.mapTileUrl, {
        attribution: fragmentEl.dataset.mapAttribution,
        maxZoom: MAX_ZOOM,
    }).addTo(map)
    const pinsLayer = L.layerGroup().addTo(map)

    // Superseded responses are dropped: only the request for the latest settled view may render
    let cellsSequence = 0
    let cellsTimer = null

    function currentView() {
        const center = map.getCenter()
        return { center: [center.lat, center.lng], zoom: map.getZoom() }
    }

    async function requestCells() {
        const sequence = ++cellsSequence
        const zoom = Math.round(map.getZoom())
        const viewport = viewportOf(map)
        const query = Alpine.store(Const.state.searchParams).toQueryString({
            viewport,
            zoom,
        })

        try {
            const response = await http.get(
                `/api/map/r/${repoId}/cells?${query}`,
            )
            if (sequence !== cellsSequence || current?.map !== map) {
                return
            }

            renderPins(response.data, { zoom, viewport })
        } catch (error) {
            if (sequence !== cellsSequence) {
                return
            }

            showErrorSnackBar(
                `Could not load the map: ${getHttpErrorMessage(error)}`,
            )
        }
    }

    function scheduleCells() {
        clearTimeout(cellsTimer)
        cellsTimer = setTimeout(requestCells, CELLS_DEBOUNCE_MS)
    }

    /**
     * Rebuilds the pins from one response: the cells are clustered for this zoom and viewport, so
     * a cell stays a pin of its own until it would overlap another on screen. A cluster's count is
     * the sum of its cells', and the largest cell's asset represents it.
     */
    function renderPins({ cells, locations }, { zoom, viewport }) {
        const index = new Supercluster({
            radius: CLUSTER_RADIUS_PX,
            // Leaflet's tiles are 256 px, so the radius is in screen pixels
            extent: 256,
            maxZoom: MAX_ZOOM,
            map: (props) => ({
                count: props.count,
                assetId: props.assetId,
                representedCount: props.count,
            }),
            reduce: (accumulated, props) => {
                accumulated.count += props.count
                if (props.representedCount > accumulated.representedCount) {
                    accumulated.assetId = props.assetId
                    accumulated.representedCount = props.representedCount
                }
            },
        })
        index.load(cells.map(cellFeature))

        const [south, west, north, east] = viewport.split(",").map(Number)
        const centerLng = map.getCenter().lng
        const cellDegrees = cellDegreesAt(zoom)

        pinsLayer.clearLayers()

        index
            .getClusters([west, south, east, north], zoom)
            .forEach((feature) => {
                const [lng, lat] = feature.geometry.coordinates
                const {
                    count,
                    assetId,
                    cluster,
                    cluster_id: clusterId,
                } = feature.properties
                const point = [lat, nearestCopyOf(lng, centerLng)]

                if (count === 1) {
                    addAssetPin({ point, assetId, count })
                    return
                }

                addAssetPin({
                    point,
                    assetId,
                    count,
                    onClick: () => {
                        if (cluster) {
                            const expansionZoom =
                                index.getClusterExpansionZoom(clusterId)
                            if (expansionZoom <= MAX_ZOOM) {
                                map.flyTo(point, expansionZoom)
                                return
                            }

                            openMapPanel({
                                bbox: bboxOfCells(
                                    index.getLeaves(clusterId, Infinity),
                                    cellDegrees,
                                    centerLng,
                                ),
                            })
                            return
                        }

                        // A cell of several points splits into finer cells at a deeper zoom, until the
                        // deepest, where only the panel can show what is there
                        if (zoom < MAX_ZOOM) {
                            map.flyTo(point, Math.min(zoom + 2, MAX_ZOOM))
                            return
                        }

                        openMapPanel({
                            bbox: bboxOfCells(
                                [feature],
                                cellDegrees,
                                centerLng,
                            ),
                        })
                    },
                })
            })

        locations.forEach((location) => {
            addLocationPin({
                point: [
                    location.latitude,
                    nearestCopyOf(location.longitude, centerLng),
                ],
                location,
            })
        })
    }

    /** A thumbnail pin; without `onClick` it is a single asset, whose click opens its detail */
    function addAssetPin({ point, assetId, count, onClick }) {
        const iconEl = document.createElement("div")
        const imgEl = document.createElement("img")
        imgEl.src = `/content/r/${repoId}/preview/${assetId}`
        imgEl.alt = ""
        imgEl.draggable = false
        iconEl.append(imgEl)

        if (count > 1) {
            const badgeEl = document.createElement("div")
            badgeEl.className = "drag-count-badge"
            badgeEl.textContent = String(count)
            iconEl.append(badgeEl)
        } else {
            imgEl.setAttribute(
                "hx-get",
                `/htmx/asset/r/${repoId}/modals/asset-detail/${assetId}`,
            )
            imgEl.setAttribute("hx-target", "#imageDetailModalContent")
            imgEl.setAttribute("hx-trigger", "click")
        }

        const marker = L.marker(point, {
            icon: L.divIcon({
                className: "asset-pin",
                html: iconEl,
                iconSize: [PIN_SIZE_PX, PIN_SIZE_PX],
                iconAnchor: [PIN_SIZE_PX / 2, PIN_SIZE_PX / 2],
            }),
            // The count is what a screen reader gets; the thumbnail stands for the area
            title: count > 1 ? `${count} items` : "",
        }).addTo(pinsLayer)

        if (onClick) {
            marker.on("click", onClick)
        } else {
            // The icon's markup is in the DOM only now
            htmx.process(marker.getElement())
        }
    }

    /** The Location's glyph and path (`Category › Name`); a click scopes the results to the Location */
    function addLocationPin({ point, location }) {
        const iconEl = document.createElement("div")
        const glyphEl = document.createElement("i")
        glyphEl.className = "fas fa-map-marker-alt"
        glyphEl.setAttribute("aria-hidden", "true")
        const pathEl = document.createElement("span")
        pathEl.className = "path"

        if (location.categoryName) {
            const categoryEl = document.createElement("span")
            categoryEl.className = "category"
            categoryEl.textContent = `${location.categoryName} › `
            pathEl.append(categoryEl)
        }
        pathEl.append(location.name)
        iconEl.append(glyphEl, pathEl)

        L.marker(point, {
            icon: L.divIcon({
                className: "location-pin on-map",
                html: iconEl,
                iconSize: [28, 28],
                // The glyph's tip is its bottom centre
                iconAnchor: [14, 28],
            }),
            title: `${location.count} ${location.count === 1 ? "item" : "items"}`,
        })
            .addTo(pinsLayer)
            .on("click", () => {
                runSearch({
                    params: { locationId: location.id },
                })
            })
    }

    const remembered = rememberedMapView(fingerprint)
    const bounds = parseBounds(fragmentEl.dataset.mapBounds)

    if (remembered) {
        map.setView(remembered.center, remembered.zoom)
    } else if (bounds) {
        map.fitBounds(bounds, {
            padding: [FIT_PADDING_PX, FIT_PADDING_PX],
            maxZoom: FIT_MAX_ZOOM,
        })
    } else {
        map.setView(WORLD_VIEW.center, WORLD_VIEW.zoom)
    }

    map.on("moveend", () => {
        const view = currentView()
        rememberMapView(fingerprint, view)
        setDisplayedMapView(view)
        scheduleCells()
    })

    // The results column resizes with the explorer split and the window; Leaflet has to be told
    const resizeObserver = new ResizeObserver(() => map.invalidateSize())
    resizeObserver.observe(fragmentEl)

    current = {
        map,
        resizeObserver,
        cancelCells: () => clearTimeout(cellsTimer),
    }
    setDisplayedMapView(currentView())
    scheduleCells()

    // A bookmarked or re-rendered map search with a map area reopens the panel for it
    const bbox = fragmentEl.closest('[data-app-fragment="search-results"]')
        ?.dataset.resultsBbox
    if (bbox) {
        openMapPanel({ bbox })
    }
}

/**
 * Releases the map on screen, if any: its listeners, its pending request, and its registered view.
 * The results fragment hydrator calls it for every new results fragment, whether or not that one
 * holds a map. A removal that throws (the container may be gone already) is logged, not raised.
 */
export function disposeMapView() {
    if (!current) {
        return
    }

    const previous = current
    current = null
    previous.cancelCells()
    previous.resizeObserver.disconnect()
    setDisplayedMapView(null)

    try {
        previous.map.remove()
    } catch (error) {
        console.warn("Could not dispose of the previous map", error)
    }
}

function cellFeature(cell) {
    return {
        type: "Feature",
        geometry: {
            type: "Point",
            coordinates: [cell.longitude, cell.latitude],
        },
        properties: { count: cell.count, assetId: cell.assetId },
    }
}

/** `[[south, west], [north, east]]` from the fragment's `s,w,n,e`, or null when nothing is plotted */
function parseBounds(value) {
    if (!value) {
        return null
    }

    const [south, west, north, east] = value.split(",").map(Number)

    return [
        [south, west],
        [north, east],
    ]
}

/**
 * The map's viewport in the server's form, `s,w,n,e`: latitudes clamped to the poles, longitudes
 * wrapped into -180..180 with `west > east` across the antimeridian, and the whole world once the
 * view spans it.
 */
function viewportOf(map) {
    const bounds = map.getBounds()
    const south = Math.max(-90, bounds.getSouth())
    const north = Math.min(90, bounds.getNorth())
    let west = bounds.getWest()
    let east = bounds.getEast()

    if (east - west >= 360) {
        west = -180
        east = 180
    } else {
        west = wrapLongitude(west)
        east = wrapLongitude(east)
    }

    return [south, west, north, east]
        .map((value) => value.toFixed(COORDINATE_DECIMALS))
        .join(",")
}

function wrapLongitude(longitude) {
    return L.Util.wrapNum(longitude, [-180, 180], true)
}

/** The size of a server cell at `zoom` (`SearchService.cellDegrees`: a quarter tile) */
function cellDegreesAt(zoom) {
    return 360 / 2 ** Math.max(0, Math.min(20, zoom)) / 4
}

/**
 * The copy of `longitude` nearest the map's centre. Leaflet draws a marker in one world copy only,
 * so a view straddling the antimeridian would otherwise miss the pins on the other side of it.
 */
function nearestCopyOf(longitude, centerLongitude) {
    let nearest = longitude

    while (nearest - centerLongitude > 180) {
        nearest -= 360
    }
    while (nearest - centerLongitude < -180) {
        nearest += 360
    }

    return nearest
}

/**
 * The `bbox` search filter covering `cells`, `s,w,n,e`: the union of the cells' own extents (a
 * centroid lies in its cell, which is `cellDegrees` square on a grid from the origin), since the
 * assets of a cell can be anywhere in it. Longitudes are taken in the world copy nearest the
 * centre, so cells on both sides of the antimeridian make a box that crosses it (`west > east`),
 * as the server reads it.
 */
function bboxOfCells(cells, cellDegrees, centerLongitude) {
    let south = Infinity
    let north = -Infinity
    let west = Infinity
    let east = -Infinity

    cells.forEach((feature) => {
        const [lng, lat] = feature.geometry.coordinates
        const latIndex = Math.floor(lat / cellDegrees)
        const lngIndex = Math.floor(
            nearestCopyOf(lng, centerLongitude) / cellDegrees,
        )

        south = Math.min(south, latIndex * cellDegrees)
        north = Math.max(north, (latIndex + 1) * cellDegrees)
        west = Math.min(west, lngIndex * cellDegrees)
        east = Math.max(east, (lngIndex + 1) * cellDegrees)
    })

    if (east - west >= 360) {
        west = -180
        east = 180
    } else {
        west = wrapLongitude(west)
        east = wrapLongitude(east)
    }

    return [Math.max(-90, south), west, Math.min(90, north), east]
        .map((value) => value.toFixed(COORDINATE_DECIMALS))
        .join(",")
}
