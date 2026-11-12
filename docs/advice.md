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
ops/replicas/attr_runs in one line. That's how we caught most issues. When you
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

Watch with `adb logcat -s AppleNotesClient:* AppleNotesDebug:*`.

## Code tour

```
app/src/main/java/com/example/applenotes/
├── auth/                      WebView cookie harvest, session refresh, DeviceIdentity (v4 UUID!)
├── automation/                Automation intent API (docs/automation.md): parsing, gate, receiver, OPEN_NOTE activity
├── client/                    AppleNotesClient: HTTP layer, fetchRecents/lookupNote/createNote/modifyNoteBody
│   └── NotesError.kt         Typed failures (AuthExpired, Offline, Conflict, Throttled, …) + mapping
├── data/
│   ├── NotesApi.kt           Interface over the client calls the UI uses (+ fakes in tests)
│   ├── NotesRepository.kt    App-scoped session, cached notes/folders, save/delete flow
│   ├── AppGraph.kt           Lazy singleton: one HttpClient, repository, alpha gate
│   ├── TextMerge.kt          Line-level three-way merge for save conflicts
│   ├── NoteRebase.kt         Merge formatting, splice the merge onto the fresh proto, "Keep both"
│   ├── Connectivity.kt       Online/offline flow (ConnectivityManager)
│   └── NoteBody.kt           decodeNoteBody(): Editable vs ReadOnly(reason)
├── export/                   Attribute runs → HTML / Markdown, Markdown → spans (import), SAF export, print
├── integrations/             Share-in / PROCESS_TEXT / shortcuts / QS tile / notes-role entry (IntegrationEntryActivity)
├── log/AppLog.kt              The only logcat access; release builds log no note content
├── proto/
│   ├── ProtobufWire.kt       Hand-rolled protobuf wire format (no schema dep)
│   ├── Gzip.kt               gzip + zlib detect/compress/decompress
│   ├── NoteBodyEditor.kt     Decode topotext.String → visible text + attribute runs
│   ├── NoteAppender.kt       Append / splice / setBody — produces new proto bytes
│   ├── NoteCreator.kt        Build proto for a fresh note
│   └── MergeableDataDecoder.kt  Table attachments (MergeableData CRDT graph)
└── ui/
    ├── AppleNotesApp.kt      Screen switch
    ├── AppViewModel.kt       Navigation, sign-in, list, folder selection
    ├── list/                 Notes list + folder drawer
    ├── detail/               NoteDetailViewModel, editor, formatting toolbar, attachments
    ├── login/                WebView sign-in
    ├── settings/             Settings screen (automation toggle)
    └── common/               Shared screens, snackbar helper, alpha dialog

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
5. `src/test/.../proto/TopotextTestSupport.kt` — the invariant checker every
   write-path test runs; use it for any new proto code.

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
  kept. Drafts live in memory and the detail `SavedStateHandle` only; there
  are no on-disk drafts yet, so force-stopping the app loses an unsaved
  edit.
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
  `lookupNote` has no `desiredKeys`). Background sync, CloudKit
  subscriptions, and a send queue are all unimplemented.
