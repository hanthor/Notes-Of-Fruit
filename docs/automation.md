# Automation intent API

Notes of Fruit has a small intent API for automation apps (Tasker, MacroDroid,
Automate, …): create a note, append text to a note, open a note. It is
**off by default** and writes straight to your iCloud account, so read the
whole page before turning it on.

There is no delete, overwrite or read-back action, on purpose.

## Turning it on

Two separate things have to be true before any call is accepted:

1. **The toggle.** In the app: drawer → **Settings** → **Allow automation
   apps**. While it is off, every call fails with
   `Automation is turned off…` and nothing touches iCloud.
2. **The permission.** The calling app must hold
   `io.github.hanthor.notesoffruit.permission.AUTOMATE` (debug builds:
   `io.github.hanthor.notesoffruit.debug.permission.AUTOMATE`). It is a
   `dangerous` permission: the calling app has to declare
   `<uses-permission android:name="io.github.hanthor.notesoffruit.permission.AUTOMATE"/>`
   in *its* manifest and you grant it in that app's permission screen. Android
   drops intents from apps without it before they reach Notes of Fruit.

> **Caveat:** an app can only request a permission that is declared in its own
> manifest. General-purpose automation apps (Tasker, MacroDroid, Automate)
> do not declare this one, so as of writing **they cannot hold it and their
> calls are rejected by Android**. Ways to use the API today: an app you build
> yourself (for example a Tasker *App Factory* export, if you can add the
> `uses-permission` line), or any small companion app that declares it. If the
> stock apps should work directly, the protection model needs a different
> design (for example a per-caller allow-list in Settings) — open an issue.

## Actions

All actions and extras are plain strings. Use **explicit** intents (set the
package to `io.github.hanthor.notesoffruit`).

| Action | Target | Extras |
|---|---|---|
| `io.github.hanthor.notesoffruit.action.CREATE_NOTE` | broadcast | `title` (optional, one line), `text` (optional; at least one of the two), `folder` (optional folder name) |
| `io.github.hanthor.notesoffruit.action.APPEND_TO_NOTE` | broadcast | `record_name` **or** `title` (exact note title), `text` (required) |
| `io.github.hanthor.notesoffruit.action.OPEN_NOTE` | activity | `record_name` **or** `title` |

Every action also accepts `result_pending_intent` (a `PendingIntent`).

- **CREATE_NOTE** creates the note through the same deferred-create path as the
  editor's first save (title = first line, `text` below it). `folder` matches a
  folder's display name, ignoring case (`Notes` = the default folder). An
  unknown folder is an error. A folder that has no notes yet can't be targeted
  (the folder reference is copied from an existing note in it).
- **APPEND_TO_NOTE** loads the note, adds `text` as new plain paragraph(s) at
  the end (existing formatting is kept) and saves through the editor's normal
  modify path. It refuses notes in Recently Deleted, notes this app can only
  open read-only (newer Apple formats), and titles that match zero or several
  notes (use `record_name` then).
- **OPEN_NOTE** brings the app up on that note. It is an activity because
  Android doesn't let a background broadcast receiver start activities.

Limits: `text` up to 100 000 characters, `title` up to 1 000. Calls time out
after 45 s (the save may still finish).

If you're not signed in, CREATE/APPEND fail with `Not signed in…`; open the app
and sign in once.

## Results

Results never go to logcat. If you pass `result_pending_intent`, it is sent
with these extras filled in:

| Extra | Type | Meaning |
|---|---|---|
| `success` | boolean | whether the call worked |
| `record_name` | string | the note's CloudKit recordName (on success; for OPEN_NOTE only when you passed one) |
| `error` | string | what went wrong (on failure) |

The PendingIntent must be created with `FLAG_MUTABLE`, or Android won't add the
extras. Its result code is `RESULT_OK` / `RESULT_CANCELED`. For an *ordered*
broadcast the same outcome is also set as the broadcast result (code, and data
= record name or error).

## Tasker example

(Subject to the permission caveat above.)

*Create a note* — Task → **System → Send Intent**:

- Action: `io.github.hanthor.notesoffruit.action.CREATE_NOTE`
- Extra: `title:Shopping %DATE`
- Extra: `text:%CLIP`
- Extra: `folder:Notes`
- Package: `io.github.hanthor.notesoffruit`
- Target: **Broadcast Receiver**

*Append a line* — same, with Action
`io.github.hanthor.notesoffruit.action.APPEND_TO_NOTE`, extras
`title:Journal` and `text:%TIME %par1`.

*Open a note* — Action `io.github.hanthor.notesoffruit.action.OPEN_NOTE`,
extra `title:Journal`, Target: **Activity**.

## From adb (testing)

`adb shell` can't hold the permission either, so for development use the
debug build's `DebugReceiver` (docs/advice.md) or a test app that declares
`uses-permission`. The equivalent call, for reference:

```bash
adb shell am broadcast -p io.github.hanthor.notesoffruit \
  -a io.github.hanthor.notesoffruit.action.CREATE_NOTE \
  --es title 'From adb' --es text 'hello'
```

## Code

`app/src/main/java/com/example/applenotes/automation/`: `AutomationApi.kt`
(names + request parsing/validation), `AutomationProcessor.kt` (gate, sign-in,
create/append via `NotesRepository.saveNote`), `AutomationReceiver.kt`,
`AutomationActivity.kt`, `AutomationSettings.kt` (the toggle). Tests:
`app/src/test/.../automation/AutomationTest.kt`.
