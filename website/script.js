(function () {
  "use strict";

  var root = document.documentElement;
  var DICT = window.OMNIFY_I18N || {};
  var LOCALES = Object.keys(DICT);
  var FALLBACK = "en";

  /* ---------- Language ---------- */

  function store(key, value) {
    try {
      localStorage.setItem(key, value);
    } catch (e) {
      /* Private mode: the choice just won't survive a reload. */
    }
  }

  function read(key) {
    try {
      return localStorage.getItem(key);
    } catch (e) {
      return null;
    }
  }

  /* Shared open/close behaviour for the header's two dropdowns (language,
     accent colour): toggle on click, close on an outside click or Escape. */
  function makeDropdown(btn, list) {
    function setOpen(open) {
      list.hidden = !open;
      btn.setAttribute("aria-expanded", open ? "true" : "false");
    }
    btn.addEventListener("click", function (event) {
      event.stopPropagation();
      setOpen(list.hidden);
    });
    document.addEventListener("click", function (event) {
      if (!list.hidden && !list.contains(event.target)) setOpen(false);
    });
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape") setOpen(false);
    });
    return setOpen;
  }

  /* An explicit choice wins. Otherwise the browser's own language order decides,
     matching on the base tag so fr-CA and fr-BE both land on French. */
  function detectLocale() {
    var saved = read("omnify-lang");
    if (saved && DICT[saved]) return saved;

    var wanted = navigator.languages && navigator.languages.length
      ? navigator.languages
      : [navigator.language || ""];

    for (var i = 0; i < wanted.length; i++) {
      var base = String(wanted[i]).toLowerCase().split("-")[0];
      if (DICT[base]) return base;
    }
    return FALLBACK;
  }

  function translate(key, locale) {
    var table = DICT[locale] || {};
    if (typeof table[key] === "string") return table[key];
    var fallback = DICT[FALLBACK] || {};
    return typeof fallback[key] === "string" ? fallback[key] : null;
  }

  var currentLocale = FALLBACK;

  function applyLocale(locale) {
    if (!DICT[locale]) locale = FALLBACK;
    currentLocale = locale;
    root.setAttribute("lang", locale);

    document.querySelectorAll("[data-i18n]").forEach(function (el) {
      var value = translate(el.getAttribute("data-i18n"), locale);
      if (value !== null) el.textContent = value;
    });

    /* Only ever fed from the dictionary above, never from user input. */
    document.querySelectorAll("[data-i18n-html]").forEach(function (el) {
      var value = translate(el.getAttribute("data-i18n-html"), locale);
      if (value !== null) el.innerHTML = value;
    });

    /* "attribute:key", e.g. data-i18n-attr="aria-label:nav.menuAria". */
    document.querySelectorAll("[data-i18n-attr]").forEach(function (el) {
      el.getAttribute("data-i18n-attr").split(",").forEach(function (pair) {
        var parts = pair.split(":");
        if (parts.length !== 2) return;
        var value = translate(parts[1].trim(), locale);
        if (value !== null) el.setAttribute(parts[0].trim(), value);
      });
    });

    var label = document.getElementById("lang-label");
    if (label) label.textContent = translate("lang.name", locale) || locale.toUpperCase();

    var langList = document.getElementById("lang-list");
    if (langList) {
      var options = langList.querySelectorAll(".lang-option");
      for (var k = 0; k < options.length; k++) {
        options[k].setAttribute(
          "aria-pressed",
          options[k].getAttribute("data-lang") === locale ? "true" : "false"
        );
      }
    }

    renderAccentSwatches();
    syncThemeButton();
    if (latestTag) showLatestTag(latestTag);
  }

  applyLocale(detectLocale());

  var langBtn = document.getElementById("lang-toggle");
  var langList = document.getElementById("lang-list");

  /* Each language names itself: the label is always read from that language's
     own dictionary entry, never translated through the locale on screen, so a
     visitor can find their language even if they can't read the current one. */
  function renderLangOptions() {
    if (!langList) return;
    langList.textContent = "";
    LOCALES.forEach(function (code) {
      var entry = DICT[code] || {};
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "lang-option";
      btn.setAttribute("data-lang", code);
      btn.setAttribute("aria-pressed", code === currentLocale ? "true" : "false");

      var name = document.createElement("span");
      name.textContent = entry["lang.autonym"] || code;
      var tag = document.createElement("span");
      tag.className = "code";
      tag.textContent = entry["lang.name"] || code.toUpperCase();

      btn.appendChild(name);
      btn.appendChild(tag);
      btn.addEventListener("click", function () {
        store("omnify-lang", code);
        applyLocale(code);
        setLangOpen(false);
      });
      langList.appendChild(btn);
    });
  }

  var setLangOpen = function () {};
  if (langBtn && langList && LOCALES.length > 1) {
    renderLangOptions();
    setLangOpen = makeDropdown(langBtn, langList);
  } else if (langBtn) {
    langBtn.hidden = true;
  }

  /* ---------- Accent colour ----------
     Repaints the site and swaps the hero and Android TV screenshots for ones
     shot in the same colour, so the page shows off the app's own accent picker
     instead of just describing it. The colours and their screenshots live in
     accents.js. */

  var ACCENTS = window.OMNIFY_ACCENTS || [];
  var accentBtn = document.getElementById("accent-toggle");
  var accentList = document.getElementById("accent-list");
  var currentAccent = ACCENTS[0] || null;

  function accentById(id) {
    for (var i = 0; i < ACCENTS.length; i++) {
      if (ACCENTS[i].id === id) return ACCENTS[i];
    }
    return null;
  }

  /* Every set is the same pixel size and the replacement is put in place only
     once it has decoded, so the screenshot changes in a single frame: nothing
     fades, moves or resizes, only the colour appears to change.

     Decoded copies are kept rather than dropped. Fetching again is cheap once
     the file is cached, decoding is not: without this the TV screenshot landed
     about 100ms after the click, visibly behind the rest of the page. */
  var warmed = {};

  function warm(src) {
    var img = warmed[src];
    if (!img) {
      img = new Image();
      img.decoding = "async";
      img.src = src;
      /* Downloading alone is not enough: an image held off the page is decoded
         lazily, which would leave the cost to be paid on the click. Ask for it
         now, while nothing else is happening. */
      if (typeof img.decode === "function") img.decode().catch(function () {});
      warmed[src] = img;
    }
    return img;
  }

  /* wantedSrc is what the image is meant to end up showing, which is not the same as what it shows:
     a swap waits on a decode, and a second one can start before the first has landed, which two
     presses on the theme toggle do easily. Both lines below exist for that: the guard reads the
     pending target so a reversal isn't mistaken for a no-op, and the assignment checks it again on
     the way out so an earlier, slower decode can no longer land last and win. */
  function swapShot(img, src) {
    if ((img.wantedSrc || img.getAttribute("src")) === src) return;
    img.wantedSrc = src;
    var next = warm(src);
    var assign = function () { if (img.wantedSrc === src) img.src = src; };
    if (typeof next.decode === "function") {
      next.decode().then(assign, assign);
    } else if (next.complete) {
      assign();
    } else {
      next.onload = assign;
      next.onerror = assign;
    }
  }

  function applyAccent(accent) {
    if (!accent) return;
    currentAccent = accent;
    root.style.setProperty("--brand", accent.brand);
    root.style.setProperty("--brand-2", accent.brand2);
    root.style.setProperty("--on-brand", accent.onBrand || "#ffffff");

    var shots = document.querySelectorAll(".shot");
    for (var i = 0; i < shots.length; i++) {
      if (accent.shots && accent.shots[i]) swapShot(shots[i], accent.shots[i]);
    }

    var tv = document.querySelector(".tv-shot img");
    if (tv && accent.tvShot) swapShot(tv, accent.tvShot);

    /* The phone in the security section: its bars carry the accent, so they change with it exactly
       as the hero's screenshots do, through the same decode-then-assign swap. */
    var chrome = document.querySelector(".device-chrome");
    if (chrome && accent.chrome) swapShot(chrome, accent.chrome);

    if (accentList) {
      var buttons = accentList.querySelectorAll(".accent-swatch");
      for (var j = 0; j < buttons.length; j++) {
        buttons[j].setAttribute(
          "aria-pressed",
          buttons[j].getAttribute("data-accent") === accent.id ? "true" : "false"
        );
      }
    }
  }

  function renderAccentSwatches() {
    if (!accentList) return;
    accentList.textContent = "";
    ACCENTS.forEach(function (accent) {
      var name = translate("accent." + accent.id, currentLocale) || accent.id;
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "accent-swatch";
      btn.style.setProperty("--c", accent.brand);
      btn.setAttribute("data-accent", accent.id);
      btn.setAttribute("aria-label", name);
      btn.title = name;
      btn.setAttribute(
        "aria-pressed",
        currentAccent && currentAccent.id === accent.id ? "true" : "false"
      );
      btn.addEventListener("click", function () {
        applyAccent(accent);
        store("omnify-accent", accent.id);
        setAccentOpen(false);
      });
      accentList.appendChild(btn);
    });
  }

  var setAccentOpen = function () {};

  if (ACCENTS.length) {
    applyAccent(accentById(read("omnify-accent")) || ACCENTS[0]);
    renderAccentSwatches();

    if (accentBtn && accentList) {
      setAccentOpen = makeDropdown(accentBtn, accentList);
    }

    /* Warm the other colours' screenshots once the page is idle, so switching
       is instant rather than showing a gap while the new set loads. */
    var preload = function () {
      ACCENTS.forEach(function (accent) {
        (accent.shots || []).concat(accent.tvShot || [], accent.chrome || []).forEach(warm);
      });
    };
    if (window.requestIdleCallback) {
      window.requestIdleCallback(preload, { timeout: 4000 });
    } else {
      window.addEventListener("load", function () { setTimeout(preload, 1200); });
    }
  } else if (accentBtn) {
    accentBtn.hidden = true;
  }

  /* ---------- Theme ---------- */

  var darkQuery = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)");

  /* Three modes, cycled in this order by the button in the header. "auto" is the default and means
     the system decides, which the stylesheet already does on its own, so a first visit is right
     before this file has run and stays right when the system flips later on. */
  var THEME_MODES = ["auto", "light", "dark"];
  var THEME_LABEL_KEYS = {
    auto: "nav.themeAuto",
    light: "nav.themeLight",
    dark: "nav.themeDark"
  };
  var themeMode = "auto";

  /* What the page is actually showing. Dark when the system says nothing, matching the CSS default. */
  function isDark() {
    if (themeMode !== "auto") return themeMode === "dark";
    return darkQuery ? darkQuery.matches : true;
  }

  /* The gallery screenshots were taken twice, light and dark, so they belong to the page rather than
     sitting on it as six bright rectangles on a dark background. Swapped through the same path as
     the accent screenshots above: decoded first, then put in place, so the picture changes in one
     frame instead of blanking and reflowing. */
  function applyShotTheme() {
    var attribute = isDark() ? "data-dark" : "data-light";
    document.querySelectorAll("img[data-light][data-dark]").forEach(function (img) {
      var src = img.getAttribute(attribute);
      if (src) swapShot(img, src);
    });
  }

  /* The button says which mode is on rather than what clicking would do, so its label changes with
     the mode and with the language both. Also called from applyLocale, which can run before the
     button has been looked up. */
  function syncThemeButton() {
    if (!themeBtn) return;
    var label = translate(THEME_LABEL_KEYS[themeMode], currentLocale);
    if (label === null) return;
    themeBtn.setAttribute("aria-label", label);
    themeBtn.title = label;
  }

  function applyThemeMode(mode) {
    themeMode = THEME_MODES.indexOf(mode) >= 0 ? mode : "auto";
    root.setAttribute("data-theme-mode", themeMode);
    /* data-theme forces a palette, so in auto it has to be absent: the light rules are written as
       :root:not([data-theme="dark"]) inside a prefers-color-scheme query, and leaving anything there
       would pin the page to one side. */
    if (themeMode === "auto") {
      root.removeAttribute("data-theme");
    } else {
      root.setAttribute("data-theme", themeMode);
    }
    syncThemeButton();
    applyShotTheme();
  }

  var themeBtn = document.getElementById("theme-toggle");

  applyThemeMode(read("omnify-theme"));

  if (themeBtn) {
    themeBtn.addEventListener("click", function () {
      var next = THEME_MODES[(THEME_MODES.indexOf(themeMode) + 1) % THEME_MODES.length];
      applyThemeMode(next);
      store("omnify-theme", next);
    });
  }

  /* The system can flip on its own, e.g. at sunset. Only matters in auto, which isDark() already
     accounts for; the palette itself is the stylesheet's business. */
  if (darkQuery && darkQuery.addEventListener) {
    darkQuery.addEventListener("change", applyShotTheme);
  }

  /* ---------- Nav ---------- */

  var nav = document.getElementById("nav");
  var onScroll = function () {
    if (nav) nav.classList.toggle("stuck", window.scrollY > 8);
  };
  onScroll();
  window.addEventListener("scroll", onScroll, { passive: true });

  var burger = document.getElementById("nav-burger");
  var links = document.getElementById("nav-links");
  if (burger && links) {
    var setMenu = function (open) {
      links.classList.toggle("open", open);
      burger.setAttribute("aria-expanded", open ? "true" : "false");
    };
    burger.addEventListener("click", function () {
      setMenu(!links.classList.contains("open"));
    });
    links.addEventListener("click", function (event) {
      if (event.target.tagName === "A") setMenu(false);
    });
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape") setMenu(false);
    });
  }

  /* ---------- Reveal on scroll ---------- */

  var revealables = document.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window) {
    var observer = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          entry.target.classList.add("in");
          observer.unobserve(entry.target);
        });
      },
      { rootMargin: "0px 0px -12% 0px", threshold: 0.08 }
    );
    revealables.forEach(function (el) {
      observer.observe(el);
    });
  } else {
    revealables.forEach(function (el) {
      el.classList.add("in");
    });
  }

  /* ---------- Copy buttons ---------- */

  /* A button marked data-copy copies the code sitting next to it in the same box, and says so on
     itself for a moment. Shared by the fingerprint and by every command on the build page, rather
     than each growing its own copy of this. Hidden outright where there is no clipboard to write
     to, since a button that silently does nothing is worse than no button. */
  document.querySelectorAll(".icon-btn[data-copy]").forEach(function (btn) {
    var source = btn.parentNode.querySelector("code");
    if (!source || !navigator.clipboard) {
      btn.hidden = true;
      return;
    }
    btn.addEventListener("click", function () {
      navigator.clipboard.writeText(source.textContent.trim()).then(
        function () {
          btn.classList.add("done");
          btn.setAttribute(
            "aria-label",
            translate("dl.copiedAria", currentLocale) || "Copied"
          );
          setTimeout(function () {
            btn.classList.remove("done");
            btn.setAttribute(
              "aria-label",
              translate("dl.copyAria", currentLocale) || "Copy"
            );
          }, 2000);
        },
        function () {
          /* Clipboard refused: the text is still selectable by hand. */
        }
      );
    });
  });

  /* ---------- Latest release: the hero pill and the download links ---------- */

  var latestTag = null;

  function showLatestTag(tag) {
    var pill = document.getElementById("version-pill");
    if (!pill) return;
    var template = translate("hero.pillOut", currentLocale);
    pill.textContent = template
      ? template.replace("{tag}", tag)
      : tag;
  }

  /* The APK a release ships. Its file name carries the version, so it can't be written into the page
     ahead of time and the download links are authored pointing at the release page instead, which
     always resolves and needs no script at all. This only upgrades them to the file itself. */
  function apkAssetUrl(release) {
    var assets = release.assets || [];
    for (var i = 0; i < assets.length; i++) {
      if (/\.apk$/i.test(assets[i].name)) return assets[i].browser_download_url;
    }
    return null;
  }

  function pointDownloadsAt(url) {
    document.querySelectorAll("[data-apk-link]").forEach(function (link) {
      link.href = url;
    });
  }

  if (window.fetch) {
    fetch("https://api.github.com/repos/Victor-root/Omnify/releases/latest", {
      headers: { Accept: "application/vnd.github+json" }
    })
      .then(function (response) {
        return response.ok ? response.json() : null;
      })
      .then(function (release) {
        if (!release) return;
        if (release.tag_name) {
          latestTag = release.tag_name;
          showLatestTag(latestTag);
        }
        var apk = apkAssetUrl(release);
        if (apk) pointDownloadsAt(apk);
      })
      .catch(function () {
        /* Offline, rate-limited or no release yet: keep the static wording, and the download links
           keep leading to the release page, where the file is one section further down. */
      });
  }
})();
