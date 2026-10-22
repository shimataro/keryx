package works.merc.keryx.app.platform

/** Desktop has no metered-network signal worth acting on; automatic network use is never restricted. */
actual fun platformNetworkConditionMonitor(): NetworkConditionMonitor = UnrestrictedNetworkConditionMonitor
