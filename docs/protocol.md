# Protocol field notes

How iCloud Notes stores and syncs a note, as far as we have worked it out by
observation. Moved here from the README. See also
[dead-ends.md](dead-ends.md) and [advice.md](advice.md).

## The most important thing we think we learned (not yet end-to-end confirmed)

**Strong hypothesis: Apple's replica UUID must be RFC 4122 v4.** This is the
last byte-level difference between an Android-created note that got trashed
(`ab-fresh-162944`) and an iCloud.com-created note that survived (`ic-create-test-001`).
Both had identical proto structure, same fields, same compression. Only the
UUID's version nibble differed.

When we created a note from Android with our previous (raw-random) UUID, Mac's
notesync added housekeeping fields, **left the body proto bytes intact**, and
**silently set `Deleted=1`** within a few minutes — the note appeared in
Recently Deleted on Mac. iCloud.com-created notes (which use v4 UUIDs) survive
the same flow.

**Caveat**: we shipped the v4 UUID fix in commit `3ee1a60` but haven't yet
confirmed end-to-end that a v4-UUID-created note survives Mac quit/reopen. If
you pick this up and v4 turns out not to be the only thing Mac validates, the
next things to investigate are (a) the substring tree shape (iCloud.com always
emits multiple substrings with tombstone separators around line breaks; we emit
one substring), (b) attribute_runs count (iCloud.com splits at trailing `\n`;
we don't), and (c) the per-replica `counter2` field (iCloud.com uses a
non-trivial value; we use 1).

`SecureRandom().nextBytes(16)` produces raw bytes — only ~1/16 chance the version
nibble is 4. Use `java.util.UUID.randomUUID()`, or set the bits explicitly:

```kotlin
val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()  // version = 4
bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()  // variant = 10
```

See [DeviceIdentity.kt](../app/src/main/java/com/example/applenotes/auth/DeviceIdentity.kt)
and commit `3ee1a60`.

## How Apple Notes works (the parts that matter)

### CloudKit layer

- Endpoint: `https://p<N>-ckdatabasews.icloud.com.cn` for Chinese accounts, `.com`
  otherwise. Path: `/database/1/com.apple.notes/production/private/...`.
- Auth: cookie-based (`X-APPLE-WEBAUTH-TOKEN`). We harvest cookies via Android's
  `WebView` flow in [`auth/`](../app/src/main/java/com/example/applenotes/auth/).
- Zone: `Notes`. Record types: `Note`, `Folder`, `SearchIndexes`.
- Required fields on `Note` create: `TextDataEncrypted`, `TitleEncrypted`,
  `SnippetEncrypted`, `Folder` (CKReference), `CreationDate`, `ModificationDate`.
- **Date trap**: omit `ModificationDate` and CloudKit defaults it to .NET
  `DateTime.MinValue` (`-62135769600000` ms — year 1). Mac silently skips records
  with that sentinel. Always send `System.currentTimeMillis()`.
- `recordChangeTag` is CloudKit's optimistic-concurrency token. Mac's notesync
  bumps it asynchronously after merging — expect `CONFLICT`/`oplock` errors on
  edits that follow a create. We auto-retry once with a fresh tag.

### Listing notes and folders

- `recordType=Note` query works.
- `recordType=Folder` query is **rejected** by the server: "Type is not marked
  indexable: Folder". Use `/records/lookup` with recordNames pulled from notes'
  `Folder` references instead.
- `SearchIndex` "recents" index exists and works. "folders" returns "No index of
  this name exists".
- Special folder recordNames: `DefaultFolder-CloudKit` ("Notes"),
  `TrashFolder-CloudKit` ("Recently Deleted").
- Notes in trash live under `Folder = TrashFolder-CloudKit`. The `Deleted` field
  is added by Mac housekeeping; don't filter by it. Filter by Folder ref.

### The body proto: `topotext.String`

`TextDataEncrypted` is gzipped (or zlibbed) protobuf. The shape:

```
versioned_document.Document {
  Version version = 2 {
    int32 minimumSupportedVersion = 2;
    bytes data = 3;     // encoded topotext.String
  }
}

topotext.String {
  string string = 2;             // the visible text, concatenation of live substrings
  repeated Substring substrings = 3;
  VectorTimestamp timestamp = 4; // per-replica clocks
  repeated AttributeRun attributeRuns = 5;
}

Substring {
  CharID charID = 1;       // (replicaID, clock) — globally unique
  uint32 length = 2;
  CharID timestamp = 3;    // for tombstone fresh-ts ordering
  bool tombstone = 4;
  repeated int32 child = 5; // forward links into substrings[]
}

CharID {
  uint32 replicaID = 1;    // 1-based index into VectorTimestamp.clock
  uint32 clock = 2;        // Lamport
}
```

Invariants (verified by sampling iCloud.com-created and Mac-created notes):

- The substring array always starts with **doc-start** (`charID=(0,0)`,
  `length=0`) and ends with the **sentinel** (`charID=(0,0xFFFFFFFF)`,
  `length=0`). Don't reorder.
- Children are forward links into the array. The walk produces the visible-order
  chain: doc-start → ... → sentinel.
- New inserts go between doc-start and sentinel; tombstones flip `tombstone=true`
  but stay in the tree.

### CRDT semantics in practice

- Each character ever inserted has a globally-unique `(replicaID, clock)` ID.
  `replicaID=1` is whoever wrote *this* version of the proto; the same UUID may
  be at a different slot in someone else's proto.
- **Slot rotation** matters: when iCloud.com edits a note, it always remaps
  itself to slot 1, demoting everyone else. Mac's notesync uses the rotation as
  a "refresh local cache" signal. We mirror this in
  [`NoteAppender.setBodySpliceBytes`](../app/src/main/java/com/example/applenotes/proto/NoteAppender.kt).
- `ReplicaIDToNotesVersionDataEncrypted` is the per-replica version vector. Mac
  uses changes to this field as the cache-invalidation signal. Pass it through
  unchanged on modify (don't strip or rewrite). Without this, Mac duplicates the
  body on multi-substring notes after Android edits.

### Compression

Both gzip (`1f 8b`) and zlib (`78 9c`) are accepted. iCloud.com sends zlib for
short notes; Mac sends gzip with OS=0x13. We send gzip with OS=0xff (Java
default). All three round-trip cleanly. Compression format is **not** the cause
of Mac trashing — we ruled this out by sampling.

### Special characters

- `U+2028` (LINE SEPARATOR) and `U+2029` (PARAGRAPH SEPARATOR) appear in Mac
  notes for soft line breaks inside lists/checkboxes. Don't strip them.
- `￼` (U+FFFC OBJECT REPLACEMENT) is Apple's placeholder for inline attachments.
  We pass through but don't render the attachment.

### Paragraph styles (`AttributeRun.f2.f1`)

| Code | Style |
|---|---|
| 0 | Title (also: implicit for the first line) |
| 1 | Heading |
| 2 | Subheading |
| 3 | Body (also: f1 absent → Body) |
| 4 | Monospaced |
| 100 | Bulleted list |
| 101 | Dashed list |
| 102 | Numbered list |
| 103 | Checkbox list |

(The earlier draft of this README listed 0=Body / 1=Title / 101=Checkbox. Wrong:
verified by sampling a baseline note that contained one of every style.)

Checkbox checked-state lives at `AttributeRun.f2.f5` — a 20-byte sub-message
containing `{f1: 16-byte UUID, f2: 0|1 (done flag)}`, NOT in `AttributeRun.f2.f4`.

### Inline formatting (siblings of `AttributeRun.f2`)

| Field | Meaning |
|---|---|
| `f5` (varint) | font weight enum: 1=Bold, 2=Italic, 3=BoldItalic |
| `f6` (varint) | underlined (bool) |
| `f7` (varint) | strikethrough (bool) |
| `f9` (bytes) | link URL (UTF-8 string) |
| `f12` (msg) | attachment_info `{f1: UUID-string, f2: UTI-string}` (table/image/etc.) |

Decode via [`NoteBodyEditor.parseAttributeRuns`](../app/src/main/java/com/example/applenotes/proto/NoteBodyEditor.kt).
Re-encode via [`NoteBodyEditor.encodeAttributeRunField`](../app/src/main/java/com/example/applenotes/proto/NoteBodyEditor.kt).

### Table attachments

Tables are stored as separate `Attachment` CKRecords (UTI
`com.apple.notes.table`). Their content lives in `MergeableDataEncrypted` — a
zlib-deflate'd protobuf encoding Apple's MergeableData CRDT graph. The graph
nodes form a typed object soup:

- **KeyItems** (field 4): string field names — `self`, `crRows`, `crColumns`,
  `cellColumns`, `crTableColumnDirection`, `identity`, `UUIDIndex`.
- **TypeItems** (field 5): string type names — `com.apple.CRDT.NSString`,
  `com.apple.CRDT.NSUUID`, `com.apple.notes.ICTable`, etc. Indexed.
- **UUIDItems** (field 6): 16-byte UUIDs. Indexed.
- **GraphObjects** (field 3): repeated, indexed. Each is one of:
  - `f1` List, `f6` Dictionary, `f10` String (with f2 = current text),
    `f13` CustomMap (typed map, like ICTable), `f16` OrderedSet.

The root ICTable's CustomMap has entries `crRows` (OrderedSet of row UUIDs),
`crColumns` (OrderedSet of column UUIDs), and `cellColumns` (Dict of
col_uuid → Dict of row_uuid → string_obj_id). Walk those to extract a 2D
grid; resolve each string_obj_id to its NSString.currentText.

See [`MergeableDataDecoder.kt`](../app/src/main/java/com/example/applenotes/proto/MergeableDataDecoder.kt).
The decoder orders rows and columns by the `crRows`/`crColumns`
OrderedSets, matching each entry to `cellColumns` by UUID; rows listed in
`crRows` with no cells render as empty rows. If any piece of that can't be
resolved, it falls back to `cellColumns` iteration order for columns and
first-appearance order for rows, which matches the visual order for tables
created left-to-right top-to-bottom. The OrderedSet handling is based on this
README's structure and public reverse-engineering work, tested on synthetic
graphs only — it still needs checking against a real table that has had rows
or columns moved.

### Image attachments

Image Attachment records carry a `Media` field that's a CKReference to a
separate "Media" record. Look up the media record; its `Asset` (or
`MediaEncrypted`) field is a CKAsset whose `value.downloadURL` is the binary's
signed download URL. GET it with the session's cookies.

## A note on "ENCRYPTED" field names

CloudKit serializes `TitleEncrypted`, `SnippetEncrypted`, and `TextDataEncrypted`
as `ENCRYPTED_STRING` / `ENCRYPTED_BYTES` types — but the actual byte values
sent over the wire are just **base64-encoded UTF-8** (for Title/Snippet) and
**gzipped protobuf** (for TextData). There is no client-side encryption layer
involved. The names appear to be a CloudKit historical artifact; iCloud's E2EE
("Advanced Data Protection") is a separate keystore mechanism we're not
exercising.

If you `kotlin.io.encoding.Base64.decode(titleField.value).decodeToString()`
you get the cleartext title. That's the entire "decryption" step.
