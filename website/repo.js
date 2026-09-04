/* The repository page, in its two halves (see repo.html).

   With a repository in the link, it hands that repository to the app and, if nothing answers, says
   where to get the app instead. Without one, it is the documentation whoever runs a repository lands
   on, and builds the link for them.

   The repository rides in the fragment rather than the query, which is the reason this page exists in
   the shape it does: everything after the # stays in the browser and is never sent to a web server,
   so an address and a username reach the phone they were sent to without passing through the logs of
   whatever host serves this page.

   Loaded after script.js so the shared nav, theme, language and copy-button wiring is already in
   place; nothing generated here carries a data-i18n attribute, so switching language never wipes it. */
(function () {
  "use strict";

  /* The scheme F-Droid clients register. fdroidrepos is the https one, which is the only one worth
     building links for; fdroidrepo is read as well, since a link may arrive written either way. */
  var SCHEMES = ["fdroidrepos://", "fdroidrepo://"];

  function shared() {
    var raw = window.location.hash.slice(1).trim();
    if (raw === "") return "";
    /* Written plainly by the generator below, since a fragment is allowed to hold : / ? and @ as they
       stand and the link reads better for it. Percent-encoded is understood too, because that is what
       a messaging app or a QR reader may hand back after passing it around. */
    for (var i = 0; i < SCHEMES.length; i++) {
      if (raw.toLowerCase().indexOf(SCHEMES[i]) === 0) return raw;
    }
    try {
      return decodeURIComponent(raw);
    } catch (ignored) {
      return raw;
    }
  }

  /* {address, host, path, fingerprint, username, hasPassword} read from a repository link, or null
     when there is no repository in it. Display only: the app does its own parsing and is the one that
     decides what the link really names. */
  function parseRepo(link) {
    var text = link;
    for (var i = 0; i < SCHEMES.length; i++) {
      if (text.toLowerCase().indexOf(SCHEMES[i]) === 0) {
        text = "https://" + text.slice(SCHEMES[i].length);
        break;
      }
    }
    if (!/^https?:\/\//i.test(text)) return null;

    var url;
    try {
      url = new URL(text);
    } catch (ignored) {
      return null;
    }
    if (!url.hostname) return null;

    var fingerprint =
      url.searchParams.get("fingerprint") || url.searchParams.get("FINGERPRINT") || "";
    var path = url.pathname.replace(/\/+$/, "");
    return {
      address: url.protocol + "//" + url.host + path,
      host: url.host,
      path: path,
      fingerprint: fingerprint.trim(),
      username: decodeURIComponent(url.username || ""),
      hasPassword: url.password !== "",
    };
  }

  /* Grouped in fours, the way a fingerprint is written everywhere else, so a reader can compare it
     against the one their repository shows without counting characters. */
  function groupFingerprint(value) {
    return value.replace(/(.{4})/g, "$1 ").trim();
  }

  /* This page's own address, which is what a shared link points at. */
  function pageLink(target) {
    var base = window.location.origin + window.location.pathname;
    return base + "#" + target;
  }

  /* Reads a string already picked by script.js (see its own translate()), for the copy generated
     here rather than sitting in the HTML. Mirrors script.js's lookup against the same dictionary and
     the same lang attribute it sets on <html>, which it has done by the time this file loads (see
     repo.html's script order). */
  function t(key) {
    var lang = document.documentElement.getAttribute("lang") || "en";
    var dict = window.OMNIFY_I18N || {};
    var table = dict[lang] || {};
    if (typeof table[key] === "string") return table[key];
    var fallback = dict.en || {};
    return typeof fallback[key] === "string" ? fallback[key] : "";
  }

  function fact(term, description) {
    var dt = document.createElement("dt");
    dt.textContent = term;
    var dd = document.createElement("dd");
    dd.textContent = description;
    return [dt, dd];
  }

  /* ---------- Someone was sent a repository ---------- */

  var link = shared();

  if (link) {
    var repo = parseRepo(link);
    var ok = document.getElementById("repo-ok");
    var invalid = document.getElementById("repo-invalid");

    if (!repo) {
      ok.hidden = true;
      invalid.hidden = false;
    } else {
      document.getElementById("repo-host").textContent = repo.host;

      /* What the link carries, spelled out before anything is added: a repository address decides
         where APKs come from, so it is worth reading rather than trusting blind. A password, if the
         sender put one in against the advice below, is acknowledged and never shown. */
      var facts = document.getElementById("repo-facts");
      var rows = [];
      if (repo.path) rows.push(fact(t("repolink.factPath"), repo.path));
      if (repo.username) rows.push(fact(t("repolink.factUsername"), repo.username));
      if (repo.fingerprint) {
        rows.push(fact(t("repolink.factFingerprint"), groupFingerprint(repo.fingerprint)));
      }
      if (repo.hasPassword) rows.push(fact(t("repolink.factPassword"), t("repolink.factPasswordYes")));
      rows.forEach(function (row) {
        facts.appendChild(row[0]);
        facts.appendChild(row[1]);
      });

      /* Omnify is Android only (see the mode-desktop check in repo.html's head): on a phone, the
         block below tries to open it directly; on a desktop, there is nothing to open, so a QR code
         hands the same link to whatever the reader scans it with, landing them on this exact page
         again but on their phone, where this same block runs and takes it from there. */
      if (document.documentElement.classList.contains("mode-desktop")) {
        var qrTarget = document.getElementById("repo-qr");
        if (qrTarget && window.qrcode) {
          /* High error correction rather than the library's default, since the mark below covers
             part of the code: a QR can lose up to ~30% of itself to damage or, as here, decoration
             and still read correctly at that level, well above what a centred mark this size costs. */
          var qr = window.qrcode(0, "H");
          qr.addData(pageLink(link));
          qr.make();
          qrTarget.innerHTML = qr.createSvgTag({ scalable: true, alt: t("repolink.qrAlt") });

          var svg = qrTarget.querySelector("svg");
          if (svg) {
            var size = svg.viewBox.baseVal.width;
            var markSize = size * 0.22;
            var pad = markSize * 0.16;
            var offset = (size - markSize) / 2;
            var svgNs = "http://www.w3.org/2000/svg";

            var backing = document.createElementNS(svgNs, "rect");
            backing.setAttribute("x", offset - pad);
            backing.setAttribute("y", offset - pad);
            backing.setAttribute("width", markSize + pad * 2);
            backing.setAttribute("height", markSize + pad * 2);
            backing.setAttribute("rx", pad * 2);
            backing.setAttribute("fill", "#fff");
            svg.appendChild(backing);

            var mark = document.createElementNS(svgNs, "image");
            mark.setAttributeNS("http://www.w3.org/1999/xlink", "href", "assets/omnify-logo.svg");
            mark.setAttribute("href", "assets/omnify-logo.svg");
            mark.setAttribute("x", offset);
            mark.setAttribute("y", offset);
            mark.setAttribute("width", markSize);
            mark.setAttribute("height", markSize);
            svg.appendChild(mark);
          }
        }
      } else {
        var button = document.getElementById("repo-open-btn");
        var fallback = document.getElementById("repo-fallback");

        /* A real href rather than a click handler, so opening the app is an ordinary navigation the
           reader asked for. That is the one form a browser never second-guesses, and it keeps working
           with this script disabled entirely. */
        button.href = link;

        /* The app coming to the front puts this page in the background. Watched so a link that worked
           is never followed by a message telling its reader it didn't. Deliberately only the two
           signals that mean this page genuinely stopped being on screen: every false positive here
           silently withholds the download link from someone who does not have the app. */
        var opened = false;
        function markOpened() {
          opened = true;
        }
        document.addEventListener("visibilitychange", function () {
          if (document.hidden) markOpened();
        });
        window.addEventListener("pagehide", markOpened);

        /* Tried once unasked, since opening the link already said what the reader wants, but through
           a throwaway frame rather than by navigating this page. Sending the page itself to a scheme
           nothing answers can strand it on a browser error, which would take away the very fallback
           below that this page exists to offer. A frame can only fail quietly. */
        try {
          var probe = document.createElement("iframe");
          probe.hidden = true;
          probe.setAttribute("aria-hidden", "true");
          probe.style.display = "none";
          probe.src = link;
          document.body.appendChild(probe);
          window.setTimeout(function () {
            if (probe.parentNode) probe.parentNode.removeChild(probe);
          }, 1500);
        } catch (ignored) {
          /* No frame, no attempt: the button below is the reliable path either way. */
        }

        /* Worded as a question rather than a verdict, because a browser that quietly refused the
           attempt above is indistinguishable here from one where the app simply isn't installed. */
        var reveal;
        function armFallback() {
          window.clearTimeout(reveal);
          reveal = window.setTimeout(function () {
            if (!opened && !document.hidden) fallback.hidden = false;
          }, 2500);
        }
        button.addEventListener("click", function () {
          opened = false;
          fallback.hidden = true;
          armFallback();
        });
        armFallback();
      }
    }
  }

  /* ---------- Someone wants a link to share ---------- */

  var addressInput = document.getElementById("gen-address");
  if (addressInput) {
    var fingerprintInput = document.getElementById("gen-fingerprint");
    var usernameInput = document.getElementById("gen-username");
    var out = document.getElementById("gen-out");
    var empty = document.getElementById("gen-empty");
    var code = document.getElementById("gen-link");

    var refresh = function () {
      var address = addressInput.value.trim();
      var parsed = address === "" ? null : parseRepo(address);

      out.hidden = !parsed;
      empty.hidden = !!parsed;
      if (!parsed) return;

      /* Spaces are what a copied fingerprint arrives with, and the app writes it that way too, so
         they are taken out here rather than turned into a link nobody can use. */
      var fingerprint = fingerprintInput.value.replace(/\s+/g, "");
      var username = usernameInput.value.trim();

      var target = "fdroidrepos://";
      if (username !== "") target += encodeURIComponent(username) + "@";
      target += parsed.host + parsed.path;
      if (fingerprint !== "") target += "?fingerprint=" + encodeURIComponent(fingerprint);

      code.textContent = pageLink(target);
    };

    [addressInput, fingerprintInput, usernameInput].forEach(function (input) {
      input.addEventListener("input", refresh);
    });
    refresh();
  }
})();
