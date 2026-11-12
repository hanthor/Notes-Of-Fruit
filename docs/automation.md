# Automation intent API

Notes of Fruit has a small intent API for automation apps (Tasker, MacroDroid,
Automate, …): create a note, append text to a note, open a note. It is
**off by default** and writes straight to your iCloud account, so read the
whole page before turning it on.

There is no delete, overwrite or read-back action, on purpose.

## Turning it on

Three things have to be true before a call does anything:

1. **The toggle.** In the app: drawer → **Settings** → **Allow automation
   apps**. While it is off, every call fails with `Automation is turned off…`;
   nothing is recorded and nothing touches iCloud. On Android 13+ turning it on
   asks for the notification permission (used only for the approval
   notification below; you can decline).
2. **Notes of Fruit can tell which app is calling.** See
   [How the caller is identified](#how-the-caller-is-identified). A call whose
   sender can't be identified is refused with an error saying so.
3. **You allowed that app.** The first call from an app you haven't allowed
   does nothing except remember that the app asked (its package name, its name
   as shown in Android, its signing certificate and when it first asked) and
   show a notification: *"‹App› wants to add notes — Review"*. The request
   itself (title, text, folder) is **not stored and not replayed**. Open
   **Settings → Allowed apps**, tap **Allow**, then run your task again.

**Allowed apps** lists the allowed apps and the ones waiting, with **Allow**,
**Dismiss** and **Remove**. An app is trusted by its package name **and** the
SHA-256 of its signing certificate(s): if you uninstall it and something else
gets installed under the same package name, or the app changes its signing
key, it is refused and has to ask again (the error says the certificate
changed). The list is stored in the app's private DataStore and is never
backed up, so a restored or new phone trusts no app.

What allowing an app means: **everything that app can do, it can now do to your
notes**. Allowing Tasker allows every Tasker task and plugin, and anything that
can make Tasker run a task. Only allow apps you trust.

## How the caller is identified

Android doesn't attach a trustworthy sender to every intent. What the app can
use depends on how it was called and on the Android version:

| Source | Android | Used by | Trust |
|---|---|---|---|
| `Activity.getLaunchedFromPackage()` | 14+ | activity | Only set when the caller opted in with `ActivityOptions.setShareIdentityEnabled(true)`. Automation apps don't (as far as we know), so usually empty. |
| `Activity.getCallingPackage()` | all | activity | Set by the system **only** for `startActivityForResult`. Can't be forged. |
| System referrer (`Activity.getReferrer()`) | **11+** | activity | The package that called `startActivity`, filled in by the system. From Android 11 the system checks that package name against the caller's uid (`ActivityTaskManagerService.assertPackageMatchesCallingUid`), so it can't be faked that way. A caller *can* override `getReferrer()` with `Intent.EXTRA_REFERRER` / `EXTRA_REFERRER_NAME`, so if either extra is present the referrer is ignored. On Android 8–10 the package name wasn't checked, so the referrer is not used there. |
| `BroadcastReceiver.getSentFromPackage()` | 14+ | receiver | Only set when the sender opted in with `BroadcastOptions.setShareIdentityEnabled(true)`. Stock automation apps don't. |
| `result_pending_intent`'s `PendingIntent.getCreatorPackage()` | all | both | The package that created the PendingIntent. Can't be forged, but a PendingIntent *can be handed on*: app B could pass a PendingIntent that app A created. So when the activity also has one of the sources above and it names a different app, the call is refused. A broadcast has nothing to compare against; see [Limits](#limits-and-what-isnt-verified). |

The activity uses the first of launched-from → calling package → referrer
(11+) → PendingIntent creator. The receiver uses sent-from (14+, opt-in) →
PendingIntent creator. **The receiver therefore requires `result_pending_intent`**
in practice; without it a broadcast is refused (as an ordered broadcast's
result, since there's nowhere else to send the error).

Note: Android's documentation says `getLaunchedFromPackage()` returns the
launching app only when the launcher opted in (or for same-uid / platform
callers). It is **not** filled in for an ordinary `startActivity` on Android
14+; the system referrer is what makes plain `startActivity` usable.

The identified package is then looked up in PackageManager for its name and
signing certificates. On Android 11+ apps are only visible to each other when
declared; Notes of Fruit declares visibility of apps with a launcher icon
(which covers the automation apps and most apps you'd build) rather than
`QUERY_ALL_PACKAGES`. An app without a launcher icon can't be looked up and is
refused (`can't look up the calling app`). Notes of Fruit never lists your
installed apps; it only looks up the one that called.

### What works where

| | Android 8–10 (API 26–29) | Android 11–13 (API 30–33) | Android 14+ (API 34+) |
|---|---|---|---|
| **Tasker** — Send Intent, Target **Activity** | ✗ (can't be identified) | ✓ (referrer) | ✓ (referrer) |
| **MacroDroid** — Send Intent, Target **Activity** | ✗ | ✓ (referrer) | ✓ (referrer) |
| **Automate** — **App decision** block (starts for result) | ✓ (calling package) | ✓ | ✓ |
| **Automate** — App start block (no result) | ✗ | ✓ (referrer) | ✓ (referrer) |
| Tasker / MacroDroid / Automate — Target **Broadcast** | ✗ (no PendingIntent) | ✗ | ✗ |
| Your own app — broadcast with `result_pending_intent` | ✓ | ✓ | ✓ |
| Your own app — `startActivityForResult` | ✓ | ✓ | ✓ |
| Your own app — broadcast with `BroadcastOptions.setShareIdentityEnabled(true)` | — | — | ✓ |

Stock automation apps can't build a `PendingIntent` extra in their "send
intent" actions, which is why the activity also accepts CREATE_NOTE and
APPEND_TO_NOTE. On Android 8–10, Tasker and MacroDroid users are out of luck
unless they use something that starts the activity *for a result* (Automate's
App decision block does).

Starting an activity from the background: Android 10+ only lets apps start
activities from the background in some cases. Tasker and MacroDroid can when
they have **Display over other apps** permission (both ask for it); Automate
needs its equivalent privilege.

### Why not a permission?

Earlier versions guarded the API with a custom `dangerous` permission
(`…permission.AUTOMATE`). An app can only request a permission its own
manifest declares, so Tasker, MacroDroid and Automate could never hold it and
the API was unusable from them. Issue #21 replaced it with this allow-list.

## Actions

All actions and extras are plain strings. Use **explicit** intents (set the
package to `io.github.hanthor.notesoffruit`).

| Action | Target | Extras |
|---|---|---|
| `io.github.hanthor.notesoffruit.action.CREATE_NOTE` | activity, or broadcast with `result_pending_intent` | `title` (optional, one line), `text` (optional; at least one of the two), `folder` (optional folder name) |
| `io.github.hanthor.notesoffruit.action.APPEND_TO_NOTE` | activity, or broadcast with `result_pending_intent` | `record_name` **or** `title` (exact note title), `text` (required) |
| `io.github.hanthor.notesoffruit.action.OPEN_NOTE` | activity | `record_name` **or** `title` |

Activity component: `io.github.hanthor.notesoffruit/com.example.applenotes.automation.AutomationActivity`.
Receiver: `io.github.hanthor.notesoffruit/com.example.applenotes.automation.AutomationReceiver`.
(The class names keep the old `com.example.applenotes` namespace; only the
application ID changed.) Debug builds use package
`io.github.hanthor.notesoffruit.debug`, with the same class names.

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
- **OPEN_NOTE** brings the app up on that note.

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
| `error` | string | what went wrong (on failure), e.g. not allowed yet, can't identify the caller |

The PendingIntent must be created with `FLAG_MUTABLE`, or Android won't add the
extras. Its result code is `RESULT_OK` / `RESULT_CANCELED`.

- **Ordered broadcast:** the same outcome is also set as the broadcast result
  (code, and data = record name or error).
- **Activity started for a result** (e.g. Automate's App decision): the
  activity finishes right away, so
  - refusals and OPEN_NOTE: `RESULT_OK` / `RESULT_CANCELED` with the extras
    above;
  - accepted CREATE/APPEND: `RESULT_OK` with `accepted=true` only. The save
    happens after the activity has finished; its outcome (and `record_name`)
    is only reported through `result_pending_intent`.

## Tasker (Android 11+)

Task → **System → Send Intent**:

*Create a note*

- Action: `io.github.hanthor.notesoffruit.action.CREATE_NOTE`
- Extra: `title:Shopping %DATE`
- Extra: `text:%CLIP`
- Extra: `folder:Notes`
- Package: `io.github.hanthor.notesoffruit`
- Class: `com.example.applenotes.automation.AutomationActivity`
- Target: **Activity**

*Append a line* — same, with Action
`io.github.hanthor.notesoffruit.action.APPEND_TO_NOTE`, extras
`title:Journal` and `text:%TIME %par1`.

*Open a note* — Action `io.github.hanthor.notesoffruit.action.OPEN_NOTE`,
extra `title:Journal`, Target **Activity**.

Run the task once: you get the "Tasker wants to add notes" notification and
nothing is saved. Allow Tasker under Settings → Allowed apps, run it again.
Tasker gets no result back (Send Intent doesn't start for a result); check the
note.

## MacroDroid (Android 11+)

Action → **Send Intent**: Target **Activity**, Package
`io.github.hanthor.notesoffruit`, Class
`com.example.applenotes.automation.AutomationActivity`, Action and extras as
for Tasker. Approve MacroDroid after the first run, as above.

## Automate (all supported versions)

Use the **App decision** block (it starts the activity for a result, which
identifies Automate on every Android version): Package
`io.github.hanthor.notesoffruit`, Activity class
`com.example.applenotes.automation.AutomationActivity`, Action as above,
Extras e.g. `{"title": "Journal", "text": "hello"}`. YES means accepted (or
opened), NO means refused; *Result extras* holds `error`. On Android 11+ the
**App start** block works too.

## Your own app

Send a broadcast with a `result_pending_intent` you created (that's how the
receiver knows it's you), or start the activity for a result:

```kotlin
val reply = PendingIntent.getBroadcast(
    context, 0, Intent(context, MyResultReceiver::class.java),
    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
context.sendBroadcast(
    Intent("io.github.hanthor.notesoffruit.action.CREATE_NOTE")
        .setPackage("io.github.hanthor.notesoffruit")
        .putExtra("title", "From my app")
        .putExtra("text", "hello")
        .putExtra("result_pending_intent", reply),
)
```

Your app needs a launcher activity (or Notes of Fruit can't look it up on
Android 11+, see above).

## From adb (testing)

`adb shell` runs as `com.android.shell`. **Debug builds** declare visibility
of that package, so on Android 11+ the activity identifies it and it can be
approved under Allowed apps like any other app (release builds can't see it,
so this doesn't work there):

```bash
adb shell am start -n io.github.hanthor.notesoffruit.debug/com.example.applenotes.automation.AutomationActivity \
  -a io.github.hanthor.notesoffruit.action.CREATE_NOTE \
  --es title 'From adb' --es text 'hello'
```

`am broadcast` can't attach a PendingIntent, so broadcasts from adb are always
refused. For protocol work, use the debug build's `DebugReceiver`
(docs/advice.md).

## Limits and what isn't verified

- **Not yet tried on a device.** In particular: that Tasker's, MacroDroid's and
  Automate's launches arrive with the system referrer (no `EXTRA_REFERRER`),
  that the launcher-icon `<queries>` makes them visible, and Automate's App
  decision result handling. The rules are unit-tested against faked signals.
- **Android 8–10 and the referrer:** not used, because the system didn't check
  the calling package then. Plain `startActivity` callers (Tasker, MacroDroid)
  can't be identified there.
- **PendingIntents can be handed on.** A broadcast is identified only by its
  PendingIntent's creator. An app that got hold of a PendingIntent
  created by an allowed app (for example from that app's notifications, if it
  can read them) could send it to the receiver and act as that app; it would
  also fire that PendingIntent with our result. The activity refuses a
  PendingIntent from a different app than the one that launched it, which
  closes this for activity callers on Android 11+.
- **Shared user IDs:** apps that share a uid are indistinguishable to the
  system; the package Android reports is the one that's checked.
- **Process death after the activity finishes:** for CREATE/APPEND through
  the activity, the save runs after the (invisible) activity has finished. If
  Android kills the app's process in the meantime, the save can be lost and
  only `result_pending_intent` would have told you (it won't be sent).
- Allowing an app trusts its current signing certificate(s); after a key
  rotation the app has to be allowed again.

## Code

`app/src/main/java/com/example/applenotes/automation/`: `AutomationApi.kt`
(names + request parsing/validation), `CallerResolution.kt` (which signal
identifies the caller, per API level), `CallerSignalsAndroid.kt` (reading those
signals, PackageManager certificate lookup), `AllowList.kt` (allow-list rules),
`AllowListStore.kt` (DataStore), `AutomationAccess.kt` (the check + pending
requests), `AutomationNotifier.kt` (the notification), `AutomationProcessor.kt`
(toggle, access, create/append via `NotesRepository.saveNote`),
`AutomationReceiver.kt`, `AutomationActivity.kt`, `AutomationSettings.kt` (the
toggle). UI: `ui/settings/AllowedAppsScreen.kt`. Tests:
`app/src/test/.../automation/AutomationTest.kt` and
`AutomationAllowListTest.kt`.
