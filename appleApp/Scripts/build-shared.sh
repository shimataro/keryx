#!/bin/sh
# Xcode "Run Script" build phase: (re)builds the KeryxShared XCFramework for the active
# configuration and regenerates the String Catalog, before the Swift compile step that links
# against them. See docs/build.md's "Building the SwiftUI app" for the full explanation.
set -eu

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

REPO_ROOT="$(cd "$SRCROOT/.." && pwd)"
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
