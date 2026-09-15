# History and render ownership: Step 4 executor contract

Created: 2026-09-15. State: notstarted (contract prepared; implementation evidence belongs in the executor's separate record).

## Premises

- **Measured H1:** `git rev-parse HEAD` in this worktree returned `318e836cdc2f9d810595e52f5d21d19ebea784d2` at 2026-09-15T17:51:22Z. This supersedes the inherited proposed base `6f82f78` for this contract.
- **Measured H2:** `sed -n '180,240p' app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt` at H1 shows redither becomes busy after its delay, catches `Exception`, and replaces current slips without history. The same file's `canPrint` getter excludes loading only, and `reprint(entry)` dispatches the passed object's slips without lookup.
- **Measured H3:** `sed -n '635,702p' app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt` at H1 shows loading, then `error`, then slips are mutually exclusive preview branches. Putting a retained-preview failure in `error` therefore selects the error branch.
- **Measured H4:** `sed -n '414,465p' app/src/main/java/com/example/snapstoneprinter/ui/ProxyPanels.kt` at H1 shows history callbacks return Unit and thumbnail caching uses only `entry.id`. `rg -n 'prepareArt|coroutines.test|coroutines-test' app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt app/build.gradle.kts gradle/libs.versions.toml` finds production `prepareArt` at line 329 and the existing coroutine-test alias, currently in the JVM test configuration only.
- **Measured H5:** `rg --files --hidden -g AGENTS.md -g CLAUDE.md -g '!.git/' .` at H1 returned no repository instruction files; user-supplied instructions and the planner role remain applicable.
- **Inherited H6:** the main thread authorized a bounded Step 4 execution with existing-version coroutine-test support for Android tests, hosted emulator acceptance, and export-capture tests deferred to Step 5. The main thread is coordinating merges and separate dispatch implementation.

## Architecture and pass boundary

Implement one history/render correctness feature PR. Preserve the screen, AndroidViewModel, session-only bitmap history, existing repositories, renderer algorithm and dispatch transport. Separate asynchronous fetching from asynchronous rendering with immutable input bundles and explicit generation/revision ownership. Cancellation is resource management; token equality is the state-write authority.

Reject a single fatal error channel for retained-preview failures because H3 would hide the preserved result. Restore controls to the last applied tone on a failed adjustment, so controls and the printable snapshot agree. Reject restarting network work for tone input: the tone belongs to rendering of downloaded source art. Keep future dispatch adapters independent of these ownership tokens.

Allowed production files are `ui/ProxyGeneratorViewModel.kt`, `ui/ProxyGeneratorScreen.kt`, `ui/ProxyPanels.kt`, `image/ArtDownloader.kt`, and new `image/ArtSource.kt` / `image/SlipRenderer.kt`, under `app/src/main/java/com/example/snapstoneprinter/`. Permit `app/build.gradle.kts` only to reuse `libs.kotlinx.coroutines.test` under `androidTestImplementation`; no dependency-version changes. Add state and UI regression tests under `app/src/androidTest/java/com/example/snapstoneprinter/`. Preserve unrelated executor files and record evidence separately.

## Exact interfaces and state

Use `interface ArtSource { suspend fun fetch(url: String): ArtResult }`, implemented by `ArtDownloader`. Use `interface SlipRenderer { suspend fun render(plan: List<SlipContent>, art: List<Bitmap?>, contrast: Float, brightness: Float): List<PrintSlip> }`. Put a default `AndroidSlipRenderer` beside that interface; it owns `withContext(Dispatchers.Default)`, `ImageProcessor.prepareArt`, and existing slip composition. Preserve input bitmap ownership: neither implementation mutates or recycles source or already-published bitmaps.

Append optional constructor dependencies `artSource: ArtSource = ArtDownloader(application)` and `slipRenderer: SlipRenderer = AndroidSlipRenderer()` after the current application/repository arguments. Do not inject or retain an Activity context.

Add `data class ToneSettings(val contrast: Float, val brightness: Float)` in the ViewModel source. Append defaulted UI-state fields `currentPullId: Long? = null`, `appliedTone: ToneSettings? = null`, and `renderError: String? = null`. Existing contrast/brightness remain requested control values; appliedTone identifies the completed slips. Null pull/applied tone means no completed current result, never a valid completed generation.

Maintain private monotonically increasing session generation IDs, a tone revision, a generation Job, a render Job, and an immutable downloaded bundle containing generation ID, card, plan, source-art list and art warning. IDs must not use wall-clock milliseconds; allocate before starting asynchronous work. Never reuse a generation ID in a session. Keep the existing history cap and order.

## Generation and render transition contract

- On a new pull, synchronously invalidate prior generation/revision, cancel both jobs, discard the prior input bundle, clear current card/slips/pull ID/applied tone and all current-generation errors, set loading true and redithering false. Preserve requested tone and history. Capture fetch parameters for that request before suspension.
- Fetch card and source art for that generation. Keep missing/failed art's existing text-only fallback and warning. Publish the downloaded bundle only if its generation is still active. Start a separately owned initial render using the **latest requested tone and revision at bundle acceptance**, not tone captured before network work.
- Tone input updates clamped requested values and revision synchronously, clears renderError, and cancels the older render. During network loading with no bundle it does not restart fetch or start a renderer. When a bundle exists it schedules a render with the existing debounce delay. If there is no completed current preview, keep loading true and redithering false; if there is a completed current preview, set redithering true immediately, including during debounce. Reset changes both values as one request.
- Each render captures immutable bundle/tone/generation/revision before suspension. Every success or failure must match both active generation and revision before changing any state, including flags. Rethrow CancellationException before ordinary exception handling. A late canceled operation that nevertheless returns must be ignored by the ownership check.
- Initial render success atomically publishes current card/slips/pull ID/applied tone, clears loading/redithering/renderError/fatal error, and prepends exactly one history entry under that generation ID. Require a nonempty rendered list with one result per planned slip; treat malformed renderer output as render failure before publication. If tone changed while that initial render ran, its old completion cannot publish or clear loading; only the latest revision may make the initial commit.
- Later render success atomically replaces current slips/applied tone and the matching history entry's slips, preserving ID, position, card metadata and unrelated entries. Never create a second history entry for a tone change. Replace list/bitmap references; do not mutate a published snapshot.
- A current redither failure with a completed preview retains its slips/history/pull ID, restores contrast/brightness from appliedTone, clears redithering, leaves fatal error null, and sets renderError to `Could not apply tone changes. Previous preview and settings kept.` It becomes printable again. Do not claim the failed requested tone completed.
- A current fetch or initial-render failure has no completed preview: preserve history, clear loading/redithering and the bundle, leave current pull/applied tone null, and use the existing fatal generation error presentation. Subsequent tone input retains requested settings but does not retry this failed fetch/render implicitly; the next generation action retries. A failed old request never replaces another generation's error or flags.
- Clear renderError on each new generation, tone request, successful render, and explicit clearError. Keep artError independent from tone failure.

## UI and history dispatch integration

Render renderError nonfatally inside the slips branch of `ProxyPreview`, above the preview/pager, and inside shared `InlineToneControls` so the message is visible while its sheet is open. Preserve the slips beneath the notice; keep fatal generation error behavior unchanged. Factor a shared small notice composable if useful. Test the notice and preview together; do not route renderError into `error`.

Set `canPrint = !isLoading && !isRedithering && slips.isNotEmpty()`. Enforce it inside `printCurrentCard()` before dispatch. Keep the existing dispatch guard and leave reserve-before-export race repair to Step 5.

Preserve `fun reprint(entry: HistoryEntry): Unit` as a delegating compatibility callback. Add `fun tryReprint(entry: HistoryEntry): Boolean`; true means a current, eligible history snapshot was passed to dispatch, not export success or physical printing. False means no dispatch was started because the ID is absent, dispatch is already busy, or the ID equals currentPullId while loading/redithering. Resolve by ID against current state inside this method; never use the passed object's bitmap list. Different completed history IDs remain eligible while the current pull loads or redithers. Same current ID is rejected throughout tone debounce/render. An evicted/missing ID is never printed.

The history-screen callback calls tryReprint synchronously. Close the sheet only for true. For false keep it open and show local text `This item is unavailable or busy. Wait for current work to finish and try again.` Add optional `error: String? = null` to HistorySheet after existing parameters; own the error in the screen's open-sheet state and clear it for a new attempt or dismissal. Do not put this action error in fatal state. Step 5 must preserve these eligibility/lookup rules while moving accepted work behind its coordinator; Unit entrypoints remain compatible with its supplemental contract.

Fix HistoryRow thumbnail derivation to depend on the current first bitmap reference (or compute asImageBitmap directly), because H4's ID-only remember would retain old artwork after same-ID history replacement. Preserve stable row identity by history ID.

## Tests first and acceptance

Use controlled suspended ArtSource/SlipRenderer fakes with actual tiny Android Bitmaps. Add androidTest coroutine-test using the existing catalog version; install/reset a test Main dispatcher only within isolated ViewModel tests, never across Compose tests. Dispatchers.setMain must be restored in finally/teardown. Tests must execute real ViewModel methods and inspect emitted state, rather than only a parallel test state machine.

Required named cases and assertions:

- `toneChangedDuringFetchUsesLatestWithoutRefetch`: hold art download, change both controls, release; assert one fetch and renderer's exact latest tone, then one completed history entry.
- `toneChangedDuringInitialRenderRejectsOldCompletion`: hold initial render, change tone, deliver old output despite cancellation; assert still loading, no current/history commit, then newest completion commits exactly once with applied tone.
- `newGenerationRejectsLateFetchAndRender`: complete old work after newer generation and assert card, art, history, errors and flags belong to the new generation only.
- `debounceImmediatelyBlocksCurrentPrinting`: successful preview then slider change, before advancing delay assert canPrint false and same-current-ID tryReprint false; no dispatch mutation. Different completed history ID remains eligible by the ID eligibility branch.
- `reditherUpdatesMatchingHistoryWithoutReordering`: assert same ID/position and new bitmap references only in the matching entry; unrelated entries unchanged. A stale passed HistoryEntry resolves the latest completed entry by ID. A narrowly scoped internal `resolveHistoryEntry(id: Long): HistoryEntry?` may expose that exact production lookup for pre-export-seam assertions.
- `failedTonePreservesPreviewHistoryAndAppliedControls`: fake renderer throws; assert original slips/history, restored controls/appliedTone, error null, renderError exact message, canPrint true. A new request clears the notice and becomes busy immediately.
- `oldFailureCannotClearNewBusyFlags`: old canceled renderer throws/returns while newer work waits; assert no stale flag/error publication.
- `failedGenerationCannotReuseOldArt`: fail new art and verify text-only render receives null at the correct position, never prior source; fail initial renderer and verify no current/history publication and no implicit tone-only retry.
- `missingHistoryDoesNotDispatch`: evict an ID through normal history-cap behavior, call tryReprint with old object, assert false and dispatch unchanged. Assert compatibility reprint also rejects it.
- `renderFailureNoticeKeepsPreviewVisible`: render state with slips/renderError and assert both bitmap preview and notice; separately assert fatal error presentation. Cover shared tone controls notice and HistorySheet local error.
- `sameHistoryIdUpdatesThumbnail`: replace entry slips under same ID in Compose state and assert the row uses the replacement bitmap (a tagged image or narrowly scoped production bitmap parameter can support deterministic inspection).

Record targeted red failures before production changes. Run `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`; any nonzero exit is failure. Hosted `:app:connectedDebugAndroidTest` must pass the new state/UI cases before merge. Do not certify emulator assertions from compilation alone. Export input capture and external sharing remain Step 5 acceptance, not a claim made by these tests.

## Invariants and unverified scope

Preserve minSdk, printed dimensions, tone math, source bitmap ownership, existing art fallback, session history cap, and unrelated card/dispatch behavior. No new live network tests, persistent bitmap storage, upstream writes, dependency upgrades, standing todo entries, or merges in this executor pass. Root owns refresh/review/push/merge coordination. H6 is inherited authorization and test-scope evidence; physical printing and future dispatch callback/export correctness remain unverified and outside this pass.

## Before/after query appendix

Before query, executed at H1 on 2026-09-15T17:51:22Z:

```sh
git rev-parse HEAD
rg -n 'fun reprint|fun dispatchSlips|canPrint|fun HistorySheet|onReprint|fun ProxyPreview|fun InlineToneControls' app/src/main -g '*.kt'
rg -n 'prepareArt|coroutines.test|coroutines-test' app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt app/build.gradle.kts gradle/libs.versions.toml
```

Observed relevant output: HEAD `318e836cdc2f9d810595e52f5d21d19ebea784d2`; ViewModel canPrint line111, reprint line353, dispatchSlips line357; Panels InlineToneControls line114, HistorySheet line414, Unit callback line416; screen onReprint line269, print enablement line561, ProxyPreview line612. The dependency query returned ViewModel prepareArt line329, testImplementation line100 and catalog coroutine-test alias line66. H2–H4 record the inspected bodies rather than inferring behavior from these symbol matches.

After query: execute the same commands against the implemented HEAD, append their output and timestamp to the executor record `2026-09-15-history-render-state.md`, and additionally record the exact commands/results for each red/green test run. Record `git diff --check` and final changed-file list there. The symbol query certifies scope only; named state/UI assertions certify behavior. This after phase is unexecuted in this planner-only contract.
