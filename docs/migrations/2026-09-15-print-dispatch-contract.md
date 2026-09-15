# Print dispatch: explicit continuation and request ownership

Created: 2026-09-15. State: complete; Passes A and B were delivered in [PR8](https://github.com/veyloris/SnapstonePrinter/pull/8), merged as `4a4c4ea`. The [final acceptance record](2026-09-15-print-integration-evidence.md#final-core-acceptance--2026-09-15) anchors passing hosted runtime and build checks at `5085ee0`. Preserve the premises, implementation instructions, and earlier unverified statements below as historical planning context; the final record supersedes their pending acceptance status.

## Premises

- **Measured D1:** `git rev-parse HEAD` in `/home/veyloris/git/SnapstonePrinter-worktrees/funny-filter` returned `ff381c52b32eee95ff11f4f3b95c3eb0b970f0a2` on 2026-09-15. Recheck the source after earlier roadmap PRs land; history/renderer integration is expected to change before this step executes.
- **Measured D2:** `sed -n '335,465p' app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt` at D1 shows export runs before `dispatch` is reserved, wall-clock filenames, unchecked `Bitmap.compress`, and parameterless `onSlipDispatched()` advancement. Use the appendix query below to anchor pre/post observations.
- **Measured D3:** `sed -n '95,153p' app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt` at D1 shows one result launcher calling parameterless advancement and a `LaunchedEffect(requestId,index)` that launches the current URI. `sed -n '525,580p'` shows the print button is disabled only after dispatch exists.
- **Measured D4:** `cat app/src/main/java/com/example/snapstoneprinter/data/print/ChosenComponentReceiver.kt app/src/main/java/com/example/snapstoneprinter/data/print/PrinterTargetStore.kt` at D1 shows chosen-component persistence through a non-job-scoped callback, remembered-target lookup by activity presence, and no print acknowledgement protocol.
- **Measured D5:** the [ActivityResultRegistry API reference](https://developer.android.com/reference/androidx/activity/result/ActivityResultRegistry), inspected 2026-09-15, documents the `register(key, contract, callback)` overload, key-based result dispatch/storage, and explicit launcher unregistration. Use a stable per-dispatch key and immutable callback token, then verify recreation against an actual registry on the emulator.
- **Inherited D6:** user explicitly chose `Ask “Send next slip” / “Stop”` on 2026-09-15. This resolves the main roadmap's prior dispatch decision gate.
- **Inherited D7:** the main thread authorized self-merging reviewed, passing fork PRs for the continuing run; upstream writes and physical devices/printers remain excluded. Use hosted API36 instrumentation introduced by the renderer step.
- **Measured D8 (supersedes inherited prerequisite):** `git -C ../history-state show dc751e0:app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt` and the corresponding screen source were inspected on 2026-09-15T18:08:57Z at full commit `dc751e064abe5eace1f57e61f21d18195a40cc9d`. They expose Unit printCurrentCard/reprint, Boolean tryReprint, internal resolveHistoryEntry, currentPullId/appliedTone/renderError, loading/redithering eligibility, and a HistorySheet callback that closes only for true. The current implementation still exports asynchronously before reserving dispatch; replace that admission behavior as specified below. This source inspection does not establish that PR 7 has merged or its hosted tests passed.
- **Measured D9:** `git rev-parse HEAD` in `/home/veyloris/git/SnapstonePrinter-worktrees/print-coordinator` returned `6f82f7893c8cddf5a1f91fbd4780803de0fccfdb` on 2026-09-15; this contract amendment precedes its Pass A implementation. Recheck the stated source premises in this checkout before implementing the adapters.

- **Measured D10:** `git rev-parse HEAD` in print-coordinator returned `e4a8ea55cb2c20e33e4f9f584fe65a1d876a3a8e` on 2026-09-15T18:08:57Z. Reading its coordinator/exporter sources confirms the Pass A signatures and handled-invalid export semantics below; preserve that implementation during integration.

## Decision and rejected alternatives

Reserve a job before asynchronous export and own transitions in a pure Kotlin coordinator. Keep Android bitmap/files/URI conversion in an exporter and ActivityResult plumbing in a small UI adapter. Use UUID job identities, monotonic slip indices, immutable URI lists, and token-bearing callbacks to make old events harmless. Keep a single active job instead of introducing a queue.

After any activity-result callback with remaining slips, show `Send next slip` and `Stop`. Treat chooser dismissal and receiver return identically for this decision: neither automatically sends another slip. Treat all result codes alike; never label a slip printed based on an Android result. Reject automatic continuation, RESULT_OK-based print acknowledgement, and a printer-specific API.

Keep one feature PR for this coordinator/export/UI change, implemented in bounded passes A and B below. Do not merge a helper-only intermediate pass. Preserve remembered-target selection independently of job progress; a chooser selection may remember the selected app but cannot advance a job.

## Contract: pure coordinator

Add `app/src/main/java/com/example/snapstoneprinter/data/print/PrintJobCoordinator.kt`, free of Android types. All coordinator calls occur on the main thread in production; deterministic unit tests call synchronously. No coordinator method suspends.

Public types and API:

```kotlin
data class DispatchToken(val jobId: String, val index: Int)
data class LaunchRequest(val token: DispatchToken, val uri: String, val label: String, val total: Int)
sealed interface StartPrintResult {
    data class Started(val jobId: String) : StartPrintResult
    data object Busy : StartPrintResult
    data object Empty : StartPrintResult
}
class PrintJobCoordinator(idFactory: () -> String = { UUID.randomUUID().toString() }) {
    val state: StateFlow<PrintJobState>
    fun start(label: String, total: Int): StartPrintResult
    fun exported(jobId: String, uris: List<String>): Boolean
    fun exportFailed(jobId: String, message: String): Boolean
    fun claimLaunch(token: DispatchToken): LaunchRequest?
    fun launchFailed(token: DispatchToken, message: String): Boolean
    fun returned(token: DispatchToken): Boolean
    fun sendNext(jobId: String): Boolean
    fun stop(jobId: String): Boolean
}
```

Require canonical UUID job IDs and nonnegative token indices when constructing tokens; factory output must parse as a UUID, equal its canonical `toString()`, and never be reused within a coordinator, otherwise throw `IllegalStateException` before entering a job. Use fixed valid UUIDs in tests rather than a permissive test-only identity format; the coordinator and Android exporter must accept the same ID set. `start(total < 0)` throws `IllegalArgumentException`; `start(total == 0)` returns Empty unchanged. Check Busy first for positive totals. Every false Boolean and null launch result means the event was rejected and state is unchanged. A true exported result means the event was handled, not necessarily that the batch was admitted: invalid active payloads enter Failed as specified below.

Define sealed `PrintJobState` variants exactly: `Idle`; `Preparing(jobId,label,total)`; `Ready(jobId,label,uris,index)`; `Launched(jobId,label,uris,index)`; `AwaitingNext(jobId,label,uris,index)` where index denotes the last returned slip; `Stopping(jobId,label,uris,index)` for an outstanding launched callback; `Completed(jobId)`; `Cancelled(jobId)`; `Failed(jobId,message)`. State URI lists are copied on admission and have exactly total nonblank elements; use private construction/helper validation for indexed states. Derive total from list size after export and derive token from jobId/index. Derive `isBusy` from allowed set `{Preparing,Ready,Launched,AwaitingNext,Stopping}`; terminal states permit a new start and retain only the latest outcome, not URI lists.

| Current state and event | Required next state / result |
|---|---|
| Idle or terminal, positive start | Preparing before return; Started(new ID) |
| Any busy state, positive start | unchanged; Busy |
| Preparing, exported with matching ID and exact nonblank URI count | Ready at index 0; true |
| Preparing, exported matching ID but wrong count/blank URI | Failed with `Could not prepare all slips for sharing.`; true (handled failure) |
| Preparing, exportFailed matching ID | Failed with supplied nonblank message or `Could not save images for sharing.`; true |
| Ready, claimLaunch matching token | Launched synchronously; return immutable LaunchRequest |
| Launched, returned matching token with remaining slips | AwaitingNext at same index; true |
| Launched, returned matching token at last slip | Completed; true |
| AwaitingNext, sendNext matching ID | Ready at index + 1; true; no direct launch inside coordinator |
| Preparing/Ready/AwaitingNext, stop matching ID | Cancelled; true |
| Launched, stop matching ID | Stopping; true; keep outstanding token registered and new jobs Busy |
| Stopping, returned matching token | Cancelled; true |
| Launched/Stopping, launchFailed matching token | Failed with supplied nonblank message or `No app could open this slip.`; true |
| Any state, event outside its listed input/state/identity combination | unchanged; false/null |

Completed means the final sharing callback returned, not paper output. Repeated Stop in Stopping is unchanged false. A callback can change state once; duplicate/out-of-order/stale callbacks cannot skip a slip or affect a later job. Do not automatically timeout Stopping into a resend-capable state while a receiver result remains outstanding; process restart clears session dispatch without replay.

## Contract: export and cleanup

Add `SlipExporter.kt` and `AndroidSlipExporter.kt` under `data/print/`:

```kotlin
data class ExportedSlips(val jobId: String, val uris: List<String>)
interface SlipExporter {
    suspend fun export(jobId: String, slips: List<PrintSlip>): ExportedSlips
    suspend fun discardUnshared(batch: ExportedSlips)
}
```

The Android implementation accepts Application context and an IO dispatcher. `export` returns exactly one content URI per input slip in input order; empty input, invalid job ID, failed directory creation, false `Bitmap.compress`, or any write/URI error throws an exception rather than returning success. Use `IOException` for IO/false-compress failures, `IllegalArgumentException` for an invalid UUID or empty input, and propagate `CancellationException` unchanged. Inject a small internal `PngEncoder` with `fun encode(bitmap: Bitmap, output: OutputStream): Boolean` for a meaningful false-compress test; its default delegates to PNG compression.

Create only `cacheDir/images/<UUID>/slip_<zero-based-index>.png`; filenames must not depend on card names or wall-clock time. Keep an exporter-owned registry of created batches/directories and validate path ownership before cleanup; `discardUnshared` must reject unknown batches instead of deriving an arbitrary filesystem deletion path from supplied text. Export clears only its own incomplete directory/files after failure or cancellation, including cancellation observed immediately before returning a completed batch. Do not delete successful shared batches when a receiver returns or the user stops remaining slips; another app may still read its granted URI. General cache retention/eviction is outside this PR.

Before admitting export completion, compare `batch.jobId` with the immutable expected jobId captured when export began. A mismatch is an exporter-contract failure: do not pass its URIs to `coordinator.exported(expectedId, ...)`; call `exportFailed(expectedId, "Could not prepare images for this print job.")`, which is ignored if expectedId is stale. Do not call discardUnshared on the mismatched payload or derive deletion paths from its ID, because it could name another job's shared batch. Log that contract violation; the production exporter must satisfy identity equality, and cleanup of its own interrupted writes remains its responsibility.

For a matching-ID batch, call `exported(expectedId, batch.uris)` and retain it only if the resulting state is Ready for that expectedId. Otherwise discard that exact owned, never-shared batch: this includes a false stale-event result AND a true handled-invalid result that entered Failed. Do not use the Boolean alone as the ownership/admission decision. Validate the exporter registry's exact batch identity before deletion; unknown/forged batch objects fail closed with no deletion. On explicit Stop or a rejected stale completion before claimLaunch, discard the owned unshared batch when available; ViewModel clearing is the lifecycle exception specified next. Track whether claimLaunch occurred; after it occurs, retain all batch files for OS cache lifecycle rather than infer which consumer has finished. On ViewModel clearing with a successfully exported Ready batch that has never launched, release in-memory ownership and leave files to OS cache lifecycle; do not create a detached cleanup scope. Preparing export cancellation still invokes the exporter’s existing incomplete-write cleanup, and launched batches remain readable. Process termination cannot promise coroutine cleanup. Test this explicit lifecycle distinction rather than asserting all unshared files disappear on process death. No blanket cache-directory cleanup, arbitrary recursive deletion, or immediate URI grant revocation on return. Perform cancellation cleanup in a bounded NonCancellable IO context so cancellation does not skip owned-file cleanup.

## Contract: ViewModel integration

Inject `SlipExporter` into `ProxyGeneratorViewModel` with a default Android implementation, preserving existing production constructor calls. Own one coordinator and one export Job in the ViewModel. Replace old `SlipDispatch` state with a `PrintJobState` field named `printJob`; remove the parameterless `onSlipDispatched` API and all callers. Expose wrappers `claimPrintLaunch(token): LaunchRequest?`, `onPrintReturned(token): Unit`, `onPrintLaunchFailed(token, message): Unit`, `sendNextSlip(jobId): Unit`, and `stopPrinting(jobId): Unit` that delegate only to the matching coordinator event.

Preserve `fun printCurrentCard(): Unit`, `fun reprint(entry: HistoryEntry): Unit`, `fun tryReprint(entry: HistoryEntry): Boolean`, and `internal fun resolveHistoryEntry(id: Long): HistoryEntry?` from D8. Keep reprint as a Unit wrapper around tryReprint. Use one private `fun startPrint(slips: List<PrintSlip>, label: String): Boolean`: return true **only** when coordinator.start returns StartPrintResult.Started and export is scheduled; Busy and Empty return false, start no export, and preserve current job/error. A true value means preparation accepted, never export/share/print success.

Before startPrint, printCurrentCard must require state.canPrint (`!isLoading && !isRedithering && slips.isNotEmpty()`) and select that state's completed slips/card label. tryReprint must reject an absent history ID, and reject the currentPullId while loading/redithering; otherwise resolve the ID against current history immediately before starting and use the resolved entry's slips/label, never the passed object's bitmap list. Return startPrint's result directly. Different completed history IDs remain eligible during current generation/tone work, subject to coordinator Busy. Preserve the screen's local HistorySheet error and close-only-on-true behavior measured in D8; no fatal preview error for a rejected history action.

After failed tone adjustment, D8 restores controls to appliedTone and retains the completed preview/history. Print those retained bitmap references without rerendering; renderError is a nonfatal notice and must not falsely disable that completed candidate. Never export a still-pending tone snapshot. Preserve appliedTone/currentPullId/history ownership and atomic history replacement unchanged.

Synchronous admission is mandatory: call coordinator.start before any suspension, and make the resulting Preparing state visible before the public method returns. Read coordinator.state.value for authoritative busy admission rather than relying on an asynchronously collected UI copy. Publish every wrapper/export-completion transition from the coordinator's current value through one synchronous helper; if a collector is retained for observation it must not replay a captured obsolete state. Use StateFlow.update to modify only printJob so concurrent unrelated UI-state updates remain intact. This prevents a same-turn second print or tryReprint call from reporting acceptance while the coordinator is Busy.

Capture slips as an immutable list at start; renderer/history changes afterward cannot alter the job's batch. Catch `CancellationException` separately from export failure; an old canceled coroutine cannot set another job's error. Route exceptions only through `exportFailed(jobId, ...)`; rejected stale errors remain ignored. Stop cancels export only when the matching job is Preparing, and performs owned unshared cleanup for other prelaunch cancellation as above. Reflect coordinator state through a single state source/collector without copying an obsolete state captured before suspension.

Disable the current print button and history reprint actions for the coordinator's isBusy set. Preserve the ViewModel guard even when UI actions are disabled. Keep card browsing/tone controls independent; they may change future print candidates but not the already-captured job.

## Contract: Android launch adapter and continuation UI

Add `ui/PrintDispatchHost.kt` and move intent/launcher plumbing out of the large screen into this adapter. Accept the registry from `LocalActivityResultRegistryOwner` by default, with a test-provided `ActivityResultRegistry` seam. Use `ActivityResultContracts.StartActivityForResult()` and key `snapstone-print:<jobId>:<index>` for each token. Register through the overload without LifecycleOwner inside a DisposableEffect and unregister its returned launcher on disposal, as required by D5. The registered callback captures an immutable token and calls `onPrintReturned(token)` for every result code; it must never read the currently active token from mutable state.

Keep registration active for Ready, Launched, and Stopping for the relevant token. Register before attempting launch. In a main-thread side effect after registration, call claimPrintLaunch(token); invoke launcher.launch only when it returns a nonnull request. Claim and launch must be contiguous without a suspending operation between them. On recreation, re-register the same key for the retained ViewModel state but do not reclaim Launched; queued callbacks can be delivered without a second external intent. Handle a callback delivered synchronously during registration by rechecking claim/state before any launch. Process death creates no restored print job; never reconstruct/relaunch it from registry extras or saved bitmap state. New UUID identities prevent stale restored callbacks from matching a new session job.

Build exactly one ACTION_SEND intent per slip: MIME `image/png`, URI in EXTRA_STREAM and ClipData, FLAG_GRANT_READ_URI_PERMISSION, no write grant or file:// URI. Preserve FileProvider authority and cache path configuration. Try the remembered explicit target; synchronous ActivityNotFoundException or SecurityException may fall back once to the chooser for the same token after clearing the displayed stale target synchronously and scheduling preference removal without awaiting it. Keep claim, attempted explicit launch and same-token chooser fallback contiguous on the main thread; DataStore writes must not suspend this sequence. Reuse the existing asynchronous forget operation with an immediate UI target clear, rather than refactoring preference storage. If persistence removal fails, retain existing logging and still allow the chooser attempt; the job owns launch outcome, not preference durability. Do not treat other exceptions as successful delivery. If both attempts fail, report onPrintLaunchFailed for that token and clear the job through its terminal state; do not retry automatically.

Create chooser PendingIntents explicitly targeting the existing nonexported ChosenComponentReceiver with unique data `snapstone-print-choice:<jobId>:<index>` and FLAG_MUTABLE because the system supplies the chosen component. Do not use one mutable UPDATE_CURRENT PendingIntent shared by different tokens. The receiver persists only the user's selected component and never calls coordinator advancement. Existing preference race hardening beyond isolating PendingIntent identity is outside this PR; choosing an app remains a preference even if the user later stops remaining slips. Check remembered-target launch failures dynamically; activity-presence lookup is only an optimization, not proof it accepts a PNG.

When AwaitingNext, replace the print action with text `Continue with slip N of M?` and buttons exactly `Send next slip` / `Stop`. Do not say the previous slip printed successfully. Both buttons carry the displayed jobId. Back/dismiss of any continuation dialog, if a dialog is used, maps to Stop; prefer an inline PrintBar prompt to survive activity recreation without separate transient flags. In Preparing show `Preparing slips…` and Stop. In Launched show `Sharing slip N of M…`; if Stop is exposed while the app is visible, explain `Stopping after this share returns…` in Stopping and keep new print actions disabled until callback. Completed may say `Sharing finished`; Cancelled clears the active prompt; Failed uses its error text. Never resend the previous slip as the continuation action.

## Bounded implementation passes

### Pass A: coordinator and checked exporter

Hand one executor the pure state types/coordinator plus exporter and their tests. Keep the feature branch open and unmerged until Pass B wires the app. Write coordinator tests in `app/src/test/java/com/example/snapstoneprinter/data/print/PrintJobCoordinatorTest.kt`; write bitmap/filesystem tests in `app/src/androidTest/java/com/example/snapstoneprinter/data/print/AndroidSlipExporterTest.kt`. Run targeted JVM tests locally and hosted exporter instrumentation. No screen/launch changes in this pass.

### Pass B: integration, continuation UI, and callback fixture

Begin this one bounded integration pass only after root confirms the history PR has merged and its required/hosted checks passed. Fetch fork master and refresh the feature branch; compare the merged history interfaces against measured D8 and preserve newer history/mode/statistics work. If APIs differ, reconcile this contract before implementation. Use the measured D10 Pass A API and this full document; no helper-only merge or stacking on an unmerged history branch. Wire ViewModel/host/PrintBar and remove old dispatch paths; add tests in `ui/PrintDispatchHostTest.kt` and `ui/PrintJobIntegrationTest.kt` under androidTest. Use a controllable fake registry for ordering/recreation and a test receiver for actual external-URI reading. No dependency upgrades, renderer changes, or general target-store refactor.

Use a fake ActivityResultRegistry that records request code, contract and input in onLaunch, and delivers results through its real dispatchResult API. Exercise onSaveInstanceState/onRestoreInstanceState with actual Bundles, unregistration and re-registration under the specified stable token key; explicitly queue a result before re-registering. Do not simulate restoration by directly calling the ViewModel callback alone. Keep fake instances scoped to each test.

For the external fixture, display its receipt in the test receiver activity UI: action, MIME, URI/ClipData agreement, read-grant bit, decoded width/height and deterministic pixel hash. Include `Return OK` and `Return Cancel` buttons that set the corresponding activity result and finish. Drive those buttons and read receipt text through instrumentation UiAutomation accessibility nodes, using bounded waits; do not use a shared cross-process singleton, mutable global receipt holder, production-only command extras or a receiver network endpoint. Put test input/expected hashes in the test fixture, not production code. Scope any fake-registry injection to the host's explicit registry parameter; use a test-owned host/activity instance for lifecycle tests instead of a global production factory override. Record which tests use fake registry and which cross the real test-APK boundary.

Add the receiver activity only in `app/src/androidTest/AndroidManifest.xml` with `exported=true`, an explicit component under the test application, and narrowly declared image/png ACTION_SEND test filter, so the production app can exercise delivery across the test-APK boundary. Its test code reads the URI using its own ContentResolver, decodes the PNG, records dimensions/hash/index, and finishes only under test control. It must not be added to the main/release manifest. Verify the release merged manifest omits the fixture and review the test-only exported component explicitly. Do not add a debug-app command endpoint or change production network exposure to seed tests.

## Named tests and assertions

- `startReservesBeforeExport`: two synchronous starts yield Started/Busy and one exporter invocation.
- `badExportCannotBecomeReady`: wrong URI count, empty list, or blank URI for the active Preparing ID produce Failed; a stale job export returns false unchanged.
- `handledInvalidBatchIsDiscarded`: a matching-ID owned batch with invalid URI count/blank entry is handled true into Failed, then discarded once and never stored/launched; preserve neighboring and previously shared batches.
- `mismatchedExportIdentityCannotBeRelabeled`: a fake exporter returns another job's ID; fail only the expected currently preparing job, admit no URIs, and do not discard the foreign payload. If the expected job is already stale, leave the new active job unchanged.
- `invalidFactoryIdentityRejectedBeforePreparation`: blank, malformed, noncanonical, and reused UUID factory results fail before state change; fixed valid UUIDs pass both coordinator and exporter identity validation.
- `claimOnce`: duplicate claim for the same token returns null after the first and launches once.
- `returnWaitsForChoice`: first return for a multi-slip job produces AwaitingNext and zero next-slip launches until Send next slip.
- `stopDoesNotSendRemainder`: Stop from AwaitingNext produces Cancelled; delayed sendNext/returned events remain rejected.
- `allResultCodesUseSameDecision`: RESULT_OK and RESULT_CANCELED both produce the same remaining-slip prompt, with no success-of-print claim.
- `chooserDismissDoesNotContinue`: dismiss chooser, return, assert no next URI sent until the explicit choice; Stop sends nothing further.
- `lastReturnCompletesSharing`: last callback terminates without another prompt; no assertion about physical output.
- `staleAndDuplicateCallbacksDoNotAdvance`: deliver an old token while a newer job/next index is Ready/Launched; new state and launch count unchanged.
- `stoppingDrainsOutstandingCallback`: Stop while Launched keeps new starts Busy, drains only matching callback, then allows a fresh UUID job.
- `canceledExportCompletesLate`: controlled exporter ignores cancellation until completion; its batch is discarded as unshared and cannot replace a later job.
- `compressFalseAndPartialWriteFail`: encoder false and an injected failure after one file both fail export, remove only the owned incomplete files, and preserve a neighboring sentinel file/batch.
- `viewModelClearRetainsCompletedCacheWithoutDetachedCleanup`: clear the test ViewModel while Ready and assert no discard request is made for its completed batch; clear while Preparing and assert export cancellation reaches the exporter; after claim, assert no cleanup/revocation is requested. Treat OS cache eviction and abrupt process termination as outside deterministic cleanup assertions.
- `successfulBatchRetainedAfterStop`: after claimLaunch, Stop/return do not delete any batch file; receiver can still read its URI.
- `historyAdmissionReturnsAcceptedOnly`: with export held, first tryReprint returns true and exposes Preparing immediately; a second tryReprint returns false with no second export. Busy at every coordinator busy state, missing/evicted ID, same-current-ID pending tone, and empty start return false. Assert HistorySheet stays open with its local error on false and closes on true.
- `currentPrintRejectsPendingToneWithoutExport`: request tone, call printCurrentCard and tryReprint(current entry) before and during debounce/render, assert zero exporter calls. Complete render, print, and assert captured references are exactly the newly completed visible slips and applied tone's snapshot.
- `staleHistoryObjectExportsLatestCompletedSlips`: hold an old HistoryEntry, complete a tone update for its ID, invoke tryReprint(old entry), and assert the capturing exporter receives the current matching history entry's exact bitmap references in order, not old entry bitmaps. An evicted ID starts no export. A different completed history ID remains printable while current generation or tone work waits.
- `failedToneExportsRetainedCompletedSnapshot`: after a controlled render failure, assert controls equal appliedTone, renderError remains nonfatal, tryReprint/printCurrentCard can accept the completed candidate, and captured bitmap references equal the preserved preview/history rather than any failed requested-tone output. Use separate fixture jobs or drain the first before testing the second entrypoint.
- `snapshotDoesNotChangeDuringExport`: tone/history changes while export is suspended do not alter the originally captured slip list/bitmap identities.
- `activityRecreationDoesNotRelaunch`: registry save/restore and ActivityScenario recreation during outstanding share preserve key/token, accept the callback once, and produce one launch total. Also test callback already queued before re-registration.
- `newProcessDoesNotReplay`: fresh coordinator with restored registry bundle has no launch; a new UUID job ignores the old key's result.
- `rememberedTargetFallback`: missing/rejected target triggers one chooser for the same slip and clears stale preference; failed chooser terminates visibly.
- `externalReceiverReadsCorrectPng`: test APK receives ACTION_SEND image/png, matching EXTRA_STREAM/ClipData, read grant, expected decoded pixels, and slip index order; no second invocation before Send next slip.

Run the full existing JVM/lint/debug build plus hosted connectedDebugAndroidTest after integration; require actual passing reports and APK. Run independent general and separate security reviews before push/merge; security scope includes URI grants, explicit mutable PendingIntent identity, test-only receiver manifest exposure, and owned cleanup paths. Correct only concrete must-fix findings with another executor/reviewer cycle.

## Before/after appendix

Execute this query on the step's actual branch before application changes and after Pass B; record outputs here alongside regression red/green commands, revision/time, and hosted report URLs:

```bash
git rev-parse HEAD
rg -n 'fun dispatchSlips|fun onSlipDispatched|fun cancelDispatch|System.currentTimeMillis|\.compress\(|rememberLauncherForActivityResult|chosenComponentSender' app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt
rg --files --hidden --glob '!.git/' app/src/main/java/com/example/snapstoneprinter/data/print app/src/androidTest
git diff --check
```

Unverified: the executor's pre-change/post-change query and red/green results are not yet run. The planner's D1-D4 commands above inspected baseline source before this design; do not stamp those commands as the later executor's observation.

## Unverified and invariants

D6 is inherited user choice; D7 inherited authorization/emulator routing. D8 is measured source behavior at an unmerged prerequisite head; merge status/hosted results remain unverified here and are an explicit Pass B start gate. D10 measures the existing helper implementation, not Android runtime acceptance. Registry lifecycle, chooser callback order, and separate test-APK URI delivery remain unverified until the named tests execute. Do not infer those outcomes from coordinator tests alone. Preserve single-module architecture, latest-preview history contract, minSdk36, rendered pixels/384 width, one PNG per slip, FileProvider nonexported status, and physical-printer exclusion. Do not add standing undated attention items. Keep one feature PR with reviewable final behavior, refreshed against merged fork master and self-merged only after the run's required checks and reviews.


### Pass B prerequisite refresh — 2026-09-15T18:08:57Z

Executed before Pass B application changes:

```bash
git rev-parse HEAD
git -C ../history-state rev-parse HEAD
git -C ../history-state show dc751e0:app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt | sed -n '370,417p'
git -C ../history-state show dc751e0:app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt | sed -n '266,289p'
```

Observed HEADs were `e4a8ea55cb2c20e33e4f9f584fe65a1d876a3a8e` and `dc751e064abe5eace1f57e61f21d18195a40cc9d`. The ViewModel output contains the following admission bodies (cache-export body omitted here; keep the original generic pre/post query for the executor evidence):

```kotlin
fun printCurrentCard() {
    val state = _uiState.value
    if (!state.canPrint || state.dispatch != null) return
    dispatchSlips(state.slips, state.currentCard?.effectiveName ?: "Proxy")
}
fun reprint(entry: HistoryEntry) { tryReprint(entry) }
internal fun resolveHistoryEntry(id: Long): HistoryEntry? = _uiState.value.history.firstOrNull { it.id == id }
fun tryReprint(entry: HistoryEntry): Boolean {
    val state = _uiState.value
    if (state.dispatch != null ||
        (entry.id == state.currentPullId && (state.isLoading || state.isRedithering))) return false
    val current = resolveHistoryEntry(entry.id) ?: return false
    dispatchSlips(current.slips, current.cardName)
    return true
}
```

The screen output calls tryReprint and sets showHistorySheet false only inside its true branch; its false branch sets `This item is unavailable or busy. Wait for current work to finish and try again.` This observation motivates preserving the Boolean while tightening true to synchronous Started admission. After integration, repeat these source queries against the merged baseline and implemented HEAD, record full outputs plus targeted export-capture tests in the Pass B evidence, and verify no old SlipDispatch/parameterless advancement/wall-clock export path remains. This after phase is unexecuted.
