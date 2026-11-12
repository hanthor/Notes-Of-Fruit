# Advice, verification and code tour

Moved here from the README. See also [protocol.md](protocol.md),
[dead-ends.md](dead-ends.md) and [../CONTRIBUTING.md](../CONTRIBUTING.md).

## Advice for the next person/agent

**Use iCloud.com web for A/B comparisons.** When something doesn't work, the
fastest way to figure out what Apple expects is to make iCloud.com produce the
same operation on a parallel test note, then diff the bytes. We caught the v4
UUID issue this way after exhausting other theories.

**Sample multiple healthy notes before drawing conclusions.** Bisha and Money
maker (Mac-created, alive) gave us a baseline. ic-create-test-001
(iCloud.com-created, alive) gave us another. Newtest1-4 and Made-in-android
(Android-created, trashed) showed the failure mode. The trashed ones had
attributes the alive ones never had — but only the UUID was a *consistent*
discriminator across all of them.

**Don't trust a single failed test.** Mac trashes records asynchronously, on
notesync, on quit/reopen — sometimes minutes after creation. A note that "looks
fine right after save" may still get trashed an hour later when Mac next runs
notesync. Wait, then re-check.

**The CRDT is real.** char-IDs are stable across remote edits. Insertions slot
in by parent char-ID, not position. Tombstones are commutative. If you're
writing new code that touches the proto, encode operations as `(parentCharID,
chars)` or `(charIDs to tombstone)`, not as positional diffs. This makes the
v2 conflict-resolution work (re-splice on stale base) trivial — see the
"What this means for our code" discussion in commit history.

**Match iCloud.com's payload shape, not just the field names.** When in doubt,
look at what iCloud.com sends. We removed `PaperStyleType` / `AttachmentViewType`
/ `Deleted` from `createNote` because iCloud.com doesn't send them — server
defaults handle it. Sending zeros made the new record look "edited" and racy.

**Pre-flight checks before edits.** Always re-`lookupNote` to get a fresh
`recordChangeTag` before submitting a modify, OR handle CONFLICT (we do the
latter: `NotesRepository.modifyWithRebase` re-applies the edit to the fresh
record, never re-sends the stale bytes). The post-create race is real and
consistent; there base == remote, so the merge is trivial.

**Write logs that show wire shape.** `summarizeBase64` in `NoteBodyEditor` dumps
ops/replicas/attr_runs in one line (`nof-cli summary` prints the same line
for a capture). That's how we caught most issues. When you
add a new field, add a corresponding summary line. Log through `log/AppLog`
only: wire-shape dumps, note text and response bodies go to `AppLog.v`, which
is off unless a debug build turns it on (any `DebugReceiver` broadcast does).
Release builds log no note content at all.

**Don't speculate about validation rules in production.** Apple's clients run
validation we can't see. The only authoritative way to know if a write is
accepted is to watch what Mac does to it over time. Build the iteration loop
short — `adb broadcast` → read response → `lookupNote` again — and you'll find
issues fast.

**Read [design-decisions.md](design-decisions.md) before adding new features.**
It records decisions that are not obvious from the code (markdown vs
attribute_runs, lifecycle-only auto-save, empty-body new-note flow). Don't
relitigate without reason. (These used to live in `PLAN.md`.)

## What works (and how to verify)

| What | Test |
|---|---|
| Read existing Mac notes | Open any note in the app — body should match Mac |
| Append edit round-trips | Add text on Android → save → check Mac after relaunch |
| Mid-text splice round-trips | Edit existing text on Android → save → verify on Mac |
| Create new note with v4 UUID | Tap FAB → type → back → verify note appears (and stays) on Mac |
| Auto-save | Type, swipe back without explicit save → reopen → text persists |
| Share OUT | Detail screen → share button → Android chooser appears; a rich-text target (e.g. Gmail) keeps headings/bold/lists |
| Share IN | Share text from another app → "New note" → editor opens prefilled, unsaved; back saves |
| Import | Drawer → Import → pick a `.md` → draft shows native formatting; save, check on Mac that no `#`/`**` appears |
| Export | Editor menu → Export as Markdown/HTML; drawer → Export folder… (one file per note) |
| Folder filter | Drawer → select folder → list filters; trash hidden by default |

The `DebugReceiver` exposes the same code paths via `adb` broadcasts. It only
exists in **debug builds** ([`app/src/debug/`](../app/src/debug/)) and only
accepts broadcasts from senders holding `android.permission.DUMP`, which
`adb shell` has and ordinary apps don't (the reasoning is in
[`app/src/debug/AndroidManifest.xml`](../app/src/debug/AndroidManifest.xml)).
The full action list is in the KDoc of
[`DebugReceiver.kt`](../app/src/debug/java/com/example/applenotes/debug/DebugReceiver.kt);
[CONTRIBUTING.md](../CONTRIBUTING.md) shows how to capture a note's proto with
`DUMP_BY_TITLE`.

```bash
# Debug builds install as io.github.hanthor.notesoffruit.debug. The action
# names keep the old com.example.applenotes prefix (they are just strings).
PKG=io.github.hanthor.notesoffruit.debug

# List all notes
adb shell am broadcast -p $PKG -a com.example.applenotes.LIST

# Look up a specific note by title
adb shell am broadcast -p $PKG -a com.example.applenotes.LOOKUP_BY_TITLE --es title 'foo'

# Append to an existing note
adb shell am broadcast -p $PKG -a com.example.applenotes.APPEND_BY_TITLE \
  --es title 'foo' --es text 'extra line'

# Create a new note
adb shell am broadcast -p $PKG -a com.example.applenotes.CREATE \
  --es title 'newnote' --es body 'hello'
```

Watch with `adb logcat -s AppleNotesClient:* AppleNotesDebug:*`. To look
at a dumped proto (`DUMP_BY_TITLE` logs it as base64 chunks), save the log
and give it to [`nof-cli`](cli.md) instead of decoding the chunks by hand:

```bash
adb logcat -d -s AppleNotesDebug:* > dump.log
cli/build/install/nof-cli/bin/nof-cli summary dump.log   # after ./gradlew :cli:installDist
cli/build/install/nof-cli/bin/nof-cli diff before.log after.log
```

## Code tour

Three Gradle modules. `:core` is plain Kotlin/JVM (no Android): the protocol
code, tested with `./gradlew :core:test`. `:app` is the Android app and
depends on it. `:cli` is [`nof-cli`](cli.md), an offline inspector for
captured protos built on `:core`. Package names didn't change when the code moved, so imports
read the same in both.

```
core/src/main/kotlin/com/example/applenotes/        (:core, pure Kotlin/JVM)
├── auth/ICloudSession.kt     ICloudSession, LENIENT_JSON, USER_AGENT; ReplicaUuid.kt (v4 UUID helpers)
├── client/                   AppleNotesClient: HTTP layer, fetchRecents/lookupNote/createNote/modifyNoteBody
│   ├── NotesError.kt         Typed failures (AuthExpired, Offline, Conflict, Throttled, …) + mapping
│   └── ZoneChanges.kt        changes/zone response parser
├── log/CoreLog.kt            Logging seam: a sink the host installs (the app: AppLog; default: no-op)
└── proto/
    ├── ProtobufWire.kt       Hand-rolled protobuf wire format (no schema dep)
    ├── Gzip.kt               gzip + zlib detect/compress/decompress
    ├── NoteBodyEditor.kt     Decode topotext.String → visible text + attribute runs
    ├── NoteAppender.kt       Append / splice / setBody — produces new proto bytes
    ├── NoteCreator.kt        Build proto for a fresh note
    ├── MergeableDataDecoder.kt  Table attachments (MergeableData CRDT graph)
    └── TopotextInvariants.kt Independent reader + the invariant checker every write-path test runs
core/src/testFixtures/        Fx / testUuid / loadFixture + captured protos (shared with :app's tests)

cli/src/main/kotlin/com/example/applenotes/cli/     (:cli, nof-cli; docs/cli.md)
├── NofCli.kt                 Argument parsing + summary / tree / runs / text / diff / check / table / raw
├── ProtoInput.kt             Raw / gzip / zlib / base64 / DebugReceiver logcat capture → protobuf bytes
└── ProtoDiff.kt              Char-ID-keyed structural diff of two bodies

app/src/main/java/com/example/applenotes/           (:app)
├── NotesOfFruitApp.kt        Application: installs the CoreLog → AppLog bridge
├── auth/                      WebView cookie harvest, session refresh, DeviceIdentity (stores the v4 UUID!)
├── automation/                Automation intent API (docs/automation.md): parsing, gate, receiver, OPEN_NOTE activity
├── data/
│   ├── NotesApi.kt           Interface over the client calls the UI uses (+ fakes in tests)
│   ├── NotesRepository.kt    App-scoped session, cached notes/folders, sync (full / incremental), save/delete flow, outbox
│   ├── local/                On-device store (Room): note summaries, folders, sync token, opened bodies, unsent drafts
│   ├── AppGraph.kt           Lazy singleton: one HttpClient, repository, alpha gate
│   ├── TextMerge.kt          Line-level three-way merge for save conflicts
│   ├── NoteRebase.kt         Merge formatting, splice the merge onto the fresh proto, "Keep both"
│   ├── Connectivity.kt       Online/offline flow (ConnectivityManager)
│   ├── AttachmentCache.kt    Disk cache for attachment images (LRU, 100 MB) + small LRU map
│   ├── AttachmentAssets.kt   CKAsset / PreviewImages JSON → download URL + cache key
│   └── NoteBody.kt           decodeNoteBody(): Editable vs ReadOnly(reason)
├── documents/                Read-only DocumentsProvider (folders → notes as Markdown); NoteDocuments: id scheme + tree
├── export/                   Attribute runs → HTML / Markdown, Markdown → spans (import), SAF export, print
├── integrations/             Share-in / PROCESS_TEXT / shortcuts / QS tile / notes-role entry (IntegrationEntryActivity);
│                             LocalSurfaces: refreshes widget + documents provider when cache / sign-in / app lock change
├── log/AppLog.kt              The only logcat access; release builds log no note content (CoreLogBridge feeds :core into it)
├── security/                 Optional app lock: policy (timeout), DataStore toggle, BiometricPrompt, lock screen
├── sync/                     DraftSyncer (+ WorkManager DraftSyncWorker): background saves of unsent drafts
├── update/                   Opt-in daily GitHub release check (own HTTP client, no cookies) + list banner
├── diagnostics/              "Copy diagnostic report": versions + counts only, never note content
├── widget/                   "Recent notes" home-screen widget (Glance); WidgetContent decides what it may show
└── ui/
    ├── AppleNotesApp.kt      Screen switch (one pane, or list + note side by side ≥ 840 dp: TwoPane.kt)
    ├── AppViewModel.kt       Navigation, sign-in, list, folder selection, two-pane note switching
    ├── list/                 Notes list + folder drawer
    ├── detail/               NoteDetailViewModel, editor, formatting toolbar, attachments
    │                         (AttachmentImageLoader / ImageDecoding: off-main-thread, downsampled;
    │                          AttachmentGuard: no deleting attachments; RemoteChangeWatcher)
    ├── login/                WebView sign-in
    ├── settings/             Settings screen (automation, app lock, update check, diagnostic report)
    └── common/               Shared screens, snackbar helper, alpha dialog, UiText (translatable
                              text built outside Compose)

app/src/debug/java/com/example/applenotes/
└── debug/DebugReceiver.kt    Broadcast-driven CLI for testing without UI (debug builds only)
```

Read order if you're new:

1. `NoteBodyEditor.kt` — get a feel for what a substring tree looks like.
2. `AppleNotesClient.kt` — see the wire shape of every CloudKit op.
3. `NoteAppender.setBodySpliceBytes` — the slot-promotion pattern in detail.
4. `NotesRepository.saveNote` — the deferred-create draft flow and the
   modify path; then `NoteDetailViewModel` (draft state, auto-save) and
   `AppViewModel.createNote` (the FAB).
5. `core/src/main/.../proto/TopotextInvariants.kt` — the invariant checker
   every write-path test runs; use it for any new proto code.

### Entry points from outside the app

Every system entry point (share sheet, "Add to Notes", launcher shortcuts,
the QS tile, the notes role) goes through `integrations/IntegrationEntryActivity`,
which turns the intent into an `IncomingRequest` and hands it to
`AppViewModel.handleIncoming` via the in-process `PendingIncoming` holder.
Requests wait (also in the SavedStateHandle) until the user is signed in and
on the list. New drafts open *prefilled but unsaved* (`DraftPrefill`), so the
only write path is still the editor's `NotesRepository.saveNote`. Each
exported component's reasoning is in comments in `AndroidManifest.xml`.
Because `createNote` writes plain text only, a draft that carries formatting
(toolbar before the first save, or an imported Markdown file) gets one
follow-up modify after the create to apply its attribute runs
(`NoteDetailViewModel.save`, `formattingPending`).

The home-screen widget (`widget/RecentNotesWidget`, Jetpack Glance) uses the
same entry activity: "New note" sends `NEW_NOTE`, a title sends the token-
checked "open note" request the shortcuts use. What it shows is decided by
`WidgetContent.of` (unit-tested): nothing but "Locked" while the app lock is
on, nothing after sign-out, otherwise up to 8 titles from the local cache;
it never touches the network. `integrations/LocalSurfaces` refreshes it when
`NotesRepository.notes` (every sync, save, delete and sign-out) or the app
lock setting changes; that observer is the only hook.

The documents provider (`documents/NotesDocumentsProvider`) shows a
read-only "Notes of Fruit" location in the system file picker: `root` →
`folder:<recordName>` → `note:<recordName>` (ids checked against the
recordName shape), each note a `text/markdown` file rendered on open with
the Export as Markdown converter, from the body cached when the note was
last opened or one `lookupNote` (`NotesRepository.noteForReading`). Only
cleanly decoded bodies are served (the editor's best-effort fallback can
contain deleted text); locked notes never. No document has a write,
delete, rename or create flag. It is guarded by `MANAGE_DOCUMENTS`; it has
no roots, and every call fails, while the app lock is on or nobody is
signed in, and `LocalSurfaces` notifies root and listing changes on
sign-in, sign-out, lock changes and cache updates. The id scheme, the tree
and the Markdown glue are unit-tested (`NoteDocumentsTest`); the provider
itself has not run on a device yet.

**On-device search (AppSearch) is skipped on purpose.** Putting note titles
into AppSearch's platform storage would make them visible to system search
surfaces outside the app, which conflicts with the app lock (titles would
have to be removed from the index whenever the lock is turned on, and the
index can't tell whether the app is unlocked) and with keeping note text
inside the app. Search inside the app covers the cache already.

## Reaching Reminders (not implemented)

Apple Reminders is not in the Notes data this app reads. What is known, and
what isn't:

- Notes lives in the `com.apple.notes` CloudKit container, private database,
  zone `Notes` (see [protocol.md](protocol.md)). Reminders are **not** records
  in that zone.
- Modern (iOS 13+ "upgraded") Reminders sync through CloudKit too, in a
  different container. Its identifier, zone names, record types and field
  encoding have **not been observed** by this project. Older accounts used
  CalDAV, which is a different protocol altogether.
- Unknown: whether the web session cookies this app harvests are accepted for
  the Reminders container; whether the setup/bootstrap call returns a
  Reminders endpoint the same way it does for Notes; whether Reminders
  fields are encrypted/compressed like `TextDataEncrypted`.
- How to find out, the same way Notes was done: open Reminders on iCloud.com
  in a desktop browser, record its network traffic, and A/B a change (create,
  complete, edit a reminder) against the requests. Write findings into
  protocol.md before writing code.

## Known limitations

- **App lock is a privacy screen, not encryption.** The optional app lock
  (`security/`) hides the UI behind the system fingerprint / face / screen-lock
  prompt, keeps notes out of Recents thumbnails and blocks screenshots
  (`FLAG_SECURE`), and hides widget titles and DocumentsProvider roots while
  on. It does not encrypt anything: the Room cache, on-disk drafts and the
  WebView cookie jar sit in app-private storage like any other app's data, and
  the prompt is not bound to a `CryptoObject` (CodeQL's "insecure local
  authentication" finding), so someone who can hook the app process (root,
  instrumentation) can skip it. Real protection would mean encrypting the
  cache and drafts with a Keystore key that requires user authentication and
  unwrapping it through the prompt's `CryptoObject`. Costs: `BIOMETRIC_STRONG`
  only (weak face unlock can't be offered), the PIN/pattern fallback with a
  crypto object only on Android 11+, and background draft sync can't read
  drafts while the app is locked.
- **Concurrent-edit conflicts** (issue #4): on CONFLICT the save looks the
  note up again and runs a three-way merge (`data/TextMerge.kt`: base = what
  the user started from, local = the draft, remote = the fresh text). The
  merge is **line level** and conservative: edits to different lines merge;
  both sides touching the same line (including both appending at the end) is
  a conflict, and the editor asks "Keep both" (the user's version is appended
  under a marked separator, attachments in that copy become `[attachment]`
  text) or "Discard my changes". Nothing is written until the user picks.
  A clean merge is spliced onto the *fresh* proto one local change at a time
  (`NoteRebase.rebaseBody`), so the other device's char IDs survive.
  Formatting is merged per char: the user's spans where they changed them,
  the remote's elsewhere. At most 3 writes per save. Still a text-level
  rebase, not the char-ID-keyed op replay the CRDT would allow, and not yet
  round-tripped against a real concurrent Mac/iPhone edit.
- **Errors and sessions** (issue #11): failures are typed
  (`client/NotesError.kt`); an expired session is refreshed from the cookie
  jar once, then the user is sent to sign in with the open note and draft
  kept.
- **Local cache and sync** (issue #12): note summaries, folders, the zone
  sync token and the records of opened notes live in a Room database
  (`data/local/`, schema exported to `app/schemas/`; never add a
  destructive migration, it would delete unsent drafts). Launch shows the
  cache, then syncs. The incremental path (`AppleNotesClient.fetchZoneChanges`,
  `POST …/changes/zone`, parsed by `ZoneChangesParser`, which also accepts a
  batched `zones: [...]` answer) is **untested against a real account**:
  request and response shapes come from Apple's CloudKit Web Services
  reference, the tests use synthetic fixtures. Any error on that path
  (BAD_REQUEST, ZONE_NOT_FOUND, an expired token, missing `syncToken`,
  unsupported, or a batch that would remove more than half the cached
  notes) drops the token and does the full recents refetch, which pages
  with no cap (≈13 notes per request, so a large first sync takes a while;
  the first page shows at once) and prunes only after a complete pass. A
  full refetch also runs at least every 24 h. A token is "primed" after
  each full refetch by enumerating the zone without one; if the server
  refuses that, refreshes simply stay full refetches. Records of other types
  (Attachment, Media, …) in a changes response are ignored. Search covers
  all cached titles/snippets and opened notes' bodies (SQLite `LIKE`, so
  case-insensitive for ASCII only). At most 300 bodies are cached.
- **Unsent drafts** (issue #13): the editor writes its draft to the outbox
  (`pending_draft` table: base record, text, spans) 1 s after typing stops,
  autosaves after 3 s idle when online, and hands unsaved changes to
  `DraftSyncWorker` on pause / leave. Saves of one note are serialised in
  the repository; a draft's created recordName is stored the moment
  `createNote` returns, so the worker and the editor never create a note
  twice (the window between the server creating it and the app storing
  the mapping — a crash there — can still duplicate, as before). A conflict
  found in the background is never resolved there: the draft is flagged
  and the editor offers it on the next open, ending in Keep both /
  Discard. Sign-out deletes the cache, drafts (including unsent ones) and
  the attachment disk cache; signing in to a *different* Apple ID after a
  session expired does the same. Decoded images already in memory stay
  until the process ends. Nothing of this has run on a device or against
  iCloud yet.
- **Modern `MergableData` proto is not supported.** Newer Apple Notes (iOS 17+
  collaborative notes?) use a different shape. We refuse to operate on those —
  see `NoteBodyEditor.probe`. Read-only support would be a starting point.
- ~~**Image attachments display as `￼`.**~~ Resolved: image attachments are
  fetched (preview inline, original on tap), cached on disk and rendered
  inline; other attachment types (sketches, maps, etc.) show Apple's preview
  image when the record has one, else a placeholder card. See the status
  table in the README.
- ~~**No formatting toolbar yet.**~~ Resolved: the format toolbar (paragraph
  styles, lists, inline B/I/U/S, links) shipped.
- **No subscription / push.** Polls when the user opens the app, plus a
  once-a-minute `lookupNote` of the open note while its screen is visible
  (`RemoteChangeWatcher`; it fetches the whole record, body included, since
  `lookupNote` has no `desiredKeys`). Background sync of the list and
  CloudKit subscriptions are unimplemented; unsent edits do go out in the
  background (see "Unsent drafts").
