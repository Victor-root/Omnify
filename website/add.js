/* The add page, in its two halves (see add.html).

   With a project in the link, it hands that project to the app and, if nothing answers, says where
   to get the app instead. Without one, it is the documentation a maintainer lands on, and builds the
   badge snippet for them.

   Loaded after script.js so the shared nav, theme, language and copy-button wiring is already in
   place; nothing generated here carries a data-i18n attribute, so switching language never wipes it. */
(function () {
  "use strict";

  var params = new URLSearchParams(window.location.search);
  var project = (params.get("url") || "").trim();

  /* This page's own address, which is what a badge points at. */
  function pageLink(target) {
    var base = window.location.origin + window.location.pathname;
    return base + "?url=" + encodeURIComponent(target);
  }

  /* Absolute, because the badge is loaded from whatever site the README lives on. */
  function badgeLink() {
    return new URL("assets/get-it-on-omnify.svg", window.location.href).href;
  }

  function appLink(target) {
    return "omnify://add?url=" + encodeURIComponent(target);
  }

  /* {host, owner, repo} as read from an address, or null when there is nothing project-shaped in it.
     The app does its own, fuller parsing and is the one that decides what an address really names
     (a bare "owner/repo" with no host is treated as GitHub there too). Only ever used for display
     and for the best-effort icon guess below, never to decide what Omnify itself will do with the
     link. */
  function parseProject(target) {
    var rest = target.replace(/^[a-z][a-z0-9+.-]*:\/\//i, "");
    rest = rest.split("?")[0].split("#")[0];
    var parts = rest.split("/").filter(function (part) {
      return part !== "";
    });
    var host = "";
    if (parts.length && parts[0].indexOf(".") !== -1) host = parts.shift().toLowerCase();
    if (parts.length < 2) return null;
    return { host: host, owner: parts[0], repo: parts[1].replace(/\.git$/i, "") };
  }

  function shortName(target) {
    var project = parseProject(target);
    return project ? project.owner + "/" + project.repo : "";
  }

  /* A couple of guesses at the project's real launcher icon, tried in order by the caller. Limited to
     GitHub, and to the one path every Android Studio template writes regardless of whether the app
     also ships an adaptive icon (see AdaptiveIconComposer on the app side for what a *reliable* read
     of one takes: parsing XML layers and rendering paths, well beyond what belongs in a static page).
     This is a best-effort decoration, not a claim of completeness. GitLab, Codeberg and self-hosted
     Gitea/Forgejo raw-file URLs vary too much by branch-naming convention to guess safely, and a
     wrong image would be worse than the plain initial tile every candidate here can safely fall back
     to. */
  function iconCandidates(target) {
    var parsed = parseProject(target);
    if (!parsed || (parsed.host && parsed.host !== "github.com")) return [];
    var base = "https://raw.githubusercontent.com/" + parsed.owner + "/" + parsed.repo +
      "/HEAD/app/src/main/res/mipmap-";
    return [base + "xxxhdpi/ic_launcher.png", base + "xxhdpi/ic_launcher.png"];
  }

  /* ---------- Someone tapped a badge ---------- */

  if (project) {
    var parsed = parseProject(project);
    var ok = document.getElementById("add-ok");
    var invalid = document.getElementById("add-invalid");

    if (!parsed) {
      ok.hidden = true;
      invalid.hidden = false;
    } else {
      document.getElementById("add-target-name").textContent = parsed.repo;

      /* The initial shows immediately so the tile is never empty; a real icon (see iconCandidates)
         quietly replaces it if and when one loads, so a slow or failing fetch is never something the
         reader is left waiting on or staring at a broken image for. */
      var initialEl = document.getElementById("add-target-initial");
      var imgEl = document.getElementById("add-target-img");
      initialEl.textContent = parsed.repo.charAt(0).toUpperCase();
      (function tryNextIcon(candidates, index) {
        if (index >= candidates.length) return;
        imgEl.onerror = function () {
          tryNextIcon(candidates, index + 1);
        };
        imgEl.onload = function () {
          imgEl.hidden = false;
          initialEl.hidden = true;
        };
        imgEl.src = candidates[index];
      })(iconCandidates(project), 0);

      var link = appLink(project);
      var button = document.getElementById("add-open-btn");
      var fallback = document.getElementById("add-fallback");

      /* A real href rather than a click handler, so opening the app is an ordinary navigation the
         reader asked for. That is the one form a browser never second-guesses, and it keeps working
         with this script disabled entirely. */
      button.href = link;

      /* The app coming to the front puts this page in the background. Watched so a link that worked
         is never followed by a message telling its reader it didn't.

         Deliberately only the two signals that mean this page genuinely stopped being on screen. A
         plain window blur was tried and dropped: it also fires for things that are none of this
         page's business, and every false positive here silently withholds the download link from
         someone who does not have the app, which is the one reader this page has to serve. */
      var opened = false;
      function markOpened() {
        opened = true;
      }
      document.addEventListener("visibilitychange", function () {
        if (document.hidden) markOpened();
      });
      window.addEventListener("pagehide", markOpened);

      /* Tried once unasked, since tapping the badge already said what the reader wants, but through
         a throwaway frame rather than by navigating this page. Sending the page itself to a scheme
         nothing answers can strand it on a browser error, which would take away the very fallback
         below that this page exists to offer. A frame can only fail quietly, and where the browser
         declines to act on it at all, the button is unaffected. */
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
         attempt above is indistinguishable here from one where the app simply isn't installed.
         Either way, where to get Omnify is the useful thing to show. Held back while the reader may
         still be looking at the app, and pushed back again whenever they ask to open it.

         The page is trimmed to fit a phone screen without scrolling (see styles.css), which means
         this card, appearing later, lands below the fold more often than not. Scrolled into view
         itself rather than left for the reader to go hunting for, since a fallback nobody sees is no
         fallback at all. */
      var reveal;
      function armFallback() {
        window.clearTimeout(reveal);
        reveal = window.setTimeout(function () {
          if (!opened && !document.hidden) {
            fallback.hidden = false;
            fallback.scrollIntoView({ behavior: "smooth", block: "start" });
          }
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

  /* ---------- A maintainer wants a badge ---------- */

  var input = document.getElementById("gen-input");
  if (input) {
    var snippets = document.getElementById("gen-snippets");
    var empty = document.getElementById("gen-empty");
    var preview = document.getElementById("gen-preview");
    var markdown = document.getElementById("gen-md");
    var html = document.getElementById("gen-html");

    var refresh = function () {
      var value = input.value.trim();
      var usable = value !== "" && shortName(value) !== "";

      snippets.hidden = !usable;
      preview.hidden = !usable;
      empty.hidden = usable;
      if (!usable) return;

      var page = pageLink(value);
      var badge = badgeLink();

      markdown.textContent = "[![Get it on Omnify](" + badge + ")](" + page + ")";
      /* Sized in the HTML form because Markdown has nowhere to put a height, and the badge's own
         400x160 is larger than most READMEs want inline. */
      html.textContent =
        '<a href="' + page + '"><img src="' + badge +
        '" alt="Get it on Omnify" height="56"></a>';
      preview.href = page;
    };

    input.addEventListener("input", refresh);
    refresh();
  }
})();
