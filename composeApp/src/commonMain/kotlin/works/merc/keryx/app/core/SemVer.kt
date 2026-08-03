package works.merc.keryx.app.core

/**
 * SemVer comparison following prerelease precedence: `[remote] > [local]`? The numeric core
 * (`major.minor.patch`) is compared first, then a prerelease suffix (`-beta`, `-rc.1`, …) ranks
 * *below* the same core without one. An unparseable core (non-numeric segment) is treated as safely
 * "not newer" (returns false) rather than throwing — a malformed remote tag should never be
 * reported as an available update.
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
 * Total ordering over release version strings for candidate selection. Unlike [isNewer] (a strict
 * boolean that returns `false` for both "equal" and "unparseable"), this distinguishes those cases:
 * two equal versions compare `0`, and an unparseable or absent version ranks *strictly below* any
 * parseable one. Without this consistency, a malformed tag preceding a valid release could stay
 * selected by `maxWithOrNull` and mask a genuine update.
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
 * Three-way SemVer comparison of two version strings (leading `v` already stripped by the caller).
 * Returns a negative/zero/positive Int like [Comparator], or `null` when either core has a
 * non-numeric segment (undeterminable → callers treat as "not newer"). Build metadata (`+...`) is
 * stripped and ignored for precedence per SemVer §10.
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
 * True when [version]'s major component is 0 (i.e. below 1.0.0). Unparseable or null versions
 * return false so an undeterminable version is never treated as pre-stable (safe: excluded from
 * pre-release eligibility rather than wrongly included).
 */
internal fun isBelowStable(version: String?): Boolean =
    (version?.substringBefore('.')?.toIntOrNull() ?: return false) < 1
