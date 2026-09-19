package works.merc.keryx.app.platform

/**
 * Whether the app is running on macOS. Used by common UI code that needs to follow a platform
 * convention (e.g. `Return` vs. `F2` for rename), not just by desktop-only integrations.
 */
expect val isMacOs: Boolean

/**
 * Whether touch is this platform's primary pointer input, rather than a precise mouse. Gates the
 * feed list's drag-to-reorder affordance (a `Modifier.dragHandle` icon reserved on each draggable
 * row, so the row's own band stays scrollable — see `ui/home/FeedListDragGestures.kt`) and,
 * indirectly, which context-menu trigger `nativeContextMenu` responds to (long-press vs.
 * right-click, decided per platform inside each `actual`, not by this flag).
 */
expect val isTouchPrimary: Boolean

/**
 * Whether the platform provides its own always-visible application menu bar (macOS's screen menu
 * bar, the desktop `AppMenuBar`/KDE Global Menu on Windows/Linux — see `app-architecture.md`'s
 * "Desktop Tray" section for the Linux D-Bus paths). When `false`, screens must offer their own
 * in-pane entry points to actions the menu bar would otherwise be the only way to reach —
 * currently Settings and About (see `FeedListPane`'s settings footer row / `GeneralTab`), and
 * (`FeedListToolbarRow`) an `app_name` title in the header space a settings icon used to occupy.
 *
 * **iOS will also resolve `false` here** — it has no menu bar either — so this flag is currently
 * an Android-specific stand-in for "no native app menu", not a true "does this platform have one"
 * predicate. The two decisions it drives today (the header title, the settings footer) both need
 * their own, more specific flag once iOS lands, since iOS's own conventions for reaching Settings
 * and naming a screen are not the same as Android's. `isTouchPrimary` (below) has no such problem:
 * its uses (swipe, row height, Snackbar, bottom sheets) are genuinely about touch vs. a precise
 * pointer, which iOS shares with Android.
 */
expect val hasNativeAppMenu: Boolean

/**
 * Whether the platform provides a desktop-style system tray (`tray/KeryxTray.kt`) that the app can
 * minimize into. Android has no tray equivalent — its background story relies on the OS
 * notification dot instead (see `background-update.md`) — so UI gated on this must not offer a
 * "minimize to tray" concept there.
 */
expect val hasSystemTray: Boolean

/**
 * Whether the platform already shows its own "copied to clipboard" confirmation UI, so the app
 * must not show a redundant one of its own on top of it. `true` only on Android API 33+ (Google's
 * own guidance: https://developer.android.com/develop/ui/views/touch-and-input/copy-paste —
 * starting with Android 13, the system shows a visual confirmation whenever an app writes to the
 * clipboard, and an app's own confirmation UI becomes a duplicate). `false` everywhere else —
 * desktop, and Android below API 33, both of which get no such system-level feedback at all.
 */
expect val platformShowsOwnCopyConfirmation: Boolean

/**
 * The CPU architecture this build is running on, as far as [works.merc.keryx.app.domain.UpdateAsset]
 * needs to know which release asset to download. Not a general-purpose ABI abstraction — it exists
 * solely to pick the right suffix among the assets `release.yml` publishes per architecture (today,
 * only Linux ships more than one: `-linux-x86_64.zip` and `-linux-arm64.zip`).
 */
enum class HostArchitecture { X86_64, ARM64, UNKNOWN }

/**
 * Detected once per process. On desktop this reads `os.arch`; on Android, [android.os.Build.SUPPORTED_ABIS].
 * [UNKNOWN] means this build genuinely has no matching release asset (a 32-bit host, for instance) —
 * [works.merc.keryx.app.domain.selectUpdateAsset] must then find nothing rather than guess.
 */
expect val hostArchitecture: HostArchitecture
