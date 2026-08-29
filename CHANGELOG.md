# 📋 Changelog

## 🚀 v1.0.4-beta.5 (2026-08-21)

Android's install prompt no longer goes missing when you leave the app mid-update, a batch of Android TV fixes (accent colours, a blocked update that said nothing), and the Installed tab now opens the source an app really came from.

### 🔄 Changed
- ⚡ **Downloads are much lighter.** No more extra copy of every file in 4 KB steps, and they no longer queue behind the app icons a list is loading.
- 🚦 **Far fewer requests behind the scenes.** Omnify stopped re-asking every site what it had already been told, which is what made one download crawl while the next ran at full speed.

### 🐛 Fixed
- 📴 **Opening an app's page with no connection no longer closes Omnify.**
- 🎨 **The changelog and README pages colour the status and navigation bars like their own header**, instead of a darker shade of it.
- 📥 **The install prompt no longer disappears when you leave Omnify mid-update.** A notification offers it while you're elsewhere, and it comes back on its own when you return.
- 🎨 **Your accent colour is followed everywhere on Android 11 and older**, instead of buttons, badges and progress bars staying green.
- 📺 **Android TV explains an update Android refuses to apply** and offers to uninstall the existing copy first, as the phone already did.
- 📲 **The Installed tab opens the source an app really came from**, not the catalogue entry that happens to share its package name.
- 🏷️ **The "Get it on Omnify" badge is the same size in both snippets**, Markdown and HTML.
- ⬇️ **An app installed from an external source is no longer offered an older version from the catalogue.** The two number their builds on unrelated terms, so Omnify leaves an app's updates to whoever installed it.
- 🌍 **An app's languages are read right even when its translation files are named after the app**, instead of every language being listed under the app's own name.
- 🖼️ **An external source keeps its name and its icon even when the project only settles them at build time**, instead of showing a raw marker and a flat coloured square.

---

## 🚀 v1.0.3-beta.4 (2026-08-18)

Device-to-device settings transfer, a "Get it on Omnify" badge any project can put in its README, four settings that finally do what they say, external-source update accuracy, and Android TV catching up with favourites and a real sync button.

### ➕ Added
- 📲 **Send your settings to another device:** Settings › Backup and restore gains "Send my data" / "Receive data", code-paired over the local network, no file needed.
- 🔒 **The transfer is encrypted and code-authenticated:** key exchange derived from the typed code, receiver must prove it before anything is sent, three wrong tries end the session, local network only.
- 📡 **Sync button on Android TV:** a new "Synchroniser" entry next to "Mise à jour", same sync as mobile's pull-to-refresh.
- ⭐ **Favourites carousel on Android TV**, matching mobile.
- 🌐 **Project website shown on external-source pages**, same as the F-Droid catalogue already does.
- 🔄 **External sources are checked in the background too**, riding along with the twelve-hourly sync instead of only while the app is open.
- ⚙️ **"Install updates automatically" now actually installs them:** the switch did nothing before. It now downloads and installs everything the Updates tab lists, catalogue and external alike, respecting your sync-network choice.
- 🏷️ **A "Get it on Omnify" badge any project can use:** a badge in a README now opens Omnify straight on its add-a-source screen, filled in, and adds it right away. Readers without Omnify land on a page showing the project's real icon, pointing at the download instead of a dead link. Maintainers get their snippet from the site's new badge page.
- ⚙️ **A setting to confirm before adding a shared or badge-linked project**, for anyone who'd rather check the details first: off by default, in Settings › External sources.
- 📱 **A badge tapped from a computer now hands the project to a phone:** Omnify only runs on Android, so a desktop visitor gets a QR code instead of a button that would do nothing. Scanning it opens the same page on their phone, where it works as it already does today.

### 🔄 Changed
- 🔔 **"Notify about updates" now actually notifies.**
- 📶 **"Sync repositories automatically" respects the connection you chose** instead of always syncing on any connection.
- 🧩 **"Incompatible versions" now shows them**, marked as such, instead of silently hiding them.
- 🔔 **Install notification says "Updating" instead of "Installing" for an update.**
- ⏱️ **The "installed" notification stays up ten seconds instead of five**, now that updates can install on their own, with nobody necessarily watching when it appears.
- 🎬 **Sync indicator moved into the header** instead of a separate banner.
- 🔀 **Favourites carousel follows your sort order** instead of always grouping catalogue before external.
- 🔁 **"Rescan" on a whole-account source now shows what it found**, spinner included.
- 🖼️ **Android TV sidebar icons updated** for the new sync entry.
- ⚡ **Faster to open an external app's page:** only fetches the full release list once you tap "Show more".
- ✏️ **Readable names for sources with no manifest to read one from** (e.g. "Brave Browser" instead of "brave-browser").

### 🗑️ Removed
- 🔏 **"Ignore signature verification" is gone:** there was no real check for it to skip, so it could never have worked.

### 🐛 Fixed
- 🖼️ **A source now shows the same icon before installing as after.** It was showing the outdated flat fallback image instead of the real adaptive icon. Confirmed on Victor-root/OpenMessages.
- 🖼️ **Icons scale more sharply**, and a "master" copy some projects keep isn't mistaken for the icon anymore.
- 🔄 **No more false "update available" for unversioned external releases** like Brave.
- 📲 **An external app could stop being recognised as installed** if a release pointed to a differently-packaged build mid-attempt. Now only recorded once the install is confirmed.
- 🎯 **Correct initial focus on Android TV Explore.**
- 📺 **Focus comes back where you left it on Android TV.**
- 🔁 **The restore dialog no longer flashes closed and open mid-restore**, and its buttons disable while it works.
- 🏷️ **An app no longer loses its "Installed via Omnify" tag as easily**, catalogue and external alike. Omnify now keeps its own record of what it installed instead of trusting Android to remember, which it can forget after Omnify itself is reinstalled.
- 🔔 **Tapping the "updates available" notification now opens the Updates tab**, instead of the home screen's usual Explore tab, leaving you to go and find what you were just told about.
- 🧊 **"Update all" no longer freezes when you leave Android's install prompt.** Walking away from that prompt (pressing Home, say) means Android answers nothing, and installs run one at a time: the rest of the batch waited behind it for a ten-minute timeout, with the button greyed out the whole time and no way to get out. Omnify now lets go the moment Android does report a result, and while a batch runs the button stops it instead of being disabled, on phone and on Android TV.

### 🛡️ Security
- 🔇 **Diagnostic logging no longer runs outside debug builds:** a sweep of every log statement in the app found a large number of development traces (TV focus tracking, remote APK parsing, database queries, update-decision dumps) that ran in release and beta too. They are now limited to debug builds. No credential was ever among them, which was checked specifically; genuine error logging is untouched.

---

## 🚀 v1.0.2-beta.3 (2026-08-07)

Reliability pass across translation, language detection and icon theming, a batch of dependency updates, and an automated watch for security advisories.

### ➕ Added
- 🛡️ **Dependabot security watch:** every dependency Omnify uses is now checked against published security advisories, and a pull request is opened only when one is actually found. Routine "a newer version exists" noise is turned off on purpose.
- ⭐ **Favourites carousel:** favourited apps, catalogue and external sources together, now get their own row at the top of Explore, appearing automatically as soon as one exists (and staying out of the way until then). The overflow menu's "Favourites" entry now only shows or hides that row, and says so directly ("Show favourites" / "Hide favourites"), instead of swapping the whole tab for a filtered list with no way back except that same menu.
- 🔀 **Sort favourites your way:** the favourites carousel's "see all" page has its own sort menu, by name, by date favourited, or by date installed, entirely independent of the carousel's own fixed most-recently-favourited-first order.

### 🔄 Changed
- 🔄 **The sync button refreshes external sources too:** pulling to sync from the app list used to only refresh the catalogue repositories, leaving external sources on their own timer. It now forces both at once, and says so while it works.
- 🔔 **Unified in-app notifications:** every message (translation failures, sync results, and so on) now looks the same everywhere, catalogue and external sources alike, instead of four different notification styles depending on the screen.
- 📦 **Dependency updates:** Compose, commonmark, kotlinx-datetime, JUnit, the Compose lint rules, the Android Gradle Plugin, Gradle itself, and the SDK level the app compiles against, along with the libraries that unlocked.

### 🐛 Fixed
- 🔄 **Apps leave the Updates tab instantly:** an app now disappears from the Updates tab as soon as its install finishes, instead of waiting for the next sync.
- 😀 **Emoji survive translation:** translated text no longer drops emoji.
- ⚡ **Much faster README translation:** the on-device translator used to be rebuilt for every paragraph of an external source's README; it now stays loaded for the whole document.
- 🌐 **Translation works in release and beta builds:** the code shrinker was stripping a constructor the on-device translation library needs to start up, so translation silently failed outside of debug builds.
- 🗣️ **Reliable language detection:** sources that ship their translations as small per-language files, such as PPSSPP, are now read correctly instead of showing "English only".
- 🎨 **No more all-white app pages:** the "match icon to app theme" option no longer turns a page all-white when an icon's background plate is more common than its logo colour.

---

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

---

## v1.0.0-beta.1 (2026-08-02)

First public release of Omnify.
