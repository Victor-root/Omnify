# 🤝 Contributing to Omnify

Thanks for your interest in contributing to **Omnify**! 🚀

---

## 🐛 Before opening an issue

Please check whether your problem is already reported. When reporting a bug, include as many details as possible: the more precise your report, the easier it is to reproduce and fix. 🙏

General details:
* 📱 Device model, Android version, ROM/manufacturer skin, and whether it's a phone/tablet or Android TV
* 📦 What's involved: an F-Droid-style repository, or an *external source* (GitHub/GitLab/Codeberg/self-hosted)
* ⚙️ Installer in use (Settings → Install method): Session, Root, Shizuku, or Legacy
* 🔁 Exact steps to reproduce, what you expected vs. what actually happened
* 🖼️ Screenshots/recordings, and Logcat logs if the issue still happens

If it's a sync or repository issue, also mention:
* 🌐 The repo address (or external source URL) involved, and whether it's official or self-hosted
* 🔄 Does it happen on a fresh add, or only on re-sync? Any error shown, or a silent failure?

If it's an install/update issue, also mention:
* 🔐 Signature-conflict prompt involved (a different-signer install)?
* 📥 Which installer backend (see above), and whether switching installer changes anything

If it's an Android TV issue, also mention:
* 📺 TV/box model and Android TV/Google TV version
* 🎮 Does remote/D-pad navigation work as expected, and does the app show in the launcher?

Useful Logcat filters:

```text
InstallManager
SessionInstaller
ShizukuInstaller
RepoRepository
SyncWorker
UpdateAllWorker
InstallAllWorker
AppDetailViewModel
ExternalAppsViewModel
RemoteApkManifestReader
RemoteApkLocaleReader
RemoteApkIconReader
ApkSigningBlockReader
```

---

## 🔧 Pull requests

Pull requests are welcome! 🎉 Please keep each one focused on a single, clear change: one bug fix, one feature, one cleanup.

Good pull requests:
* 🎯 fix one clear problem, and avoid unrelated refactors
* 📱 keep phone/tablet behavior working · 📺 keep Android TV behavior working
* 📦 don't break the F-Droid-repo path while fixing an external-source one (or vice versa)
* 🧪 include tests when practical, and explain what was tested manually

Please avoid mixing unrelated changes into the same pull request. Opening several PRs, though, is very welcome if that's what it takes to keep each one focused: working on three unrelated fixes? Three small PRs are much easier (and faster) to review than one big one, and there's no limit on how many you can open at once.

Examples:
* ✅ good: a sync bug fix
* ✅ good: an Android TV layout fix
* ✅ good: a translation fix
* ✅ also good: the three above, as three separate PRs from the same person
* ⚠️ not ideal: a sync fix + a UI redesign + a dependency bump, all in one PR

---

## 🤖 Contributing with AI assistance

Using AI tools (Claude, ChatGPT, Copilot, etc.) to contribute is **totally welcome**: it's not a problem, it's not frowned upon, it's actually encouraged.

That doesn't mean "one prompt, one PR" though: no vibe coding, where you fire off a prompt and open a PR with whatever comes out without understanding or checking it. Stay in the driver's seat: understand the problem, guide the AI, review and iterate on what it produces. It isn't perfect, and code that looks right may not be, so everything needs to be tested in detail before submitting: "it compiles" is not a test.

When you prompt a fix, explicitly ask for a **surgical**, targeted change to the exact problem: no unsolicited refactors or cleanup. A small, clean diff is easier to get with a good prompt, and easier to review.

No issue letting the AI write the PR title and description, as long as it stays **readable by a human**: what problem it solves, what the fix does, without code-level detail (that's for me to see in review). Also mention whether the PR was AI-assisted, for transparency, and if you can, briefly describe your AI workflow (tool used, how you verified it): optional, but it helps calibrate the review.

**🧪 Testing is by far the most important part of a PR, even more so with AI.** Sync and install behavior on Android is finicky (network conditions, repo quirks, OEM skins): a real bug can take a while to show up, so a quick smoke test proves little.

For any sync- or install-related change, actually exercise the path you touched (add/remove a repo, add/remove an external source, install/update/uninstall a real app) rather than trusting that the code "looks right". In the Testing section, include:
* 📱 Device, Android version, phone/tablet or TV
* 📦 Repo(s) or source(s) used to test, and what you actually did (not just "it works")

---

## 🙏 Thanks

Every useful bug report, test result, translation, fix or review helps.

Even small contributions matter. 🚀
