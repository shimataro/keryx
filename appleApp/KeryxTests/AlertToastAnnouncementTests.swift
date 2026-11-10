import Testing

@MainActor
struct AlertToastAnnouncementTests {
    @Test func announcesOnlyWhileActiveAndNotCoveredBySettings() {
        #expect(AlertToastAnnouncement.canAnnounce(sceneActive: true, settingsSheetPresented: false))
        #expect(!AlertToastAnnouncement.canAnnounce(sceneActive: false, settingsSheetPresented: false))
        #expect(!AlertToastAnnouncement.canAnnounce(sceneActive: true, settingsSheetPresented: true))
        #expect(!AlertToastAnnouncement.canAnnounce(sceneActive: false, settingsSheetPresented: true))
    }

    @Test func marksAnnouncedOnlyAfterTheToastHasBeenShown() async {
        var shown: [String] = []
        var marked = 0

        await AlertToastAnnouncement.announce(
            "Sync failed",
            for: .milliseconds(10),
            show: { shown.append($0) },
            markSurfaced: { marked += 1 }
        )

        #expect(shown == ["Sync failed"])
        #expect(marked == 1)
    }

    @Test func aCancelledToastLeavesTheAlertPending() async {
        var marked = 0
        let task = Task { @MainActor in
            await AlertToastAnnouncement.announce(
                "Sync failed",
                for: .seconds(60),
                show: { _ in },
                markSurfaced: { marked += 1 }
            )
        }

        task.cancel()
        await task.value

        #expect(marked == 0)
    }
}
