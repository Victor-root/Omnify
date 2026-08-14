/* Best-effort real app icon for the add page's target card (see add.html/add.js), composited the
   same way Android itself does: read the manifest for the real icon resource name (never assumed,
   since "ic_launcher" is only Android Studio's default suggestion; real projects rename it, or lay
   resources out under a module that isn't even called "app"), find its adaptive-icon definition, and
   draw its background and foreground layers onto a canvas exactly like the OS would.

   Mirrors AdaptiveIconComposer.kt on the app side, adapted to what a browser already gives for free:
   DOMParser reads the resource XML natively (no hand-written comment-stripping scan), and Canvas's
   Path2D understands the same path-data syntax Android's vector drawables use, so there is no
   hand-written path interpreter here either, which is why this is a few hundred lines instead of
   what that took.

   GitHub only: this reads the repository through GitHub's own API rather than guessing raw-file
   paths, so it does not share the branch-naming fragility that keeps GitLab/Codeberg/self-hosted out
   of the older, simpler guess (see the fallback this replaces in add.js). Extending it to those is
   possible, each has its own equivalent tree API, but is future work rather than something folded in
   blind here.

   Every step below can fail: the project genuinely doesn't have an adaptive icon, its foreground uses
   a gradient or a stroke (unsupported, same limits as the app-side composer), the API rate limit is
   spent, a module lives somewhere this can't find. Every failure resolves to null, quietly, so the
   caller's own initial-letter tile stays exactly as good a fallback as it already was. */
(function () {
  "use strict";

  var CANVAS_SIZE = 128;
  var REQUEST_TIMEOUT_MS = 6000;
  var OVERALL_TIMEOUT_MS = 12000;

  function escapeRegExp(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  /* A composite icon is several requests deep (tree, manifest, adaptive-icon XML, two layers, a colour
     file), any one of which can stall rather than cleanly fail: a slow connection, a proxy that holds
     a request open, a domain that's reachable but not answering. Nothing here is worth the reader
     waiting on, so every request that goes out carries its own deadline. */
  function fetchWithTimeout(url, options) {
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, REQUEST_TIMEOUT_MS);
    return fetch(url, Object.assign({ signal: controller.signal }, options || {}))
      .finally(function () { clearTimeout(timer); });
  }

  async function getJson(url) {
    var res = await fetchWithTimeout(url, { headers: { Accept: "application/vnd.github+json" } });
    if (!res.ok) return null;
    return res.json();
  }

  async function getText(owner, repo, path) {
    var url = "https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/" + path;
    var res = await fetchWithTimeout(url);
    if (!res.ok) return null;
    return res.text();
  }

  /* GitHub's raw-file CDN understands "HEAD" as the default branch (see add.js's own icon guess,
     which already relied on this); the API proper does not, so the tree listing needs the real
     branch name first. */
  async function fetchTree(owner, repo) {
    var info = await getJson("https://api.github.com/repos/" + owner + "/" + repo);
    if (!info || !info.default_branch) return null;
    var data = await getJson(
      "https://api.github.com/repos/" + owner + "/" + repo +
        "/git/trees/" + encodeURIComponent(info.default_branch) + "?recursive=1",
    );
    if (!data || !Array.isArray(data.tree)) return null;
    return data.tree.filter(function (e) { return e.type === "blob"; }).map(function (e) { return e.path; });
  }

  /* Every AndroidManifest.xml in the tree, main module first: excludes test sources (never the app's
     real icon) and prefers the shortest path, which is the main module's own manifest in every real
     layout seen so far: "app/src/main/AndroidManifest.xml", or just "src/main/..." for a project with
     no separate module directory. Tried in order rather than just taking the first, since a
     multi-module project can have several and only one is the one that ships. */
  function findManifests(paths) {
    return paths
      .filter(function (p) {
        return /(^|\/)AndroidManifest\.xml$/.test(p) &&
          !/\/(test|androidTest|build)\//.test(p);
      })
      .sort(function (a, b) { return a.length - b.length; });
  }

  function moduleRootOf(manifestPath) {
    return manifestPath.replace(/(^|\/)AndroidManifest\.xml$/, "").replace(/\/$/, "");
  }

  var XML_PARSE_ERROR = "parsererror";

  function parseXml(text) {
    var doc = new DOMParser().parseFromString(text, "application/xml");
    if (doc.getElementsByTagName(XML_PARSE_ERROR).length) return null;
    return doc;
  }

  /* "@mipmap/icon" -> {kind: "mipmap", name: "icon"}, same for drawable/color. Null for anything else
     (a themed/system icon reference, most often), which the caller treats as this project simply not
     being readable this way. */
  function parseResourceRef(ref) {
    var m = /^@(mipmap|drawable|color)\/([\w.]+)$/.exec((ref || "").trim());
    return m ? { kind: m[1], name: m[2] } : null;
  }

  function iconResourceRef(manifestDoc) {
    var app = manifestDoc.getElementsByTagName("application")[0];
    return app ? parseResourceRef(app.getAttribute("android:icon")) : null;
  }

  /* Every file under the module whose resource folder + name matches, any density qualifier, ranked
     highest-resolution-first for raster candidates so a compositing step that has to pick one gets the
     sharpest available. Vector candidates (density-independent, always full quality) sort ahead of all
     of them. */
  var DENSITY_RANK = ["xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi", "anydpi-v26", "anydpi", ""];

  function findResourceFiles(paths, moduleRoot, kind, name) {
    var folder = kind === "color" ? "values" : kind;
    var re = new RegExp(
      "^" + escapeRegExp(moduleRoot) + "/res/" + folder + "(-[^/]+)?/" +
        escapeRegExp(name) + "\\.(xml|png|webp)$",
    );
    var matches = paths.filter(function (p) { return re.test(p); }).map(function (p) {
      var m = re.exec(p);
      return { path: p, density: (m[1] || "").replace(/^-/, ""), ext: m[2] };
    });
    matches.sort(function (a, b) {
      if (a.ext !== b.ext) return a.ext === "xml" ? -1 : b.ext === "xml" ? 1 : 0;
      return DENSITY_RANK.indexOf(a.density) - DENSITY_RANK.indexOf(b.density);
    });
    return matches;
  }

  /* @color/xxx can live in any values-qualified .xml file, not necessarily colors.xml, so every one is
     a candidate in principle, but a real project's values-xx/ locale folders hold nothing except
     translated strings, and searching those first (found only by ever having to, on a project with
     enough of them) can spend the whole cap before ever reaching the one real candidate. So the plain,
     unqualified values/ folder goes first, files actually named colors*.xml next, everything else
     last, and only the ones an unremarkable project would never need go uninspected.

     A named color is itself allowed to be another reference instead of a literal hex, and real
     projects use exactly that to keep a semantic name ("color_primary_light") pointing at a shared
     palette entry ("google_blue_600") defined elsewhere: seen for real on Victor-root/VFiles, whose
     adaptive icon background went unresolved (and the whole icon fell back to the initial letter)
     until this followed the chain instead of stopping at the first non-hex value. Hops are capped and
     a name already seen ends the search, so a typo'd or circular reference fails quietly rather than
     spinning. */
  async function resolveColor(owner, repo, paths, moduleRoot, name) {
    var re = new RegExp("^" + escapeRegExp(moduleRoot) + "/res/values(-[^/]+)?/([^/]+)\\.xml$");
    var files = paths.filter(function (p) { return re.test(p); }).sort(function (a, b) {
      return rank(a) - rank(b);
      function rank(p) {
        var m = re.exec(p);
        if (!m[1]) return m[2].indexOf("colors") === 0 ? 0 : 1;
        return m[2].indexOf("colors") === 0 ? 2 : 3;
      }
    }).slice(0, 8);

    var textCache = {};
    async function textOf(path) {
      if (!(path in textCache)) textCache[path] = await getText(owner, repo, path);
      return textCache[path];
    }

    var seenNames = {};
    for (var hop = 0; hop < 5; hop++) {
      if (seenNames[name]) return null;
      seenNames[name] = true;
      var found = null;
      for (var i = 0; i < files.length; i++) {
        var text = await textOf(files[i]);
        if (!text) continue;
        var m = new RegExp(
          '<color\\s+name="' + escapeRegExp(name) + '"\\s*>\\s*(#[0-9a-fA-F]+|@color/[\\w.]+)\\s*<',
        ).exec(text);
        if (m) { found = m[1]; break; }
      }
      if (!found) return null;
      if (found.charAt(0) === "#") return androidColorToCss(found);
      name = found.slice("@color/".length);
    }
    return null;
  }

  /* Android puts alpha first (#AARRGGBB); CSS puts it last. A plain #RRGGBB (or #RGB) passes straight
     through, valid as-is. */
  function androidColorToCss(hex) {
    var h = hex.replace("#", "");
    if (h.length === 8) {
      var a = parseInt(h.slice(0, 2), 16) / 255;
      return "rgba(" + parseInt(h.slice(2, 4), 16) + "," + parseInt(h.slice(4, 6), 16) + "," +
        parseInt(h.slice(6, 8), 16) + "," + a.toFixed(3) + ")";
    }
    return "#" + h;
  }

  /* True for anything this renderer knows it will draw wrong rather than merely plainly: a gradient
     fill (declared as a nested <aapt:attr>, not a plain fillColor) or a stroke a shape actually
     depends on. Checked so those bail out to the initial-letter tile instead of showing an icon
     missing the one detail that was the point of it. */
  function hasUnsupportedFeatures(vectorDoc) {
    var paths = vectorDoc.getElementsByTagName("path");
    for (var i = 0; i < paths.length; i++) {
      var p = paths[i];
      if (p.getElementsByTagName("aapt:attr").length) return true;
      var strokeColor = p.getAttribute("android:strokeColor");
      var strokeWidth = parseFloat(p.getAttribute("android:strokeWidth") || "0");
      if (strokeColor && strokeColor !== "@android:color/transparent" && strokeWidth > 0) return true;
    }
    return false;
  }

  function num(el, attr, fallback) {
    var v = el.getAttribute(attr);
    return v === null ? fallback : parseFloat(v);
  }

  /* Walks <group>/<path> exactly as Android's own vector drawable renderer does: a group's transform
     (pivot, scale, rotation, translate, in that order around the pivot) applies to every path inside
     it, nested groups compose. ctx.save()/restore() bracket each group so a transform never leaks to
     the group's own siblings. */
  function drawVectorNode(ctx, node) {
    for (var child = node.firstElementChild; child; child = child.nextElementSibling) {
      var tag = child.tagName;
      if (tag === "group") {
        ctx.save();
        var px = num(child, "android:pivotX", 0);
        var py = num(child, "android:pivotY", 0);
        ctx.translate(num(child, "android:translateX", 0), num(child, "android:translateY", 0));
        ctx.translate(px, py);
        ctx.rotate((num(child, "android:rotation", 0) * Math.PI) / 180);
        ctx.scale(num(child, "android:scaleX", 1), num(child, "android:scaleY", 1));
        ctx.translate(-px, -py);
        drawVectorNode(ctx, child);
        ctx.restore();
      } else if (tag === "path") {
        var data = child.getAttribute("android:pathData");
        var fillColor = child.getAttribute("android:fillColor");
        if (!data || !fillColor || fillColor === "@android:color/transparent") continue;
        var fillAlpha = num(child, "android:fillAlpha", 1);
        var rule = child.getAttribute("android:fillType") === "evenOdd" ? "evenodd" : "nonzero";
        ctx.fillStyle = fillAlpha < 1
          ? androidColorToCss(fillColor).replace(/rgba?\(([^)]+)\)/, function (_, inner) {
            var parts = inner.split(",");
            return "rgba(" + parts[0] + "," + parts[1] + "," + parts[2] + "," +
              (parts[3] !== undefined ? parseFloat(parts[3]) * fillAlpha : fillAlpha) + ")";
          })
          : androidColorToCss(fillColor);
        ctx.fill(new Path2D(data), rule);
      }
    }
  }

  function drawVector(ctx, vectorDoc, size) {
    var root = vectorDoc.documentElement;
    var vw = num(root, "android:viewportWidth", 108);
    var vh = num(root, "android:viewportHeight", 108);
    ctx.save();
    ctx.scale(size / vw, size / vh);
    drawVectorNode(ctx, root);
    ctx.restore();
  }

  /* <img> loading has no AbortController of its own, so the same deadline as every fetch() above is
     applied by hand: past it, the listeners are dropped (a very late load can't resolve a promise
     that's already moved on) and the image is walked away from rather than left loading forever. */
  function loadImage(url) {
    return new Promise(function (resolve, reject) {
      var img = new Image();
      img.crossOrigin = "anonymous";
      var timer = setTimeout(function () {
        img.onload = img.onerror = null;
        reject(new Error("image timed out: " + url));
      }, REQUEST_TIMEOUT_MS);
      img.onload = function () { clearTimeout(timer); resolve(img); };
      img.onerror = function () { clearTimeout(timer); reject(new Error("image failed: " + url)); };
      img.src = url;
    });
  }

  /* One layer's resource (background or foreground), fetched and decoded but not yet on the canvas:
     a colour, a parsed vector document, or a loaded image, whichever the reference turned out to be.
     Kept apart from painting so background and foreground can be resolved at the same time (see
     composeIconInternal) instead of the foreground's fetch only starting once the background's has
     entirely finished, which used to be most of the wait before an icon appeared. */
  async function resolveLayer(owner, repo, paths, moduleRoot, ref) {
    if (ref.kind === "color") {
      var css = await resolveColor(owner, repo, paths, moduleRoot, ref.name);
      return css ? { fill: css } : null;
    }
    var candidates = findResourceFiles(paths, moduleRoot, ref.kind, ref.name);
    for (var i = 0; i < candidates.length; i++) {
      var c = candidates[i];
      try {
        if (c.ext === "xml") {
          var text = await getText(owner, repo, c.path);
          if (!text) continue;
          var doc = parseXml(text);
          if (!doc) continue;
          // A plain (non-adaptive-icon) vector drawable's root is <vector> directly.
          if (doc.documentElement.tagName !== "vector") continue;
          if (hasUnsupportedFeatures(doc)) continue;
          return { vector: doc };
        }
        var img = await loadImage(
          "https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/" + c.path,
        );
        return { image: img };
      } catch (thisCandidateFailed) {
        // Try the next density/format rather than giving up on the whole layer.
      }
    }
    return null;
  }

  /* A resolved layer, drawn full-bleed across the whole canvas: a raster adaptive-icon asset is
     already authored at the full 108dp canvas size by convention, so it is never re-centered or
     re-scaled beyond fitting the target size, only the vector case carries its own transform (see
     drawVector). Background painted before foreground is what makes it an adaptive icon rather than
     two unrelated images, so the two calls in composeIconInternal are kept in that order even though
     resolving the layers ahead of this is not. */
  function paintLayer(ctx, layer, size) {
    if (layer.fill) {
      ctx.fillStyle = layer.fill;
      ctx.fillRect(0, 0, size, size);
    } else if (layer.vector) {
      drawVector(ctx, layer.vector, size);
    } else {
      ctx.drawImage(layer.image, 0, 0, size, size);
    }
  }

  /* The public entry point: a data URL for [owner]/[repo]'s real launcher icon, or null. Every
     resolution step is independently allowed to fail; only a fully successful background AND
     foreground composite is ever handed back, since half an icon reads as broken, not as "close
     enough".

     Wrapped in one overall deadline on top of every individual request's own (see
     REQUEST_TIMEOUT_MS): a project with an unusually large tree, or one that needs several manifest
     or density candidates tried in turn before one works, can rack up more requests than any single
     timeout accounts for. Past it, this resolves to null exactly like a single failed request would,
     never rejects; the caller never has to tell "still trying" apart from "genuinely can't". */
  window.OmnifyComposeIcon = function (owner, repo) {
    return Promise.race([
      composeIconInternal(owner, repo),
      new Promise(function (resolve) {
        setTimeout(function () { resolve(null); }, OVERALL_TIMEOUT_MS);
      }),
    ]);
  };

  async function composeIconInternal(owner, repo) {
    try {
      var paths = await fetchTree(owner, repo);
      if (!paths) return null;
      var manifests = findManifests(paths);
      for (var i = 0; i < manifests.length; i++) {
        var manifestText = await getText(owner, repo, manifests[i]);
        if (!manifestText) continue;
        var manifestDoc = parseXml(manifestText);
        if (!manifestDoc) continue;
        var iconRef = iconResourceRef(manifestDoc);
        if (!iconRef || iconRef.kind === "color") continue;
        var moduleRoot = moduleRootOf(manifests[i]);

        var adaptiveXmlPath = findResourceFiles(paths, moduleRoot, iconRef.kind, iconRef.name)
          .filter(function (c) { return c.ext === "xml"; })[0];
        if (!adaptiveXmlPath) continue;
        var adaptiveText = await getText(owner, repo, adaptiveXmlPath.path);
        if (!adaptiveText) continue;
        var adaptiveDoc = parseXml(adaptiveText);
        if (!adaptiveDoc || adaptiveDoc.documentElement.tagName !== "adaptive-icon") continue;

        var bg = adaptiveDoc.getElementsByTagName("background")[0];
        var fg = adaptiveDoc.getElementsByTagName("foreground")[0];
        var bgRef = bg && parseResourceRef(bg.getAttribute("android:drawable"));
        var fgRef = fg && parseResourceRef(fg.getAttribute("android:drawable"));
        if (!bgRef || !fgRef) continue;

        var layers = await Promise.all([
          resolveLayer(owner, repo, paths, moduleRoot, bgRef),
          resolveLayer(owner, repo, paths, moduleRoot, fgRef),
        ]);
        if (!layers[0] || !layers[1]) continue;

        var canvas = document.createElement("canvas");
        canvas.width = canvas.height = CANVAS_SIZE;
        var ctx = canvas.getContext("2d");
        paintLayer(ctx, layers[0], CANVAS_SIZE);
        paintLayer(ctx, layers[1], CANVAS_SIZE);
        return canvas.toDataURL("image/png");
      }
      return null;
    } catch (unexpected) {
      return null;
    }
  }
})();
