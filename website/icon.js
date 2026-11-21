/* Best-effort real app icon for the add page's target card (see add.html/add.js), drawn the way
   Android draws it: the manifest names the icon resource, its <adaptive-icon> gives a background and
   a foreground layer, and those are painted on a canvas and cropped to the part a launcher shows.
   Mirrors AdaptiveIconComposer.kt on the app side; a browser already parses XML (DOMParser) and
   Android's path syntax (Path2D), which is why this stays a single file.

   When no adaptive icon can be drawn, the same fallbacks as the app follow, best first: the store
   listing icon (fastlane / F-Droid metadata), the launcher raster the manifest names, any other
   launcher-named raster, the icon an Expo or Cordova config declares, the project's or
   organisation's own avatar, then an image named as the project's logo. Every step can fail, and
   every failure resolves to null, so the caller's initial-letter tile stays the last resort.

   Reads GitHub, GitLab (gitlab.com and self-hosted) and Gitea/Forgejo (Codeberg and self-hosted)
   through their APIs, all of which answer a browser (CORS). */
(function () {
  "use strict";

  var SIZE = 192;
  var REQUEST_TIMEOUT_MS = 6000;
  /* Past this, the adaptive icon is given up on so the fallbacks still have time to answer. */
  var ADAPTIVE_BUDGET_MS = 9000;
  var OVERALL_TIMEOUT_MS = 13000;
  var MAX_MANIFESTS = 6;
  var MAX_VALUE_FILES = 32;
  var MAX_DEPTH = 8;

  /* ---------- Network ---------- */

  function fetchWithTimeout(url, options) {
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, REQUEST_TIMEOUT_MS);
    return fetch(url, Object.assign({ signal: controller.signal }, options || {}))
      .finally(function () { clearTimeout(timer); });
  }

  async function getJson(url, headers) {
    try {
      var res = await fetchWithTimeout(url, { headers: headers || {} });
      return res.ok ? { body: await res.json(), headers: res.headers } : null;
    } catch (failed) {
      return null;
    }
  }

  function encodePath(path) {
    return path.split("/").map(encodeURIComponent).join("/");
  }

  /* ---------- Hosts ---------- */

  /* Each host gives the same three things: every file path of the default branch, a URL that
     answers with one file's raw bytes, and an avatar to fall back on. */
  function githubHost(owner, repo) {
    var api = "https://api.github.com";
    var accept = { Accept: "application/vnd.github+json" };
    return {
      paths: async function () {
        // "HEAD" names the default branch, which saves asking for it first.
        var res = await getJson(api + "/repos/" + owner + "/" + repo + "/git/trees/HEAD?recursive=1", accept);
        if (!res || !Array.isArray(res.body.tree)) return null;
        return res.body.tree.filter(function (e) { return e.type === "blob"; }).map(function (e) { return e.path; });
      },
      raw: function (path) {
        return "https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/" + encodePath(path);
      },
      /* Only an organisation's: its avatar is the project's logo far more often than a person's. */
      avatar: async function () {
        var res = await getJson(api + "/users/" + owner, accept);
        return res && res.body.type === "Organization" ? res.body.avatar_url : null;
      },
    };
  }

  function giteaHost(origin, owner, repo) {
    var base = origin + "/api/v1/repos/" + owner + "/" + repo;
    var info = null;
    var PAGE = 1000;
    return {
      paths: async function () {
        var res = await getJson(base);
        if (!res || !res.body.default_branch) return null;
        info = res.body;
        var tree = base + "/git/trees/" + encodeURIComponent(info.default_branch) + "?recursive=true&per_page=" + PAGE;
        var first = await getJson(tree + "&page=1");
        if (!first || !Array.isArray(first.body.tree)) return null;
        var pages = Math.min(Math.ceil((first.body.total_count || 0) / PAGE), 10);
        var rest = [];
        for (var p = 2; p <= pages; p++) rest.push(getJson(tree + "&page=" + p));
        var all = [first].concat(await Promise.all(rest));
        var paths = [];
        all.forEach(function (r) {
          if (!r || !Array.isArray(r.body.tree)) return;
          r.body.tree.forEach(function (e) { if (e.type === "blob") paths.push(e.path); });
        });
        return paths;
      },
      raw: function (path) {
        return base + "/raw/" + encodePath(path) + "?ref=" + encodeURIComponent(info ? info.default_branch : "");
      },
      avatar: async function () {
        if (info && info.avatar_url) return info.avatar_url;
        if (!info || !info.owner) return null;
        var org = await getJson(origin + "/api/v1/orgs/" + owner);
        return org ? org.body.avatar_url : null;
      },
    };
  }

  function gitlabHost(origin, owner, repo) {
    var base = origin + "/api/v4/projects/" + encodeURIComponent(owner + "/" + repo);
    var info = null;
    return {
      paths: async function () {
        var res = await getJson(base);
        if (!res || !res.body.id) return null;
        info = res.body;
        var tree = base + "/repository/tree?recursive=true&per_page=100";
        var first = await getJson(tree + "&page=1");
        if (!first || !Array.isArray(first.body)) return null;
        var pages = Math.min(parseInt(first.headers.get("X-Total-Pages") || "1", 10) || 1, 40);
        var rest = [];
        for (var p = 2; p <= pages; p++) rest.push(getJson(tree + "&page=" + p));
        var all = [first].concat(await Promise.all(rest));
        var paths = [];
        all.forEach(function (r) {
          if (!r || !Array.isArray(r.body)) return;
          r.body.forEach(function (e) { if (e.type === "blob") paths.push(e.path); });
        });
        return paths;
      },
      raw: function (path) {
        return base + "/repository/files/" + encodeURIComponent(path) + "/raw?ref=HEAD";
      },
      avatar: async function () {
        if (info && info.avatar_url) return info.avatar_url;
        return info && info.namespace && info.namespace.kind === "group" ? info.namespace.avatar_url : null;
      },
    };
  }

  /* GitHub and the hosts known by name are asked directly; any other host is tried as Gitea/Forgejo
     first (the API most self-hosted forges now speak), then as GitLab. */
  function hostsFor(host, owner, repo) {
    host = (host || "github.com").toLowerCase();
    if (host === "github.com" || host === "www.github.com") return [githubHost(owner, repo)];
    var origin = "https://" + host;
    if (host === "gitlab.com" || host.indexOf("gitlab") !== -1) return [gitlabHost(origin, owner, repo)];
    if (host === "codeberg.org") return [giteaHost(origin, owner, repo)];
    return [giteaHost(origin, owner, repo), gitlabHost(origin, owner, repo)];
  }

  /* ---------- The repository's resources ---------- */

  /* Where a resource folder sits: its module ("app") and source set ("main", "release", "debug"...).
     The shipped app is main with the release build type and a store flavour laid over it, so those
     override main (Bitwarden keeps its real foreground in src/release, main's being the development
     one), and development builds and variant modules (an enterprise edition, a CI build, a watch
     app) come last: their icon is a badged or recoloured copy of the real one. */
  var DEV_WORDS = /^(debug|dev|develop|development|beta|alpha|nightly|canary|preview|staging|qa|internal|test|androidtest|benchmark|baselineprofile|ci|snapshot)$/;
  var STORE_WORDS = /^(release|foss|fdroid|libre|free|oss|full|generic|prod|production|stable|github|vanilla|default)$/;
  var VARIANT_WORDS = /^(enterprise|pro|premium|business|beta|debug|dev|nightly|canary|preview|alpha|internal|staging|qa|test|tests|testharness|testing|sample|samples|demo|example|examples|wear|wearos|watch|tv|automotive|benchmark|ci|mock|fake|e2e|testapp|sampleapp|uitest|uitests)$/i;

  function sourceSetOf(root) {
    var m = /(?:^|\/)src\/([^/]+)$/.exec(root || "");
    return m ? m[1] : "";
  }

  function moduleOf(root) {
    return (root || "").replace(/\/?src\/[^/]+$/, "");
  }

  /* A source set is read word by word, flavours and build type together: fdroidScDefaultRelease
     is a store build, fdroidScBetaRelease a beta. 0 store, 1 main, 2 other, 3 development. */
  function setClass(root) {
    var set = sourceSetOf(root);
    if (!set || set === "main") return 1;
    var words = set.replace(/([a-z0-9])([A-Z])/g, "$1 $2").toLowerCase().split(/[^a-z0-9]+/);
    if (words.some(function (w) { return DEV_WORDS.test(w); })) return 3;
    if (words.some(function (w) { return STORE_WORDS.test(w); })) return 0;
    return 2;
  }

  function isVariant(root) {
    if (setClass(root) === 3) return true;
    return moduleOf(root).split("/").some(function (segment) {
      return segment.split(/[-_.]/).some(function (word) { return VARIANT_WORDS.test(word); });
    });
  }

  /* For manifests: main first, then store builds, other flavours, development builds. */
  function sourceSetRank(root) {
    return [1, 0, 2, 3][setClass(root)];
  }

  /* How likely a resource folder is to be what the shipped app was built from. [appRoot] is where
     the application's manifest is, whose module overrides every library's resources when the app is
     built (SchildiChat replaces the icon layers of the Element module it reuses); [root] is where the
     file asking for the resource is. Either is null when unknown. */
  function placeRank(entryRoot, root, appRoot) {
    var inApp = appRoot !== null && appRoot !== undefined && moduleOf(entryRoot) === moduleOf(appRoot);
    var same = root !== null && root !== undefined && moduleOf(entryRoot) === moduleOf(root);
    if (isVariant(entryRoot)) return inApp || same ? 9 : 10;
    var set = [0, 1, 2, 2][setClass(entryRoot)];
    if (inApp) return set;
    if (same) return 3 + set;
    return 6 + set;
  }

  /* The words of a repository's name that can tell its own module from others in the same tree
     (thunderbird-android ships K-9 Mail beside Thunderbird): generic words left out. */
  var GENERIC_WORDS = /^(android|app|apps|client|mobile|the|for|and|next|ose|kotlin|flutter|java|project|source|open|lite|plus|community|enhanced)$/i;

  function nameTokens(name) {
    return (name || "").toLowerCase().split(/[^a-z0-9]+/).filter(function (w) {
      return w.length >= 3 && !GENERIC_WORDS.test(w);
    });
  }

  function tokenRank(text, tokens) {
    var lower = text.toLowerCase();
    return tokens.some(function (t) { return lower.indexOf(t) !== -1; }) ? 0 : 1;
  }

  /* Only a module's own name is compared: a repository can keep every module under a folder named
     after itself (Proton Pass's pass/), which tells none of them apart. */
  function moduleTokenRank(root, tokens) {
    return tokenRank(moduleOf(root).split("/").pop(), tokens);
  }

  /* A qualifier a phone in its default setup doesn't match (another language, night mode, a
     television, landscape...) is only used when nothing else exists. */
  function qualifierRank(qualifier) {
    if (!qualifier) return 0;
    var q = qualifier.toLowerCase();
    if (/(^|-)(night|land|television|watch|car|ldrtl|notnight)(-|$)/.test(q)) return 9;
    if (/^-[a-z]{2,3}(-r[a-z]{2})?(-|$)|^-b\+/.test(q) && !/^-(v\d+|anydpi)/.test(q)) return 9;
    return 1;
  }

  var DENSITIES = ["xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi", "ldpi"];

  function densityRank(qualifier) {
    for (var i = 0; i < DENSITIES.length; i++) {
      if ((qualifier || "").indexOf("-" + DENSITIES[i]) !== -1) return i;
    }
    return DENSITIES.length;
  }

  var RES_PATH = /^(?:(.*)\/)?res\/([a-z]+)(-[^/]+)?\/([^/]+)\.(xml|png|webp|jpe?g)$/i;

  function Repo(host, paths, name) {
    this.host = host;
    this.paths = paths;
    this.tokens = nameTokens(name);
    this.has = {};
    this.texts = {};
    this.res = {};
    for (var i = 0; i < paths.length; i++) {
      this.has[paths[i]] = true;
      var m = RES_PATH.exec(paths[i]);
      if (!m) continue;
      var kind = m[2].toLowerCase();
      var key = kind === "values" ? "values" : kind + "/" + m[4];
      (this.res[key] = this.res[key] || []).push({
        path: paths[i],
        root: m[1] || "",
        qualifier: m[3] || "",
        ext: m[5].toLowerCase(),
        name: m[4],
      });
    }
  }

  Repo.prototype.text = function (path) {
    if (!(path in this.texts)) {
      this.texts[path] = fetchWithTimeout(this.host.raw(path))
        .then(function (res) { return res.ok ? res.text() : null; })
        .catch(function () { return null; });
    }
    return this.texts[path];
  };

  Repo.prototype.xml = async function (path) {
    var text = await this.text(path);
    if (!text) return null;
    var doc = new DOMParser().parseFromString(text, "application/xml");
    return doc.getElementsByTagName("parsererror").length ? null : doc;
  };

  Repo.prototype.image = async function (path) {
    try {
      if (/\.svg$/i.test(path)) return await svgImage(await this.text(path));
      var res = await fetchWithTimeout(this.host.raw(path));
      if (!res.ok) return null;
      return await createImageBitmap(await res.blob());
    } catch (failed) {
      return null;
    }
  };

  /* Hosts serve an SVG as plain text, which an image won't load, so it is loaded from a blob of
     the right type instead. One with no size of its own is drawn at the canvas's. */
  function svgImage(text) {
    if (!text) return Promise.resolve(null);
    return new Promise(function (resolve) {
      var url = URL.createObjectURL(new Blob([text], { type: "image/svg+xml" }));
      var img = new Image();
      var done = function (result) { URL.revokeObjectURL(url); resolve(result); };
      img.onload = function () {
        if (!img.naturalWidth) img.width = img.height = SIZE;
        done(img);
      };
      img.onerror = function () { done(null); };
      img.src = url;
    });
  }

  /* Every file a resource reference can resolve to, best first: by where it sits (see placeRank;
     real projects keep the launcher icon in a shared module the app depends on, AntennaPod's
     ui/common or VLC's vlc-android, as often as in the app itself), then in the order Android would
     rather pick it: a density-independent drawing (an XML in an -anydpi or -vNN folder) before a
     raster, rasters by density, an unqualified XML after them. */
  Repo.prototype.find = function (kind, name, root, appRoot) {
    var entries = (this.res[kind + "/" + name] || []).slice();
    var tokens = this.tokens;
    return entries.sort(function (a, b) {
      return placeRank(a.root, root, appRoot) - placeRank(b.root, root, appRoot) ||
        moduleTokenRank(a.root, tokens) - moduleTokenRank(b.root, tokens) ||
        qualifierRank(a.qualifier) - qualifierRank(b.qualifier) ||
        formatRank(a) - formatRank(b) ||
        densityRank(a.qualifier) - densityRank(b.qualifier) ||
        a.path.length - b.path.length;
    });
    function formatRank(e) {
      if (e.ext !== "xml") return 1;
      return /anydpi|-v\d+/.test(e.qualifier) ? 0 : 2;
    }
  };

  /* ---------- Colours ---------- */

  var ANDROID_COLORS = {
    black: "#000000", white: "#ffffff", transparent: "rgba(0,0,0,0)",
    darker_gray: "#aaaaaa", background_dark: "#000000", background_light: "#ffffff",
    holo_blue_light: "#33b5e5", holo_blue_dark: "#0099cc", holo_green_light: "#99cc00",
    holo_green_dark: "#669900", holo_red_light: "#ff4444", holo_red_dark: "#cc0000",
    holo_orange_light: "#ffbb33", holo_orange_dark: "#ff8800", holo_purple: "#aa66cc",
  };

  /* Android writes alpha first (#AARRGGBB, #ARGB), CSS last; the short forms double each digit. */
  function cssColor(hex) {
    var h = hex.trim().replace("#", "");
    if (!/^[0-9a-f]+$/i.test(h)) return null;
    if (h.length === 3 || h.length === 4) h = h.replace(/./g, "$&$&");
    if (h.length === 6) return "#" + h;
    if (h.length !== 8) return null;
    var n = function (i) { return parseInt(h.slice(i, i + 2), 16); };
    return "rgba(" + n(2) + "," + n(4) + "," + n(6) + "," + (n(0) / 255).toFixed(3) + ")";
  }

  /* Named values (colours, and strings for a path kept in one, as Simple Keyboard does) are read in
     batches out of the values files, the likeliest to hold them first: for colours, files named
     after colours wherever they are (Organic Maps keeps its icon's in a branding module, behind a
     dozen of the app's own layout files), then the rest, files that never hold colours last. Within
     each, the best placed first, so an app's value overrides a library's of the same name.
     Translations and night mode are never read. A colour can point at another, followed in
     resolvePaint. */
  function ValuesTable(repo, root, appRoot, type) {
    this.repo = repo;
    this.type = type;
    this.map = {};
    this.next = 0;
    this.loading = Promise.resolve();
    var nameRank = type === "color" ? colorFileRank : stringFileRank;
    this.files = (repo.res.values || []).filter(function (e) {
      if (e.ext !== "xml" || qualifierRank(e.qualifier) >= 9) return false;
      return type === "string" || !/^strings|^plurals|^arrays/i.test(e.name);
    }).sort(function (a, b) {
      return nameRank(a.name) - nameRank(b.name) ||
        placeRank(a.root, root, appRoot) - placeRank(b.root, root, appRoot) ||
        qualifierRank(a.qualifier) - qualifierRank(b.qualifier);
    }).slice(0, MAX_VALUE_FILES);
  }

  function colorFileRank(n) {
    if (/colou?r|palette/i.test(n)) return 0;
    if (/launcher|icon|brand/i.test(n)) return 1;
    if (/^(dimens|attrs|ids|integers|bools|styles|themes|anim|font|preference|donottranslate|config)/i.test(n)) return 3;
    return 2;
  }

  function stringFileRank(n) {
    if (/icon|path|vector|drawable|donottranslate|launcher/i.test(n)) return 0;
    if (/^strings/i.test(n)) return 2;
    return 1;
  }

  var VALUE_ENTRY = /<(color|item|string)\b([^>]*)>([^<]*)<\/\1>/g;

  ValuesTable.prototype.lookup = function (name) {
    var self = this;
    var step = this.loading.then(async function () {
      while (!(name in self.map) && self.next < self.files.length) {
        var batch = self.files.slice(self.next, self.next + 8);
        self.next += batch.length;
        var texts = await Promise.all(batch.map(function (e) { return self.repo.text(e.path); }));
        texts.forEach(function (text) {
          if (!text) return;
          text = text.replace(/<!--[\s\S]*?-->/g, "");
          var m;
          VALUE_ENTRY.lastIndex = 0;
          while ((m = VALUE_ENTRY.exec(text))) {
            var attrs = m[2];
            var n = /\bname\s*=\s*"([^"]+)"/.exec(attrs);
            var tag = m[1] === "item" ? (/\btype\s*=\s*"(\w+)"/.exec(attrs) || [])[1] : m[1];
            if (!n || tag !== self.type) continue;
            if (!(n[1] in self.map)) self.map[n[1]] = m[3].trim();
          }
        });
      }
      return self.map[name];
    });
    this.loading = step.catch(function () {});
    return step;
  };

  /* A colour value as a paint: {css} for a plain colour, {gradient, colors} for a gradient colour
     resource, null for what can't be known without the app running (a theme attribute). */
  async function resolvePaint(value, ctx, depth) {
    value = (value || "").trim();
    if (!value || depth > MAX_DEPTH) return null;
    if (value.charAt(0) === "#") {
      var css = cssColor(value);
      return css ? { css: css } : null;
    }
    var android = /^@android:color\/(\w+)$/.exec(value);
    if (android) return ANDROID_COLORS[android[1]] ? { css: ANDROID_COLORS[android[1]] } : null;
    var ref = /^@color\/([\w.]+)$/.exec(value);
    if (!ref) return null;
    // res/color/ holds colour state lists and gradients; values/ holds plain named colours.
    var files = ctx.repo.find("color", ref[1], ctx.root, ctx.appRoot).filter(function (e) { return e.ext === "xml"; });
    for (var i = 0; i < files.length; i++) {
      var doc = await ctx.repo.xml(files[i].path);
      if (!doc) continue;
      var root = doc.documentElement;
      if (root.tagName === "gradient") return gradientPaint(root, ctx, depth + 1);
      if (root.tagName === "selector") {
        var item = defaultItem(root);
        if (item) return resolvePaint(item.getAttribute("android:color"), ctx, depth + 1);
      }
    }
    var named = await ctx.colors.lookup(ref[1]);
    return named ? resolvePaint(named, ctx, depth + 1) : null;
  }

  /* The item a selector shows in the default state: the first one that asks for no state at all. */
  function defaultItem(selector) {
    var items = selector.getElementsByTagName("item");
    for (var i = 0; i < items.length; i++) {
      var stateful = false;
      for (var a = 0; a < items[i].attributes.length; a++) {
        if (/^android:state_/.test(items[i].attributes[a].name)) stateful = true;
      }
      if (!stateful) return items[i];
    }
    return items.length ? items[items.length - 1] : null;
  }

  async function gradientPaint(el, ctx, depth) {
    var stops = [];
    var items = el.getElementsByTagName("item");
    if (items.length) {
      for (var i = 0; i < items.length; i++) {
        var c = await resolvePaint(items[i].getAttribute("android:color"), ctx, depth);
        if (!c || !c.css) return null;
        stops.push([parseFloat(items[i].getAttribute("android:offset") || "0"), c.css]);
      }
    } else {
      var names = ["startColor", "centerColor", "endColor"];
      var offsets = [0, 0.5, 1];
      for (var j = 0; j < names.length; j++) {
        var v = el.getAttribute("android:" + names[j]);
        if (!v) continue;
        var p = await resolvePaint(v, ctx, depth);
        if (!p || !p.css) return null;
        stops.push([offsets[j], p.css]);
      }
    }
    if (!stops.length) return null;
    var num = function (n, d) { var v = el.getAttribute("android:" + n); return v === null ? d : parseFloat(v); };
    return {
      gradient: {
        type: el.getAttribute("android:type") || "linear",
        startX: num("startX", 0), startY: num("startY", 0), endX: num("endX", 0), endY: num("endY", 0),
        centerX: num("centerX", 0), centerY: num("centerY", 0), radius: num("gradientRadius", 0),
        stops: stops,
      },
    };
  }

  /* A gradient in a vector's own coordinates, built at paint time so the group transforms in force
     apply to it exactly as they do to the path it fills. */
  function canvasPaint(g, paint) {
    if (paint.css) return paint.css;
    var s = paint.gradient;
    var grad;
    if (s.type === "radial") {
      if (!(s.radius > 0)) return null;
      grad = g.createRadialGradient(s.centerX, s.centerY, 0, s.centerX, s.centerY, s.radius);
    } else if (s.type === "sweep") {
      if (!g.createConicGradient) return null;
      grad = g.createConicGradient(0, s.centerX, s.centerY);
    } else {
      grad = g.createLinearGradient(s.startX, s.startY, s.endX, s.endY);
    }
    s.stops.forEach(function (stop) { grad.addColorStop(Math.min(1, Math.max(0, stop[0])), stop[1]); });
    return grad;
  }

  /* ---------- Drawables ---------- */

  /* Every drawable becomes a painter: draw(g, x, y, w, h, dp) paints it into that rectangle, dp
     being how many canvas pixels make one dp there (a layer is 108dp across). Preparing (fetching,
     resolving colours) is async; painting is not, so a composite is never half-painted. */

  function parseRef(ref) {
    var m = /^@(?:\+?)(mipmap|drawable|color)\/([\w.]+)$/.exec((ref || "").trim());
    return m ? { kind: m[1], name: m[2] } : null;
  }

  async function painterForRef(ref, ctx, depth) {
    ref = (ref || "").trim();
    if (depth > MAX_DEPTH || !ref) return null;
    if (ref.charAt(0) === "#" || /^@(android:)?color\//.test(ref)) {
      var paint = await resolvePaint(ref, ctx, depth + 1);
      return paint ? fillPainter(paint) : null;
    }
    var r = parseRef(ref);
    if (!r) return null;
    var candidates = ctx.repo.find(r.kind, r.name, ctx.root, ctx.appRoot);
    for (var i = 0; i < candidates.length; i++) {
      var c = candidates[i];
      if (c.ext === "xml") {
        var doc = await ctx.repo.xml(c.path);
        if (!doc) continue;
        var p = await painterForElement(doc.documentElement, withRoot(ctx, c.root), depth + 1);
        if (p) return p;
      } else {
        var img = await ctx.repo.image(c.path);
        if (img) return imagePainter(img);
      }
    }
    return null;
  }

  function withRoot(ctx, root) {
    return { repo: ctx.repo, colors: ctx.colors, strings: ctx.strings, root: root, appRoot: ctx.appRoot };
  }

  /* A drawable given either by an android:drawable attribute or as the element's own child. */
  function childPainter(el, ctx, depth) {
    var ref = el.getAttribute("android:drawable");
    if (ref) return painterForRef(ref, ctx, depth + 1);
    var child = el.firstElementChild;
    if (child && child.tagName === "aapt:attr") child = child.firstElementChild;
    return child ? painterForElement(child, ctx, depth + 1) : Promise.resolve(null);
  }

  async function painterForElement(el, ctx, depth) {
    if (depth > MAX_DEPTH) return null;
    switch (el.tagName) {
      case "vector": return vectorPainter(el, ctx);
      case "shape": return shapePainter(el, ctx);
      case "inset": return insetPainter(el, ctx, depth);
      case "layer-list": return layerListPainter(el, ctx, depth);
      case "bitmap":
      case "nine-patch": return painterForRef(el.getAttribute("android:src"), ctx, depth + 1);
      case "animated-vector": return childPainter(el, ctx, depth);
      case "selector":
      case "level-list": {
        var item = defaultItem(el);
        return item ? childPainter(item, ctx, depth) : null;
      }
      case "color": {
        var paint = await resolvePaint(el.getAttribute("android:color"), ctx, depth + 1);
        return paint ? fillPainter(paint) : null;
      }
      default: return null;
    }
  }

  /* A colour filling the whole rectangle. A gradient colour resource is read in the layer's own
     108-unit space, the one its coordinates are written for. */
  function fillPainter(paint) {
    return {
      draw: function (g, x, y, w, h) {
        g.save();
        g.translate(x, y);
        g.scale(w / 108, h / 108);
        var style = canvasPaint(g, paint);
        if (style) {
          g.fillStyle = style;
          g.fillRect(0, 0, 108, 108);
        }
        g.restore();
      },
    };
  }

  function imagePainter(img) {
    return {
      draw: function (g, x, y, w, h) { g.drawImage(img, x, y, w, h); },
    };
  }

  /* An inset is a dimension: a fraction ("25%") of the side, or dp. */
  function dimension(value, side, dp) {
    if (!value) return 0;
    value = value.trim();
    if (/%p?$/.test(value)) return (parseFloat(value) / 100) * side;
    if (/^-?[\d.]+$/.test(value)) return parseFloat(value) * side;
    var n = parseFloat(value);
    if (isNaN(n)) return 0;
    if (/dp$|dip$|sp$/.test(value)) return n * dp;
    if (/px$/.test(value)) return n;
    return 0;
  }

  async function insetPainter(el, ctx, depth) {
    var child = await childPainter(el, ctx, depth);
    if (!child) return null;
    var attr = function (n) { return el.getAttribute("android:" + n); };
    var all = attr("inset");
    return {
      draw: function (g, x, y, w, h, dp) {
        var l = dimension(attr("insetLeft") || all, w, dp);
        var t = dimension(attr("insetTop") || all, h, dp);
        var r = dimension(attr("insetRight") || all, w, dp);
        var b = dimension(attr("insetBottom") || all, h, dp);
        child.draw(g, x + l, y + t, w - l - r, h - t - b, dp);
      },
    };
  }

  async function layerListPainter(el, ctx, depth) {
    var items = [];
    for (var c = el.firstElementChild; c; c = c.nextElementSibling) {
      if (c.tagName !== "item") continue;
      var painter = await childPainter(c, ctx, depth);
      if (!painter) return null;
      items.push({ el: c, painter: painter });
    }
    if (!items.length) return null;
    return {
      draw: function (g, x, y, w, h, dp) {
        items.forEach(function (item) {
          var a = function (n) { return item.el.getAttribute("android:" + n); };
          var l = dimension(a("left") || a("start"), w, dp);
          var t = dimension(a("top"), h, dp);
          var r = dimension(a("right") || a("end"), w, dp);
          var b = dimension(a("bottom"), h, dp);
          var iw = w - l - r;
          var ih = h - t - b;
          var ix = x + l;
          var iy = y + t;
          // A sized item sits by its gravity (centred unless it says otherwise) in what is left.
          if (a("width")) { var nw = dimension(a("width"), w, dp); ix += gravityOffset(a("gravity"), "left", "right", iw - nw); iw = nw; }
          if (a("height")) { var nh = dimension(a("height"), h, dp); iy += gravityOffset(a("gravity"), "top", "bottom", ih - nh); ih = nh; }
          item.painter.draw(g, ix, iy, iw, ih, dp);
        });
      },
    };
  }

  function gravityOffset(gravity, start, end, room) {
    gravity = gravity || "center";
    if (gravity.indexOf(start) !== -1) return 0;
    if (gravity.indexOf(end) !== -1) return room;
    return room / 2;
  }

  async function shapePainter(el, ctx) {
    var kind = el.getAttribute("android:shape") || "rectangle";
    if (kind !== "rectangle" && kind !== "oval") return null;
    var solid = el.getElementsByTagName("solid")[0];
    var gradient = el.getElementsByTagName("gradient")[0];
    var stroke = el.getElementsByTagName("stroke")[0];
    var corners = el.getElementsByTagName("corners")[0];
    var fill = null;
    if (gradient) {
      fill = await gradientPaint(gradient, ctx, 1);
      if (!fill) return null;
      fill.shapeGradient = gradient;
    } else if (solid) {
      fill = await resolvePaint(solid.getAttribute("android:color"), ctx, 1);
      if (!fill) return null;
    }
    var strokePaint = null;
    if (stroke && stroke.getAttribute("android:color")) {
      strokePaint = await resolvePaint(stroke.getAttribute("android:color"), ctx, 1);
      if (!strokePaint || !strokePaint.css) return null;
    }
    if (!fill && !strokePaint) return null;
    return {
      draw: function (g, x, y, w, h, dp) {
        var path = new Path2D();
        if (kind === "oval") {
          path.ellipse(x + w / 2, y + h / 2, w / 2, h / 2, 0, 0, Math.PI * 2);
        } else {
          var radius = corners ? dimension(corners.getAttribute("android:radius"), 1, dp) : 0;
          var corner = function (n) {
            var v = corners && corners.getAttribute("android:" + n + "Radius");
            return v ? dimension(v, 1, dp) : radius;
          };
          var radii = [corner("topLeft"), corner("topRight"), corner("bottomRight"), corner("bottomLeft")];
          if (radii.some(function (r) { return r > 0; }) && path.roundRect) path.roundRect(x, y, w, h, radii);
          else path.rect(x, y, w, h);
        }
        if (fill) {
          g.fillStyle = fill.css || shapeGradient(g, fill, x, y, w, h, dp);
          g.fill(path);
        }
        if (strokePaint) {
          g.strokeStyle = strokePaint.css;
          g.lineWidth = dimension(stroke.getAttribute("android:width"), 1, dp);
          if (g.lineWidth > 0) g.stroke(path);
        }
      },
    };
  }

  /* A <shape>'s gradient is laid by angle across the shape's bounds, as GradientDrawable does. */
  function shapeGradient(g, fill, x, y, w, h, dp) {
    var el = fill.shapeGradient;
    var s = fill.gradient;
    var cx = x + w * parseFloat(el.getAttribute("android:centerX") || "0.5");
    var cy = y + h * parseFloat(el.getAttribute("android:centerY") || "0.5");
    var grad;
    if (s.type === "radial") {
      var r = dimension(el.getAttribute("android:gradientRadius") || "50%", Math.min(w, h), dp);
      grad = g.createRadialGradient(cx, cy, 0, cx, cy, Math.max(r, 1));
    } else if (s.type === "sweep" && g.createConicGradient) {
      grad = g.createConicGradient(0, cx, cy);
    } else {
      var angle = ((parseInt(el.getAttribute("android:angle") || "0", 10) % 360) + 360) % 360;
      var l = x, t = y, rgt = x + w, btm = y + h;
      var ends = {
        0: [l, t, rgt, t], 45: [l, btm, rgt, t], 90: [l, btm, l, t], 135: [rgt, btm, l, t],
        180: [rgt, t, l, t], 225: [rgt, t, l, btm], 270: [l, t, l, btm], 315: [l, t, rgt, btm],
      }[Math.round(angle / 45) * 45 % 360];
      grad = g.createLinearGradient(ends[0], ends[1], ends[2], ends[3]);
    }
    s.stops.forEach(function (stop) { grad.addColorStop(stop[0], stop[1]); });
    return grad;
  }

  /* ---------- Vector drawables ---------- */

  function num(el, attr, fallback) {
    var v = el.getAttribute(attr);
    return v === null || v === "" ? fallback : parseFloat(v);
  }

  /* The paint an attribute gives, either as its value or as a nested <aapt:attr name="..."> holding
     a <gradient>. Undefined when the attribute isn't set at all, null when it is but can't be read. */
  async function attrPaint(el, attr, ctx) {
    for (var c = el.firstElementChild; c; c = c.nextElementSibling) {
      if (c.tagName === "aapt:attr" && c.getAttribute("name") === attr) {
        var g = c.getElementsByTagName("gradient")[0];
        return g ? gradientPaint(g, ctx, 1) : null;
      }
    }
    var value = el.getAttribute(attr);
    if (value === null || value === "") return undefined;
    if (value === "@android:color/transparent") return undefined;
    return resolvePaint(value, ctx, 1);
  }

  /* Prepares every path's paints, then hands back a painter that draws the whole tree. Null when any
     part can't be drawn faithfully (a theme attribute colour, a trimmed path), so the caller falls
     back instead of showing a picture missing a piece. */
  async function vectorPainter(root, ctx) {
    var vw = num(root, "android:viewportWidth", 0);
    var vh = num(root, "android:viewportHeight", 0);
    if (!(vw > 0 && vh > 0)) return null;
    var paints = new Map();
    var shapes = new Map();
    var outlines = Array.prototype.slice.call(root.getElementsByTagName("path"))
      .concat(Array.prototype.slice.call(root.getElementsByTagName("clip-path")));
    for (var k = 0; k < outlines.length; k++) {
      var data = outlines[k].getAttribute("android:pathData");
      var stringRef = /^@string\/([\w.]+)$/.exec((data || "").trim());
      if (stringRef) {
        data = await ctx.strings.lookup(stringRef[1]);
        if (!data) return null;
      }
      shapes.set(outlines[k], data || "");
    }
    var paths = root.getElementsByTagName("path");
    for (var i = 0; i < paths.length; i++) {
      var p = paths[i];
      if (num(p, "android:trimPathStart", 0) !== 0 || num(p, "android:trimPathEnd", 1) !== 1) return null;
      var fill = await attrPaint(p, "android:fillColor", ctx);
      var stroke = await attrPaint(p, "android:strokeColor", ctx);
      if (fill === null || stroke === null) return null;
      paints.set(p, { fill: fill, stroke: stroke });
    }
    var tint = root.getAttribute("android:tint");
    var tintPaint = null;
    if (tint) {
      tintPaint = await resolvePaint(tint, ctx, 1);
      if (!tintPaint || !tintPaint.css) return null;
    }
    var alpha = num(root, "android:alpha", 1);

    function drawNode(g, node) {
      for (var child = node.firstElementChild; child; child = child.nextElementSibling) {
        var tag = child.tagName;
        if (tag === "group") {
          g.save();
          var px = num(child, "android:pivotX", 0);
          var py = num(child, "android:pivotY", 0);
          g.translate(num(child, "android:translateX", 0), num(child, "android:translateY", 0));
          g.translate(px, py);
          g.rotate((num(child, "android:rotation", 0) * Math.PI) / 180);
          g.scale(num(child, "android:scaleX", 1), num(child, "android:scaleY", 1));
          g.translate(-px, -py);
          drawNode(g, child);
          g.restore();
        } else if (tag === "clip-path") {
          if (shapes.get(child)) g.clip(new Path2D(shapes.get(child)));
        } else if (tag === "path") {
          drawPath(g, child);
        }
      }
    }

    function drawPath(g, el) {
      var paint = paints.get(el);
      if (!paint || !shapes.get(el)) return;
      var path = new Path2D(shapes.get(el));
      if (paint.fill) {
        g.save();
        g.globalAlpha *= num(el, "android:fillAlpha", 1);
        g.fillStyle = canvasPaint(g, paint.fill) || "rgba(0,0,0,0)";
        g.fill(path, el.getAttribute("android:fillType") === "evenOdd" ? "evenodd" : "nonzero");
        g.restore();
      }
      var width = num(el, "android:strokeWidth", 0);
      if (paint.stroke && width > 0) {
        g.save();
        g.globalAlpha *= num(el, "android:strokeAlpha", 1);
        g.strokeStyle = canvasPaint(g, paint.stroke) || "rgba(0,0,0,0)";
        g.lineWidth = width;
        g.lineCap = el.getAttribute("android:strokeLineCap") || "butt";
        g.lineJoin = el.getAttribute("android:strokeLineJoin") || "miter";
        g.miterLimit = num(el, "android:strokeMiterLimit", 4);
        g.stroke(path);
        g.restore();
      }
    }

    function drawTree(g, x, y, w, h) {
      g.save();
      g.translate(x, y);
      g.scale(w / vw, h / vh);
      g.globalAlpha *= alpha;
      drawNode(g, root);
      g.restore();
    }

    return {
      draw: function (g, x, y, w, h) {
        if (!tintPaint) return drawTree(g, x, y, w, h);
        // A tint (default mode src_in) recolours everything drawn, keeping only its coverage.
        var off = document.createElement("canvas");
        off.width = g.canvas.width;
        off.height = g.canvas.height;
        var o = off.getContext("2d");
        drawTree(o, x, y, w, h);
        o.globalCompositeOperation = "source-in";
        o.fillStyle = tintPaint.css;
        o.fillRect(0, 0, off.width, off.height);
        g.drawImage(off, 0, 0);
      },
    };
  }

  /* ---------- Finding the icon ---------- */

  var MANIFEST = /(^|\/)AndroidManifest\.xml$/;
  var NOT_SHIPPED = /(^|\/)(test|androidTest|build|benchmark|baselineprofile|samples?|examples?|demo)\//i;

  /* The application's icon references from the manifests most likely to be the shipped app's, plus
     the module each was read in. A placeholder (${appIcon}) is skipped: the names tried after the
     manifests usually find that icon anyway. */
  async function manifestIcons(repo) {
    var manifests = repo.paths.filter(function (p) { return MANIFEST.test(p) && !NOT_SHIPPED.test(p); })
      .map(function (p) { return { path: p, root: p.replace(/\/?AndroidManifest\.xml$/, "") }; })
      .sort(function (a, b) {
        return (isVariant(a.root) ? 1 : 0) - (isVariant(b.root) ? 1 : 0) ||
          sourceSetRank(a.root) - sourceSetRank(b.root) ||
          moduleTokenRank(a.root, repo.tokens) - moduleTokenRank(b.root, repo.tokens) ||
          a.path.length - b.path.length;
      })
      .slice(0, MAX_MANIFESTS);
    var texts = await Promise.all(manifests.map(function (m) { return repo.text(m.path); }));
    var found = [];
    texts.forEach(function (text, i) {
      if (!text) return;
      text = text.replace(/<!--[\s\S]*?-->/g, "");
      var app = /<application\b[^>]*>/.exec(text);
      var icon = app && /\bandroid:icon\s*=\s*"([^"]+)"/.exec(app[0]);
      if (icon && parseRef(icon[1])) found.push({ ref: icon[1], root: manifests[i].root });
    });
    return found;
  }

  var FALLBACK_NAMES = ["ic_launcher", "app_icon", "ic_app_icon", "ic_app", "launcher_icon", "icon", "logo"];

  /* The <adaptive-icon> definitions worth trying, the manifests' own first, variants included (see
     iconFrom for when those are tried). */
  async function adaptiveCandidates(repo) {
    var refs = await manifestIcons(repo);
    FALLBACK_NAMES.forEach(function (name) {
      refs.push({ ref: "@mipmap/" + name, root: null }, { ref: "@drawable/" + name, root: null });
    });
    var seen = {};
    var out = [];
    refs.forEach(function (r) {
      var ref = parseRef(r.ref);
      if (!ref || ref.kind === "color") return;
      repo.find(ref.kind, ref.name, r.root, r.root).forEach(function (e) {
        if (e.ext !== "xml" || seen[e.path]) return;
        seen[e.path] = true;
        out.push({ entry: e, appRoot: r.root });
      });
    });
    return out;
  }

  async function composeAdaptive(repo, candidates) {
    for (var i = 0; i < candidates.length; i++) {
      var e = candidates[i].entry;
      var doc = await repo.xml(e.path);
      if (!doc || doc.documentElement.tagName !== "adaptive-icon") continue;
      var appRoot = candidates[i].appRoot;
      var ctx = {
        repo: repo,
        root: e.root,
        appRoot: appRoot,
        colors: new ValuesTable(repo, e.root, appRoot, "color"),
        strings: new ValuesTable(repo, e.root, appRoot, "string"),
      };
      var bg = doc.getElementsByTagName("background")[0];
      var fg = doc.getElementsByTagName("foreground")[0];
      if (!fg) continue;
      var layers = await Promise.all([bg ? childPainter(bg, ctx, 0) : null, childPainter(fg, ctx, 0)]);
      // A background that is there but can't be read would leave the artwork on nothing.
      if ((bg && !layers[0]) || !layers[1]) continue;
      var canvas = document.createElement("canvas");
      canvas.width = canvas.height = SIZE;
      var g = canvas.getContext("2d");
      // Layers are 108dp; a launcher shows the middle 72dp of them.
      var full = SIZE * 1.5;
      var dp = full / 108;
      if (layers[0]) layers[0].draw(g, -SIZE / 4, -SIZE / 4, full, full, dp);
      layers[1].draw(g, -SIZE / 4, -SIZE / 4, full, full, dp);
      if (!isBlank(g)) return canvas.toDataURL("image/png");
    }
    return null;
  }

  function isBlank(g) {
    var data = g.getImageData(0, 0, SIZE, SIZE).data;
    for (var i = 3; i < data.length; i += 4 * 37) {
      if (data[i] !== 0) return false;
    }
    return true;
  }

  /* ---------- Raster fallbacks ---------- */

  var STORE_ICON = /(^|\/)metadata\/(.+\/)?images\/icon\.(png|webp|jpe?g)$/i;
  /* The full-size copy Android Studio's icon wizard writes next to res/. */
  var STUDIO_ICON = /(^|\/)src\/[^/]+\/ic_launcher-(playstore|web)\.png$/i;
  var EXCLUDED_STEMS = /background|monochrome|notification|splash|banner|feature|badge|store|source|_back$|_bg$/;

  /* Raster icons best first, as rankIconPaths in ExternalApi.kt: the store listing icon (drawn for a
     listing, so current), the icon wizard's full-size copy, then the manifest's own icon as a
     raster, then other launcher-named rasters, square before round before a bare foreground. */
  async function rasterCandidates(repo) {
    var store = repo.paths.filter(function (p) { return STORE_ICON.test(p); }).sort(function (a, b) {
      return rank(a) - rank(b) || a.length - b.length;
      function rank(p) { return /\/en-US\//.test(p) ? 0 : /\/en\//.test(p) ? 1 : 2; }
    });
    var out = store.slice(0, 1).map(function (p) { return { path: p, root: "" }; });
    repo.paths.filter(function (p) { return STUDIO_ICON.test(p); }).sort(function (a, b) {
      return sourceSetRank(dirOf(a)) - sourceSetRank(dirOf(b)) || (/playstore/.test(a) ? 0 : 1) - (/playstore/.test(b) ? 0 : 1);
    }).forEach(function (p) { out.push({ path: p, root: dirOf(p) }); });
    var refs = await manifestIcons(repo);
    refs.forEach(function (r) {
      var ref = parseRef(r.ref);
      if (!ref) return;
      repo.find(ref.kind, ref.name, r.root, r.root).forEach(function (e) {
        if (e.ext !== "xml" && qualifierRank(e.qualifier) < 9) out.push(e);
      });
    });
    var named = [];
    Object.keys(repo.res).forEach(function (key) {
      var kind = key.split("/")[0];
      if (kind !== "mipmap" && kind !== "drawable") return;
      repo.res[key].forEach(function (e) {
        if (e.ext === "xml" || qualifierRank(e.qualifier) >= 9) return;
        var stem = e.name.toLowerCase();
        if (EXCLUDED_STEMS.test(stem)) return;
        var launcher = /^ic_launcher|^icon|^appicon|^app_icon|launcher/.test(stem);
        if (!launcher) return;
        var variant = /_foreground$|_fore$|_fg$/.test(stem) ? 2 : /_round$/.test(stem) ? 1 : 0;
        named.push({ e: e, variant: variant });
      });
    });
    named.sort(function (a, b) {
      return a.variant - b.variant ||
        placeRank(a.e.root, null, null) - placeRank(b.e.root, null, null) ||
        densityRank(a.e.qualifier) - densityRank(b.e.qualifier);
    });
    named.forEach(function (n) { out.push(n.e); });
    var seen = {};
    return out.filter(function (e) {
      if (seen[e.path]) return false;
      seen[e.path] = true;
      return true;
    });
  }

  function dirOf(path) {
    return path.replace(/\/[^/]+$/, "");
  }

  /* Apps built with Expo or Cordova declare their icon in a config file instead of an Android project
     the repository holds (Bluesky's app.config.js): Android's own entry first, a bare foreground
     layer last. */
  var APP_CONFIG = /(^|\/)(app\.json|app\.config\.(js|ts)|config\.xml)$/;
  var DECLARED_ICON = /["']?(?:icon|foregroundImage|src)["']?\s*[:=]\s*["']([^"'\s]+\.(?:png|webp|jpe?g))["']/g;

  async function declaredIcons(repo) {
    var configs = repo.paths.filter(function (p) {
      return APP_CONFIG.test(p) && !NOT_LOGO_DIRS.test(p) && !/(^|\/)res\//.test(p);
    }).sort(function (a, b) { return a.split("/").length - b.split("/").length; }).slice(0, 3);
    var texts = await Promise.all(configs.map(function (p) { return repo.text(p); }));
    var found = [];
    texts.forEach(function (text, i) {
      if (!text) return;
      var dir = configs[i].indexOf("/") === -1 ? "" : dirOf(configs[i]) + "/";
      var m;
      DECLARED_ICON.lastIndex = 0;
      while ((m = DECLARED_ICON.exec(text))) {
        var path = dir + m[1].replace(/^\.\//, "");
        if (!repo.has[path]) continue;
        found.push({ path: path, rank: /foreground|monochrome/i.test(path) ? 2 : /android/i.test(path) ? 0 : 1 });
      }
    });
    found.sort(function (a, b) { return a.rank - b.rank; });
    return found.map(function (f) { return f.path; });
  }

  /* For a project with no Android icon at all (a desktop or web app) and no organisation avatar: an
     image named like the project or as its logo, shallowest first. */
  var LOGO_FILE = /(^|\/)([^/]+)\.(png|webp|svg|jpe?g)$/i;
  var NOT_LOGO_DIRS = /(^|\/)(node_modules|build|dist|test|tests|screenshots?|fastlane|metadata|third[-_]?party|vendor|external)\//i;

  function logoCandidates(repo) {
    var picks = [];
    repo.paths.forEach(function (p) {
      var m = LOGO_FILE.exec(p);
      if (!m || NOT_LOGO_DIRS.test(p)) return;
      var stem = m[2].toLowerCase();
      var rank = /^(logo|app[-_]?icon|icon)$/.test(stem) ? 1 : -1;
      if (repo.tokens.indexOf(stem) !== -1) rank = 0;
      else if (rank < 0 && /logo/.test(stem) && tokenRank(stem, repo.tokens) === 0) rank = 2;
      if (rank < 0) return;
      picks.push({ path: p, rank: rank, depth: p.split("/").length });
    });
    picks.sort(function (a, b) { return a.rank - b.rank || a.depth - b.depth; });
    return picks.slice(0, 3).map(function (e) { return e.path; });
  }

  async function drawRaster(image) {
    var canvas = document.createElement("canvas");
    canvas.width = canvas.height = SIZE;
    var g = canvas.getContext("2d");
    var scale = Math.min(SIZE / image.width, SIZE / image.height);
    var w = image.width * scale;
    var h = image.height * scale;
    g.drawImage(image, (SIZE - w) / 2, (SIZE - h) / 2, w, h);
    return isBlank(g) ? null : canvas.toDataURL("image/png");
  }

  async function firstRaster(repo, paths) {
    for (var i = 0; i < Math.min(paths.length, 6); i++) {
      var img = await repo.image(paths[i]);
      var url = img && img.width >= 16 ? await drawRaster(img) : null;
      if (url) return url;
    }
    return null;
  }

  async function avatarIcon(host) {
    try {
      var url = await host.avatar();
      if (!url) return null;
      var res = await fetchWithTimeout(url);
      if (!res.ok) return null;
      return drawRaster(await createImageBitmap(await res.blob()));
    } catch (failed) {
      return null;
    }
  }

  function within(ms, promise) {
    return Promise.race([promise, new Promise(function (resolve) { setTimeout(function () { resolve(null); }, ms); })]);
  }

  /* The shipped app's own icon in every form it comes in is tried before any variant's: when its
     adaptive icon can't be drawn (Proton Mail's colours come from a library outside the
     repository), its store or launcher raster is the right answer, a development build's green
     recolouring is not. */
  async function iconFrom(host, name) {
    var paths = await host.paths();
    if (!paths) return { reached: false };
    var repo = new Repo(host, paths, name);
    var candidates = await adaptiveCandidates(repo);
    var rasters = await rasterCandidates(repo);
    var shipped = function (variant) {
      return function (e) { return isVariant((e.entry || e).root) === variant; };
    };
    var pathOf = function (e) { return e.path; };
    var url = await within(ADAPTIVE_BUDGET_MS, composeAdaptive(repo, candidates.filter(shipped(false)))) ||
      await firstRaster(repo, rasters.filter(shipped(false)).map(pathOf)) ||
      await firstRaster(repo, await declaredIcons(repo)) ||
      await composeAdaptive(repo, candidates.filter(shipped(true))) ||
      await firstRaster(repo, rasters.filter(shipped(true)).map(pathOf)) ||
      await avatarIcon(host) ||
      await firstRaster(repo, logoCandidates(repo));
    return { reached: true, url: url };
  }

  /* The public entry point: a data URL for the project's launcher icon, or null. [host] is the
     address's host ("" or "github.com" for GitHub). Never rejects, and gives up after
     OVERALL_TIMEOUT_MS: the caller never has to tell "still trying" from "can't". */
  window.OmnifyComposeIcon = function (host, owner, repo) {
    return within(OVERALL_TIMEOUT_MS, (async function () {
      var hosts = hostsFor(host, owner, repo);
      for (var i = 0; i < hosts.length; i++) {
        try {
          var result = await iconFrom(hosts[i], repo);
          if (result.reached) return result.url || null;
        } catch (unexpected) {
          return null;
        }
      }
      return null;
    })());
  };
})();
