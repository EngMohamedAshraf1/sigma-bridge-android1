# Changelog

All notable changes to Sigma Bridge Android are recorded here.

## [0.8.8] — Private Chat message actions

Release tag:

```text
v0.8.8-private-chat-message-actions
```

Release commit:

```text
0f41f0a95ae4e5b02ffc52944cb59200e24ac223
```

Release status: published on GitHub and marked as Latest

### Private Chat

- Added single-tap message actions in the Private Chat UI.
- Added `Translate to <selected chat language>` using the existing Private Chat translation path.
- Added `Copy` for the currently displayed message text.
- Bound manual translation requests to the target language captured at the moment the user taps Translate.
- Recorded `translatedToLanguage` with each local translation result so cached translations are only reused when they match the current chat language.
- Preserved an existing translation when a manual retry fails.
- Kept Telegram isolated and unchanged.
- No Supabase schema changes and no user/message/conversation data deletion.

### Update system

- Updated the application metadata to:
  - `versionCode = 8`
  - `versionName = 0.8.8`
- The release tag is intentionally `v0.8.8-private-chat-message-actions`; the update checker normalizes tags by removing the leading `v` and release suffix, so the installed version `0.8.8` compares equal to the release version `0.8.8` and does not loop on the same release.

### Release artifact

- Published `sigma-bridge.apk` as the official v0.8.8 release asset.
- APK size: `20,762,956` bytes.
- APK SHA-256:

```text
d6fa89b16c5a97e5b3df5722ba6846ab2af26ad11b9089e7abadff75754faf46
```

## [0.8.7] — Private Chat stable release

Release tag:

```text
v0.8.7-private-chat-stable
```

Release commit:

```text
37054fa02f8a91d2f4e582b87756479013e73bb8
```

Release date: 2026-09-10

### Private Chat

- Improved background message handling for conversations outside the currently open chat.
- Isolated background sending and delivery/read receipts by conversation partner.
- Improved Supabase authentication checks for background message delivery.
- Preserved the active conversation while background services process other conversations.
- Maintained asynchronous translation and message history behavior.
- Fixed the Private Chat background transport isolation issues identified after v0.8.6.

### Update system

- Updated the application metadata to:
  - `versionCode = 7`
  - `versionName = 0.8.7`
- This keeps the embedded application version synchronized with the GitHub release version and prevents same-version update loops.

### Release artifact

- Published `sigma-bridge.apk` as the official v0.8.7 release asset.
- APK size: `20,747,384` bytes.
- APK SHA-256:

```text
5f671b7d99957cbfc411c72ee558cfdd0c0a22d3067d0719f8cf067864b004b0
```

### Scope boundary

- This release covers **Private Chat only**.
- Telegram functionality was not changed as part of this release.
- No Supabase database schema changes were made.
- No user accounts, devices, conversations, messages, receipts, or other Supabase data were deleted.

## [0.8.6] — Private Chat stability baseline

### Private Chat

- Preserved the working 1-to-1 Private Chat flow.
- Added explicit translation state to `ChatMessage` with `PENDING`, `COMPLETED`, and `FAILED`.
- Incoming messages are displayed from their original decrypted text immediately instead of waiting for translation.
- Incoming translation runs asynchronously and updates the same message when it succeeds.
- Translation failures preserve the original message text.
- Read receipt submission is independent of translation and is triggered as soon as the incoming message is received/visible.
- Preserved conversation-scoped local history, outbox, unread state, and conversation previews.
- Preserved background inbox/notification processing and retry behavior.
- Corrected the `fetchReceipts()` Kotlin syntax in the 6bb07de-based release line.

### Update system

- Aligned the application metadata to:
  - `versionCode = 6`
  - `versionName = 0.8.6`
- This alignment is required because the update checker compares `BuildConfig.VERSION_NAME` against the GitHub latest release tag.

### Documentation

- Added a developer handoff guide.
- Added architecture, Private Chat, Supabase, translation, update-system, development, and troubleshooting documentation.
- Replaced the historical README with a current-project entry point.

### Scope boundary

- Telegram code was not intentionally modified as part of this Private Chat work.
- No user accounts, devices, conversations, messages, receipts, or other Supabase data were deleted.

## [0.8.5]

Previous stable application baseline before the Private Chat simplification work documented above.

## Notes on version history

The original GitHub `v0.8.6` tag was created at the `6624abf` commit before the application version metadata was corrected. The development branch later received `b2eea8d`, changing the embedded application version to 0.8.6. For release engineering, the APK that is distributed as the authoritative 0.8.6 binary must be rebuilt from a source tree that reports versionName 0.8.6/versionCode 6.
