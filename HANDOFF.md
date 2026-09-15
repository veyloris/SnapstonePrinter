# SnapstonePrinter — Handoff

## Current validation record — 2026-09-15

Use the [Milestone 1 migration](docs/migrations/2026-09-15-milestone-1.md) for the
authorized automation scope and the [validation appendix](docs/migrations/2026-09-15-milestone-1-appendix.md)
for commands and measured results. Treat emulator, interactive UI, and physical printer
observations below as inherited; do not interpret local JVM checks as device verification.

## Historical handoff — inherited 2026-09-14 record

The remaining document is inherited from baseline `92abee67a84836990538e99f0715a15bd82613ae`.
Treat its status, counts, external-system claims, and reproduction narratives as historical
observations rather than current verification. Preserve its design rationale and existing
followups; consult the code and relevant tests before acting on them.

App: Android app that rolls a random Magic: The Gathering card from Scryfall, renders it as
a 384px-wide 1-bit dithered "proxy slip", and hands it to an external Bluetooth thermal
printer app via `ACTION_SEND`.

Package: `com.example.snapstoneprinter` · Module: `:app` · Single-module Gradle project.

---

## 1. Historical state

**Compiles clean.** `:app:assembleDebug` — SUCCESS, no fixes required.

| Check | Result |
|---|---|
| `:app:assembleDebug` | SUCCESS |
| `:app:testDebugUnitTest` | **74 passed**, 0 failed, 0 skipped |
| `:app:connectedDebugAndroidTest` | **31 passed**, 0 failed (Pixel 10 Pro XL, API 36) |

Baseline going into the 2026-09-14 session was 67 unit / 31 instrumented (itself up from 63/31);
this session added 7 more unit tests (5 for `secondaryFaces`, 2 for the named-lookup repository
method). Instrumented held steady. Nothing is red, nothing is stubbed out, nothing was disabled.

### Finished

- Scryfall random-card fetch with junk-layout re-roll (`CardRepository`, `CardLayouts`,
  `ScryfallQueryBuilder`).
- Moshi codegen parsing of the card + `card_faces` model.
- Slip planning / DFC detection and label wording (`SlipPlanner`).
- Mana cost formatting (`ManaCostFormatter`).
- Image pipeline: auto-levels → Floyd-Steinberg → 384px mono output (`ImageProcessor`).
- Compose UI shell: generator screen, thermal preview, DFC pager.
- **Find card by name** (2026-09-14): `cards/named?fuzzy=` lookup
  (`ScryfallApiService.getCardByName` / `CardRepository.getCardByName` /
  `ProxyGeneratorViewModel.fetchCardByName`), exposed via a "Find card by name" menu item and
  `CardSearchSheet`. Shipped as a real feature, not just test scaffolding: it's the only
  practical way to force-generate a specific split/flip/adventure/transform/modal_dfc card
  instead of waiting on `cards/random` RNG, and it doubles as a general "look up a specific card"
  feature for the user. Skips the junk-layout reroll in `fetchPlayableCard` — an explicit name
  lookup should return exactly what was asked for, tokens/emblems included.

### Previously half-done, now verified on-device (2026-09-14)

The four files from the cancelled batch all compile and are wired through to a working user
flow, confirmed on an emulator (Pixel 10 Pro XL, API 36):

| File | Intended purpose | State |
|---|---|---|
| `image/ArtDownloader.kt` | Coil-based art fetch that returns a **software** `Bitmap` the ditherer can read pixel-by-pixel. Wraps `ImageLoader`/`ImageRequest`, rasterises any `Drawable` to `ARGB_8888`, returns `ArtResult.Success/Failure` with a logged reason instead of a bare null. | **Working.** The hardware-bitmap defences here were sound but were not the actual missing-art bug — see §5, now resolved. |
| `data/print/PrinterTargetStore.kt` | DataStore persistence of the user's chosen printer app `ComponentName`, so the chooser is shown once and subsequent prints go straight to the remembered target. | **Verified.** Persists across process death/restart; confirmed via `adb logcat` (`Remembered print target: ...`) and by relaunching the app and checking the overflow-menu label. |
| `data/print/ChosenComponentReceiver.kt` | `BroadcastReceiver` for `Intent.EXTRA_CHOSEN_COMPONENT`, captures which app the user picked out of the share sheet and feeds it to `PrinterTargetStore`. | **Verified firing.** Registered in the manifest; broadcast confirmed received and persisted on first share-sheet pick. |
| `ui/ProxyPanels.kt` | Extracted Compose panels for the generator screen (card detail / controls / preview panels). | Compiles and renders. |

Full remember → reuse → reset cycle verified: first PRINT shows the chooser, picking an app
persists it, the next PRINT for a fresh card skips straight to that app (no chooser), and
"Change printer app" in the overflow menu clears it and brings the chooser back. Tested against
`com.android.bips.ImagePrintActivity` (the built-in Print Service) as the remembered target.

---

## 2. Immutable spec — the user is firm on all of these

- **384px output width.** 384 dots = 57mm at 203dpi. This is the printer's native width. Do
  not parameterise it away or "scale to fit".
- **Floyd-Steinberg dithering, 7/3/5/1 ÷ 16 kernel.** Exactly this kernel. Not Atkinson, not
  ordered/Bayer, not a threshold.
- **Frameless native Canvas text on pure white.** No borders, no card frame, no background
  tint. Text is drawn with the platform `Canvas`/`Paint`, not rendered from Compose.
- **FileProvider + `ACTION_SEND` with `image/png`** to an *external* Bluetooth printer app.
  This app does not talk to the printer itself and should not start doing so.
- **`minSdk 36`.** Deliberate. Modern phones only. **Do not lower it** to widen device
  support — it is not an oversight.
- **Plain-text mana costs. NO pip symbols.** `{2}{U}{U}` renders as text. No icon font, no
  image pips, no drawable substitution.

---

## 3. Hard-won decisions that must not be undone

Every item here has a body count. Keep the reason attached to the code if you refactor it.

### 3.1 `ManaCostFormatter.PIP_PATTERN` — the brace MUST stay escaped

A dangling `}` in a regex is tolerated by desktop `java.util.regex`, which silently treats it
as a literal. **Android's ICU regex engine does not** — it throws `PatternSyntaxException`.
Because the pattern is a `static`/companion constant, that throw happens in `<clinit>`, which
poisons the whole class: every later touch throws `NoClassDefFoundError` and **every single
render crashes**.

JVM unit tests cannot catch this, because on the JVM the bad pattern compiles fine. That is
precisely why there are **device-side (androidTest) `ManaCostFormatter` tests**. Do not
"consolidate" them into the JVM suite — that deletes the only thing that can detect the bug.

### 3.2 `safeManaCost` catches `Throwable`, not `Exception`

Direct consequence of 3.1. The original failure was an `Error`
(`ExceptionInInitializerError` / `NoClassDefFoundError`), which is **not** an `Exception` and
sails straight through `catch (e: Exception)`. Narrowing this catch re-opens the crash.

### 3.3 Two-slip DFC detection is structural, not layout-string based

The test is exactly:

> `card_faces.size >= 2` **AND** *every* face has its own `image_uris`

- **Not** keyed on layout strings.
- **Not** keyed on `card_faces` merely being present.

If you key on the presence of `card_faces` alone, split / flip / adventure cards
(e.g. **Fire // Ice**) print **twice with duplicate art**, because those layouts have two
faces sharing one image.

Layout strings are used **only** to choose label wording — never to decide slip count.

### 3.4 Slip label wording

| Case | Label |
|---|---|
| `transform`, front slip | `Transforms into: {back}` |
| `transform`, back slip | `Transforms from: {front}` |
| `modal_dfc` and `reversible_card` | `Other side: {other}` |
| Unknown multi-face fallback | `Other side: {other}` |
| Single slip | `null` (no label drawn) |

### 3.5 Thermal preview MUST use `FilterQuality.None`

Bilinear filtering averages neighbouring 1-bit pixels back into grey, so the preview shows a
soft greyscale image the printer can never produce. Measured: `FilterQuality.None` → **0%**
grey midtones; `FilterQuality.Low` → **71.34%** grey midtones. The preview's entire job is to
be an honest representation of printer output.

### 3.6 Auto-levels runs BEFORE Floyd-Steinberg, never after

The histogram stretch must precede the dither pass. Without it, dark art dithers to a solid
black smear: measured **3.2% white** pixels without auto-levels vs **49.5% white** with it.
Running it after the dither is meaningless — there are only two levels left to stretch.

### 3.7 Scryfall client requirements

Scryfall **will return 403** without:

- a `User-Agent` header,
- an `Accept` header,
- and roughly **100ms throttling** between requests.

All three are required. This is Scryfall's documented policy, not a guess.

### 3.8 `-is:extra` instead of explicit `-layout:` negations

Scryfall applies a default "exclude extras" filter — but **naming a layout in the query LIFTS
that default exclusion**. So `-layout:token` paradoxically drags tokens and other extras back
into the result pool. `-is:extra` is used server-side instead. Do not "clarify" this by
expanding it into layout negations.

### 3.9 androidTest dependency versions are pinned forward deliberately

Espresso **3.7.0**, junit **1.3.0**, core **1.7.0**, runner **1.7.0**.

Espresso 3.5.1 reflects on `InputManager#getInstance`, which was **removed in Android 15**.
On an API 36 device, no Compose test can run at all on the old versions. Do not roll these
back to match the rest of the dependency block.

### 3.10 `scrollContent: Boolean` on `ProxyPreview`

Prevents an **"infinity maximum height constraints"** crash caused by nesting a vertically
scrollable preview inside another vertical scroller. The flag lets the caller turn the
preview's own scrolling off. It is not redundant with the parent's scroll state.

### 3.11 Split / flip / adventure carry their OTHER face's text on the SAME slip (2026-09-14)

Scryfall omits `oracle_text` from the top level for split / flip / adventure — confirmed against
live data for Fire // Ice, Bonecrusher Giant // Stomp, and Bushi Tenderfoot // Kenzo. Before this
fix, `SlipContent.oracleText` (via `ScryfallCard.effectiveOracleText`) fell back to
`card_faces[0]` only, so the ENTIRE second half of the card — Ice's ability, Stomp's spell text,
Kenzo's abilities — silently never printed anywhere. Flip cards also lost the back face's
power/toughness the same way.

**Fix:** `SlipContent.secondaryFaces: List<SecondaryFace>` (see `SlipPlanner.secondaryFacesFor`)
carries the other face(s)' own raw name/cost/type/text/P-T — deliberately NOT run through the
`effective*` fallbacks, since those exist to patch a MISSING field, not to represent a face that
has its own real value. `ImageProcessor.renderSlip` draws each one underneath the primary
face's content, on the same bitmap, with generous vertical spacing (`SECONDARY_FACE_GAP_PX`) as
the only separator — deliberately no rule/divider line, to stay inside the "no borders" rule in
§2.

**Structural guard:** `secondaryFacesFor` returns empty whenever `hasPerFaceArt(card)` is true —
i.e. a true two-slip DFC's back face must never ALSO appear as a secondary face of the front, or
it prints twice.

---

## 4. Approved UX decisions

- **Re-roll lives in the `TopAppBar`.** Explicitly requested there so it cannot be hit by
  accident. Do not move it next to PRINT or into a FAB.
- **PRINT is the large bottom button.** Primary action, bottom of screen, full prominence.
- **The preview must be LARGE** — fill the available width via
  `fillMaxWidth().aspectRatio(...)` with `ContentScale.FillWidth`. Do not shrink it into a
  thumbnail or card.
- **DFC uses a `HorizontalPager` with a page indicator**, one page per slip.
- **Two-slip cards dispatch as TWO SEQUENTIAL `ACTION_SEND` calls.** Never
  `ACTION_SEND_MULTIPLE` — the cheap BT printer apps this targets mishandle it (drop the
  second image or print garbage).

- ~~Mana cost right-justify.~~ **DONE 2026-09-14.** `ImageProcessor.buildTitleRow` now lays out
  name (flush left, width-capped so it wraps instead of colliding) and cost (flush right, same
  `ALIGN_OPPOSITE` technique as the power/toughness line) as two independent `StaticLayout`s.
  Applies to both the primary face and every `secondaryFaces` block.

---

## 5. Historical bug status

Inherited claim from the 2026-09-14 handoff: no bugs were recorded as open; §5.1 described
the prior art-download finding as resolved. Do not use this historical status as a current
review verdict.

### 5.1 RESOLVED — card art missing from slips

Slips rendered with text but no art. `downloadBitmap` was returning `null`.

The hardware-bitmap theory recorded here previously (`Bitmap.Config.HARDWARE` being unreadable
by Floyd-Steinberg) was a real defensive fix already present in `ArtDownloader.kt`
(`allowHardware(false)`, forced `ARGB_8888`, etc.) — but it was **not** the actual cause.

**Actual root cause:** `cards.scryfall.io` (the image CDN, separate host from
`api.scryfall.com`) enforces the same custom-User-Agent policy documented in §3.7, and rejects
requests with a default HTTP-library User-Agent with **HTTP 400** (`rule: generic_user_agent`).
`ArtDownloader` built its own `OkHttpClient` that never got `ScryfallHeaderInterceptor` — that
interceptor only ever lived on `RetrofitClient`'s client, used for the JSON API. Found by
temporarily adding `HttpLoggingInterceptor.Level.BODY` to `ArtDownloader`'s client and reading
the JSON error body back from the CDN.

**Fix:** `ArtDownloader.kt` now adds an interceptor setting `User-Agent` to
`ScryfallHeaderInterceptor.DEFAULT_USER_AGENT` on every request (not the interceptor itself,
since its default `Accept: application/json` is wrong for an image request). Verified on-device
across multiple consecutive card rolls — art renders correctly every time.

**Lesson for next time:** any future HTTP client added for a `*.scryfall.io`/`scryfall.com`
host needs this header. It's a per-client requirement, not a one-time interceptor setup.

---

## 6. Historical followups and verification limits

### Sharing / dispatch
- ~~Finish the sequential two-slip send.~~ **DONE, verified on-device 2026-09-14** against a real
  transform card (Delver of Secrets // Insectile Aberration, fetched via the new "Find card by
  name" feature — see §4). Slip 1 dispatched, and as soon as its `ACTION_SEND` activity returned,
  slip 2 fired automatically with its own art and the correct back-face label ("Transforms from:
  Delver of Secrets"). Confirmed via the in-app pager (swipe to slip 2) that the composed bitmap
  itself is complete and uncropped — see the print-preview note below.
- ~~Remembered `ComponentName` ... reset affordance.~~ **DONE, verified on-device 2026-09-14:**
  full remember → reuse → reset cycle confirmed working (see §1).
- ~~Fallback path when the remembered printer app has been uninstalled.~~ **DONE, verified
  on-device 2026-09-14.** No physical printer needed for this - it only depends on whether the
  remembered Android app is still installed, so it was tested by remembering Drive as the target,
  then `adb shell pm uninstall -k --user 0 com.google.android.apps.docs` (reversible: restore with
  `pm install-existing`), then printing again. The fallback itself worked (no crash, straight to
  chooser), but found and fixed a real gap: the stale target was never cleared from DataStore
  when it failed `isTargetUsable` up front (only when it passed that check and then failed to
  launch) - see `ProxyGeneratorScreen.kt`'s `storedTarget` vs `remembered` distinction. Menu now
  correctly reverts to "Printer app: ask every time" instead of showing a dead package name
  forever.

**Analog output note:** actual thermal-printer output (as opposed to the Android print-preview
stand-in used for on-device testing) can only be verified once the app is stable enough to run
on a real phone against a real Bluetooth printer. Until then, verification here is "the correct
bitmap reached an app via `ACTION_SEND`," not "the paper came out right."

**Print-preview widget quirk (not a bug):** the stock Android `com.android.printspooler`
preview can show a slip's leading edge cut off (text/art missing their first ~1-2 characters or
columns) when it's re-used across two sequential print jobs in a row — it appears to carry over
a horizontal scroll/pan position from the previous job's preview. Confirmed this is a viewport
artifact, not a real crop: the same slip's share-sheet thumbnail and the app's own in-app pager
both render it complete and uncropped. Don't chase this as a rendering bug if it's seen again in
that specific widget.

### Image controls
- ~~Contrast / brightness sliders...~~ Already implemented (`ProxyGeneratorViewModel.setContrast`
  / `setBrightness` / `resetToneMapping`, debounced via `scheduleRedither`, re-dithers
  `sourceArt` — no network refetch) and present in the UI (tone sheet + "Adjust dither tone"
  menu item). Confirmed present on-device; slider drag behavior itself not exercised this
  session.
- ~~Thermal-vs-full-res preview toggle.~~ Already implemented (`PreviewMode` /
  `setPreviewMode`, the "Thermal" / "Full card" segmented toggle visible in every screenshot this
  session). Confirmed rendering both modes.

### History
- ~~~20-entry session history with reprint.~~ Already implemented (`HistoryEntry`,
  `MAX_HISTORY = 20`, `reprint()`, "Session history (N)" menu item). Present in the UI; not
  exercised this session beyond confirming the menu item and count update.

### Housekeeping (dependency + code hygiene) — **DONE, 2026-09-14**

This whole list turned out to be stale before any of it was touched this session — verified via
`grep` across `build.gradle.kts` and `gradle/libs.versions.toml` before assuming anything needed
doing:

- ~~Strip unused deps: CameraX (×4), Room (×3), `play-services-location`, Accompanist,
  `material-views`.~~ None of these appear ANYWHERE in the project. Already gone (or never
  actually added — unclear which, doesn't matter).
- ~~KEEP `datastore`.~~ N/A, moot — still present and used by `PrinterTargetStore` as expected.
- ~~Pin versions: `1.3.+`, `2.11.+`, `1.4.+`.~~ Already pinned to exact versions everywhere, with
  a comment in `libs.versions.toml` explaining why (`"1.3.+"` makes the build non-reproducible).
- ~~Gate `HttpLoggingInterceptor` behind `BuildConfig.DEBUG`.~~ Already done in
  `RetrofitClient.kt`.
- ~~Drop the redundant Moshi `KotlinJsonAdapterFactory`.~~ Already dropped — codegen-only, with a
  doc comment explaining why (a reflective factory added via `.add()` would shadow the generated
  adapters).
- Remove `Context` from the ViewModel — **not applicable**, and not a real issue: the ViewModel
  extends `AndroidViewModel(application)`, the Android-recommended safe pattern specifically
  because a raw `Context` field is the classic Activity-leak bug. `ArtDownloader` does the same
  (`context.applicationContext` on construction). Nothing to remove here.
- ~~Clean unused imports.~~ Ran `./gradlew lintDebug` plus a manual sweep for delegate-operator
  false positives. Found and fixed: an unused `android.app.Activity` import and an obsolete
  `Build.VERSION.SDK_INT >= S` check in `Theme.kt` (minSdk 36 already clears API 31
  unconditionally); a redundant `android:label` on `MainActivity` (already inherited from
  `<application>`); two fully dead resources (`colors.xml` — 7 unused template colors — and
  `placeholder.png`, a template image with zero references anywhere).

**Bonus, not on the original list:** lint's `UseKtx` check found every
`Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)` and every
`canvas.save(); canvas.translate(...); ...; canvas.restore()` block in `ImageProcessor.kt` /
`ArtDownloader.kt`. Replaced with the KTX `createBitmap(w, h)` default and
`Canvas.withTranslation(x, y) { ... }` — same behavior, no way to forget the matching `restore()`.

Remaining lint output (21 findings) is just "newer version available" noise, out of scope given
the deliberate version-pinning policy above, plus 2 false-positive `IconLocation` hints on the
adaptive icon's foreground/monochrome layers (that warning doesn't understand adaptive icon
layer semantics — a single high-res image is the correct, normal setup there).

---

## 7. Reference

**github.com/MoritzHayden/momir-basic-printer** — a Raspberry Pi equivalent of this app.
Worth reading for ideas, not for architecture.

What it does:
- Prints name + mana cost **justified on ONE line** (name left-aligned, cost right-aligned).
- Same physical target: **384 dots / 203dpi / 57mm**.
- Dithers to mono.
- Uses **Scryfall bulk data** rather than per-card `cards/random` calls — a meaningfully
  different approach that avoids rate limiting entirely.
- **No DFC handling at all** — this app is ahead of it there.

Ideas worth stealing later: the one-line justified name+cost header, and the bulk-data
strategy if per-card throttling ever becomes painful.
