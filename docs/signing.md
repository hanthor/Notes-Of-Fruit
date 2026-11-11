# Release signing

## How releases are signed today

Release APKs are signed by the `releaseAlpha` signing config in
[`app/build.gradle.kts`](../app/build.gradle.kts). It picks the key like this:

1. If the environment variable `NOF_KEYSTORE_PATH` is set (and not empty), it
   signs with that keystore, using `NOF_KEYSTORE_PASSWORD`, `NOF_KEY_ALIAS` and
   `NOF_KEY_PASSWORD` (the key password defaults to the store password). The
   build fails if the path is set but the password or alias is missing.
2. Otherwise it signs with the committed alpha keystore,
   [`app/keystore/release.keystore`](../app/keystore/release.keystore)
   (alias `notes-of-fruit`, password `notesoffruit`).

The committed keystore and its password are public **on purpose**: this is an
alpha research project, and anyone can rebuild and sign an APK that upgrades
cleanly over an official release. That also means anyone can sign an APK that
Android will accept as an "update" to yours. Treat release APKs from anywhere
other than this repository's Releases page as untrusted.

The release workflow ([`.github/workflows/release.yml`](../.github/workflows/release.yml))
prints the signing certificate's SHA-256 in each release's notes and attaches a
`.sha256` checksum of the APK.

## Rotating to a private key (owner)

Do this once, when the project is ready to stop sharing its signing key.

1. Generate a new key **on your own machine** (never in CI, never committed):

   ```bash
   keytool -genkeypair -v \
     -keystore notes-of-fruit-private.jks \
     -alias notes-of-fruit \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

   Back the `.jks` file and its passwords up somewhere safe. If it is lost, no
   future release can upgrade existing installs.

2. Add these repository secrets (Settings, Secrets and variables, Actions):

   | Secret | Value |
   |---|---|
   | `NOF_KEYSTORE_BASE64` | `base64 -w0 notes-of-fruit-private.jks` |
   | `NOF_KEYSTORE_PASSWORD` | the store password |
   | `NOF_KEY_ALIAS` | `notes-of-fruit` (or whatever alias you used) |
   | `NOF_KEY_PASSWORD` | the key password (may equal the store password) |

   The release workflow decodes `NOF_KEYSTORE_BASE64` into a temporary file and
   points `NOF_KEYSTORE_PATH` at it. If the secret is absent it keeps using the
   committed keystore, so nothing changes until you add it.

3. Tag the next release as usual. Check that the certificate SHA-256 in the
   release notes is the new one.

4. Optionally delete `app/keystore/release.keystore` afterwards (and the
   fallback branch in `app/build.gradle.kts`), so local release builds can no
   longer produce APKs that claim to be the official app. Local builds would
   then need the `NOF_*` variables set.

To sign locally with the private key:

```bash
NOF_KEYSTORE_PATH=/path/to/notes-of-fruit-private.jks \
NOF_KEYSTORE_PASSWORD=... NOF_KEY_ALIAS=notes-of-fruit NOF_KEY_PASSWORD=... \
  ./gradlew :app:assembleRelease
```

## What a key change means for users

Android only installs an update if it is signed with the same certificate as
the installed app. Changing the key therefore has the same effect as the v0.0.1
to v0.0.2 key change and the application ID change:

- The first release signed with the new key **cannot be installed over** an
  older release. Users must uninstall the old app first, then install the new
  APK and sign in to iCloud again. Notes are stored in iCloud, so nothing is
  lost, but the app gets a new replica UUID.
- Say this prominently in that release's notes and in the README "Install"
  section.
- Obtainium and similar tools will report a signature mismatch and stop
  updating until the user reinstalls.

Android's APK Signature Scheme v3 supports key rotation with a signing
lineage (`apksigner rotate`), which lets new-key APKs upgrade old installs on
Android 9+. It is not wired up here; if the owner wants it, the lineage file has
to be created with both the old and the new key and passed to `apksigner`
after Gradle builds the APK.
