# XMart Blocker

**YouTube Shorts guard for Android TV.** XMart Blocker runs in the background from the moment the TV turns on. When a YouTube Short opens, it presses Back and the child lands on the YouTube page they came from. YouTube itself, normal videos and dramas keep working.

> Urdu: TV on hote hi ye app khud background mein chal parti hai. Jaise hi YouTube par Short khulta hai, ye usay foran band kar ke YouTube ke pichle page par le aati hai. YouTube app band nahi hoti, aur dramas/normal videos chalte rehte hain.

## How it works

| Part | What it does |
|---|---|
| `ShortsBlockerService` | An Accessibility Service. Android starts it automatically on every boot once it is switched on. |
| Tree detector (`NodeScanner`) | Looks for Shorts-only view ids such as `reel_player_page_container` and `reel_recycler`. This is how blockers for the phone YouTube app work, and it also covers TV boxes that run the phone app. |
| Screen detector (`FrameAnalyzer` + `ScreenSampler`) | The YouTube **Android TV** app (`com.google.android.youtube.tv`) is built on Google's Cobalt engine. It draws its own UI, and its accessibility layer only exposes 9 empty placeholder nodes for D-pad navigation ([CobaltA11yHelper](https://github.com/youtube/cobalt/blob/main/cobalt/android/apk/app/src/main/java/dev/cobalt/coat/CobaltA11yHelper.java)), so there is no "Shorts" text to read. Instead the service takes a screenshot about every 0.7 s with the accessibility screenshot API (Android 11+, no extra permission prompt). The GPU shrinks it to a 64×36 grey grid. A Short plays as a tall 9:16 video in the middle of a 16:9 screen, so motion stays inside a narrow centred band from top to bottom while the sides stay still. Two positive checks in a row trigger the block. |
| Action | `GLOBAL_ACTION_BACK` returns to the previous YouTube page. If Back fails 3 times in a row, it falls back to `GLOBAL_ACTION_HOME`. |
| Remote rules (`rules.json`) | Detection thresholds and ids are fetched from this repo every 6 hours. Detection can be tuned without a new APK. |
| Settings guard | If a parent PIN is set, any Settings or installer screen that shows "XMart Blocker" is closed at once, so the child cannot switch the blocker off or uninstall it. Entering the PIN in the app unlocks it for 10 minutes. |
| Updater (`Updater`) | Checks GitHub Releases when the app opens and every 12 hours. "Update now" downloads the APK and installs it through `PackageInstaller`. Android asks for confirmation the first time; after that the app is its own installer and later updates install silently (Android 12+). |

Nothing leaves the TV. Screenshots are analysed in memory and are never stored or sent anywhere.

### Known limits
- **Vertical normal videos** also look like Shorts on screen, so a portrait video played full screen is closed too. Landscape videos and dramas are not affected.
- A **paused** Short has no motion. It is caught as soon as it plays.
- YouTube can change its TV layout. If that happens, adjust `rules.json` (no new APK needed) or ship a new release.
- Thresholds were checked with synthetic frames in unit tests. They still need tuning on a real TV: turn on **Diagnostics** in the app, open a Short and a normal video, then read the log (it shows `w=` width, `side=` side motion and so on).

## Install on the TV (first time)

1. Download `XMart-Blocker-vX.Y.Z.apk` from **Releases**. On the TV the *Downloader* app works well; you can also use a USB drive with a file manager.
2. Install it, then open **XMart Blocker** from the apps row.
3. Press **Turn on protection** → *Accessibility* → **XMart Blocker** → On.
   - If Android shows *"Restricted setting"*: Settings → Apps → XMart Blocker → ⋮ → **Allow restricted settings**, then repeat step 3.
   - If your TV hides these menus, use ADB from a computer:
     ```bash
     adb connect <tv-ip>
     adb shell appops set com.xmarthost.blocker ACCESS_RESTRICTED_SETTINGS allow
     adb shell settings put secure enabled_accessibility_services com.xmarthost.blocker/.ShortsBlockerService
     adb shell settings put secure accessibility_enabled 1
     ```
4. **Set parent PIN** in the app. This also turns on the settings guard.
5. The first time you press **Update now**, allow *Install unknown apps* for XMart Blocker.

## Publishing a new version (one click)

1. GitHub → **Actions** → **Release APK** → **Run workflow** → enter a version (e.g. `1.0.1`) and notes.
2. The workflow runs the tests, builds a signed APK and creates release `v1.0.1`.
3. The next time the TV opens XMart Blocker it shows **Update available → Update now**. Silent updates also happen within 12 hours once the first self-update has been done.

### One-time signing setup

All releases must be signed with the **same key**, or Android refuses the update. Add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `xmart` |
| `KEY_PASSWORD` | key password |

To create a key:

```bash
keytool -genkeypair -v -keystore release.jks -alias xmart -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=XMart Blocker"
```

Keep `release.jks` and its passwords safe and outside the repo. If you lose the key, every TV has to uninstall and reinstall the app.

Every push to `main` also runs the **Build** workflow, which uploads a test APK as an artifact.

## Build locally

```bash
./gradlew testReleaseUnitTest assembleRelease
```

Requires JDK 17+ and the Android SDK (platform 35). Without signing env vars, the release APK is signed with the debug key; use that only for testing.

## Roadmap

- [x] v1.0: Accessibility service, tree and screen detectors, Back action, PIN and settings guard, remote rules, GitHub self-updater, CI release
- [ ] Tune thresholds on a real Android TV 14 with Diagnostics logs
- [ ] Optional blocking schedule (e.g. only after 9 pm)
- [ ] Optional daily YouTube time limit
- [ ] Urdu UI

## Credits / prior art

- [ShortStop](https://github.com/also-ruan-botha/shortstop) and [Shorts-Blocker](https://github.com/atick-faisal/Shorts-Blocker): accessibility-based Shorts blockers for the phone app
- [youtube/cobalt](https://github.com/youtube/cobalt): source of the YouTube TV app engine, used to understand why the TV app needs screen analysis

License: MIT
