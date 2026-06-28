> [!WARNING]
> **Free and Open-Source Android is under threat.**
> Google plans to make Android more locked-down, restricting your freedom to install the apps of your choice.
> Make your voice heard: [**Keep Android Open**](https://keepandroidopen.org/).

<div align="center">

<img src="metadata/en-US/images/featureGraphic.png" alt="Droidify Enhanced" width="100%">

### Droidify Enhanced

**A clutter-free F-Droid client, enhanced for daily use.**

_Enhanced fork maintained by [Victor-root](https://github.com/Victor-root), based on [Droid-ify](https://github.com/Droid-ify/client) by LooKeR._

[![Latest release](https://img.shields.io/github/v/release/Victor-root/Droidify-enhanced?display_name=tag&color=%23d97706&style=for-the-badge)](https://github.com/Victor-root/Droidify-enhanced/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/Victor-root/Droidify-enhanced/total.svg?color=%236f9645&style=for-the-badge)](https://github.com/Victor-root/Droidify-enhanced/releases/)
[![Stars](https://img.shields.io/github/stars/Victor-root/Droidify-enhanced?color=%2359a14f&style=for-the-badge)](https://github.com/Victor-root/Droidify-enhanced/stargazers)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge)](LICENSE)

</div>

---

## 📸 Screenshots

<!--
  Replace the four images below with up-to-date screenshots of the Enhanced UI.
  Drop the files in metadata/en-US/images/phoneScreenshots/ (1.png … 4.png),
  or add more <img> tags. Keep width ≈ 24% so they sit four-per-row on GitHub.
-->

<div align="center">

<img src="metadata/en-US/images/phoneScreenshots/1.png" width="24%" />
<img src="metadata/en-US/images/phoneScreenshots/2.png" width="24%" />
<img src="metadata/en-US/images/phoneScreenshots/3.png" width="24%" />
<img src="metadata/en-US/images/phoneScreenshots/4.png" width="24%" />

</div>

---

## Why this fork?

I use Droidify every day, intensively, and over time I ran into bugs and wanted
features that really mattered for that kind of daily use. I proposed fixes
upstream, but many of them were built with AI assistance, and the
[Droid-ify](https://github.com/Droid-ify/client) project has chosen to stay
AI-free. That is entirely their decision to make, so those fixes couldn't be
merged. Forking was the only way for me to keep improving the app at my own pace.

**To be clear, this is not a fork against Droid-ify.** I have deep respect for the
original project, its author and its vision for the codebase. Droidify Enhanced
simply serves a different need, my own, and only exists because Droid-ify gave it
such a strong foundation.

---

## ✨ Highlights

### 📦 Install apps from anywhere (*External sources*)

Add a project's GitHub releases as a source and install & update its app with **no
F-Droid repository required**.

- Automatically picks the right APK for your CPU architecture (with an optional
  name filter), and falls back to an older release if the latest has none
  compatible.
- Shows the real app name and icon **before** installing, with a visual icon
  picker.
- Update notifications in the Updates tab, signature-conflict handling, and the
  project README rendered in-app.
- Per-source settings (custom name, pre-releases, mute), an optional no-scope
  GitHub token to lift the API rate limit, and included in your backup.

### 🎨 Modern Material You interface

Rebuilt in **Jetpack Compose** with **Material 3**: an accent-colour picker
(including a wallpaper-based option), tinted system bars, an edge-to-edge mode,
a collapsing header, a two-column grid, animated search and wavy progress
indicators.

### 🧭 Discover home

A curated landing screen with carousels (what's new, recently updated, most
downloaded) and a browsable categories section.

### 🌍 Built-in translation

Translate an app's **summary and description** into your language, with a choice
of **online**, **self-hosted**, or **fully offline on-device** engines. An
optional auto-translate toggle does it for you, and nothing is downloaded until
you pick the on-device engine.

---

## 🛡️ Security & privacy

- Signing certificate **verified against the repository index before any install**.
- Anti-feature warnings and the full runtime-permission list on the detail screen.
- A badge flags apps that depend on **proprietary Google services**.
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
[**GitHub Releases**](https://github.com/Victor-root/Droidify-enhanced/releases/latest).
**Build from source:** see the [Building Guide](docs/building.md).

> Requires Android 6.0 (API 23) or newer. Ships in English and full (formal)
> French, with the other languages inherited from upstream.

---

## 🙏 Built on the shoulders of giants

- **[Droid-ify](https://github.com/Droid-ify/client)** by **LooKeR**: the base this fork builds on.
- **[Foxy-Droid](https://github.com/kitsunyan/foxy-droid)** by **kitsunyan**: the client Droid-ify itself grew from.

Huge thanks to both projects and their contributors. Contributions here are
welcome too. Start with the [Contributing Guide](CONTRIBUTING.md).

---

## 📄 License

```
Droidify Enhanced, a fork of Droid-ify

Copyright (C) 2025 LooKeR (original Droid-ify)
Copyright (C) 2026 Victor-root (Droidify Enhanced fork)

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
