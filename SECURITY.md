# Security Policy

[日本語](SECURITY.ja.md)

Keryx is a local-first, open-source RSS reader. Because it handles your feed data
and — if you opt in — cloud-storage credentials, we take security reports
seriously and appreciate the effort of anyone who helps keep Keryx safe.

The technical claims in this document — how Keryx handles your data and
credentials — can be verified directly against the source code at
<https://github.com/shimataro/keryx>; the process and disclosure commitments below
are policy, not code. See also [PRIVACY.md](PRIVACY.md) for what data the app
handles and where it goes.

## Supported Versions

Keryx is still pre-1.0 and under active development. Only the **latest released
version** receives security fixes; there are no long-term support branches, and
older releases are not patched. If you are affected by a security issue, first
update to the newest release from the
[Releases page](https://github.com/shimataro/keryx/releases).

| Version | Supported |
| --- | --- |
| Latest release | ✅ |
| Any older release | ❌ |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues, pull requests, or any public channel.**
Public disclosure before a fix is
available puts users at risk.

Instead, report privately through **GitHub's private vulnerability reporting**:

1. Go to the repository's [Security tab](https://github.com/shimataro/keryx/security)
2. Click **Report a vulnerability** to open a private advisory visible only to the maintainer and you.

### What to include

To help us triage and fix the issue quickly, please include as much of the
following as you can:

- A clear description of the vulnerability and its potential impact.
- The affected version, platform (Windows / macOS / Linux), and configuration
  (e.g. whether cloud sync was enabled and with which provider).
- Step-by-step reproduction instructions, and a proof of concept if possible.
- Any relevant logs, stack traces, or screenshots.
- Suggested remediation, if you have one.

## What to Expect

- **Acknowledgement:** we aim to acknowledge your report within a few days.
- **Assessment:** we will investigate, confirm the issue, and keep you informed
  of progress.
- **Fix & disclosure:** once a fix is ready, we will release it and, with your
  consent, credit you in the advisory. We follow a **coordinated disclosure**
  model — please give us a reasonable window to release a fix before any public
  disclosure.

As this is a volunteer-maintained open-source project, timelines are best-effort.
Thank you for your patience and for reporting responsibly.

## Scope

Security issues in Keryx itself are in scope, for example:

- Mishandling or leakage of OAuth tokens or other cloud-storage credentials.
- Flaws in the sync / merge logic that could corrupt or expose a user's data.
- Parsing vulnerabilities in feed (RSS / Atom), OPML, or HTML handling.
- Unsafe handling of feed content, URLs, or redirects.
- Any way for a malicious feed, sync file, or crafted input to compromise the
  user's device or data.

The following are **out of scope**:

- Vulnerabilities in third-party dependencies — please report those upstream to
  the respective project (see [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md)).
  You may still let us know so we can bump the dependency.
- Vulnerabilities in Dropbox, Google Drive, or the operating system's own
  credential storage — report those to the respective vendor.
- Issues that require a device already compromised by an attacker with local
  access or elevated privileges.
- Missing hardening that has no demonstrable security impact.

## Security Model (for reference)

Keryx is designed to minimize its attack surface:

- **No accounts and no developer-operated server.** There is no backend the
  developer controls; the app talks only to the feeds you subscribe to and, if you
  opt in, directly to Dropbox or Google Drive.
- **Cloud credentials** (OAuth access / refresh tokens) are stored in the
  operating system's secure credential storage (Keychain on macOS, Credential
  Manager on Windows, Secret Service on Linux), falling back to a permission-
  restricted (`0600`) local file only when the OS store is unavailable.
- **OAuth** uses the authorization-code flow with PKCE, performed directly between
  your device and the provider — no credentials pass through any developer server.
- **Local data** (subscriptions, cached articles, settings) stays on your device
  unless you explicitly enable cloud sync.
- **In-app update downloads are verified, but not authenticated to a publisher
  identity.** When Keryx offers to download and install an update in-app (see
  `docs/background-update.md`'s "In-App Update" for which platforms/install forms
  this applies to), the downloaded file is checked against the SHA-256 digest the
  GitHub Releases API itself reports for that asset before anything is installed,
  over an HTTPS connection to an allowlisted GitHub host only. This detects
  transport corruption and a tampered-in-transit download, but **it does not
  verify who published the release** — the digest is computed and served by the
  same GitHub Releases API the asset itself comes from, so if the GitHub
  account/token used to publish Keryx releases were ever compromised, a
  substituted asset and its digest would still match each other. Trust in a
  release's authenticity currently rests on GitHub account security (2FA, token
  scoping) and HTTPS/TLS to GitHub, the same as any other software distributed
  without a separate, independently-verifiable release signature. A stronger
  guarantee (e.g. a detached minisign/cosign signature published alongside each
  release, with the verifying public key embedded in the app) is a considered
  future improvement, not yet implemented.
- **On macOS, an extracted update also passes a code-signature self-consistency
  check** (`codesign --verify --strict --deep`) before it is swapped into place —
  this catches an extracted bundle whose signed contents were altered or
  corrupted after signing, independent of the digest check above. It is **not** a
  publisher-identity check: current release builds are signed ad-hoc rather than
  with a Developer ID certificate and notarized, so there is no certificate chain
  to verify the signer against (`codesign --verify -R "anchor apple generic and
  certificate leaf[subject.OU] = <team id>"` would reject every ad-hoc-signed
  release unconditionally, including legitimate ones). Tightening this to an
  actual publisher check is planned once releases are signed with a real
  Developer ID and notarized. For the same ad-hoc-signing reason, the self-replace
  script also strips any `com.apple.quarantine` flag from the new bundle before
  relaunching it — an ad-hoc signature gives Gatekeeper nothing to clear a
  quarantine flag against, so leaving one in place could block the relaunch.
- **The macOS bundle is unpacked with `ditto`, not in process.** A signed bundle's
  `CodeResources` seals the symbolic links in its bundled JDK *as links*, and
  `java.util.zip` cannot tell a stored link from a regular file — so an in-process
  extraction flattens them and the check above rejects the result. Extraction
  therefore hands off to `ditto -x -k`, with `ZipExtractor.validate` run first so the
  zip-slip, entry-count and uncompressed-size limits still apply to an extraction
  `ditto` performs with no limits of its own. A stored link's *target* cannot be
  pre-checked (the same blind spot), so the extracted tree is walked afterwards
  (`verifyExtractedTree`): every symlink is resolved **through the filesystem** and
  rejected unless it stays inside the destination, and entry count and byte total are
  re-checked against what actually landed. Resolving through the filesystem rather
  than textually is what makes it sound — a `..` that follows another symlink
  collapses against the link, not against what the link points at, so two entries
  would otherwise be enough to look contained while pointing outside. `ditto` itself
  is *not* that guard: it declines to *traverse* links, which is a different property
  from declining to *create* one that points outside — it creates such a link and
  exits 0. What `ditto` does contribute is normalizing a `..` entry **name** into the
  destination, which is the only defense against something written *outside* the
  destination, since a walk that starts there cannot see it. The code-signature check
  is **not** a line of defense against an escape at all: it inspects the bundle
  directory only, so an entry written *beside* the bundle is never looked at, and it
  is a self-consistency check, so an attacker able to produce the whole archive could
  ad-hoc sign their own bundle. It detects modification *inside* the bundle, which is
  what it is there for.

For the complete data-handling description, see [PRIVACY.md](PRIVACY.md).
