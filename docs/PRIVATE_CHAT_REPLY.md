# Private Chat — Reply

## Scope

This document records the Telegram-style **Reply** feature added to the Private Chat implementation.

## Behavior

- Selecting a message opens the existing message-actions menu.
- `Reply` is the first action in that menu.
- Selecting `Reply` shows a compact quoted preview above the composer.
- Sending the message attaches the selected message ID as reply metadata.
- The received message renders a compact quoted preview before its own text when the referenced message is available locally.
- If the referenced message is not currently in the local 200-message history, the reply still carries the relationship; the UI shows `Original message unavailable` until the referenced message is available.
- Clearing the reply removes only the pending reply selection and does not affect the message composer text.

## Compatibility and safety

- Existing message delivery, Delivered/Read receipts, translation, alternative translation, outbox, and conversation routing are unchanged.
- Existing messages remain valid because reply metadata is optional.
- Reply metadata is carried inside the existing AES-GCM encrypted message payload, so no plaintext reply information is added to Supabase rows.
- Existing message encryption remains backward-compatible: messages without reply metadata continue to decrypt normally.
- The database schema and existing `sigma_send_message` RPC contract are unchanged.
- Telegram remains outside the Private Chat implementation boundary.

## Data flow

```text
Message actions
    -> Reply
    -> pending reply message ID
    -> existing ChatViewModel.send(...)
    -> existing Supabase send path
    -> encrypted payload includes optional reply metadata
    -> receiver decrypts payload
    -> reply relationship stored locally
    -> quoted preview rendered in the existing ChatScreen
```

## Current implementation files

- `ChatReplyStore.kt` — local reply relationship and pending-reply storage.
- `ChatCrypto.kt` — optional encrypted reply metadata encoding/decoding.
- `SupabaseChatRepository.kt` — attaches and recovers reply metadata through the existing message send/receive path.
- `ChatScreen.kt` — Reply action, composer preview, and quoted message rendering.
- `ChatMessage.kt` — optional `replyToMessageId` field for received/domain messages.

## Verification target

The intended UX is deliberately close to Telegram's basic message reply flow while avoiding any changes to the established Private Chat transport and translation paths.
