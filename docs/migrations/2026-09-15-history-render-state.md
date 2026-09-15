# History and latest-render state validation

Created: 2026-09-15. State: started (implementation; hosted green pending).

## Premises

- **Measured:** `git rev-parse HEAD` returned `6f82f7893c8cddf5a1f91fbd4780803de0fccfdb`
  on 2026-09-15 in branch `fix-history-render-state`.
- **Measured:** the before query below located debounce-before-busy state, wall-clock history
  IDs, and the generation/printing entry points in that checkout.
- **Inherited:** the main thread assigned Step 4 of the
  [card correctness plan](2026-09-15-card-print-correctness.md) and chose a dedicated
  nonblocking render-error message; defer dependent UI behavior to its supplemental contract.
- **Inherited:** hosted emulator testing is authorized, physical devices/printers are excluded,
  and the main thread owns commits, GitHub writes, review, and merge coordination.

## Regression boundary

Use the existing `CardRepository(ScryfallApiService)` injection point to establish runtime
failures before production edits. Gate fake API responses with deferred completions; make
old requests ignore cancellation so revision checks must reject stale results explicitly.
Synchronize assertions with main-thread barriers and bounded StateFlow waits instead of sleeps.
Use text-only fixtures to avoid network art downloads while exercising real bitmap composition.

`ProxyGeneratorStateTest` covers delayed old fetch versus a completed newer card, an old
failure while a newer request remains busy, immediate print ineligibility during tone debounce,
and replacement of only the current history snapshot after tone rendering completes.
Keep dispatch APIs unchanged; add controlled renderer/art-source cases after their specified
interfaces are available, and defer export-argument assertions to the separate exporter seam.

## Before/after appendix

Run before and after implementation:

```bash
rg -n 'fun scheduleRedither|delay\(REDITHER|isRedithering = true|history = it.history|id = System.currentTimeMillis|fun generateProxy|fun printCurrentCard|fun reprint' app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt
```

Before, measured 2026-09-15 at the revision above:

```text
198:    private fun scheduleRedither() {
202:            delay(REDITHER_DEBOUNCE_MS)
203:            _uiState.update { it.copy(isRedithering = true) }
252:    private fun generateProxy(notFoundMessage: String, fetchBlock: suspend () -> ScryfallCard) {
299:                        history = it.history.prepended(
301:                                id = System.currentTimeMillis(),
347:    fun printCurrentCard() {
353:    fun reprint(entry: HistoryEntry) {
```

After implementation, measured 2026-09-15 on red-test HEAD
`51e0f89689149cd3e61a0aef6994b83486202f2c` plus the uncommitted implementation:

```text
220:                if (debounce) delay(REDITHER_DEBOUNCE_MS)
299:    private fun generateProxy(notFoundMessage: String, fetchBlock: suspend () -> ScryfallCard) {
380:    fun printCurrentCard() {
387:    fun reprint(entry: HistoryEntry) {
```

## Validation

Local command:

```bash
JAVA_HOME=/home/veyloris/.local/share/snapstone-review/jdk-25 ANDROID_HOME=/home/veyloris/Android/Sdk ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Measured 2026-09-15 before refreshing the execution base: exit 0, `BUILD SUCCESSFUL in 32s`,
`88 actionable tasks: 88 executed`; full output `/tmp/snapstone-history-red-compile.log`.
`git diff --check` returned exit 0. Compilation does not execute instrumentation.

### Hosted red — 2026-09-15

The executor independently inspected the XML artifact at
`/tmp/snapstone-history-red-reports/app/build/outputs/androidTest-results/connected/debug/TEST-test(AVD) - 16.xml`;
artifact download provenance is inherited from the main thread's
[run 35003959401](https://github.com/veyloris/SnapstonePrinter/actions/runs/35003959401)
at `51e0f89689149cd3e61a0aef6994b83486202f2c`.

```text
42 tests, 4 failures, 0 errors
completedToneUpdatesOnlyItsExistingHistorySnapshot: history retained a different slip reference
oldFetchFailureCannotClearNewRequestBusyState: new request must remain loading
toneDebounceImmediatelyDisablesPrinting: tone work includes the debounce period
delayedOldFetchCannotReplaceNewerCard: expected New, observed Old
```

### Implementation and local checks — 2026-09-15

Implement the [standalone render contract](2026-09-15-history-render-contract.md) for
generation/revision ownership, applied tone, nonblocking failure presentation, ID-based reprint
eligibility, and same-ID thumbnail replacement. Keep the separate dispatch transport unchanged.

The executor authored controlled ArtSource/SlipRenderer tests with a virtual Main dispatcher
and UI notice/thumbnail assertions before changing production. `ProxyGeneratorRenderOwnershipTest`
restores Main in teardown and uses cancellation-ignoring deferred completions to exercise ownership
checks; the existing API-gated cases retain the measured runtime red evidence above.

The local command above returned exit 0 after implementation, `BUILD SUCCESSFUL in 31s`,
`88 actionable tasks: 24 executed, 64 up-to-date`; log `/tmp/snapstone-history-green-local.log`.
`git diff --check` returned exit 0. The malformed-render test was subsequently extended to
cover both empty and excess output, and the eviction case now checks monotonic history IDs;
the same local command then returned exit 0, `BUILD SUCCESSFUL in 12s`,
`88 actionable tasks: 8 executed, 80 up-to-date`
(`/tmp/snapstone-history-green-local-final.log`). The added controlled-renderer and UI assertions
remain compile-validated only until hosted execution; do not count them as runtime passes yet.

Supplemental contract scope query, measured at the same implementation snapshot:

```bash
git rev-parse HEAD
rg -n 'fun reprint|fun dispatchSlips|canPrint|fun HistorySheet|onReprint|fun ProxyPreview|fun InlineToneControls' app/src/main -g '*.kt'
rg -n 'prepareArt|coroutines.test|coroutines-test' app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt app/build.gradle.kts gradle/libs.versions.toml
```

```text
51e0f89689149cd3e61a0aef6994b83486202f2c
app/src/main/java/com/example/snapstoneprinter/ui/ProxyPanels.kt:115:fun InlineToneControls(
app/src/main/java/com/example/snapstoneprinter/ui/ProxyPanels.kt:426:fun HistorySheet(
app/src/main/java/com/example/snapstoneprinter/ui/ProxyPanels.kt:428:    onReprint: (HistoryEntry) -> Unit,
app/src/main/java/com/example/snapstoneprinter/ui/ProxyPanels.kt:467:                        HistoryRow(entry = entry, onReprint = { onReprint(entry) })
app/src/main/java/com/example/snapstoneprinter/ui/ProxyPanels.kt:476:private fun HistoryRow(entry: HistoryEntry, onReprint: () -> Unit) {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyPanels.kt:524:        FilledTonalButton(onClick = onReprint, modifier = Modifier.heightIn(min = 48.dp)) {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:120:    val canPrint: Boolean
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:382:        if (!state.canPrint || state.dispatch != null) return
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:387:    fun reprint(entry: HistoryEntry) {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:402:    private fun dispatchSlips(slips: List<PrintSlip>, label: String) {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:270:            onReprint = {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:567:                enabled = uiState.canPrint && uiState.dispatch == null,
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:618:fun ProxyPreview(
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:977:fun ProxyPreviewEmptyPreview() {
gradle/libs.versions.toml:66:kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutinesTest" }
app/build.gradle.kts:100:    testImplementation(libs.kotlinx.coroutines.test)
app/build.gradle.kts:106:    androidTestImplementation(libs.kotlinx.coroutines.test)
```

The query no longer locates `prepareArt` in the ViewModel because the default `AndroidSlipRenderer`
now owns that call; inspect `image/SlipRenderer.kt` alongside the scope query.

Changed paths for this step: `ProxyGeneratorViewModel.kt`, `ProxyGeneratorScreen.kt`,
`ProxyPanels.kt`, `ArtDownloader.kt`, new `ArtSource.kt`/`SlipRenderer.kt`, the existing-version
Android coroutine-test dependency in `app/build.gradle.kts`, the state/ownership/UI regression
tests, and this step's contract/evidence documents. Unverified: hosted green assertions and
independent review remain with the main thread.

## Limits

`ProxyGeneratorStateTest` exercises real asynchronous rendering; the added
`ProxyGeneratorRenderOwnershipTest` controls renderer completion and tone timing through its fakes.
Do not infer actual export behavior from `canPrint`, nor physical printing from a state assertion.
