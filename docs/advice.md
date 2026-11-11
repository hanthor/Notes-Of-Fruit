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
`recordChangeTag` before submitting a modify, OR rely on the auto-retry on
CONFLICT (we do the latter). The post-create race is real and consistent.

**Write logs that show wire shape.** `summarizeBase64` in `NoteBodyEditor` dumps
ops/replicas/attr_runs in one line. That's how we caught most issues. When you
add a new field, add a corresponding summary line.

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
| Share OUT | Detail screen → share button → Android chooser appears |
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
├── client/                    AppleNotesClient: HTTP layer, fetchRecents/lookupNote/createNote/modifyNoteBody
├── proto/
│   ├── ProtobufWire.kt       Hand-rolled protobuf wire format (no schema dep)
│   ├── Gzip.kt               gzip + zlib detect/compress/decompress
│   ├── NoteBodyEditor.kt     Decode topotext.String → visible text + attribute runs
│   ├── NoteAppender.kt       Append / splice / setBody — produces new proto bytes
│   └── NoteCreator.kt        Build proto for a fresh note
└── ui/                       Compose UI: list, detail, FAB draft state, drawer, save flow

app/src/debug/java/com/example/applenotes/
└── debug/DebugReceiver.kt    Broadcast-driven CLI for testing without UI (debug builds only)
```

Read order if you're new:

1. `NoteBodyEditor.kt` — get a feel for what a substring tree looks like.
2. `AppleNotesClient.kt` — see the wire shape of every CloudKit op.
3. `NoteAppender.setBodySpliceBytes` — the slot-promotion pattern in detail.
4. The UI under `ui/` — the `ScreenState.Detail` state, `NoteDetailScreen`
   (including its `ON_PAUSE` auto-save observer) and the deferred-create FAB
   flow.

## Known limitations

- **Concurrent-edit conflicts**: the auto-retry resubmits the original body
  bytes, which last-writer-wins on a true concurrent edit. Safe for the
  post-create housekeeping race we hit in practice. The proper v2 fix is to
  decompose edits into char-ID-keyed ops and replay against a fresh base on
  conflict; the design is sketched in commit `0462bcb`'s discussion thread but
  not yet implemented.
- **Modern `MergableData` proto is not supported.** Newer Apple Notes (iOS 17+
  collaborative notes?) use a different shape. We refuse to operate on those —
  see `NoteBodyEditor.probe`. Read-only support would be a starting point.
- ~~**Image attachments display as `￼`.**~~ Resolved: image attachments are
  fetched and rendered inline; other attachment types (sketches, maps, etc.)
  still show a placeholder card. See the status table in the README.
- ~~**No formatting toolbar yet.**~~ Resolved: the format toolbar (paragraph
  styles, lists, inline B/I/U/S, links) shipped.
- **No subscription / push.** Polls when the user opens the app. Background sync,
  CloudKit subscriptions, and a send queue are all unimplemented.
