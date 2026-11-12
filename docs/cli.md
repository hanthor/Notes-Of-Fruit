# nof-cli: inspecting captured protos on your computer

`nof-cli` is a small JVM command-line tool (the `:cli` Gradle module, built on
`:core`) that decodes a captured note body the same way the app does. Use it
to look at what Apple's clients (or this app) wrote, and to diff two captures,
without a phone attached. It reads files only: **it has no network code** and
never talks to iCloud.

## Build and run

```bash
./gradlew :cli:installDist
cli/build/install/nof-cli/bin/nof-cli --help

# or, without installing (relative paths resolve from the project root):
./gradlew -q :cli:run --args="summary core/src/testFixtures/resources/fmt-baseline-1.proto"
```

Needs a JDK 17 or newer (Gradle provisions one for the build; `installDist`'s
launcher uses `JAVA_HOME` or `java` on the `PATH`).

## Inputs

Every command takes a file (or `-` for standard input). The format is
detected from the content, not the file name:

| Input | Example |
|---|---|
| Raw protobuf | `core/src/testFixtures/resources/*.proto` |
| gzip or zlib compressed protobuf | `TextDataEncrypted` as CloudKit stores it, after base64 decoding |
| Base64 of either | the `value` of a `TextDataEncrypted` field in a CloudKit JSON response; line breaks are fine |
| A logcat capture of `DebugReceiver` | `adb logcat -d -s AppleNotesDebug:* > dump.log` after `DUMP_BY_TITLE` (the `DUMP_B64[i/n]` chunks are reassembled, in any order) or after `DUMP_RECORD` (`FIELD_B64 <name> [i/n]` lines; `TextDataEncrypted` by default, `--field NAME` for another field such as `MergeableDataEncrypted`) |

A capture with a chunk missing (logcat drops lines under load) is refused
with the missing indices: capture again.

## Commands

| Command | What it prints |
|---|---|
| `summary <file>` | How the input was wrapped, the one-line wire-shape summary the app logs (`NoteBodyEditor.summarize`: ops, replica registry with counters, attribute-run count) and substring / tombstone / replica counts |
| `tree <file>` | The substring tree: one line per substring with char ID, length, tombstone, style timestamp and children, then the sentinel's parents (flags a fork) |
| `runs <file>` | Every attribute run's raw fields, then the decoded runs: length, paragraph style, inline styles, link, attachment type and the text each covers |
| `text <file>` | The visible text, as the app shows it |
| `diff <a> <b>` | A structural diff keyed by CRDT char IDs (resolved through each capture's replica table): chars **added** and **tombstoned**, grouped into runs of consecutive clocks per replica with their text; any char that **vanished** or was **resurrected** (both are CRDT violations); attribute-run counts and formatting changes on chars present in both; each replica's clock counters before and after |
| `check <file>` | Runs `TopotextInvariants.checkInvariants`, the same checker every write-path unit test runs (doc-start and sentinel, forward child links, the walk reaching the sentinel, live length = `String.string`, no split surrogate pairs, attribute runs covering the text exactly, attachment runs, replica registry, unique char IDs, counters past every used clock). Exits 1 with the first violation. `--strict` also requires the single linear chain in array order that this app's writers produce; notes written by Apple's clients only need the default form |
| `table <file>` | Decodes a table attachment (`MergeableDataEncrypted`) into rows and columns |
| `raw <file>` | Writes the decompressed protobuf bytes to standard output, e.g. to save a capture as a test fixture |

Exit codes: 0 success, 1 a failed check or an unreadable input, 2 a usage
error. `--verbose` prints the protocol code's own diagnostic log (CoreLog) to
standard error; it can contain note text.

## Typical session

Capture the same note before and after an edit made on a Mac (see
[CONTRIBUTING.md](../CONTRIBUTING.md#capturing-a-notes-proto-with-the-debug-receiver)
for `DUMP_BY_TITLE`):

```bash
PKG=io.github.hanthor.notesoffruit.debug
adb logcat -c
adb shell am broadcast -p $PKG -a com.example.applenotes.DUMP_BY_TITLE --es title 'fixture-checklist'
adb logcat -d -s AppleNotesDebug:* > before.log
# ... edit the note on the Mac, wait for it to sync ...
adb logcat -c
adb shell am broadcast -p $PKG -a com.example.applenotes.DUMP_BY_TITLE --es title 'fixture-checklist'
adb logcat -d -s AppleNotesDebug:* > after.log

nof-cli check before.log
nof-cli diff before.log after.log
nof-cli runs after.log
```

To keep a capture as a test fixture (read the scrubbing rules in
CONTRIBUTING.md first):

```bash
nof-cli raw after.log > core/src/testFixtures/resources/fixture-checklist.proto
nof-cli check core/src/testFixtures/resources/fixture-checklist.proto
```
