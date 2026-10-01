import KeryxShared
import SwiftUI

/// Maps a SwiftUI `KeyPress` onto the shared `HomeKey`/`KeyModifiers` pair `HomeShortcuts.kt`
/// resolves through, so keyboard handling stays defined once in `:shared` rather than
/// reimplemented per UI (`docs/app-architecture.md`'s "Per UI" keyboard-handling note).
///
/// `.delete` is macOS's physical Backspace key (labelled "delete" on a Mac keyboard); `.deleteForward`
/// is fn+Delete — mapped to `HomeKey.backspace`/`.delete` respectively, matching the Compose
/// desktop app's own `Key.Backspace`/`Key.Delete` naming.
func mapKey(_ press: KeyPress) -> (HomeKey, KeyModifiers)? {
    let modifiers = KeyModifiers(
        shift: press.modifiers.contains(.shift),
        ctrl: press.modifiers.contains(.control),
        meta: press.modifiers.contains(.command)
    )
    let key: HomeKey
    switch press.key {
    case .escape: key = .escape
    case .upArrow: key = .up
    case .downArrow: key = .down
    case .leftArrow: key = .left
    case .rightArrow: key = .right
    case .pageUp: key = .pageUp
    case .pageDown: key = .pageDown
    case .home: key = .home
    case .end: key = .end
    case .space: key = .space
    case .return: key = .enter
    case .delete: key = .backspace
    case .deleteForward: key = .delete
    case "j": key = .j
    case "k": key = .k
    case "f": key = .f
    case "r": key = .r
    default: key = .other
    }
    return (key, modifiers)
}
