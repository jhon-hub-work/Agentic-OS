# Agentic OS phone: redesign contract (1 Nov 2026)

Every builder reads this whole file first. It overrides older notes where they disagree. Owner: Jhon. Phone: Galaxy S25 FE, Android 17 / One UI 9, sideloaded, personal only.

## What Jhon asked for, in his words

- "Agentic OS is in the middle, and something that needs me is somehow similar to how Jharvis pops up the panels on my PC."
- Apps organised as an **Orbit**: apps sit on rings around the Agentic OS core, most used on the inner ring, drag to spin, tap to open.
- Premium = iPhone feel, **cinematic** motion (big moves, blur, depth, zoom-through), haptics on every press, frosted glass, everything interruptible. "It should feel good when using it."
- **Ice light** always, like his dashboard and Jharvis (`agentic-os/web/src/index.css`), whatever the phone's dark mode says.
- Widgets on **their own page, swipe right** (like the iPhone Today view).
- Core work hours **1 AM to 4 AM Manila**, the rest of his hours are flexible.
- Driving mode: **personal chats stay spoken, group chats muted**. Night shift mode: chat alerts muted.

## Design system (locked)

Source of truth: `D:/VA/Agentic OS phone/DESIGN.md` and the dashboard's `web/src/index.css`. In code: `ui/Look.kt` (tokens; `Look.night` is false), `ui/Spring.kt`, `ui/Motion.kt`. Do not edit those three files; ask the lead (report it) if you need a token.

- **Ground**: ice `#E6EEF4` under `FrostView` (the dashboard's frost mesh: white light top-left, `#CFE3F2` bottom-right, `#DCE8F1` wash).
- **Glass, double bezel** (dashboard `.glass-shell` + `.glass`): outer shell white 28% + white 70% hairline, radius 28dp, padding 6dp; inner core white 55% (`look.glass`) + white 95% hairline (`look.glassCard()`), radius 22dp. Use it for the things that float (panels, the dock, sheets), not for every row.
- **Liquid glass** (Jharvis's panels on the PC, `.liquid-glass`): clear glass over the wave, edges lit (white 75% hairline + a soft light streak across the top), deep soft shadow `0 24 50 -24 rgba(29,43,58,.35)`. This is the look for "needs you" panels.
- **Ink** `#1D2B3A`, ink 2 `#4E6072`, ink 3 `#5E6D7B` (4.5:1 on glass). **One accent**: Glacier `#3FB6EC`. Navy chrome gradient (`look.chrome()`) for the one primary action. ok/warn/bad only with a word.
- **Type**: system sans via `look.text()` (size-specific tracking is built in). Sentence case. No eyebrows, no tracked capitals, no emoji, no em dashes in copy, no middle-dot-joined strings, no decorative status dots.
- **Android floor** (Impeccable android.md): 48dp touch targets with 8dp between; edge-to-edge with status bar, navigation bar, cutout and IME insets; predictive Back works everywhere (`onBackInvokedDispatcher`); `sp` for text; honour Remove animations (`reducedMotion()` → cross-fade or instant, no blur).
- Jhon chose the iPhone feel over Material on purpose; keep Android's system behaviours (Back, insets, targets), not Material's look.

## Motion rules (apple-design + impeccable + high-end, reconciled)

- Respond on touch-down: `look.press(view, depth)` (squish + haptic tick). Every tappable thing uses it.
- Springs (`Spring`, `SpringCurve`), never linear or ease-in-out. **Default damping 1.0** (no overshoot), response 0.35 to 0.6 s (cinematic = the slower end). **Bounce (damping ~0.8) only after a flick** carried momentum (spin release, drawer flick).
- Gesture-driven motion tracks the finger 1:1 from where it was grabbed, can be grabbed mid-flight (`Spring.stop()` + read `value`), and hands release velocity to the spring; use `project()` to land where a flick was heading; `rubberband()` at edges.
- **Materialize, don't fade**: things arrive with scale 0.92 → 1, alpha 0 → 1 and `View.blur(8dp → 0)` together; leave the same way in reverse (same path in and out).
- **Depth**: whatever is pushed behind scales to ~0.92 and blurs up to 28dp (`View.blur`), like the drawer does today.
- Stagger lists (cascade) only where items arrive together; no idle looping animation. The core moves when something happens (touch, a new need, Jharvis busy), then settles.
- Haptics: tick on press (built into `look.press`), `CONFIRM` on approve, `REJECT` on decline, `GESTURE_THRESHOLD_ACTIVATE` (API 34) when a drag crosses its commit point, a light tick when a ring snaps to a slot.

## Architecture (unchanged rules)

- Processes: UI (home, settings, dashboard), `:core` (ChatListener, ListenService, SendQueue, PC link; never imports `ui`, `android.webkit`, `android.widget`, `MainActivity` code: `checkCoreIsolation` fails the build), `:island` (IslandService overlay), `:safe` (crash fallback).
- Home must fully work with the PC off. Everything sent to the PC goes through `Link.queue` (SendQueue). PC reads use `osHttp` off the main thread. Cache what you show (`atomicWrite` into `Link.dir`) and date it when stale.
- PC endpoints (all need the pairing cookie, already handled by `osHttp`): `GET /api/jharvis/home` (urgent, next[], agents[], online, owner.timezone, closed, needs_sig), `GET /api/jharvis/approvals` (pending), `POST /api/jharvis/approvals/{id} {"approve":bool}` (409 if no longer pending), `GET /api/jharvis/panels` (`{"panels":[{"id","title","lines":[]}],"shape":...}`), `DELETE /api/jharvis/panels/{id}` (`needs` closes the Waiting on you card until it changes; `all` closes everything), `POST /api/jharvis/messages {"role":"owner","text":..,"client":"phone"}`.
- `Link.queue.add(path, json, ttlMs, method)`: for `DELETE`, pass method "DELETE" and json "" (osHttp sends no body when it is empty; done).

## Interfaces (the lead wrote stubs; each owner replaces their stub file completely, keeping these signatures)

```kotlin
// ui/WaveCore.kt (builder A): the Agentic OS core, the dashboard's Glacier dot wave drawn on Canvas
class WaveCore(c: Context, look: Look) : View(c) {
    var live: Boolean          // Jharvis reachable; grey dots when false
    var compact: Float         // 0 = full size in the middle of home, 1 = shrunk to the orbit's centre (home drives it)
    fun pulse()                // something new needs him: one swell through the dots
    fun stir()                 // a short wave burst (about 2 s), then still
}
// ui/Panels.kt (builder A): "needs you" as Jharvis-style liquid-glass panels around/below the core
class PanelStack(c: Context, look: Look, host: PanelStack.Host) : FrameLayout(c) {
    interface Host {
        fun approve(id: String, yes: Boolean)   // home queues it (it already does this today)
        fun closePanel(id: String)              // "needs" or a Jharvis panel id; home queues the DELETE
        fun open(link: String, from: View)      // a needs item's link (opens the dashboard from that view)
        fun openLog(from: View)                 // "What Jharvis did today" -> ActivityLogActivity
    }
    // raw PC JSON in; the stack diffs it: new panels materialize in, gone ones dissolve out, unchanged ones stay put
    fun show(home: org.json.JSONObject, approvals: org.json.JSONArray, panels: org.json.JSONArray, fresh: Boolean, asOf: Long)
}
// ui/Orbit.kt (builder B): all apps on rings around the core
class OrbitView(c: Context, look: Look, onLaunch: (icon: View, pkg: String) -> Unit, onClose: () -> Unit) : FrameLayout(c) {
    var progress: Float        // 0 = hidden (icons folded into the core), 1 = open; home's spring drives it
    fun load()                 // re-read installed apps + launch counts off the main thread
    fun opening()              // called once as an open starts (set up the fly-out stagger)
    fun openSearch()           // open focused on search (pull down on home)
    fun reset()                // clear search, rings back to rest
}
object LaunchCounts { fun bump(c: Context, pkg: String); fun ranked(c: Context, pkgs: List<String>): List<String> }  // in Orbit.kt
// ui/GlanceView.kt (builder C)
class GlanceView(c: Context, look: Look) : LinearLayout(c)   // date, weather, next event, core-hours line; updates itself while visible
// core/Shift.kt (builder C): see the contract in builder C's brief
// ui/ModeChip.kt + core/Mode.kt (builder D)
class ModeChip(c: Context, look: Look, onChange: () -> Unit) : LinearLayout(c)
object Modes { fun current(now: Long = System.currentTimeMillis()): Mode; fun dockKey(slot: Int): String /* ... */ }
enum class Mode { OFF, NIGHT, DRIVING }
// ui/AppMenu.kt (builder G): press and hold an app -> its menu (shortcuts, Add to dock, Hide app, App info, Delete app)
object AppMenu { interface Actions { fun addToDock(pkg: String); fun changed() }; fun show(anchor: View, pkg: String, look: Look, actions: Actions) }
object HiddenApps { fun isHidden(c: Context, pkg: String): Boolean; fun set(c: Context, pkg: String, hidden: Boolean); fun all(c: Context): Set<String> }
// ui/Widgets.kt (builder E)
class WidgetsPage(c: Context, look: Look) : FrameLayout(c) { fun start(); fun stop() }
```

## File ownership (never edit a file you don't own; report needed changes instead)

| Builder | Owns |
|---|---|
| A: core + panels | `ui/WaveCore.kt`, `ui/Panels.kt` (delete use of `ui/CoreView.kt` only by not using it; leave the file) |
| H: home integration | `ui/HomeActivity.kt`, `ui/AssistActivity.kt` (swap CoreView for WaveCore) |
| B: orbit + search | `ui/Orbit.kt`, `ui/SmartSearch.kt`, `ui/AppDrawer.kt` (reuse or remove its use), `ui/DrawerActivity.kt` (dock pick: keep `SLOT`, add `KEY` extra for D) |
| C: glance | `ui/GlanceView.kt`, `ui/Weather.kt`, `ui/GlancePrefsActivity.kt`, `core/Shift.kt`, `src/test/.../core/ShiftTest.kt` |
| D: modes | `core/Mode.kt`, `core/ModeTile.kt`, `ui/ModeChip.kt`, `src/test/.../core/ModeTest.kt`, one-line hooks in `ListenService.kt`, `ChatListener.kt`, `ui/IslandService.kt` |
| E: widgets | `ui/Widgets.kt`, `ui/WidgetPickerActivity.kt` |
| F1: log + live | `ui/ActivityLogActivity.kt`, `core/LiveJobs.kt`, `App.kt` (one line) |
| F2: share | `ui/ShareActivity.kt`, `core/ShareJob.kt` |
| G: app menu | `ui/AppMenu.kt` (the lead wires it into the orbit, the dock and search afterwards; until then long-press in those places does what their builder chose) |
| lead | `AndroidManifest.xml` (all entries already added), `Look.kt`, `Spring.kt`, `Motion.kt`, `SetupActivity.kt`, `build.gradle.kts`, resources other builders don't create |

New drawables/strings: create files with your own prefix (`ic_orbit_*`, `ic_glance_*`, `ic_mode_*`, `ic_widget_*`, `ic_log_*`, `ic_share_*`, `ic_panel_*`). Don't edit shared `res/values/*.xml`; put new strings in code like the rest of the app.

## Checks every builder runs

- `cd D:/Work/Claude/agentic-os/android && ./gradlew.bat :app:compileDebugKotlin -q` (others build at the same time: wait on the Gradle lock; errors only in files you don't own are someone else's mid-edit, ignore them). Unit tests for new pure logic: `./gradlew.bat :app:testDebugUnitTest -q`.
- No adb, no phone, no server restarts. The lead installs and tests on the phone.
- Report: files changed, what was built, what is unverified, and anything you needed from a file you don't own.

---

# Phase 2: production + features (1 Nov 2026, evening)

Read `PLAN-PRODUCTION.md` (the research synthesis: P0 = production must-fix, P1 = feel/quality, P2) and the teardown summary `D:/Temp/claude/D--Work-Claude/77239b7a-d526-48fa-a016-b816f7204b59/scratchpad/teardowns.md` (feature inventories of Niagara, Nova, Smart Launcher 6, Lawnchair, One UI Home, iOS 27). Jhon: "make it production ready, fix any issue", "as good and better than those", "copy and understand their features, never their design". The phase 1 contract above still holds (design system, motion rules, processes, PC-off, SendQueue). The lead already added every manifest entry and permission phase 2 needs (VIBRATE, USE_BIOMETRIC, BLUETOOTH_CONNECT, PinActivity, HiddenAppsActivity, CarReceiver, data extraction rules, Home flags, MainActivity in `:web`, SafeMode task affinity) and stubs for new manifest classes. Nobody but the lead edits AndroidManifest.xml or build.gradle.kts; report needs instead.

## Phase 2 ownership (replaces the phase 1 table for these files)

| Builder | Owns |
|---|---|
| CORE | `ListenService.kt`, `ChatListener.kt`, `core/LiveJobs.kt`, `core/ShareJob.kt` (only the hard-coded class name), new `core/Badges.kt`, new `core/Digest.kt` |
| STABILITY | `core/Crash.kt`, `ui/SafeModeActivity.kt`, `App.kt`, `MainActivity.kt` |
| HOME | `ui/HomeActivity.kt`, `ui/OrbitIcon.kt` |
| APPS | `ui/Orbit.kt`, `ui/AppDrawer.kt`, `ui/DrawerActivity.kt`, `ui/AppMenu.kt`, `ui/SmartSearch.kt`, new `ui/Icons.kt`, `ui/HiddenAppsActivity.kt` (stub), new `ui/LockedApps.kt` |
| MOTION | `ui/Motion.kt`, `ui/Spring.kt`, `ui/WaveCore.kt`, `ui/Panels.kt`, new `ui/GlassView.kt`, `ui/Look.kt` (additive only) |
| WIDGETS | `ui/Widgets.kt`, `ui/WidgetPickerActivity.kt`, `ui/PinActivity.kt` (stub) |
| SETTINGS | `ui/SetupActivity.kt`, `ui/AppearanceActivity.kt`, `ui/Themes.kt`, new `core/Backup.kt` |
| MODES | `core/Mode.kt`, `core/ModeTile.kt`, `ui/ModeChip.kt`, `core/CarReceiver.kt` (stub), `ui/IslandService.kt`, `ui/Island.kt`, `ui/GlancePrefsActivity.kt`, `ui/GlanceView.kt`, `core/Shift.kt` |

## Shared data contracts (write exactly these; readers rely on them)

- **Badges** (CORE writes, APPS + HOME read): `Link.dir/badges.json` = `{"<pkg>": {"count": n, "notes": [{"title": "...", "text": "...", "time": ms}]}}` (notes = latest 3, newest first). Count skips ongoing, foreground-service and group-summary notifications, uses `notification.number` when > 0, respects `Settings.Secure "notification_badging"` (0 = write an empty map). Written with `atomicWrite`, debounced ~500 ms, then an in-app broadcast `com.jhon.agenticos.BADGES` (`setPackage(packageName)`). UI draws a COUNT (text, accent hairline pill), never a bare dot; hidden or locked apps show no notes.
- **Digest** (CORE writes, HOME + MOTION show): while `Modes.current()` mutes a chat, CORE appends `{"app","pkg","title","line","time"}` to `Link.dir/digest.json` (JSON array, cap 200, dedupe by pkg+title keeping the latest line). HOME, when the mode has gone back to OFF and the digest isn't empty, calls `panels.showDigest(items) { clear }`; the clear writes `[]`.
- **Pins** (WIDGETS writes, APPS reads): UI prefs `"aos"` key `"pins"` = JSON array `[{"pkg","id","label"}]` of pinned shortcuts accepted in PinActivity; launched with `LauncherApps.startShortcut(pkg, id, bounds, null, Process.myUserHandle())`; they appear in search and can be removed from their menu.
- **Widget pins**: PinActivity allocates the id through the widgets page's host (id 1024) and appends it to the `"widgets"` prefs list WidgetsPage already uses.
- **Locked apps** (APPS): UI prefs key `"locked_apps"` (string set). Launching a locked app from our launcher asks `BiometricPrompt` (BIOMETRIC_STRONG or DEVICE_CREDENTIAL) first; say in the UI it only guards launches from this launcher.
- **Themed icons** (SETTINGS toggle in Appearance, APPS draws): UI prefs boolean `"themed_icons"`; turning it on bumps the appearance version so Home rebuilds.
- **Global gestures** (HOME sends, MODES does): in-app broadcast `com.jhon.agenticos.GLOBAL` with string extra `"a"` = `"shade"` (pull down that starts in the top 12% of the screen) or `"lock"` (double tap on empty frost); IslandService performs `GLOBAL_ACTION_NOTIFICATIONS` / `GLOBAL_ACTION_LOCK_SCREEN`. If the island service is off, nothing happens and HOME may show a one-line hint once.
- **Haptics**: everyone uses `View.tick()` and `Haptics.snap/crossing/arrive/confirm/reject` from Motion.kt (MOTION owns how they feel).
- **Digest card / glass**: `PanelStack.showDigest(items, onDismiss)` (MOTION implements).

## Rules that apply to everyone in phase 2

- Never run monkey, instrumented tests, adb input or anything that drives Jhon's phone; he uses it while we work. The lead installs and tests.
- No new dependencies. No reflection into hidden or Samsung APIs.
- Every background thread: one named HandlerThread or executor per job, shut down with its owner; no raw `Thread {}` on hot paths; nothing that blocks the main thread (disk, network, IPC, icon decode).
- Every `:core` entry point is wrapped so a bug can't crash the process (two crashes in 2 min make Android restrict the whole app).
