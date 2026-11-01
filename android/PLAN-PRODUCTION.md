# Production + quality plan (research synthesis, 1 Nov 2026)

Jhon, here is the plan. Do the P0 list first, in the order given. P1 is what makes it feel better than Nova, Niagara and Pixel. Where a report goes against REDESIGN.md, REDESIGN.md wins, and the item says so.

Legend: **Owner** is the file owner from the REDESIGN.md table. "Lead" also covers files the table doesn't list (`ListenService.kt`, `ChatListener.kt`, `MainActivity.kt`, `Crash.kt`, `SafeModeActivity.kt`). **Conflict** means it goes against REDESIGN.md, and his wish wins. Reports: OS = open-source, REA = closed-source and One UI teardown, IOS = iPhone feel, PROD = production.

I checked these against the code myself (1 Nov):
- `Crash(limit=3, windowMs=60_000)`
- `ListenService` only catches `SecurityException` around `utterance()`, and has `if (n <= 0) continue`
- `startForeground` with no try/catch
- `ChatListener` uses plain `HashMap`s and a 3 s sleep loop
- no `onRenderProcessGone`
- `versionCode = 1`, no release build type
- HomeActivity's `configChanges` has `density|fontScale` but no `uiMode`
- `onNewIntent` always calls `closeTop()`
- `tick()` uses `CLOCK_TICK`
- `blur()` makes a new `RenderEffect` on every call
- no `LauncherApps` callback anywhere
- SafeModeActivity has no `taskAffinity`

Note: the ListenService line numbers in PROD are out of date, but the problems are real.

## P0: must fix for production

1. **Catch-all guards in `:core`** (S, lead)
   - **Why:** if `:core` crashes twice within 2 min, Android kills it, marks it bad and restricts the whole package (PROD #1, #2, #7, #13; source is AOSP `AppErrors`). Chat alerts and the send queue then go quiet.
   - **How, in `ListenService`:**
     - Check `AudioRecord.state == STATE_INITIALIZED` before `startRecording()`.
     - Wrap `startRecording()` and `startForeground(MICROPHONE)` in `try/catch (Throwable)`.
     - When `read() < 0`: release the recorder, sleep 1 s, rebuild. Today it spins at 100% CPU.
     - Also fix the `ArrayList<Short>` boxing (use a `ShortArray` buffer) and the `MediaPlayer` leak in `say()`.
   - **How, in `ChatListener`:** wrap all of `onNotificationPosted` in `try/catch (Throwable)`.

2. **Make ChatListener state thread-safe and bounded** (S, lead)
   - **Why:** `replyActions` and `lastText` are written on the main thread and read on the outbox thread (a data race), and they grow forever (PROD #6).
   - **How:** a synchronized `LinkedHashMap` with `removeEldestEntry` once it passes 200 entries.

3. **Fix the crash loop and safe mode** (M, lead)
   - **Why:**
     - Android acts at 2 crashes in 120 s, but we wait for 3 in 60 s.
     - The safe flag survives installing a fixed build.
     - Back from safe mode can land in the WebView task.
     - Safe mode uses `Look`, so a crash in `Look` takes safe mode down too (PROD #3, #4).
   - **How:**
     - `Crash(limit = 2, windowMs = 120_000)`.
     - Write the versionCode into the `.safe` flag and clear the flag when it doesn't match.
     - Give `SafeModeActivity` `android:taskAffinity="com.jhon.agenticos.safe"`.
     - Build safe mode from framework widgets only.
     - Read `getHistoricalProcessExitReasons` off the main thread and log new exits to `Link.dir/exits.log` (cap ~50 lines). Count UI-process `CRASH_NATIVE` and `ANR` as crashes.
     - Add JUnit tests in `CoreTest.kt` (`Crash(File)` already takes a file).

4. **WebView must not take Home down** (S now, M later; lead plus H)
   - **Why:** with no `onRenderProcessGone`, a killed renderer crashes the process, and MainActivity shares that process with Home (PROD #5). Separately, `CookieManager.getInstance()` loads Chromium on Home's main thread in `onResume` (PROD #11).
   - **How:**
     - In `MainActivity`: `onRenderProcessGone` destroys the WebView, rebuilds it and returns true. Do this now.
     - Remove the CookieManager call from `HomeActivity:119`. MainActivity already saves the cookie.
     - Then move MainActivity to `android:process=":web"`. **[I]** This makes the home process smaller under the Android 17 memory limits.

5. **Signing and release build** (S, lead; this decides every future update)
   - **First:** check that the installed APK's certificate SHA-256 matches `%USERPROFILE%\.android\debug.keystore` (PROD §3.1 commands).
   - **Option A (recommended):** keep the debug key as the release key forever.
     - Copy it to `D:\Keys\agentic-os\`, keep 2 backups (one off the PC).
     - Keep `keystore.properties` outside the repo and load it only if the file exists.
     - Add `*.jks`, `*.keystore` and `keystore.properties` to `.gitignore`.
   - **Release build type in `build.gradle.kts`:** `isMinifyEnabled = true`, `isShrinkResources = true`, an empty `proguard-rules.pro`. PROD grepped and found no reflection.
   - **Versions:** `versionCode = yyyyMMddNN`.
   - **After the first R8 build, test on the phone:** the JS bridge (`bar`, `apps`, `open`, `listen`) and the widgets page.
   - **Never:** switch keys without a settings export first. An uninstall wipes the home role, the listener and battery grants, widget ids and all prefs.

6. **Fix hard-coded class names, then add a `.dev` variant** (S, lead)
   - **Why:** `setClassName(this, "$packageName.X")` breaks under an `applicationIdSuffix` (PROD #12). A `.dev` package keeps all testing away from his real data.
   - **Where:** `ChatListener.kt:126`, `ListenService.kt:71`, `core/ShareJob.kt:258`.
   - **How:** use the literal class names, then add `debug { applicationIdSuffix = ".dev" }`.

7. **App list correctness and no full reload on every resume** (M/L; B owns Orbit, AppDrawer and DrawerActivity, G owns AppMenu, lead owns `App.kt` and the contract)
   - **Why, now:**
     - `queryIntentActivities` + `distinctBy { pkg }` drops apps that have a second launcher activity.
     - Work, Dual Messenger and other profile apps never show.
     - The orbit goes stale after an install or uninstall.
     - `orbit.load()` re-queries every app and decodes icons on every `onResume` (OS §3, REA 3a.2).
   - **How:**
     - Enumerate with `LauncherApps.getActivityList(null, user)` for each profile, inside a per-user `try/catch (SecurityException)`. OS read a Lawnchair comment saying Android 17 QPR2 throws there for non-main users; that is unverified.
     - Register `LauncherApps.Callback` once in the main process only, never in `:core`. On a change, re-query just that package. Use `onShortcutsChanged` to drop cached shortcuts and `onPackagesSuspended` to grey apps out.
     - Launch with `startMainActivity(component, user, bounds, opts)`.
     - Run every load on one `HandlerThread("launcher-loader")` with a generation counter, so an older load can't overwrite a newer one. This replaces the 4 raw `Thread {}` calls in Orbit (OS #3).
   - **Conflict, with the contract (not with Jhon):** the locked signatures take `pkg: String`, and changing them is the lead's call. Smallest change that keeps old data valid:
     - Main-user apps with one launcher activity keep the plain `pkg` key.
     - Everything else uses `component.flattenToString() + "#" + userSerial`.
     - No migration needed.

8. **Icon cache, nothing decoded in bind** (S/M; B, plus lead for `SafeModeActivity`)
   - **Why:** `getApplicationIcon` runs on the main thread in `Orbit.kt` (2 places), `AppDrawer.kt:48`, `DrawerActivity.kt:39` and `SafeModeActivity.kt:30`. That drops frames during the fly-out (OS #4, REA #3).
   - **How:**
     - One shared `object Icons` with a platform `android.util.LruCache` sized by bytes (about 1/8 of memory).
     - Fill it on the loader thread with `lai.getIcon(densityDpi)`, badged for other profiles.
     - Evict an entry from the callback when its app changes, and everything on `onTrimMemory`.
     - Shortcut on purpose: memory only. Add a disk cache only if a cold start shows blank icons.

9. **Battery: stop polling 24/7** (S; lead for ChatListener, F1 for LiveJobs)
   - **Why:** `:core` polls the PC every 3 s, 24/7, and writes a heartbeat each time (about 28,800 writes a day). Battery optimisation is off, so Doze doesn't stop it. This is the biggest drain after the mic (PROD #8).
   - **How:**
     - When the screen is off and the mode isn't DRIVING, step the interval 3 s → 15 s → 60 s.
     - Every interval must stay under `Checks.LISTENER_STALE_MS` (120 s), or the Watchdog starts toggling the listener.
     - Pause LiveJobs while the screen is off.
     - Measure with `dumpsys batterystats` over one day.

10. **Stop thread and executor leaks** (S; H, D, C)
    - **Why:** `HomeActivity.refresh()` can stack threads when a cycle runs past 20 s (PROD #10). The executors in `ModeChip.kt:312` and `GlancePrefsActivity.kt:48` are never shut down (PROD #16).
    - **How:** an `AtomicBoolean inFlight` guard on `refresh()`; shut the executors down in `onDetachedFromWindow` / `onDestroy`.

11. **HomeActivity manifest flags like Launcher3 and One UI** (S, lead)
    - **Why:** REA verified both launchers set these, and PROD #9 found the dark-mode and font-size bugs.
    - **How:**
      - Add `resumeWhilePausing="true"`, `clearTaskOnLaunch="true"` and `windowSoftInputMode="adjustNothing"`.
      - Add `uiMode|mcc|mnc` to `configChanges`. This matches the ice-light-always wish: dark mode won't recreate Home.
      - Remove `density|fontScale` so those changes recreate Home properly. Today text stays stale because `Look.density` is cached.

12. **Data safety** (S/M; lead plus a Settings owner)
    - Add `dataExtractionRules` excluding `pair_cookie`. `allowBackup=false` doesn't block device-to-device transfer on some phones; unverified on Samsung.
    - Add Settings export and import as one JSON file: prefs, dock, hidden apps, launch history, mode, shift, glance and widget layout. Use `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`, which need no permission.
      - Never export `pair_cookie`.
      - Widgets must be picked again after an import.
      - This must exist before any uninstall.

13. **Checks** (S, lead)
    - Lint baseline with `abortOnError`, plus the error list in PROD §3.5.
    - Debug-only StrictMode (thread and VM policy, log only), switched on by `FLAG_DEBUGGABLE` because BuildConfig is off by default.
    - Never run monkey or instrumented tests against `com.jhon.agenticos`. They make real approvals and dock edits, and can put his Home into safe mode.

## P1: what most raises quality and feel

1. **Home press semantics** (S, H)
   - **Why:** today the close animation also plays when he returns from an app. Launcher3 checks first (OS #7).
   - **How:** in `onNewIntent`, `alreadyOnHome = hasWindowFocus() && (flags and FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0`. Animate `closeTop()` only when true; otherwise reset instantly.

2. **App launch zoom-through** (S, lead for `Motion.kt`, H for the call site)
   - **Why:** `makeScaleUpAnimation` scales width and height separately, so the square icon rect squashes the whole app into a square at the start (IOS #1, from AOSP `TransitionAnimation`).
   - **How:**
     - Pass a start rect with the screen's aspect ratio, centred on the icon: `h = v.width * screenH / screenW`, `startY = (v.height - h) / 2`.
     - At the same moment as `startActivity`, push Home away: scale to 1.1 plus blur, spring 0.35 s / damping 1.0. That matches the system's fixed 336 ms.
     - Keep the 0.55 / 1.0 landing on return.
     - Add `intent.sourceBounds`, `setSplashScreenStyle(SPLASH_SCREEN_STYLE_ICON)`, and a Toast when a launch fails. Today `launchFrom` swallows every error.
   - **Conflict:** none. This is the "cinematic zoom-through" he asked for.
   - **Unverified:** whether One UI replaces the default handler. Test scale-up against clip-reveal on the phone.

3. **Haptics the S25 FE actually plays** (S/M, lead for `Motion.kt` and the manifest)
   - **Why:** `CLOCK_TICK` maps to `EFFECT_TEXTURE_TICK`, which the docs say "may not make any vibration" (IOS #3).
   - **How:**
     - Change `tick()` to `SEGMENT_TICK` (API 34, maps to `EFFECT_TICK`). Keep `performHapticFeedback` so his touch-feedback setting still applies. Test both on the phone.
     - Signature haptics: add `VIBRATE`, check `areAllPrimitivesSupported` once and cache it, and call the vibrator off the touch path, as One UI and Launcher3 do.

       | Moment | Effect |
       |---|---|
       | Ring snaps to a slot | `TICK` at 0.4 |
       | Ring crossing while spinning | `LOW_TICK` at 0.3, at most once per 40 ms |
       | "Needs you" panel arrives | `QUICK_RISE` 0.4, then `CLICK` 0.7 after 40 ms |
       | Approve / decline | `CONFIRM` / `REJECT` (keep) |

       If primitives aren't supported, fall back to the HapticFeedbackConstants.
   - **Conflict:** Apple's guidance says don't overuse haptics. Jhon asked for haptics on every press, so that stays, as the lightest tick.
   - **Unverified:** Samsung's support for primitives.

4. **Cheap blur, One UI's method** (S/M; lead for `Motion.kt`, H, A for `FrostView`)
   - **Why:** One UI rounds the blur radius into steps and re-applies only on change; we make a new `RenderEffect` every frame on whole trees (REA #4, IOS #9).
   - **How:**
     - Cache a `RenderEffect` per radius rounded to 0.5 to 1 dp, and skip `setRenderEffect` when the radius is unchanged.
     - Blur the visible children, not a parent full of off-screen content.
     - Draw `FrostView` once into a quarter-size bitmap, blur it once, and draw that bitmap scaled up.
     - In battery saver, swap blur for scale and alpha. **[I]**
   - **Conflict:** small. He wants blur, and this only applies when the system's battery saver is on.

5. **Spring values** (S, all motion owners). Contract rules apply: damping 1.0 by default, response 0.35 to 0.6 s, bounce only after a flick.

   | Moment | Response / damping | Note |
   |---|---|---|
   | Launch zoom-away | 0.35 / 1.0 | |
   | Return landing | 0.55 / 1.0 | keep |
   | Context menu open | 0.38 / 1.0 | IOS suggested a 0.86 shell; that is bounce without a flick, so use 1.0 |
   | Context menu close | 0.35 / 1.0 | IOS said 0.30, but the contract floor is 0.35 |
   | Island collapse | 0.38 / 1.0 | |
   | Orbit flick / widgets page flick | 0.6 / 0.8 and 0.46 / 0.84 | keep |
   | Press down / release | 0.16 / 1.0, 0.42 / 0.82 | keep (touch feedback) |

   - **Conflicts:**
     - The One UI popup tween (350 ms, `PathInterpolator(0.22, 0.25, 0, 1)`) breaks "springs only". Use the spring.
     - IOS's island expand at 0.50 / 0.80 bounces without a flick, and so does today's 0.45 / 0.75. Lead's call; contract says 1.0.
     - The Kyant glass glow at 0.36 / 0.5 is very bouncy. Use 1.0 or drop the glow.

6. **Liquid-glass refraction panels** (L; A owns `Panels.kt`, lead owns any `Look` token)
   - **Why:** the biggest visual gain. It matches his "liquid glass, needs you" wish (IOS #7).
   - **How:**
     - A `GlassView` that ports Kyant0/AndroidLiquidGlass's AGSL rounded-rect refraction shader (Apache-2.0, keep its notice) to Views.
     - Record `FrostView` + `WaveCore` into a `RenderNode`, re-recorded only while `WaveCore` animates.
     - Effect chain: blur 2 to 4 dp, then refraction, then saturation 1.5. Then a 45° specular streak (white 50%, ADD).
     - Refraction band = 0.25 × the panel's shorter side, capped at 16 dp. Refraction amount = 2 × the band.
     - Appear by ramping refraction up from 0 while the blur goes 8 dp → 0 ("materialize").
     - Only on the "needs you" panels and the dock.
     - Under `reducedMotion()`, use the current `glassCard()`.
   - **Unverified:** cost on the 3060-class mobile GPU. Measure frame time on the phone.

7. **Search and ranking** (M, B; `LaunchCounts.ranked` signature unchanged)
   - **Why:** better matching ("ytm" finds "YT Music"), and today an app used heavily in September stays on the inner ring forever (OS #8, #9).
   - **How:**
     - Normalize labels with `android.icu.text.Transliterator` (exact ID string unverified) and cache the result on each app.
     - Score in three bands, like Kvaesitso: subsequence match first, Jaro-Winkler typo fallback for queries of 3+ characters.
     - Rank the orbit rings by KISS frecency instead of raw counts: a capped ring buffer of the last N × 30 launch ids.
     - JVM unit tests for the scorer.

8. **120 Hz where it isn't automatic** (S, A plus lead for `Spring.kt`)
   - **Why:** alpha-only, blur-only and Canvas animations may run at 60 Hz (IOS #5, #6).
   - **How:**
     - On API 35, set `setRequestedFrameRate(REQUESTED_FRAME_RATE_CATEGORY_HIGH)` on `WaveCore` and on blur-only views while they animate, then back to default.
     - Optional: time springs from `expectedPresentationTimeNanos`. **[I]** benefit unverified.

9. **Widget hardening** (M, E)
   - **Why:** Lawnchair does all of this (OS #10).
   - **How:**
     - Send the MIN/MAX `OPTION_APPWIDGET_*` values next to the `SizeF` list.
     - Start listening on resume and stop on pause, off the main thread, ignoring TransactionTooLarge.
     - Hold `updateAppWidget` while the widgets page swipes, apply the last update on settle, with a timeout.
     - Catch `IllegalArgumentException` on configure.

10. **Pin requests** (M; lead for the manifest, G and E for the actions)
    - **Why:** without them, "Add to Home screen" buttons inside other apps do nothing (OS #11).
    - **How:** handle `CONFIRM_PIN_SHORTCUT` and `CONFIRM_PIN_APPWIDGET`: `getPinItemRequest`, then `accept()`, then add to the dock or the widgets page.

11. **Accessibility** (S/M, H)
    - **Why:** TalkBack swallows one-finger swipes, so the widgets page is unreachable (PROD #15).
    - **How:** custom actions on `HomeRoot` ("Open apps", "Search apps", "Show widgets", "Back to home"), plus `performClick()`. Test with font scale 1.3.

12. **Island blur fallback** (S, owner of `Island.kt` / `IslandService`)
    - Use the cross-window blur listener with an opaque pill fallback, as `AssistActivity` already does. First check whether the island uses window blur at all; I didn't.

13. **After each install** (S, lead)
    - Run `adb shell cmd package compile -m speed -f com.jhon.agenticos`. Don't add `profileinstaller`.

## P2: nice to have

- **Ice themed icons:** use `AdaptiveIconDrawable.getMonochrome()` tinted ink `#1D2B3A` on a white-glass disc, as an option (OS #5). Fits the one-accent look.
- **Legacy icon sizing:** normalize old non-adaptive icons so sizes match. Optional tight shadow using Launcher3's numbers.
- **Notification previews in the app menu:** fed from `:core` through a cached file. Count dots only if Jhon asks; the contract bans decorative status dots.
- **Private Space:** `ACCESS_HIDDEN_PROFILES`. Samsung mostly uses Secure Folder, and private space on One UI 9 is unverified.
- **PC long-poll:** `/api/phone/outbox?wait=25` on the PC side cuts requests about 8×.
- **Exit log in Settings > About:** show `exits.log`.
- **Robolectric:** test-only, when the Crash and SafeMode work lands.
- **Kotlin build config:** move `kotlinOptions` to `compilerOptions`.

## Things top launchers do that we deliberately won't

- **Icon-to-window open and close morph, interruptible launch, predictive-back-to-home, Recents and gesture input.** These need `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS` (signature|privileged) or system access. Lawnchair needs root for Recents. Our substitute is the zoom-away on launch plus the landing on resume.
- **Samsung `sem*` APIs and the `com.samsung.android.graphics` blur.** Non-SDK, reflection only, can break on any update.
- **Wallpaper blur and screenshot-based glass.** System-only. We own the backdrop (`FrostView`), so in-window `RenderEffect` and AGSL cover it.
- **Compose, Hilt, Room, DataStore, `androidx.dynamicanimation`, `profileinstaller`.** One UI ships Compose and doesn't use it; Launcher3's home is Views. Our `Spring` already matches Apple's model, and platform `LruCache`, `HandlerThread` and `Transliterator` cover the rest.
- **SQLite icon database.** An in-memory LruCache first; a file cache only if needed.
- **Material look, Monet / dynamic colour, following dark mode.** Jhon chose ice light always and the iPhone feel.
- **iPhone's "no haptic on tap", the 4×6 grid, edit-mode jiggle.** His wishes win: a tick on every press, the Orbit, no idle loops.
- **One UI's tween popup timing.** Springs only.
- **Ads, analytics, crash upload, trackers.** Nova's ad backlash shows the cost. Crash data stays local in `exits.log`.
- **Nova backup import, Play Store release, cloud backup.** Personal, sideloaded: keep `allowBackup=false` and use our own export.

## Not verified (test on the phone)

- Samsung support for composition primitives, `SEGMENT_TICK` and `CLOCK_TICK` strength
- One UI's handling of scale-up versus clip-reveal and the splash style
- cross-window blur on One UI
- the Android 17 `getActivityList` `SecurityException` (from a Lawnchair code comment only)
- the debug keystore's validity and whether it matches the installed copy
- Smart Switch device-transfer behaviour
- whether the AGSL glass costs too much frame time
- whether the `:web` process actually shrinks Home

---
I skipped re-checking each report's line numbers; I only spot-checked the claims at the top. Biggest live risk: P0 items 1 and 2 can crash `:core`, and a second crash within 2 min makes Android stop it and restrict the whole app. Don't change the signing key before the export in P0 item 12 exists.