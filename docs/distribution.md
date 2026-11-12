# Distribution: F-Droid / IzzyOnDroid readiness

Status as of the change that added this file. The app is currently distributed
only through GitHub Releases (side-load, or [Obtainium](../README.md#updates-with-obtainium)).

## Done

- **Licence.** The project is Apache-2.0 ([LICENSE](../LICENSE)). Both F-Droid
  and IzzyOnDroid require a recognised free licence.
- **Application ID.** `io.github.hanthor.notesoffruit`, a reverse-DNS name the
  project controls, instead of `com.example.applenotes` (which F-Droid and
  IzzyOnDroid would not accept).
- **Versioning.** `versionName`/`versionCode` are derived from `vX.Y.Z` tags,
  so update checkers can map tags to versions.
- **No proprietary dependencies.** No Google Play Services, Firebase or
  analytics libraries in the build.

## Blockers left

- **Signing key handling for reproducible builds.** F-Droid either signs with
  its own key (then F-Droid builds can't update GitHub installs, and vice
  versa) or, for reproducible builds, verifies that its build matches our
  signed APK and ships our signature. The second needs a stable, private
  release key; today's key is committed to the repo
  ([signing.md](signing.md)), so anyone can sign "official" APKs. Rotating to
  a private key forces every user to reinstall once. Reproducibility of the
  release build (R8, resource shrinking, baseline profiles) has not been
  checked either.
- **No anti-features review yet.** The app talks to Apple's proprietary
  iCloud / CloudKit service, so on F-Droid it would carry at least the
  **NonFreeNet** anti-feature. Nobody has reviewed the rest of the list
  (e.g. whether anything else applies) or written the metadata (summary,
  description, screenshots, changelogs under `fastlane/`).
- IzzyOnDroid would pick up the GitHub release APK directly, so for it the
  remaining work is mainly the anti-feature declaration and a request to the
  repository maintainer; the committed signing key is still worth fixing
  first, since IzzyOnDroid pins the signing certificate.
