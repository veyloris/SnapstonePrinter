# Print integration evidence

Created: 2026-09-15. State: complete; see [final core acceptance](#final-core-acceptance--2026-09-15). Earlier failed runs and pending statements below are historical observations superseded by that final record.

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

## First integrated hosted run and fixture corrections

At `f1377a6`, [run 35008463394](https://github.com/veyloris/SnapstonePrinter/actions/runs/35008463394) reported 86 tests with four failures in `/tmp/snapstone-print-b-first-reports/app/build/outputs/androidTest-results/connected/debug/TEST-test(AVD) - 16.xml`: the three continuation/recreation host cases could not find a next-slip prompt, and the external receiver driver timed out waiting for its receipt. The ownership and same-turn admission cases passed in that report; this supersedes their earlier compile-only status, while overall acceptance remains incomplete.

The fake host fixtures supplied transform layout without per-face image URIs. Running `/tmp/PrintFixtureCount.java` against the compiled `f1377a6` production classes and Kotlin stdlib produced `/tmp/snapstone-print-fixture-count.log`:

```text
per-face-art=false planned-slips=1
per-face-art=true planned-slips=2
```

The fixture correction supplies per-face `fixture://` URLs backed by fake ArtSource and asserts exactly two completed slips before starting either multi-slip test. Production SlipPlanner is unchanged.

The external failure's logcat records the remembered target but no receiver launch. **Hypothesis:** raw accessibility polling began while asynchronous export still needed a Compose test-clock frame to launch. The driver now uses Compose's bounded `waitUntil` for the expected Launched index before polling the external UI; require hosted execution to confirm this diagnosis.

The expanded external test recreates the host while the first real receiver is open, checks the retained ViewModel/token and unchanged receiver activity identity, and still requires explicit Next after Return Cancel. During the second open receiver, it invokes Stop on Main and uses the receiver's test-only `Read URI again` button to reopen/decode the same URI after Stop, checking a fresh read counter, dimensions and pixel hash before draining Return OK into Cancelled. `HistoryPrintAdmissionUiTest` drives the actual screen callback: pending tone rejects Reprint with its sheet/error retained, then completed tone allows Reprint and closes the sheet while fake export remains held. Treat these new combined assertions as compiled test intentions until hosted execution.

At `f1377a6` plus these test-only changes, `:app:assembleDebugAndroidTest :app:lintDebug` exited 0 in 19 seconds on 2026-09-15 (`/tmp/snapstone-print-fixture-correction-compile.log`). No production files changed in this correction.

## Separate receiver process runtime correction

At `c7eb329`, [run 35009392800](https://github.com/veyloris/SnapstonePrinter/actions/runs/35009392800) reported 87 tests with one external-receipt timeout; all registry and actual history callback tests passed in its XML under `/tmp/snapstone-print-b-second-reports/`. Logcat line 84184 records the explicit ACTION_SEND receiver launch, resolving the earlier no-launch uncertainty for this revision. Lines 84201 onward show the separate test process crashing during `PrintReceiverActivity.kt:22` initialization because `kotlin.jvm.internal.Intrinsics` is absent from its standalone class path. The remaining timeout was a fixture runtime failure, not evidence that sharing never launched.

Convert only the receiver fixture to Java so its separate process uses Android/JDK classes without depending on the instrumentation process's Kotlin runtime. Preserve the component name, receipt fields, SHA256 algorithm, reread counter, and return controls; leave production dependencies and acceptance assertions unchanged.

At `c7eb329` plus the Java fixture conversion, `:app:assembleDebugAndroidTest :app:lintDebug` exited 0 in 17 seconds on 2026-09-15 (`/tmp/snapstone-print-java-receiver-compile.log`). `javap -verbose` of `app/build/intermediates/javac/debugAndroidTest/compileDebugAndroidTestJavaWithJavac/classes/com/example/snapstoneprinter/ui/PrintReceiverActivity.class` is recorded in `/tmp/snapstone-print-java-receiver-javap.log`. Parsing every constant-pool Class reference against allowed prefixes `android/`, `java/`, `org/json/`, plus the receiver itself and primitive arrays `[I`/`[B`, returned an empty outside-allowed-set list. Hosted execution of the converted separate-process receiver remains required.

## Covered-host recreation driver correction

At `9114470`, [run 35010237735](https://github.com/veyloris/SnapstonePrinter/actions/runs/35010237735) reached the receiver's first URI/grant/dimension/hash/UID assertions, then failed only at external test line 101: `ActivityScenario.recreate()` internally requested RESUMED while the original host was STOPPED behind the receiver. The XML under `/tmp/snapstone-print-b-third-reports/` records that stack, superseding the earlier uncertainty about whether the Java receiver can decode the first PNG.

Replace that test-driver convenience call with a Main-thread `Activity.recreate()` request and scoped Application lifecycle callbacks. Require the original host's destruction, a distinct replacement's post-create callback, and the retained ViewModel before reinstalling its Compose host. Keep the receiver activity-identity assertion before returning its result, plus the explicit continuation and post-Stop URI reread assertions. A bounded callback timeout fails with an explicit deferred-recreation message; it does not return the receiver or silently turn this into foreground recreation. Whether the emulator recreates the covered host immediately remains unverified until this revised test executes.

At `9114470` plus this driver-only correction, `:app:assembleDebugAndroidTest :app:lintDebug` exited 0 in 19 seconds on 2026-09-15 (`/tmp/snapstone-print-background-recreation-compile.log`). No production behavior changed; hosted execution must establish the covered-host lifecycle and remaining receiver assertions.

## Stable receiver control selectors

At `26f6c81`, [run 35011118554](https://github.com/veyloris/SnapstonePrinter/actions/runs/35011118554) reported 87 tests with one failure: the external driver timed out at line 137 looking for its return button. The XML under `/tmp/snapstone-print-b-fourth-reports/` anchors that location after the first receipt's URI/hash/UID assertions and the distinct-host/retained-ViewModel/same-open-receiver assertions. Those preceding checks passed; post-return continuation and post-Stop reread remain unverified.

Give each native receiver button a stable contentDescription and compare its string value in the test driver, preserving the visible labels and result/click assertions. Add a bounded active accessibility-tree summary to timeout failures to distinguish a missing control from the wrong foreground window. The exact prior text mismatch (including whether theme transformation contributed) was not measured; do not describe all-caps behavior as its established cause. No production files change in this correction.

At `26f6c81` plus this test-only correction, `:app:assembleDebugAndroidTest :app:lintDebug` exited 0 in 17 seconds on 2026-09-15 (`/tmp/snapstone-print-accessibility-id-compile.log`); `git diff --check` returned no output. The revised receiver controls and remaining post-return assertions require hosted execution.

## Final core acceptance — 2026-09-15

**Measured:** the closeout executor queried the following on 2026-09-15 after creating branch `docs-core-closeout` from `origin/master` at `4a4c4ea4fb0702d628de76be5d275062e411c3d6`:

```bash
gh pr view 8 --repo veyloris/SnapstonePrinter --json state,mergedAt,mergeCommit,headRefOid,url
gh run view 35012018817 --repo veyloris/SnapstonePrinter --json headSha,conclusion,url
gh run view 35012018681 --repo veyloris/SnapstonePrinter --json headSha,conclusion,url
```

The [PR8](https://github.com/veyloris/SnapstonePrinter/pull/8) response returned `MERGED`, merge time `2026-09-15T19:16:31Z`, squash commit `4a4c4ea4fb0702d628de76be5d275062e411c3d6`, and PR head `5085ee0dda0e0316daa1cf34752b17cd41c1d1ed`. Both the [instrumentation run](https://github.com/veyloris/SnapstonePrinter/actions/runs/35012018817) and [build/test/lint run](https://github.com/veyloris/SnapstonePrinter/actions/runs/35012018681) returned `success` at that exact PR head. A separate `gh pr list --repo veyloris/SnapstonePrinter --state merged --limit 10 --json number,mergedAt` query returned PRs 1–9 as merged on 2026-09-15, anchoring the roadmap's earlier feature links.

**Measured:** inspection of the downloaded instrumentation XML at `/tmp/snapstone-print-b-green-reports/app/build/outputs/androidTest-results/connected/debug/TEST-test(AVD) - 16.xml` returned:

```xml
<testsuites tests="87" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-09-15T19:14:54" hostname="localhost">
```

The report includes passing `PrintExternalReceiverTest.externalReceiverReadsCorrectPngAndWaitsForExplicitNext` and `HistoryPrintAdmissionUiTest.realHistoryCallbackKeepsRejectedSheetOpenAndClosesAcceptedSheet`. Source inspection at the merged commit confirms the former asserts separate-UID PNG pixels/grants, replacement of the covered host while the same receiver stays open, retained ViewModel/token, explicit next-slip continuation, and successful URI rereading after Stop; the latter exercises the actual history callback's rejected/accepted sheet behavior. These results supersede the earlier fixture failures and combined-acceptance uncertainty. They do not establish physical printing or arbitrary third-party printer-app behavior.

**Inherited:** root coordinated independent review and authorized self-merge for this run. This executor checked merge state and reports, not the reviewers' entire deliberations. Physical phones/printers remain excluded by the user's scope; optional signed release automation remains outside the core stop line. HANDOFF's unexercised manual slider/history observations remain historical limits rather than new standing tasks.

### Documentation closeout observations

Before editing on 2026-09-15, `git status --short` returned no output at `4a4c4ea`; reading the HANDOFF opening and dispatch/integration headers showed the old validation-only header and pending states. A retrospective `git show HEAD:<path>` check additionally confirmed the roadmap's old Step1-pending and Step2-ready headings. Keep that retrospective inspection distinct from the initial pre-edit reads.

After editing, this query identified the new header, complete migration states, and neutral Step1/Step2 headings:

```bash
rg -n '^Created:|^### Step [12]|^## Current' HANDOFF.md docs/migrations/2026-09-15-card-print-correctness.md docs/migrations/2026-09-15-print-dispatch-contract.md docs/migrations/2026-09-15-print-integration-evidence.md
git diff --name-only
```

The changed-file inventory contained only HANDOFF and those three migration documents. No production or workflow file was in that output; no build was rerun for this documentation-only closeout.
