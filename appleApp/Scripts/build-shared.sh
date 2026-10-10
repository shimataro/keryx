#!/bin/sh
# Xcode "Run Script" build phase: (re)builds the KeryxShared XCFramework for the active
# configuration and regenerates the String Catalog, before the Swift compile step that links
# against them. See docs/build.md's "Building the SwiftUI app" for the full explanation.
set -eu

REPO_ROOT="$(cd "$SRCROOT/.." && pwd)"

# CI builds (or restores from its cache) both outputs before running xcodebuild, and sets this to
# keep them as they are: a framework restored from the cache has no Gradle task history, so the
# Gradle run below would relink every Kotlin/Native slice (~10 min) instead of being up-to-date.
# Never set for a release build or a local one.
if [ "${KERYX_SKIP_SHARED_BUILD:-}" = "1" ]; then
    for output in \
        "$REPO_ROOT/shared/build/XCFrameworks/release/KeryxShared.xcframework" \
        "$REPO_ROOT/composeApp/build/generated/stringCatalog/Localizable.xcstrings"; do
        if [ ! -e "$output" ]; then
            echo "error: KERYX_SKIP_SHARED_BUILD=1 but $output does not exist. Build it with ./gradlew :shared:assembleKeryxSharedReleaseXCFramework :composeApp:generateStringCatalog first." >&2
            exit 1
        fi
    done
    echo "KERYX_SKIP_SHARED_BUILD=1: using the existing KeryxShared XCFramework and String Catalog."
    exit 0
fi

# Xcode's build-phase scripts run with a minimal environment (no ~/.zshrc/.zprofile), so a
# JAVA_HOME set only in the developer's shell profile is not visible here — resolve one
# explicitly rather than assuming it's inherited.
if [ -z "${JAVA_HOME:-}" ]; then
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        JAVA_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
    fi
fi
if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in /opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk@25 /usr/local/opt/openjdk /usr/local/opt/openjdk@25; do
        if [ -d "$candidate/libexec/openjdk.jdk/Contents/Home" ]; then
            JAVA_HOME="$candidate/libexec/openjdk.jdk/Contents/Home"
            break
        fi
    done
fi
if [ -z "${JAVA_HOME:-}" ]; then
    echo "error: no JDK found to launch Gradle. Install one (e.g. 'brew install openjdk') and set JAVA_HOME, or see docs/setup.md." >&2
    exit 1
fi
export JAVA_HOME

cd "$REPO_ROOT"

# Always the Release Kotlin/Native framework variant, regardless of Xcode's own Debug/Release
# scheme — the same convention CLAUDE.md's own command list already documents
# (:shared:assembleKeryxSharedReleaseXCFramework). Xcode's Debug/Release only affects the Swift
# side; a Kotlin/Native debug framework buys little (LLDB can't meaningfully step into Kotlin
# bytecode anyway) at the cost of a second, larger artifact to keep in sync, so the project
# references one fixed XCFramework path from project.yml regardless of configuration.
./gradlew --console=plain \
    ":shared:assembleKeryxSharedReleaseXCFramework" \
    ":composeApp:generateStringCatalog"
