/**
 * The content URLs the client builds (`altitude/static/js/common/content-urls.js`), the ones the
 * server's `Util.previewUrl` and content route answer, and the repointing of a grid cell's image at
 * a new Preview after a render, on a stand-in for the image element. Run by `make test-js`.
 */
import { test } from "node:test"
import assert from "node:assert/strict"

import {
    displayUrl,
    originalUrl,
    previewUrl,
    repointImage,
} from "../../altitude/static/js/common/content-urls.js"

const repo = "5009277e-6c3d-4103-8b19-c41ddb8f22ad"
const asset = "323689d6-9cc2-46f8-8e44-c518bf96129c"

test("a Preview is the edited one by its revision above 0, and the original's at 0 or with none", () => {
    assert.equal(
        previewUrl(repo, asset, 3),
        `/content/r/${repo}/preview/${asset}?v=3`,
    )
    assert.equal(
        previewUrl(repo, asset, 0),
        `/content/r/${repo}/preview/${asset}`,
    )
    assert.equal(previewUrl(repo, asset), `/content/r/${repo}/preview/${asset}`)
    assert.equal(
        previewUrl(repo, asset, undefined),
        `/content/r/${repo}/preview/${asset}`,
    )
})

test("the Display image is named by its revision, the original by nothing", () => {
    assert.equal(
        displayUrl(repo, asset, 2),
        `/content/r/${repo}/display/${asset}?v=2`,
    )
    assert.equal(originalUrl(repo, asset), `/content/r/${repo}/file/${asset}`)
})

/** A stand-in for a cell's `<img>`: its `src` attribute, its `data-src` and the `src` property's writes */
function cellImage({ src, dataSrc }) {
    const attributes = new Map(src === null ? [] : [["src", src]])
    return {
        dataset: { src: dataSrc },
        getAttribute: (name) => attributes.get(name) ?? null,
        get src() {
            return attributes.get("src")
        },
        set src(value) {
            attributes.set("src", value)
        },
    }
}

test("a shown image is repointed in both its src and data-src", () => {
    const old = previewUrl(repo, asset)
    const img = cellImage({ src: old, dataSrc: old })
    assert.equal(repointImage(img, previewUrl(repo, asset, 1)), true)
    assert.equal(img.dataset.src, previewUrl(repo, asset, 1))
    assert.equal(img.getAttribute("src"), previewUrl(repo, asset, 1))
})

test("a released image keeps its placeholder and is repointed in data-src alone, which the lazy loader reads", () => {
    const placeholder = "data:image/gif;base64,R0lGOD"
    const img = cellImage({
        src: placeholder,
        dataSrc: previewUrl(repo, asset),
    })
    assert.equal(repointImage(img, previewUrl(repo, asset, 1)), true)
    assert.equal(img.dataset.src, previewUrl(repo, asset, 1))
    assert.equal(img.getAttribute("src"), placeholder)
})

test("an image not loaded yet (no src) is repointed in data-src alone, and one already pointed is left alone", () => {
    const img = cellImage({ src: null, dataSrc: previewUrl(repo, asset) })
    assert.equal(repointImage(img, previewUrl(repo, asset, 1)), true)
    assert.equal(img.getAttribute("src"), null)
    assert.equal(repointImage(img, previewUrl(repo, asset, 1)), false)
})
