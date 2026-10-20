# 📋 Changelog

## 🚀 v1.0.7-beta.8 (Unreleased)

A welcome page for a fresh install, and external sources that learn to handle repositories publishing several apps side by side, like ente (Auth, Photos, Locker) or Bitwarden (Password Manager, Authenticator).

### ✨ Added
- **Import from Obtainium.** In Repositories, "+" then "Import from Obtainium": pick an Obtainium export and every app it follows on GitHub, GitLab or Codeberg (self-hosted ones included) becomes an external source, with its pre-release choice, APK filter and custom name. Apps already followed are left as they are, and the ones Obtainium follows elsewhere (F-Droid, APKMirror, direct links) are counted as not supported. Several apps followed in the same repository, each with its own APK filter (as for the apps of NullStore), each become a source of their own.
- **Jellyfin joins the Android TV picks** (Omnify's picks, Made for TV), off by default like the others. Existing installs get it too, without bringing back anything removed from the pack.
- **A welcome in two pages on a fresh install.** Omnify no longer switches F-Droid and IzzyOnDroid on by itself and starts downloading thousands of apps. The first page welcomes you and asks, with a button each, for notifications, for the install of apps and for the installer to use, as in Settings. The second brings your data over, all of it optional: from a backup file (you pick what to restore and what to leave out), from another device on the same network, or from an Omnify Beta still installed. The sources page then shows what the restore switched on. The third lets you pick the theme, the background and the accent colour, each applied as soon as you tap it. The fourth asks where your apps should come from: Omnify, F-Droid and IzzyOnDroid are ticked, every other repository Omnify ships is listed unticked, and one button ticks or clears them all. Previous and Next buttons under a row of dots move between the pages (swiping works too), and neither page scrolls: the sources sit in a card that scrolls inside itself, with a scrollbar in your accent colour. Logos are loaded while you read the first page, so they are already there on the second. Continuing, even with nothing ticked, is a normal way in, and the first synchronisation only starts then. The Android TV version has its own layout, built for the remote, and also offers the apps made for TV. Updating Omnify never shows it.
- **An empty home that says what to do** when no repository is switched on, with a button to the repositories, instead of waiting for a synchronisation that is not coming.
- **Follow several apps of one repository.** When a repository publishes several apps, Omnify lists them with a box to tick for each, along with its latest version and APK, and every app you tick becomes a source of its own, with its own install, updates, favourite and hidden state. This covers repositories releasing one app at a time (ente: Auth, Photos, Locker; Bitwarden: Password Manager, Authenticator) as well as those putting many apps in every release, told apart by their file names (NullStore). Apps you already follow are shown ticked, so adding the same repository again offers only the others.
- **External sources show their real icon before you install them**, read straight out of the APK when the repository itself has none to offer (NullStore only publishes built APKs, so every app of it showed the same account picture). Android draws it itself, so an app whose icon is only an adaptive one, like Google Maps, gets it too, shaped like the launcher's. Only the pieces of the file the icon is made of are fetched, once per release, never the whole APK.
- **External sources show the app's real name before you install them**, read from the APK along with the icon: "Ricardo nem lopot" instead of "Rikardo Nemlopot Smart123s", taken from the file name. When several apps followed in one repository call themselves the same (NullStore's three Google Maps builds are all "Ungoogled Maps"), they keep the names of their files so they can still be told apart. A name you picked yourself is never replaced.
- **An external source's icon shows a gentle pulsing placeholder while Omnify is still fetching it**, instead of the account's picture, which on a slow connection looked like the icon had failed.
- **An app among several in one repository shows its own description when it has one**: ente's Auth and Photos each get their own page instead of the repository's. When there is none, as with NullStore, the repository's description stays, under a note saying it presents the repository and not the app.
- **An app installed from elsewhere under the same package name is no longer taken for a source's own.** A source publishing a modified Gboard, say, next to Google's own Gboard on the phone, kept that one's name and icon and counted it as installed, ready to offer it an update Android would refuse. Omnify now compares signatures: such a copy leaves the source showing Install, its own name and icon, and a note saying another build holds the package and has to be uninstalled first.
- **A "Versions to keep" filter** on every external source, the opposite of "Excluded versions": only releases whose name contains one of its words are kept. The app picker fills it in for you, and it can be edited at any time.

### 🔄 Changed
- **The Session installer is now listed first**, and stays the default, so someone who does not know what to pick lands on the right one. The welcome page says so in a line under the choice, and the whole installer card is tappable, not just its top.
- **A "None" background**: plain white in the light theme, plain black in the dark ones, for those who'd rather have no decoration at all.
- **An app's page on Android TV shows what the phone's does**: its permissions, the languages it supports, the "Root" badge, and, once installed, a button to open its Android settings.
- **On Android TV, each repository you added gets its own row on the home screen**, with all its apps: a private repository's apps could only be found by opening it from the repositories screen. Its apps made for TV also come first in the TV apps list, where one not updated lately used to sink out of sight.
- **On Android TV, an app's page is now split in two, like on tablets,** for apps from the catalogue and for external sources alike. The app itself (icon, name, Install and Launch, certificate, versions) sits on the left, in a card like the phone's, with favourite and hide as small discreet buttons side by side. The screenshots and the whole description sit on the right: no more "See more" button, the description is one stop for the remote and scrolls on its own. Install keeps the focus until you press a key, even when screenshots show up afterwards.
- **A prettier secondary button.** Uninstall, Previous, Select all and the like take your accent colour, muted towards the background, instead of a grey outline: they stand apart from the main actions without a colour of their own. Phone and TV.
- **On Android TV, the background is Halo by default.** Other devices keep Dunes, and a background you already picked, or restored from a backup, is left alone.
- **On Android TV, the focus ring on a version no longer sits beside the card's own border.** It now covers it.
- **"Match accent to app icon" is available on every Android version.** It was hidden below Android 12 although it works there.
- **On Android TV, an app page with no description says so** instead of leaving an empty area that looks like it did not load.
- **On Android TV, the Back button at the top of every screen is gone.** The remote has its own Back key, and the button only got in the way.
- **Omnify no longer asks for notifications, nor for the install of apps, out of the blue at the first start.** Both are asked on the welcome page, behind a button, and a notification dialog never pops up over a screen you are looking at any more.

### 🐛 Fixed
- **On Android TV, the phone version of Jellyfin no longer shows in the TV apps list**, since Jellyfin has an app of its own for TV.
- **On Android TV, the TV apps filter lists many more apps.** It only showed the 16 most recently updated ones, which hid well-known apps; it now shows up to 200, like the "see all" page.
- **Omnify no longer closes by itself during a repository sync on some phones** (a native crash, SIGBUS, reported on a Realme GT Neo 3). The scheduled sync and a sync you start yourself could work on the same repository's index file at once, one downloading over it while the other read it. Syncs of one repository now take turns, the index file is closed once read, and each sync starts from a fresh file instead of writing over one left by an earlier, interrupted sync.
- **On Android TV, the background you pick in Settings now shows.** Every TV screen kept the same fixed wash whatever the choice. It is darkened a little in the dark themes so the content stays readable, and it no longer tints the side rail.
- **Closing the file picker without choosing a file no longer says the file is invalid** when restoring a backup from Settings.
- **On Android TV, the External tab no longer lists sources that are switched off.** It showed Omnify's picks even with every source off, where the phone already hid them.
- **No synchronisation is shown when there is nothing to synchronise.** With no source switched on, opening the home no longer lights up the sync button.
- **A source no longer shows another app's release.** Omnify keeps looking further back for the right one instead of stopping at the first page, and never falls back to a release of another app when your APK filter is working.
- **A clear message when nothing matches the "Versions to keep" filter**, instead of a generic "no release".
- **A source with an APK filter is never offered another app's APK.** When no recent release has an APK matching the filter, Omnify now says so, where it used to fall back on the newest APK of the repository, which in a repository publishing several apps could be a different app altogether.

---

## 🚀 v1.0.6-beta.7 (2026-10-10)

Ten backgrounds to choose from, an Updates tab that always matches its notification, and tidier categories. If you use the optional ML Kit translator (off by default), it no longer reports to Google, ahead of being replaced in the next version.

### ✨ Added
- **Ten backgrounds to pick from** in Settings, all tinted from your accent and following light, dark and black themes. Dunes is the new default; the previous one stays as "Aurora (classic)".
- **Install source per app.** For apps installed with Shizuku or root, choose Google Play (default) or Omnify from a line under the action buttons. Off by default, in Settings under Installer.

### 🔄 Changed
- **The update ring goes all the way around the app icon**, following its shape, with the percentage on a round badge.
- **Categories from your own repositories come first** in Explore, with their own icon.
- **The pre-install notice of an external source links to a GitHub issue** to report a wrong name, icon or version.
- **The optional ML Kit translator no longer reports to Google**: its usage statistics are cut, it only starts when a translation is asked for, and its Firebase requests are blocked. The next version replaces it.
- **Under edge-to-edge, every page's header slides away as you scroll** and comes back as soon as you scroll up, as on the home screen.

### 🐛 Fixed
- **The "updates available" notification always opens the Updates tab**, on the same apps it announced.
- **The Updates tab shows a loading state on a cold open** instead of "up to date".
- **External sources' pre-install icons are found in more places** (Magisk, for one), with the right background colour, and are tried again after a failed download or a backup restore instead of staying on the developer's avatar.
- **Empty categories no longer show.**
- **Search finds the apps you follow from GitHub or GitLab too**, ignores the space a keyboard suggestion leaves after a word, and says so when nothing matches.
- **With the accent matching an app's icon, the navigation bar keeps that colour** behind the install dialog.
- **No more grey veil over the status bar** under edge-to-edge.
- **Apps with one build per CPU architecture show all their versions.** Some devices got none and could not install. The next sync restores the missing builds.
- **Installed apps show their own icon at once** on external source pages.
- **Page accent follows the app's icon**, not the developer's avatar.

---

## 🚀 v1.0.5-beta.6 (2026-09-04)

A follow-up to the downgrade fix in v1.0.4-beta.5, which turned out not to cover every device it was reported from, and a large cut in the requests external sources spend.

### 🔄 Changed
- **External sources use a fraction of the requests they used to.** Omnify now remembers each answer and simply asks whether anything has changed since, which costs nothing against GitHub's hourly limit when nothing has. Checking a project that hasn't published is effectively free, and opening an app's page no longer asks the same question three times over.
- **The website follows your system's light or dark setting**, and its theme button cycles system, light and dark instead of locking onto one for good.
- **A repository link opens the add screen already filled in.** Send someone an `fdroidrepos://` link (or an fdroid.link page) and Omnify fills in whatever it carries: the address, the fingerprint, and the username too if the link names one, leaving them the password to type and nothing else. A link that says less just leaves more of the form to fill in. Omnify already answered these links; it opened an empty form.
- **A repository you added yourself now sits at the top of the F-Droid list**, above the several dozen Omnify ships with.
- **A repository keeps the logo it declares.** One serving a single app had that app's icon put in its place; this now only happens for a repository that declares no logo at all.
- **Adding a repository switches it on and syncs it**, so it shows its real name right away instead of the address you typed.
- **"Skip" on the add-a-repository screen now reads "Add without checking"**, which is what it does: it saves a repository that can't answer right now, such as one that is offline or behind a VPN.
- **The new repository texts are translated into every language Omnify ships with**, rather than showing up in English inside an otherwise translated screen.

### 🐛 Fixed
- **A first launch with no connection says so, and gets going the moment the connection is back.** The catalogue cannot load without one, and the home showed a header above an empty page with nothing to say whether anything was coming, which reads as an app that has broken. Turning Wi-Fi on then meant waiting minutes more for Android to get round to noticing. The page now explains itself, and the download starts as soon as the device is online. With a catalogue already downloaded there is no empty page to explain, so a banner under the tabs says it instead: your apps are all still listed, but nothing can be synced or installed until the connection is back.
- **Omnify never offers a version older than the one you have, whatever the build numbers say.** Two publishers of the same app number their builds on unrelated terms, so the version names you actually see (7.1.0 against 6.0.0) are now compared as well.
- **An app installed from its own project is no longer offered an older build from the catalogue**, on the app's own page as well as in the Updates tab. The signing key settles who an app belongs to now, since a build the catalogue never signed never came from it. Installing the catalogue's build on purpose is still available under "Show all versions". ([#1](https://github.com/Victor-root/Omnify/issues/1))
- **"Update all" now updates all of them.** It counted and installed only the catalogue half of the Updates tab, so the button read "(3)" above a list of four whenever one came from a project you follow. It covers both halves now, as the automatic installer already did.
- **An update running from the Updates tab shows on the page of the app it is updating.** Apps from a project you follow were the exception: opening one mid-update showed an idle page offering to start the update that was already running. Its progress now shows on the page and on the version being fetched, and Cancel there stops it.
- **Android TV announces updates from projects you follow.** Its Updates screen and the badge beside it counted repository apps only, so an update from GitHub or GitLab showed up nowhere: the only way to find it was to open the source's own page. Both halves are listed there now, exactly as on the phone.
- **A repository address is no longer refused over a character you cannot see.** A byte order mark, a stray space, a line ending left on a paste: any one of them meant "invalid address", with both save buttons greyed out and nothing on screen saying why.
- **An address pasted with a second line is refused, and says which problem it is.** The two lines used to be welded into one address nobody had typed, which was then looked for on a server that had never heard of it. The field shows a single line whatever it holds, so nothing on screen gave the second one away.
- **Adding a repository actually adds it.** The address was accepted and the check passed, then nothing: the screen stayed open and no repository appeared. It closes on success now, and says so when a save fails.
- **The keyboard no longer covers the add-a-repository form**, leaving the fields underneath it out of reach with nothing to scroll.
- **"Last updated" on a repository's page is translated** like the rest of that screen, in every language the app ships with.
- **A repository behind a password shows its logo, its apps' icons and their screenshots.** All of them were fetched without the login and refused, so nothing was ever shown.
- **The same repository can no longer be added twice** under its two spellings, with and without `/fdroid/repo`.
- **Adding a source you already follow says so, instead of claiming it was added.** A badge link for a project already in your list reported "Added" and added nothing, because the check ran before the list had been read.
- **Settings shows the language the app is really in.** It still read "System" after a language was chosen in Android's own per-app language screen, which is where Omnify sends you on Android 13 and up. Choosing "System" on older Android now really hands the app back to the device instead of asking for a language called "system".
- **A language list no longer turns into the app's own name** when a project names its translation files after itself, such as `app_DE.json`.
- **Syncing a repository no longer leaves files open** after reading its index or applying an update to it.

---

## 🚀 v1.0.4-beta.5 (2026-08-29)

The first fixes to come straight from the issue tracker (a repository you switch off stays off, and an app installed from GitHub is no longer offered an older build from the catalogue), no more crash when opening an app's page with no connection, Android's install prompt no longer going missing when you leave the app mid-update, lighter downloads, and a batch of Android TV fixes.

### 🔄 Changed
- **Downloads are much lighter.** No more extra copy of every file in 4 KB steps, and they no longer queue behind the app icons a list is loading.
- **Far fewer requests behind the scenes.** Omnify stopped re-asking every site what it had already been told, which is what made one download crawl while the next ran at full speed.

### 🐛 Fixed
- **A repository you switch off stays off.** F-Droid and IzzyOnDroid came back on by themselves every time Omnify was started from cold. ([#3](https://github.com/Victor-root/Omnify/issues/3))
- **Opening an app's page with no connection no longer closes Omnify.**
- **The changelog and README pages colour the status and navigation bars like their own header**, instead of a darker shade of it.
- **The install prompt no longer disappears when you leave Omnify mid-update.** A notification offers it while you're elsewhere, and it comes back on its own when you return.
- **Your accent colour is followed everywhere on Android 11 and older**, instead of buttons, badges and progress bars staying green.
- **Android TV explains an update Android refuses to apply** and offers to uninstall the existing copy first, as the phone already did.
- **The Installed tab opens the source an app really came from**, not the catalogue entry that happens to share its package name.
- **The "Get it on Omnify" badge is the same size in both snippets**, Markdown and HTML.
- **An app installed from an external source is no longer offered an older version from the catalogue.** The two number their builds on unrelated terms, so Omnify leaves an app's updates to whoever installed it. ([#1](https://github.com/Victor-root/Omnify/issues/1))
- **An app's languages are read right even when its translation files are named after the app**, instead of every language being listed under the app's own name. ([#1](https://github.com/Victor-root/Omnify/issues/1))
- **An external source keeps its name and its icon even when the project only settles them at build time**, instead of showing a raw marker and a flat coloured square.

---

## 🚀 v1.0.3-beta.4 (2026-08-18)

Device-to-device settings transfer, a "Get it on Omnify" badge any project can put in its README, four settings that finally do what they say, external-source update accuracy, and Android TV catching up with favourites and a real sync button.

### ➕ Added
- **Send your settings to another device:** Settings › Backup and restore gains "Send my data" / "Receive data", code-paired over the local network, no file needed.
- **The transfer is encrypted and code-authenticated:** key exchange derived from the typed code, receiver must prove it before anything is sent, three wrong tries end the session, local network only.
- **Sync button on Android TV:** a new "Synchroniser" entry next to "Mise à jour", same sync as mobile's pull-to-refresh.
- **Favourites carousel on Android TV**, matching mobile.
- **Project website shown on external-source pages**, same as the F-Droid catalogue already does.
- **External sources are checked in the background too**, riding along with the twelve-hourly sync instead of only while the app is open.
- **"Install updates automatically" now actually installs them:** the switch did nothing before. It now downloads and installs everything the Updates tab lists, catalogue and external alike, respecting your sync-network choice.
- **A "Get it on Omnify" badge any project can use:** a badge in a README now opens Omnify straight on its add-a-source screen, filled in, and adds it right away. Readers without Omnify land on a page showing the project's real icon, pointing at the download instead of a dead link. Maintainers get their snippet from the site's new badge page.
- **A setting to confirm before adding a shared or badge-linked project**, for anyone who'd rather check the details first: off by default, in Settings › External sources.
- **A badge tapped from a computer now hands the project to a phone:** Omnify only runs on Android, so a desktop visitor gets a QR code instead of a button that would do nothing. Scanning it opens the same page on their phone, where it works as it already does today.

### 🔄 Changed
- **"Notify about updates" now actually notifies.**
- **"Sync repositories automatically" respects the connection you chose** instead of always syncing on any connection.
- **"Incompatible versions" now shows them**, marked as such, instead of silently hiding them.
- **Install notification says "Updating" instead of "Installing" for an update.**
- **The "installed" notification stays up ten seconds instead of five**, now that updates can install on their own, with nobody necessarily watching when it appears.
- **Sync indicator moved into the header** instead of a separate banner.
- **Favourites carousel follows your sort order** instead of always grouping catalogue before external.
- **"Rescan" on a whole-account source now shows what it found**, spinner included.
- **Android TV sidebar icons updated** for the new sync entry.
- **Faster to open an external app's page:** only fetches the full release list once you tap "Show more".
- **Readable names for sources with no manifest to read one from** (e.g. "Brave Browser" instead of "brave-browser").

### 🗑️ Removed
- **"Ignore signature verification" is gone:** there was no real check for it to skip, so it could never have worked.

### 🐛 Fixed
- **A source now shows the same icon before installing as after.** It was showing the outdated flat fallback image instead of the real adaptive icon. Confirmed on Victor-root/OpenMessages.
- **Icons scale more sharply**, and a "master" copy some projects keep isn't mistaken for the icon anymore.
- **No more false "update available" for unversioned external releases** like Brave.
- **An external app could stop being recognised as installed** if a release pointed to a differently-packaged build mid-attempt. Now only recorded once the install is confirmed.
- **Correct initial focus on Android TV Explore.**
- **Focus comes back where you left it on Android TV.**
- **The restore dialog no longer flashes closed and open mid-restore**, and its buttons disable while it works.
- **An app no longer loses its "Installed via Omnify" tag as easily**, catalogue and external alike. Omnify now keeps its own record of what it installed instead of trusting Android to remember, which it can forget after Omnify itself is reinstalled.
- **Tapping the "updates available" notification now opens the Updates tab**, instead of the home screen's usual Explore tab, leaving you to go and find what you were just told about.
- **"Update all" no longer freezes when you leave Android's install prompt.** Walking away from that prompt (pressing Home, say) means Android answers nothing, and installs run one at a time: the rest of the batch waited behind it for a ten-minute timeout, with the button greyed out the whole time and no way to get out. Omnify now lets go the moment Android does report a result, and while a batch runs the button stops it instead of being disabled, on phone and on Android TV.

### 🛡️ Security
- **Diagnostic logging no longer runs outside debug builds:** a sweep of every log statement in the app found a large number of development traces (TV focus tracking, remote APK parsing, database queries, update-decision dumps) that ran in release and beta too. They are now limited to debug builds. No credential was ever among them, which was checked specifically; genuine error logging is untouched.

---

## 🚀 v1.0.2-beta.3 (2026-08-07)

Reliability pass across translation, language detection and icon theming, a batch of dependency updates, and an automated watch for security advisories.

### ➕ Added
- **Dependabot security watch:** every dependency Omnify uses is now checked against published security advisories, and a pull request is opened only when one is actually found. Routine "a newer version exists" noise is turned off on purpose.
- **Favourites carousel:** favourited apps, catalogue and external sources together, now get their own row at the top of Explore, appearing automatically as soon as one exists (and staying out of the way until then). The overflow menu's "Favourites" entry now only shows or hides that row, and says so directly ("Show favourites" / "Hide favourites"), instead of swapping the whole tab for a filtered list with no way back except that same menu.
- **Sort favourites your way:** the favourites carousel's "see all" page has its own sort menu, by name, by date favourited, or by date installed, entirely independent of the carousel's own fixed most-recently-favourited-first order.

### 🔄 Changed
- **The sync button refreshes external sources too:** pulling to sync from the app list used to only refresh the catalogue repositories, leaving external sources on their own timer. It now forces both at once, and says so while it works.
- **Unified in-app notifications:** every message (translation failures, sync results, and so on) now looks the same everywhere, catalogue and external sources alike, instead of four different notification styles depending on the screen.
- **Dependency updates:** Compose, commonmark, kotlinx-datetime, JUnit, the Compose lint rules, the Android Gradle Plugin, Gradle itself, and the SDK level the app compiles against, along with the libraries that unlocked.

### 🐛 Fixed
- **Apps leave the Updates tab instantly:** an app now disappears from the Updates tab as soon as its install finishes, instead of waiting for the next sync.
- **Emoji survive translation:** translated text no longer drops emoji.
- **Much faster README translation:** the on-device translator used to be rebuilt for every paragraph of an external source's README; it now stays loaded for the whole document.
- **Translation works in release and beta builds:** the code shrinker was stripping a constructor the on-device translation library needs to start up, so translation silently failed outside of debug builds.
- **Reliable language detection:** sources that ship their translations as small per-language files, such as PPSSPP, are now read correctly instead of showing "English only".
- **No more all-white app pages:** the "match icon to app theme" option no longer turns a page all-white when an icon's background plate is more common than its logo colour.

---

## 🚀 v1.0.1-beta.2 (2026-08-05)

Security audit of the app, plus fixes for external sources.

### 🛡️ Security
- **Closed an install hijack path:** any app on the device could make Omnify install a file of its choosing.
- **Repository passwords encrypted via Android's key store:** the encryption key used to sit in plain bytes right next to them.
- **No more sensitive data in Android backups:** automatic backup no longer copies the GitHub token and repository logins off the device. Use the app's own export instead.
- **Source names escaped before becoming part of an API address.**
- **Certificate fingerprints computed safely:** no longer shared across threads via a single, non thread-safe digest.
- **Removed six unused release workflows and some dead inherited code.**

Most of these came with the code inherited from Droid-ify rather than being introduced here, so other forks of the same base are likely affected. The external-sources side is Omnify's own.

### 🐛 Fixed
- **No more stale "update available":** no update offered for a release older than the installed one (Brave ships to Play before its GitHub release leaves pre-release).
- **Version-less APKs sorted correctly:** an APK with no version in its file name no longer reads as newer than everything.
- **Malformed APKs no longer break language detection for the whole file.**
- **External sources checked for new releases again:** each was frozen on the version it had the day it was added, Omnify itself included.

### 🔄 Changed
- **Twice as much README shown before "Show more".**
- **The version list now says when it is still loading.**

---

## v1.0.0-beta.1 (2026-08-02)

First public release of Omnify.
