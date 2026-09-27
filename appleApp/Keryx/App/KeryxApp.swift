import SwiftUI

@main
struct KeryxApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            Group {
                if let home = model.home {
                    HomeView(home: home)
                } else {
                    StartupErrorView(error: model.startupError)
                }
            }
        }
    }
}
