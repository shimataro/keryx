package works.merc.keryx.app.platform

/** Android draws no merged title bar, so there is never an inset to reserve. */
actual object WindowChrome {
    actual var titleBarInsetDp: Float = 0f
}
