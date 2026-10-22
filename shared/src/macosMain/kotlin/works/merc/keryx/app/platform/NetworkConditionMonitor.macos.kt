package works.merc.keryx.app.platform

/** The Mac app is treated like desktop: automatic network use is never restricted. */
actual fun platformNetworkConditionMonitor(): NetworkConditionMonitor = UnrestrictedNetworkConditionMonitor
