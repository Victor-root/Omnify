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

    renderAccentSwatches();
    if (latestTag) showLatestTag(latestTag);
  }

  applyLocale(detectLocale());

  var langBtn = document.getElementById("lang-toggle");
  if (langBtn && LOCALES.length > 1) {
    langBtn.addEventListener("click", function () {
      var next = LOCALES[(LOCALES.indexOf(currentLocale) + 1) % LOCALES.length];
      store("omnify-lang", next);
      applyLocale(next);
    });
  } else if (langBtn) {
    langBtn.hidden = true;
  }

  /* ---------- Accent colour ----------
     Repaints the site and swaps the hero screenshots for a set shot in the same
     colour, so the page shows off the app's own accent picker instead of just
     describing it. The colours and their screenshots live in accents.js. */

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

  /* Every set is the same pixel size and preloaded, so the replacement is put
     in place only once it has decoded. The screenshot then changes in a single
     frame: nothing fades, moves or resizes, only the colour appears to change. */
  function swapShot(img, src) {
    if (img.getAttribute("src") === src) return;
    var next = new Image();
    var assign = function () { img.src = src; };
    next.src = src;
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

  function setAccentOpen(open) {
    if (!accentList || !accentBtn) return;
    accentList.hidden = !open;
    accentBtn.setAttribute("aria-expanded", open ? "true" : "false");
  }

  if (ACCENTS.length) {
    applyAccent(accentById(read("omnify-accent")) || ACCENTS[0]);
    renderAccentSwatches();

    if (accentBtn && accentList) {
      accentBtn.addEventListener("click", function (event) {
        event.stopPropagation();
        setAccentOpen(accentList.hidden);
      });
      document.addEventListener("click", function (event) {
        if (!accentList.hidden && !accentList.contains(event.target)) setAccentOpen(false);
      });
      document.addEventListener("keydown", function (event) {
        if (event.key === "Escape") setAccentOpen(false);
      });
    }

    /* Warm the other colours' screenshots once the page is idle, so switching
       is instant rather than showing a gap while the new set downloads. */
    var preload = function () {
      ACCENTS.forEach(function (accent) {
        (accent.shots || []).forEach(function (src) {
          var img = new Image();
          img.decoding = "async";
          img.src = src;
        });
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

  var savedTheme = read("omnify-theme");
  if (savedTheme === "light" || savedTheme === "dark") {
    root.setAttribute("data-theme", savedTheme);
  }

  var themeBtn = document.getElementById("theme-toggle");
  if (themeBtn) {
    themeBtn.addEventListener("click", function () {
      var prefersLight =
        window.matchMedia &&
        window.matchMedia("(prefers-color-scheme: light)").matches;
      var current = root.getAttribute("data-theme") || (prefersLight ? "light" : "dark");
      var next = current === "dark" ? "light" : "dark";
      root.setAttribute("data-theme", next);
      store("omnify-theme", next);
    });
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

  /* ---------- Copy the fingerprint ---------- */

  var copyBtn = document.getElementById("copy-fp");
  var fp = document.getElementById("fp");
  if (copyBtn && fp && navigator.clipboard) {
    copyBtn.addEventListener("click", function () {
      navigator.clipboard.writeText(fp.textContent.trim()).then(
        function () {
          copyBtn.classList.add("done");
          copyBtn.setAttribute(
            "aria-label",
            translate("dl.copiedAria", currentLocale) || "Copied"
          );
          setTimeout(function () {
            copyBtn.classList.remove("done");
            copyBtn.setAttribute(
              "aria-label",
              translate("dl.copyAria", currentLocale) || "Copy"
            );
          }, 2000);
        },
        function () {
          /* Clipboard refused: the fingerprint is still selectable by hand. */
        }
      );
    });
  } else if (copyBtn) {
    copyBtn.hidden = true;
  }

  /* ---------- Latest release in the hero pill ---------- */

  var latestTag = null;

  function showLatestTag(tag) {
    var pill = document.getElementById("version-pill");
    if (!pill) return;
    var template = translate("hero.pillOut", currentLocale);
    pill.textContent = template
      ? template.replace("{tag}", tag)
      : tag;
  }

  if (window.fetch) {
    fetch("https://api.github.com/repos/Victor-root/Omnify/releases/latest", {
      headers: { Accept: "application/vnd.github+json" }
    })
      .then(function (response) {
        return response.ok ? response.json() : null;
      })
      .then(function (release) {
        if (release && release.tag_name) {
          latestTag = release.tag_name;
          showLatestTag(latestTag);
        }
      })
      .catch(function () {
        /* Offline, rate-limited or no release yet: keep the static wording. */
      });
  }
})();
