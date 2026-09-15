# Print integration evidence

Created: 2026-09-15. State: started; hosted integration acceptance pending.

## Premises

- **Measured B1:** Before production edits, `git rev-parse HEAD` returned `9634b0ad63717eaae437baadf0f855782782c55a` on 2026-09-15. `git diff dc751e064abe5eace1f57e61f21d18195a40cc9d --` the three UI/history files returned no output.
- **Measured B2:** The source query below found asynchronous dispatchSlips, parameterless advancement, wall-clock export and the shared launcher in B1.
- **Measured B3:** Hosted [run 35006839738](https://github.com/veyloris/SnapstonePrinter/actions/runs/35006839738) at `e97add0` produced XML with 67 tests and one failure: `PrintJobIntegrationTest.historyAdmissionReturnsAcceptedOnly`, “A second same-turn request must not also be accepted”, line 66. The downloaded XML is `/tmp/snapstone-print-red-reports/app/build/outputs/androidTest-results/connected/debug/TEST-test(AVD) - 16.xml`; the failed-run log is `/tmp/snapstone-print-hosted-red.log`.
- **Inherited B4:** Root owns commits, pushes and independent reviews; physical printers and network-backed fixtures remain excluded by the parent task.

## Tests before implementation

`PrintJobIntegrationTest` compiled before production edits using `:app:assembleDebugAndroidTest` (exit 0, `/tmp/snapstone-print-red-compile.log`), then failed its intended same-turn admission assertion in B3. `PrintExportOwnershipTest`, `PrintDispatchHostTest` and `PrintExternalReceiverTest` were authored before production integration but referenced the planned APIs; their initial state was authored-only, not observed runtime red.

The ownership tests target foreign/invalid/stale export batches, preclaim cleanup versus retained shared files, completed Ready lifecycle retention, pending tone and latest history snapshots. The registry tests use `dispatchResult` and Bundle save/restore with ActivityScenario recreation. The external driver targets the separate test APK's accessibility receipt, decoded pixel SHA256, distinct UID, URI/ClipData agreement and read-only grant flags. Treat these as test intentions until hosted results are recorded.

## Before/after appendix

Run the same source query before and after application edits:

```bash
rg -n 'fun dispatchSlips|fun onSlipDispatched|fun cancelDispatch|System.currentTimeMillis|\.compress\(|rememberLauncherForActivityResult|chosenComponentSender' app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt
rg --files --hidden --glob '!.git/' app/src/main/java/com/example/snapstoneprinter/data/print app/src/androidTest
git diff --check
```

Before at B1, the first query returned the following matches (paths shortened to filenames):

```text
ProxyGeneratorScreen.kt:9:import androidx.activity.compose.rememberLauncherForActivityResult
ProxyGeneratorScreen.kt:108:    val dispatchLauncher = rememberLauncherForActivityResult(
ProxyGeneratorScreen.kt:142:                        chosenComponentSender(context)
ProxyGeneratorScreen.kt:328:private fun chosenComponentSender(context: Context) = PendingIntent.getBroadcast(
ProxyGeneratorViewModel.kt:402:    private fun dispatchSlips(slips: List<PrintSlip>, label: String) {
ProxyGeneratorViewModel.kt:408:                    val stamp = System.currentTimeMillis()
ProxyGeneratorViewModel.kt:432:    fun onSlipDispatched() {
ProxyGeneratorViewModel.kt:444:    fun cancelDispatch(reason: String? = null) {
ProxyGeneratorViewModel.kt:482:            slip.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
```

After at `e97add0` plus the working integration diff on 2026-09-15, the same first query returned exit 1 with no matches; `git diff --check` returned exit 0 with no output. `PrintDispatchHost.kt` owns the new token-scoped chooser callback. The after file enumeration returned:

```text
app/src/androidTest/java/com/example/snapstoneprinter/ui/PrintDispatchHostTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/ui/ProxyGeneratorStateTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/ui/PrintExportOwnershipTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/ui/ProxyGeneratorRenderOwnershipTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/ui/ProxyPreviewLayoutTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/ui/PrintJobIntegrationTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/ui/RenderHistoryUiTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/ui/PrintExternalReceiverTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/ui/PrintReceiverActivity.kt
app/src/androidTest/java/com/example/snapstoneprinter/data/util/ManaCostFormatterTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/data/print/AndroidSlipExporterTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/ExampleInstrumentedTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/image/ImageProcessorTest.kt
app/src/androidTest/java/com/example/snapstoneprinter/image/CardStatsRenderingTest.kt
app/src/androidTest/AndroidManifest.xml
app/src/main/java/com/example/snapstoneprinter/data/print/PrinterTargetStore.kt
app/src/main/java/com/example/snapstoneprinter/data/print/AndroidSlipExporter.kt
app/src/main/java/com/example/snapstoneprinter/data/print/SlipExporter.kt
app/src/main/java/com/example/snapstoneprinter/data/print/ChosenComponentReceiver.kt
app/src/main/java/com/example/snapstoneprinter/data/print/PrintJobCoordinator.kt
```

## Local verification

At `e97add0` plus the initial integration diff on 2026-09-15, the following command exited 0 (27 seconds; `/tmp/snapstone-print-b-validation.log`):

```bash
JAVA_HOME=/home/veyloris/.local/share/snapstone-review/jdk-25 ANDROID_HOME=/home/veyloris/Android/Sdk ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Android test APK compilation is not device execution. After adding the lifecycle/admission tests and failed-tone current-print assertion, the same command exited 0 in 23 seconds on 2026-09-15 at `e97add0` plus the final integration diff (`/tmp/snapstone-print-b-final-local.log`); JVM tests were up-to-date from the initial 27-second run, while the Android test APK recompiled.

## Unverified

Inherited B4 is authorization supplied by root. Hosted execution of the integrated registry/receiver/ownership cases and independent general/security review remain pending. Physical output, real printer-app behavior and OS cache eviction are outside the test claim.
