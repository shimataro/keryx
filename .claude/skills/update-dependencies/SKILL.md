---
name: update-dependencies
description: Use to update dependencies, the JDK/Gradle toolchain, and frameworks to their latest stable / LTS versions in the Keryx KMP app, then verify the build and sync version numbers in the docs. Handles the Maven Central stale-index gotcha and the Kotlin↔Compose↔lifecycle coupling. Invoke explicitly with /update-dependencies, or when asked to "bump versions", "update to latest stable", "update dependencies".
---

Bump Keryx's dependencies / runtime / build tooling to the **latest stable (or LTS)**
versions, verify with a full build, then sync the version numbers documented in
`.claude/CLAUDE.md`. Do NOT adopt Beta / alpha / RC / `-Beta` / `-dev` builds — those
are out of scope for "latest stable".

## Where versions live

- **`gradle/libs.versions.toml`** — the version catalog (`[versions]`). The main target.
  Note: `composeCompiler` and `kotlinSerialization` plugins share `version.ref = kotlin`,
  so bumping `kotlin` moves them too.
- **`gradle/wrapper/gradle-wrapper.properties`** — Gradle (`distributionUrl`).
- **`settings.gradle.kts`** — `foojay-resolver-convention` plugin version.
- **`composeApp/build.gradle.kts`** — `jvmToolchain(N)` and `JvmTarget.JVM_N` (JDK, keep on latest **LTS**).

## Step 1 — Inventory current versions

Read `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`,
`settings.gradle.kts`, and the `jvmToolchain(...)` line in `composeApp/build.gradle.kts`.

## Step 2 — Find the latest STABLE version of each

**Gotcha:** the `search.maven.org` solrsearch `latestVersion` field is **stale** — it
often reports versions *older* than the repo already uses. Do not trust it. Query the
authoritative **`maven-metadata.xml`** on `repo1.maven.org` instead, and filter out
pre-releases yourself (`<release>` can itself be a Beta; inspect the version list).

```bash
for coord in \
  "org.jetbrains.kotlin:kotlin-stdlib" \
  "org.jetbrains.compose:org.jetbrains.compose.gradle.plugin" \
  "app.cash.sqldelight:runtime" \
  "org.xerial:sqlite-jdbc" \
  "io.ktor:ktor-client-core" \
  "io.insert-koin:koin-core" \
  "org.jetbrains.kotlinx:kotlinx-coroutines-core" \
  "org.jetbrains.kotlinx:kotlinx-serialization-json" \
  "org.jetbrains.kotlinx:kotlinx-datetime" \
  "io.github.pdvrieze.xmlutil:serialization" \
  "io.coil-kt.coil3:coil-compose" \
  "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose" \
  "com.fleeksoft.ksoup:ksoup" \
  "net.java.dev.jna:jna-jpms" \
  "io.github.kdroidfilter:composewebview" \
  "org.slf4j:slf4j-simple" \
; do
  g="${coord%%:*}"; a="${coord##*:}"; gpath=$(echo "$g" | tr '.' '/')
  meta=$(curl -s "https://repo1.maven.org/maven2/$gpath/$a/maven-metadata.xml")
  release=$(echo "$meta" | grep -o '<release>[^<]*</release>' | sed 's/<[^>]*>//g')
  recent=$(echo "$meta" | grep -o '<version>[^<]*</version>' | sed 's/<[^>]*>//g' | tail -6 | tr '\n' ' ')
  echo "$coord => release=$release | recent: $recent"
done
```

The `recent:` column lets you pick the newest version with **no** pre-release suffix
(reject `-Beta`, `-beta`, `-alpha`, `-RC`, `-rc`, `-dev`, `-M`). Each catalog `version.ref`
maps to a representative artifact above (e.g. `ktor` → `io.ktor:ktor-client-core`,
`serialization` → `kotlinx-serialization-json`).

Sources that are **not** on Maven Central:

```bash
# Gradle (current stable release)
curl -s "https://services.gradle.org/versions/current" | grep -o '"version" : "[^"]*"'
# foojay-resolver-convention plugin (Gradle Plugin Portal marker)
curl -s "https://plugins.gradle.org/m2/org/gradle/toolchains/foojay-resolver-convention/org.gradle.toolchains.foojay-resolver-convention.gradle.plugin/maven-metadata.xml" \
  | grep -o '<version>[^<]*</version>' | sed 's/<[^>]*>//g' | tail -6
# java-keyring is published on JitPack, not Maven Central
curl -s "https://jitpack.io/com/github/javakeyring/java-keyring/maven-metadata.xml" \
  | grep -o '<version>[^<]*</version>' | sed 's/<[^>]*>//g' | tail -6
```

- **JDK toolchain** — track the latest **LTS** (currently 25). Do not jump to a non-LTS
  feature release. `WebSearch` for the current JDK LTS if unsure.
- **`composewebview`** currently has only pre-release builds (`-beta`) — keep the newest
  beta only if no stable exists yet; note it in the report, don't silently "downgrade" it.

## Step 3 — Respect the compatibility coupling

These move as a group and can break if one outpaces the others:

- **Kotlin ↔ Compose Multiplatform ↔ `composeCompiler`.** The Compose compiler is bundled
  with Kotlin (`plugin.compose`, `version.ref = kotlin`). A Kotlin **patch** bump within
  the same minor is usually safe against a given Compose MP. A Kotlin **minor** bump may
  need a matching Compose MP — verify, and fall back to the previous Kotlin if the compose
  compiler reports an incompatibility.
- **`lifecycle` (`org.jetbrains.androidx.lifecycle`) tracks Compose MP.** A lifecycle
  version newer than what the *stable* Compose MP pairs with can pull in mismatched Compose
  runtime APIs. If Compose MP stays on its stable release, bump lifecycle but **verify**;
  on any compile/runtime/test failure, fall back to the last lifecycle that builds against
  the current Compose MP.
- Keep **Compose MP itself on its latest _stable_** (do not adopt a `1.x.0-beta` just to
  enable a newer lifecycle — stable is the constraint).
- **`compose-material3` and `compose-material-icons-extended` are versioned independently
  from `compose-multiplatform`** (the direct-dependency declarations in `libs.versions.toml`
  do not use `version.ref = "compose-multiplatform"`). When bumping `compose-multiplatform`,
  update these two to the versions that release aligns to — verify against the resolved tree
  (`./gradlew :composeApp:dependencies --configuration desktopRuntimeClasspath`, which shows
  what the aligned versions actually are; material-icons-extended is currently frozen at 1.7.3
  by JetBrains). Leaving them stale means Compose sub-artifacts silently pin to an older minor.

## Step 4 — Apply and verify

1. Edit `gradle/libs.versions.toml` (and the wrapper / settings / toolchain files if those
   moved). Bump only to the chosen stable versions.
2. Confirm the launching JVM is JDK 25+ (`java -version`), then run the full build — this is
   the acceptance gate (compiles all source sets + runs the test suite):
   ```bash
   ./gradlew build
   ```
   Verify test count/health from `composeApp/build/test-results/desktopTest/*.xml`
   (failures/errors must be 0). Compare the count against `docs/testing.md`.
3. On a red build, **isolate the culprit one bump at a time** and revert the offending
   library to its previous stable version (lifecycle and a Kotlin minor are the usual
   suspects — see Step 3). Re-run until green.

## Step 5 — Sync the docs

Only the version numbers that are written out **with their patch component** need editing:

- **`.claude/CLAUDE.md`** — the `## Environment` block spells out exact versions
  (e.g. `Kotlin 2.4.10 ...`, `Ktor 3.5.1, Koin 4.2.2, ...`). Update every line that names a
  version you changed.
- **`docs/testing.md`** — update the test count only if it actually changed.
- **`THIRD-PARTY-LICENSES.md`** — update only when a shipped runtime dependency was **added
  or removed** (not for version bumps). See constraint #8 in `.claude/CLAUDE.md`.
- **Usually no change:** `docs/external-spec.md` cites versions as `major.minor` (e.g.
  `Kotlin 2.4`, `Ktor 3.5`, `Gradle 9.6`), so patch/minor bumps within the same major.minor
  don't touch it. Edit only if a major.minor actually changed. `README.md` is user-facing
  only and must not cite dependency versions at all — don't add them there.

## How to report

- A short table of each dependency: current → new (or "already latest").
- Call out anything left on a pre-release because no stable exists, and any library held
  back for compatibility (with the reason).
- State the build result and test count (e.g. "530 tests, 0 failures").
- List the doc lines updated.
