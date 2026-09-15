# Milestone 1 validation appendix

Created: 2026-09-15. State: started.

## Before/after state query

Run from the task checkout before implementation and again after staging implementation. `rg` exit 1 means no matched lines, not successful build validation. Preserve each command's output; do not interpret a final command exit as success for the whole query.

```bash
git rev-parse HEAD
git ls-files --stage gradlew
git ls-files .github renovate.json .renovaterc.json
rg -n 'toolchainVersion|compileSdk|release\(37\)|minSdk|targetSdk' gradle/gradle-daemon-jvm.properties app/build.gradle.kts
rg -n 'IN_PROGRESS|PENDING' .agent/plan.md
```

### Before — measured 2026-09-15, before implementation

```text
92abee67a84836990538e99f0715a15bd82613ae
100644 ef07e0162b183eb9d19a2c9ba7035c283af9f8dd 0 gradlew
[git ls-files automation query: no output]
app/build.gradle.kts:21:    compileSdk {
app/build.gradle.kts:22:        version = release(37)
app/build.gradle.kts:27:        minSdk = 36
app/build.gradle.kts:28:        targetSdk = 37
gradle/gradle-daemon-jvm.properties:12:toolchainVersion=25
113:- **Status:** IN_PROGRESS
126:- **Status:** PENDING
136:- **Status:** PENDING
148:- **Status:** PENDING
```

### After

Measured 2026-09-15 on Step 1 branch `milestone-1-ci-dependencies`, after staging the
workflow, wrapper mode, README, and historical documents; the same state query returned:

```text
92abee67a84836990538e99f0715a15bd82613ae
100755 ef07e0162b183eb9d19a2c9ba7035c283af9f8dd 0 gradlew
.github/workflows/ci.yml
app/build.gradle.kts:21:    compileSdk {
app/build.gradle.kts:22:        version = release(37)
app/build.gradle.kts:27:        minSdk = 36
app/build.gradle.kts:28:        targetSdk = 37
gradle/gradle-daemon-jvm.properties:12:toolchainVersion=25
[historical-status query: no matches, rg exit 1]
```

The before/after SDK query above returned identical runtime/toolchain settings; leave
the separate KSP patch to Step 3.

## Inherited baseline log inspected before implementation

Measured 2026-09-15 using `rg -n 'BUILD SUCCESSFUL|NullPointerException' /tmp/snapstone-gradle-checks.log`:

```text
55:Exception in thread "AWT-EventQueue-0" java.lang.NullPointerException: Cannot invoke "ksp.com.intellij.openapi.application.Application.getService(java.lang.Class)" because the return value of "ksp.com.intellij.openapi.application.ApplicationManager.getApplication()" is null
150:Exception in thread "AWT-EventQueue-0" java.lang.NullPointerException: Cannot invoke "ksp.com.intellij.openapi.application.Application.getService(java.lang.Class)" because the return value of "ksp.com.intellij.openapi.application.ApplicationManager.getApplication()" is null
182:BUILD SUCCESSFUL in 2m 20s
```

Treat original invocation/commit association as inherited from the main thread until fresh reproduction; this query certifies log contents only.

## Execution evidence

Unverified: record validator versions and results, local Gradle reports, KSP investigation evidence, hosted run URL/results, review verdict, and any external activation limitations here before marking the relevant step complete.

### Step 1 local checks — measured 2026-09-15

The pre-change state query above was rerun by the executor before edits at baseline
`92abee67a84836990538e99f0715a15bd82613ae` and returned the recorded output.
The [AGP 9.4 compatibility table](https://developer.android.com/build/releases/agp-9-4-0-release-notes#compatibility),
read 2026-09-15, lists SDK Build Tools 36.0.0 as both minimum and default, supporting
the workflow's build-tools package selection.

Inherited action-pin evidence: the main thread reported GitHub API tag-ref checks on
2026-09-15 for checkout v5, setup-java v5, gradle/actions v5, upload-artifact v4, and
setup-android v3; the workflow uses the supplied full commit SHAs.

```bash
go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 .github/workflows/ci.yml
JAVA_HOME=/home/veyloris/.local/share/snapstone-review/jdk-25 ANDROID_HOME=/home/veyloris/Android/Sdk ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
git diff --check
```

```text
actionlint: exit 0; no diagnostics (/tmp/snapstone-step1-actionlint.log)
Gradle: exit 0 (/tmp/snapstone-step1-gradle.log)
BUILD SUCCESSFUL in 5s
55 actionable tasks: 1 executed, 54 up-to-date
Configuration cache entry reused.
git diff --check: exit 0; no diagnostics
```

The first sandboxed attempts failed on network access (actionlint) and a read-only
Gradle cache lock; the approved retries above produced the recorded results.
The Gradle log reported unit tests up-to-date; treat this as successful incremental
task validation, not a fresh execution of every test or reproduction of KSP diagnostics.

A Node report check on 2026-09-15 read every `.xml` in
`app/build/test-results/testDebugUnitTest/`, summed each `testsuite`'s `tests`, `failures`,
and `errors` attributes, counted `Error`/`Fatal` severities in
`app/build/reports/lint-results-debug.xml`, and required a nonempty
`app/build/outputs/apk/debug/app-debug.apk`; it exited 0 with:

```json
{"xmlFiles":8,"tests":76,"failures":0,"errors":0,"lintErrors":0,"apkBytes":66870422}
```

Executor inspection against the workflow contract on 2026-09-15 found the allowed
event set `{pull_request, push, workflow_dispatch}`, permission set `{contents: read}`,
and exact Gradle task set `{testDebugUnitTest, lintDebug, assembleDebug}`. The inspection
also checked action pins, checkout credentials disabled, the job timeout, report upload
on failure, successful-build APK upload, and artifact retention against Step 1.

Unverified: independent/security review and hosted SDK provisioning, checks, and artifacts
remain with the main thread. Emulator and physical printer checks were not run in Step 1.
