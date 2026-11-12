# Dead ends

Moved here from the README. See also [protocol.md](protocol.md).

## What we tried that failed

These were dead ends; documenting so you don't repeat them.

- **Markdown rendering as a stopgap.** Tempting because it's easy, but Apple's
  notes don't have markdown syntax in their body bytes — they have
  `attributeRuns` metadata. Rendering markdown on the Android side would mean
  literal `**bold**` showing up everywhere on Mac. Use attribute_runs, even with
  partial coverage.
- **Empty `createNote(title="", body="")` then `modifyNoteBody`.** This races
  Mac's notesync housekeeping and produces records that get trashed within
  minutes. Even with the v4 UUID fix this is risky. Defer createNote until the
  first save with content (we do; see commit `55704ae`).
- **Querying for folders by `recordType=Folder`.** Server rejects with "Type is
  not marked indexable: Folder". Use `/records/lookup` with recordNames pulled
  from notes' Folder refs.
- **Trusting CloudKit's `Deleted` field as the trash discriminator.** Mac
  housekeeping adds it, but it's also present on alive notes. Filter by Folder
  ref pointing at `TrashFolder-CloudKit` instead.
- **`fetchRecents(1)`.** The server returns 0 results for tiny limits even when
  notes exist. Use ≥ 50.
- **Driving the FAB via `adb shell input tap` coordinates.** Brittle — emulator
  scaling means coords drift between sessions. Use `uiautomator dump` if you
  must, or just exercise the same code paths via `DebugReceiver` broadcasts.
- **Recreating the device's replica UUID per session.** Apple has a per-note
  replica cap and your registry will explode. Persist once, reuse forever.
- **Skipping `ReplicaIDToNotesVersionDataEncrypted` on modify.** Mac duplicates
  the body locally after multi-substring edits. Always pass it through.
