# Prepare art at print dimensions

Created: 2026-09-15. State: started (implementation; hosted green verification pending).

## Premises

- **Measured:** `git rev-parse HEAD` returned `56017756594d8f3bc0bb35b29b52388146348588`
  on 2026-09-15 in the `fix-art-rendering` checkout.
- **Measured:** `git diff bf746e5660eb3ada8ee112f4d0856e864251b89b -- app/src` returned
  no output before edits on 2026-09-15; the planning baseline's application source is preserved.
- **Measured:** the before-state query below finds filtered composition scaling and the
  ViewModel's same-dimension dithering call; the targeted regression is art resampling after dithering.
- **Inherited:** the parent plan authorizes hosted API 36 instrumentation, excludes physical
  devices/printers, and assigns review and GitHub writes to the main thread.

## Contract and rationale

Prepare art at `OUTPUT_WIDTH - 2 * PADDING` before tone mapping and dithering to preserve
binary art pixels in the final slip. Preserve the low-level dithering method's input dimensions,
the Floyd-Steinberg kernel, text antialiasing, slip width, margins, and trailing feed.

Add `prepareArt(src: Bitmap, contrast: Float = DEFAULT_CONTRAST,
brightness: Float = DEFAULT_BRIGHTNESS): Bitmap` and public constant `ART_WIDTH`.
Resize with filtering to `ART_WIDTH` and
`maxOf(1, (src.height.toLong() * ART_WIDTH / src.width).toInt())` before dithering.
Never modify or recycle input art. Require nonnull composition art to have width `ART_WIDTH`,
raising a descriptive `IllegalArgumentException` otherwise; preserve null/missing art as text-only.
Update the ViewModel and composition test fixtures to prepare art before composition.

Reject silent composition rescaling because it would recreate the ordering defect at another
call site. Use synthetic pixel fixtures and exact allowed colors instead of network images or
approximate screenshot thresholds.

## Verification boundary

First compile the regressions and add the hosted emulator workflow. Have the main thread push
the red tests and observe their specific failures before changing production rendering.
Then verify prepared dimensions for wide/tall/narrow/one-row sources, every prepared pixel in
`{Color.BLACK, Color.WHITE}`, unchanged inputs, exact composed/PNG-decoded art pixels, white
margins/feed, width rejection, and existing rendering tests. Keep workflow permissions read-only
and obtain separate security review of hosted KVM setup before acceptance.

## Before/after appendix

Run this same query before and after production changes:

```bash
rg -n 'OUTPUT_WIDTH|PADDING|imgHeight =|FILTER_BITMAP_FLAG|fun prepareArt|applyFloydSteinbergDithering\(' app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt
```

Before: query output inspected 2026-09-15 at `56017756594d8f3bc0bb35b29b52388146348588`:

```text
app/src/main/java/com/example/snapstoneprinter/ui/ProxyGeneratorViewModel.kt:329:                ImageProcessor.applyFloydSteinbergDithering(
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:23:    const val OUTPUT_WIDTH = 384
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:41:    private const val PADDING = 12
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:56:    fun applyFloydSteinbergDithering(
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:116:     * Every slip is independently [OUTPUT_WIDTH] px wide and gets its own
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:148:     * always exactly [OUTPUT_WIDTH] pixels.
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:165:        val canvasWidth = OUTPUT_WIDTH
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:166:        val padding = PADDING
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:231:        var imgHeight = 0
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:233:            imgHeight = (ditheredArt.height * textWidth) / ditheredArt.width
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:283:                c.drawBitmap(ditheredArt, srcRect, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
```

After production implementation, measured 2026-09-15 on red-test commit
`792d9aa37eb41f426ad5b71bb9d660acc900302a` plus the uncommitted fix:

```text
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:22:    const val OUTPUT_WIDTH = 384
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:40:    private const val PADDING = 12
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:41:    const val ART_WIDTH = OUTPUT_WIDTH - 2 * PADDING
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:45:    fun prepareArt(
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:52:        return applyFloydSteinbergDithering(resized, contrast, brightness)
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:68:    fun applyFloydSteinbergDithering(
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:128:     * Every slip is independently [OUTPUT_WIDTH] px wide and gets its own
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:162:     * always exactly [OUTPUT_WIDTH] pixels.
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:187:        val canvasWidth = OUTPUT_WIDTH
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:188:        val padding = PADDING
app/src/main/java/com/example/snapstoneprinter/image/ImageProcessor.kt:253:        val imgHeight = ditheredArt?.height ?: 0
```

### Red-test preparation — 2026-09-15

```bash
JAVA_HOME=/home/veyloris/.local/share/snapstone-review/jdk-25 ANDROID_HOME=/home/veyloris/Android/Sdk ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 .github/workflows/android-tests.yml
git diff --check
```

Measured results: Gradle exit 0, `BUILD SUCCESSFUL in 36s`, with `88 actionable tasks:
88 executed`; full log `/tmp/snapstone-art-red-compile.log`. Actionlint returned exit 0
with no diagnostics (`/tmp/snapstone-art-actionlint.log`), and `git diff --check` returned
exit 0. These checks compile instrumentation but do not execute the hosted red assertions.

The new tests require each public composition entry point to reject 600-pixel art and
check exact composition/PNG preservation of a native 360-pixel checkerboard. The art rectangle
uses the deterministic fixture's specified title/type layouts, not image-based row detection.

The executor inspected the pinned emulator runner's `src/main.ts` and `src/script-parser.ts`
through `gh api` on 2026-09-15: it runs each nonempty script line through a separate shell.
Use a folded YAML command so the logcat EXIT trap and Gradle command share one shell;
retain the Gradle exit status even if log capture fails.

## Limits

Unverified: hosted image provisioning, runtime regressions, independent/security review,
and inherited authorization remain with the main thread. Do not claim physical printing
from bitmap or emulator results.

## Hosted red and implementation — 2026-09-15

Measured using `gh run view 35002043418 --repo veyloris/SnapstonePrinter --log-failed`
and downloaded `android-test-reports` from
[the red run](https://github.com/veyloris/SnapstonePrinter/actions/runs/35002043418)
at `792d9aa37eb41f426ad5b71bb9d660acc900302a`:

```text
tests="35" failures="4" errors="0" skipped="0"
legacyCompositionRejectsUnpreparedArtWidth: expected IllegalArgumentException; nothing thrown
cardCompositionRejectsUnpreparedArtWidth: expected IllegalArgumentException; nothing thrown
planCompositionRejectsUnpreparedArtWidth: expected IllegalArgumentException; nothing thrown
preparedPatternSurvivesCompositionAndPngExactly: passed
slipFillsAvailableWidthAndKeepsAspectRatio: slip width 304 dp versus requested 400 dp
```

Local artifact copy: `/tmp/snapstone-art-hosted-red-reports/`; build log:
`/tmp/snapstone-art-hosted-red.log`. The log reports emulator display `320x640, dpi: 160x160`,
and captured logcat reports density 1.0 with width 320. The layout fixture requested 400 dp,
so its comparison against requested width exceeded the available viewport; the observed
304 dp matches 320 dp minus the preview's side padding. Measure the actual tagged container
instead, retain the existing 80% filling threshold and aspect-ratio assertion, and additionally
require positive dimensions and no horizontal overflow. Keep the production preview unchanged.

After these expected width failures, the executor added preparation dimension/binary/input
preservation tests and a generated-art composition/PNG test before implementing `prepareArt`.
The new preparation ordering test compares against resampling followed by the existing
tone/dither routine; the final-art test checks every art pixel against prepared output in both
the composed slip and decoded PNG, separately from antialiased text.

The first local post-fix command matched the red compilation command above and returned
exit 0, `BUILD SUCCESSFUL in 24s`, `88 actionable tasks: 23 executed, 65 up-to-date`
(`/tmp/snapstone-art-green-local.log`). After the viewport fixture correction and native-width
source case, the same command returned exit 0, `BUILD SUCCESSFUL in 10s`,
`88 actionable tasks: 8 executed, 80 up-to-date`
(`/tmp/snapstone-art-green-local-final.log`); `git diff --check` returned exit 0.
Unverified: final hosted runtime acceptance remains pending.
