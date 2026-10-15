---
name: review-architecture
description: Reviews Keryx changes for layered-architecture violations (UI to ViewModel to Repository to DataSource), expect/actual platform boundaries and KMP portability, error-design conformance (exception vs Result, notification center), and scope drift from the external spec. Read-only.
tools: Read, Grep, Glob, Bash
model: opus
---

## Before you start

**Read `.claude/etc/review/common.md` now, before anything else.** It defines the finding schema, the
severity and confidence scales, the responsibility boundaries between the review agents, the output
language, and the display name for your perspective. It is mandatory and this file does not repeat
it. (It is referenced by path rather than imported because `@`-imports are not reliably expanded in
agent definition files.)

You review Keryx (a cross-platform RSS reader, Kotlin Multiplatform / Compose Multiplatform) for
**structural conformance to the design docs**: layering, platform boundaries, error design, and scope.

Reference: `docs/error-design.md` and `docs/external-spec.md` are already in your context —
`.claude/CLAUDE.md` imports them at session start — so consult them directly rather than
re-reading them. `docs/app-architecture.md` is **not** imported (it is ~160KB): `grep` it for the
section heading the diff touches and read only that section.

## Not yours

- Oversized files/functions, dead code, duplication, naming → `review-quality`. You report only
  boundary violations, not internal quality.
- Whether the docs describe the new structure correctly → `review-docs`.
- Whether a `Result` branch is tested → `review-verification`.

## Checklist — layer violations

- Does the UI (`ui/`) call the DataSource (`data/`) directly instead of going
  through a Repository (`domain/`)? The layering is what keeps the UI unaware of
  sync/DB, so sync and conflict-resolution changes stay contained to `domain/`.
- Does a ViewModel hold business logic (sync, conflict resolution, multi-step DB
  work) that belongs in a Repository?
- Does every action reachable by more than one route (or from both UIs) take its effect,
  enablement and feedback from one shared handler/predicate (`.claude/CLAUDE.md` constraint #10)?
  Flag a route that re-implements any of the three instead of calling it, and a decision that
  belongs in `:shared` `presentation/` but is computed separately in Compose and in SwiftUI. The
  cross-UI duplication is a boundary finding and yours, not `review-quality`'s; one route diverging
  within a UI is `review-ui`'s.

## Checklist — platform boundaries

- Is platform-specific code (java.io, java.sql, java.awt, Ktor CIO, etc.) behind
  a `commonMain` `expect`, rather than leaking into `commonMain`? A new `:shared` `expect` needs
  an `actual` in `desktopMain`, `androidMain`, and `appleMain` — a missing one breaks the Android
  or Apple build. (CLAUDE.md constraint #4)
- **`:shared` stays UI-framework-free and Apple-buildable.** No Compose, Compose
  Resources, AWT/Swing or Android UI import in `shared/src`; a new `:shared`
  `expect` needs an `appleMain` actual too (`shared/src/appleMain`, with
  `macosMain`/`iosMain` only for AppKit/UIKit differences); no localized text
  produced in shared code (emit `NotificationText`/`ErrorKind`). Logic every UI
  needs belongs in `presentation/`, not re-derived in a composable.
- Does new platform-branching logic sit where it can be replaced per target? OS branches that already
  live in `commonMain` (`platform/PlatformOs.kt`, `ui/home/HomeCommon.kt`, `ui/home/KeyboardNav.kt`)
  are deliberate — a *new* one in `commonMain` needs a reason.
- Does the decision *policy* stay in `commonMain` with only the platform conversation in the `actual`?
  `DatabaseMerger` is the model: `MergeFailureClassifier` / `MergeSchema` are pure commonMain, and the
  desktop `actual` only speaks to the SQLite driver.

## Checklist — SwiftUI app and the `:shared` API

- Does Swift code re-implement a decision that the Compose app takes from `:shared` (an enablement
  rule, a filter, a validation, the text an error maps to)? Every route in the SwiftUI app should
  call the same shared predicate or controller (constraint #10). Swift should hold only the
  platform-side execution: windows, menus, clipboard, toasts, and VoiceOver.
- `shared/src/appleMain/.../sdk/KeryxSdk.kt` is the public surface of the framework. Does a change
  expose a Kotlin type SKIE cannot bridge cleanly (a generic interface, a default argument the
  Swift side relies on, a `suspend` function without a cancellation story), or leak a
  `data`/`domain` type that Swift should not see?
- `core/` and `di/` are cross-cutting: a new dependency direction (`core` → `domain`, a module
  binding an implementation from the wrong layer) is a layering finding.
- Entry points (`composeApp/src/desktopMain/.../main.kt`, `StartupTasks.kt`, `AndroidStartupTasks.kt`,
  `FeedRefreshWorker.kt`, `androidApp/.../MainActivity.kt`) should orchestrate shared code, not hold
  logic that the other platforms then need a second copy of.

## Checklist — error design

- Are external exceptions converted to `KeryxException` subclasses at the
  DataSource layer? Do expected errors use `Result<T>`?
- Are unexpected fatal errors swallowed by `Result<T>` when they should throw?
- Does a notification kept in the bell carry a next action (`AppNotificationAction`), and is its text
  built through `NotificationMessages` rather than hardcoded?
- Is a new or changed `ErrorKind` / `NotificationText` fine-grained enough for the UI to say why the
  error happened and what to do ("Error Message Wording" in `docs/error-design.md`)? One category
  that merges causes needing different next steps (an unreachable host vs. a 404 vs. a parse
  failure) is a finding; a new exception mapped to an existing catch-all kind is the common case.

## Checklist — scope

- Has anything outside the α scope (JSON Feed / mobile notifications) slipped
  in? (Note: the in-reader WebView article view *is* in scope — it's the shipped
  reader, `presentation/article/ArticleWebViewHtml.kt` (`:shared`) + `composewebview`.)
- Does the change contradict a decision recorded in the design docs? If a doc looks wrong, say so as
  a finding — do not silently accept the deviation.

## Investigation

    grep -rn "expect " shared/src/commonMain composeApp/src/commonMain
    grep -rn "java\.\(io\|awt\|sql\|nio\)\|javax\.swing" shared/src/commonMain composeApp/src/commonMain
