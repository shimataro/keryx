import SwiftUI

/// Shown in place of the normal UI when `KeryxSdk.start` throws — currently only
/// `DatabaseTooNewException` (this device's `keryx.db` was already migrated by a newer build; see
/// `docs/db-schema.md`'s schema-version guard). Desktop shows the same failure as a message box
/// before exiting (`DatabaseTooNewDialog.kt`); this is the SwiftUI equivalent.
struct StartupErrorView: View {
    let error: (any Error)?

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
            Text(L("database_too_new_title"))
                .font(.headline)
            // The one documented failure (`DatabaseTooNewException`) always gets this message —
            // matches desktop's own `DatabaseTooNewDialog.kt`, which never shows the raw exception
            // text either.
            Text(L("database_too_new_message"))
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
    }
}
