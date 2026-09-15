# Archived project plan

Historical record inherited from baseline `92abee67a84836990538e99f0715a15bd82613ae`,
archived 2026-09-15. Treat the implementation notes, acceptance criteria, timings, and
completion claims below as historical observations, not a current task queue or fresh
validation. Preserve their rationale when changing the related code.

Use the [Milestone 1 migration](../docs/migrations/2026-09-15-milestone-1.md) for the
current automation scope and its appendix for fresh verification. Consult
[the historical handoff](../HANDOFF.md) for inherited device checks and followups.

Architecture and Implementation Plan: MTG Thermal Proxy Generator

## 1. System Objective
Develop an Android application to support the Snapstone Wielder Magic: The Gathering mechanic by fetching random card data from the Scryfall API, formatting the output for a 384-dot width thermal printer, and broadcasting the generated layout via Android intents to an external Bluetooth printing application.

## 2. Network Layer (Scryfall API Integration)
*   **Client**: Retrofit.
*   **Base URL**: `https://api.scryfall.com/`
*   **Endpoints**:
    *   Random Card: `cards/random`
    *   Random Non-land: `cards/random?q=-t:land`
*   **Query Modifiers**: Implement a toggle state to append the `is:funny` tag to the search query parameter to module the inclusion of silver border and playtest cards.
*   **Data Model Extraction**: Parse JSON response for `name`, `mana_cost`, `type_line`, `oracle_text`, `power`, `toughness`, and `image_uris`. Target both `art_crop` for the printer pipeline and `normal` (or `large`) for the in-app UI display.

## 3. Image Processing and Layout Generation
*   **Canvas Constraints**: The output `Bitmap` width must be hardcoded to 384 pixels to match standard 57mm or 58mm thermal print heads. Scaling down higher resolutions externally introduces aliasing.
*   **Monochrome Conversion**: Apply a Floyd-Steinberg error diffusion dithering algorithm to the downloaded `art_crop` image to ensure legibility on binary black and white thermal printers.
*   **Text Formatting**: Do not use standard card frames. Render the `oracle_text`, `mana_cost`, and `type_line` natively on the Android `Canvas` below the dithered art. Utilize high-contrast sans-serif typography on a pure white background.

## 4. State Management and User Interface
*   **Framework**: Jetpack Compose.
*   **Architecture**: MVVM. The `ViewModel` manages network requests and holds the current card state.
*   **Components**: Trigger buttons corresponding to the active Scryfall queries, a toggle switch for the `is:funny` parameter, and a central image preview surface displaying the full resolution image variant.

## 5. Output and Inter-Process Communication
*   **Caching**: Save the generated composite `Bitmap` to the application local cache directory.
*   **URI Generation**: Utilize `androidx.core.content.FileProvider` to create a secure content URI.
*   **Intent Dispatch**: Broadcast an `Intent.ACTION_SEND` with the MIME type `image/png`, attaching the URI as `Intent.EXTRA_STREAM`. The Android OS will prompt the user to route the image to the external Bluetooth printer application.

## Project Brief

# MTG Thermal Proxy Generator - Project Brief

## Features
1. **Scryfall API Integration**: Fetch random card or random non-land card data from the Scryfall API, including a toggle filter for silver-border and playtest cards (`is:funny`).
2. **Thermal-Optimized Processing**: Format card artwork into a monochrome layout using a Floyd-Steinberg error diffusion dithering algorithm targeted precisely for 384-dot width thermal print heads.
3. **Canvas Layout Composition**: Composite card text (name, mana cost, type line, oracle text, power, and toughness) natively onto an Android `Canvas` below the dithered artwork with high-contrast typography.
4. **Interactive Preview Interface**: A streamlined control center providing trigger buttons, search modifiers, and a central preview surface displaying the generated proxy.
5. **External Printer Dispatch**: Save the final proxy bitmap to the local application cache and broadcast it via `Intent.ACTION_SEND` using `FileProvider` to external Bluetooth printing applications.

## High-Level Technical Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Architecture**: MVVM (Model-View-ViewModel) utilizing Kotlin Coroutines for network and image manipulation tasks.
- **Network Layer**: Retrofit for Scryfall API communication.
- **Navigation & Adaptive Strategy**: Jetpack Navigation 3 (state-driven navigation) and the Compose Material Adaptive library for handling all responsive layouts.
- **Core Android IPC**: `androidx.core.content.FileProvider` and Android Graphics Canvas for bitmap generation and secure sharing.

## Implementation Steps
**Total Duration:** 30m 37s

### Task_1_Core_Logic: Implement the networking layer for Scryfall API and the image processing pipeline including Floyd-Steinberg dithering and Canvas composition for 384px output.
- **Status:** COMPLETED
- **Updates:** Implemented Scryfall API integration with Retrofit, Moshi, and repository pattern. Developed Floyd-Steinberg dithering algorithm and Canvas composition logic for 384px thermal printer output. Verified logic with unit and instrumented tests.
- **Acceptance Criteria:**
  - Retrofit client and Scryfall API repository implemented.
  - Dithering algorithm for monochrome conversion implemented.
  - Canvas logic composites card text and dithered image at 384px width.
  - API integration verified.
- **Duration:** 4m 58s

### Task_2_UI_Implementation: Build the Jetpack Compose UI using MVVM, Navigation 3, and Material Adaptive components for controls and proxy preview.
- **Status:** COMPLETED
- **Updates:** Implemented Jetpack Compose UI with MVVM architecture. Integrated Navigation 3 for state-driven navigation. Used Material 3 and adaptive layout principles for phone and tablet support. The UI includes controls for fetching cards and a preview for the generated proxy.
- **Acceptance Criteria:**
  - Jetpack Compose UI implemented with MVVM pattern.
  - Navigation 3 integrated for state-driven navigation.
  - Material Adaptive components used for responsive layout.
  - Preview surface displays the generated proxy correctly.
- **Duration:** 3m 4s

### Task_5_Data_And_Image_Pipeline_Fixes: Fix data-layer and image-pipeline defects found in audit: card_faces[] parsing with effective* resolvers, Scryfall User-Agent/Accept headers plus ~100ms throttle, junk-layout filtering, mana cost brace normalization, auto-levels tonemapping before Floyd-Steinberg, contrast/brightness params on the ViewModel, trailing feed whitespace. 384px width and dithering kernel unchanged.
- **Status:** COMPLETED
- **Updates:** All audit defects fixed and verified (assembleDebug SUCCESS, 38/38 unit tests pass, androidTest compiles).
- **Acceptance Criteria:**
  - Double-faced cards render art and oracle text via card_faces resolvers
  - User-Agent + Accept headers sent and ~100ms request throttle in place, no 403s
  - Token/art_series/memorabilia/emblem/double_faced_token results excluded from random pulls
  - mana_cost displays as plain text without braces
  - Auto-levels normalization runs before dithering; dark art no longer prints as solid black
  - Contrast/brightness parameters applied pre-dither and exposed to the ViewModel
  - Trailing feed whitespace appended below composed text
  - Output bitmap still 384px wide with Floyd-Steinberg dithering unchanged
  - build pass
- **Duration:** 9m 55s

### Task_7_Multi_Slip_Generation: Refactor ImageProcessor and ProxyGeneratorViewModel so a fetched card produces a LIST of slips instead of a single Bitmap. Introduce a slip model (bitmap + face metadata + label). Trigger two-slip generation ONLY when every entry in card_faces[] has its own image_uris (layouts transform, modal_dfc, reversible_card); split, flip, adventure, meld and normal cards stay one slip. Render a small layout-aware header/footer line in the same frameless native-Canvas style: transform front 'Transforms into: {back name}', transform back 'Transforms from: {front name}', modal_dfc and reversible_card 'Other side: {other name}'. Each slip is independently 384px wide with its own trailing feed whitespace. Add unit coverage for transform/modal_dfc/reversible_card (2 slips) vs split/flip/adventure/meld/normal (1 slip).
- **Status:** COMPLETED
- **Updates:** Card generation now returns List<PrintSlip>. Build SUCCESS, 60/60 unit tests pass (up from ~40).

NEW image/PrintSlip.kt: data class (bitmap, faceName, faceIndex, totalSlips, label).
NEW image/SlipPlanner.kt: pure Kotlin, zero android.* imports, fully JVM-testable. hasPerFaceArt() is the structural trigger - card_faces.size >= 2 AND every face yields its own usable art URL. Layout strings deliberately NOT consulted for slip count, only for label wording. Fire // Ice (split, shared top-level image_uris) correctly returns false -> 1 slip. labelFor() handles transform front/back directionality, modal_dfc + reversible_card "Other side:", unknown/null layout fallback to "Other side:", null for single-slip cards, trim+lowercase tolerant.
MODIFIED ImageProcessor.kt: new composePrintSlips(card, ditheredArt) and composeSlips(plan, ditheredArt). Old body extracted to renderSlip(SlipContent, art) so each face renders ITS OWN mana cost/type/oracle/PT/art. Optional 14f non-bold header line drawn above title, omitted when label==null so single-slip output is byte-identical to before. Legacy compositeCardProxy retained as a delegate. 384px constant, 7/3/5/1÷16 kernel, auto-levels order all untouched.
MODIFIED ProxyGeneratorViewModel.kt: state proxyBitmap -> slips: List<PrintSlip> with derived primarySlip/proxyBitmap/hasMultipleSlips. generateProxy() plans, downloads each slip's own artUrl, dithers each with current contrast/brightness, composes. shareProxy(slipIndex=0).
MODIFIED ProxyGeneratorScreen.kt: minimum adaptation only - scrollable Column of all slips labelled "Slip N of M". No pager/history (deferred to Task_8).
NEW SlipPlannerTest.kt: 20 tests. 2 slips for transform/modal_dfc/reversible_card; 1 for split/flip/adventure/meld/normal; negative structural cases (only one face has art, empty ImageUris, single face); all label cases; per-face distinct art URLs.
MODIFIED ImageProcessorTest.kt: 6 new instrumented tests - every slip exactly 384px, transform yields 2 non-sameAs() bitmaps, split yields 1 with label==null, per-slip white feed band.

CARRIED BLOCKER NOW RESOLVED: instrumented tests had never executed (no device). User has since attached an Android 16 Pixel emulator; draining the instrumented backlog now.
- **Acceptance Criteria:**
  - Slip model introduced; card generation returns List<Slip> through the ViewModel state
  - Two-slip detection keyed on every face having its own image_uris, not card_faces presence
  - transform/modal_dfc/reversible_card produce 2 slips; split/flip/adventure/meld/normal produce 1
  - Layout-aware label wording rendered on each slip in frameless Canvas style
  - Every slip is 384px wide with its own trailing feed whitespace; Floyd-Steinberg unchanged
  - New unit tests cover all listed layouts and all existing tests still pass
  - build pass
- **Duration:** 12m 40s

### Task_3_Sharing_Integration: Rescoped multi-slip sharing/dispatch. Verify FileProvider wiring in AndroidManifest.xml and res/xml/file_paths.xml (authority ${applicationId}.fileprovider, cache-path 'images/'). Cache EACH slip as its own PNG in cacheDir/images and dispatch each via Intent.ACTION_SEND with MIME image/png and the content URI in EXTRA_STREAM. For two-slip cards fire TWO SEQUENTIAL dispatches (slip 2 only after slip 1 returns) — never ACTION_SEND_MULTIPLE. Persist the printer app ComponentName chosen on the first share (DataStore) and dispatch directly to it afterwards, skipping the chooser, with a visible reset/change-target affordance and graceful fallback to the chooser if the remembered component is uninstalled.
- **Historical status:** formerly in progress; consult the dated handoff before resuming.
- **Acceptance Criteria:**
  - FileProvider config verified; content URIs generated without FileUriExposedException
  - Each slip cached as its own PNG in cacheDir/images
  - ACTION_SEND image/png with EXTRA_STREAM used; ACTION_SEND_MULTIPLE never used
  - Two-slip cards dispatch sequentially, slip 2 after slip 1 returns
  - Chosen printer ComponentName persisted and reused without chooser; reset affordance present
  - Graceful fallback when remembered component is missing
  - build pass
  - app does not crash
- **StartTime:** 2026-09-14 14:53:57 CDT

### Task_8_UI_Upgrades: UI upgrades on the existing Compose MVVM screen: contrast and brightness sliders with live thermal preview wired to setContrast/setBrightness/resetToneMapping; a preview toggle switching between the thermal composite and the full-resolution card image (effectiveNormalUrl); multi-slip preview (pager or stacked view) showing both slips for DFCs with a clear indication that two slips will print; and a session history of the last ~20 pulls with reprint, where a history entry may hold multiple slips.
- **Historical status:** formerly pending; consult the dated handoff before resuming.
- **Acceptance Criteria:**
  - Contrast/brightness sliders update the thermal preview live; reset restores auto-levels
  - Preview toggle switches between thermal composite and full-res card image
  - DFC cards show both slips in the preview with a 'prints 2 slips' indication
  - Session history keeps last ~20 pulls and reprints multi-slip entries correctly
  - build pass
  - app does not crash

### Task_9_Housekeeping: Dependency and code housekeeping: remove unused template dependencies (all 4 androidx.camera.*, all 3 Room artifacts, play-services-location, accompanist-permissions, com.google.android.material; keep datastore-preferences if used for the remembered print target), pin dynamic version ranges (1.3.+, 2.11.+, 1.4.+) to concrete versions, gate HttpLoggingInterceptor behind BuildConfig.DEBUG, remove the Context field from ProxyGeneratorViewModel, drop the redundant Moshi KotlinJsonAdapterFactory since codegen KSP is active, and remove unused imports in ProxyGeneratorScreen.kt. minSdk stays 36.
- **Historical status:** formerly pending; consult the dated handoff before resuming.
- **Acceptance Criteria:**
  - Listed unused dependencies removed; project still builds
  - Dynamic version ranges pinned to concrete versions
  - HTTP logging only active in debug builds
  - No Context field in ProxyGeneratorViewModel; no leak lint warning
  - Moshi reflective adapter factory removed; JSON parsing still works
  - minSdk remains 36
  - make sure all existing tests pass
  - build pass

### Task_4_Run_and_Verify: critic_agent: build, install and run the app on an API 36 device/emulator and execute the instrumented suite for the first time (./gradlew :app:connectedDebugAndroidTest) plus :app:testDebugUnitTest. Verify stability (no crashes on random pull, non-land pull, is:funny toggle, slider adjustment, preview toggle, history reprint, single and sequential two-slip dispatch), confirm transform/modal_dfc/reversible_card produce two correctly labelled slips while split/flip/adventure produce one, dark art is legible after auto-levels, print dispatch reaches an external app, and the immutable output spec holds (384px width, Floyd-Steinberg, frameless Canvas text, plain-text mana cost, FileProvider + ACTION_SEND image/png, minSdk 36). Report critical UI issues.
- **Historical status:** formerly pending; consult the dated handoff before resuming.
- **Acceptance Criteria:**
  - App builds, installs and runs on API 36 without crashes
  - connectedDebugAndroidTest executed and passing
  - DFC layouts print two labelled slips; split/flip/adventure print one
  - Print dispatch reaches external printer app via ACTION_SEND image/png
  - Output spec unchanged: 384px width, Floyd-Steinberg, frameless Canvas text, plain-text mana cost, minSdk 36
  - make sure all existing tests pass
  - build pass
  - app does not crash
  - Critical UI issues reported
