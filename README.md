# Notes of Fruit

An Android client for iCloud Notes. Reverse-engineered from CloudKit's private DB
API + Apple's `topotext` CRDT proto. Reads and writes notes that round-trip with
Mac's Apple Notes and iCloud.com.

This is a research codebase, not a polished product. The reverse-engineering
field notes for the next person (or agent) who picks it up live in
[`docs/`](docs/) — see [Documentation](#documentation).

## Screenshots
<img height="800" alt="Screenshot_1777485511" src="https://github.com/user-attachments/assets/58914e12-889b-44f5-8515-f842d12f4ac2" />

## Install

Grab a signed APK from the [Releases page](../../releases) and side-load it onto an
Android 8.0+ device. Each release's notes link back to the disclaimer below;
read it first. Each release also attaches a `.sha256` checksum of the APK and
lists the signing certificate's SHA-256.

> **Upgrading from v0.0.2 or earlier:** the application ID changed from
> `com.example.applenotes` to `io.github.hanthor.notesoffruit`. Android treats
> the new build as a different app, so it installs *next to* the old one
> instead of upgrading it. **Uninstall the old "Notes of Fruit" first**, then
> install the new APK and sign in again. Your notes live in iCloud; nothing is
> lost.

> **Note for early v0.0.1 users:** v0.0.1 was signed with an unstable CI key,
> so upgrading directly to v0.0.2+ will fail with "Something went wrong / app
> could not be installed." **Uninstall v0.0.1 first**, then install v0.0.2.
> From v0.0.2 onwards every release uses a pinned project-owned key, so
> future upgrades install cleanly.

### Updates with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) can install the app and
keep it updated straight from GitHub Releases:

1. In Obtainium, tap **Add App**.
2. Enter the repository URL `https://github.com/hanthor/Notes-Of-Fruit`
   (Obtainium picks GitHub as the source).
3. If it asks which file to use, pick the APK: release assets are named
   `notes-of-fruit-vX.Y.Z.apk` (you can set the APK filter to
   `notes-of-fruit-v.*\.apk` so the `.sha256` file is ignored).
4. Tap **Add**, then **Install**.

You can also turn on **Settings → Check for updates** in the app: once a day
it asks GitHub whether a newer release exists and shows a notice. It's off by
default and sends nothing but a plain request to `api.github.com`.

The app is not on F-Droid or IzzyOnDroid yet; see
[docs/distribution.md](docs/distribution.md).

If you'd rather build from source, see [Build / run](#build--run) further down.

## ⚠️ Use at your own risk

This is **alpha-quality software**. It might:

- corrupt, duplicate, or delete notes in your iCloud account
- drop formatting in subtle ways when round-tripping through Mac
- stop working at any time if Apple changes their API
- fail to handle modern collaborative notes (read-only at best)

It is **unaffiliated with Apple** — it talks to Apple's CloudKit Web Services
API as a third-party client using your own credentials. The proto formats are
inferred by observation, not from any Apple documentation. There is no
warranty of any kind. If you have notes you can't afford to lose, back them up
first or just don't use this app.

The first launch will block the UI behind a dialog you have to acknowledge so
you can't say you weren't warned.

## Status

| Capability | Status |
|---|---|
| Sign in with Apple ID (cookie-based) | Works |
| List notes (recents, paginated) | Works, with no cap: the list is cached on the device (Room) and shown at once on launch, then synced in the background ("Updating…"); pull-to-refresh, refresh on resume (at most once a minute, never offline) |
| Incremental sync (`changes/zone` + sync token) | Implemented defensively, **not yet verified against a real account**: any failure falls back to the full recents refetch, which also runs at least daily |
| Offline reading | Notes opened before can be opened without a network ("Offline — showing saved copy") |
| Search | Titles and snippets of every cached note, plus the text of notes opened before |
| Folder navigation (sidebar, trash filter) | Works; folders seen in a zone-changes sync stay listed even when empty |
| Read note content (decode topotext proto) | Works for `NOTE_STORE_PROTO`; not the modern `MergableData` shape |
| Append text | Works (round-trips with Mac) |
| Mid-text splice (insert/replace/delete) | Works (matches iCloud.com's slot-promotion pattern) |
| Create new note (FAB → save) | Code path works (deferred-create + v4 UUID); end-to-end Mac round-trip not yet user-confirmed |
| Auto-save | While typing (3 s idle, online), on back, and on `ON_PAUSE`. The draft is written to disk 1 s after typing stops and kept until iCloud confirms the save |
| Unsent drafts (outbox) | Pausing or leaving with unsaved changes hands the draft to a WorkManager job (network-constrained, exponential backoff) that saves it through the same conflict-merge path, new notes created exactly once; "N unsent changes" on the list retries. Reopening a note with an unsent draft offers "Restore unsaved changes?". Not yet exercised on a device |
| Share OUT (Android intent chooser) | Works — plain text plus an HTML version with the note's formatting (`EXTRA_HTML_TEXT`) |
| Share IN / "Add to Notes" text selection | Opens a prefilled, unsaved draft (text only; images not yet) |
| Launcher shortcuts | Static "New note"; the 4 most recently opened notes (title only); "Pin to home screen" from the editor |
| Quick Settings tile | "New note" (asks to unlock first; no lock-screen capture) |
| Note-taking role (`CREATE_NOTE`, Android 14+) | Declared; opens a new draft after unlock. Never shown over the lock screen |
| Export (Markdown / HTML) and Print / PDF | One note from the editor menu; a whole folder from the drawer (read-only, progress + cancel) |
| Import `.txt` / `.md` | Opens a draft; Markdown headings/lists/checkboxes/bold/italic become native formatting |
| Automation intent API (Tasker etc.) | Create / append / open; off by default, plus a per-app allow-list (Settings → Allowed apps; apps identified by package and signing certificate). Tasker / MacroDroid work on Android 11+, Automate on every version; see [docs/automation.md](docs/automation.md). Not yet tried on a device |
| Home-screen widget | "Recent notes" (Jetpack Glance): up to 8 titles from the local cache (never the network) and a New note button; a title opens the note. Shows "Locked" and only the button while the app lock is on, nothing after sign-out. Not yet tried on a device |
| On-device search (AppSearch) | Skipped on purpose: it would copy titles into a platform-wide index, see [docs/advice.md](docs/advice.md#entry-points-from-outside-the-app) |
| Files app / document picker | Read-only "Notes of Fruit" location (DocumentsProvider): folders → notes as Markdown (`.md`), generated on demand from the cache or one lookup; no write, delete or rename. Hidden while the app lock is on or signed out. Not yet tried on a device |
| `icloud.com/notes` links | Not yet |
| App lock | Optional (Settings, off by default): fingerprint / face / screen lock on cold start and after more than a minute in the background; while on, notes are hidden from Recents and screenshots (`FLAG_SECURE`) |
| Tablet / landscape list-detail layout | At 840 dp and wider (tablets in landscape, open foldables) the list and the open note sit side by side; picking another note saves the open one first, through the same path as Back. Not yet tried on a device |
| Accessibility / translation | Every user-visible string is in `res/values/strings.xml` (plurals for counts), ready for translations; formatting buttons and checklist boxes announce their on/off state, headings are marked as headings, touch targets are at least 48 dp. Not yet checked with TalkBack on a device |
| Update check | Optional (Settings, off by default): at most once a day asks GitHub for the latest release and shows "Version X is available". No account data or cookies are sent; debug builds never check |
| Diagnostic report | Settings → "Copy diagnostic report": versions, device model, signed in yes/no and counts only, for bug reports. Never note titles, text, Apple ID or cookies |
| Move notes between folders, folder create/rename/delete, restore from Recently Deleted, pinning | Not yet — needs writes to Apple's schema that haven't been observed |
| Delete | Uses CloudKit `forceDelete`; Mac surfaces as "Recently Deleted" (server-managed, not a hard wipe) |
| Format display (paragraph styles + inline) | Works — Title / Heading / Subheading / Body / Monospaced; Bulleted / Dashed / Numbered / Checkbox; bold / italic / underline / strikethrough; clickable links |
| Table attachments | Decoded from `MergeableDataEncrypted` (CRDT graph), rendered as a real grid; row/column order from `crRows`/`crColumns` (synthetic-tested only) |
| Image attachments | Apple's preview image inline, the original (Media reference) full screen on tap with pinch-zoom; decoded off the main thread at display size; cached on disk (100 MB, LRU). HEIC needs Android 9+ |
| Other attachments (sketches, scans, maps, etc.) | Apple's preview image with a type label when the record has one, otherwise a labeled placeholder card |
| "Updated on another device" | While a note is open, visible and online, its `recordChangeTag` is checked every 60 s (read-only lookup); a change shows a banner with Reload (only offered when there are no unsaved edits — otherwise saving merges) |
| Locked (password-protected) notes | Detected by the `Crypto*` fields on the record and shown read-only with "This note is locked with a password…" (plus the password hint if present). Never decrypted. Field names from public reverse-engineering, not verified on a real account; the list can't show a lock icon yet (it doesn't fetch those fields) |
| Removing attachments | Blocked: an edit that would delete an attachment (its `U+FFFC` character) is refused with "Attachments can't be removed from Android yet" (we can't verify that round-trips) |
| Format input toolbar | Works — paragraph style picker, list toggles (bullet/dash/numbered/checkbox), inline B/I/U/S, link |
| Tap-to-toggle checkbox | Works in formatted view (auto-saves) |
| Conflict UX | On CONFLICT/oplock the edit is three-way merged (line level) onto the fresh record and spliced onto its proto; overlapping edits ask "Keep both" / "Discard my changes" (unit-tested; not yet round-tripped against a real concurrent Mac edit) |

Known limitations are listed in [docs/advice.md](docs/advice.md#known-limitations).

## Build / run

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.hanthor.notesoffruit.debug/com.example.applenotes.MainActivity
```

Debug builds use the application ID `io.github.hanthor.notesoffruit.debug`
(label "Notes of Fruit (debug)"), so they can sit next to a release install
(`io.github.hanthor.notesoffruit`). The Kotlin packages and Gradle namespace
are still `com.example.applenotes`, which is why the activity's class name in
`am start -n` keeps that prefix. For a release build:

```bash
./gradlew :app:assembleRelease   # signed; see docs/signing.md
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n io.github.hanthor.notesoffruit/com.example.applenotes.MainActivity
```

Versions default to the values in `app/build.gradle.kts`; override them with
`-PversionName=0.1.2 -PversionCode=102`. The release workflow derives both from
the tag (`vX.Y.Z` → name `X.Y.Z`, code `X*10000 + Y*100 + Z`).

You'll need an Android emulator or device with Google Play Services. Sign in
with an Apple ID that has Notes enabled in iCloud. The first launch goes through
the WebView auth flow.

Tests and lint (the same checks CI runs):

```bash
./gradlew :core:test :cli:test :app:testDebugUnitTest :app:lintDebug
```

Debug builds also include an adb-driven `DebugReceiver` for exercising the
iCloud operations without the UI; see [docs/advice.md](docs/advice.md#what-works-and-how-to-verify).
Protos it dumps can be inspected on your computer with `nof-cli`
(`./gradlew :cli:installDist`; summary, substring tree, attribute runs,
structural diff, invariant check, tables): see [docs/cli.md](docs/cli.md).

The build has three modules: `:app` (Android), `:core` (the protocol code,
plain Kotlin/JVM, `./gradlew :core:test`) and `:cli` (`nof-cli`).

## Contributing

PRs and improvements are very welcome! Bug reports are OK, but I'd much rather
have a bug report _with an associated PR fixing the issue_ 😉 — this is a
weekend project I'm not on call for. The reverse-engineering surface area is
huge (substring CRDT, MergeableData CRDT, table object graph, image fetch
pipeline, paragraph + inline attribute_runs) and there's a long tail of edge
cases waiting to be discovered. If you investigate one, please write down what
you find in the relevant file under [`docs/`](docs/) so the next person doesn't
have to rediscover it.

Read [CONTRIBUTING.md](CONTRIBUTING.md) first: it covers the A/B-against-
iCloud.com method, capturing a note's proto as a test fixture (and scrubbing
it), running tests and lint, and the round-trip check expected for anything
that touches writes.

## Documentation

- [docs/protocol.md](docs/protocol.md) — how iCloud Notes works: the CloudKit
  layer, the `topotext` body proto, CRDT semantics, compression, special
  characters, paragraph styles, inline formatting, tables, images, the v4
  replica UUID finding, and the "ENCRYPTED" field names.
- [docs/dead-ends.md](docs/dead-ends.md) — what we tried that failed.
- [docs/advice.md](docs/advice.md) — advice for the next person, how to verify
  what works (including `DebugReceiver` usage), a code tour, and known
  limitations.
- [docs/design-decisions.md](docs/design-decisions.md) — the original design
  decisions (attribute_runs vs Markdown, auto-save, new-note flow, …).
- [docs/cli.md](docs/cli.md) — `nof-cli`, the offline inspector for captured
  protos (summary, tree, runs, diff, invariant check, tables).
- [docs/automation.md](docs/automation.md) — the automation intent API
  (create / append / open notes from Tasker-style apps).
- [docs/signing.md](docs/signing.md) — how releases are signed and how to
  rotate the key.
- [docs/distribution.md](docs/distribution.md) — F-Droid / IzzyOnDroid
  readiness.
- [PLAN.md](PLAN.md) — points at the roadmap issue.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

## Disclaimer

This project is unaffiliated with Apple. It uses Apple's public CloudKit Web
Services API with the user's own credentials. It reads and writes notes the
authenticated user already has access to. The proto formats were inferred from
observation of round-tripping the user's own data through their own iCloud
account; no Apple-internal documentation or reverse-engineered binaries were
consulted.

If you're going to publish anything based on this, be respectful. Don't redistribute
captured proto data from other users' accounts. Don't ship anything that would
encourage account credential sharing.
