# 📋 Changelog

## 🚀 v1.0.2-beta.3 (2026-08-07)

Reliability pass across translation, language detection and icon theming, a batch of dependency updates, and an automated watch for security advisories.

### ➕ Added
- 🛡️ **Dependabot security watch:** every dependency Omnify uses is now checked against published security advisories, and a pull request is opened only when one is actually found. Routine "a newer version exists" noise is turned off on purpose.

### 🔄 Changed
- 🔔 **Unified in-app notifications:** every message (translation failures, sync results, and so on) now looks the same everywhere, catalogue and external sources alike, instead of four different notification styles depending on the screen.
- 📦 **Dependency updates:** Compose, commonmark, kotlinx-datetime, JUnit, the Compose lint rules, the Android Gradle Plugin, Gradle itself, and the SDK level the app compiles against, along with the libraries that unlocked.

### 🐛 Fixed
- 🔄 **Apps leave the Updates tab instantly:** an app now disappears from the Updates tab as soon as its install finishes, instead of waiting for the next sync.
- 😀 **Emoji survive translation:** translated text no longer drops emoji.
- ⚡ **Much faster README translation:** the on-device translator used to be rebuilt for every paragraph of an external source's README; it now stays loaded for the whole document.
- 🌐 **Translation works in release and beta builds:** the code shrinker was stripping a constructor the on-device translation library needs to start up, so translation silently failed outside of debug builds.
- 🗣️ **Reliable language detection:** sources that ship their translations as small per-language files, such as PPSSPP, are now read correctly instead of showing "English only".
- 🎨 **No more all-white app pages:** the "match icon to app theme" option no longer turns a page all-white when an icon's background plate is more common than its logo colour.

## 🚀 v1.0.1-beta.2 (2026-08-05)

Security audit of the app, plus fixes for external sources.

### 🛡️ Security
- 🔒 **Closed an install hijack path:** any app on the device could make Omnify install a file of its choosing.
- 🔑 **Repository passwords encrypted via Android's key store:** the encryption key used to sit in plain bytes right next to them.
- 💾 **No more sensitive data in Android backups:** automatic backup no longer copies the GitHub token and repository logins off the device. Use the app's own export instead.
- 🧹 **Source names escaped before becoming part of an API address.**
- 🧵 **Certificate fingerprints computed safely:** no longer shared across threads via a single, non thread-safe digest.
- 🗑️ **Removed six unused release workflows and some dead inherited code.**

Most of these came with the code inherited from Droid-ify rather than being introduced here, so other forks of the same base are likely affected. The external-sources side is Omnify's own.

### 🐛 Fixed
- 🔄 **No more stale "update available":** no update offered for a release older than the installed one (Brave ships to Play before its GitHub release leaves pre-release).
- 🔢 **Version-less APKs sorted correctly:** an APK with no version in its file name no longer reads as newer than everything.
- 🌍 **Malformed APKs no longer break language detection for the whole file.**
- 🔁 **External sources checked for new releases again:** each was frozen on the version it had the day it was added, Omnify itself included.

### 🔄 Changed
- 📖 **Twice as much README shown before "Show more".**
- ⏳ **The version list now says when it is still loading.**

## v1.0.0-beta.1 (2026-08-02)

First public release of Omnify.
