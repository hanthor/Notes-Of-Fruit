# Contributing to Notes of Fruit

Thanks for helping. This app writes to people's real iCloud accounts using a
format Apple has never documented, so the bar for changes is "we have evidence
Apple's own clients accept this", not "it looks right in our app". This guide
explains how to get that evidence.

Background reading: [docs/protocol.md](docs/protocol.md) (how notes are
stored), [docs/dead-ends.md](docs/dead-ends.md) (what not to try again),
[docs/advice.md](docs/advice.md) and
[docs/design-decisions.md](docs/design-decisions.md).

**Use a throwaway Apple ID or a test folder you don't care about.** Never
develop against notes you can't afford to lose.

## Building, testing, linting

```bash
./gradlew :app:assembleDebug                         # debug APK
./gradlew :app:testDebugUnitTest :app:lintDebug      # what CI runs on every PR
```

- Unit tests live in `app/src/test/`. Proto fixtures go in
  `app/src/test/resources/`.
- Lint runs with `abortOnError = true`: any lint *error* fails CI. Warnings
  don't, but don't add new ones without a reason.
- Debug builds install as `io.github.hanthor.notesoffruit.debug`, next to any
  release install.

## The A/B-against-iCloud.com method

When something doesn't work, the fastest way to find out what Apple expects is
to make Apple's own client do the same thing and diff the bytes.

1. Create two test notes with the same content: one from this app (A) and one
   from iCloud.com in a desktop browser (B). iCloud.com is the best reference
   because it is also a CloudKit Web Services client, so its payloads have the
   same shape ours do. A Mac or iPhone is the second-best reference.
2. Do the operation under test on both (append a line, make a heading, tick a
   checkbox, delete a word…).
3. Dump both notes' protos with `DUMP_BY_TITLE` (below) and compare the
   `DUMP_SUMMARY`, `DESC`, `AR` (attribute runs) and `TREE` (substring tree)
   lines. Look at the record fields too (`DUMP_RECORD`): which fields
   iCloud.com sends matters as much as their values.
4. Change our output until it matches iCloud.com's *shape*, not just its field
   names.
5. Wait. Mac's notesync validates records asynchronously, sometimes minutes
   later, and may silently move a note it dislikes to Recently Deleted. Quit
   and reopen Notes on the Mac, wait, and check again before declaring
   success.

Sample several healthy notes (Mac-created and iCloud.com-created) before
drawing conclusions; a single difference between one pair of notes is often a
coincidence.

## Capturing a note's proto with the debug receiver

Debug builds include `DebugReceiver`
([`app/src/debug/`](app/src/debug/java/com/example/applenotes/debug/DebugReceiver.kt)),
which runs iCloud operations from `adb` without the UI. Sign in once in the app
first. Then:

```bash
PKG=io.github.hanthor.notesoffruit.debug
adb logcat -c
adb shell am broadcast -p $PKG -a com.example.applenotes.DUMP_BY_TITLE --es title 'fixture-checklist'
adb logcat -d -s AppleNotesDebug:* > dump.log
```

`title` matches the first note whose title *contains* that text (case
insensitive), so give test notes distinctive titles. The log contains:

- `DUMP_KIND`, `DUMP_SUMMARY`: what kind of proto it is and a one-line summary.
- `DUMP_B64[i/n] …` between `DUMP_B64_BEGIN` and `DUMP_B64_END`: the raw
  `TextDataEncrypted` value (base64 of gzip- or zlib-compressed protobuf),
  split into chunks so logcat doesn't truncate it.
- `DESC`, `AR`, `TREE` lines: decoded views of the document, attribute runs and
  substring tree.

To turn it into a fixture (the decompressed protobuf bytes, the same format as
the existing `app/src/test/resources/*.proto` files):

```bash
python3 - dump.log app/src/test/resources/fixture-checklist.proto <<'EOF'
import base64, re, sys, zlib
chunks = {}
for line in open(sys.argv[1], encoding="utf-8"):
    m = re.search(r"DUMP_B64\[(\d+)/\d+\] (\S+)", line)
    if m:
        chunks[int(m.group(1))] = m.group(2)
data = base64.b64decode("".join(chunks[i] for i in sorted(chunks)))
# wbits=47 auto-detects gzip or zlib headers.
open(sys.argv[2], "wb").write(zlib.decompress(data, 47))
EOF
```

`DUMP_RECORD --es recordName <name>` logs every string field of a record the
same way (`FIELD_B64` lines), for looking at non-body fields such as
`ReplicaIDToNotesVersionDataEncrypted` or table `MergeableDataEncrypted`.

## Scrubbing personal text before committing a fixture

Fixtures are committed to a public repository. Before you add one:

- **Prefer synthetic notes.** Create a dedicated test note containing only
  made-up text (`fixture one`, `alpha beta gamma`) and capture that. This is by
  far the safest option.
- **The proto keeps deleted text.** Characters you typed and deleted are
  usually still stored as tombstoned substrings. A note that *looks* clean may
  still contain what you typed first. Start from a fresh note rather than
  editing an old one down.
- **Check what's in it** before committing:

  ```bash
  strings -n 3 app/src/test/resources/fixture-checklist.proto | less
  ```

  Look for names, email addresses, URLs (links are stored in the attribute
  runs), and attachment identifiers you don't want public.
- **If you must scrub a real note**, replace each personal ASCII character with
  another ASCII character (for example `x`) *in place*, keeping the byte length
  identical, so every protobuf length prefix and every CRDT substring length
  stays valid. Don't delete or insert bytes. Then run the tests to make sure
  the fixture still parses.
- Never commit cookies, tokens, `dsid`s, or full CloudKit responses from
  `adb logcat`; the client logs can contain account identifiers.
- Don't commit protos captured from other people's notes (shared or
  collaborative notes included).

## Pull request expectations

The [pull request template](.github/pull_request_template.md) has the
checklist. In short:

- Tests added or updated where it makes sense (proto changes should come with a
  fixture-based test).
- `./gradlew :app:testDebugUnitTest :app:lintDebug` passes.
- **Anything that touches the write path** (proto encoding, CloudKit
  modify/create/delete, title/snippet computation, replica handling) needs a
  manual round-trip check: make the change from this app, then open the note
  on a Mac (quit and reopen Notes, wait a few minutes) or on iCloud.com, and
  confirm the content and formatting survive and the note is not moved to
  Recently Deleted. Describe what you did in the PR.
- Docs updated: if you learned something about the format, write it down in
  the relevant file under [`docs/`](docs/).

## Licence

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE), the project's licence.
