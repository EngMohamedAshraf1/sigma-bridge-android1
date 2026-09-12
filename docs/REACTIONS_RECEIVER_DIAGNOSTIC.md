# Reactions receiver diagnostic

Observed behavior: the reacting device shows its own reaction, while the other device does not.

Database verification on 2026-09-12 showed reaction rows are being persisted in `public.message_reactions`, so the write path is working.

The reaction ViewModel previously connected only once during initialization. If `ChatIdentity.partnerId` was still blank when the ViewModel was created, `connect()` returned and no later connection/fetch was started. The local device could still display its own reaction optimistically, which made the feature appear to work only on the sender.

The fix is to make the reaction ViewModel detect the partner becoming available and then establish the realtime subscription plus initial reaction snapshot. Existing message transport, encryption, reply, translation, receipts, and Telegram paths are not changed.
