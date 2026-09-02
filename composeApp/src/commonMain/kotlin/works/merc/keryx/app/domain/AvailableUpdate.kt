package works.merc.keryx.app.domain

/**
 * A release [UpdateChecker] found to be newer than the running build, carried through every later
 * stage of an in-app update — from first spotted through downloading, verifying, and installing —
 * so the version/notes/plan stay visible the whole time.
 *
 * @param version The release version (`tag_name` with its leading `v`/`V` stripped).
 * @param releaseUrl The release page (`html_url`) — always shown as a fallback, and the only
 *   destination when [plan] is [UpdatePlan.OpenReleasePage] or [UpdatePlan.NotOffered].
 * @param releaseNotes The release's Markdown body, or `null` when GitHub didn't return one.
 * @param asset The release asset selected for this install form ([selectUpdateAsset]), or `null`
 *   when none applies here.
 * @param plan What an in-app update should do with [asset] here ([updatePlan]).
 */
data class AvailableUpdate(
    val version: String,
    val releaseUrl: String,
    val releaseNotes: String?,
    val asset: UpdateAsset?,
    val plan: UpdatePlan,
)
