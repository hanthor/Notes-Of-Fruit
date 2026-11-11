# Design decisions

These decisions were made while building the first feature set (originally
section 4, "CEO decisions", of `PLAN.md`). They are kept as a historical
record: most of the features they were about have shipped, but the reasoning
still explains why the code looks the way it does. Don't relitigate them
without a reason; if one turns out wrong, open an issue and update this file.

Notes added later:

- Q2: auto-save shipped as lifecycle-only, as decided (the `ON_PAUSE`
  observer in `NoteDetailScreen`).
- Q6: the FAB shipped.
- Q8: the *application ID* has since changed to
  `io.github.hanthor.notesoffruit` (issue #19). The Kotlin packages, Gradle
  namespace and DebugReceiver action names are still `com.example.applenotes`,
  so the source-rename cost described below still applies.

## Q1: Markdown or Apple's native attribute_runs?

**Decision: native attribute_runs.** Markdown looks like a shortcut, but
because Mac/iCloud renders attribute_runs (not markdown), any markdown we
write would show literal `**` and `#` on every other device. The whole point
of Notes of Fruit is bidirectional fidelity with iCloud. Bite the bullet on
attribute_runs.

We'll accept partial coverage — decode/render the **paragraph styles** Apple
uses most (title, heading, subheading, body, monospace, bullet, numbered,
checkbox) and ignore the rare ones (color, highlight, custom fonts) for v1.
Round-trip perfect on the common 90% beats markdown 100% of the time.

## Q2: Auto-save — debounce or lifecycle-only?

**Decision: lifecycle-only for v1.** A debounced "save 2s after last
keystroke" save is more network traffic and forces us to handle racing saves.
For v1, save on:
- back press (before leaving the screen)
- app pause / stop (lifecycle ON_PAUSE)
- explicit save tap (already there, becomes optional)

Add a tiny "Saved Xs ago" pill in the top app bar so the user knows.

## Q3: New-note flow — empty body, or a default template?

**Decision: empty body with auto-focus on the body editor.** No "Untitled"
placeholder in the title bar — it stays blank until they type a first line,
which becomes the title (matches Apple Notes behavior).

## Q4: Share OUT — plaintext only, or keep formatting?

**Decision: plaintext only for v1.** Simplest to implement, maximum
compatibility with target apps (Messages, email, etc). v2 could add "share
as Markdown" or "share as PDF" if there's demand.

## Q5: Share IN — implement now or defer?

**Decision: defer to v2.** It's `<intent-filter android:name="android.intent.action.SEND"`
plus a "create note from incoming text" flow plus Android Share-Sheet target
registration. Not hard, but the new-note FAB already gives a fast path. Don't
need Share IN yet.

## Q6: New-note FAB or top-bar button?

**Decision: FAB.** That's the Material 3 idiom for "primary create action".
The icon (Add) is already imported; FloatingActionButton is already imported.
Two-line wire-up.

## Q7: Markdown vs attribute_runs — what about iOS-typed notes that use **bold**?

**Decision: ignore for v1.** Apple users don't actually type `**bold**` —
they use the format toolbar. Existing Apple Notes don't have markdown syntax
in their body bytes; they have attribute_runs. Verified by sampling Jan 26
todos, Money maker, Oct 2025 plan, Core Tenets, etc.: all plain text bodies
with attribute_run paragraph-style metadata.

## Q8: Package rename (`com.example.applenotes` → `com.example.notesoffruit`)?

**Decision: defer.** Cost: rewrite every Kotlin import, change the
DebugReceiver action names (which our test scripts rely on), and force
existing emulator installs to re-bootstrap (they'd see it as a new app and
lose stored device UUID). Benefit: cosmetic. Wait until we have Play Store
distribution; then rename once.

## Q9: Markdown-style markdown rendering as a stopgap until attribute_runs are decoded?

**Decision: no.** Conflicts with Q1. Doing both means user sees `**bold**`
literal on Mac side AND headings rendered in our app — inconsistent
mental model.
