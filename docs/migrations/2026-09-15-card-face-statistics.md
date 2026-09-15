# Card face statistics and printed fields

Created: 2026-09-15. State: started.

## Premises

- **Measured:** `git rev-parse HEAD` returned `2dafc940e7c5a2505c653dc211e2ca25ce0a9dfd` and `git status --short` returned no changes before Step 2 execution on 2026-09-15.
- **Measured:** `git diff bf746e5660eb3ada8ee112f4d0856e864251b89b -- app/src/main/java/com/example/snapstoneprinter/data/model/ScryfallCard.kt app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt` returned no output before edits; these source files preserved the inherited planner baseline.
- **Measured:** reading those source files at this base showed no loyalty/defense fields and a single-slip primary block populated from aggregate-first effective resolvers. `CardFixturePlanningTest` red results below demonstrate the resulting split/adventure field mismatch.
- **Measured:** the public exact-name lookups recorded in [the fixture README](../../app/src/test/resources/scryfall/README.md) completed at 2026-09-15T17:32:32Z; retain their response IDs when changing fixtures.
- **Inherited:** the main thread authorized Step 2 implementation and requires hosted renderer tests before merge; it owns branch refresh, review, PR creation, and merge coordination.

## Decision and contract

Separate printed face fields from the combined card identity used by the UI/history. Keep the existing structural slip-count decision and shared-image resolution. Preserve legacy effective resolvers for cards with fewer than two faces; for a primary face in a multiface card, use only its own fields, with top-level name as the sole missing/blank-name fallback. Preserve unnamed-secondary omission and secondary order.

Append nullable string loyalty/defense properties with null defaults to API card/face and slip/secondary models. Use strings to preserve printed zeroes and variable symbols. Format a complete power/toughness pair first, then nonblank loyalty and defense, trimming surrounding whitespace; omit absent values. Measure and draw the same formatted layout for each primary and secondary face. Keep art processing, output width, antialiased text, and trailing feed behavior outside this change.

## Before and after query

Run this source query on each revision and record the output below:

```bash
git rev-parse HEAD
rg -n 'val loyalty:|val defense:|val effectiveLoyalty:|val effectiveDefense:|FaceStatsFormatter.format' \
  app/src/main/java/com/example/snapstoneprinter/data/model/ScryfallCard.kt \
  app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt \
  app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt
```

The baseline inspection above returned HEAD `2dafc940e7c5a2505c653dc211e2ca25ce0a9dfd` with none of these source symbols; reproduce the pre-change inspection against that commit, rather than treating a later checkout as the baseline.

## Regression evidence

Run the commands below with the installed JDK25 and Android SDK paths in JAVA_HOME and ANDROID_HOME.

The first fixture-only red command on 2026-09-15 ran before application edits and exited 1:

```bash
./gradlew --no-daemon :app:testDebugUnitTest \
  --tests com.example.snapstoneprinter.image.CardFixturePlanningTest --console=plain
```

```text
CardFixturePlanningTest > adventurePrimaryUsesCreatureFields FAILED
CardFixturePlanningTest > splitPrimaryDoesNotUseCombinedCostOrType FAILED
2 tests completed, 2 failed
BUILD FAILED in 15s
```

The expanded regression command ran after adding nullable field/API scaffolding, before implementing planner propagation or formatting:

```bash
./gradlew --no-daemon :app:testDebugUnitTest \
  --tests com.example.snapstoneprinter.image.CardFixturePlanningTest \
  --tests com.example.snapstoneprinter.image.FaceStatsFormatterTest --console=plain
```

```text
14 tests completed, 10 failed
BUILD FAILED in 13s
```

The expanded command subsequently exited 0 after formatter and planner implementation: XML timestamps `2026-09-15T17:36:44.699Z` and `2026-09-15T17:36:44.765Z` report 14 tests, no failures/errors/skips, and Gradle reported `BUILD SUCCESSFUL in 9s`.

## Remaining verification

**Unverified:** successful hosted execution after renderer integration remains required; the red result is recorded below. Do not equate compilation or JVM checks with executing Android Canvas rendering. Leave this change unmerged until the hosted stat tests pass. Physical output remains outside acceptance.

## After — model/planner stage, 2026-09-15

The source query after model/planner changes and before renderer integration returned:

```text
2dafc940e7c5a2505c653dc211e2ca25ce0a9dfd
app/src/main/java/com/example/snapstoneprinter/data/model/ScryfallCard.kt:29:    val loyalty: String? = null,
app/src/main/java/com/example/snapstoneprinter/data/model/ScryfallCard.kt:30:    val defense: String? = null
app/src/main/java/com/example/snapstoneprinter/data/model/ScryfallCard.kt:44:    val loyalty: String? = null,
app/src/main/java/com/example/snapstoneprinter/data/model/ScryfallCard.kt:45:    val defense: String? = null
app/src/main/java/com/example/snapstoneprinter/data/model/ScryfallCard.kt:79:    val effectiveLoyalty: String?
app/src/main/java/com/example/snapstoneprinter/data/model/ScryfallCard.kt:82:    val effectiveDefense: String?
app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt:39:    val loyalty: String? = null,
app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt:40:    val defense: String? = null
app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt:58:    val loyalty: String? = null,
app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt:59:    val defense: String? = null
```

**Inherited execution boundary, now satisfied by the red report below:** the main thread instructed the executor on 2026-09-15 to defer renderer implementation until the hosted stats regressions ran red.

The local pre-renderer acceptance command on 2026-09-15 exited 0:

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleDebugAndroidTest --console=plain
```

```text
BUILD SUCCESSFUL in 28s
Unit XML timestamps: 2026-09-15T17:39:16.190Z through 2026-09-15T17:39:16.476Z
Unit XML totals: tests=90 failures=0 errors=0 skipped=0
Lint XML: errors/fatal=0 warnings=57
app/build/outputs/apk/debug/app-debug.apk: 66870422 bytes
app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk: 2355432 bytes
```

These observations came from parsing the unit/lint XML reports and inspecting the generated APKs at the execution base plus staged changes. At that pre-renderer observation, `git diff HEAD -- app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt` returned no output. `git diff --cached --check` and `git diff --check HEAD` passed after staging the evidence record on 2026-09-15. Instrumentation APK assembly certifies compilation and packaging only; use the hosted observations below for renderer execution.

## Hosted renderer red and resumed implementation

The executor inspected the downloaded XML artifact from [Android test run 35003772735](https://github.com/veyloris/SnapstonePrinter/actions/runs/35003772735) before editing the renderer. Its timestamp `2026-09-15T17:54:41` records:

```text
Overall: tests=42 failures=3 errors=0 skipped=0
CardStatsRenderingTest: tests=4 failures=3 errors=0 skipped=0
absentStatsHaveNoAllocatedOrDrawnLine: passed
primaryStatsAllocateAndDrawEachKind: failed
multiSlipStatsStayOnOwningFace: failed
secondaryStatsAllocateAndDrawEachKind: failed
Each failure: java.lang.AssertionError: A stat line must receive vertical space
```

At resumption, `git rev-parse HEAD` returned `1d49f024e76f29d4c09286655def561fe0645ada` and `git status --short` returned no changes. The repeatable source query returned the same model/planner fields recorded above and no `FaceStatsFormatter.format` call in ImageProcessor. The merged renderer's `prepareArt`, prepared-width check, and native art drawing were inspected before edits; keep those outside the stats diff.

The renderer change supplies primary and secondary loyalty/defense to the formatter and uses its newline-joined output in the same StaticLayout for measuring and drawing. `CardStatsRenderingTest` remains unchanged from the observed failing run.

After that change, the repeatable source query returned:

```text
1d49f024e76f29d4c09286655def561fe0645ada
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:395:        val statsText = FaceStatsFormatter.format(power, toughness, loyalty, defense).joinToString("\n")
app/src/main/java/com/example/snapstoneprinter/data/model/ScryfallCard.kt:29:    val loyalty: String? = null,
app/src/main/java/com/example/snapstoneprinter/data/model/ScryfallCard.kt:30:    val defense: String? = null
app/src/main/java/com/example/snapstoneprinter/data/model/ScryfallCard.kt:44:    val loyalty: String? = null,
app/src/main/java/com/example/snapstoneprinter/data/model/ScryfallCard.kt:45:    val defense: String? = null
app/src/main/java/com/example/snapstoneprinter/data/model/ScryfallCard.kt:79:    val effectiveLoyalty: String?
app/src/main/java/com/example/snapstoneprinter/data/model/ScryfallCard.kt:82:    val effectiveDefense: String?
app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt:39:    val loyalty: String? = null,
app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt:40:    val defense: String? = null
app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt:58:    val loyalty: String? = null,
app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt:59:    val defense: String? = null
```

The same full local JVM/lint/debug/instrumentation-assembly command exited 0 after renderer integration:

```text
BUILD SUCCESSFUL in 23s
Unit XML timestamps: 2026-09-15T17:56:27.269Z through 2026-09-15T17:56:27.493Z
Unit XML totals: tests=95 failures=0 errors=0 skipped=0
Lint XML: errors/fatal=0 warnings=58
app/build/outputs/apk/debug/app-debug.apk: 67217788 bytes
app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk: 2388077 bytes
```

`git diff --cached --check` and `git diff --check HEAD` passed after staging the renderer and updated evidence on 2026-09-15. These local results establish JVM behavior and Android test compilation; the successful hosted renderer rerun and independent review remain outstanding.
