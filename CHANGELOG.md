# 📋 Changelog

## 🚀 v1.0.3-beta.4 (2026-08-11)

Settings can now be copied straight from one device to another over the local network, plus external-source update accuracy, a redesigned sync indicator on the app list header, and Android TV catching up with favourites and a real sync button.

### ➕ Added
- 📲 **Send your settings to another device:** setting up a second phone, or an Android TV, no longer means exporting a file and finding a way to move it across. Settings › Backup and restore gains "Send my data" and "Receive data": the receiving device shows an eight-digit code, you type it on the sending one, and everything travels directly between them. Choosing what to send uses the same list as a backup file, so nothing new to learn; the receiving side then lists what turned up and applies it only once you say so. The data never leaves the network the two devices share.
- 🔒 **The transfer is encrypted, and nothing leaves before the other device has proved itself:** the two devices agree on a key nobody else can derive (an elliptic-curve exchange), and the typed code is stretched into that key rather than used as one, so recording the traffic gives an eavesdropper nothing to test a guess against. The sending device then makes the receiving one answer for the code before parting with anything at all, so a machine on the same Wi-Fi that merely claims to be waiting is turned away empty handed rather than left holding your data to work on at its leisure. It also checks that what it sent genuinely arrived, instead of taking "done" on trust. Three wrong codes end the session, a code stops being valid after five minutes, nothing identifying is broadcast, and neither device will open a connection outside its own network, VPN tunnels included.
- 📡 **Sync button on Android TV:** the sidebar now has its own "Synchroniser" entry right below "Mise à jour", refreshing catalogue and external sources together like the mobile pull-to-sync does, with a loading bar replacing its label while it works.
- ⭐ **Favourites carousel on Android TV:** favourited apps now get their own row at the top of Explore on TV too, shown automatically as soon as one exists, matching mobile.
- 🌐 **Project website on external-source pages:** when a GitHub or Codeberg source declares one in its own "About" section, it now shows up next to Issue tracker and Changelog, the same way the F-Droid catalogue already links a project's website.

### 🔄 Changed
- 🎬 **Sync indicator moved into the header:** the app list no longer shows a separate "Synchronisation en cours" banner below the tabs. The header title now turns into the loading bar itself while a sync runs, then writes itself back in once it's done, freeing up space in the list.
- 🔀 **Favourites carousel follows your sort order:** it used to always group catalogue apps before external ones regardless of the chosen sort. It now fully interleaves both by the same order shown on the carousel's "see all" page.
- 🔁 **"Rescan" on a whole-account source now shows what it did:** the action existed but ran silently, so pressing it looked like nothing had happened whether it found something or not. It now spins while it works and says how many new apps it found, or that GitHub's request limit was reached partway through, which is a different thing from finding nothing.
- 🖼️ **Android TV sidebar icons:** "Mise à jour" gets a new cloud-download icon, freeing up the previous refresh icon for the new "Synchroniser" entry.

### 🐛 Fixed
- 🔄 **No more false "update available" for unversioned external releases:** apps like Brave, whose release assets carry no version number in the filename, could be flagged as updatable even when the installed version already matched the latest one.
- 🎯 **Correct initial focus on Android TV Explore:** opening the app used to land the remote's focus one row too low when favourites were present, as if the screen had already been scrolled. It now lands on Favourites right away, or on the first carousel when there are none.
- 📺 **Focus comes back where you left it on Android TV:** returning from Repositories or Settings dropped the remote's focus into the content instead of onto the sidebar entry you had just used.

### 🛡️ Security
- 🔇 **Diagnostic logging no longer runs outside debug builds:** a sweep of every log statement in the app found a large number of development traces (TV focus tracking, remote APK parsing, database queries, update-decision dumps) that ran in release and beta too. They are now limited to debug builds. No credential was ever among them, which was checked specifically; genuine error logging is untouched.

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
