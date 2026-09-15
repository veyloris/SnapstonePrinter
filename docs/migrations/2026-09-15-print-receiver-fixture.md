# Test receiver fixture evidence

Created: 2026-09-15. State: started; runtime receipt/delivery assertions belong to the dispatch integration tests.

## Premises and boundary

- **Measured:** `git rev-parse HEAD` returned `9634b0ad63717eaae437baadf0f855782782c55a` before fixture edits on 2026-09-15; the only existing modification was the refreshed dispatch contract.
- **Inherited:** root assigned only the androidTest manifest, PrintReceiverActivity, and fixture evidence to this executor; the integration executor owns the UiAutomation driver and acceptance tests.
- **Measured:** no androidTest manifest existed at that observation; `cat app/src/main/AndroidManifest.xml` showed the production MainActivity and FileProvider, without this fixture.

## Fixture contract

Launch the explicit component whose package is `instrumentation.context.packageName` and whose class is `com.example.snapstoneprinter.ui.PrintReceiverActivity`. Keep its exported ACTION_SEND/image/png filter in `app/src/androidTest/AndroidManifest.xml` only, to exercise delivery to the test APK.

Read the TextView with content description `print-receiver-receipt` through UiAutomation. Parse its JSON fields: action, mime, uid, readGrant, writeGrant, streamUri, clipUri, clipCount, urisMatch, slipIndex, error, and successful-decode width/height/pixelSha256. `urisMatch` requires exactly one ClipData item matching EXTRA_STREAM. The zero-based index comes from the final URI segment `slip_N.png`. Check `error` is null before asserting decoded image fields.

Compute expected SHA-256 from row-major ARGB pixels, adding each integer's A/R/G/B bytes in big-endian order without a dimensions prefix. Use the receiver's own ContentResolver to read the shared content URI. Keep the fixture free of network calls, receipt singletons, production-only extras, or automatic completion. Drive `Return OK` and `Return Cancel` buttons to set their respective activity result and finish.

## Local validation

The initial command on 2026-09-15 was:

```bash
./gradlew --no-daemon :app:assembleDebugAndroidTest :app:processReleaseMainManifest --console=plain
```

It exited 1 during KSP output hashing because generated `ScryfallCardJsonAdapter.kt` was absent. Treat that attempt as failed validation; no receiver compilation result was established by it.

The same log recorded completion of `:app:processReleaseMainManifest`. Inspection of `app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml` returned the production package and MainActivity, with no PrintReceiverActivity; `git diff HEAD -- app/src/main/AndroidManifest.xml` returned no output. Recheck the generated release manifest after the final integration build.

The serialized retry at 2026-09-15T18:23:13Z, with HEAD `e97add0f79afa45bda31a503eafbe54fadd78e39` and the uncommitted fixture, used:

```bash
JAVA_HOME=/home/veyloris/.local/share/snapstone-review/jdk-25 \
ANDROID_HOME=/home/veyloris/Android/Sdk \
./gradlew --no-daemon :app:processReleaseMainManifest :app:processDebugAndroidTestManifest :app:assembleDebugAndroidTest --console=plain
```

The manifest tasks were successful/up-to-date; the overall command exited 1 at `:app:compileDebugAndroidTestKotlin` because the integration tests referenced unimplemented Pass B APIs, including `printJob`, `PrintDispatchHost`, and `stopPrinting`. No fixture compilation diagnostic appeared, but this failed command does not certify successful packaging.

The following queries ran after that attempt:

```bash
rg -n 'package=|PrintReceiverActivity|android:exported|android.intent.action.SEND|image/png' app/build/intermediates/merged_manifest/debugAndroidTest/mergeDebugAndroidTestManifest/AndroidManifest.xml
rg -n 'android:name=".*Activity"|PrintReceiverActivity' app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml
git diff HEAD -- app/src/main/AndroidManifest.xml
```

The androidTest output included package `com.example.snapstoneprinter.test`, exported `com.example.snapstoneprinter.ui.PrintReceiverActivity`, `android.intent.action.SEND`, and `image/png`. The release query returned only `com.example.snapstoneprinter.MainActivity`; the production-manifest diff was empty. These observations establish the merged-manifest boundary at that timestamp, not final APK contents.

**Unverified:** final APK registration and actual cross-APK URI decoding/result delivery require successful compilation and the integration executor's hosted tests. No physical-printer behavior is claimed. The integration executor owns subsequent Gradle runs to avoid concurrent generated-output changes.
