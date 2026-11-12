## What and why

<!-- What does this change and why? Link the issue: "Fixes #123". -->

## Checklist

- [ ] Tests added or updated (proto changes come with a fixture-based test;
      fixtures scrubbed of personal text, see CONTRIBUTING.md)
- [ ] `./gradlew :core:test :cli:test :app:testDebugUnitTest :app:lintDebug` passes locally
- [ ] Touches the write path (proto encoding, CloudKit create/modify/delete,
      title/snippet, replica handling)? Round-trip verified on a Mac or on
      iCloud.com, described below. Otherwise: not applicable
- [ ] Docs updated (`docs/`, README) if behaviour or format knowledge changed

## Round-trip check

<!--
For write-path changes: what you did in this app, where you checked the note
(Mac Notes after quit/reopen, or iCloud.com), how long you waited, and what you
saw. Mention the kind of note (Mac/iOS/iCloud.com-created, checklist, table…).
-->
