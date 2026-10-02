import Testing

/// `SettingsNavigation` decides which Settings tab a bell-row `ShowSettingsTab` action lands on —
/// including the ids this app has no tab for.
@MainActor
@Suite
struct SettingsNavigationTests {
    @Test
    func startsOnGeneral() {
        #expect(SettingsNavigation().selectedTab == SettingsNavigation.Tab.general)
    }

    @Test
    func showSelectsTheRequestedTab() {
        let navigation = SettingsNavigation()
        navigation.show(tabId: SettingsNavigation.Tab.cloudSync)
        #expect(navigation.selectedTab == SettingsNavigation.Tab.cloudSync)
        navigation.show(tabId: SettingsNavigation.Tab.data)
        #expect(navigation.selectedTab == SettingsNavigation.Tab.data)
    }

    @Test
    func showMapsUpdatesToCloudSync() {
        let navigation = SettingsNavigation()
        navigation.show(tabId: SettingsNavigation.Tab.updates)
        #expect(navigation.selectedTab == SettingsNavigation.Tab.cloudSync)
    }

    @Test
    func visibleTabKeepsEveryExistingTab() {
        let tabs = [
            SettingsNavigation.Tab.general, SettingsNavigation.Tab.notifications,
            SettingsNavigation.Tab.cloudSync, SettingsNavigation.Tab.data,
        ]
        for tab in tabs {
            #expect(SettingsNavigation.visibleTab(tab, cloudSyncAvailable: true) == tab)
        }
    }

    @Test
    func visibleTabFallsBackToGeneralWithoutACloudSyncTab() {
        #expect(
            SettingsNavigation.visibleTab(SettingsNavigation.Tab.cloudSync, cloudSyncAvailable: false)
                == SettingsNavigation.Tab.general
        )
    }

    @Test
    func visibleTabFallsBackToGeneralForAnUnknownId() {
        #expect(SettingsNavigation.visibleTab("nope", cloudSyncAvailable: true) == SettingsNavigation.Tab.general)
        #expect(
            SettingsNavigation.visibleTab(SettingsNavigation.Tab.updates, cloudSyncAvailable: true)
                == SettingsNavigation.Tab.general
        )
    }

    @Test
    func startsWithTheSheetDismissed() {
        #expect(!SettingsNavigation().isSheetPresented)
    }

    @Test
    func initialPathIsEmptyForGeneral() {
        #expect(SettingsNavigation.initialPath(SettingsNavigation.Tab.general, cloudSyncAvailable: true).isEmpty)
    }

    @Test
    func initialPathPushesAnyOtherVisibleTab() {
        for tab in [SettingsNavigation.Tab.notifications, SettingsNavigation.Tab.cloudSync, SettingsNavigation.Tab.data] {
            #expect(SettingsNavigation.initialPath(tab, cloudSyncAvailable: true) == [tab])
        }
    }

    @Test
    func initialPathIsEmptyForATabThatIsNotShown() {
        #expect(SettingsNavigation.initialPath(SettingsNavigation.Tab.cloudSync, cloudSyncAvailable: false).isEmpty)
        #expect(SettingsNavigation.initialPath("nope", cloudSyncAvailable: true).isEmpty)
    }

    @Test
    func initialPathForAnUpdatesRequestLandsOnCloudSync() {
        let navigation = SettingsNavigation()
        navigation.show(tabId: SettingsNavigation.Tab.updates)
        #expect(
            SettingsNavigation.initialPath(navigation.selectedTab, cloudSyncAvailable: true)
                == [SettingsNavigation.Tab.cloudSync]
        )
    }

    /// The File menu and an opened `.opml` file both land on the Data tab
    /// (`OpmlRequestPresenter`), on macOS and in the iOS sheet alike.
    @Test
    func anOpmlRequestLandsOnTheDataTab() {
        let navigation = SettingsNavigation()
        navigation.show(tabId: SettingsNavigation.Tab.data)
        #expect(navigation.selectedTab == SettingsNavigation.Tab.data)
        #expect(
            SettingsNavigation.initialPath(navigation.selectedTab, cloudSyncAvailable: false)
                == [SettingsNavigation.Tab.data]
        )
    }
}
