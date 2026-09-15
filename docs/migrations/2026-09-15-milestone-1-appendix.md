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

Use the dated records below for observed results and the linked PRs for current delivery state.

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

Consult the later hosted/review record below for follow-through. Emulator and physical
printer checks were not run in Step 1.

### Hosted SDK setup correction — 2026-09-15

Inherited from the main thread's `gh run view 34998605327 --log-failed`: the
[first hosted run](https://github.com/veyloris/SnapstonePrinter/actions/runs/34998605327)
failed in Android SDK setup because setup-android v3's default package list requested
the unavailable `tools` package; `sdkmanager` returned exit 1.

Set `packages: platform-tools` explicitly to avoid requesting that obsolete package.
Set `cmdline-tools-version: '15859902'` to match the main thread's inherited report of
the locally checksum-verified SDK tools, and disable accepted-license log output.
Keep platform and build-tools installation in the following explicit provisioning step.

Measured 2026-09-15 after this correction:
`go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 .github/workflows/ci.yml`
returned exit 0 with no diagnostics; output log:
`/tmp/snapstone-step1-actionlint-sdk-fix.log`.

Consult the later hosted record below for the corrected run; do not treat local workflow
lint as proof that SDK provisioning succeeds on the hosted runner.

### Hosted checks, review, and delivery — measured 2026-09-15

`gh run view 34998887037 --repo veyloris/SnapstonePrinter --json status,conclusion`
returned `{"conclusion":"success","status":"completed"}` for workflow commit
`b3bfede907fcb0ad1a151f3b23b3134b01ba0aa1`.
[The hosted run](https://github.com/veyloris/SnapstonePrinter/actions/runs/34998887037)
completed SDK provisioning, Gradle checks, and both artifact uploads.

`gh api repos/veyloris/SnapstonePrinter/actions/runs/34998887037/artifacts`
returned nonexpired artifacts named `debug-apk` and `test-and-lint-reports`.
After `gh run download 34998887037 --repo veyloris/SnapstonePrinter --name
test-and-lint-reports --dir /tmp/snapstone-hosted-reports`, the XML aggregation used
for local checks above, now against the downloaded reports, returned:

```json
{"tests":76,"failures":0,"errors":0,"skipped":0,"lintErrors":0,"lintWarnings":57}
```

The downloaded hosted log retained the KSP 2.3.5 exception despite successful tasks;
consult [the separate KSP patch PR](https://github.com/veyloris/SnapstonePrinter/pull/2)
for the clean before/after reproduction and fix. Treat the Node 20 action-runtime
deprecation annotation as a remaining diagnostic, not a failing check.

Independent general reviews and separate workflow security passes on 2026-09-15
reported no must-fix findings. The main thread spot-checked the workflow, version diff,
Renovate extraction/rule outputs, and the locally generated dependency snapshot.

`gh api repos/veyloris/SnapstonePrinter/rules/branches/master` returned an active
required-status rule for `Build, test, lint` from GitHub Actions integration `15368`,
with strict base freshness. `gh pr checks 1 --repo veyloris/SnapstonePrinter --required`
returned that check as passing for the hosted run above. The advisory dependency
submission job is not part of the required-status rule.

Use [CI PR #1](https://github.com/veyloris/SnapstonePrinter/pull/1),
[KSP PR #2](https://github.com/veyloris/SnapstonePrinter/pull/2), and
[dependency PR #3](https://github.com/veyloris/SnapstonePrinter/pull/3) for current
delivery state. Land CI first, then refresh the sibling branches against the new
default branch before merging them so their heads receive the required check.

Unverified: Renovate activation and hosted dependency submission require the
configuration on the default branch; local extraction and generation do not certify
either. The main thread enabled vulnerability alerts and read back HTTP 204 on
2026-09-15; the user confirmed all-repository Renovate App access. Preserve these
limits until observing a default-branch run. No merges or upstream writes were made
during the recorded implementation.
