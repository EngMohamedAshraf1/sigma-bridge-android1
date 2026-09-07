# Changelog

All notable changes to Sigma Bridge Android are recorded here.

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
