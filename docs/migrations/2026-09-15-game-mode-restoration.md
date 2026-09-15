# Game mode restoration: Step 6 executor brief

Created: 2026-09-15. State: started (hosted red observed; saver implemented, successful hosted verification pending).

## Premises

- **Measured M1:** `git rev-parse HEAD` returned `318e836cdc2f9d810595e52f5d21d19ebea784d2` in the mode-restore worktree on 2026-09-15T17:56:26Z.
- **Measured M2:** `rg -n 'appMode|enum class AppMode|onRoll' app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt` at M1 shows enum entries SNAPSTONE_WIELDER/MOMIR_VIG at lines 73–75, plain remember at line 91, and routing from the same value at lines 95–98. Top-bar and expanded controls receive that value and callback at lines 156–158 and 210–211.
- **Measured M3:** `cat app/src/main/java/com/example/snapstoneprinter/MainActivity.kt` and `sed -n '145,182p' app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt` at M1 show MainActivity constructs the screen through navigation and ViewModel initialization only collects printer preferences. Mode selection/recreation and opening the CMC screen need no fetch; selecting a CMC invokes fetch at screen line 295.
- **Measured M4:** `cat app/src/main/java/com/example/snapstoneprinter/data/api/ScryfallApiService.kt` at M1 shows two suspend API methods: getRandomCard(query: String?): ScryfallCard and getCardByName(fuzzy: String): ScryfallCard. CardRepository accepts that interface in its constructor, so isolated routing tests need no production injection hooks.
- **Measured M5:** `rg --files --hidden -g AGENTS.md -g CLAUDE.md -g '!.git/' .` at M1 returned no project instruction files. Existing androidTest UI tests use createComposeRule, measured by reading ProxyPreviewLayoutTest.kt.
- **Inherited M6:** root scoped one independent feature PR, owns refresh/merge coordination, and permits hosted API36 emulator testing while physical devices/printers remain excluded. Another feature branch edits history-related regions of this screen.

## Decision and boundary

Persist only the chosen enum name in Compose saved instance state. Derive labels and roll routing from that restored value exactly as the screen already does. Reject enum ordinal storage because source ordering would change the meaning of saved data. Do not persist transient sheets, bitmaps, history, or printer jobs in this feature.

One executor pass may change only mode state/saver in `app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt`, add focused tests, and update this evidence document or its sibling appendix. Do not refactor screen ownership or introduce mutable global test hooks. Start from fork master; if history merges first, refresh this branch and preserve its screen changes before push. Do not stack the unrelated features.

## Contract

Add `internal val AppModeSaver: Saver<AppMode, String>` beside AppMode using `save = { it.name }` and `restore = { stored -> AppMode.entries.firstOrNull { it.name == stored } ?: AppMode.SNAPSTONE_WIELDER }`. Import the Compose saveable APIs. Change only the mode declaration to `var appMode by rememberSaveable(stateSaver = AppModeSaver) { mutableStateOf(AppMode.SNAPSTONE_WIELDER) }`.

The allowed stored values are exactly current enum names. Unknown strings, including empty, lowercase display names and removed future names, restore SNAPSTONE_WIELDER without throwing. The saver accepts String input by type; no arbitrary-object coercion is required. Missing saved state also initializes SNAPSTONE_WIELDER. Preserve all existing action callbacks and derive both displayed mode and routing from this single restored variable.

## Tests first

Add `AppModeRestorationTest.kt` under the existing androidTest UI package. Use StateRestorationTester with the real ProxyGeneratorScreen and a real ViewModel backed by CardRepository and a local fake ScryfallApiService. Hold the ViewModel outside the recomposed test content in a test-owned ViewModelStore and clear the store afterward. The fake records API calls and throws a deterministic IOException immediately when requested; this proves routing without rendering, source art, live network, or production globals. Do not assert that fetching succeeds in this feature's routing tests.

- `momirRestoresLabelAndCmcRouting`: select MomirVig through the actual mode menu, emulate saved-instance restoration, assert visible MomirVig label and `Pick a CMC` action. Click that action, assert the real CMC screen appears and fake API call count remains zero. Do not select a CMC.
- `snapstoneRestoresDirectRollRouting`: select MomirVig then switch back to Snapstone Wielder, restore, assert Snapstone label and `Random card` action. Click it, wait for exactly one fake random API call and assert no CMC screen. Assert the captured query is the current default query emitted by ScryfallQueryBuilder; do not copy obsolete query text into this test.
- `unknownSavedModeFallsBack` and `everyModeRoundTripsByName` in JVM `AppModeSaverTest`: call the actual AppModeSaver.restore for unknown, empty, and display-name strings; assert SNAPSTONE_WIELDER. Assert each enum round-trips through the actual saver and its saved string equals enum.name. Keep these pure saver assertions runnable locally; do not reproduce saver logic in a helper.

Add a separate `AppModeActivityRecreationTest.kt` using `createAndroidComposeRule<MainActivity>()`, whose ActivityScenario is available through the rule. Launch the real activity, select MomirVig, call `activityRule.scenario.recreate()`, assert restored label/action, open CMC screen and assert it is visible. Never click a random-card action or pick a CMC in this activity test: M3 shows those interactions are the fetch boundaries. This existing activity is the practical fixture; no new exported activity, production factory override, mutable singleton, or manifest edit is required. Treat this as activity recreation coverage, not a claim about OS process death.

Use real semantics text/content descriptions already in the screen to select controls. Inspect the CMC screen's actual stable heading before writing assertions; prefer an existing heading over matching generic numeric text. If duplicate menu/title labels exist, constrain the selector to the visible popup/clickable node instead of adding arbitrary timing sleeps.

Record a runnable red restoration failure against plain remember before the production declaration change. Saver-only cases can follow once its public testable symbol exists; do not present compilation failure as regression evidence. Run `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` and hosted `:app:connectedDebugAndroidTest`. Nonzero is failure. Hosted restoration/routing assertions must execute successfully before merge; compilation alone cannot certify restoration.

## Invariants and limits

Preserve current modes, display names, compact/expanded routing, transient sheet reset behavior and all rendering/history/dispatch changes from refreshed master. Do not introduce network test traffic or new dependency versions. Root owns independent review, push and merge. M6 remains inherited scope/coordination evidence. Physical devices and actual process termination are unverified; no process-death claim is required.

## Before/after appendix

Before query executed at M1 on 2026-09-15T17:56:26Z:

```sh
git rev-parse HEAD
rg -n 'appMode|enum class AppMode|onRoll' app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt
```

Observed output:

```text
318e836cdc2f9d810595e52f5d21d19ebea784d2
73:enum class AppMode(val displayName: String) {
91:    var appMode by remember { mutableStateOf(AppMode.SNAPSTONE_WIELDER) }
95:    val onRoll: () -> Unit = if (appMode == AppMode.SNAPSTONE_WIELDER) {
156:                appMode = appMode,
157:                onAppModeChange = { appMode = it },
158:                onFetchRandom = onRoll,
210:                        appMode = appMode,
211:                        onFetchRandom = onRoll,
333:    appMode: AppMode,
359:                        text = appMode.displayName,
393:            val rollLabel = if (appMode == AppMode.SNAPSTONE_WIELDER) {
887:    appMode: AppMode,
953:            Text(if (appMode == AppMode.SNAPSTONE_WIELDER) "Random card" else "Pick a CMC")
```

Run the same query against the implemented HEAD, append timestamp/output and `git diff --check` output here or in a linked sibling appendix, and record exact red/green commands/results. The source query checks edit scope; the named tests check saved state and action routing.

## Test-only executor stage — 2026-09-15

The executor measured HEAD `318e836cdc2f9d810595e52f5d21d19ebea784d2` and an initially untracked plan, with no source changes. Repeating the before query after test authoring returned exactly the M1 mode/routing output above; `git diff HEAD -- app/src/main app/build.gradle.kts` returned no output. The CMC heading was measured with `rg -n 'Pick a converted mana cost' app/src/main/java/com/example/snapstoneprinter/ui/ProxyPanels.kt`, which located that text at line 323 before writing the UI assertions.

The isolated screen tests hold the ViewModel in a test-owned ViewModelStore, create/clear it on the UI thread, and use a recording API fake that immediately throws IOException. The activity test selects MomirVig, recreates MainActivity, and opens the CMC screen without selecting a mana value. Review the tests' exact interactions when checking that they stay short of real fetch boundaries; no production injection hooks or fixture activities were added.

The source-diff observation above anchors the test-only stage before saver implementation; use the hosted red and subsequent saver evidence below for current progress.

The local test-only verification command exited 0 on 2026-09-15:

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleDebugAndroidTest --console=plain
```

```text
Initial invocation: BUILD SUCCESSFUL in 31s
After fixture setup adjustment: BUILD SUCCESSFUL in 10s
Unit XML timestamps retained from initial invocation: 2026-09-15T18:01:47.358Z through 2026-09-15T18:01:47.553Z
Unit XML totals: tests=81 failures=0 errors=0 skipped=0
Lint XML: errors/fatal=0 warnings=58
app/build/outputs/apk/debug/app-debug.apk: 66870422 bytes
app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk: 2416889 bytes
```

The executor parsed the unit/lint XML reports and APK file sizes for these observations. `git diff --cached --check` and `git diff --check HEAD` passed after staging only the restoration tests and this plan on 2026-09-15. These local commands assembled the test APK; use the later hosted report for runtime restoration assertions.

## Hosted red and saver implementation — 2026-09-15

Before implementing the saver, the executor inspected downloaded XML from [hosted run 35005184574](https://github.com/veyloris/SnapstonePrinter/actions/runs/35005184574). Its timestamp `2026-09-15T18:08:59` records:

```text
Overall: tests=41 failures=2 errors=0 skipped=0
AppModeActivityRecreationTest.activityRecreationRetainsMomirModeAndCmcRouting: failed at line 24
AppModeRestorationTest.momirRestoresLabelAndCmcRouting: failed at line 65
Both assertions: MomirVig is not displayed after recreate/restore
AppModeRestorationTest.snapstoneRestoresDirectRollRouting: passed
```

`git rev-parse HEAD` returned `6b20d43e6a60215d3d879ba7472263b8483f6df6` and `git status --short` returned no changes at resumption. The executor checked that both failing lines follow recreation/restoration, rather than initial mode selection.

The pure actual-Saver tests were added in the JVM source set to obtain executable local feedback for the stored-value contract. With neutral restoration scaffolding, this command exited 1:

```bash
./gradlew --no-daemon :app:testDebugUnitTest \
  --tests com.example.snapstoneprinter.ui.AppModeSaverTest --console=plain
```

```text
AppModeSaverTest > everyModeRoundTripsByName FAILED
2 tests completed, 1 failed
BUILD FAILED in 11s
```

After that failure, the implementation added allowed-name restoration with the Snapstone fallback and changed only `appMode` to rememberSaveable. No other screen state or callback routing changed; inspect the source diff and unchanged Android regressions before the hosted green run.

After the saver change, the same mode source query returned:

```text
6b20d43e6a60215d3d879ba7472263b8483f6df6
75:enum class AppMode(val displayName: String) {
98:    var appMode by rememberSaveable(stateSaver = AppModeSaver) { mutableStateOf(AppMode.SNAPSTONE_WIELDER) }
102:    val onRoll: () -> Unit = if (appMode == AppMode.SNAPSTONE_WIELDER) {
163:                appMode = appMode,
164:                onAppModeChange = { appMode = it },
165:                onFetchRandom = onRoll,
217:                        appMode = appMode,
218:                        onFetchRandom = onRoll,
340:    appMode: AppMode,
366:                        text = appMode.displayName,
400:            val rollLabel = if (appMode == AppMode.SNAPSTONE_WIELDER) {
894:    appMode: AppMode,
960:            Text(if (appMode == AppMode.SNAPSTONE_WIELDER) "Random card" else "Pick a CMC")
```

The full local JVM/lint/debug/instrumentation-assembly command recorded above exited 0 after implementation:

```text
BUILD SUCCESSFUL in 20s
Unit XML timestamps: 2026-09-15T18:12:41.493Z through 2026-09-15T18:12:41.708Z
Unit XML totals: tests=83 failures=0 errors=0 skipped=0
Lint XML: errors/fatal=0 warnings=58
app/build/outputs/apk/debug/app-debug.apk: 67221180 bytes
app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk: 2416889 bytes
```

The executor parsed the XML reports and APK file sizes for these results. `git diff --cached --check` and `git diff --check HEAD` passed after staging the saver, JVM assertions, and updated evidence on 2026-09-15. Successful hosted saved-state/activity-restoration execution and independent review remain unverified; the JVM saver result does not certify activity recreation.
