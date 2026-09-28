import Foundation

/// Looks up `key` in the String Catalog generated from `composeResources/values*/strings.xml`
/// (`:composeApp:generateStringCatalog`) — the catalog's top-level keys are exactly the Android
/// resource `name`s, so `key` here must match one of those, never a literal display string (see
/// `CLAUDE.md` constraint #3 / `docs/build.md`'s "String Catalog for the Apple app").
///
/// Returns a plain `String` deliberately, not a `LocalizedStringKey`: every call site below passes
/// the result into a view's `String`-taking initializer, which displays it verbatim rather than
/// attempting a second, unrelated table lookup.
func L(_ key: String) -> String {
    String(localized: String.LocalizationValue(key))
}

/// `L(key)` with argument substitution — for a resource whose value carries `%1$@`/`%1$lld`-style
/// placeholders, **including** a `<plurals>` resource: the generated String Catalog converts those
/// into genuine plural variations (`GenerateStringCatalogTask` in `composeApp/build.gradle.kts`),
/// and `NSLocalizedString(key, comment:)` + `String.localizedStringWithFormat` — Apple's own
/// documented idiom for a plural-aware lookup (see "Localizing strings that contain plurals") — is
/// what actually selects the right one for `args`' value, unlike plain `String(format:)`, which
/// only ever substitutes into whichever single form the catalog happened to store. Used instead of
/// relying on SwiftUI's automatic `Text` plural/argument matching, which only works when the
/// catalog's own key textually contains the format specifier; this catalog's keys are opaque
/// identifiers instead.
func LF(_ key: String, _ args: CVarArg...) -> String {
    let format = NSLocalizedString(key, comment: "")
    switch args.count {
    case 1: return String.localizedStringWithFormat(format, args[0])
    case 2: return String.localizedStringWithFormat(format, args[0], args[1])
    default: return String(format: format, arguments: args)
    }
}
