/**
 * The Edit's WebGL2 renderer: draws an Edit (`CONTEXT.md`) from the upright original into a canvas
 * with the math of the server's renderer (`util/EditRenderer.scala`, the spec in
 * `docs/image-edits.md`), so the Asset detail stage can show an Edit whose
 * render is pending, and the Edit panel every change at once.
 *
 * The passes, each a full-canvas triangle over an output of `width` × `height` pixels:
 * 1. **Develop:** each output pixel's frame point (the crop's, pixel centers at +0.5) is warped back
 *    (`geometry.createWarp`'s inverse) and turned back into the upright original
 *    (`geometry.frameToUpright`), which is sampled from a mipmapped texture with its edges repeated
 *    (CLAMP_TO_EDGE, the server's BORDER_REPLICATE). Its channels go through the tables of steps 1 to
 *    6 (`tone.tableTexels`, the 4096 entries as a 64 × 64 RGBA32F texture, read by two texel fetches
 *    and a mix, so no float filtering is needed), then step 7. A pixel whose source lies outside the frame, or beyond
 *    the horizon, is transparent: a hard edge, as the server's.
 * 2. **Blur:** Clarity's and Sharpen's Gaussian blurs of the luminance L, separable, a horizontal and
 *    a vertical pass computing both at once, with OpenCV's kernels (`tone.gaussianHalfKernel`) and
 *    its default border (BORDER_REFLECT_101). Skipped when both are 0.
 * 3. **Finish:** Clarity's and Sharpen's deltas, the vignette and the grain on integer columns and
 *    rows from the top left, the clamp, into an 8-bit output texture. Drawing the whole frame (the
 *    Crop tool), the effects are sized from the crop in it and laid over it (`effectRegion`), so
 *    they do not jump on Done.
 * 4. **Present:** the output texture onto the canvas, its rows turned bottom up.
 *
 * The intermediate textures hold rows top down (row j at `gl_FragCoord.y` = j), so pixel (i, j) is
 * the spec's (i, j) everywhere but on the canvas, whose rows run bottom up. The canvas has straight
 * (not premultiplied) alpha, and keeps its drawing buffer, so the Clipping blink and a test can read
 * it at any time.
 *
 * The source is the upright original as the browser decodes it (`createImageBitmap` with
 * `imageOrientation: "from-image"` and `colorSpaceConversion: "none"`, uploaded with no color
 * conversion), fitted to the long edge its caller asks for, at most `MAX_TEXTURE_SIZE`; it is kept,
 * so a lost context is rebuilt on `webglcontextrestored` and the last render drawn again.
 *
 * `render` draws the Edit; `renderBefore` draws its geometry alone (the split view's Before), and
 * `render` with `split` draws Before left of that fraction of the width and After right of it.
 * `readHistogram` reads the last render, as drawn, at 256 px on its long edge: its crop alone when
 * the whole frame was drawn.
 */
import {
    cropOf,
    createWarp,
    effectRegion,
    frameSize,
    frameToUpright,
    WHOLE_FRAME,
} from "./geometry.js"
import {
    claritySigma,
    gaussianHalfKernel,
    grainCell,
    grainScale,
    LUMA,
    sharpenSigma,
    TABLE_SIZE,
    tableTexels,
} from "./tone.js"

// The long edge `readHistogram` reads the render at, and its bins per channel (`Histogram.BINS`)
const HISTOGRAM_LONG_EDGE = 256
const HISTOGRAM_BINS = 64

// The tables' texture lays the 4096 entries out in rows of 64 (64 × 64): WebGL2 promises textures of 2048 a side, not 4096
const TABLE_COLUMNS = 64

const VERTEX = `#version 300 es
void main() {
    vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}`

const HEADER = `#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
const vec3 LUMA = vec3(${LUMA.join(", ")});
out vec4 outColor;
`

// Steps 1 to 7 on the warped and cropped frame; with uTone false, the geometry alone (Before)
const DEVELOP = `${HEADER}
uniform sampler2D uSource;
uniform sampler2D uTables;
uniform vec2 uFrame;
uniform vec4 uCrop;
uniform vec2 uOut;
// cos θ, sin θ and the keystones a and b; uHalf is half the frame's long side
uniform vec4 uWarp;
uniform float uHalf;
uniform bool uMoves;
uniform mat3 uToUpright;
uniform bool uTone;
uniform bool uSaturates;
uniform vec2 uSaturation;
uniform bool uFlip;

// Entry i of a channel's table, in rows of ${TABLE_COLUMNS}
float tableEntry(int i, int channel) {
    return texelFetch(uTables, ivec2(i % ${TABLE_COLUMNS}, i / ${TABLE_COLUMNS}), 0)[channel];
}

float lookupTable(float v, int channel) {
    float f = clamp(v, 0.0, 1.0) * ${TABLE_SIZE - 1}.0;
    int i = int(floor(f));
    if (i >= ${TABLE_SIZE - 1}) {
        return tableEntry(${TABLE_SIZE - 1}, channel);
    }
    float a = tableEntry(i, channel);
    float b = tableEntry(i + 1, channel);
    return a + (b - a) * (f - float(i));
}

void main() {
    float i = floor(gl_FragCoord.x);
    float j = uFlip ? uOut.y - 1.0 - floor(gl_FragCoord.y) : floor(gl_FragCoord.y);
    vec2 point = uCrop.xy * uFrame + (vec2(i, j) + 0.5) * uCrop.zw * uFrame / uOut;

    vec2 source = point;
    bool covered = true;
    if (uMoves) {
        vec2 center = uFrame * 0.5;
        vec2 q = (point - center) / uHalf;
        vec2 u = vec2(uWarp.x * q.x + uWarp.y * q.y, -uWarp.y * q.x + uWarp.x * q.y);
        float w = 1.0 - uWarp.z * u.x - uWarp.w * u.y;
        // Past the horizon (w <= 0.001) the pixel is not covered: its color, sampled at the projected point as OpenCV's
        // warpPerspective samples it, never shows; w is only held away from 0, so the division stays finite
        float divisor = abs(w) < 1e-6 ? 1e-6 : w;
        source = u / divisor * uHalf + center;
        covered = w > ${0.001} && all(greaterThanEqual(source, vec2(0.0))) && all(lessThanEqual(source, uFrame));
    }

    vec4 color = texture(uSource, (uToUpright * vec3(source, 1.0)).xy);
    vec3 rgb = color.rgb;
    if (uTone) {
        rgb = vec3(lookupTable(rgb.r, 0), lookupTable(rgb.g, 1), lookupTable(rgb.b, 2));
        if (uSaturates) {
            float y = dot(rgb, LUMA);
            float chroma = max(rgb.r, max(rgb.g, rgb.b)) - min(rgb.r, min(rgb.g, rgb.b));
            float vib = uSaturation.y;
            float k = (1.0 + uSaturation.x) * (1.0 + vib * (vib > 0.0 ? 1.0 - min(1.0, 1.6 * chroma) : 1.0));
            rgb = y + (rgb - y) * k;
        }
    }
    outColor = vec4(rgb, covered ? color.a : 0.0);
}`

// One direction of Clarity's (x) and Sharpen's (y) blurs of L: from the developed colors, or from the other direction's result
const BLUR = `${HEADER}
uniform sampler2D uImage;
uniform sampler2D uKernels;
uniform ivec2 uRadius;
uniform ivec2 uSize;
uniform bool uHorizontal;

// OpenCV's BORDER_REFLECT_101: -1 is 1, n is n - 2
int mirrored(int k, int n) {
    if (n == 1) {
        return 0;
    }
    int period = 2 * (n - 1);
    int m = abs(k) % period;
    return m < n ? m : period - m;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int n = uHorizontal ? uSize.x : uSize.y;
    int at = uHorizontal ? p.x : p.y;
    int radius = max(uRadius.x, uRadius.y);
    vec2 sum = vec2(0.0);
    for (int t = -radius; t <= radius; t++) {
        int k = mirrored(at + t, n);
        vec4 texel = texelFetch(uImage, uHorizontal ? ivec2(k, p.y) : ivec2(p.x, k), 0);
        vec2 value = uHorizontal ? vec2(dot(texel.rgb, LUMA)) : texel.rg;
        int a = abs(t);
        float clarity = a <= uRadius.x ? texelFetch(uKernels, ivec2(a, 0), 0).r : 0.0;
        float sharpen = a <= uRadius.y ? texelFetch(uKernels, ivec2(a, 1), 0).r : 0.0;
        sum += vec2(clarity, sharpen) * value;
    }
    outColor = vec4(sum, 0.0, 1.0);
}`

// Steps 8 to 11
const FINISH = `${HEADER}
uniform sampler2D uImage;
uniform sampler2D uBlur;
uniform bool uBlurs;
uniform vec2 uDetail;
uniform float uVignette;
uniform float uGrain;
uniform float uCell;
// The region the vignette and the grain are laid over: its top left and its size, in output pixels
uniform vec4 uEffect;

float hash(int x, int y) {
    uint h = uint(x) * 374761393u + uint(y) * 668265263u;
    h = (h ^ (h >> 13u)) * 1274126177u;
    h = h ^ (h >> 16u);
    return float(h) / 4294967295.0;
}

float grainNoise(float x, float y) {
    vec2 g = vec2(x, y) / uCell;
    vec2 g0 = floor(g);
    vec2 f = g - g0;
    int x0 = int(g0.x);
    int y0 = int(g0.y);
    return (hash(x0, y0) * (1.0 - f.x) + hash(x0 + 1, y0) * f.x) * (1.0 - f.y)
        + (hash(x0, y0 + 1) * (1.0 - f.x) + hash(x0 + 1, y0 + 1) * f.x) * f.y - 0.5;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 color = texelFetch(uImage, p, 0);
    vec3 rgb = color.rgb;
    float l = dot(rgb, LUMA);

    if (uBlurs) {
        vec2 blurred = texelFetch(uBlur, p, 0).rg;
        float m = 2.0 * l - 1.0;
        rgb += (l - blurred.x) * uDetail.x * (1.0 - 0.7 * m * m) + (l - blurred.y) * uDetail.y;
    }

    vec2 xy = vec2(p) - uEffect.xy;
    if (uVignette != 0.0) {
        vec2 d = (xy - uEffect.zw * 0.5) / (uEffect.zw * 0.5);
        float f = uVignette * smoothstep(0.0, 1.0, (sqrt(dot(d, d) / 2.0) - 0.39375) / 0.65625);
        rgb = uVignette < 0.0 ? rgb * (1.0 + f) : rgb + (1.0 - rgb) * f;
    }

    if (uGrain != 0.0) {
        rgb += grainNoise(xy.x, xy.y) * uGrain * (1.0 - 0.5 * abs(2.0 * l - 1.0));
    }

    outColor = vec4(clamp(rgb, 0.0, 1.0), color.a);
}`

// A texture sampled at the nearest texel onto a target: the output onto the canvas (rows turned), or onto the histogram's
const COPY = `${HEADER}
uniform sampler2D uImage;
uniform vec2 uOffset;
uniform vec2 uScale;
uniform bool uFlip;
uniform float uHeight;

void main() {
    vec2 q = floor(gl_FragCoord.xy);
    if (uFlip) {
        q.y = uHeight - 1.0 - q.y;
    }
    outColor = texelFetch(uImage, ivec2(uOffset + (q + 0.5) * uScale), 0);
}`

// Whether the browser can run the renderer, once asked
let supported = null

/** Whether the browser can run the renderer: WebGL2. The probe's context is let go at once, and the answer kept. */
export function isRendererSupported() {
    if (supported === null) {
        try {
            const probe = document.createElement("canvas").getContext("webgl2")
            supported = Boolean(probe)
            probe?.getExtension("WEBGL_lose_context")?.loseContext()
        } catch {
            supported = false
        }
    }
    return supported
}

/**
 * Decodes the original's bytes upright, as the renderer and the server read them: turned by its
 * EXIF orientation, with no color space conversion and no premultiplied alpha
 */
export function decodeUpright(blob) {
    return createImageBitmap(blob, {
        imageOrientation: "from-image",
        colorSpaceConversion: "none",
        premultiplyAlpha: "none",
    })
}

/**
 * A renderer drawing into `canvas`. `onRestored` is called once a lost context has been rebuilt
 * and the last render drawn again. Throws when the browser has no WebGL2.
 */
export function createRenderer(canvas, { onRestored = null } = {}) {
    let gl = null
    let resources = null
    let lost = false
    let destroyed = false

    // The upright original as given (its caller's), and as fitted into the source texture: the
    // original itself, or a smaller bitmap this renderer made and closes
    let original = null
    let fitted = null
    let fittedLongEdge = 0
    // The last draw, drawn again on a restored context, and read by `readHistogram`, and whether it
    // waits for a source to be drawn
    let last = null
    let undrawn = false
    // The largest texture side the context takes, kept while it is lost
    let maxTexture = Infinity

    const onLost = (event) => {
        event.preventDefault()
        lost = true
        resources = null
        console.warn("The Edit's WebGL context was lost")
    }
    const onRestoredEvent = () => {
        lost = false
        resources = build()
        if (fitted) {
            uploadSource(fitted)
        }
        if (last) {
            draw(last)
        }
        console.info("The Edit's WebGL context was restored")
        onRestored?.()
    }
    canvas.addEventListener("webglcontextlost", onLost)
    canvas.addEventListener("webglcontextrestored", onRestoredEvent)

    gl = canvas.getContext("webgl2", {
        alpha: true,
        premultipliedAlpha: false,
        preserveDrawingBuffer: true,
        antialias: false,
        depth: false,
        stencil: false,
    })
    if (!gl) {
        throw new Error("WebGL2 is not available")
    }
    resources = build()

    /** Programs, the vertex array, the fixed textures, and the targets made as sizes ask */
    function build() {
        maxTexture = gl.getParameter(gl.MAX_TEXTURE_SIZE)
        // Half-float color targets keep the steps' values between passes (step 7 above 1, L unquantized); 8 bits where the
        // context cannot draw into them
        const floatTargets = Boolean(
            gl.getExtension("EXT_color_buffer_float") ||
            gl.getExtension("EXT_color_buffer_half_float"),
        )
        return {
            floatTargets,
            vertexArray: gl.createVertexArray(),
            develop: program(DEVELOP),
            blur: program(BLUR),
            finish: program(FINISH),
            copy: program(COPY),
            source: texture({ mipmapped: true }),
            tables: texture(),
            kernels: texture(),
            targets: new Map(),
        }
    }

    function program(fragmentSource) {
        const compile = (type, text) => {
            const shader = gl.createShader(type)
            gl.shaderSource(shader, text)
            gl.compileShader(shader)
            if (
                !gl.getShaderParameter(shader, gl.COMPILE_STATUS) &&
                !gl.isContextLost()
            ) {
                throw new Error(
                    `The Edit's shader did not compile: ${gl.getShaderInfoLog(shader)}`,
                )
            }
            return shader
        }
        const prog = gl.createProgram()
        gl.attachShader(prog, compile(gl.VERTEX_SHADER, VERTEX))
        gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, fragmentSource))
        gl.linkProgram(prog)
        if (
            !gl.getProgramParameter(prog, gl.LINK_STATUS) &&
            !gl.isContextLost()
        ) {
            throw new Error(
                `The Edit's program did not link: ${gl.getProgramInfoLog(prog)}`,
            )
        }
        const locations = new Map()
        return {
            prog,
            at(name) {
                if (!locations.has(name)) {
                    locations.set(name, gl.getUniformLocation(prog, name))
                }
                return locations.get(name)
            },
        }
    }

    function texture({ mipmapped = false } = {}) {
        const tex = gl.createTexture()
        gl.bindTexture(gl.TEXTURE_2D, tex)
        gl.texParameteri(
            gl.TEXTURE_2D,
            gl.TEXTURE_MIN_FILTER,
            mipmapped ? gl.LINEAR_MIPMAP_LINEAR : gl.NEAREST,
        )
        gl.texParameteri(
            gl.TEXTURE_2D,
            gl.TEXTURE_MAG_FILTER,
            mipmapped ? gl.LINEAR : gl.NEAREST,
        )
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
        return tex
    }

    /**
     * A render target of the kind (`color`: the developed colors, `blur` and `blurDown`: the two
     * blurs across and down, `output`: the finished 8-bit image, `histogram`) and size, made once
     * and remade when the size changes. A half-float target the context cannot draw into (an
     * incomplete framebuffer) turns the renderer to 8-bit targets for good.
     */
    function target(kind, width, height) {
        const known = resources.targets.get(kind)
        if (known && known.width === width && known.height === height) {
            return known
        }
        if (known) {
            gl.deleteTexture(known.tex)
            gl.deleteFramebuffer(known.fbo)
        }
        const float = resources.floatTargets
        const formats = {
            color: float
                ? [gl.RGBA16F, gl.RGBA, gl.HALF_FLOAT]
                : [gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE],
            blur: float
                ? [gl.RG16F, gl.RG, gl.HALF_FLOAT]
                : [gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE],
            output: [gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE],
            histogram: [gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE],
        }
        // The vertical blur's target is the horizontal one's kind
        const [internal, format, type] = formats[kind] ?? formats.blur
        const tex = texture()
        gl.texImage2D(
            gl.TEXTURE_2D,
            0,
            internal,
            width,
            height,
            0,
            format,
            type,
            null,
        )
        const fbo = gl.createFramebuffer()
        gl.bindFramebuffer(gl.FRAMEBUFFER, fbo)
        gl.framebufferTexture2D(
            gl.FRAMEBUFFER,
            gl.COLOR_ATTACHMENT0,
            gl.TEXTURE_2D,
            tex,
            0,
        )
        const half = type === gl.HALF_FLOAT
        if (
            half &&
            gl.checkFramebufferStatus(gl.FRAMEBUFFER) !==
                gl.FRAMEBUFFER_COMPLETE
        ) {
            console.warn(
                "The Edit's renderer cannot draw into half-float textures; it uses 8 bits",
            )
            gl.deleteTexture(tex)
            gl.deleteFramebuffer(fbo)
            resources.floatTargets = false
            return target(kind, width, height)
        }
        const made = { tex, fbo, width, height }
        resources.targets.set(kind, made)
        return made
    }

    function uploadSource(image) {
        gl.bindTexture(gl.TEXTURE_2D, resources.source)
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false)
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false)
        gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL, gl.NONE)
        gl.texImage2D(
            gl.TEXTURE_2D,
            0,
            gl.RGBA8,
            gl.RGBA,
            gl.UNSIGNED_BYTE,
            image,
        )
        gl.generateMipmap(gl.TEXTURE_2D)
    }

    /**
     * The upright original to draw from, fitted to `longEdge` on its long edge (its own size when
     * that is larger, or none is given) and at most the largest texture: the long edge the stage's
     * device pixels need at the crop. A later call with a long edge the fitted source already
     * covers keeps it; the mipmaps serve any smaller render.
     */
    async function setSource(image, { longEdge = null } = {}) {
        const uprightLong = Math.max(image.width, image.height)
        const wanted = Math.min(
            uprightLong,
            longEdge ?? uprightLong,
            maxTexture,
        )
        if (image === original && fitted && wanted <= fittedLongEdge) {
            return
        }

        if (image !== original) {
            releaseFitted()
            fitted = null
        }
        original = image
        const scale = wanted / uprightLong
        let next = image
        if (scale < 1) {
            next = await createImageBitmap(image, {
                resizeWidth: Math.max(1, Math.round(image.width * scale)),
                resizeHeight: Math.max(1, Math.round(image.height * scale)),
                resizeQuality: "high",
                colorSpaceConversion: "none",
                premultiplyAlpha: "none",
            })
        }
        if (destroyed || original !== image) {
            if (next !== image) {
                next.close()
            }
            return
        }
        releaseFitted()
        fitted = next
        fittedLongEdge = wanted
        if (!lost) {
            uploadSource(fitted)
            // A render asked for before the source was in is drawn now
            if (undrawn && last) {
                draw(last)
            }
        }
    }

    /** Closes the fitted source when this renderer made it */
    function releaseFitted() {
        if (fitted && fitted !== original) {
            fitted.close?.()
        }
    }

    /** The upright original's size, W₀ × H₀, of the source last set */
    function uprightSize() {
        return original
            ? { width: original.width, height: original.height }
            : null
    }

    function useProgram(p) {
        gl.useProgram(p.prog)
        gl.bindVertexArray(resources.vertexArray)
        return p
    }

    function bindTexture(unit, tex) {
        gl.activeTexture(gl.TEXTURE0 + unit)
        gl.bindTexture(gl.TEXTURE_2D, tex)
    }

    /**
     * Runs the develop program into the bound target: the Edit's geometry and, with `toned`, its
     * steps 1 to 7; `flip` for the canvas, whose rows run bottom up
     */
    function develop(recipe, { width, height, wholeFrame, toned, flip }) {
        const upright = uprightSize()
        const frame = frameSize(upright.width, upright.height, recipe)
        const crop = wholeFrame ? WHOLE_FRAME : cropOf(recipe)
        const warpOf = createWarp(frame.width, frame.height, recipe)
        const [a, b, c, d, e, f] = frameToUpright(
            upright.width,
            upright.height,
            recipe,
        )

        const p = useProgram(resources.develop)
        bindTexture(0, resources.source)
        gl.uniform1i(p.at("uSource"), 0)
        bindTexture(1, resources.tables)
        gl.uniform1i(p.at("uTables"), 1)
        gl.uniform2f(p.at("uFrame"), frame.width, frame.height)
        gl.uniform4f(p.at("uCrop"), crop.x, crop.y, crop.w, crop.h)
        gl.uniform2f(p.at("uOut"), width, height)
        gl.uniform4f(
            p.at("uWarp"),
            warpOf.cos,
            warpOf.sin,
            warpOf.keystones.a,
            warpOf.keystones.b,
        )
        gl.uniform1f(p.at("uHalf"), warpOf.halfLong)
        gl.uniform1i(p.at("uMoves"), warpOf.moves ? 1 : 0)
        // Column-major: the columns of the affine map
        gl.uniformMatrix3fv(p.at("uToUpright"), false, [
            a,
            d,
            0,
            b,
            e,
            0,
            c,
            f,
            1,
        ])
        gl.uniform1i(p.at("uTone"), toned ? 1 : 0)
        gl.uniform1i(
            p.at("uSaturates"),
            recipe.saturation !== 0 || recipe.vibrance !== 0 ? 1 : 0,
        )
        gl.uniform2f(
            p.at("uSaturation"),
            recipe.saturation / 100,
            recipe.vibrance / 100,
        )
        gl.uniform1i(p.at("uFlip"), flip ? 1 : 0)
        gl.viewport(0, 0, width, height)
        gl.drawArrays(gl.TRIANGLES, 0, 3)
    }

    function uploadTables(recipe) {
        bindTexture(1, resources.tables)
        gl.texImage2D(
            gl.TEXTURE_2D,
            0,
            gl.RGBA32F,
            TABLE_COLUMNS,
            TABLE_SIZE / TABLE_COLUMNS,
            0,
            gl.RGBA,
            gl.FLOAT,
            tableTexels(recipe),
        )
    }

    /** The two half kernels, Clarity's in row 0 and Sharpen's in row 1, and their radii (−1 for a blur not asked) */
    function uploadKernels(recipe, width, height) {
        const clarity =
            recipe.clarity !== 0
                ? gaussianHalfKernel(claritySigma(width, height))
                : null
        const sharpen =
            recipe.sharpen !== 0
                ? gaussianHalfKernel(sharpenSigma(width, height))
                : null
        const length = Math.max(clarity?.length ?? 1, sharpen?.length ?? 1)
        const texels = new Float32Array(length * 2)
        clarity && texels.set(clarity, 0)
        sharpen && texels.set(sharpen, length)
        bindTexture(2, resources.kernels)
        gl.texImage2D(
            gl.TEXTURE_2D,
            0,
            gl.R32F,
            length,
            2,
            0,
            gl.RED,
            gl.FLOAT,
            texels,
        )
        return [(clarity?.length ?? 0) - 1, (sharpen?.length ?? 0) - 1]
    }

    /**
     * Draws the Edit into the output texture: develop, the blurs when asked, finish, the effects sized from and laid over
     * `effectRegion`
     */
    function developed(recipe, { width, height, wholeFrame }) {
        const region = effectRegion(recipe, { width, height, wholeFrame })
        uploadTables(recipe)
        const color = target("color", width, height)
        gl.bindFramebuffer(gl.FRAMEBUFFER, color.fbo)
        develop(recipe, { width, height, wholeFrame, toned: true, flip: false })

        const blurs = recipe.clarity !== 0 || recipe.sharpen !== 0
        let blurred = null
        if (blurs) {
            const radius = uploadKernels(recipe, region.width, region.height)
            const across = target("blur", width, height)
            const passes = [
                { from: color.tex, into: across, horizontal: true },
                {
                    from: across.tex,
                    into: target("blurDown", width, height),
                    horizontal: false,
                },
            ]
            for (const pass of passes) {
                gl.bindFramebuffer(gl.FRAMEBUFFER, pass.into.fbo)
                const p = useProgram(resources.blur)
                bindTexture(0, pass.from)
                gl.uniform1i(p.at("uImage"), 0)
                bindTexture(2, resources.kernels)
                gl.uniform1i(p.at("uKernels"), 2)
                gl.uniform2i(p.at("uRadius"), radius[0], radius[1])
                gl.uniform2i(p.at("uSize"), width, height)
                gl.uniform1i(p.at("uHorizontal"), pass.horizontal ? 1 : 0)
                gl.viewport(0, 0, width, height)
                gl.drawArrays(gl.TRIANGLES, 0, 3)
            }
            blurred = passes[1].into
        }

        const output = target("output", width, height)
        gl.bindFramebuffer(gl.FRAMEBUFFER, output.fbo)
        const p = useProgram(resources.finish)
        bindTexture(0, color.tex)
        gl.uniform1i(p.at("uImage"), 0)
        bindTexture(3, blurred?.tex ?? color.tex)
        gl.uniform1i(p.at("uBlur"), 3)
        gl.uniform1i(p.at("uBlurs"), blurs ? 1 : 0)
        gl.uniform2f(
            p.at("uDetail"),
            (recipe.clarity / 100) * 1.4,
            (recipe.sharpen / 100) * 2.2,
        )
        gl.uniform1f(p.at("uVignette"), recipe.vignette / 100)
        gl.uniform1f(
            p.at("uGrain"),
            (recipe.grain / 100) *
                0.22 *
                grainScale(region.width, region.height),
        )
        gl.uniform1f(p.at("uCell"), grainCell(region.width, region.height))
        gl.uniform4f(
            p.at("uEffect"),
            region.x,
            region.y,
            region.width,
            region.height,
        )
        gl.viewport(0, 0, width, height)
        gl.drawArrays(gl.TRIANGLES, 0, 3)
        return output
    }

    /**
     * Copies `from`, or its `region` (`{ x, y, width, height }` in its texels), onto the bound target of `width` ×
     * `height`, sampled at the nearest texel, rows turned for the canvas
     */
    function copy(from, { width, height, flip, region = null }) {
        const area = region ?? {
            x: 0,
            y: 0,
            width: from.width,
            height: from.height,
        }
        const p = useProgram(resources.copy)
        bindTexture(0, from.tex)
        gl.uniform1i(p.at("uImage"), 0)
        gl.uniform2f(p.at("uOffset"), area.x, area.y)
        gl.uniform2f(p.at("uScale"), area.width / width, area.height / height)
        gl.uniform1i(p.at("uFlip"), flip ? 1 : 0)
        gl.uniform1f(p.at("uHeight"), height)
        gl.viewport(0, 0, width, height)
        gl.drawArrays(gl.TRIANGLES, 0, 3)
    }

    function sizeCanvas(width, height) {
        if (canvas.width !== width || canvas.height !== height) {
            canvas.width = width
            canvas.height = height
        }
    }

    function draw(call) {
        undrawn = !fitted
        if (lost || destroyed || !fitted) {
            return
        }
        const { recipe, width, height, wholeFrame, before, split } = call
        sizeCanvas(width, height)

        if (before) {
            gl.bindFramebuffer(gl.FRAMEBUFFER, null)
            develop(recipe, {
                width,
                height,
                wholeFrame,
                toned: false,
                flip: true,
            })
            return
        }

        const output = developed(recipe, { width, height, wholeFrame })
        gl.bindFramebuffer(gl.FRAMEBUFFER, null)
        copy(output, { width, height, flip: true })

        if (split !== null) {
            // Before on the left of the divider
            const splitAt = Math.round(Math.min(1, Math.max(0, split)) * width)
            gl.enable(gl.SCISSOR_TEST)
            gl.scissor(0, 0, splitAt, height)
            develop(recipe, {
                width,
                height,
                wholeFrame,
                toned: false,
                flip: true,
            })
            gl.disable(gl.SCISSOR_TEST)
        }
    }

    /** The output size asked for, kept within what the context can draw */
    function bounded(width, height) {
        const scale = Math.min(1, maxTexture / Math.max(width, height))
        return {
            width: Math.max(1, Math.round(width * scale)),
            height: Math.max(1, Math.round(height * scale)),
        }
    }

    /**
     * Draws the Edit into the canvas at `width` × `height`: the crop, or the whole warped frame
     * with `wholeFrame` (the Crop tool); with `split` (0 to 1), Before left of that fraction of
     * the width. Nothing is drawn before a source is set or while the context is lost; a restored
     * context draws the last call again.
     */
    function render(
        recipe,
        { width, height, wholeFrame = false, split = null } = {},
    ) {
        last = {
            recipe,
            ...bounded(width, height),
            wholeFrame,
            before: false,
            split,
        }
        draw(last)
    }

    /** Draws the Edit's geometry alone into the canvas (the split view's Before), as `render` takes its sizes */
    function renderBefore(recipe, { width, height, wholeFrame = false } = {}) {
        last = {
            recipe,
            ...bounded(width, height),
            wholeFrame,
            before: true,
            split: null,
        }
        draw(last)
    }

    /**
     * The Histogram of the last render as drawn, at `HISTOGRAM_LONG_EDGE` px on its long edge, over
     * the pixels the image covers: `{ luminance, red, green, blue }`, each 64 bins of 4 levels as
     * bytes over the tallest bin of the four (the server's `Histogram.relativeBytes`, which
     * Curation's paths read), the luminance the gray the measurement reads (Rec. 601); and
     * `pixels`, how many were counted. Null before a render is drawn or while the context is lost.
     */
    function readHistogram() {
        if (
            lost ||
            destroyed ||
            !last ||
            !fitted ||
            undrawn ||
            (!last.before && !resources.targets.has("output"))
        ) {
            return null
        }
        // The output texture holds the last render's After; a Before is drawn into it for the count
        const source = last.before
            ? beforeOutput()
            : resources.targets.get("output")
        // The crop alone, when the whole frame is drawn (the Crop tool)
        const region = effectRegion(last.recipe, {
            width: source.width,
            height: source.height,
            wholeFrame: last.wholeFrame,
        })
        const scale = Math.min(
            1,
            HISTOGRAM_LONG_EDGE / Math.max(region.width, region.height),
        )
        const width = Math.max(1, Math.round(region.width * scale))
        const height = Math.max(1, Math.round(region.height * scale))
        const small = target("histogram", width, height)
        gl.bindFramebuffer(gl.FRAMEBUFFER, small.fbo)
        copy(source, { width, height, flip: false, region })
        const pixels = new Uint8Array(width * height * 4)
        gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, pixels)
        gl.bindFramebuffer(gl.FRAMEBUFFER, null)
        return binned(pixels)
    }

    /** The Before of the last call drawn into the output texture, for its histogram */
    function beforeOutput() {
        const output = target("output", last.width, last.height)
        gl.bindFramebuffer(gl.FRAMEBUFFER, output.fbo)
        develop(last.recipe, { ...last, toned: false, flip: false })
        return output
    }

    /**
     * Lets the source go (another asset is coming) and clears the canvas, so nothing of the last
     * asset is shown again: nothing is drawn until the next `setSource`
     */
    function clearSource() {
        releaseFitted()
        fitted = null
        fittedLongEdge = 0
        original = null
        last = null
        undrawn = false
        if (!lost && !destroyed) {
            gl.bindFramebuffer(gl.FRAMEBUFFER, null)
            gl.clearColor(0, 0, 0, 0)
            gl.clear(gl.COLOR_BUFFER_BIT)
        }
    }

    function destroy() {
        destroyed = true
        canvas.removeEventListener("webglcontextlost", onLost)
        canvas.removeEventListener("webglcontextrestored", onRestoredEvent)
        releaseFitted()
        fitted = null
        original = null
        last = null
        if (resources && !lost) {
            for (const known of resources.targets.values()) {
                gl.deleteTexture(known.tex)
                gl.deleteFramebuffer(known.fbo)
            }
            for (const tex of [
                resources.source,
                resources.tables,
                resources.kernels,
            ]) {
                gl.deleteTexture(tex)
            }
            for (const p of [
                resources.develop,
                resources.blur,
                resources.finish,
                resources.copy,
            ]) {
                gl.deleteProgram(p.prog)
            }
            gl.deleteVertexArray(resources.vertexArray)
            // The context itself goes now, not when the canvas is collected: a page holds few at once
            gl.getExtension("WEBGL_lose_context")?.loseContext()
        }
        resources = null
    }

    return {
        setSource,
        clearSource,
        uprightSize,
        render,
        renderBefore,
        readHistogram,
        destroy,
        canvas,
        get isLost() {
            return lost
        },
    }
}

/** RGBA bytes binned into the four channels' relative bins, over the covered pixels */
function binned(pixels) {
    const counts = {
        luminance: new Float64Array(HISTOGRAM_BINS),
        red: new Float64Array(HISTOGRAM_BINS),
        green: new Float64Array(HISTOGRAM_BINS),
        blue: new Float64Array(HISTOGRAM_BINS),
    }
    const levelsPerBin = 256 / HISTOGRAM_BINS
    let counted = 0
    for (let i = 0; i < pixels.length; i += 4) {
        if (pixels[i + 3] === 0) {
            continue
        }
        const [r, g, b] = [pixels[i], pixels[i + 1], pixels[i + 2]]
        const gray = Math.round(0.299 * r + 0.587 * g + 0.114 * b)
        counts.luminance[Math.floor(gray / levelsPerBin)]++
        counts.red[Math.floor(r / levelsPerBin)]++
        counts.green[Math.floor(g / levelsPerBin)]++
        counts.blue[Math.floor(b / levelsPerBin)]++
        counted++
    }
    const tallest = Math.max(
        ...Object.values(counts).map((bins) => Math.max(...bins)),
    )
    const relative = (bins) =>
        Uint8Array.from(bins, (count) =>
            tallest > 0 ? Math.round((255 * count) / tallest) : 0,
        )
    return {
        luminance: relative(counts.luminance),
        red: relative(counts.red),
        green: relative(counts.green),
        blue: relative(counts.blue),
        pixels: counted,
    }
}
