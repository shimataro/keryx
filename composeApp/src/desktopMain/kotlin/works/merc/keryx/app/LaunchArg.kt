package works.merc.keryx.app

/** A recognized, actionable command-line argument (or single-instance-forwarded value). */
internal sealed interface LaunchArg {
    /** A `keryx://` OAuth redirect URI. */
    data class OAuthCallback(val uri: String) : LaunchArg

    /** The filesystem path of an `.opml` file to import. */
    data class OpmlFile(val path: String) : LaunchArg
}

/**
 * Classifies a raw launch argument (from `argv` or forwarded via [SingleInstanceCoordinator]).
 *
 * @param arg The raw argument to classify.
 * @return The recognized [LaunchArg], or `null` if [arg] doesn't match anything Keryx handles.
 */
internal fun classifyLaunchArg(arg: String): LaunchArg? = when {
    arg.startsWith("keryx://") -> LaunchArg.OAuthCallback(arg)
    arg.endsWith(".opml", ignoreCase = true) -> LaunchArg.OpmlFile(arg)
    else -> null
}
