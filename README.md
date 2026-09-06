<div align="center">

**A clutter-free F-Droid client that also installs apps from anywhere.**

[![Visit the website](https://img.shields.io/badge/Visit%20the%20website-victor--root.github.io-3DDC84?style=for-the-badge&logo=googlechrome&logoColor=white)](https://victor-root.github.io/Omnify/)

[![Latest release](https://img.shields.io/github/v/release/Victor-root/Omnify?style=for-the-badge&logo=github&label=release&include_prereleases)](https://github.com/Victor-root/Omnify/releases/latest)
[![Last update](https://img.shields.io/github/last-commit/Victor-root/Omnify/main?style=for-the-badge&logo=git&label=last%20update)](https://github.com/Victor-root/Omnify/commits/main)
[![Downloads](https://img.shields.io/github/downloads/Victor-root/Omnify/total?style=for-the-badge&logo=github&label=downloads)](https://github.com/Victor-root/Omnify/releases)

[![Android](https://img.shields.io/badge/Android-6.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Victor-root/Omnify/releases/latest)
[![Android TV](https://img.shields.io/badge/Android%20TV-supported-3DDC84?style=for-the-badge&logo=androidtv&logoColor=white)](https://github.com/Victor-root/Omnify#-made-for-android-tv)
[![F-Droid repositories](https://img.shields.io/badge/F--Droid-repositories-1976D2?style=for-the-badge&logo=fdroid&logoColor=white)](https://github.com/Victor-root/Omnify#-highlights)

[![External sources](https://img.shields.io/badge/External%20sources-GitHub%20%C2%B7%20GitLab%20%C2%B7%20Codeberg%20%C2%B7%20Forgejo-8A2BE2?style=for-the-badge&logo=git&logoColor=white)](https://github.com/Victor-root/Omnify#-install-apps-from-anywhere-external-sources)

[![Automatic updates](https://img.shields.io/badge/External%20apps-auto--updated-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Victor-root/Omnify#-install-apps-from-anywhere-external-sources)
[![Material You](https://img.shields.io/badge/Material%20You-Material%203-6750A4?style=for-the-badge&logo=materialdesign&logoColor=white)](https://github.com/Victor-root/Omnify#-modern-material-you-interface)

<a href="https://victor-root.github.io/Omnify/"><picture><source media="(prefers-color-scheme: dark)" srcset=".github/assets/website-dark.webp"><img width="820" alt="The Omnify website: the full feature list with real screenshots, the download link, and the tools to share a repository or badge a project" src=".github/assets/website-light.webp" /></picture></a>

**[victor-root.github.io/Omnify](https://victor-root.github.io/Omnify/)** has the full
feature list with real screenshots, the download link, and the tools to share a
private repository by link or badge your own project. Thirteen languages, light and dark.

</div>

> [!WARNING]
> **Free and Open-Source Android is under threat.**
> Google plans to make Android more locked-down, restricting your freedom to install the apps of your choice.
> Make your voice heard: [**Keep Android Open**](https://keepandroidopen.org/).

---

## 📸 Screenshots

<p align="center"><strong>📱 Mobile</strong></p>
<p align="center">
<picture><source media="(prefers-color-scheme: dark)" srcset="website/assets/screenshots/explore-dark.webp"><img width="220" alt="Discover home screen" src="website/assets/screenshots/explore-light.webp" /></picture>
<picture><source media="(prefers-color-scheme: dark)" srcset="website/assets/screenshots/external-dark.webp"><img width="220" alt="The External tab, showing tracked sources" src="website/assets/screenshots/external-light.webp" /></picture>
<picture><source media="(prefers-color-scheme: dark)" srcset="website/assets/screenshots/addsource-dark.webp"><img width="220" alt="Adding a source: a repository, one app, or a whole account" src="website/assets/screenshots/addsource-light.webp" /></picture>
<picture><source media="(prefers-color-scheme: dark)" srcset="website/assets/screenshots/adaway-dark.webp"><img width="220" alt="AdAway's page, with its README" src="website/assets/screenshots/adaway-light.webp" /></picture>
<picture><source media="(prefers-color-scheme: dark)" srcset="website/assets/screenshots/glasskeep-dark.webp"><img width="220" alt="GlassKeep's page, with its README" src="website/assets/screenshots/glasskeep-light.webp" /></picture>
<picture><source media="(prefers-color-scheme: dark)" srcset="website/assets/screenshots/shizuku-dark.webp"><img width="220" alt="Shizuku's page, with its README" src="website/assets/screenshots/shizuku-light.webp" /></picture>
</p>

<p align="center"><strong>📺 Android TV</strong></p>
<p align="center">
  <img
    src="https://github.com/user-attachments/assets/46b59cbe-f019-49f7-8164-3228a6ff9f0d"
    width="720"
    alt="AdAway Community — Android TV home screen"
  />
</p>

---

## 🧪 This is a beta

Omnify is currently in beta. The core is solid and I use it every day, but
might as well say so clearly rather than pretend it's already fully polished.
Any problem report is useful and welcome, see the
[Contributing Guide](CONTRIBUTING.md) for how.

External sources deserve the most attention right now. Every GitHub, GitLab,
Codeberg or self-hosted Gitea/Forgejo project organizes its releases,
descriptions and files a little differently, and Omnify has to figure it out
entirely on its own, with no F-Droid index to guide it. Here's what's worth
checking on every external source added:

- The app icon and name shown actually match the real app, even before installing
- Tags like root-compatible or an anti-feature are accurate for that specific app
- The version number shown matches what's actually installed
- The supported languages list is correct
- The Google-services badge (and how well microG covers it) reflects what the
  app actually needs

Projects publish their releases in a huge variety of ways, and a given pattern
can only really be made reliable once it's actually been encountered on a real
repository. Every mismatch reported on a new source helps make detection a bit
more solid for the next one: even a simple "this looks wrong" report is
genuinely useful, never just noise.

---

## ✨ Highlights

### 📦 Install apps from anywhere (*External sources*)

Add a project's **GitHub, GitLab, Codeberg, or self-hosted Gitea/Forgejo**
releases as a source and install & update its app with **no F-Droid repository
required**. Track a whole publisher account at once instead of one project at a
time.

- Automatically picks the right APK for your CPU architecture (with an optional
  name filter), and falls back to an older release if the latest has none
  compatible.
- Shows the real app name and icon **before** installing, with a visual icon
  picker.
- Update notifications in the Updates tab, signature-conflict handling, and the
  project README rendered in-app.
- Per-source settings (custom name, pre-releases, mute), an optional no-scope
  GitHub token to lift the API rate limit, and included in your backup.
- **Omnify's picks**: a curated list of noteworthy sources, disabled by
  default, one tap away.

### 🏷️ A badge for your own project

Maintain a project? Put a **Get it on Omnify** badge in your README and anyone
reading it can follow your releases in one tap, without copying an address by
hand. Paste your project's address on
**[the badge page](https://victor-root.github.io/Omnify/add)** and copy the
snippet it gives you.

[![Get it on Omnify](website/assets/get-it-on-omnify.svg)](https://victor-root.github.io/Omnify/add)

Tapping it opens Omnify on its add-a-source screen, already filled in. Nothing
is installed without the reader saying so, and if they don't have Omnify yet the
page points them at the download rather than leaving them on a dead link.

### 📺 Made for Android TV

A proper 10-foot interface, not an afterthought: full D-pad navigation, a
scaled-up UI, automatic "Made for TV" detection for both catalogue and
external apps, and a curated pack of FOSS TV apps ready to install.

### 🎨 Modern Material You interface

Rebuilt in **Jetpack Compose** with **Material 3**: an accent-colour picker
(including a wallpaper-based option, or matching an app's own icon on its
detail page), tinted system bars, an edge-to-edge mode, a collapsing header,
a two-column grid, animated search and wavy progress indicators.

### 🧭 Discover home

A curated landing screen with carousels (what's new, recently updated, most
downloaded) and a browsable categories section.

### 👁️ Hide apps you don't need

Hide any app, catalogue or external, from every list (Discover, Installed,
Updates) with one tap on its page. Manage what's hidden, or bring an app
back, from Settings.

### 🌍 Built-in translation

Translate an app's **summary and description** into your language, with a choice
of **online**, **self-hosted**, or **fully offline on-device** engines. An
optional auto-translate toggle does it for you, and nothing is downloaded until
you pick the on-device engine.

### 💾 Backup & restore

Back up repositories, external sources, settings, favourites and custom
buttons independently, all in one file. Restoring only ever adds to what's
already there, never wipes it.

---

## 🛡️ Security & privacy

- Signing certificate **verified against the repository index before any install**,
  with the installed and expected fingerprints viewable and copyable on the
  detail screen.
- Anti-feature warnings and the full runtime-permission list on the detail screen.
- A badge flags apps that depend on **proprietary Google services**, and how
  well **microG** covers what they actually need.
- Recovers an app's real supported languages even when its own listing doesn't
  declare them.
- The optional GitHub token is **scope-less** (it only lifts the rate limit), and
  translation can run **fully offline**.

---

## 🔧 Stability & bug fixes

**Performance**
- Unified the data layer on a single **Room** database, removing the legacy
  SQLite store, sync service, downloader and index parser.
- Moved list and screen-state work **off the main thread**, added a **baseline
  profile** for faster cold starts, and fixed a **freeze (ANR)** on the detail
  screen.

**Large catalogues & sync**
- Oversized repository rows no longer exceed the SQLite cursor-window limit that
  could crash the list.
- Reliable sync: auto re-sync after a database reset, no more silent
  empty-catalogue syncs, a fresh index file each time, and a clear fetching state
  on first launch. The foreground notification is throttled so it no longer
  flickers.

**Updates & installs**
- Hides system-app updates that can't be installed and stops the uninstall-prompt
  loop for differently-signed system apps.
- Reuses an already-downloaded APK after a signature-conflict uninstall, with no
  needless re-download.

---

## 🚀 Get started

**Download:** the latest APK from
[**GitHub Releases**](https://github.com/Victor-root/Omnify/releases/latest).
**Build from source:** see the [Building Guide](docs/building.md).

> Requires Android 6.0 (API 23) or newer. See **Language support** below for
> translation coverage.

**Verify:** every release is signed with the same certificate. Compare its
SHA-256 fingerprint (e.g. via `apksigner verify --print-certs`) against:

```
F2:2B:D7:B4:63:D8:D8:9C:A1:AC:3B:6C:41:DB:0B:25:AA:C7:7B:86:24:C9:70:E4:52:81:2D:32:19:42:A9:71
```

---

## 🗣️ Language support

Omnify's own interface is fully translated in **English, French, German,
Russian, Chinese (Simplified), Polish, Portuguese (Brazil), Spanish,
Indonesian, Turkish, Italian, Dutch and Japanese**. Every other language
inherited from upstream Droid-ify only has partial coverage: it's missing
everything added in this fork.

If you're a native speaker of any of these languages, a review pass to catch
awkward phrasing or mistakes would be very welcome, and adding another
language entirely is welcome too. See the [Contributing Guide](CONTRIBUTING.md).

---

## 🔀 Why this fork?

I use Droid-ify every day, intensively, and over time I ran into bugs and wanted
features that really mattered for that kind of daily use. I proposed fixes
upstream, but many of them were built with AI assistance, and the
[Droid-ify](https://github.com/Droid-ify/client) project has chosen to stay
AI-free. That is entirely their decision to make, so those fixes couldn't be
merged. Forking was the only way for me to keep improving the app at my own pace.

**To be clear, this is not a fork against Droid-ify.** I have deep respect for the
original project, its author and its vision for the codebase. Omnify
simply serves a different need, my own, and only exists because Droid-ify gave it
such a strong foundation.

---

## 🙏 Built on the shoulders of giants

- **[Droid-ify](https://github.com/Droid-ify/client)** by **LooKeR**: the base this fork builds on.
- **[Foxy-Droid](https://github.com/kitsunyan/foxy-droid)** by **kitsunyan**: the client Droid-ify itself grew from.

Huge thanks to both projects and their contributors. Contributions here are
welcome too. Start with the [Contributing Guide](CONTRIBUTING.md).

---

## 📄 License

```
Omnify, a fork of Droid-ify

Copyright (C) 2025 LooKeR (original Droid-ify)
Copyright (C) 2026 Victor-root (Omnify fork)

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
```
