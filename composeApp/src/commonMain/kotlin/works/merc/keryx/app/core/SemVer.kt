package works.merc.keryx.app.core

/**
 * SemVer comparison following prerelease precedence: `[remote] > [local]`? The numeric core
 * (`major.minor.patch`) is compared first, then a prerelease suffix (`-beta`, `-rc.1`, …) ranks
 * *below* the same core without one. An unparseable core (non-numeric segment) is treated as safely
 * "not newer" (returns false) rather than throwing — a malformed remote tag should never be
 * reported as an available update.
 */
/**
     * Determines whether the remote version is newer than the local version.
     *
     * @return `true` if the remote version compares greater than the local version, `false` otherwise.
     */
    internal fun isNewer(remote: String, local: String): Boolean =
    compareVersions(remote, local)?.let { it > 0 } ?: false

/**
 * Parses the numeric core (`major.minor.patch`) of a version string into a list of ints, or `null`
 * when any core segment is non-numeric (unparseable). Build metadata (`+...`) and any prerelease
 * suffix (`-...`) are stripped first, so `1.0.0-alpha+001` yields `[1, 0, 0]`.
 */
private fun parseCore(version: String): List<Int>? {
    val core = version.substringBefore('+').substringBefore('-').split(".").map { it.toIntOrNull() }
    return if (core.any { it == null }) null else core.map { it!! }
}

/**
 * Orders release versions for candidate selection.
 *
 * @param a The first nullable version to compare.
 * @param b The second nullable version to compare.
 * @return A negative value if `a` ranks lower than `b`, zero if they rank equally, or a positive value if `a` ranks higher than `b`.
 */
internal fun compareReleaseVersions(a: String?, b: String?): Int {
    val aOk = a != null && parseCore(a) != null
    val bOk = b != null && parseCore(b) != null
    return when {
        aOk && bOk -> compareVersions(a, b)!!
        aOk -> 1
        bOk -> -1
        else -> 0
    }
}

/**
 * Compares two version strings according to SemVer precedence.
 *
 * Build metadata is ignored, missing core components are treated as zero, and prerelease
 * versions rank below corresponding stable versions.
 *
 * @param a The first version string.
 * @param b The second version string.
 * @return A negative, zero, or positive value indicating the ordering, or `null` if either
 * version contains an invalid core component.
 */
private fun compareVersions(a: String, b: String): Int? {
    // SemVer §10: build metadata is ignored for precedence. Strip it first — it may follow either
    // the core (`1.0.0+001`) or the prerelease (`1.0.0-alpha+001`).
    val aClean = a.substringBefore('+')
    val bClean = b.substringBefore('+')
    val aCore = parseCore(a) ?: return null
    val bCore = parseCore(b) ?: return null

    val length = maxOf(aCore.size, bCore.size)
    for (i in 0 until length) {
        val ai = aCore.getOrElse(i) { 0 }
        val bi = bCore.getOrElse(i) { 0 }
        if (ai != bi) return ai.compareTo(bi)
    }

    // Cores equal → compare prerelease per SemVer: absence of a prerelease outranks its presence.
    val aPre = aClean.substringAfter('-', "")
    val bPre = bClean.substringAfter('-', "")
    if (aPre.isEmpty() && bPre.isEmpty()) return 0
    if (aPre.isEmpty()) return 1
    if (bPre.isEmpty()) return -1
    return comparePrerelease(aPre, bPre)
}

/**
 * Compares two dot-separated prerelease strings per SemVer §11: identifiers are compared field by
 * field; numeric identifiers compare numerically and rank below alphanumeric ones, alphanumeric
 * identifiers compare lexically (ASCII), and when all shared fields are equal the longer list wins.
 */
private fun comparePrerelease(a: String, b: String): Int {
    val aIds = a.split(".")
    val bIds = b.split(".")
    for (i in 0 until minOf(aIds.size, bIds.size)) {
        val aId = aIds[i]
        val bId = bIds[i]
        val aNum = aId.toIntOrNull()
        val bNum = bId.toIntOrNull()
        val cmp = when {
            aNum != null && bNum != null -> aNum.compareTo(bNum)
            aNum != null -> -1 // numeric ranks below alphanumeric
            bNum != null -> 1
            else -> aId.compareTo(bId)
        }
        if (cmp != 0) return cmp
    }
    return aIds.size.compareTo(bIds.size)
}

/**
     * Determines whether a version has a numeric major component below 1.
     *
     * @param version The version to evaluate.
     * @return `true` if the major component is below 1, `false` for null or unparseable versions.
     */
internal fun isBelowStable(version: String?): Boolean =
    (version?.substringBefore('.')?.toIntOrNull() ?: return false) < 1
