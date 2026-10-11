import Testing

@Suite struct NotificationAuthorizationPolicyTests {
    @Test func asksWhenEnabledAndSetupIsDone() {
        #expect(shouldRequestNotificationAuthorization(enabled: true, needsSetup: false))
    }

    @Test func doesNotAskDuringSetup() {
        #expect(!shouldRequestNotificationAuthorization(enabled: true, needsSetup: true))
    }

    @Test func doesNotAskWhenDisabled() {
        #expect(!shouldRequestNotificationAuthorization(enabled: false, needsSetup: false))
        #expect(!shouldRequestNotificationAuthorization(enabled: false, needsSetup: true))
    }

    @Test func doesNotAskBeforePreferencesLoad() {
        #expect(!shouldRequestNotificationAuthorization(enabled: nil, needsSetup: false))
    }
}
