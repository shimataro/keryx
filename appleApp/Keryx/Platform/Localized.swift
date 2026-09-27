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

/// `L(key)` with `String(format:)` substitution — for a resource whose value carries `%1$@`/`%1$lld`
/// -style placeholders. Used instead of relying on SwiftUI's automatic `Text` plural/argument
/// matching, which only works when the catalog's own key textually contains the format specifier;
/// this catalog's keys are opaque identifiers instead (see `apple_add_feed_partial_result`'s own
/// comment in `strings.xml` for the one case — a two-argument plural — this sidesteps entirely).
func LF(_ key: String, _ args: CVarArg...) -> String {
    String(format: L(key), arguments: args)
}
