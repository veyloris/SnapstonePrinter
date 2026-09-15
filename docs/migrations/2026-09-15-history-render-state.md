# History and latest-render state validation

Created: 2026-09-15. State: started (regression preparation).

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

After: unverified until production implementation.

## Validation

Local command:

```bash
JAVA_HOME=/home/veyloris/.local/share/snapstone-review/jdk-25 ANDROID_HOME=/home/veyloris/Android/Sdk ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Measured 2026-09-15 before refreshing the execution base: exit 0, `BUILD SUCCESSFUL in 32s`,
`88 actionable tasks: 88 executed`; full output `/tmp/snapstone-history-red-compile.log`.
`git diff --check` returned exit 0. Compilation does not execute instrumentation.

Unverified: hosted red assertions, implementation, hosted green assertions, and independent review.

## Limits

The initial tests exercise real asynchronous rendering but do not control its completion order;
reserve cancellation-ignoring renderer and rapid-tone ordering cases for the injected renderer.
Do not infer actual export behavior from `canPrint`, nor physical printing from a state assertion.
