# 📋 Changelog

## 🚀 v1.0.3-beta.4 (2026-08-11)

Settings can now be copied straight from one device to another over the local network, plus external-source update accuracy, a redesigned sync indicator on the app list header, and Android TV catching up with favourites and a real sync button.

### ➕ Added
- 📲 **Send your settings to another device:** setting up a second phone, or an Android TV, no longer means exporting a file and finding a way to move it across. Settings › Backup and restore gains "Send my data" and "Receive data": the receiving device shows an eight-digit code, you type it on the sending one, and everything travels directly between them. Choosing what to send uses the same list as a backup file, so nothing new to learn; the receiving side then lists what turned up and applies it only once you say so. The data never leaves the network the two devices share.
- 🔒 **The transfer is encrypted, and nothing leaves before the other device has proved itself:** the two devices agree on a key nobody else can derive (an elliptic-curve exchange), and the typed code is stretched into that key rather than used as one, so recording the traffic gives an eavesdropper nothing to test a guess against. The sending device then makes the receiving one answer for the code before parting with anything at all, so a machine on the same Wi-Fi that merely claims to be waiting is turned away empty handed rather than left holding your data to work on at its leisure. It also checks that what it sent genuinely arrived, instead of taking "done" on trust. Three wrong codes end the session, a code stops being valid after five minutes, nothing identifying is broadcast, and neither device will open a connection outside its own network, VPN tunnels included.
- 📡 **Sync button on Android TV:** the sidebar now has its own "Synchroniser" entry right below "Mise à jour", refreshing catalogue and external sources together like the mobile pull-to-sync does, with a loading bar replacing its label while it works.
- ⭐ **Favourites carousel on Android TV:** favourited apps now get their own row at the top of Explore on TV too, shown automatically as soon as one exists, matching mobile.
- 🌐 **Project website on external-source pages:** when a GitHub or Codeberg source declares one in its own "About" section, it now shows up next to Issue tracker and Changelog, the same way the F-Droid catalogue already links a project's website.
- 🔄 **External sources are checked in the background too:** until now they were only ever re-checked while the app was open, so a source that published a new release went unnoticed until you happened to open Omnify and land on the right screen. They now ride along with the twelve-hourly repository sync, exactly like the catalogue, including the once-a-day scan of whole-account sources for newly published apps. An app followed from GitHub no longer behaves differently from one served by a repository, which was the point of the External tab in the first place.
- ⚙️ **"Install updates automatically" now actually installs them:** the switch in Settings › Updates has been there all along without a single line of code reading it, so turning it on changed nothing whatsoever. It now does what it says, after each background sync and again the moment you switch it on: everything the Updates tab would have listed gets downloaded and installed on its own, catalogue apps and external sources alike. It only ever acts on what that tab shows you, so anything you hid or set to "track only" is left alone, and it never installs an app you merely follow without having installed. It also follows your existing "Sync repositories automatically" choice for the network to use, since an APK is a lot heavier than an index and nobody who limits syncing to Wi-Fi wants those over mobile data. Whether each install still asks you to confirm is Android's call, not ours: with Shizuku or root it is silent, and the default installer asks the system for a silent update, which recent Android versions grant when the new build is signed by the same key. Two cases are deliberately left for you: a release signed by a different key (Android has to uninstall first, which needs your confirmation) and a release that turns out to belong to a different app entirely, which does happen (Brave publishes Stable and Beta from the same repository, under two different Android packages). Both stay listed in the Updates tab.

### 🔄 Changed
- 🔔 **"Notify about updates" now actually notifies:** the notification it promises had been written, complete with its own notification channel and a tap target landing on the app list, and then never wired to anything, so the switch (on by default) did nothing at all. A background check that turns something up now says so, listing catalogue apps and external sources together the way the Updates tab does. Nothing is posted while automatic installation is on, since those updates are about to install themselves and each one announces its own result, and a notification is cleared once the updates it named are gone.
- 📶 **"Sync repositories automatically" respects the connection you chose:** only "Never" ever had any effect. "Always", "Only on Wi-Fi" and "Only on Wi-Fi and while charging" all behaved identically, syncing over whatever connection was available, mobile data included, which a code comment acknowledged. They now differ as their labels say. A sync you press the button for yourself still runs on any connection, since the setting is about what happens on its own.
- 🧩 **"Incompatible versions" now shows them:** the switch changed nothing. An app's version list silently dropped any release with no build this device can run, so a repository's version history looked shorter here than it really is. With the switch on, those releases are listed and marked "Incompatible version", which is what the setting always claimed to do. They are shown, not offered: tapping one still hands Android a build it will refuse.
- 🔔 **The install notification says "updating" when it's an update:** it read "Installing" / "Installed" whatever was happening, which was easy enough to overlook while you were the one who pressed Install, and plainly wrong once updates started installing on their own. It now says "Updating" / "Updated" when the app was already on the device. A failure is still worded as a failed installation either way, since that is what it is, and it already names the app.
- 🎬 **Sync indicator moved into the header:** the app list no longer shows a separate "Synchronisation en cours" banner below the tabs. The header title now turns into the loading bar itself while a sync runs, then writes itself back in once it's done, freeing up space in the list.
- 🔀 **Favourites carousel follows your sort order:** it used to always group catalogue apps before external ones regardless of the chosen sort. It now fully interleaves both by the same order shown on the carousel's "see all" page.
- 🔁 **"Rescan" on a whole-account source now shows what it did:** the action existed but ran silently, so pressing it looked like nothing had happened whether it found something or not. It now spins while it works and says how many new apps it found, or that GitHub's request limit was reached partway through, which is a different thing from finding nothing.
- 🖼️ **Android TV sidebar icons:** "Mise à jour" gets a new cloud-download icon, freeing up the previous refresh icon for the new "Synchroniser" entry.
- ⚡ **Faster to open an external app's page:** the version list used to fetch and hold a whole page of releases from GitHub/GitLab/Codeberg the moment the page opened, even though only the newest five are ever shown before "Show more" is tapped. It now asks for just enough to know whether there's more, and only fetches the rest once you actually tap it.
- ✏️ **Readable names for sources with no Android source to read one from:** a repo like brave/brave-browser, which by its own README holds nothing but issues, releases and the wiki, never had a manifest to pull a proper name out of, so it showed as the raw "brave-browser" instead of "Brave Browser". Such a source now gets its repo name turned into something readable (hyphens become spaces, each word capitalised) instead of showing the slug as-is. Existing sources pick this up on their next refresh, no need to re-add them.

### 🗑️ Removed
- 🔏 **"Ignore signature verification" is gone:** it was another switch nothing read, but unlike the others it could not honestly be made to work. There is no signature check in Omnify for it to skip: the only thing that stops an install over signatures is Android itself refusing to replace an app with one signed by a different key, which no setting here can override, and which the app already handles properly by offering to uninstall the old copy first. Wiring the switch to skip that check would have turned a clear explanation into a cryptic system failure without letting anything new install. The certificate comparison shown on an app's page is unaffected, and so is the checksum every download is verified against.

### 🐛 Fixed
- 🔄 **No more false "update available" for unversioned external releases:** apps like Brave, whose release assets carry no version number in the filename, could be flagged as updatable even when the installed version already matched the latest one.
- 📲 **An external app could stop being recognised as installed:** installing or updating one through Omnify recorded which package it now belongs to right away, before the system install was actually confirmed. A source whose latest release temporarily points to a differently-packaged build of the same app (confirmed real: brave/brave-browser mixes Stable and Beta releases, each its own separate Android package) could have this overwritten the moment such a release was merely attempted, permanently losing track of the copy actually on the device, whether or not that particular install went on to succeed. It's now only recorded once the system genuinely confirms the install.
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
