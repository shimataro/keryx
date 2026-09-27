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
 * @param installable Whether [plan] can actually be carried out *right now* — [UpdatePlan.isInstallable]
 *   folded together with the platform `actual`'s own live [UpdateInstaller.canInstall] answer (e.g.
 *   Android's install-unknown-apps consent), resolved once when this update was found
 *   ([nextStateAfterCheck]) so the Updates tab and the tray read the exact same fact
 *   [UpdateRepository.startDownload] gates on, rather than [plan] alone — which can look
 *   installable while the platform would refuse it. Defaults to [UpdatePlan.isInstallable] for
 *   callers (mostly tests) that don't care about that distinction.
 */
data class AvailableUpdate(
    val version: String,
    val releaseUrl: String,
    val releaseNotes: String?,
    val asset: UpdateAsset?,
    val plan: UpdatePlan,
    val installable: Boolean = plan.isInstallable,
)
