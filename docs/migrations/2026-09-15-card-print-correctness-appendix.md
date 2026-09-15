# Card and print correctness: validation appendix

Created: 2026-09-15. State: started.

## Repeatable source query

Run before edits in each task checkout and again after the relevant implementation; record its actual output plus `git diff --check` and regression/build results. `rg` exit 1 means no matches; do not confuse that with a failed application test or successful build.

```bash
git rev-parse HEAD
rg -n 'if \(isFunny\)|effectiveName|effectiveManaCost|FILTER_BITMAP_FLAG|fun scheduleRedither|fun dispatchSlips|fun onSlipDispatched|var appMode by remember' app/src/main/java/com/example/snapstoneprinter/data/api/ScryfallQueryBuilder.kt app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt
git diff bf746e5660eb3ada8ee112f4d0856e864251b89b -- app/src
```

## Before — measured 2026-09-15, planner baseline

```text
bf746e5660eb3ada8ee112f4d0856e864251b89b
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:91:    var appMode by remember { mutableStateOf(AppMode.SNAPSTONE_WIELDER) }
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:593:            text = card.effectiveName,
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:907:                text = card.effectiveName,
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:198:    private fun scheduleRedither() {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:302:                                cardName = card.effectiveName,
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:349:        dispatchSlips(state.slips, state.currentCard?.effectiveName ?: "Proxy")
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:357:    private fun dispatchSlips(slips: List<PrintSlip>, label: String) {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:387:    fun onSlipDispatched() {
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:283:                c.drawBitmap(ditheredArt, srcRect, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
app/src/main/java/com/example/snapstoneprinter/data/api/ScryfallQueryBuilder.kt:32:        if (isFunny) terms += FUNNY
app/src/main/java/com/example/snapstoneprinter/data/api/ScryfallQueryBuilder.kt:49:        if (isFunny) terms += FUNNY
app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt:159:        name = card.effectiveName,
app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt:160:        manaCost = card.effectiveManaCost,
[git diff: no output]
```

## Live data observations — measured 2026-09-15

The planner ran this lookup separately for `Fire // Ice`, `Jace, the Mind Sculptor`, and `Invasion of Zendikar`, writing responses below `/tmp/snapstone-review-fixtures/`:

```bash
curl --fail --silent --show-error --get https://api.scryfall.com/cards/named \
  --data-urlencode 'exact=Fire // Ice' \
  -H 'User-Agent: SnapstoneReview/1.0' -H 'Accept: application/json' \
  -o /tmp/snapstone-review-fixtures/fire-ice.json
```

Node JSON field projection returned:

```json
{"file":"fire-ice.json","id":"18303862-4726-4136-814f-157aa7006579","name":"Fire // Ice","mana_cost":"{1}{R} // {1}{U}","type_line":"Instant // Instant","faces":[{"name":"Fire","mana_cost":"{1}{R}","type_line":"Instant"},{"name":"Ice","mana_cost":"{1}{U}","type_line":"Instant"}]}
{"file":"invasion-zendikar.json","id":"8fed056f-a8f5-41ec-a7d2-a80a238872d1","name":"Invasion of Zendikar // Awakened Skyclave","type_line":"Battle — Siege // Creature — Elemental","faces":[{"name":"Invasion of Zendikar","mana_cost":"{3}{G}","type_line":"Battle — Siege","defense":"3"},{"name":"Awakened Skyclave","mana_cost":"","type_line":"Creature — Elemental"}]}
{"file":"jace.json","id":"c8817585-0d32-4d56-9142-0d29512e86a9","name":"Jace, the Mind Sculptor","mana_cost":"{2}{U}{U}","type_line":"Legendary Planeswalker — Jace","loyalty":"3"}
```

For query semantics, `curl --fail --silent --show-error --get https://api.scryfall.com/cards/search --data-urlencode 'q=!"Lightning Bolt" is:funny -is:extra' -H 'User-Agent: SnapstoneReview/1.0' -H 'Accept: application/json' -o /tmp/snapstone-review-fixtures/bolt-funny-query.json -w '%{http_code}\n'` returned HTTP 404 and curl exit 22. Replacing `is:funny` with `-is:funny` and the output filename with `bolt-ordinary-query.json` returned HTTP 200 and exit 0. These are live examples; commit fixtures and exact query assertions rather than using live services in tests.

## After and regression evidence

Measured pre-implementation base transfer on 2026-09-15 in the `funny-filter` worktree: `git rev-parse HEAD` returned `ff381c52b32eee95ff11f4f3b95c3eb0b970f0a2`; `git diff bf746e5660eb3ada8ee112f4d0856e864251b89b -- app/src` returned no output. Only the new plan/appendix were untracked at that observation; no application edits preceded it.

Append each step's source-query outputs, failing regression results before fixes, successful results after fixes, hosted instrumentation URLs/reports, independent reviews, and delivery state. Capture each on its actual execution revision; do not inherit the planner baseline commit after merges.

## Step 1 executor observations — 2026-09-15

Before implementation, the repeatable source query returned HEAD `ff381c52b32eee95ff11f4f3b95c3eb0b970f0a2`, the following matches, and no application diff against the planner baseline:

```text
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:91:    var appMode by remember { mutableStateOf(AppMode.SNAPSTONE_WIELDER) }
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:593:            text = card.effectiveName,
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:907:                text = card.effectiveName,
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:198:    private fun scheduleRedither() {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:302:                                cardName = card.effectiveName,
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:349:        dispatchSlips(state.slips, state.currentCard?.effectiveName ?: "Proxy")
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:357:    private fun dispatchSlips(slips: List<PrintSlip>, label: String) {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:387:    fun onSlipDispatched() {
app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt:159:        name = card.effectiveName,
app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt:160:        manaCost = card.effectiveManaCost,
app/src/main/java/com/example/snapstoneprinter/data/api/ScryfallQueryBuilder.kt:32:        if (isFunny) terms += FUNNY
app/src/main/java/com/example/snapstoneprinter/data/api/ScryfallQueryBuilder.kt:49:        if (isFunny) terms += FUNNY
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:283:                c.drawBitmap(ditheredArt, srcRect, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
[git diff: no output]
```

The same targeted command ran before and after the fix, with JAVA_HOME pointing to the installed JDK25 and ANDROID_HOME to the installed Android SDK:

```bash
./gradlew --no-daemon :app:testDebugUnitTest \
  --tests com.example.snapstoneprinter.data.api.ScryfallQueryBuilderTest \
  --tests com.example.snapstoneprinter.data.repository.CardRepositoryTest --console=plain
```

Red run at the execution base plus new tests, before editing the builder or existing repository query expectations, exited 1:

```text
ScryfallQueryBuilderTest > snapstoneOffExcludesFunny FAILED
ScryfallQueryBuilderTest > momirOffExcludesFunny FAILED
ScryfallQueryBuilderTest > momirOnPreservesCreatureAndCmc FAILED
ScryfallQueryBuilderTest > snapstoneOnIncludesOrdinaryAndFunnyPool FAILED
18 tests completed, 4 failed
BUILD FAILED in 17s
```

Each listed failure was `org.junit.ComparisonFailure` from the exact query assertion. After the builder fix and repository expectation updates, the targeted command exited 0:

```text
BUILD SUCCESSFUL in 10s
Query XML timestamp: 2026-09-15T17:26:42.442Z; tests=5 skipped=0 failures=0 errors=0
Repository XML timestamp: 2026-09-15T17:26:42.470Z; tests=13 skipped=0 failures=0 errors=0
```

After staging the new query test for inclusion in Git's diff, the same source query returned the following output; blank diff-context lines are normalized to empty lines for Markdown whitespace hygiene:

```text
ff381c52b32eee95ff11f4f3b95c3eb0b970f0a2
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:91:    var appMode by remember { mutableStateOf(AppMode.SNAPSTONE_WIELDER) }
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:593:            text = card.effectiveName,
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorScreen.kt:907:                text = card.effectiveName,
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:198:    private fun scheduleRedither() {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:302:                                cardName = card.effectiveName,
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:349:        dispatchSlips(state.slips, state.currentCard?.effectiveName ?: "Proxy")
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:357:    private fun dispatchSlips(slips: List<PrintSlip>, label: String) {
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:387:    fun onSlipDispatched() {
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:283:                c.drawBitmap(ditheredArt, srcRect, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt:159:        name = card.effectiveName,
app/src/main/java/com/example/snapstoneprinter/image/SlipPlanner.kt:160:        manaCost = card.effectiveManaCost,
diff --git a/app/src/main/java/com/example/snapstoneprinter/data/api/ScryfallQueryBuilder.kt b/app/src/main/java/com/example/snapstoneprinter/data/api/ScryfallQueryBuilder.kt
index 40f1996..756cd7f 100644
--- a/app/src/main/java/com/example/snapstoneprinter/data/api/ScryfallQueryBuilder.kt
+++ b/app/src/main/java/com/example/snapstoneprinter/data/api/ScryfallQueryBuilder.kt
@@ -29,7 +29,7 @@ object ScryfallQueryBuilder {
      */
     fun build(isFunny: Boolean = false): String {
         val terms = mutableListOf(NON_LAND)
-        if (isFunny) terms += FUNNY
+        if (!isFunny) terms += "-$FUNNY"
         terms += EXCLUDE_EXTRAS
         return terms.joinToString(" ")
     }
@@ -46,7 +46,7 @@ object ScryfallQueryBuilder {
             "cmc must be in $MOMIR_VIG_CMC_RANGE, got $cmc"
         }
         val terms = mutableListOf("cmc=$cmc", CREATURE_ONLY)
-        if (isFunny) terms += FUNNY
+        if (!isFunny) terms += "-$FUNNY"
         terms += EXCLUDE_EXTRAS
         return terms.joinToString(" ")
     }
diff --git a/app/src/test/java/com/example/snapstoneprinter/data/api/ScryfallQueryBuilderTest.kt b/app/src/test/java/com/example/snapstoneprinter/data/api/ScryfallQueryBuilderTest.kt
new file mode 100644
index 0000000..40782c9
--- /dev/null
+++ b/app/src/test/java/com/example/snapstoneprinter/data/api/ScryfallQueryBuilderTest.kt
@@ -0,0 +1,45 @@
+package com.example.snapstoneprinter.data.api
+
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertThrows
+import org.junit.Test
+
+class ScryfallQueryBuilderTest {
+
+    @Test
+    fun snapstoneOffExcludesFunny() {
+        assertEquals("-t:land -is:funny -is:extra", ScryfallQueryBuilder.build(false))
+        assertEquals("-t:land -is:funny -is:extra", ScryfallQueryBuilder.build())
+    }
+
+    @Test
+    fun snapstoneOnIncludesOrdinaryAndFunnyPool() {
+        assertEquals("-t:land -is:extra", ScryfallQueryBuilder.build(true))
+    }
+
+    @Test
+    fun momirOffExcludesFunny() {
+        for (cmc in listOf(0, 3, 16)) {
+            assertEquals("cmc=$cmc t:creature -is:funny -is:extra", ScryfallQueryBuilder.buildMomirVig(cmc, false))
+            assertEquals("cmc=$cmc t:creature -is:funny -is:extra", ScryfallQueryBuilder.buildMomirVig(cmc))
+        }
+    }
+
+    @Test
+    fun momirOnPreservesCreatureAndCmc() {
+        for (cmc in listOf(0, 3, 16)) {
+            assertEquals("cmc=$cmc t:creature -is:extra", ScryfallQueryBuilder.buildMomirVig(cmc, true))
+        }
+    }
+
+    @Test
+    fun invalidCmcRejectsBothToggleStates() {
+        for (cmc in listOf(-1, 17)) {
+            for (isFunny in listOf(false, true)) {
+                assertThrows(IllegalArgumentException::class.java) {
+                    ScryfallQueryBuilder.buildMomirVig(cmc, isFunny)
+                }
+            }
+        }
+    }
+}
diff --git a/app/src/test/java/com/example/snapstoneprinter/data/repository/CardRepositoryTest.kt b/app/src/test/java/com/example/snapstoneprinter/data/repository/CardRepositoryTest.kt
index 200b3fb..f3ba85b 100644
--- a/app/src/test/java/com/example/snapstoneprinter/data/repository/CardRepositoryTest.kt
+++ b/app/src/test/java/com/example/snapstoneprinter/data/repository/CardRepositoryTest.kt
@@ -57,7 +57,7 @@ class CardRepositoryTest {
         val card = repository.getRandomCard(isFunny = false)

         assertEquals("Black Lotus", card.name)
-        assertEquals("-t:land -is:extra", fakeApi.lastQuery)
+        assertEquals("-t:land -is:funny -is:extra", fakeApi.lastQuery)
     }

     @Test
@@ -67,7 +67,7 @@ class CardRepositoryTest {
         val card = repository.getRandomCard(isFunny = true)

         assertEquals("Black Lotus", card.name)
-        assertEquals("-t:land is:funny -is:extra", fakeApi.lastQuery)
+        assertEquals("-t:land -is:extra", fakeApi.lastQuery)
     }

     // ------------------------------------------------- junk layout re-rolling
@@ -156,7 +156,7 @@ class CardRepositoryTest {
         val repository = CardRepository(fakeApi)
         repository.getMomirVigCreature(cmc = 3, isFunny = false)

-        assertEquals("cmc=3 t:creature -is:extra", fakeApi.lastQuery)
+        assertEquals("cmc=3 t:creature -is:funny -is:extra", fakeApi.lastQuery)
     }

     @Test
@@ -165,7 +165,7 @@ class CardRepositoryTest {
         val repository = CardRepository(fakeApi)
         repository.getMomirVigCreature(cmc = 0, isFunny = true)

-        assertEquals("cmc=0 t:creature is:funny -is:extra", fakeApi.lastQuery)
+        assertEquals("cmc=0 t:creature -is:extra", fakeApi.lastQuery)
     }

     @Test
@@ -179,16 +179,19 @@ class CardRepositoryTest {
     }

     @Test
-    fun testGetMomirVigCreature_rejectsCmcOutsideValidRange() = runTest {
+    fun invalidCmcRejectsBeforeApiCall() = runTest {
         val fakeApi = FakeScryfallApiService()
         val repository = CardRepository(fakeApi)

         for (invalidCmc in listOf(-1, 17)) {
-            try {
-                repository.getMomirVigCreature(cmc = invalidCmc)
-                fail("Expected IllegalArgumentException for cmc=$invalidCmc")
-            } catch (e: IllegalArgumentException) {
-                // expected
+            for (isFunny in listOf(false, true)) {
+                try {
+                    repository.getMomirVigCreature(cmc = invalidCmc, isFunny = isFunny)
+                    fail("Expected IllegalArgumentException for cmc=$invalidCmc")
+                } catch (e: IllegalArgumentException) {
+                    assertEquals(0, fakeApi.callCount)
+                    assertEquals("UNINITIALIZED", fakeApi.lastQuery)
+                }
             }
         }
     }
```

The full acceptance command `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain` exited 0 at the same HEAD plus staged Step 1 edits on 2026-09-15:

```text
BUILD SUCCESSFUL in 28s
Unit XML timestamps: 2026-09-15T17:27:26.752Z through 2026-09-15T17:27:26.992Z
Unit XML totals: tests=81 failures=0 errors=0 skipped=0
Lint XML: errors/fatal=0 warnings=58
Debug APK: app/build/outputs/apk/debug/app-debug.apk; 66870422 bytes
```

The executor parsed every `app/build/test-results/testDebugUnitTest/*.xml` testsuite header, counted lint XML severities, and inspected the APK file size for this observation. The targeted and full commands ran against committed code plus the staged Step 1 diff; do not attribute these new regression results to the unchanged base commit alone.

Corrected whitespace verification at 2026-09-15T17:30:58Z: after normalizing the embedded diff's blank context lines and staging the documentation, `git diff --cached --check` and `git diff --check HEAD` exited 0 with no output. This supersedes the earlier clean-check claim, which did not cover the newly staged appendix.

**Unverified:** independent review, hosted CI, and delivery remain for the main thread. This step did not run emulator or physical-printer tests; exact query and repository assertions require neither.
