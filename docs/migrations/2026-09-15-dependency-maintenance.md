# Dependency maintenance evidence

Created: 2026-09-15. State: started; await default-branch landing and hosted verification.

## Premises

- **Measured:** `git rev-parse HEAD` returned `92abee67a84836990538e99f0715a15bd82613ae` before implementation on 2026-09-15; `git status --short` returned no changes.
- **Measured, retrospective baseline query:** `git ls-tree -r --name-only 92abee67a84836990538e99f0715a15bd82613ae -- .github renovate.json` returned no paths after implementation on 2026-09-15; use this commit-addressed result as the baseline rather than claiming the query preceded edits.
- **Inherited:** the user confirmed Renovate App access to all repositories in the 2026-09-15 session; app installation was not independently inspected by this executor.
- **Inherited:** the main thread reported GitHub vulnerability-alert enablement with `gh api --method PUT repos/veyloris/SnapstonePrinter/vulnerability-alerts`, followed by `gh api --include repos/veyloris/SnapstonePrinter/vulnerability-alerts` returning HTTP 204 at 2026-09-15 16:57 UTC; this does not certify dependency graph coverage.

## Decision and scope

Use native Renovate managers to avoid maintaining a second dependency parser. Keep manual review, family grouping for minor/patch updates, separate majors, and default required-check discovery; inspect [renovate.json](../../renovate.json) for policy values.

Use the pinned Gradle dependency-submission action to obtain resolved dependencies for GitHub alerts. Keep the job advisory, timed, and restricted to `master`, with write permission confined to that job; inspect [the workflow](../../.github/workflows/dependency-submission.yml) for the executable contract. Require the CI baseline before adopting updates. Do not add another update bot or application changes to this step.

## Validation commands and outputs

### Renovate

The 2026-09-15 runs below used Renovate 44.93.4 and Node 24.21.0; the native strict repository validator and local extract-only invocation exited 0.

```bash
npm exec --yes --package=node@24.21.0 --package=renovate@44.93.4 -- \
  renovate-config-validator --strict --no-global renovate.json
LOG_LEVEL=debug npm exec --yes --package=node@24.21.0 --package=renovate@44.93.4 -- \
  renovate --platform=local --dry-run=extract
```

Recorded validator output:

```text
INFO: Validating renovate.json as repo config
INFO: Config validated successfully against 1 file(s)
```

Recorded extraction summary at the baseline plus staged configuration/workflow on 2026-09-15:

```json
{"github-actions":{"fileCount":1,"depCount":6},"gradle":{"fileCount":5,"depCount":44},"gradle-wrapper":{"fileCount":1,"depCount":1}}
```

Stage added files before repeating local-platform extraction so Git's file inventory includes the workflow. The extraction reported an unavailable optional RE2 binary and a missing GitHub token for some dependencies; treat this as extraction evidence, not authenticated update lookup or activation. The rules use glob patterns rather than custom regular expressions.

The native `applyPackageRules` check on 2026-09-15 passed minor/patch family matching, major-group clearing, and exclusion of unrelated AndroidX core from custom groups. To repeat the load-bearing plugin-marker and major checks against the pinned package, use:

```bash
npm exec --yes --package=node@24.21.0 --package=renovate@44.93.4 -- bash -c '
  export SNAPSTONE_RENOVATE_ROOT="$(dirname "$(dirname "$(readlink -f "$(command -v renovate)")")")"
  node --input-type=module
' <<'JS'
import fs from 'node:fs';
import assert from 'node:assert/strict';
const { applyPackageRules } = await import(`${process.env.SNAPSTONE_RENOVATE_ROOT}/dist/util/package-rules/index.js`);
const { packageRules } = JSON.parse(fs.readFileSync('renovate.json', 'utf8'));
const pluginIds = ['org.jetbrains.kotlin.plugin.compose', 'org.jetbrains.kotlin.plugin.serialization', 'com.google.devtools.ksp'];
for (const id of pluginIds) {
  const packageName = `${id}:${id}.gradle.plugin`;
  for (const updateType of ['minor', 'patch', 'major']) {
    const actual = await applyPackageRules({ packageName, packageRules, updateType, groupName: 'inherited group' });
    assert.equal(actual.groupName, updateType === 'major' ? null : 'Kotlin compiler plugins and KSP');
  }
}
assert.equal((await applyPackageRules({ packageName: 'androidx.core:core-ktx', packageRules, updateType: 'minor' })).groupName, undefined);
console.log('PASS: plugin marker groups, separate majors, unrelated AndroidX exclusion');
JS
```

### Workflow structure

`go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 .github/workflows/dependency-submission.yml` exited 0 with no output on 2026-09-15, after adding explicit Android command-line tools and package inputs. The main thread's inherited CI finding was that setup-android's default `tools` package failed on hosted run 34998605327; use the explicit inputs to avoid that default.

The parsed workflow assertions on 2026-09-15 passed the allowed event set `{push, workflow_dispatch}`, pushed branch set `{master}`, master-only job guard, workflow `{contents: read}` and job `{contents: write}` permissions, timeout, enforced advisory job, pinned actions, and graph-step failure visibility. Repeat inspection against those allowed sets when changing the workflow; check that manual dispatch from a feature branch fails the ref guard.

### Local dependency snapshot

Fetch the exact action scripts and run the same generate-only invocation used on 2026-09-15; the GitHub CLI reads public source files, while the Gradle invocation receives no submission token:

```bash
mkdir -p /tmp/snapstone-graph-validation
for script in gradle-actions.github-dependency-graph.init.gradle gradle-actions.github-dependency-graph-gradle-plugin-apply.groovy; do
  gh api "repos/gradle/actions/contents/sources/src/resources/init-scripts/$script?ref=4c125117fe7c5aed11272ec4213f602f012f89f2" \
    --jq .content | base64 --decode > "/tmp/snapstone-graph-validation/$script"
done
# Set JAVA_HOME and ANDROID_HOME for the installed project toolchain first.
GITHUB_DEPENDENCY_GRAPH_ENABLED=true \
GITHUB_DEPENDENCY_GRAPH_JOB_CORRELATOR=local-snapstone-review \
GITHUB_DEPENDENCY_GRAPH_JOB_ID=local-review \
GITHUB_DEPENDENCY_GRAPH_REF=refs/heads/dependency-maintenance \
GITHUB_DEPENDENCY_GRAPH_SHA="$(git rev-parse HEAD)" \
GITHUB_DEPENDENCY_GRAPH_WORKSPACE="$PWD" \
DEPENDENCY_GRAPH_REPORT_DIR=/tmp/snapstone-dependency-graph \
bash gradlew --no-daemon --no-configuration-cache \
  -I /tmp/snapstone-graph-validation/gradle-actions.github-dependency-graph.init.gradle \
  :ForceDependencyResolutionPlugin_resolveAllDependencies
```

Recorded output, exit 0:

```text
Resolving dependency graph plugin 1.4.1 from plugin repository: https://plugins.gradle.org/m2
GitHubDependencyGraphRenderer: Wrote dependency snapshot to
/tmp/snapstone-dependency-graph/local-snapstone-review.json
BUILD SUCCESSFUL in 11s
```

Parsing that JSON snapshot at its `scanned` timestamp `2026-09-15T17:02:24Z` found 403 resolved dependencies, including both direct and indirect Maven entries, and detector version 1.4.1. Inspect the generated JSON rather than substituting Gradle's textual dependency report for snapshot validation. Treat repeat runs as new observations; the plugin may suffix filenames when an earlier report exists.

## After inventory and remaining verification

`git diff --cached --name-only` on 2026-09-15, before adding this evidence record, returned:

```text
.github/workflows/dependency-submission.yml
README.md
renovate.json
```

`git diff --cached --check` passed on 2026-09-15. Include this evidence record in the final diff inventory.

**Unverified:** hosted submission, GitHub's acceptance of the snapshot, resulting alert coverage, and Renovate activation require default-branch landing and a hosted run. Keep the inherited App-access and alert-setting observations separate from those results. No merge or upstream write is authorized by this record.
