// src/kinds.ts
var DEFAULT_TYPOGRAPHY_NAMES = [
  "font-size",
  "font-weight",
  "letter-spacing",
  "line-height"
];
var BORDER_STYLES = /\b(solid|dashed|dotted|double|groove|ridge|inset|outset)\b/;
var TOKEN_REFERENCE = /var\(\s*(--[a-zA-Z][\w-]*)\s*\)/g;
var defaultSupports = (property, value) => typeof CSS !== "undefined" && typeof CSS.supports === "function" ? CSS.supports(property, value) : false;
function matchesName(name, matcher) {
  if (matcher instanceof RegExp) {
    return matcher.test(name);
  }
  const bare = name.replace(/^--/, "");
  return bare === matcher || bare.startsWith(`${matcher}-`) || bare.endsWith(`-${matcher}`) || bare.includes(`-${matcher}-`);
}
function isTypographyName(name, names = DEFAULT_TYPOGRAPHY_NAMES) {
  return names.some((matcher) => matchesName(name, matcher));
}
function kindOf(name, value, options = {}) {
  const supports = options.supports ?? defaultSupports;
  if (isTypographyName(name, options.typographyNames)) {
    return "typography";
  }
  const text = value.trim();
  if (text === "") {
    return "other";
  }
  if (supports("color", text)) {
    return "color";
  }
  if (isLengths(text, supports)) {
    return "length";
  }
  if (BORDER_STYLES.test(text) && supports("border", text)) {
    return "border";
  }
  if (supports("box-shadow", text)) {
    return "shadow";
  }
  if (supports("font-family", text)) {
    return "font";
  }
  return "other";
}
function isLengths(text, supports) {
  if (supports("width", text)) {
    return true;
  }
  const parts = splitOutsideParentheses(text, " ").filter(
    (part) => part !== ""
  );
  return parts.length > 1 && parts.every((part) => supports("width", part));
}
function splitOutsideParentheses(text, separator) {
  const parts = [];
  let depth = 0;
  let start = 0;
  for (let index = 0; index < text.length; index++) {
    const char = text[index];
    if (char === "(") {
      depth++;
    } else if (char === ")") {
      depth--;
    } else if (char === separator && depth === 0) {
      parts.push(text.slice(start, index).trim());
      start = index + 1;
    }
  }
  parts.push(text.slice(start).trim());
  return parts;
}
function referencedNamesOf(declaration) {
  const names = Array.from(
    declaration.matchAll(TOKEN_REFERENCE),
    ([, name]) => name
  );
  return [...new Set(names)];
}
function isRecipe(declared) {
  return referencedNamesOf(declared).length > 0;
}

// src/config.ts
var DOCKS = ["left", "right", "bottom"];
var DEFAULT_STORAGE_KEY = "tokentweak";
var DEFAULT_ROOT_SELECTOR = ":root";
var DEFAULT_HOST_ID = "tokentweak";
var DEFAULT_TITLE = "Tweaks";
var DEFAULT_SHORTCUT = "Ctrl+Alt+T";
var DEFAULT_DOCK = "right";
var DEFAULT_FILE_NAME = "the stylesheet";
var DEFAULT_INDENT = "    ";
function defaultIntro(fileName) {
  return `Apply these design changes to ${fileName}. Each token's new value is exact.`;
}
var typography = (name) => (token) => isTypographyName(token.name, [name]);
var DEFAULT_RANGES = [
  {
    match: typography("font-size"),
    min: 0,
    max: 3,
    step: 0.0625,
    unit: "rem"
  },
  { match: typography("font-weight"), min: 100, max: 900, step: 100 },
  {
    match: typography("letter-spacing"),
    min: -0.1,
    max: 0.5,
    step: 0.01,
    unit: "em"
  },
  { match: typography("line-height"), min: 0.8, max: 2.5, step: 0.05 },
  {
    match: (token) => token.kind === "length",
    min: 0,
    max: 4,
    step: 0.0625,
    unit: "rem"
  }
];
function matchesToken(match, token) {
  if (match instanceof RegExp) {
    return match.test(token.name);
  }
  if (typeof match === "function") {
    return match(token);
  }
  return match.some((prefix) => token.name.startsWith(prefix));
}
function rangeFor(token, ranges = DEFAULT_RANGES) {
  const rule = ranges.find(
    (candidate) => matchesToken(candidate.match, token)
  );
  return rule ? {
    min: rule.min,
    max: rule.max,
    step: rule.step,
    unit: rule.unit ?? ""
  } : null;
}
function scopedControlOf(token) {
  if (token.control) {
    return token.control;
  }
  const value = parseFloat(token.default);
  return { min: 0, max: Number.isNaN(value) ? 0 : value * 3, step: 1 };
}
function resolveSeed(seed, tokens) {
  if (!seed) {
    return null;
  }
  if (!isSeedShaped(seed)) {
    console.warn(
      'tokentweak: the seed is not of the 0.3.0 shape, { hue, lightness, targets: { "--token": (seed) => string } }; the seed control is hidden'
    );
    return null;
  }
  const names = new Set(
    tokens.filter((token) => token.kind !== "scoped").map((token) => token.name)
  );
  const missing = [
    .../* @__PURE__ */ new Set([seed.hue, seed.lightness, ...Object.keys(seed.targets)])
  ].filter((name) => !names.has(name));
  if (missing.length === 0) {
    return seed;
  }
  console.warn(
    `tokentweak: no root token named ${missing.join(", ")}; the seed control is hidden`
  );
  return null;
}
function isSeedShaped(seed) {
  const { hue, lightness, targets } = seed;
  return typeof hue === "string" && typeof lightness === "string" && typeof targets === "object" && targets !== null && Object.values(targets).every((formula) => typeof formula === "function");
}
function resolveConfig(config = {}) {
  const fileName = config.exports?.fileName ?? DEFAULT_FILE_NAME;
  return {
    storageKey: config.storageKey ?? DEFAULT_STORAGE_KEY,
    rootSelector: config.rootSelector ?? DEFAULT_ROOT_SELECTOR,
    stylesheet: config.stylesheet ?? null,
    hostId: config.hostId ?? DEFAULT_HOST_ID,
    title: config.title ?? DEFAULT_TITLE,
    trigger: config.trigger ?? null,
    shortcut: config.shortcut === void 0 ? DEFAULT_SHORTCUT : config.shortcut,
    dock: config.dock ?? DEFAULT_DOCK,
    inertGuard: config.inertGuard ?? true,
    theme: config.theme ?? "auto",
    typographyNames: config.typographyNames ?? DEFAULT_TYPOGRAPHY_NAMES,
    sections: config.sections ?? null,
    scale: config.scale ?? null,
    typeScale: config.typeScale ?? null,
    seed: config.seed ?? null,
    scopedTokens: [...config.scopedTokens ?? []],
    extraControls: [...config.extraControls ?? []],
    ranges: [...config.ranges ?? [], ...DEFAULT_RANGES],
    exports: {
      fileName,
      indent: config.exports?.indent ?? DEFAULT_INDENT,
      intro: config.exports?.intro ?? defaultIntro(fileName)
    },
    sources: config.sources ?? null
  };
}

// src/recipe.ts
var RELATIVE_FNS = [
  "rgb",
  "hsl",
  "hwb",
  "lab",
  "lch",
  "oklab",
  "oklch"
];
var FACTOR_SPAN = { min: 0, max: 3, step: 0.05 };
var MIX_SPAN = { min: 0, max: 100, step: 1 };
var NUMBER = /^(-?\d*\.?\d+(?:e[+-]?\d+)?)(%?)$/i;
var CALC = /^calc\(\s*([a-z]+)\s*([+\-*])\s*(-?\d*\.?\d+(?:e[+-]?\d+)?)(%?)\s*\)$/;
var RELATIVE = /^(rgb|hsl|hwb|lab|lch|oklab|oklch)\(\s*from\s+(.*)\)$/;
var MIX = /^color-mix\(\s*in\s+(.*)\)$/;
var TOKEN = /^var\(\s*(--[\w-]+)\s*\)$/;
var CHANNELS = {
  rgb: [
    ["r", { min: 0, max: 255, step: 1 }, false],
    ["g", { min: 0, max: 255, step: 1 }, false],
    ["b", { min: 0, max: 255, step: 1 }, false]
  ],
  hsl: [
    ["h", { min: 0, max: 360, step: 1 }, false],
    ["s", { min: 0, max: 100, step: 0.5 }, true],
    ["l", { min: 0, max: 100, step: 0.5 }, true]
  ],
  hwb: [
    ["h", { min: 0, max: 360, step: 1 }, false],
    ["w", { min: 0, max: 100, step: 0.5 }, true],
    ["b", { min: 0, max: 100, step: 0.5 }, true]
  ],
  lab: [
    ["l", { min: 0, max: 100, step: 0.5 }, false],
    ["a", { min: -125, max: 125, step: 1 }, false],
    ["b", { min: -125, max: 125, step: 1 }, false]
  ],
  lch: [
    ["l", { min: 0, max: 100, step: 0.5 }, false],
    ["c", { min: 0, max: 150, step: 0.5 }, false],
    ["h", { min: 0, max: 360, step: 1 }, false]
  ],
  oklab: [
    ["l", { min: 0, max: 1, step: 5e-3 }, false],
    ["a", { min: -0.4, max: 0.4, step: 2e-3 }, false],
    ["b", { min: -0.4, max: 0.4, step: 2e-3 }, false]
  ],
  oklch: [
    ["l", { min: 0, max: 1, step: 5e-3 }, false],
    ["c", { min: 0, max: 0.4, step: 2e-3 }, false],
    ["h", { min: 0, max: 360, step: 1 }, false]
  ]
};
function parseRecipe(text) {
  const whole = text.trim().replace(/\s+/g, " ").replace(/\s*\/\s*/g, " / ");
  if (splitOutsideParentheses(whole, " ").length !== 1) {
    return null;
  }
  const [, fn, body] = whole.match(RELATIVE) ?? [];
  if (fn !== void 0 && body !== void 0) {
    return parseRelative(fn, body);
  }
  const [, mixBody] = whole.match(MIX) ?? [];
  return mixBody === void 0 ? null : parseMix(mixBody);
}
function parseRelative(fn, body) {
  const parts = splitOutsideParentheses(body.trim(), " ").filter(Boolean);
  const [source, ...rest] = parts;
  if (source === void 0 || rest.length !== 3 && rest.length !== 5) {
    return null;
  }
  let alpha = null;
  if (rest.length === 5) {
    const [, alphaText, percent] = rest[4].match(NUMBER) ?? [];
    if (rest[3] !== "/" || alphaText === void 0 || percent !== "") {
      return null;
    }
    alpha = Number(alphaText);
  }
  const channels = CHANNELS[fn].map(
    ([keyword], index) => parseChannel(rest[index], keyword)
  );
  if (!channels.every((channel) => channel !== null)) {
    return null;
  }
  return {
    kind: "relative",
    fn,
    source,
    channels,
    alpha
  };
}
function parseChannel(text, keyword) {
  if (text === keyword) {
    return { keyword, mode: "inherits", value: null, percent: false };
  }
  const [, number, percent] = text.match(NUMBER) ?? [];
  if (number !== void 0) {
    return {
      keyword,
      mode: "absolute",
      value: Number(number),
      percent: percent === "%"
    };
  }
  const [, own, operator, operand, operandPercent] = text.match(CALC) ?? [];
  if (own !== keyword || operand === void 0) {
    return null;
  }
  const value = Number(operand);
  return {
    keyword,
    mode: operator === "*" ? "factor" : "offset",
    value: operator === "-" ? -value : value,
    percent: operandPercent === "%"
  };
}
function parseMix(body) {
  const parts = splitOutsideParentheses(body, ",");
  if (parts.length !== 3) {
    return null;
  }
  const [space, first, b] = parts;
  const firstParts = splitOutsideParentheses(first, " ").filter(Boolean);
  const percentText = firstParts.pop();
  const a = firstParts.join(" ");
  const [, percent, sign] = percentText?.match(NUMBER) ?? [];
  if (space === "" || a === "" || b === "" || sign !== "%") {
    return null;
  }
  const bLast = splitOutsideParentheses(b, " ").at(-1) ?? "";
  if (bLast.match(NUMBER)?.[2] === "%") {
    return null;
  }
  return { kind: "mix", space, a, percent: Number(percent), b };
}
function serializeRecipe(recipe) {
  if (recipe.kind === "mix") {
    return `color-mix(in ${recipe.space}, ${recipe.a} ${round(recipe.percent)}%, ${recipe.b})`;
  }
  const channels = recipe.channels.map(serializeChannel).join(" ");
  const alpha = recipe.alpha === null ? "" : ` / ${round(recipe.alpha)}`;
  return `${recipe.fn}(from ${recipe.source} ${channels}${alpha})`;
}
function serializeChannel({ keyword, mode, value, percent }) {
  const number = `${round(Math.abs(value ?? 0))}${percent ? "%" : ""}`;
  switch (mode) {
    case "inherits":
      return keyword;
    case "absolute":
      return `${(value ?? 0) < 0 ? "-" : ""}${number}`;
    case "offset":
      return `calc(${keyword} ${(value ?? 0) < 0 ? "-" : "+"} ${number})`;
    case "factor":
      return `calc(${keyword} * ${(value ?? 0) < 0 ? "-" : ""}${number})`;
  }
}
function channelRanges(fn) {
  return CHANNELS[fn].map(([keyword, absolute, percent]) => {
    const width = absolute.max - absolute.min;
    return {
      label: keyword.toUpperCase(),
      keyword,
      percent,
      absolute,
      offset: { min: -width, max: width, step: absolute.step },
      factor: FACTOR_SPAN
    };
  });
}
function withChannel(recipe, index, channel) {
  const channels = [...recipe.channels];
  channels[index] = {
    ...channel,
    keyword: recipe.channels[index].keyword
  };
  return { ...recipe, channels };
}
function channelInMode(range, mode, resolved) {
  const { keyword } = range;
  switch (mode) {
    case "inherits":
      return { keyword, mode, value: null, percent: false };
    case "absolute":
      return { keyword, mode, value: resolved, percent: range.percent };
    case "offset":
      return { keyword, mode, value: 0, percent: false };
    case "factor":
      return { keyword, mode, value: 1, percent: false };
  }
}
function withSource(recipe, source) {
  return { ...recipe, source };
}
function withAlpha(recipe, alpha) {
  return { ...recipe, alpha };
}
function withPercent(mix, percent) {
  return { ...mix, percent };
}
function withMixColor(mix, which, color) {
  return { ...mix, [which]: color };
}
function recipePhrase(before, after) {
  const a = parseRecipe(before);
  const b = parseRecipe(after);
  if (!a || !b || a.kind !== b.kind) {
    return null;
  }
  const moved = [];
  if (a.kind === "mix" && b.kind === "mix") {
    if (a.space !== b.space) {
      moved.push(`in ${a.space} \u2192 ${b.space}`);
    }
    if (a.a !== b.a) {
      moved.push(`first ${sourceName(a.a)} \u2192 ${sourceName(b.a)}`);
    }
    if (a.b !== b.b) {
      moved.push(`second ${sourceName(a.b)} \u2192 ${sourceName(b.b)}`);
    }
    if (round(a.percent) !== round(b.percent)) {
      moved.push(`mix ${plain(a.percent)}% \u2192 ${plain(b.percent)}%`);
    }
  } else if (a.kind === "relative" && b.kind === "relative") {
    if (a.fn !== b.fn) {
      return null;
    }
    if (a.source !== b.source) {
      moved.push(`from ${sourceName(a.source)} \u2192 ${sourceName(b.source)}`);
    }
    a.channels.forEach((channel, index) => {
      const other = b.channels[index];
      if (!sameChannel(channel, other)) {
        moved.push(channelPhrase(channel, other));
      }
    });
    if (round(a.alpha ?? 1) !== round(b.alpha ?? 1)) {
      moved.push(`alpha ${plain(a.alpha ?? 1)} \u2192 ${plain(b.alpha ?? 1)}`);
    }
  }
  return moved.length ? moved.join(", ") : null;
}
function sameChannel(a, b) {
  return a.mode === b.mode && a.percent === b.percent && round(a.value ?? 0) === round(b.value ?? 0);
}
function channelPhrase(a, b) {
  const label = a.keyword.toUpperCase();
  if (a.mode === b.mode && a.mode !== "inherits") {
    return `${label} ${a.mode} ${channelNumber(a)} \u2192 ${channelNumber(b)}`;
  }
  return `${label} ${channelText(a)} \u2192 ${channelText(b)}`;
}
function channelText(channel) {
  return channel.mode === "inherits" ? "inherits" : `${channel.mode} ${channelNumber(channel)}`;
}
function channelNumber({ mode, value, percent }) {
  const number = mode === "offset" ? signed(value ?? 0) : plain(value ?? 0);
  return `${number}${percent ? "%" : ""}`;
}
function sourceName(source) {
  const [, name] = source.match(TOKEN) ?? [];
  return name ?? source;
}
function plain(value) {
  return String(round(value)).replace("-", "\u2212");
}
function signed(value) {
  const rounded = round(value);
  return rounded < 0 ? plain(rounded) : `+${rounded}`;
}
function round(value) {
  return Math.round(value * 1e4) / 1e4;
}

// src/theme.ts
var THEMES = ["light", "dark"];
var LIGHT_DARK = /^light-dark\((.*)\)$/s;
function isTheme(value) {
  return THEMES.includes(value);
}
function themeColorsOf(value) {
  const [, colors] = value.trim().match(LIGHT_DARK) ?? [];
  const parts = colors === void 0 ? [] : splitOutsideParentheses(colors, ",");
  return parts.length === THEMES.length ? [parts[0], parts[1]] : null;
}
function withThemeColor(value, theme, color) {
  const colors = themeColorsOf(value);
  if (!colors) {
    return color;
  }
  colors[THEMES.indexOf(theme)] = color;
  return `light-dark(${colors.join(", ")})`;
}
function themeSideOf(value, theme) {
  const colors = themeColorsOf(value);
  return colors ? colors[THEMES.indexOf(theme)] : value;
}
function applyTheme(theme) {
  document.documentElement.style.colorScheme = theme ?? "";
}
function themeOrStylesheets(theme) {
  return isTheme(theme) ? theme : getComputedStyle(document.documentElement).colorScheme;
}
function resolveColor(value) {
  const probeEl = document.createElement("span");
  probeEl.style.color = value;
  document.body.appendChild(probeEl);
  const color = getComputedStyle(probeEl).color;
  probeEl.remove();
  return color;
}
function inTheme(theme, resolve) {
  const rootStyle = document.documentElement.style;
  const shownScheme = rootStyle.colorScheme;
  applyTheme(theme);
  try {
    return resolve();
  } finally {
    rootStyle.colorScheme = shownScheme;
  }
}

// src/color.ts
var OKLCH = /^oklch\(([\d.e-]+%?) ([\d.e-]+%?) ([\d.e-]+|none)(?: \/ ([\d.e-]+%?))?\)$/;
var RGB = /^(color\(srgb|rgba?\()\s*([^)]+)\)$/;
var FN_BODY = /^[a-z]+\((.*)\)$/;
var HEX = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})/i;
function oklchOf(color) {
  const computed = resolveColor(`oklch(from ${color} l c h / alpha)`);
  const [, l, c, h, alpha = "1"] = computed.match(OKLCH) ?? [];
  return {
    l: fraction(l ?? "0"),
    c: fraction(c ?? "0", 0.4),
    h: h === "none" || h === void 0 ? 0 : Number(h),
    alpha: fraction(alpha)
  };
}
function hexOf(color) {
  const computed = resolveColor(`rgb(from ${color} r g b / alpha)`);
  const [, space, channels] = computed.match(RGB) ?? [];
  if (space === void 0 || channels === void 0) {
    return color;
  }
  const scale = space.startsWith("color") ? 255 : 1;
  const [r = "0", g = "0", b = "0", alpha = "1"] = channels.replace("/", " ").split(/[\s,]+/).filter(Boolean);
  const channel = (value, factor = scale) => Math.round(Math.min(255, Math.max(0, Number(value) * factor))).toString(16).padStart(2, "0");
  const hex = `#${channel(r)}${channel(g)}${channel(b)}`;
  return Number(alpha) < 1 ? `${hex}${channel(alpha, 255)}` : hex;
}
function channelsOf(fn, color) {
  const ranges = channelRanges(fn);
  if (fn === "rgb" || fn === "hsl" || fn === "hwb") {
    const [, r = "0", g = "0", b = "0"] = hexOf(color).match(HEX) ?? [];
    const rgb = [parseInt(r, 16), parseInt(g, 16), parseInt(b, 16)];
    return fn === "rgb" ? rgb : fn === "hsl" ? hslOf(rgb) : hwbOf(rgb);
  }
  const keywords = ranges.map((range) => range.keyword).join(" ");
  const computed = resolveColor(`${fn}(from ${color} ${keywords})`);
  const [, body = ""] = computed.match(FN_BODY) ?? [];
  const parts = (body.split("/")[0] ?? "").trim().split(/\s+/);
  return ranges.map((range, index) => {
    const part = parts[index] ?? "none";
    return part === "none" ? 0 : fraction(part, range.absolute.max);
  });
}
function hslOf(rgb) {
  const max = Math.max(...rgb) / 255;
  const min = Math.min(...rgb) / 255;
  const l = (max + min) / 2;
  const d = max - min;
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
  return [hueOf(rgb), s * 100, l * 100];
}
function hwbOf(rgb) {
  const max = Math.max(...rgb) / 255;
  const min = Math.min(...rgb) / 255;
  return [hueOf(rgb), min * 100, (1 - max) * 100];
}
function hueOf([r, g, b]) {
  const max = Math.max(r, g, b);
  const d = max - Math.min(r, g, b);
  if (d === 0) {
    return 0;
  }
  const h = max === r ? (g - b) / d % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return (h * 60 + 360) % 360;
}
function isColor(text) {
  return typeof CSS !== "undefined" && CSS.supports("color", text);
}
function fraction(text, full = 1) {
  return text.endsWith("%") ? Number(text.slice(0, -1)) / 100 * full : Number(text);
}

// src/dom.ts
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (key === "class") {
      node.className = String(value);
    } else if (key === "text") {
      node.textContent = String(value);
    } else if (key.startsWith("on") && typeof value === "function") {
      node.addEventListener(key.slice(2), value);
    } else if (value !== void 0 && value !== null && value !== false) {
      node.setAttribute(key, value === true ? "" : String(value));
    }
  });
  node.append(
    ...children.filter(
      (child) => child !== null && child !== void 0 && child !== false
    )
  );
  return node;
}
function flash(button, label, ms = 1200) {
  const previous = button.textContent;
  button.textContent = label;
  setTimeout(() => {
    button.textContent = previous;
  }, ms);
}
function copyText(text, button) {
  navigator.clipboard.writeText(text).then(
    () => flash(button, "Copied"),
    () => flash(button, "Copy failed")
  );
}
function downloadText(fileName, text, type = "application/json") {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const link = el("a", { href: url, download: fileName });
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

// src/fonts.ts
var FONT_PROBE_TEXT = "mmmmmmmmmmlliWWW0123456789";
var FONT_PROBE_FALLBACKS = ["monospace", "sans-serif", "serif"];
function isFontAvailable(family) {
  const context = document.createElement("canvas").getContext("2d");
  if (!context) {
    return true;
  }
  const widthIn = (fontFamily) => {
    context.font = `72px ${fontFamily}`;
    return context.measureText(FONT_PROBE_TEXT).width;
  };
  return FONT_PROBE_FALLBACKS.some(
    (fallback) => widthIn(`${family}, ${fallback}`) !== widthIn(fallback)
  );
}
function asFontFamily(value) {
  return CSS.supports("font-family", value) ? value : JSON.stringify(value);
}

// src/sections.ts
var OTHER_SECTION = { id: "other", title: "Other" };
function inferSections(tokens, config = {}) {
  const sections = [];
  const byId = /* @__PURE__ */ new Map();
  const other = {
    ...OTHER_SECTION,
    tokens: [],
    scoped: [],
    extras: []
  };
  const add = (id, title) => {
    const section = {
      id,
      title,
      tokens: [],
      scoped: [],
      extras: []
    };
    sections.push(section);
    byId.set(id, section);
    return section;
  };
  const rootTokens = tokens.filter((token) => token.kind !== "scoped");
  if (config.sections) {
    config.sections.forEach(({ id, title }) => add(id, title));
    rootTokens.forEach((token) => {
      const match = config.sections?.find(
        (section) => sectionMatches(section, token)
      );
      const target = match ? byId.get(match.id) : void 0;
      (target ?? other).tokens.push(token);
    });
  } else {
    const counts = /* @__PURE__ */ new Map();
    rootTokens.forEach((token) => {
      const segment = segmentOf(token.name);
      counts.set(segment, (counts.get(segment) ?? 0) + 1);
    });
    rootTokens.forEach((token) => {
      const segment = segmentOf(token.name);
      if ((counts.get(segment) ?? 0) < 2) {
        other.tokens.push(token);
        return;
      }
      const section = byId.get(segment) ?? add(segment, titleOf(segment));
      section.tokens.push(token);
    });
  }
  tokens.filter((token) => token.kind === "scoped").forEach((token) => {
    const target = sectionFor(token.scoped?.section, byId) ?? other;
    target.scoped.push(token);
  });
  (config.extraControls ?? []).forEach((control) => {
    const target = sectionFor(control.section, byId) ?? other;
    target.extras.push(control);
  });
  const configured = byId.get(OTHER_SECTION.id);
  if (configured) {
    configured.tokens.push(...other.tokens);
    configured.scoped.push(...other.scoped);
    configured.extras.push(...other.extras);
  } else if (other.tokens.length || other.scoped.length || other.extras.length) {
    sections.push(other);
  }
  return sections;
}
function sectionMatches(section, token) {
  if (!section.match && !section.kinds) {
    return false;
  }
  if (section.kinds && !section.kinds.includes(token.kind)) {
    return false;
  }
  return section.match ? matchesToken(section.match, token) : true;
}
function sectionFor(id, byId) {
  return id === void 0 ? void 0 : byId.get(id);
}
function segmentOf(name) {
  const [segment = ""] = name.replace(/^--/, "").split("-");
  return segment === "" ? OTHER_SECTION.id : segment;
}
function titleOf(segment) {
  return segment.charAt(0).toUpperCase() + segment.slice(1);
}
var LENGTH_LITERAL = /^-?\d*\.?\d+[a-z%]+$/i;
var STEP_SUFFIX = /-\d+$/;
var STEP_REFERENCE = /^var\(\s*(--[a-zA-Z][\w-]*)\s*\)$/;
function inferScale(tokens, config) {
  if (config === false) {
    return null;
  }
  if (config) {
    const steps = config.steps instanceof RegExp ? tokens.filter(
      (token) => config.steps.test(token.name)
    ) : config.steps.map(
      (name) => tokens.find((token) => token.name === name)
    ).filter((token) => token !== void 0);
    return steps.length ? { steps: steps.map(stepOf) } : null;
  }
  const families = /* @__PURE__ */ new Map();
  tokens.filter(
    (token) => token.kind === "length" && LENGTH_LITERAL.test(token.declared.trim()) && STEP_SUFFIX.test(token.name)
  ).forEach((token) => {
    const family = token.name.replace(STEP_SUFFIX, "");
    families.set(family, [...families.get(family) ?? [], token]);
  });
  let largest = null;
  families.forEach((members) => {
    if (members.length >= 3 && members.length > (largest?.length ?? 0)) {
      largest = members;
    }
  });
  if (!largest) {
    return null;
  }
  const sorted = [...largest].sort(
    (a, b) => stepNumber(a.name) - stepNumber(b.name)
  );
  return { steps: sorted.map(stepOf) };
}
function stepReferenceOf(declared) {
  const [, name] = declared.trim().match(STEP_REFERENCE) ?? [];
  return name ?? null;
}
function scaleRoleOf(token, scale) {
  if (!scale) {
    return null;
  }
  if (scale.steps.some((step) => step.name === token.name)) {
    return "step";
  }
  const referenced = stepReferenceOf(token.declared);
  if (token.kind === "length" && referenced !== null && scale.steps.some((step) => step.name === referenced)) {
    return "role";
  }
  return null;
}
function withScaleRoles(tokens, scale) {
  return tokens.map((token) => ({
    ...token,
    scaleRole: scaleRoleOf(token, scale)
  }));
}
function stepOf(token) {
  return { name: token.name, value: token.declared };
}
function stepNumber(name) {
  return Number(name.match(/(\d+)$/)?.[1] ?? 0);
}
var REM = /^(-?\d*\.?\d+)rem$/;
var FONT_SIZE = /font-size/;
function remOf(value) {
  const [, rem] = value.trim().match(REM) ?? [];
  return rem === void 0 ? null : Number(rem);
}
function inferTypeScale(tokens, config) {
  if (config === false) {
    return null;
  }
  if (config) {
    const names = new Set(tokens.map((token) => token.name));
    const steps2 = config.steps.filter((name) => names.has(name));
    return steps2.length >= 3 && steps2.includes(config.base) ? { steps: steps2, base: config.base } : null;
  }
  const sized = tokens.filter(
    (token) => FONT_SIZE.test(token.name) && remOf(token.declared) !== null
  ).sort((a, b) => (remOf(a.declared) ?? 0) - (remOf(b.declared) ?? 0));
  if (sized.length < 3) {
    return null;
  }
  const steps = sized.map((token) => token.name);
  const base = steps.find((name) => name.endsWith("-base")) ?? steps[Math.floor((steps.length - 1) / 2)];
  return { steps, base };
}
function typeScaleValues(typeScale, baseRem, ratio) {
  const baseIndex = typeScale.steps.indexOf(typeScale.base);
  return Object.fromEntries(
    typeScale.steps.map((name, index) => [
      name,
      `${Math.round(baseRem * ratio ** (index - baseIndex) * 1e4) / 1e4}rem`
    ])
  );
}
function colorSources(tokens) {
  return tokens.filter((token) => token.kind === "color").map((token) => ({ name: token.name, value: token.declared }));
}

// src/controls.ts
var BORDER_STYLES2 = [
  "solid",
  "dashed",
  "dotted",
  "double",
  "groove",
  "ridge",
  "inset",
  "outset",
  "none"
];
var PX = /^(-?\d*\.?\d+)px$/;
var NUMBER2 = /^-?\d*\.?\d+$/;
var TOKEN_ONLY = /^var\((--[\w-]+)\)$/;
var OWN_COLOR = /^(#|rgba?\(|hsla?\(|oklch\(|oklab\(|lab\(|lch\(|hwb\(|color\()(?!\s*from\b)|^[a-z]+$/i;
var DEFAULT_REM = { min: 0, max: 4, step: 0.0625, unit: "rem" };
function buildControl(token, ctx) {
  switch (token.kind) {
    case "color":
      return buildColorControl(token, ctx);
    case "length":
      return buildLengthControl(token, ctx);
    case "border":
      return buildBorderControl(token, ctx);
    case "shadow":
      return buildShadowControl(token, ctx);
    case "font":
      return buildFontControl(token, ctx);
    case "typography":
      return buildTypographyControl(token, ctx);
    case "scoped":
      return buildScopedControl(token, ctx);
    default:
      return buildTextControl(token, ctx);
  }
}
var CHANNEL_MODES = [
  "inherits",
  "absolute",
  "offset",
  "factor"
];
var PERCENT_SPAN = { min: 0, max: 100, step: 0.5 };
var PERCENT_OFFSET_SPAN = {
  min: -PERCENT_SPAN.max,
  max: PERCENT_SPAN.max,
  step: PERCENT_SPAN.step
};
function buildColorControl(token, ctx) {
  const sideOf = () => themeSideOf(ctx.value(), ctx.theme());
  const write = (color) => ctx.set(withThemeColor(ctx.value(), ctx.theme(), color));
  const views = { sideOf, write, refreshViews: () => refresh() };
  const literal = buildLiteralColorView(token, views);
  const wrapper = el("div", { class: "control color" }, literal.el);
  let recipe = null;
  const refresh = () => {
    const own = OWN_COLOR.test(sideOf());
    literal.el.hidden = !own;
    if (own) {
      if (recipe) {
        recipe.el.hidden = true;
      }
      literal.refresh();
      return;
    }
    if (recipe === null) {
      recipe = buildRecipeColorView(token, ctx, views);
      wrapper.append(recipe.el);
    }
    recipe.el.hidden = false;
    recipe.refresh();
  };
  refresh();
  return { el: wrapper, refresh };
}
function buildLiteralColorView(token, { sideOf, write, refreshViews }) {
  const swatch = el("span", { class: "swatch" });
  const hex = el("input", {
    type: "text",
    size: "9",
    "aria-label": `${token.name} hex`
  });
  const picker = el("input", {
    type: "color",
    "aria-label": `${token.name} picker`
  });
  const sliders = {
    l: slider(0, 1, 5e-3),
    c: slider(0, 0.4, 2e-3),
    h: slider(0, 360, 1)
  };
  const readouts = {
    l: el("span", { class: "v" }),
    c: el("span", { class: "v" }),
    h: el("span", { class: "v" })
  };
  let alpha = 1;
  const paint = () => {
    const color = sideOf();
    swatch.style.background = color;
    hex.value = color;
    picker.value = hexOf(color).slice(0, 7);
  };
  const fromSliders = () => {
    const color = `oklch(${sliders.l.value} ${sliders.c.value} ${sliders.h.value}${alpha < 1 ? ` / ${alpha}` : ""})`;
    write(hexOf(color));
    paint();
  };
  const refresh = () => {
    const { l, c, h, alpha: a } = oklchOf(sideOf());
    alpha = a;
    sliders.l.value = String(l);
    sliders.c.value = String(c);
    sliders.h.value = String(h);
    readouts.l.textContent = l.toFixed(3);
    readouts.c.textContent = c.toFixed(3);
    readouts.h.textContent = `${Math.round(h)}\xB0`;
    paint();
  };
  ["l", "c", "h"].forEach(
    (channel) => sliders[channel].addEventListener("input", () => {
      const input = sliders[channel];
      readouts[channel].textContent = channel === "h" ? `${Math.round(Number(input.value))}\xB0` : Number(input.value).toFixed(3);
      fromSliders();
    })
  );
  hex.addEventListener("change", () => {
    if (isColor(hex.value.trim())) {
      write(hex.value.trim());
      refreshViews();
    } else {
      hex.value = sideOf();
    }
  });
  picker.addEventListener("input", () => {
    write(picker.value);
    refreshViews();
  });
  return {
    el: el(
      "div",
      { class: "view literal" },
      el("div", { class: "row" }, swatch, hex, picker),
      channelRow("L", sliders.l, readouts.l),
      channelRow("C", sliders.c, readouts.c),
      channelRow("H", sliders.h, readouts.h)
    ),
    refresh
  };
}
function buildRecipeColorView(token, ctx, { sideOf, write, refreshViews }) {
  const relativeOf = () => relativeRecipeOf(sideOf());
  const mixOf = () => {
    const recipe = parseRecipe(sideOf());
    return recipe?.kind === "mix" ? recipe : null;
  };
  const writeRecipe = (recipe) => write(serializeRecipe(recipe));
  const text = el("input", {
    type: "text",
    "aria-label": `${token.name} value`
  });
  text.addEventListener("change", () => {
    if (isColor(text.value.trim())) {
      write(text.value.trim());
    }
    refreshViews();
  });
  const swatch = el("span", { class: "swatch" });
  const oklch = el("span", { class: "oklch" });
  const detach = el("button", {
    type: "button",
    class: "quiet detach",
    title: "Write the resolved color as a literal",
    text: "detach",
    onclick: () => {
      write(hexOf(sideOf()));
      refreshViews();
    }
  });
  const paintResolved = () => {
    const side = sideOf();
    text.value = side;
    swatch.style.background = hexOf(side);
    const { l, c, h } = oklchOf(side);
    oklch.textContent = `${l.toFixed(3)} ${c.toFixed(3)} ${Math.round(h)}\xB0`;
  };
  const fn = el("span", { class: "fn" });
  const source = sourceSelect(
    token,
    ctx,
    `${token.name} source`,
    (picked) => {
      const recipe = relativeOf();
      if (recipe && picked !== null) {
        writeRecipe(withSource(recipe, picked));
      }
      refreshViews();
    }
  );
  const channels = [0, 1, 2].map((index) => {
    const label = el("span", { class: "k" });
    const mode = el("select", { class: "mode" });
    CHANNEL_MODES.forEach(
      (name) => mode.append(el("option", { value: name, text: name }))
    );
    const slide = slider(0, 1, 0.01);
    const readout = el("span", { class: "v" });
    const show = (range, channel) => {
      label.textContent = range.label;
      mode.setAttribute("aria-label", `${token.name} ${range.label} mode`);
      slide.setAttribute("aria-label", `${token.name} ${range.label}`);
      mode.value = channel.mode;
      const span = spanOf(range, channel);
      slide.hidden = span === null;
      readout.hidden = span === null;
      if (span) {
        slide.min = String(span.min);
        slide.max = String(span.max);
        slide.step = String(span.step);
        slide.value = String(channel.value ?? 0);
        readout.textContent = channelReadout(channel, span.step);
      }
    };
    mode.addEventListener("change", () => {
      const recipe = relativeOf();
      if (recipe) {
        const range = channelRanges(recipe.fn)[index];
        const resolved = channelsOf(recipe.fn, sideOf())[index];
        const channel = channelInMode(
          range,
          mode.value,
          resolved
        );
        writeRecipe(withChannel(recipe, index, channel));
      }
      refreshViews();
    });
    slide.addEventListener("input", () => {
      const recipe = relativeOf();
      if (recipe) {
        const channel = {
          ...recipe.channels[index],
          value: Number(slide.value)
        };
        readout.textContent = channelReadout(
          channel,
          Number(slide.step)
        );
        writeRecipe(withChannel(recipe, index, channel));
        paintResolved();
      }
    });
    return {
      el: el(
        "div",
        { class: "row channel" },
        label,
        mode,
        slide,
        readout
      ),
      show
    };
  });
  const alpha = slider(0, 1, 0.01);
  const alphaReadout = el("span", { class: "v" });
  const alphaRow = channelRow("\u03B1", alpha, alphaReadout);
  alphaRow.classList.add("alpha");
  alpha.addEventListener("input", () => {
    const recipe = relativeOf();
    if (recipe) {
      alphaReadout.textContent = Number(alpha.value).toFixed(2);
      writeRecipe(withAlpha(recipe, Number(alpha.value)));
      paintResolved();
    }
  });
  const mixLabel = el("span", { class: "fn" });
  const mixColor = (which, label) => sourceSelect(token, ctx, `${token.name} ${label}`, (picked) => {
    const mix = mixOf();
    if (mix && picked !== null) {
      writeRecipe(withMixColor(mix, which, picked));
    }
    refreshViews();
  });
  let mixRows = null;
  const mixRowsOf = () => {
    if (mixRows === null) {
      const first = mixColor("a", "first color");
      const second = mixColor("b", "second color");
      mixRows = {
        first,
        second,
        rows: [
          el("div", { class: "row mix" }, mixLabel, first.el),
          el(
            "div",
            { class: "row mix" },
            el("span", { class: "fn", text: "with" }),
            second.el
          )
        ]
      };
      percentRow.before(...mixRows.rows);
    }
    return mixRows;
  };
  const percent = slider(MIX_SPAN.min, MIX_SPAN.max, MIX_SPAN.step);
  const percentReadout = el("span", { class: "v" });
  const percentRow = channelRow("%", percent, percentReadout);
  percentRow.classList.add("percent");
  percent.addEventListener("input", () => {
    const mix = mixOf();
    if (mix) {
      percentReadout.textContent = `${percent.value}%`;
      writeRecipe(withPercent(mix, Number(percent.value)));
      paintResolved();
    }
  });
  const resolvedRow = el(
    "div",
    { class: "row resolved" },
    swatch,
    oklch,
    detach
  );
  const sourceRow = el("div", { class: "row source" }, fn, source.el);
  const refresh = () => {
    const value = sideOf();
    const recipe = parseRecipe(value);
    const relative = recipe?.kind === "relative" ? recipe : null;
    const mix = recipe?.kind === "mix" ? recipe : null;
    text.value = value;
    resolvedRow.hidden = recipe === null;
    sourceRow.hidden = relative === null;
    channels.forEach((channel) => {
      channel.el.hidden = relative === null;
    });
    alphaRow.hidden = (relative?.alpha ?? null) === null;
    percentRow.hidden = mix === null;
    if (mix === null) {
      mixRows?.rows.forEach((row) => {
        row.hidden = true;
      });
    }
    if (recipe) {
      paintResolved();
    }
    if (relative) {
      fn.textContent = `${relative.fn} from`;
      source.show(relative.source);
      const ranges = channelRanges(relative.fn);
      channels.forEach(
        (channel, index) => channel.show(ranges[index], relative.channels[index])
      );
      if (relative.alpha !== null) {
        alpha.value = String(relative.alpha);
        alphaReadout.textContent = relative.alpha.toFixed(2);
      }
    }
    if (mix) {
      const { first, second, rows } = mixRowsOf();
      rows.forEach((row) => {
        row.hidden = false;
      });
      mixLabel.textContent = `mix in ${mix.space}`;
      first.show(mix.a);
      second.show(mix.b);
      percent.value = String(mix.percent);
      percentReadout.textContent = `${mix.percent}%`;
    }
  };
  return {
    el: el(
      "div",
      { class: "view recipe" },
      resolvedRow,
      sourceRow,
      ...channels.map((channel) => channel.el),
      alphaRow,
      percentRow,
      el("div", { class: "row text" }, text)
    ),
    refresh
  };
}
function sourceSelect(token, ctx, label, onPick) {
  const names = ctx.colorSources.map((source) => source.name).filter((name) => name !== token.name);
  const select = el("select", { class: "source", "aria-label": label });
  select.append(el("option", { value: "", text: "(as written)" }));
  names.forEach(
    (name) => select.append(el("option", { value: name, text: name }))
  );
  select.addEventListener(
    "change",
    () => onPick(select.value === "" ? null : `var(${select.value})`)
  );
  return {
    el: select,
    show: (source) => {
      const [, name] = source.match(TOKEN_ONLY) ?? [];
      select.value = name !== void 0 && names.includes(name) ? name : "";
    }
  };
}
function spanOf(range, channel) {
  if (channel.mode === "inherits") {
    return null;
  }
  if (channel.percent && !range.percent) {
    if (channel.mode === "absolute") {
      return PERCENT_SPAN;
    }
    if (channel.mode === "offset") {
      return PERCENT_OFFSET_SPAN;
    }
  }
  return range[channel.mode];
}
function channelReadout({ mode, value, percent }, step) {
  const number = (value ?? 0).toFixed(decimalsOf(step));
  if (mode === "factor") {
    return `\xD7${number}`;
  }
  const signed2 = mode === "offset" && (value ?? 0) >= 0 ? `+${number}` : number;
  return `${signed2}${percent ? "%" : ""}`;
}
function decimalsOf(step) {
  return String(step).split(".")[1]?.length ?? 0;
}
function relativeRecipeOf(value) {
  const recipe = parseRecipe(value);
  return recipe?.kind === "relative" ? recipe : null;
}
function tokenAtAlpha(value) {
  const recipe = relativeRecipeOf(value);
  const [, token] = recipe?.source.match(TOKEN_ONLY) ?? [];
  if (!recipe || recipe.fn !== "rgb" || recipe.alpha === null || token === void 0 || !recipe.channels.every((channel) => channel.mode === "inherits")) {
    return null;
  }
  return { token, alpha: recipe.alpha };
}
function buildColorSourceControl(ctx, { read, write }) {
  const names = ctx.colorSources.map((source) => source.name);
  const select = el("select", { "aria-label": "Color token" });
  select.append(el("option", { value: "", text: "(as written)" }));
  names.forEach(
    (name) => select.append(el("option", { value: name, text: name }))
  );
  const alpha = slider(0, 1, 0.01);
  const readout = el("span", { class: "v" });
  const text = el("input", { type: "text", "aria-label": "Color" });
  const parse = () => {
    const color = read();
    const atAlpha = tokenAtAlpha(color);
    const [, only] = color.match(TOKEN_ONLY) ?? [];
    return {
      token: atAlpha?.token ?? only ?? "",
      alpha: atAlpha?.alpha ?? 1,
      color
    };
  };
  const compose = (token, alphaValue) => Number(alphaValue) >= 1 ? `var(${token})` : `rgb(from var(${token}) r g b / ${alphaValue})`;
  const refresh = () => {
    const { token, alpha: alphaValue, color } = parse();
    select.value = names.includes(token) ? token : "";
    alpha.value = String(alphaValue);
    readout.textContent = alphaValue.toFixed(2);
    text.value = color;
    text.hidden = token !== "";
    alpha.disabled = token === "";
  };
  select.addEventListener("change", () => {
    if (select.value === "") {
      return;
    }
    write(compose(select.value, alpha.value));
    refresh();
  });
  alpha.addEventListener("input", () => {
    const { token } = parse();
    if (token) {
      readout.textContent = Number(alpha.value).toFixed(2);
      write(compose(token, alpha.value));
    }
  });
  text.addEventListener("change", () => {
    if (isColor(text.value.trim())) {
      write(text.value.trim());
    }
    refresh();
  });
  refresh();
  return {
    el: el(
      "div",
      { class: "control" },
      el("div", { class: "row" }, select, text),
      channelRow("\u03B1", alpha, readout)
    ),
    refresh
  };
}
function buildLengthControl(token, ctx) {
  const value = ctx.value();
  const step = stepReferenceOf(value);
  if (ctx.scale && (step !== null && isStep(ctx.scale, step) || token.scaleRole === "role" && remOf(value) !== null)) {
    return buildRoleControl(token, ctx);
  }
  if (remOf(value) === null) {
    return buildTextControl(token, ctx);
  }
  return buildRemControl(token, ctx, rangeFor(token, ctx.ranges));
}
function isStep(scale, name) {
  return scale.steps.some((step) => step.name === name);
}
function buildRemControl(token, ctx, range) {
  const { min, max, step } = range ?? DEFAULT_REM;
  const slide = slider(min, max, step);
  const number = el("input", {
    type: "number",
    min: String(min),
    step: String(step),
    "aria-label": `${token.name} rem`
  });
  const px = el("span", { class: "v" });
  const paint = (rem) => {
    slide.value = String(rem);
    number.value = String(rem);
    px.textContent = `${Math.round(rem * rootFontSize() * 100) / 100}px`;
  };
  const refresh = () => {
    const rem = remOf(ctx.value()) ?? 0;
    slide.max = String(Math.max(max, Math.ceil(rem * 2)));
    paint(rem);
  };
  const write = (rem) => {
    ctx.set(`${round2(rem)}rem`);
    paint(round2(rem));
  };
  slide.addEventListener("input", () => write(Number(slide.value)));
  number.addEventListener("change", () => write(Number(number.value)));
  refresh();
  return {
    el: el(
      "div",
      { class: "control" },
      el(
        "div",
        { class: "row" },
        slide,
        number,
        el("span", { class: "k", text: "rem" }),
        px
      )
    ),
    refresh
  };
}
function buildRoleControl(token, ctx) {
  const steps = ctx.scale?.steps ?? [];
  const select = el("select", { "aria-label": `${token.name} step` });
  steps.forEach(
    ({ name, value }) => select.append(
      el("option", { value: name, text: `${name} (${value})` })
    )
  );
  select.append(el("option", { value: "", text: "custom (detach)" }));
  const rem = buildRemControl(token, ctx, rangeFor(token, ctx.ranges));
  const refresh = () => {
    const step = stepReferenceOf(ctx.value());
    select.value = step ?? "";
    rem.el.hidden = step !== null;
    if (step === null) {
      rem.refresh();
    }
  };
  select.addEventListener("change", () => {
    if (select.value === "") {
      const current = ctx.value();
      const step = stepReferenceOf(current);
      const stepValue = steps.find(({ name }) => name === step)?.value ?? current;
      ctx.set(remOf(stepValue) === null ? current : stepValue);
    } else {
      ctx.set(`var(${select.value})`);
    }
    refresh();
  });
  refresh();
  return {
    el: el(
      "div",
      { class: "control" },
      el("div", { class: "row" }, select),
      rem.el
    ),
    refresh
  };
}
function buildBorderControl(token, ctx) {
  const parse = () => {
    const parts = splitOutsideParentheses(ctx.value(), " ").filter(Boolean);
    const style2 = parts.find((part) => BORDER_STYLES2.includes(part)) ?? "solid";
    const width2 = parts.find((part) => PX.test(part) || TOKEN_ONLY.test(part)) ?? "1px";
    const color2 = parts.filter((part) => part !== style2 && part !== width2).join(" ") || "currentcolor";
    return { width: width2, style: style2, color: color2 };
  };
  const compose = ({ width: width2, style: style2, color: color2 }) => `${width2} ${style2} ${color2}`;
  const width = el("input", {
    type: "number",
    min: "0",
    step: "1",
    "aria-label": `${token.name} width`
  });
  const widthText = el("input", {
    type: "text",
    size: "16",
    "aria-label": `${token.name} width`
  });
  const style = el("select", { "aria-label": `${token.name} style` });
  BORDER_STYLES2.forEach(
    (name) => style.append(el("option", { value: name, text: name }))
  );
  const color = buildColorSourceControl(ctx, {
    read: () => parse().color,
    write: (value) => ctx.set(compose({ ...parse(), color: value }))
  });
  const refresh = () => {
    const current = parse();
    const [, px] = current.width.match(PX) ?? [];
    width.hidden = px === void 0;
    widthText.hidden = px !== void 0;
    width.value = px ?? "";
    widthText.value = current.width;
    style.value = current.style;
    color.refresh();
  };
  width.addEventListener(
    "change",
    () => ctx.set(compose({ ...parse(), width: `${width.value}px` }))
  );
  widthText.addEventListener(
    "change",
    () => ctx.set(compose({ ...parse(), width: widthText.value.trim() }))
  );
  style.addEventListener(
    "change",
    () => ctx.set(compose({ ...parse(), style: style.value }))
  );
  refresh();
  return {
    el: el(
      "div",
      { class: "control" },
      el(
        "div",
        { class: "row" },
        width,
        widthText,
        el("span", { class: "k", text: "px" }),
        style
      ),
      color.el
    ),
    refresh
  };
}
function buildShadowControl(token, ctx) {
  const parse = () => {
    const parts = splitOutsideParentheses(ctx.value(), " ").filter(Boolean);
    const colorIndex = parts.findIndex(
      (part) => !PX.test(part) && part !== "0" && part !== "inset"
    );
    const color2 = colorIndex === -1 ? "currentcolor" : parts[colorIndex];
    const lengths = parts.filter(
      (part, index) => index !== colorIndex && part !== "inset"
    );
    return {
      color: color2,
      colorFirst: colorIndex === 0,
      inset: parts.includes("inset"),
      lengths: lengths.map((part) => Number(part.replace("px", "")))
    };
  };
  const compose = ({ color: color2, colorFirst, inset, lengths }) => {
    const offsets = lengths.map((value) => value === 0 ? "0" : `${value}px`).join(" ");
    return [
      inset ? "inset" : "",
      colorFirst ? color2 : "",
      offsets,
      colorFirst ? "" : color2
    ].filter(Boolean).join(" ");
  };
  const labels = ["x", "y", "blur", "spread"];
  const inputs = labels.map(
    (label) => el("input", {
      type: "number",
      step: "1",
      "aria-label": `${token.name} ${label}`
    })
  );
  const rows = inputs.map(
    (input, index) => channelRow(
      labels[index],
      input,
      el("span", { class: "k", text: "px" })
    )
  );
  const color = buildColorSourceControl(ctx, {
    read: () => parse().color,
    write: (value) => ctx.set(compose({ ...parse(), color: value }))
  });
  const refresh = () => {
    const { lengths } = parse();
    rows.forEach((row, index) => {
      row.hidden = index >= Math.max(lengths.length, 2);
      inputs[index].value = String(
        lengths[index] ?? 0
      );
    });
    color.refresh();
  };
  inputs.forEach(
    (input, index) => input.addEventListener("change", () => {
      const current = parse();
      while (current.lengths.length <= index) {
        current.lengths.push(0);
      }
      current.lengths[index] = Number(input.value);
      ctx.set(compose(current));
    })
  );
  refresh();
  return { el: el("div", { class: "control" }, ...rows, color.el), refresh };
}
function buildFontControl(token, ctx) {
  const text = el("input", {
    type: "text",
    "aria-label": `${token.name} family`
  });
  const status = el("span", { class: "status" });
  const refresh = () => {
    text.value = ctx.value();
    const family = asFontFamily(text.value.trim());
    const missing = family !== "" && !isFontAvailable(family);
    status.textContent = missing ? "Not available in this browser: shown in the fallback font." : "Available in this browser.";
    status.classList.toggle("missing", missing);
  };
  text.addEventListener("change", () => {
    const value = text.value.trim();
    if (value !== "") {
      ctx.set(asFontFamily(value));
    }
    refresh();
  });
  refresh();
  return {
    el: el(
      "div",
      { class: "control" },
      el("div", { class: "row" }, text),
      status
    ),
    refresh
  };
}
function buildTypographyControl(token, ctx) {
  const range = rangeFor(token, ctx.ranges);
  if (!range) {
    return buildTextControl(token, ctx);
  }
  if (range.unit === "rem") {
    return buildRemControl(token, ctx, range);
  }
  return buildNumberControl(token, ctx, range);
}
function buildScopedControl(token, ctx) {
  const control = token.scoped ? scopedControlOf(token.scoped) : { min: 0, max: 0, step: 1 };
  return buildNumberControl(token, ctx, { ...control, unit: "px" });
}
function buildNumberControl(token, ctx, { min, max, step, unit }) {
  const pattern = unit === "" ? NUMBER2 : new RegExp(`^(-?\\d*\\.?\\d+)${escape(unit)}$`);
  const slide = slider(min, max, step);
  const number = el("input", {
    type: "number",
    min: String(min),
    max: String(max),
    step: String(step),
    "aria-label": token.name
  });
  const paint = (value) => {
    slide.value = String(value);
    number.value = String(value);
  };
  const refresh = () => {
    const [, value] = ctx.value().match(pattern) ?? [];
    paint(value === void 0 ? min : Number(value));
  };
  const write = (value) => {
    ctx.set(`${round2(value)}${unit}`);
    paint(round2(value));
  };
  slide.addEventListener("input", () => write(Number(slide.value)));
  number.addEventListener("change", () => write(Number(number.value)));
  refresh();
  return {
    el: el(
      "div",
      { class: "control" },
      el(
        "div",
        { class: "row" },
        slide,
        number,
        unit ? el("span", { class: "k", text: unit }) : null
      )
    ),
    refresh
  };
}
function buildTypeScaleControl(typeScale, ctx) {
  const base = el("input", {
    type: "number",
    min: "0.5",
    max: "2",
    step: "0.0625",
    "aria-label": "Base size"
  });
  const ratio = el("input", {
    type: "number",
    min: "1",
    max: "1.5",
    step: "0.01",
    value: "1.2",
    "aria-label": "Scale ratio"
  });
  const refresh = () => {
    base.value = String(remOf(ctx.value(typeScale.base)) ?? 1);
  };
  const apply = () => ctx.setMany(
    typeScaleValues(typeScale, Number(base.value), Number(ratio.value))
  );
  refresh();
  return {
    el: el(
      "div",
      { class: "group-controls" },
      el(
        "div",
        { class: "row" },
        el(
          "label",
          {},
          "Base ",
          base,
          el("span", { class: "k", text: "rem" })
        ),
        el("label", {}, "Ratio ", ratio),
        el("button", {
          type: "button",
          text: "Write the scale",
          onclick: apply
        })
      ),
      el("span", {
        class: "note",
        text: `Writes every step from ${typeScale.steps[0]} to ${typeScale.steps[typeScale.steps.length - 1]} as base \xD7 ratio to its distance from ${typeScale.base}.`
      })
    ),
    refresh
  };
}
function buildSeedControl(seed, ctx) {
  const hue = slider(0, 360, 1);
  const lightness = slider(0.1, 0.95, 0.01);
  const chroma = slider(0, 0.2, 5e-3);
  const readouts = [
    el("span", { class: "v" }),
    el("span", { class: "v" }),
    el("span", { class: "v" })
  ];
  const paint = () => {
    readouts[0].textContent = `${Math.round(Number(hue.value))}\xB0`;
    readouts[1].textContent = Number(lightness.value).toFixed(2);
    readouts[2].textContent = Number(chroma.value).toFixed(3);
  };
  const refresh = () => {
    const { h, c } = oklchOf(themeSideOf(ctx.value(seed.hue), ctx.theme()));
    const { l } = oklchOf(
      themeSideOf(ctx.value(seed.lightness), ctx.theme())
    );
    hue.value = String(h);
    lightness.value = String(l);
    chroma.value = String(Math.min(0.2, c));
    paint();
  };
  const apply = () => {
    const l = Number(lightness.value);
    const values = {
      h: Number(hue.value),
      l,
      c: Number(chroma.value),
      dark: l < 0.5
    };
    ctx.setMany(
      Object.fromEntries(
        Object.entries(seed.targets).map(([name, formula]) => {
          const color = formula(values);
          const text = OWN_COLOR.test(color) ? hexOf(color) : color;
          return [
            name,
            withThemeColor(ctx.value(name), ctx.theme(), text)
          ];
        })
      )
    );
  };
  [hue, lightness, chroma].forEach(
    (input) => input.addEventListener("input", paint)
  );
  refresh();
  return {
    el: el(
      "div",
      { class: "group-controls" },
      channelRow("H", hue, readouts[0]),
      channelRow("L", lightness, readouts[1]),
      channelRow("C", chroma, readouts[2]),
      el(
        "div",
        { class: "row" },
        el("button", {
          type: "button",
          text: `Seed ${Object.keys(seed.targets).length} tokens`,
          onclick: apply
        })
      )
    ),
    refresh
  };
}
function buildExtraCard(control, ctx) {
  const unit = control.unit ?? "";
  const range = el("input", {
    type: "range",
    min: String(control.min),
    max: String(control.max),
    step: String(control.step),
    "aria-label": control.label
  });
  const readout = el("span", { class: "v" });
  const note = el("div", { class: "note" });
  const card = el(
    "div",
    { class: "card", "data-extra": control.id },
    el(
      "div",
      { class: "name" },
      el("span", { class: "dot" }),
      el("code", { text: control.label }),
      el("button", {
        type: "button",
        class: "quiet reset",
        title: "Back to the default",
        text: "\u21BA",
        onclick: () => {
          if (isAvailable(control)) {
            control.set(control.default);
          }
          ctx.reset();
        }
      })
    ),
    note,
    el(
      "div",
      { class: "control" },
      el("div", { class: "row" }, range, readout)
    )
  );
  const refresh = () => {
    const live = isAvailable(control);
    const value = ctx.value() ?? (live ? control.get() : control.default);
    range.value = String(value);
    range.disabled = !live;
    readout.textContent = `${value}${unit}`;
    card.classList.toggle("changed", ctx.changed());
    note.hidden = live;
    note.textContent = live ? "" : control.unavailableNote ?? "Not available on this page.";
  };
  range.addEventListener("input", () => {
    readout.textContent = `${range.value}${unit}`;
    control.set(Number(range.value));
  });
  range.addEventListener("change", () => ctx.set(Number(range.value)));
  refresh();
  return { el: card, refresh };
}
function isAvailable(control) {
  try {
    return control.available ? control.available() : true;
  } catch {
    return false;
  }
}
function buildTextControl(token, ctx) {
  const text = el("input", { type: "text", "aria-label": token.name });
  const refresh = () => {
    text.value = ctx.value();
  };
  text.addEventListener("change", () => {
    if (text.value.trim() !== "") {
      ctx.set(text.value.trim());
    }
    refresh();
  });
  refresh();
  return {
    el: el("div", { class: "control" }, el("div", { class: "row" }, text)),
    refresh
  };
}
function slider(min, max, step) {
  return el("input", {
    type: "range",
    min: String(min),
    max: String(max),
    step: String(step)
  });
}
function channelRow(label, input, readout) {
  return el(
    "div",
    { class: "row" },
    el("span", { class: "k", text: label }),
    input,
    readout
  );
}
function round2(value) {
  return Math.round(value * 1e4) / 1e4;
}
function escape(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
function rootFontSize() {
  return parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
}

// src/exports.ts
function buildPrompt({
  sections,
  store,
  fileName = DEFAULT_FILE_NAME,
  intro = defaultIntro(fileName),
  color
}) {
  const lines = [];
  sections.forEach(({ title, tokens }) => {
    const changed = tokens.filter((token) => store.isChanged(token.name));
    if (!changed.length) {
      return;
    }
    lines.push(`${title}:`);
    changed.forEach(
      (token) => lines.push(
        `- ${describeChange(token, store.valueOf(token.name) ?? "", color)}`
      )
    );
    lines.push("");
  });
  const others = [];
  sections.forEach(({ scoped, extras }) => {
    scoped.filter((token) => store.isChanged(token.name)).forEach((token) => {
      const value = store.valueOf(token.name) ?? "";
      const label = token.scoped?.exportLabel ?? `${token.name} on ${token.scoped?.selector ?? "its container"}`;
      others.push(
        `- ${label}: ${pixelsOf(value)} (the ${token.name} size)`
      );
    });
    extras.filter(
      (control) => isExtraChanged(store, control.id, control.default)
    ).forEach((control) => {
      const value = store.extras[control.id];
      others.push(
        `- ${control.exportLabel ?? control.label}: ${describeExtra(control, value)}`
      );
    });
  });
  if (others.length) {
    lines.push("Not root tokens:", ...others, "");
  }
  if (!lines.length) {
    return `No token differs from ${fileName}.`;
  }
  return [intro, "", ...lines].join("\n").trim();
}
function buildCss({
  sections,
  store,
  fileName = DEFAULT_FILE_NAME,
  indent = DEFAULT_INDENT
}) {
  const blocks = [];
  let located = false;
  sections.forEach(
    ({ tokens }) => tokens.forEach((token) => {
      if (!store.isChanged(token.name)) {
        return;
      }
      const before = token.sourceValue ?? token.declared;
      const lines = [
        `-${indent}${token.name}: ${before};`,
        `+${indent}${token.name}: ${store.valueOf(token.name)};`
      ];
      if (token.location) {
        located = true;
        lines.unshift(`/* ${token.location} */`);
      }
      blocks.push(lines.join("\n"));
    })
  );
  const notes = [];
  sections.forEach(({ scoped, extras }) => {
    scoped.filter((token) => store.isChanged(token.name)).forEach((token) => {
      const value = store.valueOf(token.name) ?? "";
      const label = token.scoped?.exportLabel;
      notes.push(
        label ? `/* ${token.name}: set ${label} to ${pixelsOf(value)} */` : `/* ${token.name} on ${token.scoped?.selector ?? "its container"}: ${value} */`
      );
    });
    extras.filter(
      (control) => isExtraChanged(store, control.id, control.default)
    ).forEach((control) => {
      const value = store.extras[control.id];
      notes.push(
        `/* ${control.exportLabel ?? control.label}: ${describeExtra(control, value)} */`
      );
    });
  });
  if (!blocks.length && !notes.length) {
    return `/* No token differs from ${fileName}. */`;
  }
  const where = located ? " (the file and line of the declaration above each)" : "";
  const body = [blocks.join("\n\n"), notes.join("\n")].filter(
    (part) => part !== ""
  );
  return [
    `/* ${fileName}: replace each - line with its + line${where} */`,
    "",
    body.join("\n\n")
  ].join("\n");
}
function buildSnapshotJson(store) {
  return store.exportSnapshot();
}
function isExtraChanged(store, id, defaultValue) {
  const value = store.extras[id];
  return value !== void 0 && value !== defaultValue;
}
function describeExtra(control, value) {
  if (control.describe) {
    return control.describe(value, control.default);
  }
  const unit = control.unit ?? "";
  return `${value}${unit} instead of ${control.default}${unit}`;
}
function pixelsOf(value) {
  const pixels = parseInt(value, 10);
  return Number.isNaN(pixels) ? value : String(pixels);
}
function describeChange(token, value, color) {
  const before = token.declared;
  const sides = changedSides(before, value);
  const side = sides.length === 1 ? ` (${sides[0]} side)` : sides.length === 2 ? " (both sides)" : "";
  return `${token.name}${side}: ${phraseFor(token, before, value, sides, color)}; from \`${before}\` to \`${value}\``;
}
function changedSides(before, after) {
  const a = themeColorsOf(before);
  const b = themeColorsOf(after);
  if (!a || !b) {
    return [];
  }
  return THEMES.filter((_theme, index) => a[index] !== b[index]);
}
function phraseFor(token, before, after, sides, color) {
  if (token.kind === "color") {
    const theme = sides[0];
    const index = theme ? THEMES.indexOf(theme) : -1;
    const from = index === -1 ? before : themeColorsOf(before)?.[index] ?? before;
    const to = index === -1 ? after : themeColorsOf(after)?.[index] ?? after;
    return recipePhrase(from, to) ?? colorPhrase(from, to, color);
  }
  if (token.kind === "length" || token.kind === "typography") {
    return lengthPhrase(before, after);
  }
  return "changed";
}
var HUE_NAMES = [
  [20, "red"],
  [50, "orange"],
  [90, "yellow"],
  [150, "green"],
  [200, "teal"],
  [260, "blue"],
  [300, "purple"],
  [340, "magenta"],
  [360, "red"]
];
function colorPhrase(from, to, color) {
  if (!color) {
    return "changed";
  }
  const isColor2 = color.isColor ?? (() => true);
  if (!isColor2(from) || !isColor2(to)) {
    return "changed";
  }
  const a = color.oklchOf(from);
  const b = color.oklchOf(to);
  const parts = [];
  const dl = b.l - a.l;
  const dc = b.c - a.c;
  if (Math.abs(dl) >= 0.02) {
    parts.push(
      `${Math.abs(dl) >= 0.15 ? "much " : Math.abs(dl) < 0.05 ? "slightly " : ""}${dl > 0 ? "lighter" : "darker"}`
    );
  }
  if (Math.abs(dc) >= 0.01) {
    parts.push(
      `${Math.abs(dc) < 0.03 ? "slightly " : ""}${dc > 0 ? "more" : "less"} saturated`
    );
  }
  if (b.c >= 0.02 && (a.c < 0.02 || hueDistance(a.h, b.h) >= 12)) {
    parts.push(
      `${a.c < 0.02 ? "tinted" : "hue shifted"} toward ${hueName(b.h)}`
    );
  }
  if (Math.abs(b.alpha - a.alpha) >= 0.02) {
    parts.push(
      `${b.alpha > a.alpha ? "more" : "less"} opaque (alpha ${b.alpha.toFixed(2)})`
    );
  }
  const phrase = parts.length ? parts.join(", ") : "the same color, written differently";
  return `${phrase} (${color.hexOf(to)})`;
}
function hueDistance(a, b) {
  const d = Math.abs(a - b) % 360;
  return d > 180 ? 360 - d : d;
}
function hueName(hue) {
  return HUE_NAMES.find(([upTo]) => hue < upTo)?.[1] ?? "red";
}
var NUMBER_WITH_UNIT = /^(-?\d*\.?\d+)(rem|px|em)?$/;
function lengthPhrase(before, after) {
  const [, a, unitA = ""] = before.trim().match(NUMBER_WITH_UNIT) ?? [];
  const [, b, unitB = ""] = after.trim().match(NUMBER_WITH_UNIT) ?? [];
  if (a === void 0 || b === void 0 || unitA !== unitB) {
    return "changed";
  }
  const delta = Number(b) - Number(a);
  const ratio = Number(a) === 0 ? 0 : Number(b) / Number(a);
  const size = ratio >= 1.5 || ratio <= 0.66 ? "much " : Math.abs(ratio - 1) < 0.15 ? "slightly " : "";
  const direction = delta > 0 ? "larger" : "smaller";
  return `${size}${direction} (${delta > 0 ? "+" : ""}${Math.round(delta * 1e4) / 1e4}${unitA})`;
}

// src/inspect.ts
var STATE_PSEUDOS = /::[\w-]+(?:\([^)]*\))?|:(?:hover|focus|focus-visible|focus-within|active|visited|popover-open)/g;
var CONSUMED_EVENTS = [
  "pointerdown",
  "pointerup",
  "mousedown",
  "mouseup",
  "click"
];
var Inspector = class {
  active = false;
  options;
  handler;
  constructor(options) {
    this.options = options;
    this.handler = (event) => this.handleEvent(event);
  }
  start() {
    if (this.active) {
      return;
    }
    this.active = true;
    document.documentElement.style.cursor = "crosshair";
    CONSUMED_EVENTS.forEach(
      (type) => document.addEventListener(type, this.handler, true)
    );
    document.addEventListener("keydown", this.handler, true);
  }
  stop() {
    if (!this.active) {
      return;
    }
    this.active = false;
    document.documentElement.style.cursor = "";
    CONSUMED_EVENTS.forEach(
      (type) => document.removeEventListener(type, this.handler, true)
    );
    document.removeEventListener("keydown", this.handler, true);
    this.options.onLeave();
  }
  handleEvent(event) {
    if (event.type === "keydown") {
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        this.stop();
      }
      return;
    }
    const target = event.composedPath()[0];
    if (this.options.isOwn(target)) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    if (event.type === "click" && target instanceof Element) {
      this.pick(target);
    }
  }
  pick(element) {
    this.options.outline(element.getBoundingClientRect());
    const tokens = tokensReferencedBy(
      element,
      this.options.sheets(),
      this.options.declaredOf
    );
    this.stop();
    this.options.onPick({ element, tokens, label: describe(element) });
  }
};
function tokensReferencedBy(element, sheets, declaredOf) {
  const names = /* @__PURE__ */ new Set();
  const visit = (rules) => {
    Array.from(rules).forEach((rule) => {
      if (!(rule instanceof CSSStyleRule)) {
        if (rule instanceof CSSGroupingRule) {
          visit(rule.cssRules);
        }
        return;
      }
      if (!matchesOrAncestor(element, rule.selectorText)) {
        return;
      }
      referencedNamesOf(rule.style.cssText).forEach(
        (name) => names.add(name)
      );
      visit(rule.cssRules);
    });
  };
  sheets.forEach((sheet2) => {
    try {
      visit(sheet2.cssRules);
    } catch {
    }
  });
  const queue = [...names];
  while (queue.length) {
    const name = queue.pop();
    referencedNamesOf(declaredOf(name) ?? "").forEach((referenced) => {
      if (!names.has(referenced)) {
        names.add(referenced);
        queue.push(referenced);
      }
    });
  }
  return names;
}
function matchesOrAncestor(element, selectorText) {
  const selector = selectorText.replace(STATE_PSEUDOS, "");
  try {
    return element.closest(selector) !== null;
  } catch {
    return false;
  }
}
function describe(element) {
  const id = element.id ? `#${element.id}` : "";
  const classes = Array.from(element.classList).slice(0, 3).map((name) => `.${name}`).join("");
  return `${element.tagName.toLowerCase()}${id}${classes}`;
}

// src/panel.css.ts
var PANEL_CSS = `
:host {
    /* The panel's own palette: fixed literals, never the page's tokens */
    --tt-bg: #1b1c21;
    --tt-bg-raised: #26272e;
    --tt-fg: #e9e9ec;
    --tt-muted: #9a9ca8;
    --tt-accent: #7b96ff;
    --tt-changed: #f0b64b;
    --tt-border: #363841;
    --tt-font: system-ui, -apple-system, "Segoe UI", sans-serif;
    --tt-mono: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
    --tt-size: 12px;
    --tt-gap: 6px;
    --tt-pad: 10px;
    --tt-radius: 4px;
    --tt-width: 26rem;
    --tt-height: 45vh;

    /* The inherited text properties, pinned so the page's do not reach in */
    font: 400 var(--tt-size) / 1.4 var(--tt-font);
    letter-spacing: normal;
    color: var(--tt-fg);
    color-scheme: dark;

    /* A popover in the top layer; the UA's centering and frame are replaced by the dock */
    position: fixed;
    inset: auto;
    margin: 0;
    padding: 0;
    border: 0;
    width: var(--tt-width);
    max-width: 100vw;
    height: 100vh;
    max-height: 100vh;
    overflow: hidden;
    background: var(--tt-bg);
    box-shadow: 0 0 0 1px var(--tt-border), 0 8px 30px rgb(0 0 0 / 0.5);
    display: grid;
    grid-template-rows: auto auto minmax(0, 1fr);
}
:host([data-dock="right"]) { top: 0; right: 0; }
:host([data-dock="left"]) { top: 0; left: 0; }
:host([data-dock="bottom"]) {
    left: 0;
    right: 0;
    bottom: 0;
    width: auto;
    height: var(--tt-height);
}
:host([data-collapsed]) {
    height: auto;
    width: auto;
    max-width: 100vw;
    grid-template-rows: auto;
}
:host([data-collapsed][data-dock="right"]) { top: auto; bottom: 0; }
:host([data-collapsed][data-dock="left"]) { top: auto; bottom: 0; }
:host([data-collapsed]) .tabs,
:host([data-collapsed]) .tab,
:host([data-collapsed]) header .full { display: none; }
:host(:not([data-collapsed])) header .strip { display: none; }

* { box-sizing: border-box; }
/* The panel's own display rules would otherwise beat the attribute */
[hidden] { display: none !important; }

header {
    display: flex;
    align-items: center;
    gap: var(--tt-gap);
    padding: var(--tt-gap) var(--tt-pad);
    border-bottom: 1px solid var(--tt-border);
    background: var(--tt-bg-raised);
}
header strong { font-weight: 600; margin-right: auto; white-space: nowrap; }
header .strip { color: var(--tt-muted); white-space: nowrap; }
header .group { display: inline-flex; gap: 2px; }

button, select, input, textarea {
    font: inherit;
    color: inherit;
    letter-spacing: inherit;
}
button {
    background: var(--tt-bg-raised);
    border: 1px solid var(--tt-border);
    border-radius: var(--tt-radius);
    padding: 2px 7px;
    cursor: pointer;
    white-space: nowrap;
}
button:hover { border-color: var(--tt-muted); }
button[aria-pressed="true"], button.active { background: var(--tt-accent); color: #0d0f18; border-color: var(--tt-accent); }
button.quiet { background: none; border-color: transparent; color: var(--tt-muted); padding: 2px 4px; }
button.quiet:hover { color: var(--tt-fg); }
button:disabled { opacity: 0.4; cursor: default; }
input[type="text"], input[type="number"], select, textarea {
    background: var(--tt-bg);
    border: 1px solid var(--tt-border);
    border-radius: var(--tt-radius);
    padding: 2px 5px;
    min-width: 0;
}
input[type="text"], textarea { font-family: var(--tt-mono); }
input[type="number"] { width: 5.5em; }
input[type="range"] { flex: 1 1 4em; min-width: 3em; accent-color: var(--tt-accent); margin: 0; }
input[type="color"] { width: 2em; height: 1.6em; padding: 0; border: 1px solid var(--tt-border); background: none; }
input[type="checkbox"] { accent-color: var(--tt-accent); margin: 0 4px 0 0; }
textarea { width: 100%; min-height: 6em; resize: vertical; }
label { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }

.tabs { display: flex; gap: 2px; padding: var(--tt-gap) var(--tt-pad) 0; border-bottom: 1px solid var(--tt-border); }
.tabs button { border-bottom: 0; border-radius: var(--tt-radius) var(--tt-radius) 0 0; }
.tab { overflow: auto; padding: var(--tt-pad); display: none; }
.tab.shown { display: block; }

.toolbar { display: flex; flex-wrap: wrap; gap: var(--tt-gap); align-items: center; margin-bottom: var(--tt-pad); }
.toolbar input[type="text"] { flex: 1 1 8em; }
.chip { display: inline-flex; gap: 4px; align-items: center; border: 1px solid var(--tt-accent); border-radius: 1em; padding: 0 8px; color: var(--tt-accent); }

section.group { margin-bottom: var(--tt-pad); }
section.group > h3 {
    display: flex;
    align-items: center;
    gap: var(--tt-gap);
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--tt-muted);
    margin: var(--tt-pad) 0 var(--tt-gap);
    border-bottom: 1px solid var(--tt-border);
    padding-bottom: 2px;
}
section.group > h3 .reset { margin-left: auto; }
section.group[hidden] { display: none; }
.group-controls {
    display: grid;
    gap: var(--tt-gap);
    padding: var(--tt-gap);
    margin-bottom: var(--tt-gap);
    border: 1px dashed var(--tt-border);
    border-radius: var(--tt-radius);
}

.card {
    display: grid;
    gap: 4px;
    padding: var(--tt-gap) 0 var(--tt-gap) var(--tt-pad);
    border-left: 2px solid transparent;
    border-bottom: 1px solid var(--tt-border);
}
.card[hidden] { display: none; }
.card.changed { border-left-color: var(--tt-changed); }
.card .name { display: flex; align-items: center; gap: var(--tt-gap); }
.card .name code { font-family: var(--tt-mono); font-size: 11px; }
.card .name .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--tt-changed); visibility: hidden; }
.card.changed .name .dot { visibility: visible; }
.card .name .reset { margin-left: auto; }
.card .note { color: var(--tt-muted); font-size: 11px; }
.card .value { font-family: var(--tt-mono); font-size: 11px; color: var(--tt-muted); overflow-wrap: anywhere; }
.card .status { font-size: 11px; color: var(--tt-muted); }
.card .status.missing { color: var(--tt-changed); }

.control { display: grid; gap: 3px; }
.row { display: flex; align-items: center; gap: var(--tt-gap); flex-wrap: wrap; }
.row > span.k { color: var(--tt-muted); width: 1.2em; text-align: right; font-family: var(--tt-mono); font-size: 11px; }
.row > span.v { font-family: var(--tt-mono); font-size: 11px; width: 4.5em; }
.row > input[type="text"] { flex: 1 1 6em; }
.swatch { width: 1.6em; height: 1.6em; border-radius: var(--tt-radius); border: 1px solid var(--tt-border); flex: none; }
/* The color control's two views: the rows of the shown one lay out in the control's own grid */
.control.color > .view { display: contents; }
.row > span.fn, .row > span.oklch { font-family: var(--tt-mono); font-size: 11px; color: var(--tt-muted); white-space: nowrap; }
.row > select.mode { flex: none; padding: 1px 2px; font-size: 11px; }
.row > select.source { flex: 1 1 8em; }
.row > button.detach { margin-left: auto; }
.sample {
    background: var(--tt-bg-raised);
    border-radius: var(--tt-radius);
    padding: 4px 6px;
    color: var(--tt-fg);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

table { border-collapse: collapse; width: 100%; }
th, td { text-align: left; vertical-align: top; padding: 3px 6px 3px 0; border-bottom: 1px solid var(--tt-border); }
th { color: var(--tt-muted); font-weight: 600; font-size: 11px; }
td code { font-family: var(--tt-mono); font-size: 11px; }
details summary { cursor: pointer; color: var(--tt-accent); }
.locations div { font-family: var(--tt-mono); font-size: 11px; color: var(--tt-muted); }

.snapshot { display: flex; flex-wrap: wrap; align-items: center; gap: var(--tt-gap); padding: var(--tt-gap) 0; border-bottom: 1px solid var(--tt-border); }
.snapshot .title { font-weight: 600; margin-right: auto; }
.snapshot.active .title::before { content: "\u25CF "; color: var(--tt-accent); }
.snapshot .count { color: var(--tt-muted); }

.export { margin-bottom: var(--tt-pad); }
.export h3 { display: flex; align-items: center; gap: var(--tt-gap); font-size: 12px; margin: 0 0 var(--tt-gap); }
.export h3 button { margin-left: auto; }
.export pre {
    font-family: var(--tt-mono);
    font-size: 11px;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    background: var(--tt-bg-raised);
    border: 1px solid var(--tt-border);
    border-radius: var(--tt-radius);
    padding: var(--tt-gap);
    margin: 0;
    max-height: 40vh;
    overflow: auto;
}

p.hint { color: var(--tt-muted); margin: 0 0 var(--tt-gap); }

/* The outline of the element picked by Inspect: drawn over the page from the top layer */
.inspect-outline {
    position: fixed;
    pointer-events: none;
    border: 2px solid var(--tt-accent);
    background: rgb(123 150 255 / 0.12);
    display: none;
}
.inspect-outline.shown { display: block; }
`;
var sheet = null;
function panelSheet() {
  if (!sheet) {
    sheet = new CSSStyleSheet();
    sheet.replaceSync(PANEL_CSS);
  }
  return sheet;
}

// src/panel.ts
var TABS = [
  ["tokens", "Tokens"],
  ["snapshots", "Snapshots"],
  ["exports", "Export"],
  ["unreachable", "Not reachable"]
];
var DOCK_GLYPHS = {
  left: "\u25E7",
  right: "\u25E8",
  bottom: "\u2B13"
};
var HOLDS_LIGHT_DARK = /light-dark\(/;
function shownTheme(stored) {
  const scheme = themeOrStylesheets(stored);
  if (isTheme(scheme)) {
    return scheme;
  }
  const schemes = scheme.split(/\s+/);
  const light = schemes.includes("light");
  const dark = schemes.includes("dark");
  if (light && dark) {
    return matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }
  return dark ? "dark" : "light";
}
var Panel = class {
  host;
  shadow;
  store;
  tokens;
  tokensByName;
  sections;
  scale;
  typeScale;
  seed;
  config;
  sources;
  sheets;
  colorSourceList;
  extraDefaults;
  cards = /* @__PURE__ */ new Map();
  extraCards = /* @__PURE__ */ new Map();
  sectionEls = /* @__PURE__ */ new Map();
  refreshers = [];
  filters = { search: "", changedOnly: false, inspect: null };
  tab = "tokens";
  appliedExtras = {};
  inspector;
  unsubscribe;
  outlineEl;
  strip;
  inspectBtn;
  themeButtons;
  dockButtons;
  collapseBtn;
  tabButtons;
  searchInput;
  inspectChip;
  panes;
  exportBlocks = [];
  constructor(options) {
    const { store, config } = options;
    this.store = store;
    this.tokens = options.tokens;
    this.tokensByName = new Map(
      options.tokens.map((token) => [token.name, token])
    );
    this.sections = options.sections;
    this.scale = options.scale;
    this.typeScale = options.typeScale;
    this.seed = options.seed;
    this.config = config;
    this.sources = options.sources;
    this.sheets = options.sheets;
    this.colorSourceList = colorSources(options.tokens);
    this.extraDefaults = Object.fromEntries(
      config.extraControls.map((control) => [
        control.id,
        control.default
      ])
    );
    this.host = el("div", {
      id: config.hostId,
      popover: "manual",
      "data-dock": store.state.dock
    });
    this.host.toggleAttribute("data-collapsed", store.state.collapsed);
    this.shadow = this.host.attachShadow({ mode: "open" });
    this.shadow.adoptedStyleSheets = [panelSheet()];
    this.outlineEl = el("div", { class: "inspect-outline" });
    this.inspector = new Inspector({
      sheets: () => this.sheets,
      declaredOf: (name) => this.valueOf(name),
      isOwn: (target) => target instanceof Node && (this.host.contains(target) || this.shadow.contains(target)),
      outline: (rect) => this.outline(rect),
      onPick: ({ tokens, label }) => this.setInspectFilter(tokens, label),
      onLeave: () => this.inspectBtn.setAttribute("aria-pressed", "false")
    });
    this.strip = el("span", { class: "strip" });
    this.inspectBtn = el("button", {
      type: "button",
      class: "full",
      title: "Inspect: pick an element to see its tokens",
      "aria-pressed": "false",
      text: "\u2316",
      onclick: () => this.toggleInspect()
    });
    this.themeButtons = THEMES.map(
      (theme) => el("button", {
        type: "button",
        "data-theme": theme,
        "aria-pressed": String(this.theme === theme),
        text: theme.charAt(0).toUpperCase() + theme.slice(1),
        onclick: () => this.setTheme(theme)
      })
    );
    this.dockButtons = DOCKS.map(
      (dock) => el("button", {
        type: "button",
        title: `Dock ${dock}`,
        "data-dock": dock,
        "aria-pressed": String(store.state.dock === dock),
        text: DOCK_GLYPHS[dock],
        onclick: () => this.setDock(dock)
      })
    );
    this.collapseBtn = el("button", {
      type: "button",
      class: "quiet",
      title: "Collapse or expand",
      text: store.state.collapsed ? "\u25B4" : "\u25BE",
      onclick: () => this.setCollapsed(!this.host.hasAttribute("data-collapsed"))
    });
    const header = el(
      "header",
      {},
      el("strong", { text: config.title }),
      this.strip,
      el(
        "span",
        { class: "group full", hidden: !this.showsThemeToggle() },
        ...this.themeButtons
      ),
      el("span", { class: "group full" }, ...this.dockButtons),
      this.inspectBtn,
      this.collapseBtn
    );
    const tabIds = TABS.filter(
      ([id]) => id !== "unreachable" || this.sources !== null
    );
    this.tabButtons = new Map(
      tabIds.map(([id, label]) => [
        id,
        el("button", {
          type: "button",
          text: label,
          onclick: () => this.showTab(id)
        })
      ])
    );
    const tabs = el("nav", { class: "tabs" }, ...this.tabButtons.values());
    this.searchInput = el("input", {
      type: "text",
      placeholder: "Search tokens",
      "aria-label": "Search tokens",
      oninput: () => {
        this.filters.search = this.searchInput.value.trim().toLowerCase();
        this.applyFilters();
      }
    });
    this.inspectChip = el("span", { class: "chip", hidden: true });
    this.panes = {
      tokens: this.buildTokensTab(),
      snapshots: el("section", { class: "tab" }),
      exports: this.buildExportsTab()
    };
    if (this.sources) {
      this.panes.unreachable = this.buildUnreachableTab(this.sources);
    }
    this.shadow.append(
      header,
      tabs,
      ...Object.values(this.panes),
      this.outlineEl
    );
    this.renderSnapshots();
    this.showTab(this.tab);
    this.updateStrip();
    this.unsubscribe = store.subscribe(
      (change) => this.onStoreChange(change)
    );
  }
  // Reading values
  /** The current declared text of a token: its tweak, or the stylesheet's. */
  valueOf(name) {
    return this.store.valueOf(name) ?? this.tokensByName.get(name)?.declared;
  }
  /** The theme the page shows. */
  get theme() {
    return shownTheme(this.store.theme);
  }
  /** Whether the Light/Dark toggle shows: forced, or any root token holds `light-dark()`. */
  showsThemeToggle() {
    if (this.config.theme !== "auto") {
      return this.config.theme;
    }
    return this.tokens.some(
      (token) => token.kind !== "scoped" && HOLDS_LIGHT_DARK.test(token.declared)
    );
  }
  /** What a control reads and writes: see `controls.ts`. */
  contextFor(token) {
    return {
      value: (name = token?.name) => name === void 0 ? "" : this.valueOf(name) ?? "",
      theme: () => this.theme,
      set: (text) => {
        if (token) {
          this.store.set(token.name, text);
        }
      },
      setMany: (tweaks) => this.store.setMany(tweaks),
      colorSources: this.colorSourceList,
      scale: this.scale,
      ranges: this.config.ranges
    };
  }
  // Building
  buildTokensTab() {
    const changedOnly = el("input", {
      type: "checkbox",
      onchange: () => {
        this.filters.changedOnly = changedOnly.checked;
        this.applyFilters();
      }
    });
    const toolbar = el(
      "div",
      { class: "toolbar" },
      this.searchInput,
      el("label", {}, changedOnly, "Changed only"),
      el("button", {
        type: "button",
        text: "Reset all",
        onclick: () => this.store.resetAll()
      }),
      this.inspectChip
    );
    const sections = this.sections.map(
      (section) => this.buildSection(section)
    );
    return el("section", { class: "tab" }, toolbar, ...sections);
  }
  buildSection(section) {
    const names = [...section.tokens, ...section.scoped].map(
      (token) => token.name
    );
    const heading = el(
      "h3",
      {},
      section.title,
      el("button", {
        type: "button",
        class: "quiet reset",
        title: "Reset this section",
        text: "\u21BA section",
        onclick: () => this.store.resetMany(names)
      })
    );
    const children = [heading];
    const seed = this.seed;
    if (seed && names.includes(seed.hue)) {
      const control = buildSeedControl(seed, this.contextFor(null));
      this.refreshers.push(control.refresh);
      children.push(
        el(
          "details",
          {},
          el("summary", {
            text: "Seed the theme colors from one hue"
          }),
          control.el
        )
      );
    }
    if (this.typeScale && names.includes(this.typeScale.base)) {
      const control = buildTypeScaleControl(
        this.typeScale,
        this.contextFor(null)
      );
      this.refreshers.push(control.refresh);
      children.push(control.el);
    }
    section.tokens.forEach((token) => children.push(this.buildCard(token)));
    section.scoped.forEach((token) => children.push(this.buildCard(token)));
    section.extras.forEach((control) => {
      const card = buildExtraCard(control, {
        value: () => this.store.extraOf(control.id),
        set: (value) => this.store.setExtra(control.id, value),
        reset: () => this.store.resetExtra(control.id),
        changed: () => this.store.isExtraChanged(control.id)
      });
      this.extraCards.set(control.id, card);
      children.push(card.el);
    });
    const element = el(
      "section",
      { class: "group", "data-section": section.id },
      ...children
    );
    this.sectionEls.set(section.id, element);
    return element;
  }
  buildCard(token) {
    const control = buildControl(token, this.contextFor(token));
    const value = el("div", { class: "value" });
    const card = el(
      "div",
      { class: "card", "data-token": token.name },
      el(
        "div",
        { class: "name" },
        el("span", { class: "dot" }),
        el("code", { text: token.name }),
        token.registration ? el("span", {
          class: "note",
          text: `@property ${token.registration.syntax}`
        }) : null,
        el("button", {
          type: "button",
          class: "quiet reset",
          title: "Reset to the stylesheet's value",
          text: "\u21BA",
          onclick: () => {
            this.store.reset(token.name);
            control.refresh();
          }
        })
      ),
      value,
      control.el
    );
    const entry = { token, card, control, value };
    this.cards.set(token.name, entry);
    this.paintCard(entry);
    return card;
  }
  buildExportsTab() {
    const { fileName, indent, intro } = this.config.exports;
    const color = { oklchOf, hexOf, isColor };
    const blocks = [
      [
        "Prompt",
        () => buildPrompt({
          sections: this.sections,
          store: this.store,
          fileName,
          indent,
          intro,
          color
        })
      ],
      [
        `CSS for ${fileName}`,
        () => buildCss({
          sections: this.sections,
          store: this.store,
          fileName,
          indent,
          intro,
          color
        })
      ],
      ["Snapshot JSON", () => buildSnapshotJson(this.store)]
    ];
    const elements = blocks.map(([title, build2]) => {
      const pre = el("pre");
      this.exportBlocks.push({ pre, build: build2 });
      return el(
        "div",
        { class: "export" },
        el(
          "h3",
          {},
          title,
          el("button", {
            type: "button",
            text: "Copy",
            onclick: (event) => copyText(
              pre.textContent ?? "",
              event.currentTarget
            )
          })
        ),
        pre
      );
    });
    return el(
      "section",
      { class: "tab" },
      el("p", { class: "hint", text: "Regenerated on every change." }),
      ...elements
    );
  }
  renderExports() {
    this.exportBlocks.forEach(({ pre, build: build2 }) => {
      pre.textContent = build2();
    });
  }
  buildUnreachableTab(sources) {
    const colors = sources.literals;
    const lengths = sources.lengthLiterals;
    const locationsCell = (locations) => el(
      "td",
      {},
      el(
        "details",
        { class: "locations" },
        el("summary", { text: String(locations.length) }),
        ...locations.map(
          (location2) => el("div", { text: location2 })
        )
      )
    );
    return el(
      "section",
      { class: "tab" },
      el("p", {
        class: "hint",
        text: "Values written as literals in the stylesheets and the markup, which no token reaches. Each names its file and line."
      }),
      el("h3", { text: `Color literals (${colors.length})` }),
      el(
        "table",
        {},
        el(
          "tr",
          {},
          el("th", { text: "Literal" }),
          el("th", { text: "Uses" })
        ),
        ...colors.map(
          (literal) => el(
            "tr",
            {},
            el(
              "td",
              {},
              el("span", {
                class: "swatch",
                style: `background: ${literal.value}`
              }),
              " ",
              el("code", { text: literal.value })
            ),
            locationsCell(literal.locations)
          )
        )
      ),
      el("h3", { text: `Length literals (${lengths.length})` }),
      el(
        "table",
        {},
        el(
          "tr",
          {},
          el("th", { text: "Property" }),
          el("th", { text: "Literal" }),
          el("th", { text: "Uses" })
        ),
        ...lengths.map(
          (literal) => el(
            "tr",
            {},
            el("td", {}, el("code", { text: literal.property })),
            el("td", {}, el("code", { text: literal.value })),
            locationsCell(literal.locations)
          )
        )
      )
    );
  }
  renderSnapshots() {
    const store = this.store;
    const pane = this.panes.snapshots;
    if (!pane) {
      return;
    }
    pane.replaceChildren();
    const nameInput = el("input", {
      type: "text",
      placeholder: "Snapshot name",
      "aria-label": "Snapshot name",
      value: store.activeSnapshot ?? ""
    });
    const saveAs = () => {
      const name = nameInput.value.trim();
      if (name !== "") {
        store.saveSnapshot(name);
      }
    };
    nameInput.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        saveAs();
      }
    });
    pane.append(
      el(
        "div",
        { class: "toolbar" },
        nameInput,
        el("button", {
          type: "button",
          text: "Save as",
          onclick: saveAs
        }),
        el("button", {
          type: "button",
          class: "quiet",
          text: "Export current",
          title: "Download the current tweaks as a snapshot file",
          onclick: () => downloadText(
            `${store.activeSnapshot ?? "current"}.tokentweak.json`,
            store.exportSnapshot()
          )
        })
      )
    );
    if (!store.snapshots.length) {
      pane.append(
        el("p", {
          class: "hint",
          text: "No snapshots yet. Save the current tweaks under a name to keep and compare them."
        })
      );
    }
    store.snapshots.forEach(
      (snapshot) => pane.append(this.buildSnapshotRow(snapshot))
    );
    const abSelects = [0, 1].map((index) => {
      const select = el("select", {
        "aria-label": index === 0 ? "A" : "B"
      });
      select.append(
        el("option", {
          value: "",
          text: index === 0 ? "A: choose" : "B: choose"
        })
      );
      store.snapshots.forEach(
        ({ name }) => select.append(el("option", { value: name, text: name }))
      );
      select.value = store.ab[index] ?? "";
      select.addEventListener(
        "change",
        () => store.setAb(index, select.value || null)
      );
      return select;
    });
    const [a, b] = store.ab;
    pane.append(
      el("h3", { text: "A / B" }),
      el(
        "div",
        { class: "row" },
        ...abSelects,
        el("button", {
          type: "button",
          text: "Flip",
          disabled: !(a && b),
          onclick: () => store.flipAb()
        }),
        el("span", {
          class: "note",
          text: a && b ? `Showing ${store.activeSnapshot === b ? "B" : store.activeSnapshot === a ? "A" : "neither"}` : ""
        })
      )
    );
    const importText = el("textarea", {
      placeholder: "Paste a snapshot's JSON",
      "aria-label": "Snapshot JSON to import"
    });
    const importStatus = el("span", { class: "note" });
    const importJson = (json) => {
      const name = store.importSnapshot(json);
      importStatus.textContent = name === null ? "Not a snapshot." : `Imported as ${name}.`;
    };
    const file = el("input", {
      type: "file",
      accept: "application/json",
      "aria-label": "Snapshot file"
    });
    file.addEventListener("change", () => {
      file.files?.[0]?.text().then(importJson);
    });
    pane.append(
      el("h3", { text: "Import" }),
      importText,
      el(
        "div",
        { class: "row" },
        el("button", {
          type: "button",
          text: "Import",
          onclick: () => importJson(importText.value)
        }),
        file,
        importStatus
      )
    );
  }
  buildSnapshotRow(snapshot) {
    const store = this.store;
    const count = Object.keys(snapshot.tweaks).length + Object.entries(snapshot.extras).filter(
      ([id, value]) => value !== this.extraDefaults[id]
    ).length;
    const title = el("span", { class: "title", text: snapshot.name });
    const row = el("div", {
      class: `snapshot${store.activeSnapshot === snapshot.name ? " active" : ""}`
    });
    const rename = () => {
      const input = el("input", {
        type: "text",
        value: snapshot.name,
        "aria-label": "New name"
      });
      const commit = () => {
        if (!store.renameSnapshot(snapshot.name, input.value.trim())) {
          this.renderSnapshots();
        }
      };
      input.addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
          commit();
        } else if (event.key === "Escape") {
          this.renderSnapshots();
        }
      });
      input.addEventListener("blur", commit);
      title.replaceWith(input);
      input.focus();
      input.select();
    };
    row.append(
      title,
      el("span", {
        class: "count",
        text: `${count} changed${snapshot.theme ? `, ${snapshot.theme}` : ""}`
      }),
      el("button", {
        type: "button",
        text: "Load",
        onclick: () => store.loadSnapshot(snapshot.name)
      }),
      el("button", {
        type: "button",
        class: "quiet",
        text: "Rename",
        onclick: rename
      }),
      el("button", {
        type: "button",
        class: "quiet",
        text: "Duplicate",
        onclick: () => store.duplicateSnapshot(snapshot.name)
      }),
      el("button", {
        type: "button",
        class: "quiet",
        text: "Export",
        onclick: () => downloadText(
          `${snapshot.name}.tokentweak.json`,
          store.exportSnapshot(snapshot.name)
        )
      }),
      el("button", {
        type: "button",
        class: "quiet",
        text: "Delete",
        onclick: () => store.deleteSnapshot(snapshot.name)
      })
    );
    return row;
  }
  // Painting
  paintCard({ token, card, value }) {
    card.classList.toggle("changed", this.store.isChanged(token.name));
    value.textContent = this.valueOf(token.name) ?? "";
  }
  refreshAll() {
    this.cards.forEach((entry) => {
      entry.control.refresh();
      this.paintCard(entry);
    });
    this.refreshers.forEach((refresh) => refresh());
    this.extraCards.forEach((card) => card.refresh());
  }
  /**
   * Re-reads the controls of the tokens whose value derives from `name`, transitively: a
   * recipe over the changed token resolves to another color, which its swatch and readout show
   * at once rather than at the next whole-set refresh. The changed card itself is left alone,
   * since its control wrote the change and may be mid-drag.
   */
  refreshDerivedFrom(name) {
    const references = /* @__PURE__ */ new Map();
    this.cards.forEach((_, cardName) => {
      if (cardName !== name) {
        references.set(
          cardName,
          referencedNamesOf(this.valueOf(cardName) ?? "")
        );
      }
    });
    const derived = /* @__PURE__ */ new Set();
    const queue = [name];
    for (let next = queue.shift(); next !== void 0; next = queue.shift()) {
      references.forEach((names, cardName) => {
        if (!derived.has(cardName) && names.includes(next)) {
          derived.add(cardName);
          queue.push(cardName);
          this.cards.get(cardName)?.control.refresh();
        }
      });
    }
  }
  updateStrip() {
    const name = this.store.activeSnapshot;
    const parts = [`${this.store.changedCount} changed`];
    if (name) {
      parts.push(`${name}${this.store.activeSnapshotModified ? "*" : ""}`);
    }
    this.strip.textContent = parts.join(" \xB7 ");
  }
  applyFilters() {
    const { search, changedOnly, inspect } = this.filters;
    this.sections.forEach((section) => {
      let shown = 0;
      [...section.tokens, ...section.scoped].forEach((token) => {
        const entry = this.cards.get(token.name);
        if (!entry) {
          return;
        }
        const visible = (search === "" || token.name.includes(search)) && (!changedOnly || this.store.isChanged(token.name)) && (inspect === null || inspect.has(token.name));
        entry.card.hidden = !visible;
        shown += visible ? 1 : 0;
      });
      section.extras.forEach((control) => {
        const card = this.extraCards.get(control.id);
        if (!card) {
          return;
        }
        const visible = inspect === null && (!changedOnly || this.store.isExtraChanged(control.id)) && (search === "" || control.label.toLowerCase().includes(search) || control.id.toLowerCase().includes(search));
        card.el.hidden = !visible;
        shown += visible ? 1 : 0;
      });
      const element = this.sectionEls.get(section.id);
      if (element) {
        element.hidden = shown === 0;
      }
    });
  }
  onStoreChange(change) {
    if (change.kind === "tweak") {
      const entry = this.cards.get(change.name);
      if (entry) {
        this.paintCard(entry);
      }
      this.refreshDerivedFrom(change.name);
    } else if (change.kind === "tweaks" || change.kind === "theme") {
      if (change.kind === "tweaks") {
        this.applyExtras();
      }
      this.refreshAll();
      this.themeButtons.forEach(
        (button) => button.setAttribute(
          "aria-pressed",
          String(button.dataset.theme === this.theme)
        )
      );
    } else if (change.kind === "extra") {
      this.extraCards.get(change.id)?.refresh();
      this.appliedExtras = { ...this.store.extras };
    }
    if (change.kind !== "panel") {
      this.updateStrip();
      if (["tweaks", "snapshots", "theme"].includes(change.kind)) {
        this.renderSnapshots();
      }
      if (this.filters.changedOnly) {
        this.applyFilters();
      }
      if (this.tab === "exports") {
        this.renderExports();
      }
    }
  }
  /**
   * Brings the page's extra controls to the stored values, where the page has them: a stored
   * value is applied, and a control whose stored value went away (a reset, another snapshot)
   * is put back to its default. Called at `load`, and after every whole-set change.
   */
  applyExtras() {
    this.config.extraControls.forEach((control) => {
      if (!isAvailable(control)) {
        return;
      }
      const stored = this.store.extraOf(control.id);
      const target = stored ?? (control.id in this.appliedExtras ? control.default : void 0);
      if (target !== void 0 && control.get() !== target) {
        control.set(target);
      }
    });
    this.appliedExtras = { ...this.store.extras };
    this.extraCards.forEach((card) => card.refresh());
  }
  // Actions
  setTheme(theme) {
    applyTheme(theme);
    this.store.setTheme(theme);
  }
  setDock(dock) {
    this.host.setAttribute("data-dock", dock);
    this.dockButtons.forEach(
      (button) => button.setAttribute(
        "aria-pressed",
        String(button.dataset.dock === dock)
      )
    );
    this.store.setPanel({ dock });
  }
  setCollapsed(collapsed) {
    this.host.toggleAttribute("data-collapsed", collapsed);
    this.collapseBtn.textContent = collapsed ? "\u25B4" : "\u25BE";
    this.store.setPanel({ collapsed });
  }
  showTab(id) {
    if (!this.panes[id]) {
      return;
    }
    this.tab = id;
    this.tabButtons.forEach(
      (button, tabId) => button.setAttribute("aria-pressed", String(tabId === id))
    );
    Object.entries(this.panes).forEach(
      ([paneId, pane]) => pane.classList.toggle("shown", paneId === id)
    );
    if (id === "exports") {
      this.renderExports();
    }
  }
  toggleInspect() {
    if (this.inspector.active) {
      this.inspector.stop();
    } else {
      this.inspectBtn.setAttribute("aria-pressed", "true");
      this.inspector.start();
    }
  }
  setInspectFilter(tokens, label) {
    this.inspectBtn.setAttribute("aria-pressed", "false");
    this.filters.inspect = tokens;
    this.inspectChip.replaceChildren(
      `Inspecting ${label} (${tokens.size})`,
      el("button", {
        type: "button",
        class: "quiet",
        text: "\xD7",
        title: "Show every token",
        onclick: () => this.clearInspect()
      })
    );
    this.inspectChip.hidden = false;
    this.showTab("tokens");
    this.applyFilters();
    window.addEventListener("scroll", () => this.outline(null), {
      once: true,
      capture: true
    });
  }
  clearInspect() {
    this.filters.inspect = null;
    this.inspectChip.hidden = true;
    this.outline(null);
    this.applyFilters();
  }
  outline(rect) {
    const style = this.outlineEl.style;
    this.outlineEl.classList.toggle("shown", rect !== null);
    if (rect) {
      style.top = `${rect.top}px`;
      style.left = `${rect.left}px`;
      style.width = `${rect.width}px`;
      style.height = `${rect.height}px`;
    }
  }
  get isOpen() {
    return this.host.isConnected && this.host.matches(":popover-open");
  }
  open() {
    if (this.host.isConnected && !this.isOpen) {
      this.host.showPopover();
    }
    this.store.setPanel({ open: true });
  }
  close() {
    if (this.isOpen) {
      this.host.hidePopover();
    }
    this.inspector.stop();
    this.store.setPanel({ open: false });
  }
  toggle() {
    if (this.isOpen) {
      this.close();
    } else {
      this.open();
    }
  }
  /** Removes the panel from the page and stops listening; the stored state is left as it is. */
  destroy() {
    this.inspector.stop();
    if (this.isOpen) {
      this.host.hidePopover();
    }
    this.unsubscribe();
    this.host.remove();
  }
};

// src/sheet.ts
var MARKER_PROPERTY = "--tokentweak";
function sheetText({
  tweaks,
  scopedTokens = [],
  rootSelector = ":root",
  storageKey
}) {
  const selectors = new Map(
    scopedTokens.map((token) => [token.name, token.selector])
  );
  const root = [];
  const scoped = [];
  Object.entries(tweaks).forEach(([name, value]) => {
    const selector = selectors.get(name);
    if (selector !== void 0) {
      scoped.push(`${selector} { ${name}: ${value} !important; }`);
    } else {
      root.push(`    ${name}: ${value};`);
    }
  });
  return [
    `${rootSelector} { ${MARKER_PROPERTY}: ${storageKey}; }`,
    `${rootSelector} {`,
    ...root,
    `}`,
    ...scoped
  ].join("\n");
}
function isBootSheet(sheet2, storageKey) {
  const first = sheet2.cssRules[0];
  if (!isRuleLike(first)) {
    return false;
  }
  return first.style.getPropertyValue(MARKER_PROPERTY).trim() === storageKey;
}
function isRuleLike(rule) {
  if (typeof rule !== "object" || rule === null || !("style" in rule)) {
    return false;
  }
  const style = rule.style;
  return typeof style === "object" && style !== null && "getPropertyValue" in style && typeof style.getPropertyValue === "function";
}
function findBootSheet(storageKey) {
  if (typeof document === "undefined") {
    return null;
  }
  return document.adoptedStyleSheets.find((sheet2) => {
    try {
      return isBootSheet(sheet2, storageKey);
    } catch {
      return false;
    }
  }) ?? null;
}

// src/sources.ts
function normalizeSources(raw) {
  const record = isRecord(raw) ? raw : {};
  return {
    version: 1,
    rootTokens: entriesOf(record.rootTokens, readToken),
    literals: entriesOf(record.literals, readLiteral),
    lengthLiterals: entriesOf(record.lengthLiterals, readLengthLiteral)
  };
}
async function loadSources(input, fetchImpl = globalThis.fetch) {
  if (input === null || input === void 0) {
    return null;
  }
  if (typeof input === "function") {
    return normalizeSources(await input());
  }
  if (typeof input !== "string") {
    return normalizeSources(input);
  }
  if (!fetchImpl) {
    console.warn("tokentweak: no fetch, the sources were not loaded");
    return null;
  }
  try {
    const response = await fetchImpl(input, { credentials: "include" });
    if (!response.ok) {
      console.warn(
        `tokentweak: the sources at ${input} answered ${response.status}`
      );
      return null;
    }
    return normalizeSources(await response.json());
  } catch (error) {
    console.warn(
      `tokentweak: the sources at ${input} were not fetched`,
      error
    );
    return null;
  }
}
function entriesOf(value, read) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map((entry) => isRecord(entry) ? read(entry) : null).filter((entry) => entry !== null);
}
function readToken(entry) {
  if (typeof entry.name !== "string" || typeof entry.value !== "string" || typeof entry.location !== "string") {
    return null;
  }
  const token = {
    name: entry.name,
    value: entry.value,
    location: entry.location
  };
  if (typeof entry.note === "string") {
    token.note = entry.note;
  }
  return token;
}
function readLiteral(entry) {
  if (typeof entry.value !== "string") {
    return null;
  }
  return { value: entry.value, locations: stringsOf(entry.locations) };
}
function readLengthLiteral(entry) {
  if (typeof entry.property !== "string" || typeof entry.value !== "string") {
    return null;
  }
  return {
    property: entry.property,
    value: entry.value,
    locations: stringsOf(entry.locations)
  };
}
function stringsOf(value) {
  return Array.isArray(value) ? value.filter((entry) => typeof entry === "string") : [];
}
function isRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

// src/state.ts
var STATE_VERSION = 1;
var DEFAULT_STATE = Object.freeze({
  v: STATE_VERSION,
  tweaks: {},
  extras: {},
  snapshots: [],
  activeSnapshot: null,
  theme: null,
  dock: "right",
  open: false,
  collapsed: false,
  ab: [null, null],
  css: ""
});
function defaultState() {
  return {
    ...DEFAULT_STATE,
    tweaks: {},
    extras: {},
    snapshots: [],
    ab: [null, null]
  };
}
function readState(json) {
  const parsed = parseObject(json);
  if (!parsed) {
    return defaultState();
  }
  return {
    v: STATE_VERSION,
    tweaks: readTweaks(parsed.tweaks),
    extras: readExtras(parsed.extras),
    snapshots: Array.isArray(parsed.snapshots) ? parsed.snapshots.map((entry) => readSnapshot(entry)).filter((entry) => entry !== null) : [],
    activeSnapshot: typeof parsed.activeSnapshot === "string" ? parsed.activeSnapshot : null,
    theme: isTheme(parsed.theme) ? parsed.theme : null,
    dock: isDock(parsed.dock) ? parsed.dock : DEFAULT_STATE.dock,
    open: parsed.open === true,
    collapsed: parsed.collapsed === true,
    ab: readAb(parsed.ab),
    css: typeof parsed.css === "string" ? parsed.css : ""
  };
}
function readSnapshot(value) {
  if (!isRecord2(value) || !isTweaks(value.tweaks)) {
    return null;
  }
  return {
    name: typeof value.name === "string" && value.name !== "" ? value.name : "Imported",
    theme: isTheme(value.theme) ? value.theme : null,
    tweaks: { ...value.tweaks },
    extras: readExtras(value.extras)
  };
}
function isTweaks(value) {
  return isRecord2(value) && Object.entries(value).every(
    ([name, text]) => name.startsWith("--") && typeof text === "string"
  );
}
function isExtras(value) {
  return isRecord2(value) && Object.values(value).every(
    (entry) => typeof entry === "number" && Number.isFinite(entry)
  );
}
function copyName(name, existing, preferOwn = false) {
  const taken = new Set(existing.map((snapshot) => snapshot.name));
  if (preferOwn && !taken.has(name)) {
    return name;
  }
  let copy = 2;
  while (taken.has(`${name} (${copy})`)) {
    copy++;
  }
  return `${name} (${copy})`;
}
function parseObject(json) {
  if (json === null) {
    return null;
  }
  try {
    const parsed = JSON.parse(json);
    return isRecord2(parsed) ? parsed : null;
  } catch {
    return null;
  }
}
function isRecord2(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function isDock(value) {
  return DOCKS.includes(value);
}
function readTweaks(value) {
  if (!isRecord2(value)) {
    return {};
  }
  return Object.fromEntries(
    Object.entries(value).filter(
      ([name, text]) => name.startsWith("--") && typeof text === "string"
    )
  );
}
function readExtras(value) {
  if (!isRecord2(value)) {
    return {};
  }
  return Object.fromEntries(
    Object.entries(value).filter(
      ([, entry]) => typeof entry === "number" && Number.isFinite(entry)
    )
  );
}
function readAb(value) {
  if (!Array.isArray(value) || value.length !== 2) {
    return [null, null];
  }
  const side = (entry) => typeof entry === "string" ? entry : null;
  return [side(value[0]), side(value[1])];
}

// src/store.ts
var TweaksStore = class {
  state;
  listeners = /* @__PURE__ */ new Set();
  storage;
  sheet;
  storageKey;
  rootSelector;
  scopedTokens;
  extraDefaults;
  constructor(options) {
    this.storage = options.storage;
    this.sheet = options.sheet;
    this.storageKey = options.storageKey ?? "tokentweak";
    this.rootSelector = options.rootSelector ?? ":root";
    this.scopedTokens = options.scopedTokens ?? [];
    this.extraDefaults = options.extraDefaults ?? {};
    this.state = readState(readStored(options.storage));
    this.state.css = this.sheetText();
    this.sheet.replace(this.state.css);
  }
  /** `listener(change)` is called after every change; the return value unsubscribes. */
  subscribe(listener) {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }
  get tweaks() {
    return this.state.tweaks;
  }
  get extras() {
    return this.state.extras;
  }
  get theme() {
    return this.state.theme;
  }
  /** The tweaked declared text of `name`, or undefined while the token stands as declared. */
  valueOf(name) {
    return this.state.tweaks[name];
  }
  isChanged(name) {
    return name in this.state.tweaks;
  }
  /** The value of the extra control `id`, or undefined while it stands at its default. */
  extraOf(id) {
    return this.state.extras[id];
  }
  /** Whether the extra control `id` holds a value other than its default. */
  isExtraChanged(id) {
    const value = this.state.extras[id];
    return value !== void 0 && value !== this.extraDefaults[id];
  }
  /** How many tokens the active tweak set changes, plus the extras that differ from their defaults. */
  get changedCount() {
    return Object.keys(this.state.tweaks).length + Object.keys(this.state.extras).filter(
      (id) => this.isExtraChanged(id)
    ).length;
  }
  /** Writes `name` as `value`, or stands it back as declared when `value` is undefined. */
  set(name, value) {
    if (value === void 0) {
      delete this.state.tweaks[name];
    } else {
      this.state.tweaks[name] = value;
    }
    this.commit({ kind: "tweak", name });
  }
  reset(name) {
    this.set(name, void 0);
  }
  /** Writes several tokens at once, as the type scale and the seed controls do. */
  setMany(tweaks) {
    Object.assign(this.state.tweaks, tweaks);
    this.commit({ kind: "tweaks" });
  }
  resetMany(names) {
    names.forEach((name) => delete this.state.tweaks[name]);
    this.commit({ kind: "tweaks" });
  }
  /** Stands every token back as declared and every extra control at its default. */
  resetAll() {
    this.state.tweaks = {};
    this.state.extras = {};
    this.commit({ kind: "tweaks" });
  }
  /** Replaces the whole active tweak set, as loading a snapshot does. */
  replaceTweaks({
    tweaks,
    extras = {}
  }) {
    this.state.tweaks = { ...tweaks };
    this.state.extras = { ...extras };
    this.commit({ kind: "tweaks" });
  }
  setExtra(id, value) {
    this.state.extras[id] = value;
    this.commit({ kind: "extra", id });
  }
  resetExtra(id) {
    delete this.state.extras[id];
    this.commit({ kind: "extra", id });
  }
  setTheme(theme) {
    this.state.theme = theme;
    this.commit({ kind: "theme" });
  }
  setPanel({ dock, open, collapsed }) {
    if (dock !== void 0) {
      this.state.dock = dock;
    }
    if (open !== void 0) {
      this.state.open = open;
    }
    if (collapsed !== void 0) {
      this.state.collapsed = collapsed;
    }
    this.commit({ kind: "panel" });
  }
  // Snapshots: each a named copy of a tweak set with the theme it was taken in
  get snapshots() {
    return this.state.snapshots;
  }
  snapshot(name) {
    return this.state.snapshots.find((snapshot) => snapshot.name === name);
  }
  /** The active tweak set as a snapshot named `name`; a snapshot of that name is replaced. */
  saveSnapshot(name) {
    const snapshot = {
      name,
      theme: this.state.theme,
      tweaks: { ...this.state.tweaks },
      extras: { ...this.state.extras }
    };
    this.state.snapshots = [
      ...this.state.snapshots.filter((other) => other.name !== name),
      snapshot
    ];
    this.state.activeSnapshot = name;
    this.commit({ kind: "snapshots" });
  }
  /** Makes the snapshot the active tweak set, theme included, and names it the active one. */
  loadSnapshot(name) {
    const snapshot = this.snapshot(name);
    if (!snapshot) {
      return;
    }
    this.state.tweaks = { ...snapshot.tweaks };
    this.state.extras = { ...snapshot.extras };
    this.state.theme = snapshot.theme;
    this.state.activeSnapshot = name;
    this.commit({ kind: "tweaks" });
  }
  /** Renames a snapshot; false when there is none, the new name is empty or another has it. */
  renameSnapshot(name, newName) {
    const snapshot = this.snapshot(name);
    if (!snapshot || newName === "" || this.snapshot(newName)) {
      return false;
    }
    snapshot.name = newName;
    if (this.state.activeSnapshot === name) {
      this.state.activeSnapshot = newName;
    }
    this.state.ab = sidesOf(
      this.state.ab,
      (side) => side === name ? newName : side
    );
    this.commit({ kind: "snapshots" });
    return true;
  }
  deleteSnapshot(name) {
    this.state.snapshots = this.state.snapshots.filter(
      (snapshot) => snapshot.name !== name
    );
    if (this.state.activeSnapshot === name) {
      this.state.activeSnapshot = null;
    }
    this.state.ab = sidesOf(
      this.state.ab,
      (side) => side === name ? null : side
    );
    this.commit({ kind: "snapshots" });
  }
  /** Adds a copy of the snapshot under the first free "name (n)" and returns that name. */
  duplicateSnapshot(name) {
    const snapshot = this.snapshot(name);
    if (!snapshot) {
      return null;
    }
    const copy = copyName(name, this.state.snapshots);
    this.state.snapshots = [
      ...this.state.snapshots,
      {
        ...snapshot,
        tweaks: { ...snapshot.tweaks },
        extras: { ...snapshot.extras },
        name: copy
      }
    ];
    this.commit({ kind: "snapshots" });
    return copy;
  }
  /**
   * Adds the snapshot `json` holds (as `exportSnapshot` wrote it), under a name no other has,
   * and returns its name; a text that is no snapshot returns null.
   */
  importSnapshot(json) {
    let parsed;
    try {
      parsed = JSON.parse(json);
    } catch {
      return null;
    }
    const snapshot = readSnapshot(parsed);
    if (!snapshot) {
      return null;
    }
    snapshot.name = copyName(snapshot.name, this.state.snapshots, true);
    this.state.snapshots = [...this.state.snapshots, snapshot];
    this.commit({ kind: "snapshots" });
    return snapshot.name;
  }
  /** The snapshot named, or the active tweak set when `name` is null, as JSON for a file. */
  exportSnapshot(name = null) {
    const snapshot = name === null ? this.currentAsSnapshot() : this.snapshot(name);
    return JSON.stringify(snapshot ?? null, null, 4);
  }
  /** The active tweak set as an unsaved snapshot, named after the active one when there is one. */
  currentAsSnapshot() {
    return {
      name: this.state.activeSnapshot ?? "Current tweaks",
      theme: this.state.theme,
      tweaks: { ...this.state.tweaks },
      extras: { ...this.state.extras }
    };
  }
  /** Whether the active tweak set differs from the snapshot it was loaded from or saved as. */
  get activeSnapshotModified() {
    const snapshot = this.snapshot(this.state.activeSnapshot);
    return snapshot !== void 0 && (JSON.stringify(snapshot.tweaks) !== JSON.stringify(this.state.tweaks) || JSON.stringify(snapshot.extras) !== JSON.stringify(this.state.extras));
  }
  get activeSnapshot() {
    return this.state.activeSnapshot;
  }
  // The A/B toggle: two chosen snapshots, flipped between
  get ab() {
    return this.state.ab;
  }
  setAb(index, name) {
    this.state.ab[index] = name;
    this.commit({ kind: "snapshots" });
  }
  /** Loads the side of the A/B pair that is not the active snapshot (A when neither is). */
  flipAb() {
    const [a, b] = this.state.ab;
    if (!a || !b) {
      return;
    }
    this.loadSnapshot(this.state.activeSnapshot === a ? b : a);
  }
  /** Rewrites the sheet, recomputes `css`, saves, and tells the listeners. */
  commit(change) {
    this.state.css = this.sheetText();
    this.sheet.replace(this.state.css);
    try {
      this.storage.set(JSON.stringify(this.state));
    } catch (error) {
      console.warn("tokentweak: the state was not stored", error);
    }
    this.listeners.forEach((listener) => listener(change));
  }
  sheetText() {
    return sheetText({
      tweaks: this.state.tweaks,
      scopedTokens: this.scopedTokens,
      rootSelector: this.rootSelector,
      storageKey: this.storageKey
    });
  }
};
function readStored(storage) {
  try {
    return storage.get();
  } catch {
    return null;
  }
}
function sidesOf(ab, map) {
  return [map(ab[0]), map(ab[1])];
}

// src/tokens.ts
async function selectStylesheets(config) {
  let sheets = qualifyingSheets(config);
  if (sheets.length === 0 && document.readyState !== "complete") {
    await new Promise(
      (resolve) => window.addEventListener("load", () => resolve(), { once: true })
    );
    sheets = qualifyingSheets(config);
  }
  return sheets;
}
function qualifyingSheets(config) {
  return Array.from(document.styleSheets).filter((sheet2) => {
    if (!isSameOrigin(sheet2) || !passesFilter(sheet2, config.stylesheet)) {
      return false;
    }
    try {
      return rootRulesOf(sheet2.cssRules, config.rootSelector).length > 0;
    } catch {
      return false;
    }
  });
}
function readTokens(sheets, config, sources) {
  const fromSources = new Map(
    (sources?.rootTokens ?? []).map((token) => [token.name, token])
  );
  const registrations = /* @__PURE__ */ new Map();
  const declared = /* @__PURE__ */ new Map();
  sheets.forEach((sheet2) => {
    collectRegistrations(sheet2.cssRules, registrations);
    rootRulesOf(sheet2.cssRules, config.rootSelector).forEach((rule) => {
      Array.from(rule.style).filter((property) => property.startsWith("--")).forEach(
        (property) => declared.set(
          property,
          rule.style.getPropertyValue(property).trim()
        )
      );
    });
  });
  const rootStyle = getComputedStyle(document.documentElement);
  const tokens = Array.from(declared, ([name, text]) => {
    const resolved = rootStyle.getPropertyValue(name).trim();
    const token = {
      name,
      declared: text,
      kind: kindOf(name, resolved || text, {
        typographyNames: config.typographyNames
      })
    };
    const registration = registrations.get(name);
    if (registration) {
      token.registration = registration;
    }
    const source = fromSources.get(name);
    if (source) {
      token.location = source.location;
      token.sourceValue = source.value;
      if (source.note !== void 0) {
        token.note = source.note;
      }
    }
    return token;
  });
  config.scopedTokens.forEach((scoped) => {
    const inline = containerOf(scoped.selector)?.style.getPropertyValue(scoped.name).trim();
    tokens.push({
      name: scoped.name,
      declared: inline || scoped.default,
      kind: "scoped",
      scoped
    });
  });
  return tokens;
}
function rootRulesOf(rules, rootSelector) {
  const found = [];
  Array.from(rules).forEach((rule) => {
    if (rule instanceof CSSStyleRule) {
      if (selectorsOf(rule.selectorText).includes(rootSelector)) {
        found.push(rule);
      }
    } else if (typeof CSSLayerBlockRule !== "undefined" && rule instanceof CSSLayerBlockRule) {
      found.push(...rootRulesOf(rule.cssRules, rootSelector));
    }
  });
  return found;
}
function collectRegistrations(rules, into) {
  Array.from(rules).forEach((rule) => {
    if (typeof CSSPropertyRule !== "undefined" && rule instanceof CSSPropertyRule) {
      into.set(rule.name, {
        syntax: rule.syntax,
        inherits: rule.inherits,
        initialValue: rule.initialValue
      });
    } else if (typeof CSSLayerBlockRule !== "undefined" && rule instanceof CSSLayerBlockRule) {
      collectRegistrations(rule.cssRules, into);
    }
  });
}
function selectorsOf(selectorText) {
  return selectorText.split(",").map((selector) => selector.trim());
}
function isSameOrigin(sheet2) {
  if (!sheet2.href) {
    return true;
  }
  try {
    return new URL(sheet2.href, location.href).origin === location.origin;
  } catch {
    return false;
  }
}
function passesFilter(sheet2, filter) {
  if (filter === null) {
    return true;
  }
  if (typeof filter === "function") {
    return filter(sheet2);
  }
  const href = sheet2.href ?? "";
  if (typeof filter === "string") {
    return href.includes(filter);
  }
  filter.lastIndex = 0;
  return filter.test(href);
}
function containerOf(selector) {
  try {
    return document.querySelector(selector);
  } catch {
    return null;
  }
}

// src/index.ts
var VERSION = "0.3.0";
var MODIFIERS = {
  ctrl: "ctrl",
  control: "ctrl",
  alt: "alt",
  option: "alt",
  shift: "shift",
  meta: "meta",
  cmd: "meta",
  command: "meta",
  super: "meta",
  win: "meta"
};
var KEY_NAMES = {
  space: " ",
  spacebar: " ",
  esc: "escape",
  return: "enter"
};
function parseShortcut(text) {
  const parts = text.split("+").map((part) => part.trim()).filter((part) => part !== "");
  const shortcut = {
    ctrl: false,
    alt: false,
    shift: false,
    meta: false,
    key: ""
  };
  for (const part of parts) {
    const lower = part.toLowerCase();
    const modifier = MODIFIERS[lower];
    if (modifier) {
      shortcut[modifier] = true;
    } else if (shortcut.key === "") {
      shortcut.key = KEY_NAMES[lower] ?? lower;
    } else {
      return null;
    }
  }
  return shortcut.key === "" ? null : shortcut;
}
function matchesShortcut(event, shortcut) {
  if (event.ctrlKey !== shortcut.ctrl || event.altKey !== shortcut.alt || event.shiftKey !== shortcut.shift || event.metaKey !== shortcut.meta) {
    return false;
  }
  const key = shortcut.key;
  if (event.key.toLowerCase() === key) {
    return true;
  }
  if (/^[a-z]$/.test(key)) {
    return event.code === `Key${key.toUpperCase()}`;
  }
  if (/^\d$/.test(key)) {
    return event.code === `Digit${key}`;
  }
  return false;
}
async function createTokenTweaks(config = {}) {
  const resolved = resolveConfig(config);
  await domReady();
  const [sources, sheets] = await Promise.all([
    loadSources(resolved.sources),
    selectStylesheets(resolved)
  ]);
  if (sheets.length === 0) {
    console.warn(
      `tokentweak: no stylesheet declares ${resolved.rootSelector} tokens` + (resolved.stylesheet ? " and passes the stylesheet filter" : "")
    );
  }
  const key = resolved.storageKey;
  const firstRun = localStorage.getItem(key) === null;
  const sheet2 = findBootSheet(key) ?? adoptNewSheet();
  const store = new TweaksStore({
    storage: {
      get: () => localStorage.getItem(key),
      set: (json) => localStorage.setItem(key, json)
    },
    sheet: { replace: (cssText) => sheet2.replaceSync(cssText) },
    storageKey: key,
    rootSelector: resolved.rootSelector,
    scopedTokens: resolved.scopedTokens,
    extraDefaults: Object.fromEntries(
      resolved.extraControls.map((control) => [
        control.id,
        control.default
      ])
    )
  });
  if (firstRun && store.state.dock !== resolved.dock) {
    store.setPanel({ dock: resolved.dock });
  }
  if (store.theme !== null) {
    applyTheme(store.theme);
  }
  let current = build(resolved, store, sheets, sources);
  let destroyed = false;
  if (document.readyState === "complete") {
    current.panel.applyExtras();
  } else {
    window.addEventListener("load", () => current.panel.applyExtras(), {
      once: true
    });
  }
  const listeners = [];
  const listen = (target, type, handler) => {
    target.addEventListener(type, handler);
    listeners.push([target, type, handler]);
  };
  const toggle = () => current.panel.toggle();
  const activates = (event) => event instanceof KeyboardEvent && (event.key === "Enter" || event.key === " ");
  const { trigger } = resolved;
  if (typeof trigger === "string") {
    const onTrigger = (event) => event.target instanceof Element && event.target.closest(trigger) !== null;
    listen(document, "click", (event) => {
      if (onTrigger(event)) {
        toggle();
      }
    });
    listen(document, "keydown", (event) => {
      if (onTrigger(event) && activates(event)) {
        event.preventDefault();
        toggle();
      }
    });
  } else if (trigger) {
    listen(trigger, "click", toggle);
    listen(trigger, "keydown", (event) => {
      if (activates(event)) {
        event.preventDefault();
        toggle();
      }
    });
  }
  const shortcut = resolved.shortcut === null ? null : parseShortcut(resolved.shortcut);
  if (resolved.shortcut !== null && shortcut === null) {
    console.warn(
      `tokentweak: the shortcut "${resolved.shortcut}" is not understood`
    );
  }
  if (shortcut) {
    listen(document, "keydown", (event) => {
      if (event instanceof KeyboardEvent && matchesShortcut(event, shortcut)) {
        event.preventDefault();
        toggle();
      }
    });
  }
  let guard = null;
  const guardHost = (host) => {
    guard?.disconnect();
    guard = null;
    if (!resolved.inertGuard) {
      return;
    }
    guard = new MutationObserver(() => {
      if (host.hasAttribute("inert")) {
        host.removeAttribute("inert");
      }
    });
    guard.observe(host, { attributes: true, attributeFilter: ["inert"] });
  };
  guardHost(current.panel.host);
  if (store.state.open) {
    current.panel.open();
  }
  return {
    open: () => current.panel.open(),
    close: () => current.panel.close(),
    toggle,
    destroy: () => {
      if (destroyed) {
        return;
      }
      destroyed = true;
      listeners.forEach(
        ([target, type, handler]) => target.removeEventListener(type, handler)
      );
      guard?.disconnect();
      current.panel.destroy();
    },
    get isOpen() {
      return current.panel.isOpen;
    },
    get host() {
      return current.panel.host;
    },
    get tokens() {
      return current.tokens;
    },
    getState: () => structuredClone(store.state),
    onChange: (listener) => store.subscribe(listener),
    refresh: async () => {
      if (destroyed) {
        return;
      }
      const wasOpen = current.panel.isOpen;
      const freshSources = await loadSources(resolved.sources);
      const freshSheets = qualifyingSheets(resolved);
      current.panel.destroy();
      current = build(resolved, store, freshSheets, freshSources);
      guardHost(current.panel.host);
      current.panel.applyExtras();
      if (wasOpen) {
        current.panel.open();
      }
    }
  };
}
function build(config, store, sheets, sources) {
  const read = readTokens(sheets, config, sources);
  const scale = inferScale(read, config.scale);
  const tokens = withScaleRoles(read, scale);
  const typeScale = inferTypeScale(tokens, config.typeScale);
  const seed = resolveSeed(config.seed, tokens);
  const sections = inferSections(tokens, config);
  const panel = new Panel({
    store,
    tokens,
    sections,
    scale,
    typeScale,
    seed,
    config,
    sources,
    sheets
  });
  document.body.append(panel.host);
  return { panel, tokens };
}
function adoptNewSheet() {
  const sheet2 = new CSSStyleSheet();
  document.adoptedStyleSheets.push(sheet2);
  return sheet2;
}
function domReady() {
  if (document.readyState !== "loading") {
    return Promise.resolve();
  }
  return new Promise(
    (resolve) => document.addEventListener("DOMContentLoaded", () => resolve(), {
      once: true
    })
  );
}
export {
  DEFAULT_DOCK,
  DEFAULT_FILE_NAME,
  DEFAULT_HOST_ID,
  DEFAULT_INDENT,
  DEFAULT_RANGES,
  DEFAULT_ROOT_SELECTOR,
  DEFAULT_SHORTCUT,
  DEFAULT_STATE,
  DEFAULT_STORAGE_KEY,
  DEFAULT_TITLE,
  DEFAULT_TYPOGRAPHY_NAMES,
  DOCKS,
  FACTOR_SPAN,
  Inspector,
  LIGHT_DARK,
  MARKER_PROPERTY,
  MIX_SPAN,
  OTHER_SECTION,
  PANEL_CSS,
  Panel,
  RELATIVE_FNS,
  STATE_VERSION,
  THEMES,
  TOKEN_REFERENCE,
  TweaksStore,
  VERSION,
  applyTheme,
  asFontFamily,
  buildCss,
  buildPrompt,
  buildSnapshotJson,
  channelInMode,
  channelRanges,
  channelsOf,
  colorSources,
  copyName,
  createTokenTweaks,
  defaultIntro,
  defaultState,
  defaultSupports,
  findBootSheet,
  hexOf,
  inTheme,
  inferScale,
  inferSections,
  inferTypeScale,
  isBootSheet,
  isColor,
  isExtras,
  isFontAvailable,
  isRecipe,
  isTheme,
  isTweaks,
  isTypographyName,
  kindOf,
  lengthPhrase,
  loadSources,
  matchesName,
  matchesShortcut,
  matchesToken,
  normalizeSources,
  oklchOf,
  parseRecipe,
  parseShortcut,
  qualifyingSheets,
  rangeFor,
  readSnapshot,
  readState,
  readTokens,
  recipePhrase,
  referencedNamesOf,
  remOf,
  resolveColor,
  resolveConfig,
  resolveSeed,
  rootRulesOf,
  scaleRoleOf,
  scopedControlOf,
  selectStylesheets,
  serializeRecipe,
  sheetText,
  shownTheme,
  splitOutsideParentheses,
  stepReferenceOf,
  themeColorsOf,
  themeOrStylesheets,
  themeSideOf,
  tokensReferencedBy,
  typeScaleValues,
  withAlpha,
  withChannel,
  withMixColor,
  withPercent,
  withScaleRoles,
  withSource,
  withThemeColor
};
