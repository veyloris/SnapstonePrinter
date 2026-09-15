# Print coordinator and exporter: Pass A evidence

Created: 2026-09-15. State: started; keep this helper pass unmerged until Pass B integration.

## Premises

- **Measured:** `git rev-parse HEAD` returned `6f82f7893c8cddf5a1f91fbd4780803de0fccfdb` on 2026-09-15 before application edits. `git status --short` reported only the planner's modified dispatch contract.
- **Measured:** the [contract's before/after query](2026-09-15-print-dispatch-contract.md#beforeafter-appendix) produced the source matches and file inventory below before Pass A implementation; `git diff --check` exited 0 with no output.
- **Inherited:** the main thread scoped this pass to the pure coordinator, checked exporter, tests, and evidence; it owns independent/security reviews and hosted execution. ViewModel/UI/history integration remains Pass B and must use its merged prerequisites.

## Before query output — 2026-09-15

```text
6f82f7893c8cddf5a1f91fbd4780803de0fccfdb
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:9:import androidx.activity.compose.rememberLauncherForActivityResult
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:107:    val dispatchLauncher = rememberLauncherForActivityResult(
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:141:                        chosenComponentSender(context)
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:322:private fun chosenComponentSender(context: Context) = PendingIntent.getBroadcast(
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:301:                                id = System.currentTimeMillis(),
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:357:    private fun dispatchSlips(slips: List<PrintSlip>, label: String) {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:363:                    val stamp = System.currentTimeMillis()
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:387:    fun onSlipDispatched() {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:399:    fun cancelDispatch(reason: String? = null) {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:437:            slip.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
app/src/main/java/com/example/snapstoneprinter/data/print/PrinterTargetStore.kt
app/src/main/java/com/example/snapstoneprinter/data/print/ChosenComponentReceiver.kt
app/src/androidTest/java/com/example/snapstoneprinter/ui/ProxyPreviewLayoutTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/ExampleInstrumentedTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/image/ImageProcessorTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/data/util/ManaCostFormatterTest.kt
```

## Implementation boundaries

Use the [standalone dispatch contract](2026-09-15-print-dispatch-contract.md) as the API and transition specification. Keep coordinator calls synchronous on the main thread, with canonical UUID identities, defensive URI snapshots, and explicit matching-token transitions. `PrintJobCoordinatorTest` exercises reservation, duplicate claims, explicit continuation, stale results, draining Stop, invalid IDs, invalid batches, and terminal failure behavior.

Keep PNG encoding behind the internal PngEncoder seam for false-return tests. Limit Android exporter cleanup to its registered exact batch identity and explicitly created files inside its own UUID directory. Do not derive deletion paths from a caller's batch ID, recursively delete directories, or wire successful-share cleanup into this pass. Leave successful exported batches available until an owner explicitly discards an unshared batch or the OS manages the cache. The authored `AndroidSlipExporterTest` cases cover PNG decoding/order, forged-batch rejection, partial-write cleanup, invalid identifiers, existing-directory preservation, cancellation before return, and preservation of earlier successful batches; these are unverified until hosted execution.

## Runnable coordinator red and green

Use the installed JDK25 and Android SDK through JAVA_HOME and ANDROID_HOME. The same targeted command ran before and after implementing coordinator transitions:

```bash
./gradlew --no-daemon :app:testDebugUnitTest \
  --tests com.example.snapstoneprinter.data.print.PrintJobCoordinatorTest --console=plain
```

The runnable red observation on 2026-09-15 used neutral API scaffolding and exited 1:

```text
13 tests completed, 12 failed
BUILD FAILED in 10s
```

After transition implementation, the targeted command exited 0:

```text
BUILD SUCCESSFUL in 8s
```

The token-construction test already passed during the red observation; the other cases failed actual assertions rather than relying on compilation errors as regression evidence. Broader busy-state and rejected-event assertions were added after the initial targeted green run and are included in the final full-suite verification below.

## Remaining verification

**Unverified:** Android exporter tests were authored before implementation, but no Android exporter assertion has executed locally; test APK compilation is not a substitute for hosted red/green evidence. Pass B still owns export-completion admission, history snapshot selection, cancellation-after-launch retention, ActivityResult lifecycle, and continuation UI. No coordinator test certifies physical output or actual receiver behavior. Require independent general/security review and actual hosted tests before merging the combined feature.

## After Pass A source query — 2026-09-15

The same query after the staged helper implementation returned:

```text
6f82f7893c8cddf5a1f91fbd4780803de0fccfdb
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:9:import androidx.activity.compose.rememberLauncherForActivityResult
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:107:    val dispatchLauncher = rememberLauncherForActivityResult(
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:141:                        chosenComponentSender(context)
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:322:private fun chosenComponentSender(context: Context) = PendingIntent.getBroadcast(
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:301:                                id = System.currentTimeMillis(),
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:357:    private fun dispatchSlips(slips: List<PrintSlip>, label: String) {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:363:                    val stamp = System.currentTimeMillis()
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:387:    fun onSlipDispatched() {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:399:    fun cancelDispatch(reason: String? = null) {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:437:            slip.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
app/src/androidTest/java/com/example/snapstoneprinter/ui/ProxyPreviewLayoutTest.kt
app/src/main/java/com/example/snapstoneprinter/data/print/PrinterTargetStore.kt
app/src/main/java/com/example/snapstoneprinter/data/print/AndroidSlipExporter.kt
app/src/main/java/com/example/snapstoneprinter/data/print/SlipExporter.kt
app/src/main/java/com/example/snapstoneprinter/data/print/ChosenComponentReceiver.kt
app/src/main/java/com/example/snapstoneprinter/data/print/PrintJobCoordinator.kt
app/src/androidTest/java/com/example/snapstoneprinter/ExampleInstrumentedTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/image/ImageProcessorTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/data/util/ManaCostFormatterTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/data/print/AndroidSlipExporterTest.kt
```

## Final local verification — 2026-09-15

The final command exited 0 at base `6f82f7893c8cddf5a1f91fbd4780803de0fccfdb` plus the staged Pass A diff:

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleDebugAndroidTest --console=plain
```

```text
BUILD SUCCESSFUL in 21s
Unit XML timestamps: 2026-09-15T17:52:04.372Z through 2026-09-15T17:52:04.614Z
Unit XML totals: tests=96 failures=0 errors=0 skipped=0
PrintJobCoordinatorTest XML at 2026-09-15T17:52:04.478Z: tests=15 failures=0 errors=0 skipped=0
Lint XML: errors/fatal=0 warnings=57
app/build/outputs/apk/debug/app-debug.apk: 66989548 bytes
app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk: 2396939 bytes
```

The executor parsed all unit XML headers, lint severities, and APK file sizes for those results. `git diff HEAD -- app/src/main/java/com/example/snapstoneprinter/ui app/src/main/AndroidManifest.xml app/src/main/res/xml/file_paths.xml` returned no output after implementation. A source query for `deleteRecursively|\.delete\(` in AndroidSlipExporter returned only individual registered-file deletion and empty owned-directory deletion, guarded by ownership checks; actual Android failure/cancellation cleanup still requires the authored instrumentation tests.

`git diff --cached --check` and `git diff --check HEAD` both exited 0 with no output after staging this final evidence on 2026-09-15. No application integration or physical/receiver validation is claimed by these helper-pass results.
